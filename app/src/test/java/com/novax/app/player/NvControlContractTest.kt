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
    // rev5（2026-11-18 顶栏=进度条）：契约扩三字段——quickMenuOpen（⋯菜单打开态，编排层持有，
    // 自动隐藏计时挂起的唯一显式豁免 spec §5.1）/ onToggleQuickMenu（⋯块点击切换）/
    // hasCast（⋯菜单投屏行门控，主侧=playUrl 非 null、RMVB 恒 false；hasQuality 同型先例）。
    // 行为接线在 T3/T4 两侧编排层，JVM 只锚默认值与 no-op 安全（onSetSpeed 用例同姿势）。
    @Test fun `rev5 三新字段默认值安全`() {
        val c = NvControlContract()
        assertEquals(false, c.quickMenuOpen)
        assertEquals(true, c.hasCast)
        c.onToggleQuickMenu()       // 默认 no-op 不崩
        assertNotNull(c.onToggleQuickMenu) // 字段存在性锚定
    }
}
