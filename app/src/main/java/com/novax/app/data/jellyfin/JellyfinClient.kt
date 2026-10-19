package com.novax.app.data.jellyfin

import com.novax.app.data.NvFileEntry
import com.novax.app.data.NvPaths
import com.novax.app.data.db.SourceEntity
import com.novax.app.player.NvQualityTier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** 清晰度会话计划：isHls=false = 原画静态流（现有管线）；isHls=true = 服务器转码 HLS。
 *  [transcodeNotNeeded] 见字段注释——「无需转码」与「转码不可用」是两种结论，UI 文案必须分流。 */
data class JellyfinStreamPlan(
    val isHls: Boolean,
    val hlsUrl: String?,
    val playSessionId: String?,
    /**
     * 2026-10-08 用户缺陷实证（海市蜃楼.E01，源码率 3.1M < 4M 档）：所选档位上限 ≥ 源码率时
     * 服务器报 SupportsTranscoding=true 但不给 TranscodingUrl——DirectPlay/DirectStream 就是
     * 该档位下的最佳画质。此为「无需转码」的正常结论；旧实现与真不可转码混为一谈，弹
     * 「转码不可用，已回退原画」误导用户以为档位坏了。
     */
    val transcodeNotNeeded: Boolean = false,
    /** 源视频码率 bps（「无需转码」文案的数字来源；未知 0） */
    val sourceVideoBitrate: Long = 0,
)

/** 内嵌字幕发现结果（M4）：文本字幕 vtt 候选 + 图形字幕存在性（直连提示用） */
data class JellyfinSubtitleDiscovery(
    val candidates: List<com.novax.app.player.SubtitleCandidate>,
    val hasImageSubtitles: Boolean,
    /** 首个视频流源码率 bps（未知 0）——清晰度面板「档位 ≥ 源码率隐藏」的判据（经 EngineSpec 透传 UI） */
    val sourceVideoBitrate: Long = 0,
)

/**
 * Jellyfin REST 客户端（spec 2026-10-07 §5）。与 SmbClient/WebDavClient 同构：
 * NvSourceRepository 门面按 source.type 路由到本类。
 *
 * - 导航：Jellyfin 无真实路径，浏览自顶向下逐级「父 id → 子条目」，路径↔itemId 记在
 *   [JellyfinPathIndex]；索引丢失（进程重启后 seek 重新 open、SavedStateHandle 恢复面包屑）
 *   时按 walkSegments 从根逐段重解析（Review Focus 1）。
 * - 播放（M2 直连）：`/Videos/{id}/stream?static=true` 静态端点，HTTP Range 随机读，
 *   与 DefaultHttpDataSource 同模型；M3 转码 HLS 在此之上另行分支。
 * - Token 只存内存（按 sourceId），401 用凭据静默重登一次（Review Focus 2）。
 */
class JellyfinClient(
    /** 连接/读超时对齐 WebDavClient */
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    /** suspend 入口（connect/list/openRead）切 IO 调度器，Main 安全（同 SmbClient/WebDavClient） */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private data class Session(val userId: String, val token: String)

    /** sourceId → 登录态（不落库；进程内缓存，401 重登自愈） */
    private val sessions = ConcurrentHashMap<Long, Session>()

    /** sourceId → 路径索引（浏览记忆；lost 时 walk 重解析） */
    private val indexes = ConcurrentHashMap<Long, JellyfinPathIndex>()

    private fun index(source: SourceEntity): JellyfinPathIndex =
        indexes.getOrPut(source.id) { JellyfinPathIndex() }

    /** 全部路径索引快照（隐私计划 Task 17 的 jf: 规则 resolver 消费：判定无源上下文，跨源查后按歧义 fail-closed） */
    fun allPathIndexes(): List<JellyfinPathIndex> = indexes.values.toList()

    fun cachedUserId(source: SourceEntity): String? = sessions[source.id]?.userId

    /**
     * 组请求用的 base URL（端口语义见 SourceEntity KDoc「JELLYFIN … port = 8096」）：
     * host 已带显式端口（authority 含 ':'，如 "http://IP:8096"）或 port 未填（<=0）时
     * 原样返回；否则在 scheme://host 后追加 ":$port"——用户分格填 host="1.2.3.4" +
     * port=8096 时若无此步会打到 :80。normalizeBaseUrl 保持纯函数层不动。
     */
    private fun baseUrlOf(source: SourceEntity): String {
        val base = JellyfinApi.normalizeBaseUrl(source.host)
        val authority = base.substringAfter("://")
        return if (authority.contains(':') || source.port <= 0) base else "$base:${source.port}"
    }

    // ---------------- connect（向导「测试连接」）----------------

    suspend fun connect(source: SourceEntity): Result<Unit> = withContext(io) {
        runCatching {
            // 向导「测试连接」必须真认证：先逐出缓存会话（可能已失效/密码已改），
            // 否则 authenticate 短路返回旧会话，改密后重测会假通过；list/播放路径的缓存语义不变
            sessions.remove(source.id)
            authenticate(source)
            Unit
        }.recoverCatching { e -> throw toIo(e, "连接 ${source.nickname}") }
    }

    // ---------------- list（浏览）----------------

    suspend fun list(source: SourceEntity, dirPath: String): Result<List<NvFileEntry>> = withContext(io) {
        runCatching {
            val session = authenticate(source)
            val dir = NvPaths.asDirectory(dirPath)          // "" / "/" → 根
            val parentId = resolveParentId(source, session, dir)
            val baseUrl = baseUrlOf(source)

            // 分页循环拉全（Review Focus 4：硬上限 5000 防失控）
            val items = mutableListOf<JellyfinItem>()
            var start = 0
            while (true) {
                val url = if (parentId == null) {
                    JellyfinApi.viewsUrl(baseUrl, session.userId)
                } else {
                    JellyfinApi.itemsUrl(baseUrl, session.userId, parentId, start, JellyfinApi.PAGE_LIMIT)
                }
                val page = JellyfinApi.parseItems(getForJson(source, url))
                items += page
                // 根（Views）不分页；分页流按页长推进
                if (parentId == null || page.size < JellyfinApi.PAGE_LIMIT || items.size >= MAX_ITEMS) break
                start += page.size
            }

            items.map { item ->
                if (item.isFolder) {
                    val childPath = NvPaths.join(dir, item.name)
                    index(source).remember(NvPaths.asDirectory(childPath), item.id)
                    NvFileEntry(item.name, NvPaths.asDirectory(childPath), isDir = true)
                } else {
                    // Name 是媒体标题、不带扩展名（12.x 实证），播放路由/缩略图嗅探/RMVB 兜底
                    // 都靠扩展名：文件条目用真实文件名（MediaSources Path 末段）作 display
                    // 与 path key，保持一致；拿不到回退标题
                    val fileName = item.fileName ?: item.name
                    val childPath = NvPaths.join(dir, fileName)
                    index(source).remember(childPath, item.id)
                    NvFileEntry(fileName, childPath, isDir = false, size = item.size, lastModified = item.modifiedMs)
                }
            }
        }.recoverCatching { e -> throw toIo(e, "列目录 ${source.nickname} $dirPath") }
    }

    /**
     * dirPath → 父目录 itemId；根返回 null（= Views）。
     * 索引缺失时从浅到深逐段补：每段取「已解析父 id 的子列表」按段名匹配（Review Focus 1）。
     */
    private fun resolveParentId(
        source: SourceEntity,
        session: Session,
        dir: String,
    ): String? {
        if (dir == NvPaths.SEPARATOR) return null
        val idx = index(source)
        idx.idOf(dir)?.let { return it }
        // 逐级重解析
        val parents = idx.walkSegments(NvPaths.parentOf(dir))
        var walked = ""    // 已解析到的最深目录（统一表示，不带尾分隔符）
        for (p in parents) {
            val id = ensureId(source, session, walked, p)
            idx.remember(NvPaths.asDirectory(p), id)
            walked = p
        }
        // 最后一段（目标目录自身）在其父的子列表里按名匹配
        return ensureId(source, session, walked, dir)
    }

    /** 在 [parentPath]（""=根）的子列表里找名为 [NvPaths.fileNameOf(childPath)] 的条目并记忆 id */
    private fun ensureId(
        source: SourceEntity,
        session: Session,
        parentPath: String,
        childPath: String,
    ): String {
        val name = NvPaths.fileNameOf(childPath)
        val parentId = if (parentPath.isEmpty() || parentPath == NvPaths.SEPARATOR) {
            null
        } else {
            index(source).idOf(parentPath)
                ?: throw IOException("Jellyfin 目录索引缺失: $parentPath")
        }
        val baseUrl = baseUrlOf(source)
        var start = 0
        while (true) {
            val url = if (parentId == null) {
                JellyfinApi.viewsUrl(baseUrl, session.userId)
            } else {
                JellyfinApi.itemsUrl(baseUrl, session.userId, parentId, start, JellyfinApi.PAGE_LIMIT)
            }
            val page = JellyfinApi.parseItems(getForJson(source, url))
            // path key 用真实文件名（list 同口径），匹配也按 fileName ?: name 对齐
            page.firstOrNull { (it.fileName ?: it.name) == name }?.let {
                index(source).remember(childPath, it.id)
                return it.id
            }
            if (parentId == null || page.size < JellyfinApi.PAGE_LIMIT) break
            start += page.size
            // 与 list() 的 MAX_ITEMS 同一口径：分页查找也要硬上限，防服务器异常满页返回导致死循环
            if (start >= MAX_ITEMS) throw IOException("Jellyfin 目录条目超过上限（$MAX_ITEMS）")
        }
        throw IOException("Jellyfin 上找不到目录: $childPath")
    }

    // ---------------- 打开流（随机读）----------------

    suspend fun openRead(source: SourceEntity, path: String, offset: Long): InputStream =
        withContext(io) { openReadBlocking(source, path, offset) }

    fun openReadBlocking(source: SourceEntity, path: String, offset: Long): InputStream {
        // 2026-10-18 发布终审 BUG-5：冷启动直接投屏链 prepareSource（1 字节预检）走本入口，
        // 此前无缓存会话直接抛「未登录」取消投屏——且报错文案误导用户去查 NAS。与 401 重登
        // 同型收敛：缺会话先 authenticate（阻塞函数，调用方已在 IO 上下文；authenticate
        // 对已有会话短路，有会话时本改动零开销）。播放路径（jellyfinItemIdOf）本就先认证
        // 不受影响，投屏链由此补齐。
        val session = sessions[source.id]
            ?: runCatching { authenticate(source) }.getOrElse { e ->
                throw toIo(e, "登录 ${source.nickname}")
            }
        return openStreamInner(source, session, path, offset, allowReauth = true)
    }

    private fun openStreamInner(
        source: SourceEntity,
        session: Session,
        path: String,
        offset: Long,
        allowReauth: Boolean,
    ): InputStream {
        val itemId = resolveItemIdBlocking(source, session, path)
        // resolve 内部 getForJson 可能已 401 重登并回写新会话：stream URL 必须重读最新 token，
        // 否则带着已逐出的旧 token 打流端点必再 401（session 参数仍承担本次首试的 401 判断语义）
        val fresh = sessions[source.id] ?: session
        val url = URL(JellyfinApi.streamUrl(baseUrlOf(source), itemId, fresh.token))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = this@JellyfinClient.connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=$offset-")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val code = conn.responseCode
        if (code == 401 && allowReauth) {
            conn.disconnect()
            // 静默重登一次后重试（Review Focus 2）。先逐出旧会话：authenticate 对已有会话短路，
            // 不清则「重登」拿回旧 token，重试必再 401；新会话必须回写供后续请求使用
            sessions.remove(source.id)
            val fresh = runCatching { authenticate(source) }.getOrElse { e ->
                throw toIo(e, "登录 ${source.nickname}")
            }
            sessions[source.id] = fresh
            return openStreamInner(source, fresh, path, offset, allowReauth = false)
        }
        if (code == 404) {
            conn.disconnect()
            throw IOException("${JellyfinApi.httpErrorMessage(404)}: $path")
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw JellyfinApi.httpError(code)
        }
        return conn.inputStream
    }

    /** 阻塞上下文里的 id 解析（复用 walk 逻辑；loader 线程直接网络 IO，同 DefaultHttpDataSource 模型） */
    private fun resolveItemIdBlocking(source: SourceEntity, session: Session, path: String): String {
        index(source).idOf(path)?.let { return it }
        val dir = NvPaths.asDirectory(NvPaths.parentOf(path))
        // 冷启动（进程重启后直接 seek/open，索引为空）：先从根逐段重解析父目录链填索引（Review Focus 1），
        // 再按名解析文件自身
        resolveParentId(source, session, dir)
        return ensureId(source, session, dir, path)
    }

    // ---------------- 文件大小（失败 -1 不抛，同 SMB/WebDAV 契约）----------------

    fun fileSizeBlocking(source: SourceEntity, path: String): Long {
        val session = sessions[source.id] ?: return -1L
        var conn: HttpURLConnection? = null
        return try {
            val itemId = resolveItemIdBlocking(source, session, path)
            // 同 openStreamInner：resolve 可能已重登回写新会话，取最新 token 组 URL
            val fresh = sessions[source.id] ?: session
            conn = (URL(
                JellyfinApi.streamUrl(baseUrlOf(source), itemId, fresh.token),
            ).openConnection() as HttpURLConnection).apply {
                connectTimeout = this@JellyfinClient.connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val total = JellyfinApi.parseContentRangeTotal(conn.getHeaderField("Content-Range"))
            conn.inputStream.close()
            total
        } catch (_: Exception) {
            -1L    // 长度缺失只降级 seek 质量（NvSourceRepository.kt:126 契约），不值得让起播失败
        } finally {
            conn?.disconnect()    // 失败路径同样释放连接；成功路径 close 流后 disconnect 归还连接池
        }
    }

    // ---------------- M4：进度上报 / 服务器续播 / 内嵌字幕发现 ----------------
    // 契约：本区块全部 suspend 且内部 runCatching——任何网络/解析失败静默回默认值
    // （null / 空 / Unit），绝不向调用方（起播/切集/心跳/暂停回调）抛异常。

    /**
     * 条目 itemId：索引快路径（浏览过 = 零网络）→ 冷索引按目录链 walk（有界网络 IO）。
     * 失败 null = 调用方本会话放弃上报目标（播放照常）。
     * 时延语义（重要）：冷索引 walk 走 resolveItemIdBlocking→ensureId 的通用 30s 读超时，
     * withTimeoutOrNull 只能在挂起点间生效、切不断阻塞中的 socket 读——真实硬上界是
     * socket 超时（connect 10s / read 30s，每跳）。这不是新的挂起类别：冷启动时紧随其后的
     * openStreamBlocking 本就要走同一条 walk（起播必需路径），本函数只是先行一次；
     * 调用方的 withTimeoutOrNull(8_000) 作为「挂起边界聚合上界」尽力收口。
     */
    suspend fun itemIdOf(source: SourceEntity, path: String): String? = withContext(io) {
        runCatching {
            val session = authenticate(source)
            resolveItemIdBlocking(source, session, path)
        }.getOrNull()
    }

    /**
     * 服务器续播位置 ms（UserData.PlaybackPositionTicks）。本地历史无进度时的兜底读取；
     * 未知/失败 null（调用方按 0 处理 = 从头播）。itemMetadataUrl 是元数据查询，
     * 不触发服务端媒体探测（与 PlaybackInfo 的冷条目 100s 探测不同，典型 ms 级）。
     * 读超时收窄到 5s（socket 级硬上界，M3 PLAYBACK_INFO_TIMEOUT_MS 同款哲学）：
     * 本入口在起播路径上，不可沿用通用 30s 读超时。
     */
    suspend fun serverResumePositionMs(source: SourceEntity, path: String): Long? = withContext(io) {
        runCatching {
            val session = authenticate(source)
            val itemId = resolveItemIdBlocking(source, session, path)
            val fresh = sessions[source.id] ?: session
            JellyfinApi.parseResumePositionMs(
                getForJson(
                    source,
                    JellyfinApi.itemMetadataUrl(baseUrlOf(source), fresh.userId, itemId),
                    readTimeoutMs = 5_000,
                ),
            )
        }.getOrNull()
    }

    // ---------------- 2026-10-10 通知栏封面(spec §4.2)----------------
    // 契约:挂起 + runCatching → null(调用方回落 app 图标),绝不向起播/换集链抛异常。
    // connect+read 双收窄 5s:PLAYBACK_INFO_TIMEOUT_MS 先例只收 read,connect 黑洞(10s)
    // 同样挂在起播/换集串行链上(第二轮评审 P1);协程超时切不断阻塞 socket 读,必须在
    // 连接层收窄。计量闸门在 NvNotificationArtwork(调用方),本方法不重复。

    /**
     * 通知栏海报字节(归一化在 NvNotificationArtwork.codec)。正常一张 20-50KB;
     * 累计 >2MB 判异常服务器,放弃本张(防异常端点拖垮内存)。
     */
    suspend fun posterBytes(source: SourceEntity, itemId: String): ByteArray? = withContext(io) {
        runCatching {
            val session = authenticate(source)
            val url = JellyfinApi.primaryImageUrl(baseUrlOf(source), itemId, session.token)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = POSTER_TIMEOUT_MS
                readTimeout = POSTER_TIMEOUT_MS
                setRequestProperty(
                    "Authorization",
                    JellyfinApi.authHeader(CLIENT, DEVICE, deviceIdOf(source), VERSION, session.token),
                )
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (conn.responseCode != 200) throw IOException("poster HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_POSTER_BYTES) throw IOException("poster too large")
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()   // 失败路径同样释放(同 getForJson 惯例)
            }
        }.getOrNull()
    }

    /** 起播上报（服务器仪表盘出现「正在播放」+ 会话锚定 itemId） */
    suspend fun reportPlaybackStart(source: SourceEntity, itemId: String, playSessionId: String?, positionMs: Long) =
        reportPlayback(source, JellyfinApi.playingStartUrl(baseUrlOf(source)), itemId, playSessionId, positionMs, isPaused = false)

    /** 进度心跳 / 暂停恢复单发（10s 一次 + isPaused 翻转时单发） */
    suspend fun reportPlaybackProgress(
        source: SourceEntity,
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        durationMs: Long = 0L,
    ) = reportPlayback(
        source, JellyfinApi.playingProgressUrl(baseUrlOf(source)), itemId, playSessionId,
        positionMs, isPaused, durationMs,
    )

    /** 停播上报（换片/切档重建/退出；服务器回收会话 + 按位置回写观看状态） */
    suspend fun reportPlaybackStopped(source: SourceEntity, itemId: String, playSessionId: String?, positionMs: Long) =
        reportPlayback(source, JellyfinApi.playingStoppedUrl(baseUrlOf(source)), itemId, playSessionId, positionMs, isPaused = false)

    /** 三端点公共体：POST {}；失败 Log.d（Review Focus 1：上报永不影响播放，也不重试风暴） */
    private suspend fun reportPlayback(
        source: SourceEntity,
        url: String,
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        durationMs: Long = 0L,
    ) {
        withContext(io) {
            runCatching {
                postForJson(
                    source,
                    url,
                    JellyfinApi.playingBody(itemId, playSessionId, positionMs, isPaused, durationMs),
                )
            }.onFailure {
                android.util.Log.d("NvJfProgress", "report failed (ignored): ${it.message}")
            }
        }
    }

    /**
     * 内嵌字幕发现：文本字幕 → vtt 侧载候选；图形字幕（PGS 等）只报存在性。
     * [knownItemId] 非空（起播管线已解析）时跳过重复 walk。失败 = 空 discovery（无候选、无提示）。
     * 读超时收窄到 5s（同 serverResumePositionMs：起播路径上的元数据查询不吃通用 30s）。
     */
    suspend fun embeddedSubtitleDiscovery(
        source: SourceEntity,
        videoPath: String,
        knownItemId: String? = null,
    ): JellyfinSubtitleDiscovery = withContext(io) {
        runCatching {
            val session = authenticate(source)
            val itemId = knownItemId ?: resolveItemIdBlocking(source, session, videoPath)
            val fresh = sessions[source.id] ?: session
            val json = getForJson(
                source,
                JellyfinApi.itemMetadataUrl(baseUrlOf(source), fresh.userId, itemId),
                readTimeoutMs = 5_000,
            )
            val parsed = JellyfinApi.parseMediaStreams(json)
            val mediaSourceId = parsed.mediaSourceId
                ?: return@runCatching JellyfinSubtitleDiscovery(
                    emptyList(), hasImageSubtitles = false,
                    sourceVideoBitrate = parsed.videoBitrate,
                )
            val token = (sessions[source.id] ?: session).token
            JellyfinSubtitleDiscovery(
                candidates = JellyfinApi.embeddedSubtitleCandidates(
                    baseUrlOf(source), itemId, mediaSourceId, token, parsed.streams,
                ),
                hasImageSubtitles = parsed.streams.any { !it.isText },
                sourceVideoBitrate = parsed.videoBitrate,
            )
        }.getOrDefault(JellyfinSubtitleDiscovery(emptyList(), hasImageSubtitles = false))
    }

    // ---------------- 清晰度会话（M3 转码，spec §6）----------------

    /**
     * PlaybackInfo 专用短超时（2026-10-07「下一集卡死 30 秒」插桩实证的修复）：
     * 换集/起播管线在等本请求返回期间，UI 已切到装载态（旧引擎 surface 已解绑，画面定格），
     * 管线每多等一秒用户就多看一秒冻结画面。而 PlaybackInfo 对「服务器尚未探测过的 4K
     * 条目」会触发服务端媒体探测——Jellyfin 12.2.0 实测：冷条目 30~100s 不返回甚至 500，
     * 热条目 40ms。沿用通用 30s 读超时 = 每次切到冷条目都冻结 30s（读超时抛异常回原画）。
     * 收窄到 5s：热条目（绝大多数场景）无感；冷条目快速回退原画直连（本就是服务器给不出
     * 转码时的既定语义，只是从 30s 才回退变成 5s 就回退）。「永不抛异常」契约不变。
     */
    suspend fun resolveStream(source: SourceEntity, path: String, tier: NvQualityTier): JellyfinStreamPlan {
        if (tier == NvQualityTier.ORIGINAL || tier == NvQualityTier.AUTO) {
            // AUTO 与 ORIGINAL 同为原画直连（spec §6：自动档起播原画，rebuffer 后由
            // switchQualityInternal 以具体档位降档，不经本入口的 AUTO 分支）——此前 AUTO
            // 带着 Int.MAX_VALUE 的 MaxStreamingBitrate 打 PlaybackInfo：服务器侧要探测
            // 媒体流，弱网/慢源下这一趟网络要数秒，纯属浪费（切集卡顿元凶之一）
            return JellyfinStreamPlan(false, null, null)
        }
        return withContext(io) {
            runCatching {
                val session = sessions[source.id] ?: return@runCatching JellyfinStreamPlan(false, null, null)
                com.novax.app.player.NvQSwitchTrace.mark("resolveStream itemId enter tier=$tier")
                // 残余上界（终审 Minor，仅记录不改行为）：索引全冷时 resolveItemIdBlocking 内部
                // getForJson 走通用 30s 读超时——下方 PlaybackInfo 的 5s 短超时不覆盖这条路径，
                // 最坏情况 = 冷索引 + 慢源的浏览记忆重建阶段仍可挂到 30s（快路径有索引缓存，罕见）
                val itemId = resolveItemIdBlocking(source, session, path)
                com.novax.app.player.NvQSwitchTrace.mark("resolveStream itemId=$itemId")
                // base 必须走 baseUrlOf（用户分格填 host + port 时补端口），
                // 裸 normalizeBaseUrl 会打到 :80 使转码静默失效回原画
                val base = baseUrlOf(source)
                // C-1（终审）：必须 POST + body 带 DeviceProfile——空/缺 DeviceProfile 的请求
                // 服务器全拒（supportsTranscoding 恒 false，12.2.0 实证），GET 无 body 的旧实现
                // 使转码分支永不可达。查询串的 userId/maxStreamingBitrate 保留（官方客户端同型）
                val url = JellyfinApi.playbackInfoUrl(base, itemId, session.userId, tier.maxBitrateBps)
                val info = JellyfinApi.parsePlaybackInfo(
                    postForJson(
                        source,
                        url,
                        JellyfinApi.playbackInfoBody(
                            session.userId,
                            tier.maxBitrateBps,
                            // 2026-10-10 S3:DeviceProfile 按设备能力生成(hevc 转码 + 音频直通直解,
                            // 修「2M 档 4K→720p + 音频缩水」;详见 NvDeviceCodecs KDoc)
                            JellyfinApi.deviceProfileJson(
                                transcodeVideoCodecs = NvDeviceCodecs.transcodeVideoCodecs().joinToString(","),
                                transcodeAudioCodecs = NvDeviceCodecs.TRANSCODE_AUDIO_CODECS,
                                directPlayAudioCodecs = NvDeviceCodecs.DIRECT_PLAY_AUDIO_CODECS,
                            ),
                        ),
                        readTimeoutMs = PLAYBACK_INFO_TIMEOUT_MS,
                    ),
                )
                com.novax.app.player.NvQSwitchTrace.mark(
                    "resolveStream playbackInfo transcode=${info.supportsTranscoding} rel=${info.transcodingUrl != null} " +
                        "dp=${info.supportsDirectPlay} srcBps=${info.sourceVideoBitrate}",
                )
                val rel = info.transcodingUrl
                when {
                    info.supportsTranscoding && rel != null -> {
                        // getForJson 可能已 401 重登并回写新会话：转码 URL 的 api_key 必须
                        // 重读最新 token（同 openStreamInner/fileSizeBlocking 的重读惯例）
                        val token = (sessions[source.id] ?: session).token
                        JellyfinStreamPlan(
                            isHls = true,
                            hlsUrl = JellyfinApi.absoluteTranscodingUrl(base, rel, token),
                            playSessionId = info.playSessionId,
                        )
                    }
                    info.supportsTranscoding && (info.supportsDirectPlay || info.supportsDirectStream) ->
                        // 档位上限 ≥ 源码率：服务器判定「无需转码」，原画直连即该档位下最佳画质
                        // （2026-10-08 实证，详见 JellyfinStreamPlan.transcodeNotNeeded 注释）
                        JellyfinStreamPlan(
                            isHls = false,
                            hlsUrl = null,
                            playSessionId = null,
                            transcodeNotNeeded = true,
                            sourceVideoBitrate = info.sourceVideoBitrate,
                        )
                    else -> JellyfinStreamPlan(false, null, null)   // 服务器不给转码也不给直连 → 真不可转 → 原画
                }
            }.getOrElse { JellyfinStreamPlan(false, null, null) }
        }
    }

    // ---------------- 鉴权与底层 ----------------

    /** AuthenticateByName；成功缓存会话并返回。失败抛 IOException（401 → httpErrorMessage 文案） */
    private fun authenticate(source: SourceEntity): Session {
        val existing = sessions[source.id]
        if (existing != null) return existing
        val baseUrl = baseUrlOf(source)
        val conn = (URL("$baseUrl/Users/AuthenticateByName").openConnection() as HttpURLConnection).apply {
            connectTimeout = this@JellyfinClient.connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // 认证头用标准 Authorization 头名（MediaBrowser 方案）：Jellyfin 12.x 拒绝
            // X-Emby-Authorization 旧头名（一律 400，真机 12.2.0 实证），10.x 两者兼容
            setRequestProperty(
                "Authorization",
                JellyfinApi.authHeader(CLIENT, DEVICE, deviceIdOf(source), VERSION),
            )
        }
        try {
            // 落库 password 是 NvSecrets 密文（"nvenc1:iv:ct"），认证必须解密后发明文——
            // 发密文必 401（同 SmbClient/WebDavClient/SftpClient 的取密写法）
            conn.outputStream.use {
                it.write(
                    JellyfinApi.authBody(source.username, com.novax.app.data.NvSecrets.decrypt(source.password))
                        .toByteArray(),
                )
            }
            val code = conn.responseCode
            if (code != 200) {
                throw JellyfinApi.httpError(code)
            }
            val auth = JellyfinApi.parseAuth(conn.inputStream.bufferedReader().readText())
            return Session(auth.userId, auth.accessToken).also { sessions[source.id] = it }
        } catch (e: JSONException) {
            throw IOException("Jellyfin 响应异常，请确认服务器地址正确", e)
        } finally {
            conn.disconnect()
        }
    }

    /** 每源稳定 DeviceId（同一源重装后服务器侧会话不膨胀） */
    private fun deviceIdOf(source: SourceEntity): String =
        "novax-" + (source.id.toString() + "-" + source.nickname).hashCode().toUInt().toString()

    /**
     * POST JSON body 并读回 JSON 文本（与 [getForJson] 同构：token 走 Authorization 头、
     * 401 重登一次重试、readTimeoutMs 可按调用点收窄）。
     * 成功码契约：2xx 全算成功。其中 204 No Content 是进度上报三端点
     * （/Sessions/Playing*）的成功形态——无响应体，不得读 inputStream（无体可读会抛），
     * 直接返回空串；其余 2xx 照旧读回响应体文本。getForJson 不放宽（GET 端点全部 200）。
     * C-1（2026-10-07 终审）：PlaybackInfo 的 DeviceProfile **只在 POST body 里被服务器
     * 接受**——空/缺 DeviceProfile 的请求一律全拒（supportsTranscoding=false，12.2.0 实证），
     * 而此前 resolveStream 走 GET 无 body，转码分支恒不可达、playbackInfoBody 死代码。
     */
    private fun postForJson(
        source: SourceEntity,
        url: String,
        jsonBody: String,
        readTimeoutMs: Int = this.readTimeoutMs,
    ): String {
        val session = sessions[source.id] ?: throw IOException("Jellyfin 未登录: ${source.nickname}")
        fun fetch(tok: Session): String {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = this@JellyfinClient.connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty(
                    "Authorization",
                    JellyfinApi.authHeader(CLIENT, DEVICE, deviceIdOf(source), VERSION, tok.token),
                )
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                val code = conn.responseCode
                if (code == 401) throw AuthRequired()
                // 成功码放宽为 2xx：进度上报三端点成功返回 204，只认 200 会把每次成功
                // 上报误判为失败（缺陷 D2，服务器实际已收到、仅观测性受损，2026-10-08 修复）
                if (code !in 200..299) throw JellyfinApi.httpError(code)
                // 204 无响应体：读 inputStream 会抛，直接返回空串（上报端点不回读）
                if (code == 204) return ""
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }
        return try {
            fetch(session)
        } catch (_: AuthRequired) {
            // 逐出旧会话：authenticate 对已有会话短路，不清则重登拿回旧 token，重试必再 401
            sessions.remove(source.id)
            val fresh = runCatching { authenticate(source) }.getOrElse { t -> throw toIo(t, "登录 ${source.nickname}") }
            sessions[source.id] = fresh
            try {
                fetch(fresh)
            } catch (_: AuthRequired) {
                // 重登后仍 401：凭据确已失效，落标准文案（私有 AuthRequired 不得逃逸给调用方）
                throw JellyfinApi.httpError(401)
            }
        }
    }

    /** GET JSON 文本（token 走 Authorization 头；401 重登一次重试；readTimeoutMs 可按调用点收窄） */
    private fun getForJson(source: SourceEntity, url: String, readTimeoutMs: Int = this.readTimeoutMs): String {
        val session = sessions[source.id] ?: throw IOException("Jellyfin 未登录: ${source.nickname}")
        fun fetch(tok: Session): String {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = this@JellyfinClient.connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty(
                    "Authorization",
                    JellyfinApi.authHeader(CLIENT, DEVICE, deviceIdOf(source), VERSION, tok.token),
                )
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code == 401) throw AuthRequired()
                if (code != 200) throw JellyfinApi.httpError(code)
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }
        return try {
            fetch(session)
        } catch (_: AuthRequired) {
            // 逐出旧会话：authenticate 对已有会话短路，不清则重登拿回旧 token，重试必再 401
            sessions.remove(source.id)
            val fresh = runCatching { authenticate(source) }.getOrElse { t -> throw toIo(t, "登录 ${source.nickname}") }
            sessions[source.id] = fresh
            try {
                fetch(fresh)
            } catch (_: AuthRequired) {
                // 重登后仍 401：凭据确已失效，落标准文案（私有 AuthRequired 不得逃逸给调用方）
                throw JellyfinApi.httpError(401)
            }
        }
    }

    private class AuthRequired : Exception()

    private fun toIo(e: Throwable, what: String): IOException = when (e) {
        is IOException -> e
        else -> IOException("$what: ${e.message}", e)
    }

    private companion object {
        const val CLIENT = "NovaX"
        const val DEVICE = "NovaX Android"
        const val VERSION = "1.0"
        const val USER_AGENT = "NovaX/1.0 (Jellyfin)"
        const val MAX_ITEMS = 5000

        /**
         * PlaybackInfo 请求的专用读超时（2026-10-07「下一集卡死 30 秒」插桩实证）：
         * 换集/起播管线在等本请求期间，UI 已是装载态（旧引擎 surface 已解绑 → 画面定格），
         * 管线每多等一秒用户就多看一秒冻结画面。而 PlaybackInfo 对「服务器尚未探测过的
         * 4K 条目」会触发服务端媒体探测——Jellyfin 12.2.0 实测：冷条目 30~100s 不返回甚至
         * HTTP 500，热条目 40ms。沿用通用 30s 读超时 = 每次切到冷条目都冻结 30s（超时抛
         * 异常回原画）。收窄到 5s：热条目（绝大多数场景）无感；冷条目快速回退原画直连
         * （服务器给不出转码时回原画本就是既定语义，只是从 30s 才回退变成 5s 就回退）。
         */
        const val PLAYBACK_INFO_TIMEOUT_MS = 5_000

        /** 通知栏海报下载专用超时(spec §5.2):connect+read 双 5s——connect 黑洞是评审 P1 专项 */
        const val POSTER_TIMEOUT_MS = 5_000
        private const val MAX_POSTER_BYTES = 2L * 1024 * 1024
    }
}
