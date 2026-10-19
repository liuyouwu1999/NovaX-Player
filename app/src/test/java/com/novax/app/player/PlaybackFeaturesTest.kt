package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放功能状态持有器纯函数（审计 A 收敛：AB 循环/睡眠定时/长按倍速） */
class PlaybackFeaturesTest {

    // ---- AB 循环回跳判定 ----

    @Test
    fun `AB循环_越过B点回跳A`() {
        assertEquals(10_000L, PlaybackFeatures.loopSeekBackMs(90_000, 10_000, 90_000))
        assertEquals(10_000L, PlaybackFeatures.loopSeekBackMs(120_000, 10_000, 90_000))
    }

    @Test
    fun `AB循环_未设点或非法区间不循环`() {
        assertNull(PlaybackFeatures.loopSeekBackMs(90_000, -1L, -1L))       // 未设
        assertNull(PlaybackFeatures.loopSeekBackMs(90_000, 90_000, 90_000)) // B<=A 非法
        assertNull(PlaybackFeatures.loopSeekBackMs(90_000, 80_000, 70_000))
        assertNull(PlaybackFeatures.loopSeekBackMs(50_000, 10_000, 90_000)) // 未越过 B
    }

    // ---- 睡眠定时到期判定 ----

    @Test
    fun `睡眠定时_到期且启用才触发`() {
        assertTrue(PlaybackFeatures.sleepFired(1_000L, 1_000L))
        assertTrue(PlaybackFeatures.sleepFired(1_000L, 2_000L))
        assertFalse(PlaybackFeatures.sleepFired(2_000L, 1_999L))
        assertFalse(PlaybackFeatures.sleepFired(-1L, 9_999L))  // 未启用
        assertFalse(PlaybackFeatures.sleepFired(0L, 9_999L))
    }

    // ---- 长按倍速目标值 ----

    @Test
    fun `长按倍速_锁定时保持现值不被改写`() {
        // BUG-C-R1-04：0.5x 慢放锁定被再次长按 2x 静默替换
        assertEquals(0.5f, PlaybackFeatures.longPressTargetSpeed(
            locked = true, currentValue = 0.5f, fastForward = true, configuredSpeed = 2f, slowmoSpeed = 0.5f))
        assertEquals(3f, PlaybackFeatures.longPressTargetSpeed(
            locked = true, currentValue = 3f, fastForward = false, configuredSpeed = 2f, slowmoSpeed = 0.5f))
    }

    @Test
    fun `长按倍速_未锁定按方向取值并夹取`() {
        assertEquals(2f, PlaybackFeatures.longPressTargetSpeed(false, 1f, true, 2f, 0.5f))
        assertEquals(0.5f, PlaybackFeatures.longPressTargetSpeed(false, 1f, false, 2f, 0.5f))
        assertEquals(3f, PlaybackFeatures.longPressTargetSpeed(false, 1f, true, 9f, 0.5f))  // 异常配置夹上限
        assertEquals(0.5f, PlaybackFeatures.longPressTargetSpeed(false, 1f, true, 0.1f, 0.5f)) // 夹下限
    }

    // ---- 状态持有器复位口径 ----

    @Test
    fun `切集复位_AB与倍速清零_静音恢复_睡眠保留`() {
        val f = PlaybackFeatures()
        f.loopAMs = 1_000; f.loopBMs = 5_000
        f.speedShiftActive = true; f.speedShiftValue = 3f; f.speedShiftLocked = true
        f.speedBoostMuted = true
        f.sleepDeadlineMs = 9_999_999; f.sleepMinutes = 30

        f.resetForEpisodeSwitch()

        assertEquals(-1L, f.loopAMs)
        assertEquals(-1L, f.loopBMs)
        assertFalse(f.speedShiftActive)
        assertFalse(f.speedShiftLocked)
        assertEquals(PlaybackEngine.LONG_PRESS_SPEED, f.speedShiftValue)
        assertFalse(f.speedBoostMuted)          // BUG-C-R4-04：切集必须恢复音量
        assertEquals(9_999_999L, f.sleepDeadlineMs)  // 睡眠跨换集仍有效
        assertEquals(30, f.sleepMinutes)
    }

    @Test
    fun `引擎重建复位_仅倍速三态`() {
        val f = PlaybackFeatures()
        f.loopAMs = 1_000
        f.speedShiftActive = true; f.speedShiftLocked = true
        f.resetSpeedShift()
        assertFalse(f.speedShiftActive)
        assertFalse(f.speedShiftLocked)
        assertEquals(1_000L, f.loopAMs)  // AB 由宿主按 remember(engine) 口径单独调 resetAbLoop
    }
}
