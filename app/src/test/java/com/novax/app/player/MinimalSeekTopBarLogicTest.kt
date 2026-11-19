// app/src/test/java/com/novax/app/player/MinimalSeekTopBarLogicTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rev5 极简顶栏=进度条（spec 2026-11-15 §3.2 rev5 / §9，T1）纯函数锚：
 * - [minimalFillWidthPx]：顶栏已播填充宽 = 比例×栏宽 线性映射，coerce 0~栏宽
 *   （2026-11-19 用户拍板弃 nPlayer「lead 最小填充宽 max 规则」——真机两 bug：0% 时✕块
 *   右侧残留色块左右不对称、低进度拖动前缘钉死无反馈拖不回 00:00。改版后 0%=无填充、
 *   ✕块右缘即前缘、100%=与右⋯块贴合、全程跟手）。
 * - [minimalDrawsFill]：durationMs>0 才画填充；≤0（直播/无时长）整栏只画半透明深底、
 *   无最小宽蓝段（spec §3.2「无进度可言」口径，plan 审核 P2-2 提为可断言纯函数）。
 * - [quickMenuRows]：⋯精简菜单行键集（spec §5.3 键集思想，quickControlsFor 同型）——
 *   RMVB 剔 PIP/CAST；主侧 hasCast=false 剔 CAST（playUrl 为 null，与经典顶栏藏投屏键
 *   同口径）。两机制独立（plan 复审已核无冲突，RMVB 接线恒 hasCast=false 双重剔除幂等）。
 */
class MinimalSeekTopBarLogicTest {
    // —— minimalFillWidthPx ——

    @Test fun `progress 为零时填充宽为零叉块右缘即前缘`() =
        assertEquals(0f, minimalFillWidthPx(progress = 0f, barWidthPx = 400f), 0.001f)

    @Test fun `progress 为一时填充铺满整栏`() =
        assertEquals(400f, minimalFillWidthPx(progress = 1f, barWidthPx = 400f), 0.001f)

    @Test fun `progress 超一时 coerce 到栏宽`() =   // 1.2×400=480 → coerce 400（T1 复审 P2-1 上界锚）
        assertEquals(400f, minimalFillWidthPx(progress = 1.2f, barWidthPx = 400f), 0.001f)

    @Test fun `中间进度按比例宽走`() =   // 0.2×400 = 80（线性，无下限）
        assertEquals(80f, minimalFillWidthPx(progress = 0.2f, barWidthPx = 400f), 0.001f)

    @Test fun `中间进度高比例按比例宽走`() =   // 0.5×400 = 200
        assertEquals(200f, minimalFillWidthPx(progress = 0.5f, barWidthPx = 400f), 0.001f)

    @Test fun `负 progress coerce 到零`() =
        assertEquals(0f, minimalFillWidthPx(progress = -0.5f, barWidthPx = 400f), 0.001f)

    // —— minimalDrawsFill ——

    @Test fun `正常时长画填充`() = assertTrue(minimalDrawsFill(durationMs = 45 * 60 * 1000L))

    @Test fun `时长为一毫秒也画填充`() = assertTrue(minimalDrawsFill(durationMs = 1L))

    @Test fun `零时长不画填充`() = assertFalse(minimalDrawsFill(durationMs = 0L))

    @Test fun `负时长不画填充`() = assertFalse(minimalDrawsFill(durationMs = -1L))

    // —— quickMenuRows ——

    @Test fun `RMVB 行集恰为锁定上下集退出`() = assertEquals(
        listOf(QuickMenuRow.LOCK, QuickMenuRow.PREV, QuickMenuRow.NEXT, QuickMenuRow.EXIT),
        quickMenuRows(isRmvb = true, hasCast = false),
    )

    @Test fun `RMVB 行集不受 hasCast 值影响`() = assertEquals(
        quickMenuRows(isRmvb = true, hasCast = false),
        quickMenuRows(isRmvb = true, hasCast = true),
    )

    @Test fun `主侧无投屏源剔除投屏行`() = assertEquals(
        listOf(QuickMenuRow.LOCK, QuickMenuRow.PREV, QuickMenuRow.NEXT, QuickMenuRow.PIP, QuickMenuRow.EXIT),
        quickMenuRows(isRmvb = false, hasCast = false),
    )

    @Test fun `主侧有投屏源时全六行`() = assertEquals(
        listOf(
            QuickMenuRow.LOCK, QuickMenuRow.PREV, QuickMenuRow.NEXT,
            QuickMenuRow.PIP, QuickMenuRow.CAST, QuickMenuRow.EXIT,
        ),
        quickMenuRows(isRmvb = false, hasCast = true),
    )
}
