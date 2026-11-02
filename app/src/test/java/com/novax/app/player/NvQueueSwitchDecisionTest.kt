package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvQueueSwitchDecisionTest {

    private fun decide(size: Int, index: Int, dir: Int, inFlight: Boolean = false) =
        NvQueueSwitchDecision.decideEntry(inFlight, size, index, dir)

    @Test
    fun `in flight drop wins over everything`() {
        assertEquals(NvQueueSwitchDecision.SwitchAction.DROP_IN_FLIGHT, decide(5, 1, 1, inFlight = true))
        assertEquals(NvQueueSwitchDecision.SwitchAction.DROP_IN_FLIGHT, decide(0, 0, 1, inFlight = true))
        // 丢弃即丢弃：被挡的第二击不产生任何动作、不缓存（行为锚定 be6e3d8 :732）
    }

    @Test
    fun `empty queue ignored silently`() {
        assertEquals(NvQueueSwitchDecision.SwitchAction.IGNORE_EMPTY_QUEUE, decide(0, 0, 1))
    }

    @Test
    fun `bound first and last`() {
        assertEquals(NvQueueSwitchDecision.SwitchAction.BOUND_FIRST, decide(3, 0, -1))
        assertEquals(NvQueueSwitchDecision.SwitchAction.BOUND_LAST, decide(3, 2, 1))
    }

    @Test
    fun `proceed inside bounds`() {
        assertEquals(NvQueueSwitchDecision.SwitchAction.PROCEED, decide(3, 1, -1))
        assertEquals(NvQueueSwitchDecision.SwitchAction.PROCEED, decide(3, 1, 1))
    }

    @Test
    fun `size one never proceeds`() {
        // 评审 P1-1 的根：入口 isEmpty 与 canSwitch(size>1) 不等——本判定单集返回 BOUND_LAST，
        // 上游 QueueCommandsPlayer canSwitch 守卫（本任务补）保证单集根本到不了这里（双保险）
        assertEquals(NvQueueSwitchDecision.SwitchAction.BOUND_LAST, decide(1, 0, 1))
    }

    @Test
    fun `stale intent detection`() {
        assertTrue(NvQueueSwitchDecision.isStaleIntent("a", "b"))
        assertTrue(NvQueueSwitchDecision.isStaleIntent("a", null))
        assertFalse(NvQueueSwitchDecision.isStaleIntent("a", "a"))
    }
}
