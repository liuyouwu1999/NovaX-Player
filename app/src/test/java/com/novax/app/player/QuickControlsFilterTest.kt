package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「更多」面板「播放控制」组键集过滤（spec 2026-11-15 §5.5 T8 + 2026-11-19 用户拍板方案 A
 * 「按当前样式智能去重」）：当前样式外层已有直接按钮的入口从组中移除（与外面按钮重复是用户
 * 点名痛点），未覆盖入口保留兜底；RMVB 按 §5.3 键集先剔 PIP/清晰度/投屏（先例保持，两过滤
 * 叠加）；QUALITY 仅 Jellyfin 有质量档时可见。组空（经典档全覆盖）由 [QuickControlsSection]
 * 整组隐藏——键集侧以恒空列表表达。
 *
 * 精确列表断言=成员+顺序双锚（顺序=QuickControlId 枚举序，即面板行序）。
 * 边界口径（复审 P1-1）：面板 narrow 名义值=[nvPanelNarrowOf]（屏宽<512）——底栏三处
 * BoxWithConstraints 在水平内移 16dp×2 后判 maxWidth<480dp，实测窄幅阈值=窗口宽<512dp，
 * 名义值取 512 对齐实测（取 480 会在 [480,512) 档底栏窄幅而面板宽幅剔行=断档）。
 */
class QuickControlsFilterTest {
    @Test fun `面板窄幅名义值阈值对齐底栏实测 512`() {
        org.junit.Assert.assertTrue(nvPanelNarrowOf(479))
        org.junit.Assert.assertTrue(nvPanelNarrowOf(480)) // 旧口径 480 恰漏判：底栏 448<480 实为窄幅
        org.junit.Assert.assertTrue(nvPanelNarrowOf(511))
        org.junit.Assert.assertFalse(nvPanelNarrowOf(512))
        org.junit.Assert.assertFalse(nvPanelNarrowOf(600))
    }

    @Test fun `经典档顶栏底栏全覆盖组恒空`() {
        assertEquals(emptyList<QuickControlId>(), quickControlsFor(isRmvb = false, hasQuality = true, style = NvControlStyle.CLASSIC, narrow = false))
        assertEquals(emptyList<QuickControlId>(), quickControlsFor(isRmvb = true, hasQuality = true, style = NvControlStyle.CLASSIC, narrow = true))
    }

    @Test fun `极简宽幅保留六项精确列表`() =
        assertEquals(
            listOf(
                QuickControlId.SPEED, QuickControlId.AUDIO, QuickControlId.ORIENTATION,
                QuickControlId.MIRROR, QuickControlId.QUALITY, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = false, hasQuality = true, style = NvControlStyle.MINIMAL, narrow = false),
        )

    @Test fun `极简窄幅保留七项含比例`() =
        assertEquals(
            listOf(
                QuickControlId.SPEED, QuickControlId.AUDIO, QuickControlId.ASPECT,
                QuickControlId.ORIENTATION, QuickControlId.MIRROR, QuickControlId.QUALITY, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = false, hasQuality = true, style = NvControlStyle.MINIMAL, narrow = true),
        )

    @Test fun `极简 RMVB 两过滤叠加再减清晰度`() =
        assertEquals(
            listOf(
                QuickControlId.SPEED, QuickControlId.AUDIO, QuickControlId.ORIENTATION,
                QuickControlId.MIRROR, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = true, hasQuality = true, style = NvControlStyle.MINIMAL, narrow = false),
        )

    @Test fun `极简 RMVB 窄幅六项含比例`() =
        assertEquals(
            listOf(
                QuickControlId.SPEED, QuickControlId.AUDIO, QuickControlId.ASPECT,
                QuickControlId.ORIENTATION, QuickControlId.MIRROR, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = true, hasQuality = false, style = NvControlStyle.MINIMAL, narrow = true),
        )

    @Test fun `长视频风保留八项精确列表`() =
        assertEquals(
            listOf(
                QuickControlId.LOCK, QuickControlId.ASPECT, QuickControlId.ORIENTATION,
                QuickControlId.MIRROR, QuickControlId.ROTATE, QuickControlId.PIP,
                QuickControlId.QUALITY, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = false, hasQuality = true, style = NvControlStyle.STREAMING, narrow = false),
        )

    @Test fun `长视频风窄幅保留九项含倍速`() =
        assertEquals(
            listOf(
                QuickControlId.LOCK, QuickControlId.SPEED, QuickControlId.ASPECT,
                QuickControlId.ORIENTATION, QuickControlId.MIRROR, QuickControlId.ROTATE,
                QuickControlId.PIP, QuickControlId.QUALITY, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = false, hasQuality = true, style = NvControlStyle.STREAMING, narrow = true),
        )

    @Test fun `长视频风 RMVB 减投屏清晰度`() =
        assertEquals(
            listOf(
                QuickControlId.LOCK, QuickControlId.ASPECT, QuickControlId.ORIENTATION,
                QuickControlId.MIRROR, QuickControlId.ROTATE, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = true, hasQuality = true, style = NvControlStyle.STREAMING, narrow = false),
        )

    @Test fun `无质量档时 QUALITY 各样式隐藏`() {
        assertEquals(
            listOf(
                QuickControlId.SPEED, QuickControlId.AUDIO, QuickControlId.ORIENTATION,
                QuickControlId.MIRROR, QuickControlId.INFO,
            ),
            quickControlsFor(isRmvb = false, hasQuality = false, style = NvControlStyle.MINIMAL, narrow = false),
        )
        assertFalse(
            quickControlsFor(isRmvb = false, hasQuality = false, style = NvControlStyle.STREAMING, narrow = true)
                .contains(QuickControlId.QUALITY),
        )
    }
}
