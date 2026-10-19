package com.novax.app.data.thumb

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 后台渐进抽帧限速器（本任务新增：≤1 个/秒防 NAS 过载；时钟与等待均可注入，零真实睡眠） */
class RateLimiterTest {

    @Test
    fun firstAcquireNeverWaits() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val limiter = RateLimiter(1_000, nowMs = { clock }, waitFn = { clock += it; waits.add(it) })
        limiter.acquire()
        assertEquals(emptyList<Long>(), waits)
        assertEquals(0L, clock)
    }

    @Test
    fun consecutiveAcquiresSpacedByMinInterval() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val limiter = RateLimiter(1_000, nowMs = { clock }, waitFn = { clock += it; waits.add(it) })
        limiter.acquire()   // t=0：首个不等待
        limiter.acquire()   // 需等到 t=1000
        limiter.acquire()   // 需等到 t=2000
        assertEquals(listOf(1_000L, 1_000L), waits)
        assertEquals(2_000L, clock)
    }

    @Test
    fun elapsedRealTimeBetweenAcquiresReducesWait() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val limiter = RateLimiter(1_000, nowMs = { clock }, waitFn = { clock += it; waits.add(it) })
        limiter.acquire()   // t=0
        clock += 600        // 抽帧本身耗时 600ms（真实时间流逝）
        limiter.acquire()   // 只需再等 400ms
        assertEquals(listOf(400L), waits)
        assertEquals(1_000L, clock)
    }

    @Test
    fun intervalZeroNeverWaits() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val limiter = RateLimiter(0, nowMs = { clock }, waitFn = { clock += it; waits.add(it) })
        repeat(5) { limiter.acquire() }
        assertEquals(emptyList<Long>(), waits)
        assertEquals(0L, clock)
    }

    @Test
    fun concurrentAcquiresAreSerialized() = runBlocking {
        var clock = 0L
        var acquisitions = 0
        val limiter = RateLimiter(500, nowMs = { clock }, waitFn = { clock += it })
        (1..4).map {
            async {
                limiter.acquire()
                acquisitions++
            }
        }.forEach { it.await() }
        assertEquals(4, acquisitions)
        // 串行限速：4 次共等 (4-1)*500=1500ms 虚拟时间
        assertEquals(1_500L, clock)
    }
}
