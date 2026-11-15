// app/src/test/java/com/novax/app/player/StreamingRowTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingRowTest {
    @Test fun `宽幅七槽位含倍速`() = assertEquals(
        listOf(StreamingSlot.PREV, StreamingSlot.REW, StreamingSlot.PAUSE, StreamingSlot.FWD, StreamingSlot.NEXT, StreamingSlot.TIME, StreamingSlot.SPEED),
        streamingRowSlots(narrow = false))
    @Test fun `窄幅去倍速(Review Focus 6)`() {
        val s = streamingRowSlots(narrow = true)
        assertEquals(false, s.contains(StreamingSlot.SPEED))   // 倍速经顶栏更多+长按仍可达
        assertEquals(true, s.contains(StreamingSlot.TIME))
    }
}
