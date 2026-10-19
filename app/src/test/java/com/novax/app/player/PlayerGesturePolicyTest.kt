package com.novax.app.player

import org.junit.Assert.*
import org.junit.Test

class PlayerGesturePolicyTest {
    @Test fun belowThresholdDiagonal_doesNotSlide() {
        assertNull(initialPlayerSlide(100f, 400f, 20f, 20f, 24f))
        assertNull(initialPlayerSlide(100f, 400f, 0f, 24f, 24f))
    }
    @Test fun vertical_usesStartHalfNotCurrentPosition() {
        assertEquals(PlayerSlide.BRIGHTNESS, initialPlayerSlide(190f, 400f, 20f, 40f, 24f))
        assertEquals(PlayerSlide.VOLUME, initialPlayerSlide(210f, 400f, -20f, -40f, 24f))
    }
    @Test fun horizontalDominant_seeksFromEitherHalf() {
        assertEquals(PlayerSlide.SEEK, initialPlayerSlide(10f, 400f, 40f, 30f, 24f))
        assertEquals(PlayerSlide.SEEK, initialPlayerSlide(390f, 400f, -40f, 30f, 24f))
    }

    // ---- 2026-10-08 用户需求：整屏步长 = 片长百分比滑杆自由设定（1~50%，默认 10%），
    // 删 30s~10min 夹取与三档灵敏度——「选到多少就是百分之多少」----

    @Test fun swipeSeek_percentOfDuration_noClamp() {
        // 2 小时电影 10%：整屏 = 12min（旧 10min 上限夹取已删——用户「不得劲」的根因）
        assertEquals(720_000L, swipeSeekDeltaMs(1f, 2 * 3600_000L, 10))
        // 45 分钟剧集 10%：整屏 = 4.5min；半屏 = 2.25min
        assertEquals(270_000L, swipeSeekDeltaMs(1f, 45 * 60_000L, 10))
        assertEquals(135_000L, swipeSeekDeltaMs(0.5f, 45 * 60_000L, 10))
    }

    @Test fun swipeSeek_customPercent() {
        // 50%：1 小时视频整屏 = 30min；1%：2 小时视频整屏 = 72s
        assertEquals(1_800_000L, swipeSeekDeltaMs(1f, 3600_000L, 50))
        assertEquals(72_000L, swipeSeekDeltaMs(1f, 2 * 3600_000L, 1))
    }

    @Test fun swipeSeek_percentClampedToValidRange() {
        // 越界百分比（DataStore 手改/旧版残留）防御性夹取到 1~50
        assertEquals(1_800_000L, swipeSeekDeltaMs(1f, 3600_000L, 100))   // →50%
        assertEquals(36_000L, swipeSeekDeltaMs(1f, 3600_000L, 0))        // →1%
        assertEquals(36_000L, swipeSeekDeltaMs(1f, 3600_000L, -7))       // →1%
    }

    @Test fun swipeSeek_unknownDurationScalesDefault() {
        // 时长未知（直播/慢源起播早期）：60s 基准按百分比等比缩放（10% → 60s，20% → 120s）
        assertEquals(60_000L, swipeSeekDeltaMs(1f, 0L, 10))
        assertEquals(120_000L, swipeSeekDeltaMs(1f, 0L, 20))
        assertEquals(60_000L, swipeSeekDeltaMs(1f, -5L, 10))
    }

    @Test fun swipeSeek_signAndMinStep() {
        // 向左滑为负；过了触摸 slop 的有效滑动保底 ±5s（微划不「和没滑一样」）
        assertEquals(-135_000L, swipeSeekDeltaMs(-0.5f, 45 * 60_000L, 10))
        assertEquals(5_000L, swipeSeekDeltaMs(0.05f, 2 * 60_000L, 10))   // 2min 视频 10% 整屏=12s，5% 划=0.6s → 保底 5s
        assertEquals(5_000L, swipeSeekDeltaMs(0.005f, 2 * 60_000L, 10))
        assertEquals(-5_000L, swipeSeekDeltaMs(-0.05f, 2 * 60_000L, 10))
        assertEquals(0L, swipeSeekDeltaMs(0f, 2 * 60_000L, 10))
    }
}
