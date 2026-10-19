package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdOrientationGateTest {
    @Test
    fun gate_onlyTrueForColdNotificationEntry() {
        assertTrue(PlayerActivity.coldAutoOrientationGate(fromNotification = true, hasSavedState = false))
        assertFalse("带 savedInstanceState 的重建会恢复用户手选方向,禁止自动定向", PlayerActivity.coldAutoOrientationGate(true, true))
        assertFalse("应用内入口不自动定向", PlayerActivity.coldAutoOrientationGate(false, false))
    }

    @Test
    fun pipAspectRatio_knownSizeUsesRealRatio() {
        assertEquals(9 to 16, PlayerActivity.pipAspectRatioCode(1080, 1920))
        assertEquals(16 to 9, PlayerActivity.pipAspectRatioCode(1920, 1080))
    }

    @Test
    fun pipAspectRatio_unknownOrIllegalFallsBackTo16x9() {
        assertEquals(16 to 9, PlayerActivity.pipAspectRatioCode(0, 0))
        assertEquals(16 to 9, PlayerActivity.pipAspectRatioCode(-1, 1080))
    }

    @Test
    fun pipAspectRatio_extremeRatioClampedIntoPipDomain() {
        val (nw, nh) = PlayerActivity.pipAspectRatioCode(3840, 800)   // 4.8 超上界 2.39
        val f = nw.toFloat() / nh.toFloat()
        assertTrue("钳制后须 ≤2.39", f <= 2.39f)
        val (vw, vh) = PlayerActivity.pipAspectRatioCode(600, 3000)   // 0.2 超下界 0.42
        val g = vw.toFloat() / vh.toFloat()
        assertTrue("钳制后须 ≥0.42", g >= 0.42f)
    }
}
