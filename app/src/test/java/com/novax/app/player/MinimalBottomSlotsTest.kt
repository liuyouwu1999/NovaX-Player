// app/src/test/java/com/novax/app/player/MinimalBottomSlotsTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** R4 极简底栏槽位（spec 2026-11-15 §3.2 唤起态布局图底栏行）：宽幅七钮等距、窄幅五钮。 */
class MinimalBottomSlotsTest {
    @Test fun `宽幅七槽含锁与比例`() = assertEquals(
        listOf(
            MinimalSlot.LOCK, MinimalSlot.PREV, MinimalSlot.REW, MinimalSlot.PAUSE,
            MinimalSlot.FWD, MinimalSlot.NEXT, MinimalSlot.ASPECT,
        ),
        minimalBottomSlots(narrow = false),
    )

    @Test fun `窄幅五槽去锁与比例`() {
        val s = minimalBottomSlots(narrow = true)
        assertEquals(
            listOf(MinimalSlot.PREV, MinimalSlot.REW, MinimalSlot.PAUSE, MinimalSlot.FWD, MinimalSlot.NEXT),
            s,
        )
        assertEquals(false, s.contains(MinimalSlot.LOCK))    // 锁经左圆钮仍可达（spec §3.2）
        assertEquals(false, s.contains(MinimalSlot.ASPECT))  // 比例经更多面板仍可达（spec §3.2）
    }
}
