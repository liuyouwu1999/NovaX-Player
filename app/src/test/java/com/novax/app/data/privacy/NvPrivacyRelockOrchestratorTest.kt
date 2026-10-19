package com.novax.app.data.privacy

import com.novax.app.data.privacy.NvPrivacyRelockOrchestrator.Deps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 重锁编排单测（隐私计划 Task 11，纯 JVM）：purgeScope/pauseAll/ruleScope/NvPinStore 全部
 * 注入缝替换并写同一事件日志，断言 relock 的硬顺序——generation 递增 → relockAll →
 * pendingWipe **先落盘** → 逐规则清理 → 清 pendingWipe（失败的保留）→ 暂停本地播放 →
 * 投屏停止钩子 → 发重锁事件；以及冷启动 pendingWipe 补跑（只清理，不暂停不投屏不发事件）。
 */
class NvPrivacyRelockOrchestratorTest {

    private class FakeStore(private val log: MutableList<String>) : NvPinStore {
        var pendingWipe: List<Long> = emptyList()
        val setCalls = mutableListOf<List<Long>>()
        override suspend fun pinCipher(): String? = null
        override suspend fun pinCipherRaw(): String? = null
        override suspend fun setPinCipher(v: String?) = Unit
        override suspend fun failedCount() = 0
        override suspend fun setFailedCount(v: Int) = Unit
        override suspend fun lockUntil() = 0L to 0
        override suspend fun setLockUntil(elapsed: Long, boot: Int) = Unit
        override suspend fun pendingWipeIds(): List<Long> = pendingWipe
        override suspend fun setPendingWipeIds(v: List<Long>) {
            log += if (v.isEmpty()) "clearPending" else "persist:$v"
            setCalls += v
            pendingWipe = v
        }
        override val biometricEnabled = MutableStateFlow(false)
        override suspend fun setBiometricEnabled(v: Boolean) = Unit
    }

    private lateinit var store: FakeStore
    private lateinit var log: MutableList<String>
    private val rules = mutableMapOf<Long, Pair<Long, String>>()
    private var failPurgeFor: Set<Long> = emptySet()
    private var pauseCount = 0
    private var castCount = 0
    private var castHookThrows = false

    @Before
    fun setUp() {
        log = mutableListOf()
        store = FakeStore(log)
        rules.clear()
        failPurgeFor = emptySet()
        pauseCount = 0
        castCount = 0
        castHookThrows = false
        NvPrivacySession.resetForTest()
        NvPrivacyRelockOrchestrator.castStopHook = null
    }

    @After
    fun tearDown() {
        NvPrivacySession.resetForTest()
        NvPrivacyRelockOrchestrator.castStopHook = null
    }

    private fun deps() = Deps(
        prefs = store,
        ruleScopeOf = { id -> rules[id] },
        purgeOf = { ruleId, sourceId, dir, includeHistory ->
            log += "purge:$ruleId:$sourceId:$dir:$includeHistory"
            if (ruleId in failPurgeFor) throw IllegalStateException("purge boom")
        },
        pausePlayers = { pauseCount++; log += "pause" },
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    /** 在订阅已注册（UNDISPATCHED 起协程跑到首个挂起点）的前提下跑 block，收 relock 事件 */
    private fun runWithCollector(block: suspend CoroutineScope.(received: MutableList<List<Long>>) -> Unit) =
        runBlocking {
            val received = mutableListOf<List<Long>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                NvPrivacySession.relockEvents.collect { received += it.wipedRuleIds }
            }
            block(received)
            job.cancel()
        }

    @Test
    fun `重锁硬顺序——落盘先于清理清理后清标记暂停投屏最后发事件`() = runWithCollector { received ->
        rules[1L] = 10L to "/private/dir"
        rules[2L] = 10L to ""   // 整源规则
        NvPrivacySession.unlock(1L)
        NvPrivacySession.unlock(2L)
        NvPrivacyRelockOrchestrator.castStopHook = { castCount++; log += "cast" }

        NvPrivacyRelockOrchestrator.performRelock(deps())
        repeat(20) { yield() }

        assertEquals(
            listOf("persist:[1, 2]", "purge:1:10:/private/dir:false", "purge:2:10::false", "clearPending", "pause", "cast"),
            log,
        )
        assertEquals(listOf(listOf(1L, 2L), emptyList<Long>()), store.setCalls)
        assertEquals(listOf(listOf(1L, 2L)), received)
        assertEquals(1, pauseCount)
        assertEquals(1, castCount)
    }

    @Test
    fun `无解锁会话时重锁不暂停不投屏不发事件`() = runWithCollector { received ->
        NvPrivacyRelockOrchestrator.castStopHook = { castCount++ }

        NvPrivacyRelockOrchestrator.performRelock(deps())
        repeat(20) { yield() }

        assertTrue(log.isEmpty())
        assertEquals(0, pauseCount)
        assertEquals(0, castCount)
        assertTrue(received.isEmpty())
        assertTrue(store.setCalls.isEmpty())
    }

    @Test
    fun `单规则清理失败保留其待清理标记且不阻断后续规则与收尾`() = runWithCollector { received ->
        rules[1L] = 10L to "/a"
        rules[2L] = 10L to "/b"
        NvPrivacySession.unlock(1L)
        NvPrivacySession.unlock(2L)
        failPurgeFor = setOf(1L)

        NvPrivacyRelockOrchestrator.performRelock(deps())
        repeat(20) { yield() }

        // 规则 2 照常清理；pendingWipe 收敛为失败的 {1}，下次冷启动补跑
        assertTrue("purge:2:10:/b:false" in log)
        assertEquals(listOf(1L), store.pendingWipe)
        assertEquals(1, pauseCount)
        assertEquals(listOf(listOf(1L, 2L)), received)   // 事件报全集（会话已重锁）
    }

    @Test
    fun `投屏钩子异常不阻断事件发射`() = runWithCollector { received ->
        NvPrivacySession.unlock(5L)
        rules[5L] = 3L to "/c"
        NvPrivacyRelockOrchestrator.castStopHook = { castHookThrows = true; throw IllegalStateException("cast boom") }

        NvPrivacyRelockOrchestrator.performRelock(deps())
        repeat(20) { yield() }

        assertTrue(castHookThrows)
        assertEquals(listOf(listOf(5L)), received)
    }

    @Test
    fun `冷启动补跑逐规则清理并清标记且不暂停不投屏不发事件`() = runWithCollector { received ->
        store.pendingWipe = listOf(7L)
        rules[7L] = 4L to "/d"
        NvPrivacyRelockOrchestrator.castStopHook = { castCount++ }

        NvPrivacyRelockOrchestrator.catchUpPendingWipes(deps())
        repeat(20) { yield() }

        assertEquals(listOf("purge:7:4:/d:false", "clearPending"), log)
        assertEquals(emptyList<Long>(), store.pendingWipe)
        assertEquals(0, pauseCount)
        assertEquals(0, castCount)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `补跑遇规则已消失仍清标记不误留下次重试`() = runBlocking {
        store.pendingWipe = listOf(9L)   // rules 无 9 → 规则已删，范围随之消亡
        NvPrivacyRelockOrchestrator.catchUpPendingWipes(deps())
        assertTrue(log.none { it.startsWith("purge") })
        assertEquals(emptyList<Long>(), store.pendingWipe)
    }

    @Test
    fun `补跑清理失败保留待清理标记下次冷启动再试`() = runBlocking {
        store.pendingWipe = listOf(8L)
        rules[8L] = 2L to "/e"
        failPurgeFor = setOf(8L)
        NvPrivacyRelockOrchestrator.catchUpPendingWipes(deps())
        assertEquals(listOf(8L), store.pendingWipe)
    }
}
