package com.novax.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun snap_coercesTo01Step() {
        assertEquals(2.3f, snapPlaybackSpeed(2.34f))
        assertEquals(2.4f, snapPlaybackSpeed(2.35f))
        assertEquals(0.1f, snapPlaybackSpeed(0.1499f))
    }

    @Test fun snap_clampsToRange() {
        assertEquals(0.1f, snapPlaybackSpeed(0.01f))
        assertEquals(5f, snapPlaybackSpeed(9.9f))
        assertEquals(5f, snapPlaybackSpeed(Float.MAX_VALUE))
    }

    @Test fun snap_passThroughPresets() {
        // 0.1 网格上的档位吸附后不变；0.75/1.25 不在网格上（走列表直选路径，不经过 snap）
        for (s in listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)) {
            assertEquals(s, snapPlaybackSpeed(s))
        }
    }
}
