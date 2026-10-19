package com.novax.app.data.thumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 抽帧常驻流管线单测（2026-09-15 读放大修复；评审要求 7 断言点）：
 * 顺序读只开 1 次流 / 回退 seek 重开且 offset 正确 / EOF 守卫不开流（在 DataSource 层）/
 * 短读前进量 / IO 异常自愈重开 / close 幂等且恰释放一次 / readFully 语义不回归。
 */
class SequentialRandomReadPipeTest {

    /** 记录 open offset 序列的内存文件流工厂 */
    private class FakeFile(
        val data: ByteArray,
        /** 第 N 次 read 调用抛 IOException（模拟瞬时断连；一次性的，重开即恢复） */
        val failOnCalls: Set<Int> = emptySet(),
    ) {
        val openedAt = mutableListOf<Long>()
        val closedCount = AtomicInteger()
        private var readCalls = 0

        fun open(offset: Long): java.io.InputStream = object : java.io.InputStream() {
            private var pos = offset
            init {
                openedAt += offset
            }
            override fun read(): Int = throw NotImplementedError()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                readCalls++
                if (readCalls in failOnCalls) throw IOException("connection lost")
                val n = minOf(len, (data.size - pos).toInt())
                System.arraycopy(data, pos.toInt(), b, off, n)
                pos += n
                return n
            }
            override fun close() {
                closedCount.incrementAndGet()
            }
        }
    }

    @Test
    fun sequentialReads_openStreamExactlyOnce() {
        val file = FakeFile(ByteArray(4096) { (it % 251).toByte() })
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(64)
        // 模拟 retriever 顺序容器解析：16 次 64KB→这里 64B 步进
        var total = 0
        for (i in 0 until 16) {
            val n = pipe.readAt(i * 64L, buf, 0, 64)
            assertEquals(64, n)
            total += n
        }
        assertEquals(1024, total)
        assertEquals("顺序读必须只开 1 次流（读放大修复核心断言）", 1, file.openedAt.size)
        assertEquals(0L, file.openedAt[0])
        pipe.close()
    }

    @Test
    fun backwardSeek_reopensOnceAtExactOffset() {
        val file = FakeFile(ByteArray(8192))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(32)
        assertEquals(32, pipe.readAt(0, buf, 0, 32))
        assertEquals(32, pipe.readAt(32, buf, 0, 32))
        // 回跳（moov 在文件头的 MP4 解析常见）：恰好重开 1 次、offset 精确
        assertEquals(32, pipe.readAt(16, buf, 0, 32))
        assertEquals(2, file.openedAt.size)
        assertEquals(16L, file.openedAt[1])
        pipe.close()
    }

    @Test
    fun forwardJumpBeyondTolerance_reopensAtNewOffset() {
        val file = FakeFile(ByteArray(700_000))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(16)
        assertEquals(16, pipe.readAt(0, buf, 0, 16))
        assertEquals(16, pipe.readAt(300_000L, buf, 0, 16))   // 前跳 ~300KB > 256KB 容差 → 重开
        assertEquals(2, file.openedAt.size)
        pipe.close()
    }

    @Test
    fun forwardJumpWithinTolerance_continuesSameStream_andCountsSkippedBytes() {
        val file = FakeFile(ByteArray(200_000))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(16)
        assertEquals(16, pipe.readAt(0, buf, 0, 16))
        assertEquals(16, pipe.readAt(100_000L, buf, 0, 16))   // 前跳 ~100KB ≤ 256KB → 同流 skip 续读
        assertEquals("容差内前跳不得重开(100ms/读 重开风暴的治本断言)", 1, file.openedAt.size)
        assertEquals(100_000L + 16, pipe.servedBytesTotal)
        assertEquals(99_984L, pipe.totalSkippedBytes)
        pipe.close()
    }

    @Test
    fun skipLandingAtEof_returnsMinusOne_andClampsStreamPos() {
        val file = FakeFile(ByteArray(1_000))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(16)
        assertEquals(16, pipe.readAt(0, buf, 0, 16))
        assertEquals(-1, pipe.readAt(5_000L, buf, 0, 16))     // 间隙越过 EOF:跳到 EOF 读出 -1
        assertEquals(1, file.openedAt.size)                    // 不重开(流还活着在 EOF)
        assertEquals(984L, pipe.totalSkippedBytes)             // 只跳过到 EOF 为止
        assertEquals(-1, pipe.readAt(5_000L, buf, 0, 16))      // 再读仍 -1(流位置=EOF,零字节可跳)
        assertEquals(1, file.openedAt.size)
        pipe.close()
    }

    @Test
    fun budget_countsSkippedBytes() {
        val file = FakeFile(ByteArray(4096) { (it % 251).toByte() })
        val pipe = SequentialRandomReadPipe(file::open, maxBytes = 100)
        val buf = ByteArray(64)
        assertEquals(64, pipe.readAt(0, buf, 0, 64))     // served=64
        assertEquals(36, pipe.readAt(80L, buf, 0, 36))   // skip 16(served=80) + 读 36 → served=116:跳过字节计入预算
        assertEquals(1, file.openedAt.size)              // 0→80 容差内同流 skip,未重开
        assertThrows(IOException::class.java) { pipe.readAt(128L, buf, 0, 64) } // 入口判定 116≥100 熔断(发生在重开之前)
        assertEquals(1, file.openedAt.size)              // 熔断关流且不重开(既有 budgetReached 语义)
        pipe.close()
    }

    @Test
    fun shortReadAtEof_advancesPositionByActualBytes() {
        val file = FakeFile(ByteArray(100))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(64)
        assertEquals(64, pipe.readAt(0, buf, 0, 64))            // 满
        assertEquals(36, pipe.readAt(64, buf, 0, 64))           // 短读：剩 36
        assertEquals(-1, pipe.readAt(100, buf, 0, 64))          // EOF：-1
        // EOF 后流仍在原位（EOF 读不重开）
        assertEquals(1, file.openedAt.size)
        pipe.close()
    }

    @Test
    fun ioException_closesBadStream_andNextReadReopensAtRequestedOffset() {
        // 第 2 次 read 调用瞬时断连：顺序续读在流中段失败
        val file = FakeFile(ByteArray(4096), failOnCalls = setOf(2))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(32)
        assertEquals(32, pipe.readAt(0, buf, 0, 32))
        assertThrows(IOException::class.java) { pipe.readAt(32, buf, 0, 32) }
        // 坏流已被关掉（自愈），下一次 readAt 按请求 offset 重开成功
        assertEquals(32, pipe.readAt(32, buf, 0, 32))
        assertEquals(2, file.openedAt.size)
        assertEquals(32L, file.openedAt[1])
        pipe.close()
    }

    @Test
    fun ioExceptionDuringSkip_closesBadStream_andNextReadReopensAtRequestedOffset() {
        // spec §1(P1-1):skip 丢弃读中途瞬时断连——坏流必须关掉,半途字节不入账,
        // 下次 readAt 按请求 offset 重开自愈(skip 是 4K MKV 抽帧读型主路径)
        val target = 64L + 200L * 1024   // gap=200KB > 64KB scratch → skip 需 4 次读
        val file = FakeFile(ByteArray(256 * 1024), failOnCalls = setOf(4))  // call#4=第 3 次 skip 读,抛
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(64)
        assertEquals(64, pipe.readAt(0, buf, 0, 64))            // call#1,建立流
        // call#2/#3 各跳 64KB 成功,call#4 抛:已半途跳过 128KB
        assertThrows(IOException::class.java) { pipe.readAt(target, buf, 0, 64) }
        assertEquals("坏流必须已被关闭(自愈前提)", 1, file.closedCount.get())
        assertEquals("异常路径此刻不得重开流", 1, file.openedAt.size)
        assertEquals("异常半途的 skip 字节不得入账(防双计/预算提前熔断)", 64L, pipe.servedBytesTotal)
        assertEquals(0L, pipe.totalSkippedBytes)
        // 自愈:下次同 offset 重开成功(failOnCalls 一次性),数据正确
        assertEquals(64, pipe.readAt(target, buf, 0, 64))
        assertEquals(2, file.openedAt.size)
        assertEquals(target, file.openedAt[1])
        pipe.close()
    }

    @Test
    fun close_isIdempotent_andReleasesStreamExactlyOnce() {
        val file = FakeFile(ByteArray(1024))
        val pipe = SequentialRandomReadPipe(file::open)
        val buf = ByteArray(16)
        assertEquals(16, pipe.readAt(0, buf, 0, 16))
        pipe.close()
        pipe.close()
        pipe.close()
        assertEquals("幂等 close 只释放一次流", 1, file.closedCount.get())
        assertThrows(IOException::class.java) { pipe.readAt(0, buf, 0, 16) }
    }

    @Test
    fun readFully_semantics_fullThenEofMinusOne() {
        // 与 NvMediaDataSource.readFully 同语义：读满 / 短读 / 全空 -1（回归保护）
        val data = ByteArray(48) { it.toByte() }
        val buf = ByteArray(64)
        assertEquals(48, SequentialRandomReadPipe(FakeFile(data)::open).readAt(0, buf, 0, 64))
        assertEquals(-1, SequentialRandomReadPipe(FakeFile(ByteArray(0))::open).readAt(0, buf, 0, 64))
    }

    // ---------- 字节预算保险丝（2026-09-28 后台偷跑流量事故）：远程抽帧读放大 1.5×+，超限必须熔断 ----------

    @Test
    fun budgetReached_abortsNextReadAndClosesStream() {
        val file = FakeFile(ByteArray(4096) { (it % 251).toByte() })
        val pipe = SequentialRandomReadPipe(file::open, maxBytes = 100)
        val buf = ByteArray(64)
        assertEquals(64, pipe.readAt(0, buf, 0, 64))
        // 预算按调用边界判定：本次读照常服务（超限幅度 ≤ 单次读长，64B），下次读熔断
        assertEquals(64, pipe.readAt(64, buf, 0, 64))
        assertThrows(IOException::class.java) { pipe.readAt(128, buf, 0, 64) }
        // 熔断不可复位：换 offset 也拒读，防反复重连绕过预算
        assertThrows(IOException::class.java) { pipe.readAt(0, buf, 0, 10) }
        // 熔断时把底层流也关掉（不占住 SMB/HTTP 连接）
        assertEquals("预算熔断必须关闭底层流", 1, file.closedCount.get())
        pipe.close()
    }

    @Test
    fun budget_countsCumulativelyAcrossReopen() {
        val file = FakeFile(ByteArray(4096) { (it % 251).toByte() })
        val pipe = SequentialRandomReadPipe(file::open, maxBytes = 96)
        val buf = ByteArray(64)
        assertEquals(64, pipe.readAt(0, buf, 0, 64))
        // 回跳触发重开：预算按管线累计（跨流），不按单条流重置
        assertEquals(64, pipe.readAt(0, buf, 0, 64))
        assertThrows(IOException::class.java) { pipe.readAt(128, buf, 0, 64) }
        assertEquals(2, file.openedAt.size)
        pipe.close()
    }

    @Test
    fun budgetZero_meansUnlimited() {
        val file = FakeFile(ByteArray(4096))
        val pipe = SequentialRandomReadPipe(file::open, maxBytes = 0)
        val buf = ByteArray(512)
        for (i in 0 until 8) assertEquals(512, pipe.readAt(i * 512L, buf, 0, 512))
        pipe.close()
    }
}
