package com.novax.app.data.thumb

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** thumbState CAS 认领 / 进程内防重复（NONE→RUNNING 唯一写入点的前置闸门，C12 §三.5） */
class ThumbClaimGateTest {

    @Test
    fun concurrentClaims_exactlyOneWinner() = runBlocking {
        // 模拟 DB CAS：thumbState 0=NONE → 1=RUNNING，compareAndSet 语义
        val dbState = AtomicInteger(0)
        val gate = ThumbClaimGate(claim = { _ ->
            if (dbState.compareAndSet(0, 1)) 1 else 0
        })
        val results = (1..64).map {
            async { gate.tryClaim(100L) }
        }.awaitAll()

        assertEquals(1, results.count { it })   // 同一 id 并发触发仅成功一次
        assertEquals(1, dbState.get())
        assertEquals(1, gate.inFlightCount())
    }

    @Test
    fun inFlightDedup_reReleaseAllowsReclaim() = runBlocking {
        val gate = ThumbClaimGate(claim = { 1 })   // DB 恒成功（模拟无其他竞争者）
        assertTrue(gate.tryClaim(5L))
        assertFalse(gate.tryClaim(5L))    // 在途去重：不重复入队
        assertEquals(1, gate.inFlightCount())
        gate.release(5L)                  // 抽帧收尾释放
        assertTrue(gate.tryClaim(5L))     // 「重新抽帧」可再次认领
    }

    @Test
    fun dbCasLoss_cleansInFlight() = runBlocking {
        val gate = ThumbClaimGate(claim = { 0 })   // DB 侧他人已认领（受影响行数=0）
        assertFalse(gate.tryClaim(7L))
        assertEquals(0, gate.inFlightCount())
        // 落败不占用去重集：DB 状态恢复后可重试
    }

    @Test
    fun claimThrow_propagates_andCleansInFlight() = runBlocking {
        val gate = ThumbClaimGate(claim = { throw IllegalStateException("db down") })
        try {
            gate.tryClaim(9L)
            throw AssertionError("应抛出")
        } catch (expected: IllegalStateException) {
            // 预期
        }
        assertEquals(0, gate.inFlightCount())   // 异常路径也清去重集，不卡死后续认领
    }

    @Test
    fun cancellationDuringClaim_cleansInFlight() = runBlocking {
        val gate = ThumbClaimGate(claim = { throw CancellationException("cancelled") })
        try {
            gate.tryClaim(3L)
        } catch (expected: CancellationException) {
        } finally {
            gate.release(3L)   // 生产路径 extractOne 的 finally 语义
        }
        assertEquals(0, gate.inFlightCount())
    }
}
