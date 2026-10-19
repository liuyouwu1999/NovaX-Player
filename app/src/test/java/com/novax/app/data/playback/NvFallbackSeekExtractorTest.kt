@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.data.playback

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

/**
 * 坏索引 MKV 降级 seek 包装器单测：seekMap 拦截/透传、长度未知退回、Cluster 重同步落点、
 * bytesRemaining 纯函数（C8 §4.3）。
 * 用内存版 [MemExtractorInput]（peek 语义与 DefaultExtractorInput 同构：peek 先入队、
 * read/skip 先消化队列）——纯 JVM 无 android 依赖（Uri/DataSpec 在单测不可构造）。
 */
class NvFallbackSeekExtractorTest {

    /** 记录型 delegate：首次 read 时经 output 发出 [seekMapToEmit] */
    private class FakeDelegate(private val seekMapToEmit: SeekMap) : Extractor {
        val seekCalls = mutableListOf<Pair<Long, Long>>()
        val readPositions = mutableListOf<Long>()
        var output: ExtractorOutput? = null

        override fun sniff(input: ExtractorInput): Boolean = true
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            readPositions += input.position
            output?.seekMap(seekMapToEmit)
            return Extractor.RESULT_END_OF_INPUT
        }
        override fun seek(position: Long, timeUs: Long) { seekCalls += position to timeUs }
        override fun release() {}
    }

    private class RecordingOutput : ExtractorOutput {
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = error("not needed")
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    }

    /**
     * 内存 ExtractorInput：read/skip 先消化 peek 队列再走底层数组；peek 向前但不推进读位。
     * 与 DefaultExtractorInput 的 peek→read 重放语义一致，纯 JVM 无 android 依赖。
     */
    private class MemExtractorInput(
        private val data: ByteArray,
        start: Long,
        private val totalLength: Long,
    ) : ExtractorInput {
        private var readPtr = start.toInt()
        private var peekPtr = start.toInt()
        private val peeked = ArrayDeque<Byte>()

        override fun getPosition(): Long = readPtr.toLong()
        override fun getPeekPosition(): Long = peekPtr.toLong()
        override fun getLength(): Long = totalLength
        override fun resetPeekPosition() { peeked.clear(); peekPtr = readPtr }
        override fun <E : Throwable> setRetryPosition(position: Long, error: E): Nothing = throw error

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            // 与 DefaultExtractorInput 同语义：不足 length 时先把可用字节 append 进 peek 流，
            // 再抛 EOFException（部分字节保留在 peek 流，供 peek() 重放）
            var filled = 0
            while (filled < length) {
                if (peekPtr >= data.size) throw EOFException("peek past EOF after $filled")
                peeked.addLast(data[peekPtr])
                peekPtr++
                filled++
            }
            for (k in 0 until length) target[offset + k] = peeked.elementAt(peeked.size - length + k)
        }

        /** 只重放已 peek 的内容（不新增底层读）；不足即 EOF——与生产 DefaultExtractorInput 一致 */
        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (length > peeked.size) throw EOFException("peek buffer has ${peeked.size} < $length")
            for (k in 0 until length) target[offset + k] = peeked.elementAt(k)
            return length
        }

        /**
         * 逻辑读位推进：先消化 peek 队列，队空直读底层数组。
         * readPtr 是「下一个将被 read 的字节」的绝对位——skip 穿过队列时同样前移
         * （DefaultExtractorInput.skipFully 语义），否则 getPosition 会停在旧位置。
         */
        private fun consume(n: Int) {
            repeat(n) {
                if (peeked.isNotEmpty()) peeked.removeFirst()
                readPtr++
                if (peekPtr < readPtr) peekPtr = readPtr
            }
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (readPtr >= data.size && peeked.isEmpty()) return C.RESULT_END_OF_INPUT
            var served = 0
            while (served < length) {
                if (readPtr >= data.size && peeked.isEmpty()) break
                if (peeked.isNotEmpty()) target[offset + served] = peeked.removeFirst()
                else target[offset + served] = data[readPtr]
                readPtr++
                if (peekPtr < readPtr) peekPtr = readPtr
                served++
            }
            return served
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            var served = 0
            while (served < length) {
                val n = read(target, offset + served, length - served)
                if (n == C.RESULT_END_OF_INPUT) throw EOFException()
                served += n
            }
        }

        override fun skipFully(length: Int) = consume(length)

        // —— 被测代码未用到：带 bool 的变体一律 error 兜底，防静默错语义 ——
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            error("unused")
        override fun skip(length: Int): Int = error("unused")
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = error("unused")
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            error("unused")
        override fun advancePeekPosition(length: Int) = error("unused") as Unit
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean = error("unused")
    }

    private fun memInput(bytes: ByteArray, position: Long, length: Long = bytes.size.toLong()) =
        MemExtractorInput(bytes, position, length)

    @Test
    fun unseekable_knownLength_replacedWithSeekableEstimate() {
        val delegate = FakeDelegate(SeekMap.Unseekable(100_000_000L))
        val wrapper = NvFallbackSeekExtractor(delegate, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        val length = 100_000_000L
        wrapper.read(memInput(ByteArray(16), 0L, length), PositionHolder())

        val sm = out.seekMap!!
        assertTrue("替换后的 SeekMap 应可 seek", sm.isSeekable)
        // 估算字节 = timeUs × length / durationUs（全长无偏估算）
        val expected = 50_000_000L * length / 100_000_000L
        val got = sm.getSeekPoints(50_000_000L).first.position
        assertTrue("落点 $got 应在 $expected±64 内", got in (expected - 64)..(expected + 64))
        // 降级激活后 seek() 应转给 delegate 并置重同步标记（下一次 read 先扫簇）
        wrapper.seek(expected, 50_000_000L)
        assertEquals(listOf(expected to 50_000_000L), delegate.seekCalls)
    }

    @Test
    fun seekableMap_passthrough_withoutFallback() {
        val upstream = object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = 60_000_000L
            override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(timeUs, 1L))
        }
        val delegate = FakeDelegate(upstream)
        val wrapper = NvFallbackSeekExtractor(delegate, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(ByteArray(16), 0L, 1_000L), PositionHolder())
        assertTrue("seekable 原样透传（同一实例）", out.seekMap === upstream)
        // 非 fallback：seek 直通，下一次 read 不做任何重同步扫描
        wrapper.seek(123L, 5L)
        wrapper.read(memInput(ByteArray(16), 123L, 1_000L), PositionHolder())
        assertEquals(123L, delegate.readPositions.last())
    }

    @Test
    fun unknownLength_unseekablePassthrough() {
        val unseekable = SeekMap.Unseekable(80_000_000L)
        val delegate = FakeDelegate(unseekable)
        val wrapper = NvFallbackSeekExtractor(delegate, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(ByteArray(16), 0L, C.LENGTH_UNSET.toLong()), PositionHolder())
        assertTrue("长度未知时原样透传（上层 toast 兜底）", out.seekMap === unseekable)
    }

    @Test
    fun fallbackSeek_resyncsToNextClusterBoundary() {
        // 5000B 垃圾 + Cluster ID + 载荷；估算落点 10 → 应重同步到 5000
        val clusterAt = 5000
        val bytes = ByteArray(clusterAt + 64) { 0x55 }
        NvFallbackSeekExtractor.CLUSTER_ID.forEachIndexed { i, b -> bytes[clusterAt + i] = b }
        val delegate = FakeDelegate(SeekMap.Unseekable(100_000_000L))
        val wrapper = NvFallbackSeekExtractor(delegate, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(bytes, 0L, 100_000L), PositionHolder())   // 激活降级
        assertTrue(out.seekMap!!.isSeekable)

        wrapper.seek(10L, 5_000_000L)
        wrapper.read(memInput(bytes, 10L, 100_000L), PositionHolder())
        // delegate 在重同步后被再次 seek 到簇起点，且从该位置继续读
        assertEquals(listOf(clusterAt.toLong() to 5_000_000L), delegate.seekCalls.takeLast(1))
        assertEquals(clusterAt.toLong(), delegate.readPositions.last())
    }

    @Test
    fun resync_clusterIdAcrossWindowBoundary() {
        // 落点 0、簇 ID 从 4094 开始（末 2B 在窗 0、头 2B 在窗 1）→ 考察 carry 逻辑
        val clusterAt = 4094
        val bytes = ByteArray(clusterAt + 64) { 0x55 }
        NvFallbackSeekExtractor.CLUSTER_ID.forEachIndexed { i, b -> bytes[clusterAt + i] = b }
        val delegate = FakeDelegate(SeekMap.Unseekable(100_000_000L))
        val wrapper = NvFallbackSeekExtractor(delegate, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(bytes, 0L, 100_000L), PositionHolder())
        wrapper.seek(0L, 1_000_000L)
        wrapper.read(memInput(bytes, 0L, 100_000L), PositionHolder())
        assertEquals(clusterAt.toLong(), delegate.readPositions.last())
    }

    // ---------- 2026-10-03 MP4 分片 moof 锚点 ----------

    /** 造一个 moof box 头写入 bytes[offset..offset+7]：4B 大端 size(=256) + 'moof' ASCII */
    private fun writeMoofBox(bytes: ByteArray, offset: Int) {
        bytes[offset] = 0; bytes[offset + 1] = 0; bytes[offset + 2] = 0x01; bytes[offset + 3] = 0x00
        bytes[offset + 4] = 0x6D; bytes[offset + 5] = 0x6F; bytes[offset + 6] = 0x6F; bytes[offset + 7] = 0x66
    }

    /** MP4 规格三件套：wrapper + 自持 delegate（读 seekCalls/readPositions）+ 已 init 的 output */
    private fun mp4Harness(data: ByteArray): Triple<NvFallbackSeekExtractor, FakeDelegate, RecordingOutput> {
        val delegate = FakeDelegate(SeekMap.Unseekable(100_000_000L))
        val wrapper = NvFallbackSeekExtractor(delegate, NvFallbackSeekExtractor.ResyncSpec.MP4, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(data, 0L, 100_000L), PositionHolder())   // 激活降级
        return Triple(wrapper, delegate, out)
    }

    @Test
    fun mp4Resync_landsAtMoofBoxSizeFieldStart() {
        // 8192B（≥4096 走主循环）+ moof box 起点 3000；估算落点 10 → 落 box size 字段起点 3000
        //（非魔数处 3004——Mp4Extractor 分片解析必须从 box 头 size 字段开始读）
        val moofAt = 3000
        val bytes = ByteArray(8192) { 0x33 }
        writeMoofBox(bytes, moofAt)
        val (wrapper, delegate, out) = mp4Harness(bytes)
        assertTrue(out.seekMap!!.isSeekable)

        wrapper.seek(10L, 5_000_000L)
        wrapper.read(memInput(bytes, 10L, 100_000L), PositionHolder())
        assertEquals(listOf(moofAt.toLong() to 5_000_000L), delegate.seekCalls.takeLast(1))
        assertEquals(moofAt.toLong(), delegate.readPositions.last())
    }

    @Test
    fun mp4Resync_moofAcrossWindowBoundary() {
        // moof box 起点 4094：魔数 [4098..4101] 跨 4096 滚动窗边界（4158B 数组 → 残窗 scanTail + carry 拼接）
        val moofAt = 4094
        val bytes = ByteArray(moofAt + 64) { 0x33 }
        writeMoofBox(bytes, moofAt)
        val (wrapper, delegate, _) = mp4Harness(bytes)
        wrapper.seek(0L, 1_000_000L)
        wrapper.read(memInput(bytes, 0L, 100_000L), PositionHolder())
        assertEquals(moofAt.toLong(), delegate.readPositions.last())
    }

    @Test
    fun mp4Resync_moofAtByte0_landsClampedToCurrentPosition() {
        // 魔数在 0..3：landingOffset=-4 会算出 -4，必须钳到当前位置 0（不产生逆向 seek）
        val bytes = ByteArray(8192) { 0x33 }
        bytes[0] = 0x6D; bytes[1] = 0x6F; bytes[2] = 0x6F; bytes[3] = 0x66
        val (wrapper, delegate, _) = mp4Harness(bytes)
        wrapper.seek(0L, 1_000_000L)
        wrapper.read(memInput(bytes, 0L, 100_000L), PositionHolder())
        assertEquals(0L, delegate.readPositions.last())
    }

    @Test
    fun resolveBytesRemaining_specLengthPrecedence_totalRemaining_fallbacks() {
        val unset = C.LENGTH_UNSET.toLong()
        assertEquals(42L, NvDataSource.resolveBytesRemaining(42L, 0L, 1_000L))
        assertEquals(900L, NvDataSource.resolveBytesRemaining(unset, 100L, 1_000L))
        assertEquals(unset, NvDataSource.resolveBytesRemaining(unset, 1_000L, 1_000L))
        assertEquals(unset, NvDataSource.resolveBytesRemaining(unset, 100L, -1L))
        assertEquals(unset, NvDataSource.resolveBytesRemaining(unset, 100L, 0L))
    }

    @After
    fun clearActivationHook() {
        NvFallbackSeekExtractor.onActivated = null
    }

    @Test
    fun fallbackActivation_firesVisibleHintOnce() {
        // i18n Task 9：提取器只发激活信号（文案改由宿主资源渲染）——断言改锁触发次数
        var fired = 0
        NvFallbackSeekExtractor.onActivated = { fired++ }
        val delegate = FakeDelegate(SeekMap.Unseekable(100_000_000L))
        val wrapper = NvFallbackSeekExtractor(delegate, log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(ByteArray(16), 0L, 100_000L), PositionHolder())
        assertEquals("激活时应触发一次可见提示", 1, fired)
        // 激活后再次 read 不重复触发（每实例至多一次）
        wrapper.read(memInput(ByteArray(16), 0L, 100_000L), PositionHolder())
        assertEquals(1, fired)
    }

    @Test
    fun noFallback_noActivationHint() {
        var fired = 0
        NvFallbackSeekExtractor.onActivated = { fired++ }
        val upstream = object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = 60_000_000L
            override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(timeUs, 1L))
        }
        val wrapper = NvFallbackSeekExtractor(FakeDelegate(upstream), log = {})
        val out = RecordingOutput()
        wrapper.init(out)
        wrapper.read(memInput(ByteArray(16), 0L, 1_000L), PositionHolder())
        wrapper.read(memInput(ByteArray(16), 0L, C.LENGTH_UNSET.toLong()), PositionHolder())
        assertEquals("透传路径不得触发激活提示", 0, fired)
    }

    @Test
    fun wrapWithFallbackSeek_wrapsMatroskaAndMp4() {
        val matroska = androidx.media3.extractor.mkv.MatroskaExtractor()
        val mp4 = androidx.media3.extractor.mp4.Mp4Extractor()
        val wrapped = NvMediaSourceBuilder.wrapWithFallbackSeek(arrayOf(matroska, mp4))
        assertEquals(2, wrapped.size)
        assertTrue(wrapped[0] is NvFallbackSeekExtractor)
        assertTrue("MP4 提取器（含分片）应被包装", wrapped[1] is NvFallbackSeekExtractor)
        // 重复包装防御：已包装的不再二次包（同一条管线重复经 wrap 时的幂等性）
        val rewrapped = NvMediaSourceBuilder.wrapWithFallbackSeek(wrapped)
        assertTrue(rewrapped[0] === wrapped[0])
        assertTrue(rewrapped[1] === wrapped[1])
    }

    @Test
    fun wrapWithFallbackSeek_wrapsFragmentedMp4_inRealDefaultFactoryOrder() {
        // 2026-10-03 模拟器实证（factory 组成日志）：media3 1.8.0 DefaultExtractorsFactory
        // 真实序列里 FragmentedMp4Extractor 排在 Mp4Extractor 之前，分片 MP4 嗅探先命中它——
        // 只包 Mp4Extractor 会让分片片源完全绕过降级管线（「源限制」Toast 复发）。
        // 真实 DefaultExtractorsFactory 在纯 JVM 测试环境构造会 NPE（部分提取器依赖 Android
        // 类），故手工按真实嗅探顺序摆阵：FragmentedMp4Extractor 在 Mp4Extractor 之前。
        val raw = arrayOf(
            androidx.media3.extractor.mp4.FragmentedMp4Extractor(),
            androidx.media3.extractor.mp4.Mp4Extractor(),
        )
        val wrapped = NvMediaSourceBuilder.wrapWithFallbackSeek(raw)
        assertEquals(2, wrapped.size)
        assertTrue("FragmentedMp4Extractor 所在位必须被包装", wrapped[0] is NvFallbackSeekExtractor)
        assertTrue("Mp4Extractor 所在位必须被包装", wrapped[1] is NvFallbackSeekExtractor)
    }
}
