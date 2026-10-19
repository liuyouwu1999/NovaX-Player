package com.novax.app.player

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class NvShiftedSubtitleParserTest {

    /** 固定输出的 delegate parser：模拟一次 parse 产出两条 CuesWithTiming */
    private class FakeParser(
        val emissions: List<CuesWithTiming> = listOf(
            CuesWithTiming(listOf(Cue.Builder().setText("hello").build()), 10_000_000L, 2_000_000L),
            CuesWithTiming(listOf(Cue.Builder().setText("world").build()), 30_000_000L, 2_000_000L),
        ),
    ) : SubtitleParser {
        var lastOptions: SubtitleParser.OutputOptions? = null
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            lastOptions = outputOptions
            emissions.forEach { output.accept(it) }
        }

        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    }

    private fun collect(parser: SubtitleParser, options: SubtitleParser.OutputOptions = SubtitleParser.OutputOptions.allCues()): List<CuesWithTiming> {
        val out = mutableListOf<CuesWithTiming>()
        parser.parse(ByteArray(8), 0, 8, options) { out.add(it) }
        return out
    }

    @Test
    fun `零偏移直通且不产生新对象`() {
        NvSubtitleOffset.delayMs = 0L
        val delegate = FakeParser()
        val parser = NvShiftedSubtitleParser(delegate)
        val out = collect(parser)
        assertEquals(2, out.size)
        assertEquals(10_000_000L, out[0].startTimeUs)
        assertTrue(out[0] === delegate.emissions[0]) // 直通路径零包装
    }

    @Test
    fun `正延迟平移开始时间且时长不变`() {
        NvSubtitleOffset.delayMs = 500L
        val out = collect(NvShiftedSubtitleParser(FakeParser()))
        assertEquals(10_500_000L, out[0].startTimeUs)
        assertEquals(2_000_000L, out[0].durationUs)
        assertEquals(30_500_000L, out[1].startTimeUs)
    }

    @Test
    fun `负延迟提前`() {
        NvSubtitleOffset.delayMs = -1_500L
        val out = collect(NvShiftedSubtitleParser(FakeParser()))
        assertEquals(8_500_000L, out[0].startTimeUs)
        assertEquals(28_500_000L, out[1].startTimeUs)
    }

    @Test
    fun `负延迟不产生负时间戳`() {
        NvSubtitleOffset.delayMs = -20_000L // -20s，首条 start=10s → 钳 0
        val out = collect(NvShiftedSubtitleParser(FakeParser()))
        assertEquals(0L, out[0].startTimeUs)
        assertEquals(10_000_000L, out[1].startTimeUs)
    }

    @Test
    fun `抽取期阈值过滤按偏移回退`() {
        NvSubtitleOffset.delayMs = 5_000L
        val delegate = FakeParser()
        val parser = NvShiftedSubtitleParser(delegate)
        collect(parser, SubtitleParser.OutputOptions.onlyCuesAfter(100_000_000L))
        // 请求 shifted ≥100s 的 cues → delegate 应收到 ≥95s 的原始阈值
        assertEquals(95_000_000L, delegate.lastOptions?.startTimeUs)
    }

    @Test
    fun `工厂透传能力与替换行为`() {
        val factory = NvShiftedSubtitleParserFactory()
        val format = Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP).build()
        assertTrue(factory.supportsFormat(format))
        assertEquals(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE, factory.getCueReplacementBehavior(format))
        assertTrue(factory.create(format) is NvShiftedSubtitleParser)
    }
}
