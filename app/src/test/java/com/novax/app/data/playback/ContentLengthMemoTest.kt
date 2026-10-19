package com.novax.app.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * ContentLengthMemo 语义（2026-10-03 失败冷却重试加固）：
 * - 成功 → 永久缓存，不再打查询；
 * - 失败 → 冷却期内返回 -1 且不重试；冷却结束 → 允许重试，重试成功转永久缓存；
 * - 持续失败 → 每次冷却窗口至多一次真实查询。
 */
class ContentLengthMemoTest {

    /** 手动时钟：纳秒 */
    private val now = AtomicLong(0L)

    private fun memo(query: () -> Long, cooldownNanos: Long = 1_000L) =
        ContentLengthMemo(query, clock = { now.get() }, cooldownNanos = cooldownNanos)

    @Test
    fun success_cachesForever() {
        var calls = 0
        val m = memo(query = { calls++; 123L })
        assertEquals(123L, m.get())
        assertEquals(123L, m.get())
        assertEquals(123L, m.get())
        assertEquals("成功后不再打查询", 1, calls)
    }

    @Test
    fun failure_coolsDown_thenRetries() {
        var calls = 0
        var result = -1L
        val m = memo(
            query = { calls++; result },
            cooldownNanos = 100L,
        )
        assertEquals(-1L, m.get())
        assertEquals("冷却期内不重试", 1, calls)
        assertEquals(-1L, m.get())
        assertEquals(1, calls)

        now.set(150L) // 冷却结束
        result = 88L
        assertEquals(88L, m.get())
        assertEquals(2, calls)
        assertEquals("重试成功后永久缓存", 88L, m.get())
        assertEquals(2, calls)
    }

    @Test
    fun persistentFailure_oneQueryPerCooldownWindow() {
        var calls = 0
        val m = memo(query = { calls++; -1L }, cooldownNanos = 100L)
        m.get(); m.get(); m.get()
        assertEquals(1, calls)
        now.set(100L)
        m.get()
        assertEquals(2, calls)
        now.set(150L) // 同一窗口内
        m.get()
        assertEquals("冷却期内仍不打查询", 2, calls)
        now.set(250L)
        m.get()
        assertEquals(3, calls)
    }

    @Test
    fun queryThrows_treatedAsFailure() {
        val m = memo(query = { error("SMB down") }, cooldownNanos = 50L)
        assertEquals(-1L, m.get())
        assertEquals(-1L, m.get())
        now.set(51L)
        assertEquals(-1L, m.get())
    }

    @Test
    fun zeroOrNegativeLength_treatedAsUnknown() {
        var calls = 0
        val m = memo(query = { calls++; 0L }, cooldownNanos = 100L)
        assertEquals("0 视为未知：返回 -1 并进入冷却", -1L, m.get())
        assertEquals(1, calls)
        now.set(101L)
        assertEquals(-1L, m.get())
        assertEquals(2, calls)
    }
}
