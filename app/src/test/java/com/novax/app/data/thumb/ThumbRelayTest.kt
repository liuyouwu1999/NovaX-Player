package com.novax.app.data.thumb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SDK<29 缩略图「本地缓存文件中转」兜底的纯逻辑锁（2026-09-29 phase2）：
 * - 拷贝上限决策：min(文件大小, 16MB)，size≤0 钳 0；
 * - 中转临时文件命名：thumb_relay_<hash>[.<扩展名>]，扩展名辅助 MMR 识别容器；
 * - 常量契约锁（照 ThumbNetworkGateTest 模式）：16MB 上限被删/改小立即红。
 */
class ThumbRelayTest {

    @Test
    fun `拷贝上限_小文件取文件大小本身`() {
        assertEquals(0L, Thumbnailer.relayCopyLimit(0L))
        assertEquals(1L, Thumbnailer.relayCopyLimit(1L))
        assertEquals(1024L * 1024, Thumbnailer.relayCopyLimit(1024L * 1024))
    }

    @Test
    fun `拷贝上限_恰好16MB不截断_超过16MB截断`() {
        val max = 16L * 1024 * 1024
        assertEquals(max, Thumbnailer.relayCopyLimit(max))
        assertEquals(max, Thumbnailer.relayCopyLimit(max + 1))
        assertEquals(max, Thumbnailer.relayCopyLimit(Long.MAX_VALUE))
    }

    @Test
    fun `拷贝上限_非法size钳到0_空文件走MMR失败路径`() {
        assertEquals(0L, Thumbnailer.relayCopyLimit(-1L))
        assertEquals(0L, Thumbnailer.relayCopyLimit(Long.MIN_VALUE))
    }

    @Test
    fun `临时文件名_带扩展名`() {
        // relayFileFor 侧负责扩展名小写化，本纯函数只锁「hash.扩展名」拼接规则本身
        assertEquals("thumb_relay_abc123.mp4", Thumbnailer.relayFileName("abc123", "mp4"))
        assertEquals("thumb_relay_abc123.mkv", Thumbnailer.relayFileName("abc123", "mkv"))
    }

    @Test
    fun `临时文件名_无扩展名裸hash`() {
        assertEquals("thumb_relay_abc123", Thumbnailer.relayFileName("abc123", ""))
    }

    /** 常量契约锁：中转上限 16MB 与 256KB 块口径任何一处被动即红 */
    @Test
    fun `中转契约锁_16MB上限与256KB块`() {
        assertEquals(16L * 1024 * 1024, Thumbnailer.RELAY_MAX_COPY_BYTES)
        assertEquals(256 * 1024, Thumbnailer.THUMB_STREAM_BLOCK_BYTES)
    }
}
