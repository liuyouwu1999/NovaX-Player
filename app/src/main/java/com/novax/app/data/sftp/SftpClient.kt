package com.novax.app.data.sftp

import android.util.Log
import com.novax.app.data.NvFileEntry
import com.novax.app.data.NvPaths
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.smb.NvNetTuning
import com.novax.app.data.smb.SmbRandomReadStream
import com.novax.app.data.smb.SmbReadMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * SFTP 客户端（sshj 封装）——对齐 [com.novax.app.data.smb.SmbClient] 的 API（2026-09-29 落地）。
 *
 * 连接模型：进程内按 "host:port:user:root" 维护常驻 [SSHClient]（其上开一条 SFTP 子系统通道）
 * 缓存；DataSource 每次打开的 RemoteFile 句柄挂在缓存的 SFTPClient 上，seek（重新 open）
 * 只发定址 READ，绝不重新 connect/authenticate。连接建立次数 = 播放会话数。
 *
 * 与 SMB 的差异（kdoc 明示）：**单通道**，不区分 SMB 的 play/main/thumb 连接通道——
 * sshj 的请求/应答按 id 多路复用，一条 SFTP 子系统通道上并发读写天然安全；NAS 的 SFTP
 * 子系统并发上限普遍低于 SMB 会话上限，多开通道收益为负。故 [openReadBlocking] 不带
 * lane 参数，浏览与播放共用条目；驱逐只发生在探活失败（[ensureConnected]）时。
 *
 * 读路径：[SftpReadHandle] 适配 sshj RemoteFile 到 smb 的 [SmbRandomReadStream]，
 * 大块缓冲/预读/僵死看门狗整条管线复用（单请求读长 256KB，见 [SFTP_MAX_READ_BYTES] kdoc）。
 *
 * Host key 校验：TOFU（trust on first use）——首连把服务器公钥指纹（SHA-256，OpenSSH
 * known_hosts 同款 `SHA256:<b64无填充>` 格式）记入 filesDir 下 sftp_known_hosts
 * （[SftpKnownHosts]；不用 DataStore：连接建立有阻塞路径，DataStore 同步读需 runBlocking），
 * 复连一致放行、不一致抛中文可行动错误；删除/编辑源时清对应指纹行（[evictFor]），
 * 「删除源重新添加」即重置信任。known-hosts 未初始化时直接抛错拒绝建链（E2-2，
 * 不再回落 accept-all：attach 已前移 NvApp.onCreate，正常路径必先初始化）。
 *
 * 联调注记：list/open/fileSize 的**真实服务器路径**（家目录相对路径、非 ASCII 文件名、
 * symlink、权限拒绝等）待真源联调验证（一期仅 JVM 单测覆盖纯函数与内存 fake 语义）。
 */
class SftpClient(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** known-hosts 指纹文件（filesDir 下）；null = 未初始化，建链直接抛错（E2-2，见类 kdoc） */
    private val knownHosts: File? = null,
) {

    private class PooledEntry(val ssh: SSHClient, val sftp: SFTPClient)

    private val pool = ConcurrentHashMap<String, PooledEntry>()

    /** TOFU 指纹存储（[knownHosts] 未注入时为 null，见类 kdoc） */
    private val knownHostsStore: SftpKnownHosts? = knownHosts?.let(::SftpKnownHosts)

    /** 死连接关闭走后台守护线程（对齐 SmbClient evict 理由：半死 socket 上的 close 可阻塞满超时） */
    private val closeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NvSftpClose").apply { isDaemon = true }
    }

    /** 池键前缀（不含口令哈希）：[evictFor] 按前缀匹配驱逐；[keyOf] = 前缀 + 口令哈希 */
    private fun poolPrefixOf(source: SourceEntity): String =
        "sftp://${source.username.trim()}@${source.host.trim()}:${portOf(source)}/${source.shareOrPath.trim()}/"

    /** 池键：同 host+port+username+root 复用同一条 SSH 连接（口令变更 → 新键，不串号） */
    fun keyOf(source: SourceEntity): String =
        poolPrefixOf(source) +
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(com.novax.app.data.NvSecrets.decrypt(source.password).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

    /**
     * 验证源可连：建立/复用连接并探活根——配了根路径时 stat 该目录（提前暴露路径错误），
     * 未配根 stat "."（家目录）。成功后条目留在池中供 list/openRead 复用。
     * H-1（2026-10-18 发布终审）：stat 撞上连接死亡（半开 TCP）时驱逐重试一次——
     * 探活 isAlive 探不出半开，不驱逐会让「测试连接」在本会话内反复假失败。
     */
    suspend fun connect(source: SourceEntity): Result<Unit> = withContext(io) {
        runCatching {
            try {
                probeRoot(source)
            } catch (e: Exception) {
                if (!isConnectionDeath(e) || e is CancellationException) throw e
                evictEntry(source)
                Log.w("NvSftp", "connect hit dead link, evicted+retry: ${e.message}")
                probeRoot(source)
            }
        }.recoverCatching { e -> throw mapToIo(e, "connect ${source.nickname}") }
    }

    private fun probeRoot(source: SourceEntity) {
        val entry = ensureConnected(source)
        val root = SftpPaths.remotePath(source.shareOrPath.trim(), "/")
        val attrs = entry.sftp.stat(root)
        // FileMode.Type 为普通枚举（javap 核实）：DIRECTORY/REGULAR/SYMLINK/…
        require(attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            "源根不是目录: ${source.shareOrPath} (${source.nickname})"
        }
    }

    /** 列目录（[dirPath] 为统一路径表示，"/" 为源根）。目录在前、名称排序，与其他源一致。
     *  H-1：连接死亡（半开 TCP 上 ls 才炸）驱逐重建后重试一次——对齐 SmbClient 的
     *  isClosedHandleError 驱逐重试体系 */
    suspend fun list(source: SourceEntity, dirPath: String): Result<List<NvFileEntry>> =
        runCatching {
            withContext(io) {
                val dir = NvPaths.asDirectory(dirPath)
                val remote = SftpPaths.remotePath(source.shareOrPath, dirPath)
                try {
                    lsInto(source, remote)
                } catch (e: Exception) {
                    if (!isConnectionDeath(e) || e is CancellationException) throw e
                    evictEntry(source)
                    Log.w("NvSftp", "list hit dead link, evicted+retry: ${e.message}")
                    lsInto(source, remote)
                }
                    .asSequence()
                    .filter { it.name != "." && it.name != ".." && it.name.isNotBlank() }
                    .map { SftpEntries.toEntry(dir, it) }
                    .sortedWith(compareByDescending<NvFileEntry> { it.isDir }.thenBy { it.name.lowercase() })
                    .toList()
            }
        }.recoverCatching { e -> throw mapToIo(e, "list ${source.nickname} $dirPath") }

    private fun lsInto(source: SourceEntity, remotePath: String) =
        ensureConnected(source).sftp.ls(remotePath)

    /** 打开随机读流（从 [offset] 起）。协程版：跑 [io] 调度器 */
    suspend fun openRead(source: SourceEntity, path: String, offset: Long): InputStream =
        withContext(io) { openReadBlocking(source, path, offset) }

    /**
     * [openRead] 的阻塞版本：供 ExoPlayer 加载线程直接调用（非协程上下文）。
     * 无 lane 参数（单通道，见类 kdoc）；僵死回调语义与 SMB 相同。
     * H-1（2026-10-18 发布终审）：open 撞上连接死亡（半开 TCP——探活 isAlive 探不出，
     * 只有真发请求才炸）时驱逐重建后重试一次。中流读死不在此处：流抛错 → ExoPlayer
     * stall/forceRePrepare → 重新 openReadBlocking 走到本驱逐重试 → 恢复（对齐 SmbClient
     * 「DiskShare has already been closed」驱逐重链同款自愈闭环）。
     */
    fun openReadBlocking(
        source: SourceEntity,
        path: String,
        offset: Long,
        maxBlockBytes: Int? = null,
        onStalled: (() -> Unit)? = null,
    ): InputStream {
        val entry = try {
            ensureConnected(source)
        } catch (e: Exception) {
            throw mapToIo(e, "openRead(session) $path")
        }
        val remote = SftpPaths.remotePath(source.shareOrPath, path)
        val file = try {
            entry.sftp.open(remote, EnumSet.of(net.schmizz.sshj.sftp.OpenMode.READ))
        } catch (e: Exception) {
            if (e is CancellationException || !isConnectionDeath(e)) throw e
            evictEntry(source)
            Log.w("NvSftp", "openRead hit dead link, evicted+retry: $path (${e.message})")
            try {
                ensureConnected(source).sftp.open(remote, EnumSet.of(net.schmizz.sshj.sftp.OpenMode.READ))
            } catch (e2: Exception) {
                throw mapToIo(e2, "openRead $path")
            }
        }
        try {
            return SmbRandomReadStream(
                SftpReadHandle(file),
                // SFTP 单请求读长 256KB（见 SFTP_MAX_READ_BYTES）；块上限照走 SMB 调优链
                SFTP_MAX_READ_BYTES,
                offset,
                maxBlockBytes
                    ?: NvNetTuning.blockBytesOverride
                    ?: SmbReadMath.MAX_STREAM_BLOCK_BYTES,
                enablePrefetch = NvNetTuning.prefetchEnabled,
                onStalled = onStalled,
            )
        } catch (e: Exception) {
            runCatching { file.close() }
            throw mapToIo(e, "openRead $path")
        }
    }

    /**
     * 单文件大小（字节；未知/失败 -1，同 SMB/WebDAV 契约：长度缺失只降级 seek 质量，
     * 不阻断起播）。SFTPClient.stat().size（SSH_FXP_STAT SIZE 位必含常规文件大小）。
     */
    fun fileSizeBlocking(source: SourceEntity, path: String): Long = try {
        val entry = ensureConnected(source)
        val size = entry.sftp.stat(SftpPaths.remotePath(source.shareOrPath, path)).size
        if (size >= 0) size else -1L
    } catch (e: Exception) {
        Log.d("NvPerf", "sftp fileSize fallback (-1): ${e.message}")
        -1L
    }

    /** 关闭全部缓存连接（App 退出 / 源删除时调用）；SFTPClient.close 只关子系统通道，随 disconnect 一并回收 */
    fun closeAll() {
        val entries = pool.values.toList()
        pool.clear()
        entries.forEach { entry ->
            closeExecutor.execute {
                runCatching { entry.sftp.close() }
                runCatching { entry.ssh.disconnect() }
            }
        }
    }

    /**
     * 源删除/编辑保存时驱逐（NvSourceRepository.evictSftp 转发）：按池键**前缀**（不含
     * 口令哈希——改密后旧键也能命中）移除匹配连接并后台关闭；同时清该 host:port 的指纹行，
     * 「删除源重新添加」即重置 TOFU 信任（见类 kdoc）。
     */
    fun evictFor(source: SourceEntity) {
        val prefix = poolPrefixOf(source)
        pool.keys.filter { it.startsWith(prefix) }.forEach { key ->
            pool.remove(key)?.let { dead -> closeEntryAsync(dead) }
        }
        knownHostsStore?.remove(source.host.trim(), portOf(source))
    }

    // ---------- 连接管理 ----------

    /**
     * 取活条目，死/缺则建（compute 原子段：并发首开只建一条连接，败者直接复用胜者条目，
     * 不会像各自 putIfAbsent 那样泄漏没人 close 的 socket）。探活 = TCP 连在且 SSH 传输层在跑。
     */
    private fun ensureConnected(source: SourceEntity): PooledEntry {
        val key = keyOf(source)
        pool[key]?.let { if (isAlive(it)) return it }
        return pool.compute(key) { _, existing ->
            if (existing != null && isAlive(existing)) {
                existing
            } else {
                existing?.let { dead -> closeEntryAsync(dead) }   // 探活失败：旧条目后台关闭
                createEntry(source)
            }
        }!!
    }

    private fun isAlive(entry: PooledEntry): Boolean =
        entry.ssh.isConnected && entry.ssh.transport.isRunning

    /** H-1：按当前源池键精确驱逐（evictFor 的单键版：不动指纹，只杀连接） */
    private fun evictEntry(source: SourceEntity) {
        pool.remove(keyOf(source))?.let { dead -> closeEntryAsync(dead) }
    }

    /**
     * H-1（2026-10-18 发布终审）：判定「连接已死」类异常——半开 TCP 上探活探不出，
     * 只有真发请求时才以这些形态炸出。TransportException/ConnectionException=sshj 传输层
     * 死亡；SocketException/EOFException=底层 socket 断裂（reset/broken pipe/对端关闭）。
     * 刻意不判死：SocketTimeoutException（读超时是慢/抖，驱逐重连救不了还会白白重建）、
     * SFTPException（路径不存在/权限拒绝等业务失败）、认证异常——重连同样结果。
     */
    internal fun isConnectionDeath(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            when (t) {
                is net.schmizz.sshj.transport.TransportException,
                is net.schmizz.sshj.connection.ConnectionException,
                is java.net.SocketException,
                is java.io.EOFException,
                -> return true
                else -> {
                    t = t.cause
                    depth++
                }
            }
        }
        return false
    }

    /** 建链：connect → authPassword → newSFTPClient；中途失败把已建资源关干净再抛 */
    private fun createEntry(source: SourceEntity): PooledEntry {
        val user = source.username.trim()
        val ssh = SSHClient()
        // E2-2（发版审查）：known-hosts 未初始化一律拒绝建链，不再回落 accept-all
        // （任意主机密钥放行）校验器。attach 已前移到 NvApp.onCreate（先于一切
        // repository()），此分支仅剩防御意义。
        val store = requireNotNull(knownHostsStore) {
            "SFTP known-hosts 未初始化（NvGraph.attach 未执行），拒绝以 accept-all 建链"
        }
        // sshj 不带默认 verifier（0.38.0 字节码核实），TOFU 校验器是唯一守门者
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(store, source.host.trim(), portOf(source)))
        ssh.setConnectTimeout(10_000)
        ssh.setTimeout(15_000)   // socket 读超时（对齐 SMB SoTimeout 量级）
        // 60s 心跳（sshj keepalive 线程，connect 时见间隔>0 才启动）：防 NAT/防火墙把空闲连接静默掐掉
        ssh.connection.keepAlive.setKeepAliveInterval(60)
        try {
            ssh.connect(source.host.trim(), portOf(source))
            if (user.isNotEmpty()) {
                ssh.authPassword(user, com.novax.app.data.NvSecrets.decrypt(source.password))
            }
            return PooledEntry(ssh, ssh.newSFTPClient())
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            throw e
        }
    }

    private fun closeEntryAsync(entry: PooledEntry) {
        closeExecutor.execute {
            runCatching { entry.sftp.close() }
            runCatching { entry.ssh.disconnect() }
        }
    }

    private fun mapToIo(e: Throwable?, what: String): IOException = when (e) {
        null -> IOException(what)
        // 取消透传：协程取消不得降级成 IOException（connect/list 的 recoverCatching 与阻塞路径共用本映射）
        is CancellationException -> throw e
        is IOException -> e
        else -> IOException("$what: ${e.message}", e)
    }

    private fun portOf(source: SourceEntity): Int =
        if (source.port in 1..65535) source.port else DEFAULT_PORT

    companion object {
        const val DEFAULT_PORT = 22

        /**
         * SFTP 单次 READ 的请求长度上限 256KB：SFTP READ 响应受服务端消息上限约束
         * （OpenSSH sftp-server 消息上限 256KB，更老实现 64KB；请求里的 length 只是 uint32
         * 字段，规范服务端自行截短应答——但个别 NAS 实现对超长请求直接断连）。256KB 下
         * SmbRandomReadStream 块 = 256KB，每块 1~2 次往返，千兆局域网吞吐仍然打满，
         * 兼容性风险最小（sshj 自家下载管线同样是「短读自适应」思路）。
         */
        const val SFTP_MAX_READ_BYTES = 256 * 1024
    }
}

/**
 * RemoteResourceInfo → 统一 NvFileEntry 映射（纯函数，可单测；SftpClient.list 委托至此）。
 * mtime：sshj getMtime 为 epoch **秒** → 毫秒（*1000）；服务器未回该标志时为 0（源不可提供时 0 的统一契约）。
 * 目录条目：路径带尾 "/"、size 恒 0（对齐 SMB/WebDAV/本地三路映射口径）。
 */
internal object SftpEntries {
    fun toEntry(dirPath: String, info: net.schmizz.sshj.sftp.RemoteResourceInfo): NvFileEntry {
        val isDir = info.isDirectory()   // isXxx() 用方法调用（Kotlin 对 boolean is-getter 不一定映射属性语法）
        return NvFileEntry(
            name = info.name,
            path = NvPaths.join(dirPath, info.name) + if (isDir) NvPaths.SEPARATOR else "",
            isDir = isDir,
            size = if (isDir) 0L else info.attributes.size,
            lastModified = info.attributes.mtime * 1000L,
        )
    }
}
