// app/src/test/java/com/novax/app/player/NvControlContractTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NvControlContractTest {
    @Test fun `默认契约状态值安全`() {
        val c = NvControlContract()
        assertEquals(0L, c.positionMs); assertEquals(0L, c.bufferedMs); assertEquals(0L, c.durationMs)
        assertEquals(false, c.isPlaying); assertEquals(1f, c.speed, 0.001f)
        assertEquals(0, c.lockMode); assertEquals(15, c.skipSec)
        assertEquals(false, c.hasPrev); assertEquals(false, c.hasNext)
        assertEquals("", c.title)
    }
    @Test fun `样式无关的全量回调可空持有`() {
        val c = NvControlContract()
        c.onTogglePlay()          // 默认 no-op 不崩
        c.onMore()
        assertNotNull(c.onSeek)
    }
    // R1（2026-11-15 极简样式）：契约扩 onSetSpeed——极简档倍速三件套经 nvSpeedStep 步进后
    // 直设倍速提交。提交语义四/五要素（UI 态镜像/清长按双态/引擎设速/≥3x 静音/跨会话记忆）
    // 全在播放器宿主侧 lambda 里，JVM 不可测——本用例锚定默认 no-op 安全 + 字段存在性，
    // 行为等价靠提取重构（主侧 applyPickedSpeed / RMVB 侧共享提交函数）+ 独立复审 + R6 冒烟。
    @Test fun `onSetSpeed 默认 no-op 不崩`() {
        val c = NvControlContract()
        c.onSetSpeed(1.5f)          // 默认 no-op 不崩
        assertNotNull(c.onSetSpeed) // 字段存在性锚定
    }
}
