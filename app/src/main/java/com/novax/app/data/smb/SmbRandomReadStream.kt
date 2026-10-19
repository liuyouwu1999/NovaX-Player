package com.novax.app.data.smb

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

/**
 * 随机定位读句柄抽象：生产实现 [SmbjReadHandle] 包 smbj File，单测用内存 fake。
 * [readAt] 返回实际读取字节数，EOF 返回 -1（smbj STATUS_END_OF_FILE 语义）；
 * 每次调用对应一次 SMB READ 网络往返。
 */
internal interface RandomReadHandle : AutoCloseable {
    fun readAt(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int
}

/** smbj 定位读实现：把 [com.hierynomus.smbj.share.File.read] 适配到 [RandomReadHandle] */
internal class SmbjReadHandle(
    private val file: com.hierynomus.smbj.share.File,
) : RandomReadHandle {
    override fun readAt(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int =
        file.read(data, fileOffset, offset, length)

    override fun close() {
        file.close()
    }
}

/**
 * SMB 随机读大块缓冲流（SMB 播放卡顿的性能核心修复）：
 *
 * 根因：ExoPlayer 以 64KB 为步长调用 read；旧实现每次 read 直接发一次 SMB READ
 * （局域网 1~3ms 往返 + smbj 分包/Java 开销），80Mbps 码率 = 每秒 160+ 次同步
 * 往返，加载线程被 RTT 打满必然饥饿卡顿。
 *
 * 新管线（C8 §3.1）：
 * - **大块缓冲**：内部块 = min(协商 maxReadSize, [SmbReadMath.MAX_STREAM_BLOCK_BYTES])（4MB）。
 *   read 优先从内存缓冲服务，缓冲空了才发 SMB READ 并把**整块填满**——
 *   一次网络往返服务 ~64 次 ExoPlayer 64KB read；协商 64KB 的老 NAS 自动退化仍可工作。
 * - **后台预读**：单线程 daemon executor + 原子槽位（1 块 lookahead）。主线程消费块 N 时
 *   预读线程已把块 N+1 读进槽位，块对上时直接换入（命中）；预读失败静默（失败槽标记 -1），
 *   主路径整块读时自然重试。
 * - **seek 语义**：ExoPlayer seek = close 旧流 + 新 position 重新 open = 本类新实例，
 *   缓冲与预读槽随实例丢弃，绝不跨 open 复用脏块。
 * - **统计**：bytesRead / 网络往返 / 预读命中每 10s 一条 NvPerf 日志（close 时补一条），
 *   真机可据此验证「往返次数 ≪ read 次数」。
 *
 * 线程模型：read 只发生在 ExoPlayer 加载线程；预读仅预读线程；close 可从任意线程。
 * 主读与预读并发使用同一 SMB2 文件句柄发起 READ 合法（SMB2 多路复用 + credit）。
 */
internal class SmbRandomReadStream(
    private val handle: RandomReadHandle,
    /** 协商 maxReadSize（已在连接层与 8MB 上限取小） */
    maxReadSize: Int,
    startPosition: Long,
    /** 块大小上限（测试注入小块用；生产 4MB） */
    maxBlockBytes: Int = SmbReadMath.MAX_STREAM_BLOCK_BYTES,
    /** 日志出口（测试注入空实现，避免 JVM 无 android.util.Log） */
    private val log: (String) -> Unit = { Log.d(TAG, it) },
    /** 预读开关（2026-09-15 设置「并发预读」真化；false = 仅主路径读块） */
    private val enablePrefetch: Boolean = true,
    /**
     * 读侧僵死回调（2026-09-15 用户实测修复）：连续读不到新字节时触发一次，
     * 由上层重建整条连接链并重新 prepare 播放器（见 [noteRead] 注释）。
     */
    private val onStalled: (() -> Unit)? = null,
) : InputStream() {

    /** 单块字节：一次 SMB 往返填充/服务这么多数据 */
    private val blockSize: Int = SmbReadMath.blockBytes(maxReadSize, maxBlockBytes)

    private var position: Long = startPosition

    // ---------- 服务槽（仅加载线程触碰） ----------
    private var serveBuf: ByteArray = ByteArray(blockSize)
    private var serveLen = 0
    private var servePos = 0
    private var eof = false

    // ---------- 预读槽（原子槽位，1 块 lookahead） ----------
    private val prefetchSlot = AtomicReference<Slot?>(null)
    private val prefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, PREFETCH_THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    @Volatile
    private var closed = false

    // ---------- 统计（NvPerf） ----------
    private val totalBytes = LongAdder()
    private val roundTrips = LongAdder()
    private val prefetchHits = LongAdder()
    private val readsServed = LongAdder()
    private var lastLogAt = System.nanoTime()

    /**
     * 一个预读槽位：起始文件偏移 + 数据。length=-1 表示预读失败（主路径重试），0 表示 EOF。
     * 以偏移为键（而非块号）：open/seek 的 position 通常不对齐块边界，命中判定必须用精确偏移。
     */
    private class Slot(val startOffset: Long, val bytes: ByteArray) {
        @Volatile
        var length: Int = Int.MIN_VALUE

        @Volatile
        var done = false
        val latch = CountDownLatch(1)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkClosed()
        if (len == 0) return 0
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off=$off len=$len")
        var served = 0
        while (served < len) {
            if (servePos >= serveLen) {
                if (eof) {
                    if (served == 0) {
                        noteRead(0)
                        return -1
                    }
                    break
                }
                if (!advance()) {
                    // 块首即 EOF：本次调用无数据可给
                    eof = true
                    if (served == 0) {
                        noteRead(0)
                        return -1
                    }
                    break
                }
            }
            val n = minOf(len - served, serveLen - servePos)
            System.arraycopy(serveBuf, servePos, b, off + served, n)
            servePos += n
            served += n
            position += n
        }
        totalBytes.add(served.toLong())
        readsServed.increment()
        maybeLog()
        noteRead(served)
        return served
    }

    // ---------------- 读侧僵死看门狗（2026-09-15 用户实测「画面卡住+进度条在走」修复）----------------
    // 真机现场（第05集/低俗小说）：DiskShare 被服务端关闭 → 播放流挂死句柄 → 重建后
    // `bytes=0 netRtt=0 served=0`（一个字节都读不到）→ 解码器无数据不出帧 → **画面永久冻住**，
    // 而播放器仍报 playing=true / buffering=false / position 正常推进（播放器状态完全不可信），
    // 连 seek 都救不回来（重建连接后依然读 0）。
    // 因此僵死判据必须落在**读侧**：连续 STALL_READS 次读调用期间读到的字节数没有增长
    // → 触发 [onStalled]（由上层重建连接链并重新 prepare）。这是不会骗人的信号。

    @Volatile
    private var lastDataAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastDataPosition: Long = startPosition

    /** 连续多少次 read 调用没有取到任何新字节（正常播放时恒为 0） */
    private val stalledReads = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 已读到「短块 EOF」（读到过真实数据、只是到了文件尾）时置位。
     * 此时后续 read 返回 -1 是播放自然收尾，不参与僵死计数——否则片尾 demuxer
     * 反复读到 EOF 会误触发 onStalled → 引擎级 forceRePrepare（播完前无谓重载）。
     * 句柄僵死的特征是「一个字节都读不到」（got==0），不会置位本标志，仍照常计数自愈。
     */
    @Volatile
    private var eofShortBlock = false

    /** 僵死回调只触发一次（避免每次 read 都触发）；恢复读到数据后自动重新武装 */
    @Volatile
    private var stallNotified = false

    /**
     * 记录一次读结果并判定僵死。真机判据（保守，避免误报）：
     * 连续 [STALL_READS] 次读调用「本次 0 字节 **且** 全局读到的字节数没有增长」才算僵死——
     * 正常播放时即使某次 read 恰好返回 0，全局计数也在增长，不会命中。
     */
    private fun noteRead(served: Int) {
        if (closed) return
        if (served == 0 && eofShortBlock) return // 短块已证实文件尾：自然收尾，不算僵死
        val now = System.currentTimeMillis()
        val totalNow = totalBytes.sum()
        if (served > 0) {
            lastDataAtMs = now
            lastDataPosition = position
            stalledReads.set(0)
            stallNotified = false
            return
        }
        // 本次 0 字节：看全局字节数是否仍在增长（预读线程也会增加 totalBytes）
        if (totalNow != lastServedTotal) {
            lastServedTotal = totalNow
            lastDataAtMs = now
            stalledReads.set(0)
            stallNotified = false
            return
        }
        if (now - lastDataAtMs < STALL_GRACE_MS) return   // 宽限期内的短读不算僵死
        val n = stalledReads.incrementAndGet()
        if (n >= STALL_READS && !stallNotified) {
            stallNotified = true
            log(
                "smb stream STALLED: no bytes for ${now - lastDataAtMs}ms " +
                    "pos=$position servedTotal=$totalNow reads=$n closed=$closed",
            )
            runCatching { onStalled?.invoke() }
        }
    }

    @Volatile
    private var lastServedTotal: Long = 0L

    /**
     * 把「从当前 [position] 起的至多一个块」装入服务槽。优先取预读槽（起始偏移对上且成功=命中）；
     * 未命中则主路径整块填满（成功后为下一段投递预读）。返回 false = 该位置为 EOF。
     *
     * 【关键】填充起点必须是**精确的 position**（open/seek 的原始值，通常不对齐块边界）——
     * 回归教训：曾按块边界 block*blockSize 填充，非对齐 seek（如 MP4 moov 位于文件尾）
     * 会把「当前位置之前」的脏数据当请求字节返回，MP4/MKV 解析器读出垃圾 box 头后
     * 跳到文件外的荒谬位置报"容器解析失败"。预读槽同样以文件偏移为键。
     */
    private fun advance(): Boolean {
        val thisOffset = position
        val p = prefetchSlot.getAndSet(null)
        if (p != null && p.startOffset == thisOffset) {
            // 预读通常早已完成；未完成则等（省一次重复网络读），失败/EOF 语义见 Slot
            p.latch.await(PREFETCH_WAIT_SECONDS, TimeUnit.SECONDS)
            if (p.done && p.length > 0) {
                prefetchHits.increment()
                val freed = serveBuf
                serveBuf = p.bytes
                serveLen = p.length
                servePos = 0
                if (p.length < blockSize) { eof = true; eofShortBlock = true } // 短块 = 文件尾，锁存 EOF 防重复服务末块
                else kickPrefetch(thisOffset + blockSize, freed)  // 换下的旧缓冲复用为下一预读槽（稳态零分配）
                return true
            }
            if (p.done && p.length == 0) return false // 预读已确认 EOF
            // 预读失败（length<0）或等待超时：静默丢弃，走主路径重试
        }
        checkClosed() // close 与主路径读的窄竞态：不再对已关句柄发起新 READ
        // 主路径：从精确 position 整块填满（每次 handle.readAt = 1 次 SMB 往返，server 短读时循环补满）
        val buf = serveBuf
        var got = 0
        while (got < blockSize) {
            val n = try {
                roundTrips.increment()
                handle.readAt(buf, thisOffset + got, got, blockSize - got)
            } catch (e: Exception) {
                throw IOException("SMB READ 失败 (offset=${thisOffset + got})", e)
            }
            if (n <= 0) break // n==0 按流结束处理：防底层异常返回 0 时此处自旋死循环
            got += n
        }
        if (got <= 0) return false
        serveLen = got
        servePos = 0
        if (got < blockSize) { eof = true; eofShortBlock = true } else kickPrefetch(thisOffset + blockSize, null)
        return true
    }

    /**
     * 为 [nextBlock] 投递预读：槽位空闲（CAS 成功）才投，避免覆盖未消费的旧槽。
     * [buffer] 复用换下来的服务缓冲（null = 新分配）。槽已在引用里则直接返回。
     * close 后绝不投递：CAS 后复检 closed（回滚槽位），任务内每块填充前复检 closed，
     * 保证 close 之后不再向 smbj 发起任何 READ。
     */
    private fun kickPrefetch(nextOffset: Long, buffer: ByteArray?) {
        if (closed || !enablePrefetch) return
        val slot = Slot(nextOffset, buffer ?: ByteArray(blockSize))
        if (!prefetchSlot.compareAndSet(null, slot)) return
        if (closed) {
            // close 与投递的窄竞态：回滚槽位、不向线程池投递任何 smbj 调用
            prefetchSlot.compareAndSet(slot, null)
            failSlot(slot)
            return
        }
        try {
            prefetchExecutor.execute {
                var got = 0
                var failed = false
                while (got < blockSize) {
                    if (closed) {
                        failed = true // close 后不再发起新 READ（在途块按预读失败处理）
                        break
                    }
                    val n = try {
                        roundTrips.increment()
                        handle.readAt(slot.bytes, nextOffset + got, got, blockSize - got) // bufferOffset=got：短读续读不得覆盖已读段（BUG-C-R4-02）
                    } catch (t: Exception) {
                        failed = true // 预读失败静默：主路径整块读时重试
                        break
                    }
                    if (n <= 0) break // 同主路径：n==0 防自旋
                    got += n
                }
                slot.length = if (failed) -1 else got
                slot.done = true
                slot.latch.countDown()
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            failSlot(slot) // close 与 execute 的窄竞态：按预读失败处理
        }
    }

    private fun failSlot(slot: Slot) {
        slot.length = -1
        slot.done = true
        slot.latch.countDown()
    }

    override fun close() {
        if (closed) return
        closed = true
        // 未消费的预读槽补发完成信号（等待中的主路径立即看到失败→closed 抛出）
        prefetchSlot.getAndSet(null)?.let { failSlot(it) }
        // 先停预读线程（cancel + join ≤200ms）：close 返回后预读线程不会再发起任何
        // smbj READ（多块填充在块间检查 closed 提前退出），handle.close 不与在途读竞争
        prefetchExecutor.shutdown()
        try {
            if (!prefetchExecutor.awaitTermination(CLOSE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                prefetchExecutor.shutdownNow() // 极端慢读在途：中断兜底，任务内 closed 检查保证不再发新 READ
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            prefetchExecutor.shutdownNow()
        }
        logStats(force = true)
        runCatching { handle.close() }
    }

    override fun available(): Int = (serveLen - servePos).coerceAtLeast(0)

    // ---------- 统计 ----------

    private fun maybeLog() {
        val now = System.nanoTime()
        if (now - lastLogAt >= 10_000_000_000L) logStats(force = false)
    }

    private fun logStats(force: Boolean) {
        if (!force && totalBytes.sum() == 0L) return
        lastLogAt = System.nanoTime()
        val bytes = totalBytes.sum()
        val rtt = roundTrips.sum()
        val hits = prefetchHits.sum()
        val rate = if (readsServed.sum() > 0) hits * 100 / readsServed.sum() else 0
        log(
            "smb stream: block=${blockSize shr 10}KB bytes=$bytes netRtt=$rtt " +
                "prefetchHit=$hits($rate%) served=${readsServed.sum()} pos=$position closed=$force"
        )
    }

    /** 单测观察用计数器（同模块 internal 可见） */
    internal fun networkRoundTrips(): Long = roundTrips.sum()
    internal fun prefetchHitCount(): Long = prefetchHits.sum()
    internal fun prefetchSlotStartOffset(): Long? = prefetchSlot.get()?.startOffset

    private fun checkClosed() {
        if (closed) throw IOException("SMB stream closed")
    }

    companion object {
        private const val TAG = "NvPerf"
        private const val PREFETCH_THREAD_NAME = "nvx-smb-prefetch"
        private const val PREFETCH_WAIT_SECONDS = 30L

        /** close 停预读线程的 join 超时：正常预读一次 READ ≤ 数百 ms，200ms 内未退出则中断兜底 */
        private const val CLOSE_JOIN_TIMEOUT_MS = 200L

        /** 读侧僵死：连续多少次「0 字节且全局字节数不增长」的 read 判定为僵死 */
        internal const val STALL_READS = 3

        /** 读侧僵死宽限期（毫秒）：正常短读/切换位置期间不误判 */
        internal const val STALL_GRACE_MS = 3_000L
    }
}
