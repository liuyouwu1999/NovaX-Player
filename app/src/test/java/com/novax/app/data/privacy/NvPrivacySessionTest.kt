package com.novax.app.data.privacy

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 解锁会话单测（隐私计划 Task 11，纯 JVM）：
 * generation 递增 / relockAll 返回全部解锁规则（补扫完成与否一律清，fail-closed 超集）/
 * markRescanned 只记已解锁规则 / resetForTest 复位。重锁事件由 Orchestrator 发，见其测试。
 */
class NvPrivacySessionTest {

    @Before
    fun setUp() = NvPrivacySession.resetForTest()

    @After
    fun tearDown() = NvPrivacySession.resetForTest()

    @Test
    fun `初始状态代数为零且无解锁`() {
        assertEquals(0L, NvPrivacySession.generation)
        assertTrue(NvPrivacySession.unlockedRuleIds().isEmpty())
    }

    @Test
    fun `解锁进入会话集且重复解锁幂等`() {
        NvPrivacySession.unlock(3L)
        assertEquals(setOf(3L), NvPrivacySession.unlockedRuleIds())
        NvPrivacySession.unlock(5L)
        NvPrivacySession.unlock(3L)
        assertEquals(setOf(3L, 5L), NvPrivacySession.unlockedRuleIds())
    }

    @Test
    fun `重锁后代数递增并返回待清理集且清空会话`() {
        NvPrivacySession.unlock(1L)
        NvPrivacySession.unlock(2L)
        val before = NvPrivacySession.generation
        assertEquals(listOf(1L, 2L), NvPrivacySession.relockAll())
        assertEquals(before + 1, NvPrivacySession.generation)
        assertTrue(NvPrivacySession.unlockedRuleIds().isEmpty())
        assertTrue(NvPrivacySession.rescannedRuleIdsForTest().isEmpty())
    }

    @Test
    fun `重锁清理集含补扫完成与未完成的全部解锁规则`() {
        NvPrivacySession.unlock(7L)
        NvPrivacySession.markRescanned(7L)   // 补扫已完成
        NvPrivacySession.unlock(9L)          // 补扫未完成（解锁即退后台）也必须清——缩略图/入库行可能已在
        val wiped = NvPrivacySession.relockAll()
        assertTrue(7L in wiped)
        assertTrue(9L in wiped)
    }

    @Test
    fun `未解锁规则的补扫标记被忽略`() {
        NvPrivacySession.markRescanned(42L)
        assertTrue(NvPrivacySession.rescannedRuleIdsForTest().isEmpty())
    }

    @Test
    fun `无解锁时重锁返回空集但代数仍递增`() {
        val before = NvPrivacySession.generation
        assertTrue(NvPrivacySession.relockAll().isEmpty())
        assertEquals(before + 1, NvPrivacySession.generation)
    }

    @Test
    fun `resetForTest 完全复位`() {
        NvPrivacySession.unlock(1L)
        NvPrivacySession.markRescanned(1L)
        NvPrivacySession.relockAll()
        NvPrivacySession.resetForTest()
        assertEquals(0L, NvPrivacySession.generation)
        assertTrue(NvPrivacySession.unlockedRuleIds().isEmpty())
        assertTrue(NvPrivacySession.rescannedRuleIdsForTest().isEmpty())
    }

    @Test
    fun `relockEvents 可订阅且 emit 不挂起`() = runBlocking {
        val received = mutableListOf<List<Long>>()
        val job = launch { NvPrivacySession.relockEvents.collect { received += it.wipedRuleIds } }
        repeat(4) { yield() }   // 让订阅先注册（replay=0，只收订阅后的值）
        NvPrivacySession.relockEvents.emit(NvPrivacySession.RelockEvent(listOf(8L)))
        repeat(20) { yield() }
        assertEquals(listOf(listOf(8L)), received)
        job.cancel()
    }
}
