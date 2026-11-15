package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T5 RMVB 顶栏收拢：两侧「现状顺序」常量的钉死测试（收拢零重排的技术保证）。
 * 常量必须与两侧代码现状逐键对齐——主播放器 NvPlayerTopBar 渲染序、
 * RMVB ControlsTop（收拢前）渲染序；顺序有意调整时须同步改这里与两侧常量。
 */
class NvTopBarOrderTest {
    @Test fun `主播放器现状顺序`() {
        assertEquals(
            listOf(NvTopAction.SUBTITLE, NvTopAction.AUDIO, NvTopAction.ASPECT, NvTopAction.ORIENTATION,
                NvTopAction.MIRROR, NvTopAction.ROTATE, NvTopAction.PIP, NvTopAction.QUALITY,
                NvTopAction.INFO, NvTopAction.CAST, NvTopAction.MORE),
            NvTopBarOrderMain)
    }
    @Test fun `RMVB 现状顺序(音轨在字幕前,无 PIP、QUALITY、CAST)`() {
        assertEquals(
            listOf(NvTopAction.ASPECT, NvTopAction.ORIENTATION, NvTopAction.MIRROR, NvTopAction.ROTATE,
                NvTopAction.AUDIO, NvTopAction.SUBTITLE, NvTopAction.INFO, NvTopAction.MORE),
            NvTopBarOrderRmvb)
        assertEquals(false, NvTopBarOrderRmvb.contains(NvTopAction.PIP))
        assertEquals(false, NvTopBarOrderRmvb.contains(NvTopAction.QUALITY))
        assertEquals(false, NvTopBarOrderRmvb.contains(NvTopAction.CAST))
    }
}
