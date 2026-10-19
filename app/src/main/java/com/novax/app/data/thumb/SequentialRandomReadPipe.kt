package com.novax.app.data.thumb

import java.io.IOException
import java.io.InputStream

/**
 * 抽帧随机读常驻流管线（2026-09-15 读放大修复，评审 C-1/C-2 定型）：
 *
 * 旧行为：NvMediaDataSource 每次 readAt 独立开定位流、只消费 length 字节即关——SMB 块流
 * 会把整块（4MB）填满后随 close 丢弃，KB 级容器读产生最高 64× 读放大，且每次 readAt
 * 附带一次 SMB CREATE+CLOSE。这是「NAS 磁盘无谓损耗 + 手机发热」的主力。
 *
 * 新行为：常驻一条流按位置连续服务——readAt(offset) 与流当前位置一致时直接续读
 * （容器解析几乎全为顺序读，零重开零放大），错位（回跳/前跳）才 close + 按新 offset 重开；
 * IO 异常关流置空后重抛，下次 readAt 按请求 offset 自动重开（自愈）；close 幂等。
 *
 * 线程模型：MediaDataSource.readAt/getSize/close 不承诺单线程——本类所有入口 synchronized。
 * 纯 JVM 可测：open 以 (Long) -> InputStream lambda 注入（NvSourceRepository 是 final 类不 mock）；
 * open 计数暴露给单测断言「顺序读只开 1 次流」。
 */
internal class SequentialRandomReadPipe(
    /** 按文件偏移开定位流（生产 = repo.openStreamBlocking(source, path, offset)） */
    private val open: (Long) -> InputStream,
    /** 累计读字节预算（0=不限）。远程抽帧的流量保险丝（2026-09-28 后台偷跑流量事故：
     * MMR 对 moov 在尾部的 mp4 有 1.5×+ 顺序读放大，实测 55MB 片抽一张封面拉 85MB）——
     * 烧断即永久熔断（不可复位、关底层流），上层收 IOException → 抽帧落 FAILED 占位 */
    private val maxBytes: Long = 0L,
) : AutoCloseable {

    private val lock = Any()
    private var stream: InputStream? = null

    /** stream 的下一个字节对应文件偏移；无流 = -1 */
    private var streamPos: Long = -1L
    private var closed = false

    /** 累计服务字节(含跳过;预算判据;internal 供单测断言) */
    internal var servedBytesTotal: Long = 0L
        private set

    /** 累计跳过字节(§1b 观测:真实流量口径变化的关键计数) */
    internal var totalSkippedBytes: Long = 0L
        private set

    /** openStream 成功计数（单测观察：顺序读应 ==1，回退 seek 恰好 +1） */
    var openCount: Int = 0
        private set

    /**
     * 从 [offset] 读至多 [length] 字节进 buffer（读满或 EOF；一个字节都没读到返回 -1）。
     * IOException 抛出前关流置空——下次调用按请求 offset 自动重开。
     */
    fun readAt(offset: Long, buffer: ByteArray, offsetInBuffer: Int, length: Int): Int = synchronized(lock) {
        if (closed) throw IOException("read pipe closed")
        if (maxBytes > 0 && servedBytesTotal >= maxBytes) {
            // 预算熔断（永久）：关流释放连接后拒读，绝不复位——防反复重连绕过预算
            stream?.let { runCatching { it.close() } }
            stream = null
            streamPos = -1
            throw IOException("thumb read budget exceeded: served=$servedBytesTotal max=$maxBytes")
        }
        var ins = stream
        if (ins == null) {
            // 无流（首读/IO 自愈后）：一律 open，不走 skip——否则小偏移首读会落入前跳分支却无流可跳
            ins = open(offset).also { s -> openCount++; stream = s; streamPos = offset }
        } else if (streamPos != offset) {
            val gap = offset - streamPos
            if (gap > 0 && gap <= FORWARD_SKIP_TOLERANCE_BYTES) {
                // §1b 前跳≤容差：同流分块 read 丢弃（SmbRandomReadStream 未覆写 skip()，禁用之；
                // 短 skip 循环 + n<=0 防自旋为全库惯例）；跳过字节计入预算口径。
                // §1(P1-1) 自愈:与 readFully 同口径——异常关流置空,半途字节不入账,
                // 下次 readAt 按请求 offset 重开;EOF(n<=0 break)不属异常,照常入账
                try {
                    val scratch = ByteArray(64 * 1024)
                    var remaining = gap
                    while (remaining > 0) {
                        val n = ins.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                        if (n <= 0) break   // EOF：流位置停在 EOF，本次 readFully 将返回 -1
                        remaining -= n
                    }
                    val skipped = gap - remaining
                    streamPos += skipped
                    servedBytesTotal += skipped
                    totalSkippedBytes += skipped
                } catch (e: Exception) {
                    runCatching { ins.close() }
                    stream = null
                    streamPos = -1
                    throw e
                }
            } else {
                // 回跳 / 超容差前跳：关流按新 offset 重开（现状语义）
                runCatching { ins.close() }
                stream = null
                streamPos = -1
                ins = open(offset).also { s -> openCount++; stream = s; streamPos = offset }
            }
        }
        val n = try {
            readFully(ins, buffer, offsetInBuffer, length)
        } catch (e: Exception) {
            // 自愈：坏流（空闲断连/超时）关流置空后重抛，绝不把坏流留在原位继续吐字节
            runCatching { ins.close() }
            stream = null
            streamPos = -1
            throw e
        }
        if (n > 0) {
            streamPos += n
            servedBytesTotal += n
        }
        n
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        stream?.let { runCatching { it.close() } }
        stream = null
        streamPos = -1
    }

    /** 读满 [length] 字节或遇 EOF；读不到任何字节返回 -1（EOF 信号，retriever 依赖它） */
    private fun readFully(ins: InputStream, buffer: ByteArray, offsetInBuffer: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = ins.read(buffer, offsetInBuffer + total, length - total)
            if (n <= 0) break // n==0 按流结束处理：防底层异常返回 0 时自旋死循环
            total += n
        }
        return if (total == 0) -1 else total
    }

    companion object {
        /** 前跳容差(§1b,= 抽帧块 256KB):MMR 解析 4K MKV 的读型是 64KB 读+≤~130KB 前跳,
         *  逐读重开实测 ~100ms/读(0.6MB/s,一张封面数分钟);容差内同流跳过 = 每跳 2-4ms */
        internal const val FORWARD_SKIP_TOLERANCE_BYTES: Long = 256L * 1024
    }
}
