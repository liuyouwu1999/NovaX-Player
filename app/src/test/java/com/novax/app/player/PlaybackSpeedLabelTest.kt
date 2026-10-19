package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-10-09 B4:倍速槽内嵌文本格式——整数档 2x、小数档 4.9x(与 SpeedSheet 八档同款表达式)。
 *  注:实际输入全部经 snapPlaybackSpeed 收敛到 0.1 步进,多位零头不可达,不做该用例。 */
class PlaybackSpeedLabelTest {
    @Test fun `整数档不带小数`() { assertEquals("2x", formatSpeedLabel(2.0f)) }
    @Test fun `一位小数原样`() { assertEquals("4.9x", formatSpeedLabel(4.9f)) }
    @Test fun `1x 也正常格式化`() { assertEquals("1x", formatSpeedLabel(1.0f)) }
}
