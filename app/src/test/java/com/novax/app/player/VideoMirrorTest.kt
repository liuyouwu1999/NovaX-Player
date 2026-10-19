package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面镜像纯函数单测(2026-10-11 spec §5.1,2026-10-11 画面镜像设计)。
 *
 * 镜像 = 水平负缩放,与双指缩放共用 View 级 scaleX 属性一次写入;
 * 恒等红线:mirrored=false 时与功能上线前逐位一致(零行为差异)。
 */
class VideoMirrorTest {

    @Test
    fun `关闭时逐位恒等(零行为差异红线)`() {
        assertEquals(1.0f, mirroredScaleX(false, 1.0f), 0f)
        assertEquals(2.5f, mirroredScaleX(false, 2.5f), 0f)
        assertEquals(0.01f, mirroredScaleX(false, 0.01f), 0f)
    }

    @Test
    fun `开启时水平取负`() {
        assertEquals(-1.0f, mirroredScaleX(true, 1.0f), 0f)
        assertEquals(-2.5f, mirroredScaleX(true, 2.5f), 0f)
    }

    @Test
    fun `与clampScale产物组合仅符号翻转_幅值不变`() {
        val clamped = PlayerZoomPolicy.clampScale(4f)
        assertEquals(-clamped, mirroredScaleX(true, clamped), 0f)
        assertEquals(clamped, mirroredScaleX(false, clamped), 0f)
    }
}
