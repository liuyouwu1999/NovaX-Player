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
}
