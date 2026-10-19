package com.novax.app.data.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SmbRandomReadStream 大块缓冲/预读管线单测：用内存字节数组 fake [FakeHandle]
 * 模拟 smbj 定位读（每次 readAt = 1 次网络往返，offset 序列 = 往返序列），验证：
 * 顺序读跨块、seek（新 open）缓冲失效、预读命中、预读失败主路径重试、close 停线程、EOF 语义。
 *
 * 断言均按「预读线程可能已并发推进」的保守口径（take(n)/count），保证无 flaky。
 */
class SmbRandomReadStreamTest {

    /** 共享「文件」：数据 = (index % 256) 填充；记录所有流的定位读 offset（= 1 次网络往返） */
    private class FakeFile(val size: Long) {
        val data = ByteArray(size.toInt()).also { d ->
            for (i in d.indices) d[i] = (i % 256).toByte()
        }
        private val offsets = mutableListOf<Long>()
        private val failed = AtomicBoolean(false)

        fun record(fileOffset: Long) {
            synchronized(offsets) { offsets.add(fileOffset) } // 失败的尝试也计入往返
        }

        fun readInto(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int {
            if (fileOffset >= size) return -1
            val n = minOf(length.toLong(), size - fileOffset).toInt()
            System.arraycopy(this.data, fileOffset.toInt(), data, offset, n)
            return n
        }

        fun recordedOffsets(): List<Long> = synchronized(offsets) { offsets.toList() }
    }

    /** 每流一个句柄（生产 = 每次开一个 smbj File）：own closed 标志；失败注入/往返记录挂这里 */
    private class FakeHandle(private val file: FakeFile) : RandomReadHandle {
        private val closed = AtomicBoolean(false)
        private var failFirstReadAt: Long = Long.MIN_VALUE
        private val failed = AtomicBoolean(false)

        /** 仅第一次发生在该 offset 的读失败（模拟传输错误；谁先读谁失败，另一方重试） */
        fun failFirstReadAt(offset: Long) {
            failFirstReadAt = offset
        }

        override fun readAt(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int {
            if (closed.get()) throw IOException("fake handle closed")
            file.record(fileOffset)
            if (fileOffset == failFirstReadAt && !failed.getAndSet(true)) {
                throw IOException("fake transport error")
            }
            return file.readInto(data, fileOffset, offset, length)
        }

        override fun close() {
            closed.set(true)
        }

        fun recordedOffsets(): List<Long> = file.recordedOffsets()
    }

    private fun fakeFile(size: Long) = FakeFile(size)

    private fun stream(
        handle: FakeHandle,
        start: Long = 0,
        block: Int = 1024,
        maxReadSizeOverride: Int? = null,
        enablePrefetch: Boolean = true,
    ) = SmbRandomReadStream(handle, maxReadSize = maxReadSizeOverride ?: block, startPosition = start,
        maxBlockBytes = block, log = { /* JVM 无 android.util.Log */ }, enablePrefetch = enablePrefetch)

    /** 读满 n 字节（模拟 ExoPlayer 64KB 步长的多次小读） */
    private fun readFully(s: SmbRandomReadStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(out, off, n - off)
            if (r < 0) throw IOException("EOF at $off/$n")
            off += r
        }
        return out
    }

    private fun expectData(actual: ByteArray, from: Int) {
        for (i in actual.indices) {
            assertEquals("byte@$i", ((from + i) % 256).toByte(), actual[i])
        }
    }

    // ---------- 顺序读跨块 ----------

    @Test
    fun sequentialRead_acrossBlocks_oneRoundTripPerBlock() {
        val h = FakeHandle(fakeFile(10_000))
        stream(h, start = 0, block = 1024).use { s ->
            val got = readFully(s, 2_500) // 跨块 0/1/2（2500 = 1024+1024+452）
            expectData(got, 0)
        }
        // 整块填满：块 0/1/2 各恰好 1 次定址读，起点对齐块边界
        assertEquals(listOf(0L, 1024L, 2048L), h.recordedOffsets().take(3))
        assertEquals("无碎片化重读", 1, h.recordedOffsets().count { it == 1024L })
    }

    @Test
    fun smallReads_spanningOneBlock_stillOneRoundTrip() {
        val h = FakeHandle(fakeFile(10_000))
        stream(h, start = 0, block = 1024).use { s ->
            repeat(16) { readFully(s, 64) } // 16×64B 小读，消耗 1 个 1024B 块
        }
        // 对块 0 的主路径往返只有 1 次（修复前 16 次 read = 16 次往返）；
        // 预读线程可能已对块 1 发起至多 1 次读
        assertEquals(1, h.recordedOffsets().count { it == 0L })
        assertTrue(h.recordedOffsets().size <= 2)
    }

    // ---------- seek 语义：新 open = 新流，缓冲/预读槽丢弃 ----------

    @Test
    fun seek_newOpen_discardsBuffersAndReadsFromNewPosition() {
        val file = fakeFile(20_000)
        // 第一次 open：读块 0 少量数据即弃（模拟被 seek 打断的旧流）；句柄随流 close
        val first = FakeHandle(file)
        val s1 = stream(first, start = 0, block = 1024)
        readFully(s1, 16)
        s1.close()

        // seek = 新 position 重新 open（新句柄）：必须从 4096 定址读，绝不出块 0~3 的脏数据
        val second = FakeHandle(file)
        stream(second, start = 4_096, block = 1024).use { s2 ->
            val got = readFully(s2, 32)
            expectData(got, 4_096)
            assertTrue("seek 后首个读应在 4096", second.recordedOffsets().contains(4_096L))
        }
        assertTrue("无落在旧流当前位置的读", first.recordedOffsets().none { it in 1025..4095 })
    }

    // ---------- 预读命中 ----------

    @Test
    fun prefetch_nextBlockServedWithoutExtraRoundTrip() {
        val h = FakeHandle(fakeFile(20_000))
        stream(h, start = 0, block = 1024).use { s ->
            readFully(s, 1024) // 块 0：1 次主路径读 + 投递块 1 预读
            val got = readFully(s, 1024) // 块 1：预读命中（await 槽 latch 保证顺序）
            expectData(got, 1024)
            assertEquals("块 1 命中预读槽", 1, s.prefetchHitCount())
        }
        val offsets = h.recordedOffsets()
        assertEquals(listOf(0L, 1024L), offsets.take(2))
        assertEquals("块 1 只读一次（预读线程读，主路径未重复）", 1, offsets.count { it == 1024L })
    }

    @Test
    fun prefetch_sequentialRun_roundTripsNearBlockCount() {
        val h = FakeHandle(fakeFile(64_000))
        stream(h, start = 0, block = 1024).use { s ->
            val got = readFully(s, 8 * 1024) // 8 块顺序读
            expectData(got, 0)
        }
        // 主路径 1 次 + 命中 7 次 + lookahead 多出 ≤1 次
        assertTrue("往返 ${h.recordedOffsets().size} 应 ≈ 块数 8", h.recordedOffsets().size <= 9)
    }

    // ---------- 预读失败 → 主路径重试 ----------

    @Test
    fun prefetchFailure_silent_mainPathRetries() {
        // 第一次 @1024 的读（无论预读线程还是主路径先到）失败，另一方重试成功
        val h = FakeHandle(fakeFile(20_000)).apply { failFirstReadAt(1024L) }
        stream(h, start = 0, block = 1024).use { s ->
            readFully(s, 1024) // 块 0
            val got = readFully(s, 1024) // 块 1：预读失败静默 → 主路径重试
            expectData(got, 1024)
        }
        val offsets = h.recordedOffsets()
        assertEquals("失败 1 次 + 重试 1 次 = @1024 共 2 次往返", 2, offsets.count { it == 1024L })
        assertEquals(listOf(0L, 1024L, 1024L), offsets.take(3))
    }

    // ---------- EOF ----------

    @Test
    fun eof_partialLastBlock_thenNegativeOne() {
        val h = FakeHandle(fakeFile(1_500))
        stream(h, start = 0, block = 1024).use { s ->
            val got = readFully(s, 1_500)
            expectData(got, 0)
            assertEquals(-1, s.read())
            assertEquals(-1, s.read(ByteArray(16), 0, 16))
        }
        // 末块部分填充后不再重读末块（EOF 锁存）
        assertEquals(1, h.recordedOffsets().count { it == 1024L })
    }

    @Test
    fun eof_atExactBlockBoundary() {
        val h = FakeHandle(fakeFile(2_048))
        stream(h, start = 0, block = 1024).use { s ->
            readFully(s, 2_048)
            assertEquals(-1, s.read())
        }
    }

    // ---------- close ----------

    @Test
    fun close_stopsPrefetchThread_andSubsequentReadThrows() {
        val h = FakeHandle(fakeFile(100_000))
        val s = stream(h, start = 0, block = 1024)
        readFully(s, 1024) // 触发预读线程启动
        s.close()
        assertTrue("预读线程应退出", awaitTrue(2_000) { threadAlive(PREFETCH_THREAD_NAME) == null })
        try {
            s.read(ByteArray(8), 0, 8)
            fail("close 后 read 应抛 IOException")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun close_idempotent_andClosesUnderlyingHandle() {
        val h = FakeHandle(fakeFile(100))
        val s = stream(h, start = 0, block = 64)
        readFully(s, 10)
        s.close()
        s.close() // 幂等
        try {
            readFully(s, 1)
            fail("close 后 read 应抛 IOException")
        } catch (expected: IOException) {
        }
        try {
            h.readAt(ByteArray(8), 0, 0, 8)
            fail("close 后底层句柄应不可用")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun close_joinsInFlightPrefetch_andNeverCallsHandleAfterClose() {
        // 慢句柄：≥1024 的块每次只回 100B 且睡 100ms（模拟在途慢网络读，一块需多次往返填充）
        val file = fakeFile(200_000)
        val closed = AtomicBoolean(false)
        val readsAfterClose = java.util.concurrent.atomic.AtomicInteger(0)
        val readsAt1024 = java.util.concurrent.atomic.AtomicInteger(0)
        val slow = object : RandomReadHandle {
            override fun readAt(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int {
                if (closed.get()) {
                    readsAfterClose.incrementAndGet()
                    throw IOException("fake handle closed")
                }
                if (fileOffset >= 1024L) {
                    readsAt1024.incrementAndGet()
                    Thread.sleep(100)
                }
                return file.readInto(data, fileOffset, offset, minOf(length, 100))
            }

            override fun close() {
                closed.set(true)
            }
        }
        val s = SmbRandomReadStream(slow, maxReadSize = 1024, startPosition = 0, maxBlockBytes = 1024, log = {})
        readFully(s, 1024) // 块 0 整块读 → 投递块 1 预读（进入慢速在途读）
        Thread.sleep(150)  // 让预读线程进入 1~2 次在途慢读
        val t0 = System.nanoTime()
        s.close()          // 加固后：先停预读线程（join ≤200ms）再关句柄
        val closeMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("close 后不得再对底层句柄发起任何 READ（快进报错竞态回归锁定）", 0, readsAfterClose.get())
        assertTrue("close 应在 join 超时+余量内返回，实际 ${closeMs}ms", closeMs < 5_000)
        assertTrue("预读线程应退出", awaitTrue(2_000) { threadAlive(PREFETCH_THREAD_NAME) == null })
        assertTrue(
            "close 应提前停掉剩余块填充（在途 1~2 次 ≪ 整块 11 次），实际 ${readsAt1024.get()}",
            readsAt1024.get() <= 8,
        )
        try {
            s.read(ByteArray(8), 0, 8)
            fail("close 后 read 应抛 IOException")
        } catch (expected: IOException) {
        }
    }

    // ---------- 块大小 ----------

    @Test
    fun blockSize_clampedToMaxBlockBytes() {
        // 协商 8MB、上限 1MB → 块 1MB（2026-10-22 真机 OOM 修复后的发版口径；4MB 大块仅设置页手动档）
        assertEquals(1 shl 20, SmbReadMath.blockBytes(maxReadSize = 8 shl 20))
        // 协商 64KB 老 NAS → 块退化为 64KB，仍可工作
        assertEquals(64 * 1024, SmbReadMath.blockBytes(maxReadSize = 64 * 1024))
        // 协商 1MB → 块 1MB
        assertEquals(1 shl 20, SmbReadMath.blockBytes(maxReadSize = 1 shl 20))
    }

    // ---------- 预读槽位/流水线 ----------

    @Test
    fun prefetchSlot_pipelinesNextBlockAfterHit() {
        val h = FakeHandle(fakeFile(1_000_000))
        stream(h, start = 0, block = 1024).use { s ->
            readFully(s, 1024) // 块 0 → 预读块 1 投递
            assertEquals(1_024L, s.prefetchSlotStartOffset())
            readFully(s, 1024) // 块 1 经槽命中消费 → 立即为块 2 投递（1 块 lookahead 流水线）
            assertEquals("命中后槽位滚动到下一块", 2_048L, s.prefetchSlotStartOffset())
        }
        // 块 0/1 各只发生 1 次往返（无主读与预读的重复读）
        assertEquals(1, h.recordedOffsets().count { it == 0L })
        assertEquals(1, h.recordedOffsets().count { it == 1024L })
    }

    @Test
    fun noPrefetchLeak_afterFullConsume() {
        val h = FakeHandle(fakeFile(5_000))
        stream(h, start = 0, block = 1024).use { s ->
            readFully(s, 5_000)
            assertEquals(-1, s.read())
            assertNull(s.prefetchSlotStartOffset()) // EOF 后槽位已被消费清空
        }
        assertFalse(h.recordedOffsets().isEmpty())
        // 每块恰好读一次，无重复网络读
        assertTrue(h.recordedOffsets().groupBy { it }.all { it.value.size == 1 })
    }

    // ---------- 非对齐 position（生产回归：MP4 moov 在文件尾，seek 目标非块对齐） ----------

    @Test
    fun unalignedOpen_servesExactRequestedBytes_notBlockStartGarbage() {
        // 回归：曾按块边界填充（start=1234 → 实际从 1024 读），返回「请求位置之前」的脏数据
        val h = FakeHandle(fakeFile(10_000))
        // 关预读：本用例断言主路径填充序列（旧写法读 2000B 只触发 2 次主填充，第 3 个
        // offset 靠预读线程「碰巧」记录——时序性假绿；现读 2800B 使三次填充全部确定性发生）
        stream(h, start = 1_234, block = 1024, enablePrefetch = false).use { s ->
            val got = readFully(s, 2_800) // 1234→4034（三次非对齐填充）
            expectData(got, 1_234)
        }
        // 每次填充起点必须是精确 position 的链：1234 → 2258 → 3282（绝不是 1024/2048/3072）
        assertEquals(listOf(1_234L, 2_258L, 3_282L), h.recordedOffsets())
        assertEquals("不得出现块边界起点 1024（回归锁定）", 0, h.recordedOffsets().count { it == 1_024L })
    }

    @Test
    fun unalignedSeekNearEof_moovScenario_servesTailExactly() {
        // 生产现场复刻：4.7MB 摄像头 MP4，moov 位于 4484696（距尾 233896B），非对齐
        val h = FakeHandle(fakeFile(4_718_592))
        val start = 4_484_696L
        stream(h, start = start, block = 4 shl 20, maxReadSizeOverride = 8 shl 20).use { s ->
            val got = readFully(s, 233_896) // 完整读出 moov 到文件尾
            expectData(got, start.toInt())
            assertEquals(-1, s.read())
        }
        assertEquals("首读必须精确落在 4484696（旧 bug 从 4194304 读出 mdat 垃圾）", start, h.recordedOffsets().first())
        assertTrue("距尾不足一整块，一次往返即可", h.recordedOffsets().size <= 2)
    }

    @Test
    fun unalignedOpen_prefetchPipeline_followsExactOffsets() {
        val h = FakeHandle(fakeFile(20_000))
        stream(h, start = 500, block = 1024).use { s ->
            readFully(s, 1024) // 非对齐块 500→1524 → 预读下一段 1524
            assertEquals(1_524L, s.prefetchSlotStartOffset())
            val got = readFully(s, 1024) // 预读命中（1524→2548）
            expectData(got, 1_524)
            assertEquals(1, s.prefetchHitCount())
        }
        assertEquals(listOf(500L, 1_524L), h.recordedOffsets().take(2))
    }

    // ---------- 工具 ----------

    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun threadAlive(name: String): Thread? {
        val threads = arrayOfNulls<Thread>(Thread.activeCount() + 8)
        val n = Thread.enumerate(threads)
        return (0 until n).mapNotNull { threads[it] }.firstOrNull { it.name == name && it.isAlive }
    }

    private companion object {
        const val PREFETCH_THREAD_NAME = "nvx-smb-prefetch"
    }
}
