package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NvQualityTierTest {

    @Test
    fun ladder_order_and_steps() {
        assertEquals(NvQualityTier.P20M, NvQualityTier.ORIGINAL.lower())
        assertEquals(NvQualityTier.P10M, NvQualityTier.P20M.lower())
        assertEquals(NvQualityTier.P4M, NvQualityTier.P10M.lower())
        assertEquals(NvQualityTier.P2M, NvQualityTier.P4M.lower())
        assertNull(NvQualityTier.P2M.lower())
        assertNull(NvQualityTier.ORIGINAL.higher())
        assertEquals(NvQualityTier.P4M, NvQualityTier.P2M.higher())
        assertEquals(NvQualityTier.ORIGINAL, NvQualityTier.P20M.higher())
    }

    @Test
    fun auto_is_outside_ladder() {
        assertNull(NvQualityTier.AUTO.lower())
        assertNull(NvQualityTier.AUTO.higher())
    }

    @Test
    fun bitrate_mapping_matches_labels() {
        assertEquals(20_000_000, NvQualityTier.P20M.maxBitrateBps)
        assertEquals(10_000_000, NvQualityTier.P10M.maxBitrateBps)
        assertEquals(4_000_000, NvQualityTier.P4M.maxBitrateBps)
        assertEquals(2_000_000, NvQualityTier.P2M.maxBitrateBps)
    }

    @Test
    fun fromName_fallsBackToAuto() {
        assertEquals(NvQualityTier.AUTO, NvQualityTier.fromName(null))
        assertEquals(NvQualityTier.AUTO, NvQualityTier.fromName("garbage"))
        assertEquals(NvQualityTier.P4M, NvQualityTier.fromName("P4M"))
    }

    @Test
    fun availableTiers_hidesTiersAtOrAboveSourceBitrate() {
        // 2026-10-08 用户缺陷同源实证（海市蜃楼.E01 源码率 3.1M）：4M 及以上 = 原画的重复项
        val list = NvQualityTier.availableTiers(3_124_018L)
        assertEquals(
            listOf(NvQualityTier.ORIGINAL, NvQualityTier.P2M, NvQualityTier.AUTO),
            list,
        )
    }

    @Test
    fun availableTiers_highBitrateSource_showsAllTranscodeTiers() {
        // 25M 片源：20M 及以下全部有效（严格小于）
        val list = NvQualityTier.availableTiers(25_000_000L)
        assertEquals(
            listOf(
                NvQualityTier.ORIGINAL, NvQualityTier.P20M, NvQualityTier.P10M,
                NvQualityTier.P4M, NvQualityTier.P2M, NvQualityTier.AUTO,
            ),
            list,
        )
    }

    @Test
    fun availableTiers_unknownBitrate_showsAll() {
        assertEquals(NvQualityTier.entries.toList(), NvQualityTier.availableTiers(0L))
    }

    @Test
    fun availableTiers_lowBitrateSource_onlyOriginalAndAuto() {
        // 1.5M 片源：没有任何转码档低于它
        assertEquals(
            listOf(NvQualityTier.ORIGINAL, NvQualityTier.AUTO),
            NvQualityTier.availableTiers(1_500_000L),
        )
    }
}
