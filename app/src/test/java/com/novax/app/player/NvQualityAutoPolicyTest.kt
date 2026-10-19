package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NvQualityAutoPolicy 决策语义（spec §6；Task 3）。
 *
 * 档位约定：[NvQualityAutoPolicy.tick] 的 activeTier 传「用户所选档位」——
 * AUTO = 自动档运行中（阶梯基于内部生效档，起播原画逐档步进）；
 * 其余任何值（含 ORIGINAL）= 手动档，policy 永不决策。
 * 时序用注入 nowMs 手动推进（同 NvAdaptivePolicyTest 惯例）。
 */
class NvQualityAutoPolicyTest {

    private var t = 0L
    private fun policy(): NvQualityAutoPolicy = NvQualityAutoPolicy(nowMs = { t })
    private fun advance(ms: Long) { t += ms }

    @Test
    fun longBuffer_onAuto_downgradesFromOriginal() {
        val p = policy()
        p.onEngineSwapped(); advance(30_000)
        // 稳定期过后一次长缓冲（>30s）→ AUTO 从原画降到 20M
        p.onStateChanged(stateBuffering = true, playing = true, frozen = false)
        advance(31_000)
        val d = p.tick(NvQualityTier.AUTO)
        assertEquals(NvQualityAutoPolicy.Action.DOWNGRADE, d.action)
        assertEquals(NvQualityTier.P20M, d.target)
        assertEquals(NvQualityTier.P20M, p.currentTier())
    }

    @Test
    fun downgrade_stopsAt2M_floor() {
        val p = policy()
        p.onEngineSwapped()
        // 连续长缓冲把阶梯逐档压到下界 P2M（每次动作间隔 61s > 冷却 60s）
        repeat(4) {
            advance(30_000)
            p.onStateChanged(true, true, false)
            advance(31_000)
            assertEquals(NvQualityAutoPolicy.Action.DOWNGRADE, p.tick(NvQualityTier.AUTO).action)
        }
        assertEquals(NvQualityTier.P2M, p.currentTier())
        // P2M 已是下界：条件再满足也无更低档 → NONE
        advance(30_000)
        p.onStateChanged(true, true, false)
        advance(31_000)
        val d = p.tick(NvQualityTier.AUTO)
        assertEquals(NvQualityAutoPolicy.Action.NONE, d.action)
        assertNull(d.target)
    }

    @Test
    fun rebuildGraceWindow_ignoresSelfInflictedBuffering() {
        val p = policy()
        p.onEngineSwapped()                   // 宽限窗开（15s）
        p.onStateChanged(true, true, false)   // 缓冲始于窗内（t=0）
        advance(31_000)                       // 窗内长缓冲
        // 缓冲开始于宽限窗内（bufferingSince <= graceUntil）：再长也不算网络证据（防自激）→ NONE
        assertEquals(NvQualityAutoPolicy.Action.NONE, p.tick(NvQualityTier.AUTO).action)
    }

    @Test
    fun upgrade_afterLongStablePlayback() {
        val p = policy()
        p.onEngineSwapped()
        // 先三次长缓冲把阶梯压到 P4M
        repeat(3) {
            advance(30_000)
            p.onStateChanged(true, true, false)
            advance(31_000)
            assertEquals(NvQualityAutoPolicy.Action.DOWNGRADE, p.tick(NvQualityTier.AUTO).action)
        }
        assertEquals(NvQualityTier.P4M, p.currentTier())
        // 正常播放 3 分钟无 rebuffer → 升回 P10M（邻档步进）
        p.onStateChanged(false, true, false)
        advance(180_000)
        val d = p.tick(NvQualityTier.AUTO)
        assertEquals(NvQualityAutoPolicy.Action.UPGRADE, d.action)
        assertEquals(NvQualityTier.P10M, d.target)
    }

    @Test
    fun upgrade_neverAboveOriginal_capAtOriginal() {
        val p = policy()
        p.onEngineSwapped(); advance(30_000)
        p.onStateChanged(false, true, false); advance(180_000)
        val d = p.tick(NvQualityTier.AUTO)
        assertEquals(NvQualityAutoPolicy.Action.NONE, d.action)
        assertNull(d.target)   // 原画即上界
    }

    @Test
    fun cooldown_betweenActions() {
        val p = policy()
        p.onEngineSwapped(); advance(30_000)
        p.onStateChanged(true, true, false); advance(31_000)
        assertEquals(NvQualityAutoPolicy.Action.DOWNGRADE, p.tick(NvQualityTier.AUTO).action)
        p.onUserAction()      // 切档后动作时间戳刷新 + 冷却
        advance(10_000)       // 冷却 60s 内再满足条件 → NONE
        p.onStateChanged(true, true, false); advance(31_000)
        assertEquals(NvQualityAutoPolicy.Action.NONE, p.tick(NvQualityTier.AUTO).action)
    }

    @Test
    fun manualTier_notAuto_neverDecides() {
        val p = policy()
        p.onEngineSwapped(); advance(30_000)
        p.onStateChanged(true, true, false); advance(31_000)
        assertNull(p.tick(NvQualityTier.P4M).target)   // 手动档 policy 不出决策
        assertEquals(NvQualityAutoPolicy.Action.NONE, p.tick(NvQualityTier.P4M).action)
    }
}
