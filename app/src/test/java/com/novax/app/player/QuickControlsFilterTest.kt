package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「更多」面板「播放控制」组键集过滤（spec 2026-11-15 §5.5，T8）：
 * 主播放器=功能全集 16 项兜底；RMVB 按 §5.3 键集剔除 PIP/清晰度/投屏；
 * QUALITY 仅主播放器且 Jellyfin 有质量档时可见（Review Focus #5）。
 */
class QuickControlsFilterTest {
    @Test fun `主播放器全集 16 项`() = assertEquals(16, quickControlsFor(isRmvb = false, hasQuality = true).size)

    @Test fun `RMVB 剔除 PIP QUALITY CAST 三项(Review Focus 5)`() {
        val s = quickControlsFor(isRmvb = true, hasQuality = true)
        assertFalse(s.contains(QuickControlId.PIP)); assertFalse(s.contains(QuickControlId.QUALITY)); assertFalse(s.contains(QuickControlId.CAST))
        assertEquals(13, s.size)
    }

    @Test fun `无质量档时主播放器也隐藏 QUALITY`() =
        assertEquals(false, quickControlsFor(isRmvb = false, hasQuality = false).contains(QuickControlId.QUALITY))
}
