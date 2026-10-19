package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 方向模式变化旋转策略单测(2026-10-14,spec §3-1)。
 * 契约: 模式真实变化→归零;模式相等(回前台重申)→保持;old=null(冷启动防御)→归零。
 * 泛型双实例化: 主播放器 OrientationMode 枚举语义用 String 代理,RMVB 页 Int 模式直接测。
 */
class NvOrientationPolicyTest {

    @Test
    fun `模式变化 - 归零`() {
        assertEquals(0, NvOrientationPolicy.rotationAfterModeChange("LANDSCAPE", "PORTRAIT", 90))
    }

    @Test
    fun `模式相等 - 保持原值`() {
        assertEquals(90, NvOrientationPolicy.rotationAfterModeChange("LANDSCAPE", "LANDSCAPE", 90))
        assertEquals(270, NvOrientationPolicy.rotationAfterModeChange(0, 0, 270))
    }

    @Test
    fun `old为null冷启动 - 归零`() {
        assertEquals(0, NvOrientationPolicy.rotationAfterModeChange(null, "LANDSCAPE", 180))
    }

    @Test
    fun `RMVB Int模式 - 变化归零相等保持`() {
        assertEquals(0, NvOrientationPolicy.rotationAfterModeChange(0, 2, 90))
        assertEquals(90, NvOrientationPolicy.rotationAfterModeChange(2, 2, 90))
    }

    @Test
    fun `任意currentRotation - 变化恒归零`() {
        assertEquals(0, NvOrientationPolicy.rotationAfterModeChange(1, 0, 270))
        assertEquals(0, NvOrientationPolicy.rotationAfterModeChange("A", "B", 1))
    }

    // ---- 2026-10-17 系统旋转锁定驱动重力跟随(设计要点 2/5/6) ----

    @Test
    fun `锁定 - 自动档收拢为当前实际竖屏`() {
        assertEquals(
            "PORTRAIT",
            NvOrientationPolicy.modeAfterSystemRotationLockChange(
                systemLocked = true, currentMode = "AUTO_SENSOR",
                autoMode = "AUTO_SENSOR", portraitMode = "PORTRAIT", landscapeMode = "LANDSCAPE",
                actuallyPortrait = true,
            ),
        )
    }

    @Test
    fun `锁定 - 自动档收拢为当前实际横屏`() {
        assertEquals(
            "LANDSCAPE",
            NvOrientationPolicy.modeAfterSystemRotationLockChange(
                true, "AUTO_SENSOR", "AUTO_SENSOR", "PORTRAIT", "LANDSCAPE", false,
            ),
        )
    }

    @Test
    fun `锁定 - 已是固定档不动`() {
        assertNull(NvOrientationPolicy.modeAfterSystemRotationLockChange(
            true, "LANDSCAPE", "AUTO_SENSOR", "PORTRAIT", "LANDSCAPE", true))
        assertNull(NvOrientationPolicy.modeAfterSystemRotationLockChange(
            true, "PORTRAIT", "AUTO_SENSOR", "PORTRAIT", "LANDSCAPE", false))
    }

    @Test
    fun `解锁 - 固定档立即恢复自动·重力`() {
        assertEquals("AUTO_SENSOR", NvOrientationPolicy.modeAfterSystemRotationLockChange(
            false, "LANDSCAPE", "AUTO_SENSOR", "PORTRAIT", "LANDSCAPE", true))
        assertEquals("AUTO_SENSOR", NvOrientationPolicy.modeAfterSystemRotationLockChange(
            false, "PORTRAIT", "AUTO_SENSOR", "PORTRAIT", "LANDSCAPE", false))
    }

    @Test
    fun `解锁 - 已是自动档不动`() {
        assertNull(NvOrientationPolicy.modeAfterSystemRotationLockChange(
            false, "AUTO_SENSOR", "AUTO_SENSOR", "PORTRAIT", "LANDSCAPE", true))
    }

    @Test
    fun `RMVB Int模式 - 锁定收拢解锁恢复`() {
        assertEquals(1, NvOrientationPolicy.modeAfterSystemRotationLockChange(true, 2, 2, 1, 0, true))
        assertEquals(0, NvOrientationPolicy.modeAfterSystemRotationLockChange(true, 2, 2, 1, 0, false))
        assertEquals(2, NvOrientationPolicy.modeAfterSystemRotationLockChange(false, 1, 2, 1, 0, false))
        assertNull(NvOrientationPolicy.modeAfterSystemRotationLockChange(false, 2, 2, 1, 0, false))
    }
}
