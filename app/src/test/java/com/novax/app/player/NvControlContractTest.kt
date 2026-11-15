// app/src/test/java/com/novax/app/player/NvControlContractTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NvControlContractTest {
    @Test fun `默认契约状态值安全`() {
        val c = NvControlContract()
        assertEquals(0L, c.positionMs); assertEquals(0L, c.bufferedMs); assertEquals(0L, c.durationMs)
        assertEquals(false, c.isPlaying); assertEquals(1f, c.speed, 0.001f)
        assertEquals(0, c.lockMode); assertEquals(15, c.skipSec)
        assertEquals(false, c.hasPrev); assertEquals(false, c.hasNext)
        assertEquals("", c.title)
    }
    @Test fun `样式无关的全量回调可空持有`() {
        val c = NvControlContract()
        c.onTogglePlay()          // 默认 no-op 不崩
        c.onMore()
        assertNotNull(c.onSeek)
    }
}
