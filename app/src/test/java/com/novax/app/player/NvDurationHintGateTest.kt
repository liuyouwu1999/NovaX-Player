package com.novax.app.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NvDurationHintGateTest {

    @Test
    fun `clear rejects late refresh`() = runTest {
        val gate = NvDurationHintGate(UnconfinedTestDispatcher(testScheduler))
        val captured = gate.capture()
        gate.clear()
        var applied = 0L
        gate.refresh(captured, { 42_000L }, { applied = it })
        assertEquals(0L, applied)
    }

    @Test
    fun `current refresh applies`() = runTest {
        val gate = NvDurationHintGate(UnconfinedTestDispatcher(testScheduler))
        val captured = gate.capture()
        var applied = 0L
        gate.refresh(captured, { 42_000L }, { applied = it })
        assertEquals(42_000L, applied)
    }

    @Test
    fun `older generation rejected after new capture`() = runTest {
        val main = UnconfinedTestDispatcher(testScheduler)
        val gate = NvDurationHintGate(main)
        val stale = gate.capture()
        gate.clear()
        val fresh = gate.capture()
        var appliedStale = 0L
        var appliedFresh = 0L
        gate.refresh(fresh, { 7_000L }, { appliedFresh = it })
        gate.refresh(stale, { 9_000L }, { appliedStale = it })
        assertEquals(7_000L, appliedFresh)
        assertEquals(0L, appliedStale)
    }

    @Test
    fun `zero duration and query exception do not apply nor poison`() = runTest {
        val main = UnconfinedTestDispatcher(testScheduler)
        val gate = NvDurationHintGate(main)
        var applied = 0L
        gate.refresh(gate.capture(), { 0L }, { applied = it })
        assertEquals(0L, applied)
        gate.refresh(gate.capture(), { error("db busy") }, { applied = it })
        assertEquals(0L, applied)
        // gate 状态未被污染：新一代查询正常回填
        val captured = gate.capture()
        gate.refresh(captured, { 5_000L }, { applied = it })
        assertEquals(5_000L, applied)
    }

    @Test
    fun `clear during pending query rejects apply`() = runTest {
        val main = UnconfinedTestDispatcher(testScheduler)
        val gate = NvDurationHintGate(main)
        val captured = gate.capture()
        val release = CompletableDeferred<Unit>()
        var applied = 0L
        val job = launch { gate.refresh(captured, { release.await(); 42_000L }, { applied = it }) }
        testScheduler.runCurrent()          // query 已挂起在 release.await
        gate.clear()                        // 挂起期间内容切换（applySwitch 清零）
        release.complete(Unit)
        testScheduler.runCurrent()
        job.join()
        assertEquals(0L, applied)
    }
}
