package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络恢复自动重连状态机单测：预算、门控、重置、去重（2026-09-17）。
 * 真机断网验证之外，这里覆盖全部状态转移分支。
 */
class NvAutoReconnectTest {

    private var retried = 0
    private val autoEvents = mutableListOf<Int>()

    private fun newController(maxAttempts: Int = 3) = NvAutoReconnect(
        maxAttempts = maxAttempts,
        onRetry = { retried++ },
        onAutoRetry = { autoEvents.add(it) },
    )

    @Test
    fun `network error then network available triggers one retry`() {
        val c = newController()
        c.onFatalError(isNetwork = true)
        assertTrue(c.armed)
        c.onNetworkAvailable()
        assertEquals(1, retried)
        assertEquals(1, autoEvents.single())
        assertFalse(c.armed)   // 等待重试结果期间不再武装
    }

    @Test
    fun `non-network error never arms`() {
        val c = newController()
        c.onFatalError(isNetwork = false)
        assertFalse(c.armed)
        c.onNetworkAvailable()
        assertEquals(0, retried)
    }

    @Test
    fun `budget of three then stops retrying`() {
        val c = newController(maxAttempts = 3)
        repeat(3) {
            c.onFatalError(isNetwork = true)
            c.onNetworkAvailable()
        }
        assertEquals(3, retried)
        // 第 4 次网络恢复：预算耗尽，不再自动重试（用户仍可手动）
        c.onFatalError(isNetwork = true)
        assertTrue(c.armed)
        c.onNetworkAvailable()
        assertEquals(3, retried)
    }

    @Test
    fun `playback started resets budget`() {
        val c = newController()
        repeat(3) {
            c.onFatalError(isNetwork = true)
            c.onNetworkAvailable()
        }
        assertEquals(3, retried)
        c.onPlaybackStarted()          // 播放成功 → 预算归零
        assertEquals(0, c.attempts)
        c.onFatalError(isNetwork = true)
        c.onNetworkAvailable()         // 新一轮断网仍有全额额度
        assertEquals(4, retried)
    }

    @Test
    fun `duplicate network available events do not double retry`() {
        val c = newController()
        c.onFatalError(isNetwork = true)
        c.onNetworkAvailable()
        c.onNetworkAvailable()         // WiFi+蜂窝 同时恢复的重复事件
        assertEquals(1, retried)
    }

    @Test
    fun `manual retry disarms auto reconnect`() {
        val c = newController()
        c.onFatalError(isNetwork = true)
        c.onManualRetry()              // 用户先点了按钮
        c.onNetworkAvailable()         // 随后网络恢复：不再叠加自动重试
        assertEquals(0, retried)
        assertFalse(c.armed)
    }

    @Test
    fun `network available without error does nothing`() {
        val c = newController()
        c.onNetworkAvailable()
        assertEquals(0, retried)
    }
}
