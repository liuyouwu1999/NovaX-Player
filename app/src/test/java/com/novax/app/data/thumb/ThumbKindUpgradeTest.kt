package com.novax.app.data.thumb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** kind 升级判定边界（2026-09-15 接线修复；评审要求锁 2399999/2400000ms） */
class ThumbKindUpgradeTest {

    @Test
    fun boundary_exactlyFortyMinutes_upgrades() {
        assertTrue(Thumbnailer.isMovieLongEnough(2_400_000L))
    }

    @Test
    fun boundary_justBelowFortyMinutes_doesNotUpgrade() {
        assertFalse(Thumbnailer.isMovieLongEnough(2_399_999L))
    }

    @Test
    fun nullOrZero_doesNotUpgrade() {
        assertFalse(Thumbnailer.isMovieLongEnough(null))
        assertFalse(Thumbnailer.isMovieLongEnough(0L))
    }
}
