package com.novax.app.data

import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.jellyfin.JellyfinClient
import com.novax.app.data.jellyfin.JellyfinSubtitleDiscovery
import com.novax.app.data.sftp.SftpClient
import com.novax.app.data.smb.SmbClient
import com.novax.app.data.webdav.WebDavClient
import com.novax.app.player.SubtitleCandidate
import com.novax.app.player.SubtitleMatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * NAS 源统一门面：按 [SourceEntity.type] 路由到 SmbClient / WebDavClient / SftpClient / 本地文件。
 * 所有页面与播放层只依赖本接口暴露的 connect / list / openStream / listSubtitles。
 *
 * 单例持有（App 范围）：连接池（SMB session、SFTP SSH 连接、OkHttp keep-alive）随仓库存活，
 * seek 与多次浏览共享连接链（C8 §3 连接复用）。App 退出时调 [closeAll]。
 */
class NvSourceRepository(
    val smb: SmbClient = SmbClient(),
    val webdav: WebDavClient = WebDavClient(),
    val sftp: SftpClient = SftpClient(),
    val jellyfin: JellyfinClient = JellyfinClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {

    /** 验证源可连（不改变 DB 状态；状态回写由调用方经 SourceDao.updateStatus 完成） */
    suspend fun connect(source: SourceEntity): Result<Unit> = when (source.type) {
        SourceType.SMB -> smb.connect(source)
        SourceType.WEBDAV -> webdav.connect(source)
        SourceType.LOCAL -> withContext(io) {
            runCatching {
                requireDir(File(source.shareOrPath), source)
                Unit
            }.recoverCatching { e -> throw toIo(e, "connect ${source.nickname}") }
        }
        SourceType.SFTP -> sftp.connect(source)
        SourceType.JELLYFIN -> jellyfin.connect(source)
    }

    /**
     * SFTP 源删除/编辑保存后驱逐连接池并重置该 host:port 的 known-hosts 指纹信任
     * （TOFU 重置路径，见 [SftpClient.evictFor]）。suspend + [io] 调度：驱逐含
     * known-hosts 文件 IO，调用方（可在主线程协程）不必关心线程。
     */
    suspend fun evictSftp(source: SourceEntity) {
        if (source.type != SourceType.SFTP) return
        withContext(io) { sftp.evictFor(source) }
    }

    /**
     * SMB 源删除/编辑保存后驱逐连接池（M3，2026-10-18 发布终审，见 [SmbClient.evictFor]）：
     * 按池键前缀清已认证连接（含 lane 变体）——此前删源后旧凭据会话存活到进程死。
     * WebDAV 无需对位清理（无持久会话池：每请求带 Authorization 头，OkHttp 连接池
     * 不持凭据）。JELLYFIN 会话缓存随源删除自然失配（源 id 不复用）。
     */
    suspend fun evictSmb(source: SourceEntity) {
        if (source.type != SourceType.SMB) return
        withContext(io) { smb.evictFor(source) }
    }

    /**
     * 枚举 SMB 主机共享名（向导「获取共享列表」）：jcifs-ng 独立短连接，不经播放连接池。
     * 返回按名称排序的共享名列表；失败为中文友好错误的 Result.failure。
     */
    suspend fun listSmbShares(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String? = null,
    ): Result<List<String>> = smb.listShares(host, port, username, password, domain)

    /**
     * 列目录。[dirPath] 为统一路径表示（"/" 分隔）；SMB/WEBDAV 相对源根，
     * LOCAL 为绝对文件系统路径（空串时回落 [SourceEntity.shareOrPath]）。
     */
    suspend fun list(source: SourceEntity, dirPath: String): Result<List<NvFileEntry>> =
        when (source.type) {
            SourceType.SMB -> smb.list(source, dirPath)
            SourceType.WEBDAV -> webdav.list(source, dirPath)
            SourceType.LOCAL -> withContext(io) {
                runCatching {
                    val dir = File(localPathOf(source, dirPath))
                    requireDir(dir, source)
                    dir.listFiles()
                        ?.map {
                            NvFileEntry(
                                name = it.name,
                                path = NvPaths.asDirectory(dirPath) + it.name + (if (it.isDirectory) NvPaths.SEPARATOR else ""),
                                isDir = it.isDirectory,
                                size = if (it.isFile) it.length() else 0L,
                                lastModified = it.lastModified(),
                            )
                        }
                        ?.sortedWith(compareByDescending<NvFileEntry> { it.isDir }.thenBy { it.name.lowercase() })
                        ?: emptyList()
                }.recoverCatching { e -> throw toIo(e, "list ${source.nickname} $dirPath") }
            }
            SourceType.SFTP -> sftp.list(source, dirPath)
            SourceType.JELLYFIN -> jellyfin.list(source, dirPath)
        }

    /**
     * 打开随机读流（从 [offset] 起）。供 DataSource seek 与工具层预读使用；
     * 失败抛 IOException。SMB/WebDAV 均为网络流：调用方负责 close。
     */
    suspend fun openStream(source: SourceEntity, path: String, offset: Long = 0L): InputStream =
        when (source.type) {
            SourceType.SMB -> smb.openRead(source, path, offset)
            SourceType.WEBDAV -> webdav.openRead(source, path, offset)
            SourceType.LOCAL -> withContext(io) { openLocalStream(source, path, offset) }
            SourceType.SFTP -> sftp.openRead(source, path, offset)
            SourceType.JELLYFIN -> jellyfin.openRead(source, path, offset)
        }

    /**
     * [openStream] 的阻塞版本：给 ExoPlayer 加载线程（非协程上下文）用。
     * 网络 IO 直接发生在调用线程（与 DefaultHttpDataSource 同模型），
     * 底层连接池/会话依然跨 open 复用。
     */
    /**
     * [maxBlockBytes]：SMB 块流单块上限覆盖（2026-09-15 抽帧路径注入 256KB 防读放大；
     * null = 播放默认 4MB，播放路径行为零变化）。
     */
    fun openStreamBlocking(
        source: SourceEntity,
        path: String,
        offset: Long = 0L,
        maxBlockBytes: Int? = null,
        lane: String = com.novax.app.data.smb.SmbClient.LANE_MAIN,
        /** 读侧僵死回调（2026-09-15 播放卡死修复）：SMB 连续读不到字节时通知上层重建+重播 */
        onStalled: (() -> Unit)? = null,
    ): InputStream =
        when (source.type) {
            SourceType.SMB -> smb.openReadBlocking(source, path, offset, maxBlockBytes, lane, onStalled)
            SourceType.WEBDAV -> webdav.openReadBlocking(source, path, offset)
            SourceType.LOCAL -> openLocalStream(source, path, offset)
            // SFTP：无 lane 概念（单通道，见 SftpClient 类 kdoc）；僵死回调语义与 SMB 相同
            SourceType.SFTP -> sftp.openReadBlocking(source, path, offset, maxBlockBytes, onStalled = onStalled)
            // JELLYFIN：同为 HTTP 直连流，无 lane/maxBlockBytes 概念（与 WEBDAV 分支同型，参数忽略）；
            // 僵死回调 onStalled 暂不接——Task 7 验收若现读僵死，再按 M3 计划接 NvStallRecovery
            SourceType.JELLYFIN -> jellyfin.openReadBlocking(source, path, offset)
        }

    /**
     * 单文件大小（字节；未知/失败 -1）。起播时取一次（Factory 级 memo，见
     * NvDataSourceFactory），供 NvDataSource.open 返回剩余总长（C8 §4.3）——
     * MatroskaExtractor 无 Cues 时的降级 seek 与 MP4 尾部 moov 精确 seek 都依赖已知长度。
     * 网络失败不抛：长度缺失只降级 seek 质量，不值得让起播失败（同 SMB/WebDAV 契约）。
     */
    fun fileSizeBlocking(source: SourceEntity, path: String): Long = when (source.type) {
        SourceType.SMB -> smb.fileSizeBlocking(source, path)
        SourceType.WEBDAV -> webdav.fileSizeBlocking(source, path)
        SourceType.LOCAL ->
            runCatching {
                File(localPathOf(source, path)).takeIf { it.isFile }?.length() ?: -1L
            }.getOrDefault(-1L)
        SourceType.SFTP -> sftp.fileSizeBlocking(source, path)
        SourceType.JELLYFIN -> jellyfin.fileSizeBlocking(source, path)
    }

    private fun openLocalStream(source: SourceEntity, path: String, offset: Long): InputStream {
        val file = File(localPathOf(source, path))
        if (!file.isFile) throw IOException("本地文件不存在: $path")
        return RandomAccessFile(file, "r").let { raf ->
            raf.seek(offset)
            CloseableRafInputStream(raf)
        }
    }

    /** 本地源条目绝对路径（2026-09-18 RMVB 兜底播放页受控出口；localPathOf 不对外） */
    fun localAbsolutePath(source: SourceEntity, path: String): String = localPathOf(source, path)

    /**
     * 同目录字幕候选（C6 §3.1）：列视频所在目录后交 [SubtitleMatcher] 评分。
     * 返回按分数降序的候选；视频路径也接受目录条目路径（取其父目录）。
     */
    suspend fun listSubtitles(
        source: SourceEntity,
        videoPath: String,
        dirPath: String = NvPaths.parentOf(videoPath),
    ): List<SubtitleCandidate> {
        val files = list(source, dirPath).getOrDefault(emptyList())
        val paths = files.filter { !it.isDir }.map { it.path }
        return SubtitleMatcher.findSubtitles(videoPath, paths)
    }

    // ---------- M4（2026-10-07）：Jellyfin 进度上报 / 服务器续播 / 内嵌字幕发现 ----------
    // 非 JELLYFIN 源一律空操作（播放层无需预判源类型）；全部 runCatching 语义由 client 层保证。

    suspend fun jellyfinItemIdOf(source: SourceEntity, path: String): String? =
        if (source.type == SourceType.JELLYFIN) jellyfin.itemIdOf(source, path) else null

    suspend fun jellyfinServerResumePositionMs(source: SourceEntity, path: String): Long? =
        if (source.type == SourceType.JELLYFIN) jellyfin.serverResumePositionMs(source, path) else null

    suspend fun jellyfinReportStart(source: SourceEntity, itemId: String, playSessionId: String?, positionMs: Long) {
        if (source.type == SourceType.JELLYFIN) {
            jellyfin.reportPlaybackStart(source, itemId, playSessionId, positionMs)
        }
    }

    suspend fun jellyfinReportProgress(
        source: SourceEntity,
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        durationMs: Long = 0L,
    ) {
        if (source.type == SourceType.JELLYFIN) {
            jellyfin.reportPlaybackProgress(source, itemId, playSessionId, positionMs, isPaused, durationMs)
        }
    }

    suspend fun jellyfinReportStopped(source: SourceEntity, itemId: String, playSessionId: String?, positionMs: Long) {
        if (source.type == SourceType.JELLYFIN) {
            jellyfin.reportPlaybackStopped(source, itemId, playSessionId, positionMs)
        }
    }

    suspend fun jellyfinSubtitleDiscovery(
        source: SourceEntity,
        videoPath: String,
        knownItemId: String? = null,
    ): JellyfinSubtitleDiscovery =
        if (source.type == SourceType.JELLYFIN) {
            jellyfin.embeddedSubtitleDiscovery(source, videoPath, knownItemId)
        } else {
            JellyfinSubtitleDiscovery(emptyList(), hasImageSubtitles = false)
        }

    /** 关闭全部底层连接（SMB session 池 + SFTP SSH 连接池 + OkHttp keep-alive 池） */
    override fun close() {
        smb.closeAll()
        webdav.closeAll()
        sftp.closeAll()
    }

    // ---------- 本地文件 ----------

    private fun localPathOf(source: SourceEntity, path: String): String =
        localPathInternal(source.shareOrPath.trim(), path, source.nickname)

    private fun requireDir(dir: File, source: SourceEntity): File =
        dir.takeIf { it.isDirectory && it.canRead() }
            // i18n Task 8 评审移交①：结构化判别随异常携带（上层按 kind 归类，不再 contains 中文原文）
            ?: throw com.novax.app.data.NvConnectException(
                com.novax.app.data.NvConnectKind.DIR_UNREADABLE,
                "目录不可读: ${dir.absolutePath} (${source.nickname})",
            )

    private fun toIo(e: Throwable, what: String): IOException = when (e) {
        is IOException -> e
        else -> IOException("$what: ${e.message}", e)
    }

    /** RandomAccessFile → InputStream（close 释放文件句柄；C8 §3.4 本地零成本 seek） */
    private class CloseableRafInputStream(private val raf: RandomAccessFile) : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun close() = raf.close()
    }

    internal companion object {
        /**
         * 本地源绝对路径解析（纯函数，可单测；[localPathOf] 委托至此）。
         *
         * 2026-09-28 路径穿越防御（审查 P2-4）：[NvPaths.normalize] 段级折叠 ".." 后，
         * 相对源根的路径已无法越出根；这里再做一道纯字符串前缀哨兵（不走 canonicalPath：
         * 它会解析符号链接，破坏 /sdcard → /storage/... 这类合法别名）。折叠语义保证
         * 哨兵对正常输入恒通过（逃逸已被折叠消解），此断言只在 normalize 未来回归时触发，
         * 抛 IllegalArgumentException 经上层 toIo 归一为 IOException 走既有错误路径。
         */
        fun localPathInternal(root: String, path: String, nickname: String): String {
            if (root.isEmpty() || NvPaths.normalize(path) == NvPaths.SEPARATOR) {
                return if (root.isEmpty()) File(NvPaths.asDirectory(path)).absolutePath else root
            }
            // path 以 "/" 开头视为相对源根；否则视为绝对路径
            val p = NvPaths.normalize(path)
            if (p == NvPaths.SEPARATOR) return root
            val base = root.trimEnd('/', '\\')
            val resolved = base + p
            require(resolved.startsWith("$base/")) { "路径越出源根: $path ($nickname)" }
            return resolved
        }
    }
}
