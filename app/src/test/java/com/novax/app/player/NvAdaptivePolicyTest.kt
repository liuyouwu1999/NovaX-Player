package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NvAdaptivePolicy 单测（2026-09-15 阈值收敛后重写）。
 *
 * 时间轴约定：`now` 是可控时钟。`ready()` 表示「此刻达到 READY」，
 * 因策略要求「稳定 SETTLE_AFTER_READY_MS 之后才判定升档」，
 * 凡是要判定升级的用例都必须先让时钟越过稳定期。
 */
class NvAdaptivePolicyTest {

    private var now = 0L

    private fun policy() = NvAdaptivePolicy(nowMs = { now })

    /** 一次完整 rebuffer（BUFFERING→READY），结束时把稳定起点设为当前时刻 */
    private fun NvAdaptivePolicy.rebuffer(durationMs: Long = 1_000) {
        onStateChanged(stateBuffering = true, playing = true, frozen = false)
        now += durationMs
        onStateChanged(stateBuffering = false, playing = true, frozen = false)
    }

    /** 稳定期：READY 之后经过该时长才允许判定升档 */
    private fun NvAdaptivePolicy.settle() {
        now += 21_000
    }

    // ---------------- 基本行为 ----------------

    @Test
    fun `稳定期内单次短缓冲不升级`() {
        val p = policy()
        p.rebuffer()
        now += 5_000
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `稳定后单次短缓冲仍不升级`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false) // 起播即 READY
        p.settle()
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `180秒内三次缓冲触发升级`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 10_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)  // 两次不再升级（旧阈值是 2 次）
        now += 10_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
    }

    @Test
    fun `两次缓冲不再升级（旧阈值 60s 2 次已被收敛）`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `单次缓冲超过30秒才按长卡判定，8秒不再触发`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.onStateChanged(stateBuffering = true, playing = true, frozen = false)
        now += 8_500
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)      // 旧阈值下这里会升级
        now += 22_000                                                     // 累计 > 30s
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
    }

    @Test
    fun `升级后连续180秒无缓冲触发恢复`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 10_000
        p.rebuffer()
        now += 10_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
        now += 179_000
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
        now += 1_100
        assertEquals(NvAdaptivePolicy.Action.RESTORE, p.tick().action)
    }

    @Test
    fun `升级后再次缓冲重置恢复计时`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
        now += 100_000
        p.rebuffer()
        now += 100_000
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)      // 距上次 rebuffer 100s < 180s
        now += 80_100
        assertEquals(NvAdaptivePolicy.Action.RESTORE, p.tick().action)
    }

    @Test
    fun `动作冷却期内不升级`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
        // 升级后立刻又劣化：冷却 60s 内不再动作
        now += 25_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `ENDED冻结清理窗口并阻止恢复`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
        now += 30_000
        p.onStateChanged(stateBuffering = false, playing = false, frozen = true)
        now += 1_000_000
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `换引擎清观测但保留升降级记忆`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
        p.onEngineSwapped()
        now += 200_000
        assertEquals(NvAdaptivePolicy.Action.RESTORE, p.tick().action)
    }

    @Test
    fun `reset清空升降级记忆`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertTrue(p.tick().action == NvAdaptivePolicy.Action.ESCALATE)
        p.reset()
        now += 10_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `reset之后冷却恢复正常仍可再次升级（溢出回归）`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
        p.reset()
        now += 2_000
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
    }

    // ---------------- 2026-09-15 收敛：用户 seek 与自身重建都不算网络抖动 ----------------

    @Test
    fun `用户seek之后的起播缓冲不触发升级`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        // 用户拖动进度条：连续多次 seek，每次都带一段起播缓冲
        repeat(4) {
            p.onUserSeek()
            p.rebuffer(1_200)
            now += 3_000
        }
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `seek清空此前累计的缓冲计数`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()                 // 已累计 2 次
        p.onUserSeek()               // 用户 seek：计数清空
        now += 10_000
        p.rebuffer()                 // 只剩 1 次
        now += 1_000
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `引擎重建宽限窗内的缓冲不计入rebuffer`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        // 自身重建 → 宽限窗内出现缓冲（正是真机上的自激来源）
        repeat(4) {
            p.onEngineRebuilt()
            p.rebuffer(1_500)
            now += 1_000
        }
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }

    @Test
    fun `宽限窗过后真实劣化仍会升级`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.onEngineRebuilt()
        now += 16_000                                   // 越过宽限窗
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        now += 1_000
        p.rebuffer()
        assertEquals(NvAdaptivePolicy.Action.ESCALATE, p.tick().action)
    }

    @Test
    fun `不足200ms的缓冲抖动仍然不计`() {
        val p = policy()
        p.onStateChanged(stateBuffering = false, playing = true, frozen = false)
        p.settle()
        repeat(5) { p.rebuffer(50) }
        assertEquals(NvAdaptivePolicy.Action.NONE, p.tick().action)
    }
}
