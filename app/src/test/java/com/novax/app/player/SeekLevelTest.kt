package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * seek 分级阈值（2026-09-18 用户反馈：±15s 连跳「看一秒再跳」不该转圈卡顿）：
 * Micro 档从 <12s 提到 <45s（CLOSEST_SYNC 关键帧直达不转圈），±15s/±30s 必须落 Micro。
 */
class SeekLevelTest {

    @Test
    fun `±15s 与 ±30s 相对跳变归 Micro（CLOSEST_SYNC 零转圈）`() {
        assertEquals(SeekLevel.Micro, seekLevel(15_000))
        assertEquals(SeekLevel.Micro, seekLevel(30_000))
        assertEquals(SeekLevel.Micro, seekLevel(44_999))
        // 双击 ±10s 原有语义不变
        assertEquals(SeekLevel.Micro, seekLevel(10_000))
    }

    @Test
    fun `45s 以上仍走 Standard 关键帧近似，2 分钟以上 Large`() {
        assertEquals(SeekLevel.Standard, seekLevel(45_000))
        assertEquals(SeekLevel.Standard, seekLevel(60_000))
        assertEquals(SeekLevel.Large, seekLevel(121_000))
    }

    @Test
    fun `精确跳转设置开启时全部 Exact（用户显式要求精确）`() {
        assertEquals(SeekLevel.Exact, seekLevel(10_000, exactMode = true))
        assertEquals(SeekLevel.Exact, seekLevel(300_000, exactMode = true))
    }
}
