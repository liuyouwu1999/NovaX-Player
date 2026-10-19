package com.novax.app.data.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.novax.app.data.NvFileEntry
import com.novax.app.data.NvPaths
import com.novax.app.data.db.SourceEntity
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB 客户端（smbj 封装）——C8 §3.1 连接复用标准的落地。
 *
 * 连接模型：进程内按 "host:port:user:share" 维护常驻
 * [Connection] → [Session] → [DiskShare] 三层缓存；DataSource 每次打开的文件句柄
 * 挂在缓存的 DiskShare 上，seek（重新 open DataSpec）只发定址 READ，绝不重新
 * connectShare/认证。连接建立次数 = 播放会话数，与 seek 次数无关。
 *
 * 断线自愈：所有操作在传输层异常时驱逐缓存条目并透明重试 ≤ 1 次（重建连接链），
 * 对上层表现为短暂缓冲而非错误（C8 §3.1，重试预算 2 次内）。
 *
 * 所有阻塞 IO 经 [io] 调度器封装为 suspend。
 */
class SmbClient(private val io: CoroutineDispatcher = Dispatchers.IO) {

    /** C8 §5.1 连接埋点：conn_rebuild_count 应恒为 0（断线自愈除外） */
    class Stats {
        // 并发自增用 AtomicInteger（BUG-C-R4-11：@Volatile+++ 读改写竞争会丢埋点）
        private val nSessions = java.util.concurrent.atomic.AtomicInteger()
        private val nReconnects = java.util.concurrent.atomic.AtomicInteger()
        private val nEvictions = java.util.concurrent.atomic.AtomicInteger()

        val sessionCreations: Int get() = nSessions.get()
        val reconnects: Int get() = nReconnects.get()
        val evictions: Int get() = nEvictions.get()

        internal fun onSessionCreated() { nSessions.incrementAndGet() }
        internal fun onReconnect() { nReconnects.incrementAndGet() }
        internal fun onEviction() { nEvictions.incrementAndGet() }

        override fun toString(): String =
            "SmbStats(sessions=$sessionCreations, rebuilds=$reconnects, evictions=$evictions)"
    }

    private inner class PooledEntry(
        val smbClient: SMBClient,
        val connection: Connection,
        val session: Session,
        val shareName: String,
        val share: DiskShare,
        /** 连接协商的单次 READ 上限（字节） */
        val maxReadSize: Int,
    )

    private val pool = ConcurrentHashMap<String, PooledEntry>()
    val stats = Stats()

    companion object {
        /** 连接通道：浏览/列表（可被透明驱逐重建）；播放（独立通道，隔离驱逐波及） */
        const val LANE_MAIN = "main"
        const val LANE_PLAY = "play"
        const val LANE_THUMB = "thumb"
    }



    /** 池键：同 host+port+username+share 复用同一条连接链 */
    fun keyOf(source: SourceEntity): String =
        "smb://${source.username}@${source.host}:${source.portOr(SmbClientDefaults.PORT)}/${source.shareOrPath}/" +
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(
                    "${source.domain}\u0000${com.novax.app.data.NvSecrets.decrypt(source.password)}"
                        .toByteArray(Charsets.UTF_8),
                )
                .joinToString("") { "%02x".format(it) }

    /**
     * 验证源可连：建立/复用连接链并 connectShare。成功后条目留在池中供后续 list/openRead 复用。
     * 共享名留空的源只验证登录（连接 + 认证，不 connectShare）——共享在浏览时经虚拟根选择。
     */
    suspend fun connect(source: SourceEntity): Result<Unit> = withContext(io) {
        runCatching {
            if (source.shareOrPath.isBlank()) verifyLoginOnly(source) else ensureConnected(source)
            Unit
        }.recoverCatching { e -> throw mapToIo(e, "connect ${source.nickname}") }
    }

    /** 认证级验证（不挂载共享）：连接 + 认证成功即通过，连接用完即关 */
    private fun verifyLoginOnly(source: SourceEntity) {
        val smbClient = SMBClient(SmbClientDefaults.config())
        try {
            val connection = smbClient.connect(source.host, source.portOr(SmbClientDefaults.PORT))
            try {
                connection.authenticate(authContextOf(source)).close()
            } finally {
                runCatching { connection.close() }
            }
        } finally {
            runCatching { smbClient.close() }
        }
    }

    /**
     * 枚举主机共享名（向导「获取共享列表」）：jcifs-ng 独立短连接，与 smbj 播放池无关。
     * SMB2.0.2~3.1.1 协商 + NTLM 认证，列 "smb://host:port/" 后仅保留 SHARE 类型条目
     * （天然排除 IPC$ 管道，另按名称兜底过滤），用完即关。IO 跑 [io] 调度器，
     * 异常映射为中文友好错误的 Result.failure（原始异常保留在 cause 供上层归类）。
     */
    suspend fun listShares(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String? = null,
    ): Result<List<String>> = withContext(io) {
        runCatching {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
            }
            val base = BaseContext(PropertyConfiguration(props))
            val ctx: CIFSContext = if (username.isBlank()) {
                base.withAnonymousCredentials()
            } else {
                base.withCredentials(
                    NtlmPasswordAuthenticator(domain?.takeIf { it.isNotBlank() }, username, password),
                )
            }
            val effectivePort = if (port in 1..65535) port else SmbClientDefaults.PORT
            SmbFile("smb://${host.trim()}:$effectivePort/", ctx).use { root ->
                root.listFiles()
                    .filter { it.type == SmbFile.TYPE_SHARE }
                    .map { it.name.removeSuffix("/") }
                    .filter { it.isNotBlank() && !it.equals("IPC$", ignoreCase = true) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            }
        }.recoverCatching { e -> throw mapShareError(e, host) }
    }

    /**
     * 列目录（[dirPath] 为统一路径表示，"/" 为根）。返回按目录在前、名称排序的条目。
     * 共享名留空的源：根返回共享枚举作为虚拟根（见 [SmbShareRoot]）；
     * "/@share/<名>/…" 兜底直列该共享子路径。
     */
    suspend fun list(source: SourceEntity, dirPath: String): Result<List<NvFileEntry>> =
        runCatching {
            withContext(io) {
                if (source.shareOrPath.isBlank()) listWithoutShare(source, dirPath)
                else listInShare(source, dirPath)
            }
        }

    /** 未选共享源：根 = 共享枚举虚拟根目录；/@share/<名>/… 兜底直列该共享 */
    private suspend fun listWithoutShare(source: SourceEntity, dirPath: String): List<NvFileEntry> {
        if (NvPaths.normalize(dirPath) in setOf("/", SmbShareRoot.PREFIX)) return listShares(
                host = source.host,
                port = source.portOr(SmbClientDefaults.PORT),
                username = source.username,
                password = com.novax.app.data.NvSecrets.decrypt(source.password),
                domain = source.domain.trim().takeIf { it.isNotBlank() },
            )
                .getOrThrow()
                .map { name ->
                    NvFileEntry(name = name, path = SmbShareRoot.entryPath(name), isDir = true)
                }
        val target = SmbShareRoot.resolve("", dirPath)
        return listInShare(source.copy(shareOrPath = target.share), target.path).map {
            it.copy(path = SmbShareRoot.virtualPath(target.share, it.path, it.isDir))
        }
    }

    private suspend fun listInShare(source: SourceEntity, dirPath: String): List<NvFileEntry> =
        retryWithReconnect(source) { share ->
                    val dir = NvPaths.asDirectory(dirPath)
                    share.list(NvPaths.toSmb(dirPath))
                        .asSequence()
                        .filter { it.fileName != "." && it.fileName != ".." && it.fileName.isNotBlank() }
                        .map { info ->
                            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                            NvFileEntry(
                                name = info.fileName,
                                path = NvPaths.join(dir, info.fileName) + if (isDir) NvPaths.SEPARATOR else "",
                                isDir = isDir,
                                size = info.endOfFile,
                                lastModified = SmbTime.windowsToEpochMillis(info.lastWriteTime.windowsTimeStamp),
                            )
                        }
                        .sortedWith(compareByDescending<NvFileEntry> { it.isDir }.thenBy { it.name.lowercase() })
                        .toList()
        }

    /**
     * 打开随机读流：从 [offset] 起读 [path]。供 DataSource seek 使用——
     * seek 由 ExoPlayer 以新 position 重新调 openRead 实现，句柄随流 close 释放，
     * 连接链保留在池中复用（C8 §3.1 句柄/会话策略）。
     *
     * @throws IOException 连接/认证/打开失败
     */
    suspend fun openRead(source: SourceEntity, path: String, offset: Long): InputStream =
        withContext(io) { openReadBlocking(source, path, offset) }

    /**
     * [openRead] 的阻塞版本：供 ExoPlayer 加载线程直接调用（非协程上下文）。
     * 连接链来自池，open 只建立文件句柄（一次 seek = 一次定址 READ）。
     */
    /**
     * [lane] 连接通道隔离（2026-09-15 播放稳定性修复）：
     * 旧行为播放/抽帧/浏览共用一条连接链——任意一路出错触发 evict（关 session/share）时，
     * 正在播放的文件句柄随 DiskShare 一起被关闭 →「DiskShare has already been closed」
     * 播放断流（真机复现 2026-09-15）。现播放走 "play" 独立通道，浏览/抽帧走 "main"/"thumb"，
     * 互相的驱逐不再波及对方。代价：同源最多 2-3 条 SMB 会话（NAS 默认上限 128+，无风险）。
     */
    fun openReadBlocking(
        source: SourceEntity,
        path: String,
        offset: Long,
        maxBlockBytes: Int? = null,
        lane: String = LANE_MAIN,
        onStalled: (() -> Unit)? = null,
    ): InputStream {
        val target = SmbShareRoot.resolve(source.shareOrPath, path)
        val resolved = source.copy(shareOrPath = target.share)
        // 2026-09-15 根因修复（用户实测「画面卡死/进度条还在走」）：
        // 现场日志 `openRead …: DiskShare has already been closed`（SMBRuntimeException），
        // 且引擎的 io 自愈重试**同样失败**——因为池里那条连接链的 DiskShare 已死，而
        // `ensureConnected` 只校验 `connection.isConnected`（TCP 层仍为 true），于是每次都
        // 复用到这个死条目。旧实现的 `retryWithReconnect` 只服务于列目录且硬编码 LANE_MAIN，
        // 播放通道（LANE_PLAY）**没有任何驱逐/重建路径** → 一旦 share 被服务端空闲回收/会话
        // 上限踢掉，播放就永久卡死，只能靠用户手动拖进度条（重 open 走别的路径）或重进播放器。
        // 修法：识别「share/session 已关闭」类错误 → 按**本通道**驱逐该条目并重建一次再试。
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val key = keyOf(resolved) + lane
            val entry = try {
                ensureConnected(resolved, lane)
            } catch (e: Exception) {
                throw mapToIo(e, "openRead(session) $path")
            }
            var file: com.hierynomus.smbj.share.File? = null
            try {
                file = entry.share.openFile(
                    NvPaths.toSmb(target.path),
                    setOf(AccessMask.GENERIC_READ),
                    EnumSet.noneOf(com.hierynomus.msfscc.FileAttributes::class.java),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    // smbj 内部 EnumSet.copyOf 拒绝空普通集合，必须给空 EnumSet
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                )
                return SmbRandomReadStream(
                    SmbjReadHandle(file),
                    entry.maxReadSize,
                    offset,
                    // 抽帧路径显式 256KB（评审红线：播放块大小不得被降）；播放路径用用户显式
                    // 设置覆盖（NvNetTuning，未设置 = 默认 4MB）——评审 C3：contains() 门控语义
                    maxBlockBytes
                        ?: NvNetTuning.blockBytesOverride
                        ?: SmbReadMath.MAX_STREAM_BLOCK_BYTES,
                    enablePrefetch = NvNetTuning.prefetchEnabled,
                    onStalled = onStalled,
                )
            } catch (e: Exception) {
                runCatching { file?.close() }
                lastError = e
                if (attempt == 0 && isClosedHandleError(e)) {
                    // 死条目：驱逐本通道连接链后重建重试（红线：绝不动其他通道的池条目）
                    Log.d("NvPerf", "smb $lane share closed -> evict+reconnect: ${e.message}")
                    evict(key, entry)
                    stats.onReconnect()
                } else {
                    // 打开失败常见于句柄级错误（路径不存在等），无需驱逐整条连接
                    throw mapToIo(e, "openRead $path")
                }
            }
        }
        throw mapToIo(lastError ?: IOException("openRead 失败: $path"), "openRead $path")
    }

    /**
     * 单文件大小（字节；C8 §4.3：起播一次取长度 → NvDataSource.open 返回剩余总长，
     * Extractor 才能建精确/降级 SeekMap——坏索引 MKV 降级 seek 的前置）。失败返回 -1：
     * 长度缺失只降级 seek 质量，绝不阻断起播，故不驱逐连接、不重试（读路径自会自愈）。
     * 连接链走 LANE_MAIN（与浏览共用；播放通道 LANE_PLAY 保持纯读流专用）。
     */
    fun fileSizeBlocking(source: SourceEntity, path: String): Long = try {
        val target = SmbShareRoot.resolve(source.shareOrPath, path)
        val resolved = source.copy(shareOrPath = target.share)
        val entry = ensureConnected(resolved)
        // FileAllInformation.standardInformation.endOfFile = 文件字节数（javap 核实 smbj 0.14 API）
        entry.share.getFileInformation(NvPaths.toSmb(target.path)).standardInformation.endOfFile
    } catch (e: Exception) {
        Log.d("NvPerf", "fileSize fallback (-1): ${e.message}")
        -1L
    }

    /**
     * 「句柄/连接已失效」类错误识别（2026-09-15 播放卡死修复）：
     * smbj 在 DiskShare/Session/Connection/Tree 被关闭或失效后抛的异常文案各不相同，
     * 按关键词识别（比按异常类型稳：SMBRuntimeException 也被大量非连接类错误复用）。
     */
    private fun isClosedHandleError(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val m = t.message?.lowercase().orEmpty()
            if (m.contains("already been closed") ||
                m.contains("has been closed") ||
                m.contains("session") && m.contains("closed") ||
                m.contains("tree") && m.contains("disconnect") ||
                m.contains("broken pipe") ||
                m.contains("connection reset") ||
                m.contains("socket closed") ||
                m.contains("pipe closed")
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    /** 关闭全部缓存连接链（App 退出 / 源删除时调用） */
    fun closeAll() {
        val entries = pool.values.toList()
        pool.clear()
        // 同 evict：死连接上的 close 可阻塞至整体超时，退出/删源路径不等待
        entries.forEach { closeEntryAsync(it) }
    }

    /**
     * M3（2026-10-18 发布终审）：源删除/编辑保存后按池键**前缀**驱逐（对齐 SftpClient.evictFor
     * 语义）——前缀 = keyOf 去掉口令哈希（host+port+username+share），改密/改账号后旧键也能
     * 命中。此前删 SMB 源后其已认证连接存活到进程死（旧凭据仍可拉流，直至 OS 回收）。
     * 关闭走 evictExecutor 后台线程（死连接 close 可阻塞满超时，见 evictExecutor 注释）。
     * 非 SMB 源前缀天然不命中任何池键，空操作。
     */
    fun evictFor(source: SourceEntity) {
        val prefix = "smb://${source.username}@${source.host}:${source.portOr(SmbClientDefaults.PORT)}/${source.shareOrPath}/"
        pool.keys.filter { it.startsWith(prefix) }.forEach { key ->
            pool.remove(key)?.let { dead -> closeEntryAsync(dead) }
        }
    }

    // ---------- 连接链管理 ----------

    /**
     * 驱逐关闭专用守护线程池（2026-09-16 大幅 seek 卡 30s 修复）：
     * 真机取证（拖到 94% 处黑屏 30s）：NAS 中途踢会话后，自愈路径 evict() 在**播放 IO 线程**
     * 同步 close 死连接链——smbj 的 session/connection.close() 对半死传输要等满
     * SmbConfig.withTimeout（原 30s）才返回，期间 seek 全程挂起（日志 23:19:57.375 →
     * 23:20:27.501 整整 30.1s 静默）。旧条目此时已出池，关闭成败无人依赖 →
     * 改为后台 fire-and-forget，自愈路径只剩真正的重连（局域网 ~数百 ms）。
     * cached 池：play/browse/thumb 三通道同时驱逐不排队。
     */
    private val evictExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "NvSmbEvict").apply { isDaemon = true }
    }

    private fun closeEntryAsync(entry: PooledEntry) {
        closePartialAsync(entry.smbClient, entry.connection, entry.session, entry.share)
    }

    private fun closePartialAsync(
        smbClient: SMBClient,
        connection: com.hierynomus.smbj.connection.Connection?,
        session: com.hierynomus.smbj.session.Session? = null,
        share: DiskShare? = null,
    ) {
        evictExecutor.execute {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { smbClient.close() }
        }
    }

    private fun ensureConnected(source: SourceEntity, lane: String = LANE_MAIN): PooledEntry {
        val key = keyOf(source) + lane
        pool[key]?.let { entry ->
            if (entry.connection.isConnected) return entry
            // 半开连接（NAS 重启/空闲回收）：驱逐重建。
            // 注意：smbj 的 Session/DiskShare 都没有可靠的 isConnected（已用 javap 核实），
            // 所以「share 被服务端回收但 TCP 仍在」这类死条目无法在此探活——
            // 由 openReadBlocking 按异常识别后驱逐重建（见 isClosedHandleError）。
            evict(key, entry)
        }
        return createEntry(key, source)
    }

    private fun createEntry(key: String, source: SourceEntity): PooledEntry {
        val shareName = source.shareOrPath.trim().trimStart('/').ifEmpty {
            throw IOException("SMB 源未配置共享名: ${source.nickname}")
        }
            val smbClient = SMBClient(SmbClientDefaults.config())
            try {
                val port = source.portOr(SmbClientDefaults.PORT)
                val connection = smbClient.connect(source.host, port)
                try {
                    val session = connection.authenticate(authContextOf(source))
                    val share = session.connectShare(shareName) as DiskShare
                    val negotiated = connection.negotiatedProtocol
                    // 服务端协商 maxReadSize 与 8MB 消息上限取小（下限 64KB 兜底老 NAS）
                    val maxRead = negotiated.maxReadSize
                        .coerceIn(SmbClientDefaults.MIN_READ_CHUNK, SmbClientDefaults.MAX_MESSAGE_SIZE_BYTES)
                    val entry = PooledEntry(smbClient, connection, session, shareName, share, maxRead)
                    // 并发首开（video 与 side-load 字幕线程同时起播）时后到者让位：
                    // putIfAbsent 落败必须关掉自建整条连接链，否则它既不在池中也没人 close，
                    // socket + smbj IO 线程泄漏至进程结束（NAS 会话随之耗尽）。
                    val winner = pool.putIfAbsent(key, entry)
                    if (winner != null) {
                        Log.d("NvSmb", "connection race on $key: closing duplicate entry")
                        closeEntryAsync(entry)
                        return winner
                    }
                    stats.onSessionCreated()
                    // C8 §5.1 NvPerf：真实协商值决定流块大小（large MTU 下应为多 MB）
                    Log.d(
                        "NvPerf",
                        "negotiated maxReadSize=${negotiated.maxReadSize} " +
                            "maxTransactSize=${negotiated.maxTransactSize} dialect=${negotiated.dialect} " +
                            "streamBlock=${SmbReadMath.blockBytes(maxRead) / 1024}KB host=${source.host}"
                    )
                    return entry
                } catch (e: Exception) {
                    // 建链中途失败：剩余关闭同样可能撞上超时（同 evict 理由），异步化不阻塞重试
                    closePartialAsync(smbClient, connection)
                    throw e
                }
            } catch (e: Exception) {
                closePartialAsync(smbClient, null)
                throw e
            }
    }

    private fun evict(key: String, entry: PooledEntry) {
        if (pool.remove(key, entry)) stats.onEviction()
        closeEntryAsync(entry)
    }

    /** 操作执行 + 断线自愈：传输层失败驱逐重连一次（C8 §3.1 内部透明重试） */
    private suspend fun <T> retryWithReconnect(
        source: SourceEntity,
        block: suspend (DiskShare) -> T,
    ): T {
        val key = keyOf(source) + LANE_MAIN   // 复审 M2：与 ensureConnected 的池键同式（+lane），否则驱逐恒 no-op
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val entry = try {
                ensureConnected(source)
            } catch (e: Exception) {
                throw mapToIo(e, "session ${source.nickname}")
            }
            try {
                return block(entry.share)
            } catch (e: Exception) {
                lastError = e
                // 传输类错误或「share/session 已关闭」类错误才驱逐重建（BUG-C-R4-05）：
                // 业务错误（路径不存在/权限不足）时连接是好的，驱逐会连带踢断同链其他操作。
                // 2026-09-20 补 isClosedHandleError：DiskShare 被服务端空闲回收后抛的
                // SMBRuntimeException（"already been closed"）不带传输类类名，旧判断直接放行
                // 导致浏览路径报错不重连（播放路径 openReadBlocking 一直用的就是本函数，故正常）。
                // 分道后本函数只动 LANE_MAIN，播放流在 LANE_PLAY，驱逐互不影响。
                if (!isTransportError(e) && !isClosedHandleError(e)) throw mapToIo(e, "operation ${source.nickname}")
                evict(key, entry)
                if (attempt == 0) stats.onReconnect()
            }
        }
        throw mapToIo(lastError ?: IOException("SMB 操作失败"), "operation ${source.nickname}")
    }

    /** 传输类错误（断连/超时/重置/建链失败）才值得驱逐重建连接链；业务错误不驱逐 */
    private fun isTransportError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.io.EOFException ||
                t is java.net.SocketException ||
                t is java.net.SocketTimeoutException ||
                t is java.net.ConnectException ||
                t is javax.net.ssl.SSLException ||
                // smbj 的 transport/connection 包装异常（按类名判，避免硬依赖内部包结构）
                t.javaClass.simpleName.contains("TransportException") ||
                t.javaClass.simpleName.contains("ConnectionException")
            ) return true
            t = t.cause
        }
        return false
    }

    private fun authContextOf(source: SourceEntity): AuthenticationContext {
        val user = source.username.trim()
        if (user.isEmpty()) return AuthenticationContext.anonymous()
        return AuthenticationContext(
            user,
            com.novax.app.data.NvSecrets.decrypt(source.password).toCharArray(),
            source.domain.trim(),
        )
    }

    private fun mapToIo(e: Throwable?, what: String): IOException = when (e) {
        null -> IOException(what)
        is IOException -> e
        else -> IOException("$what: ${e.message}", e)
    }

    /** 共享枚举异常 → 中文友好错误（原始异常保留为 cause，供上层 mapConnectError 二次归类） */
    private fun mapShareError(e: Throwable, host: String): IOException {
        val msg = "${e.message.orEmpty()} ${e.cause?.message.orEmpty()}"
        val friendly = when {
            msg.contains("LOGON_FAILURE", true) || msg.contains("logon failure", true) ->
                "SMB 认证失败：账号或密码错误，请检查账号、密码与域设置"
            msg.contains("ACCESS_DENIED", true) || msg.contains("access denied", true) ->
                "无访问权限：服务器拒绝枚举共享，请确认账号权限"
            msg.contains("refused", true) ->
                "连接被拒绝：请检查 IP 与端口（SMB 默认 445，NAS 需开启 SMB 服务）"
            msg.contains("UnknownHost", true) || msg.contains("unknown host", true) ||
                msg.contains("unable to resolve", true) ->
                "无法解析主机：找不到 $host，请检查地址拼写或改用 IP"
            msg.contains("timed out", true) || msg.contains("timeout", true) ->
                "连接超时：服务器响应超时，请检查网络与 NAS 防火墙设置"
            msg.contains("unreachable", true) ->
                "网络不可达：无法路由到主机，请确认手机与 NAS 在同一局域网"
            else -> "共享列表获取失败: ${e.message ?: e.javaClass.simpleName}"
        }
        return IOException(friendly, e)
    }

    private fun SourceEntity.portOr(default: Int): Int = if (port in 1..65535) port else default
}

/** smbj 全局配置（Android 上必须绑定 slf4j-android，见 app/build.gradle.kts） */
private object SmbClientDefaults {
    const val PORT = 445
    const val MIN_READ_CHUNK = 64 * 1024

    /**
     * 大 MTU 消息上限 8MB：SMB3（large MTU）协商后 Windows/群晖/Samba 常给出 1~8MB 的
     * maxReadSize。smbj 0.14 无 maximumMessageSize API，READ 请求长度由 readBufferSize
     * 承担（Share.readAsync 按 min(readBufferSize, 服务端协商 maxReadSize) 截断，
     * 见 smbj Share.<init>/readAsync 字节码），故 read 侧给 8MB 上限、其余 1MB。
     */
    const val MAX_MESSAGE_SIZE_BYTES = 8 * 1024 * 1024

    fun config(): SmbConfig = SmbConfig.builder()
        // 2026-09-16：30s→20s（高于 SoTimeout 15s 让读超时先暴露；局域网请求本就在 ms 级，
        // 该值只决定「半死传输上的请求/关闭最坏等多久」——直接决定断线自愈的最坏时延）
        .withTimeout(20, TimeUnit.SECONDS)               // 整体超时
        .withSoTimeout(15, TimeUnit.SECONDS)             // socket 读超时（C8 §3.1 读超时 5~15s 量级）
        .withReadBufferSize(MAX_MESSAGE_SIZE_BYTES)      // READ 上限 8MB（实际取协商 maxReadSize）
        .withWriteBufferSize(SmbReadMath.TARGET_CHUNK_BYTES)
        .withTransactBufferSize(SmbReadMath.TARGET_CHUNK_BYTES)
        .withMultiProtocolNegotiate(true)                // SMB1 格式探测包协商（含 SMB3 large MTU 协商）
        // 2026-09-28 安全钉版（审查 P3）：显式钉死协商方言集 = SMB 2.0.2~3.1.1，杜绝 SMB1。
        // smbj 0.14 无 withMinimumProtocolVersion API——钉版的唯一途径就是方言集；
        // SMB2Dialect 枚举本身不含 SMB1 值，这里显式列举与 smbj 0.14 默认值一致
        // （运行时零变化），属防上游默认漂移的保险，并与 jcifs 轨 SMB202~SMB311 对齐。
        // withMultiProtocolNegotiate(true) 只改协商包格式（先发 SMB1 格式 COM_NEGOTIATE
        // 探测、载荷仍是上列 SMB2+ 方言），不影响版本下限。
        // signing/encrypt 维持关闭：局域网纯播放场景的兼容取舍（签名/加密会砍掉部分老 NAS）。
        .withDialects(
            SMB2Dialect.SMB_2_0_2,
            SMB2Dialect.SMB_2_1,
            SMB2Dialect.SMB_3_0,
            SMB2Dialect.SMB_3_0_2,
            SMB2Dialect.SMB_3_1_1,
        )
        .withSigningRequired(false)
        .withEncryptData(false)
        .build()
}
