package com.novax.app.player

import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * 字幕延迟偏移（Media3 1.8 无公开 offset API，这里包装 SubtitleParser 实现）。
 *
 * 原理：Media3 1.5+ 字幕在抽取期由 SubtitleParser.parse 吐出带时间的
 * CuesWithTiming（startTimeUs/durationUs/endTimeUs 派生）；装饰 parser 把
 * startTimeUs 平移 delayUs（endTimeUs 由构造器随 start 派生，durationUs 不动）
 * 即整体延迟/提前字幕。parseToLegacySubtitle 是 default 方法且内部走 parse，
 * 装饰 parse 即同时覆盖 legacy 路径。
 *
 * 生效语义：parser 每包调用时读取当前 delayMs → 新样本即刻用新偏移，
 * 已解码在途样本不变 → 「调整后从下一句字幕生效」（避免重建引擎）。
 */
object NvSubtitleOffset {
    @Volatile
    var delayMs: Long = 0L
}

/** 偏移装饰 parser：无偏移时零开销直通。（media3 UnstableApi 面） */
@UnstableApi
class NvShiftedSubtitleParser(private val delegate: SubtitleParser) : SubtitleParser {

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val delayUs = NvSubtitleOffset.delayMs * 1000
        if (delayUs == 0L) {
            delegate.parse(data, offset, length, outputOptions, output)
            return
        }
        // 抽取期按 startTimeUs 阈值过滤时，阈值要按同一偏移回退，否则延迟后的
        // 在播字幕会被抽取器当成「已过时样本」丢弃
        val adjusted = if (outputOptions.startTimeUs > 0 && !outputOptions.outputAllCues) {
            SubtitleParser.OutputOptions.onlyCuesAfter((outputOptions.startTimeUs - delayUs).coerceAtLeast(0))
        } else {
            outputOptions
        }
        delegate.parse(data, offset, length, adjusted) { cues ->
            output.accept(
                CuesWithTiming(
                    cues.cues,
                    (cues.startTimeUs + delayUs).coerceAtLeast(0),
                    cues.durationUs,
                ),
            )
        }
    }

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
}

/** 包装 [DefaultSubtitleParserFactory]，所有支持的格式都套偏移装饰。 */
@UnstableApi
class NvShiftedSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = NvShiftedSubtitleParser(delegate.create(format))
}
