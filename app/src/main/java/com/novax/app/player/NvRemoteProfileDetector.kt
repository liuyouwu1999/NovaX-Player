package com.novax.app.player

import android.util.Log
import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

/**
 * 远程链路探测（2026-09-15 远程模式自适应）：
 *
 * 用户经 Tailscale/外网访问家庭 NAS 时，LAN 缓冲档不再合适——外网抖动大，600ms 的
 * rebuffer 恢复门槛会造成「停-起-停」的顿挫。本探测器在**起播前**读一次 256KB：
 * - 用时 ≤ [PROBE_REMOTE_THRESHOLD_MS] → 视为 LAN，维持原档；
 * - 用时 >  阈值（≈  sustained <3.4Mbps）→ 判定远程，换 REMOTE 缓冲档
 *   （rebuffer 恢复 2s + 前向目标 90s 持续抢跑）。
 *
 * 探测失败（网络错误）→ 不覆盖（保持原档，让既有错误链路接管）；结果按 sourceId
 * 缓存 [CACHE_TTL_MS]（同源连播不重复探测，10 分钟过期自动重测——用户回家/出门自动切换）。
 */
object NvRemoteProfileDetector {

    private const val TAG = "NvPerf"
    private const val PROBE_BYTES = 256 * 1024

    /** 判定阈值：256KB 超过 600ms（≈ 持续带宽 <3.4Mbps）→ 远程 */
    internal const val PROBE_REMOTE_THRESHOLD_MS = 600L

    /** 探测结果缓存时长：同源 10 分钟内复用（回家/出门场景自动重测） */
    internal const val CACHE_TTL_MS = 10 * 60_000L

    private val cache = ConcurrentHashMap<Long, Pair<Boolean, Long>>()

    internal fun isRemoteLink(elapsedMs: Long): Boolean = elapsedMs > PROBE_REMOTE_THRESHOLD_MS

    internal fun remoteProfileOf(base: BufferProfile): BufferProfile? = when (base) {
        BufferProfile.SMB -> BufferProfile.SMB_REMOTE
        BufferProfile.WEBDAV_HTTP -> BufferProfile.WEBDAV_HTTP_REMOTE
        else -> null
    }

    /**
     * 返回需要覆盖的远程档（null = 维持 base 档）。仅 SMB/WEBDAV 探测，LOCAL/SFTP 不适用。
     * 挂起函数：调用方须在 IO 上下文（PlayerActivity.startPlayback 已在 IO）。
     */
    suspend fun classify(source: SourceEntity, path: String, repo: NvSourceRepository, base: BufferProfile): BufferProfile? {
        if (base != BufferProfile.SMB && base != BufferProfile.WEBDAV_HTTP) return null
        cache[source.id]?.let { (remote, at) ->
            if (clock() - at < CACHE_TTL_MS) return if (remote) remoteProfileOf(base) else null
        }
        var ok = false
        val elapsedMs = withContext(Dispatchers.IO) {
            measureTimeMillis {
                runCatching {
                    // 2026-09-28 偷跑流量修复（F7/P3）：补 maxBlockBytes=PROBE_BYTES——漏传时
                    // SMB 整块填满 + 预读 ≈8MB/次探测；256KB 恰好覆盖探测读长，零读放大
                    repo.openStreamBlocking(
                        source,
                        path,
                        0L,
                        maxBlockBytes = PROBE_BYTES,
                        lane = SmbClient.LANE_PLAY,
                    ).use { ins ->
                        val buf = ByteArray(PROBE_BYTES)
                        var off = 0
                        while (off < buf.size) {
                            val n = ins.read(buf, off, buf.size - off)
                            if (n <= 0) break // n==0 按流结束处理：防自旋死循环
                            off += n
                        }
                        ok = off > 0
                    }
                }
            }
        }
        if (!ok) return null   // 读失败 fail-open：保持原档，错误由既有链路接管
        val remote = isRemoteLink(elapsedMs)
        cache[source.id] = remote to clock()
        Log.d(TAG, "remote probe: ${PROBE_BYTES / 1024}KB in ${elapsedMs}ms → ${if (remote) "REMOTE" else "LAN"} profile")
        return if (remote) remoteProfileOf(base) else null
    }

    internal fun clock(): Long = System.currentTimeMillis()
}
