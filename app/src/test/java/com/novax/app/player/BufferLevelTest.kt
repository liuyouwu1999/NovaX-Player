package com.novax.app.player

import org.junit.Assert.*
import org.junit.Test

class BufferLevelTest {
    @Test fun levelsHaveExpectedTargets() {
        assertEquals(15_000, BufferLevel.from("low").forwardMs)
        assertEquals(50_000, BufferLevel.from("medium").forwardMs)
        assertEquals(120_000, BufferLevel.from("high").forwardMs)
    }
    @Test fun invalidPreferenceUsesMedium() {
        assertEquals(BufferLevel.MEDIUM, BufferLevel.from(""))
        assertEquals(BufferLevel.MEDIUM, BufferLevel.from("unexpected"))
    }
    @Test fun upperBoundNeverBelowForwardTarget() {
        BufferLevel.entries.forEach { assertTrue(it.maxMs >= it.forwardMs) }
    }
}
