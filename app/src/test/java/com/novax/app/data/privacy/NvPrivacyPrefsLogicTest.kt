package com.novax.app.data.privacy
import org.junit.Assert.*; import org.junit.Test

/** 纯 JVM：只测 CooldownMath（DataStore 本体不进单测，见全局约束）。 */
class NvPrivacyPrefsLogicTest {

    @Test fun `同boot未到期_剩余秒向上取整`() {
        // 剩 1500ms → 2 秒（冷却显示不能比实际短）
        assertEquals(2, CooldownMath.remainingSec(lockUntilElapsed = 61_500, bootAtLock = 7, nowElapsed = 60_000, nowBoot = 7))
    }

    @Test fun `同boot剩1ms_向上取整为1秒`() {
        assertEquals(1, CooldownMath.remainingSec(lockUntilElapsed = 60_001, bootAtLock = 7, nowElapsed = 60_000, nowBoot = 7))
    }

    @Test fun `同boot恰好到界_0秒`() {
        assertEquals(0, CooldownMath.remainingSec(lockUntilElapsed = 60_000, bootAtLock = 7, nowElapsed = 60_000, nowBoot = 7))
    }

    @Test fun `同boot已过期_0秒不透负`() {
        assertEquals(0, CooldownMath.remainingSec(lockUntilElapsed = 59_000, bootAtLock = 7, nowElapsed = 60_000, nowBoot = 7))
        assertEquals(0, CooldownMath.remainingSec(lockUntilElapsed = 1, bootAtLock = 7, nowElapsed = 900_000, nowBoot = 7))
    }

    @Test fun `boot变化_视为已过期0秒`() {
        // 重启后 elapsedRealtime 从零起算，旧截止值无意义；即使按数值未到期也必须 0
        assertEquals(0, CooldownMath.remainingSec(lockUntilElapsed = 120_000, bootAtLock = 7, nowElapsed = 60_000, nowBoot = 8))
    }

    @Test fun `长冷却剩余整秒`() {
        // 对应 Task 6「错到 10 次→冷却 300 秒」：剩整 300s
        assertEquals(300, CooldownMath.remainingSec(lockUntilElapsed = 360_000, bootAtLock = 3, nowElapsed = 60_000, nowBoot = 3))
    }
}
