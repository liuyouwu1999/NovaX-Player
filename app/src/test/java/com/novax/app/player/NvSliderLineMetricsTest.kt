package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NvSliderLineMetricsTest {
    @Test fun `classic 模式保留时间行旋钮与 16dp 内边距`() {
        val m = nvSliderLineMetrics(NvSliderVisual.CLASSIC)
        assertEquals(16f, m.hPaddingDp, 0.01f)
        assertEquals(true, m.showsTimeRow)
        assertEquals(true, m.showsThumb)
    }
    @Test fun `line 模式贴缘无时间行无旋钮`() {
        val m = nvSliderLineMetrics(NvSliderVisual.LINE)
        assertEquals(0f, m.hPaddingDp, 0.01f)
        assertEquals(false, m.showsTimeRow)   // 时间由常驻层/顶栏承载（spec §5.2 口径）
        assertEquals(false, m.showsThumb)
    }
    @Test fun `line 未播段专用色白50(根修2026-11-15真机缺陷 Track 20%白叠黑不可辨)`() {
        // Color 分量经 8bit 量化（0.5f→0x80=0.50196），容差取 0.002
        assertEquals(0.5f, NvLineTrackUnplayed.alpha, 0.002f)
        assertEquals(1f, NvLineTrackUnplayed.red, 0.002f)
        assertEquals(1f, NvLineTrackUnplayed.green, 0.002f)
        assertEquals(1f, NvLineTrackUnplayed.blue, 0.002f)
    }
}
