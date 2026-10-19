package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双指缩放纯逻辑单测（2026-09-15 用户需求，B 站同款：捏合缩放 + 还原画面按钮）。
 *
 * 几何口径与真机对齐：2400×1080 屏（21:9 全面屏横屏），16:9 视频在 FIT 模式下
 * 内容框 1920×1080（左右黑边各 240px）——这是最容易露黑边的场景，也是夹紧逻辑的主战场。
 */
class PlayerZoomPolicyTest {

    private val SCREEN_W = 2400f
    private val SCREEN_H = 1080f
    private val FIT_16_9_W = 1920f   // 1080 * 16/9
    private val FIT_16_9_H = 1080f

    // ---------- scaleFromPinch：捏合距离比 ----------

    @Test
    fun `两指距离翻倍 = 放大一倍`() {
        assertEquals(2.0f, PlayerZoomPolicy.scaleFromPinch(1f, 300f, 600f), 0.001f)
    }

    @Test
    fun `两指距离减半 = 缩小一半（缩到下限 1 倍为止）`() {
        // 设计不变量：倍率下限 1.0——缩小手势不允许把画面缩得比原始比例还小，
        // 从 2 倍捏合减半 → 1.0；已在 1.0 时继续缩小 → 仍是 1.0
        assertEquals(1.0f, PlayerZoomPolicy.scaleFromPinch(2f, 600f, 300f), 0.001f)
        assertEquals(1.0f, PlayerZoomPolicy.scaleFromPinch(1f, 600f, 300f), 0.001f)
    }

    @Test
    fun `在已有缩放基础上继续捏合（起点缩放乘距离比）`() {
        assertEquals(3.0f, PlayerZoomPolicy.scaleFromPinch(2f, 400f, 600f), 0.001f)
        assertEquals(1.0f, PlayerZoomPolicy.scaleFromPinch(2f, 400f, 200f), 0.001f)
    }

    @Test
    fun `上限 4 倍夹紧（B 站量级）`() {
        assertEquals(PlayerZoomPolicy.MAX_SCALE, PlayerZoomPolicy.scaleFromPinch(3.9f, 100f, 10000f), 0.001f)
        assertEquals(4.0f, PlayerZoomPolicy.clampScale(99f), 0.001f)
    }

    @Test
    fun `下限 1 倍夹紧（不会缩得比原画面还小）`() {
        assertEquals(PlayerZoomPolicy.MIN_SCALE, PlayerZoomPolicy.scaleFromPinch(1f, 500f, 50f), 0.001f)
        assertEquals(1.0f, PlayerZoomPolicy.clampScale(0.2f), 0.001f)
    }

    @Test
    fun `异常输入不崩溃：起始距离为 0 与 NaN`() {
        // 两指按在同一点（dist=0）：保持起点缩放，不得除零出 NaN
        assertEquals(1.0f, PlayerZoomPolicy.scaleFromPinch(1f, 0f, 600f), 0.001f)
        assertEquals(PlayerZoomPolicy.MIN_SCALE, PlayerZoomPolicy.clampScale(Float.NaN), 0.001f)
        assertEquals(PlayerZoomPolicy.MIN_SCALE, PlayerZoomPolicy.scaleFromPinch(1f, 100f, Float.NaN), 0.001f)
    }

    // ---------- clampPan：平移夹紧（画面边缘不得拖进屏幕） ----------

    @Test
    fun `未放大不允许平移（FIT 内容小于屏幕）`() {
        val z = PlayerZoomPolicy.clampPan(ZoomState(1f, 500f, -500f), FIT_16_9_W, FIT_16_9_H, SCREEN_W, SCREEN_H)
        assertEquals(0f, z.panX, 0.001f)
        assertEquals(0f, z.panY, 0.001f)
    }

    @Test
    fun `低倍率盖不住屏幕的轴锁定居中`() {
        // FIT 16:9 内容 1920 宽，1.1 倍 = 2112 < 2400：水平盖不住 → 水平平移锁定 0；
        // 垂直 1080×1.1 = 1188 > 1080：垂直可平移 ±54
        val z = PlayerZoomPolicy.clampPan(ZoomState(1.1f, 300f, 300f), FIT_16_9_W, FIT_16_9_H, SCREEN_W, SCREEN_H)
        assertEquals(0f, z.panX, 0.001f)
        assertEquals(54f, z.panY, 0.001f)
    }

    @Test
    fun `2 倍时的平移上界 = 内容与屏幕边缘对齐处`() {
        // 2 倍：内容 3840×2160，上界 x=(3840-2400)/2=720、y=(2160-1080)/2=540
        val z = PlayerZoomPolicy.clampPan(ZoomState(2f, 9999f, -9999f), FIT_16_9_W, FIT_16_9_H, SCREEN_W, SCREEN_H)
        assertEquals(720f, z.panX, 0.001f)
        assertEquals(-540f, z.panY, 0.001f)
    }

    @Test
    fun `上界内平移不被扰动`() {
        val z = PlayerZoomPolicy.clampPan(ZoomState(2f, 100f, -200f), FIT_16_9_W, FIT_16_9_H, SCREEN_W, SCREEN_H)
        assertEquals(100f, z.panX, 0.001f)
        assertEquals(-200f, z.panY, 0.001f)
    }

    @Test
    fun `FILL 内容等于屏幕：1 倍锁 0，4 倍上界为屏幕的 1点5 倍`() {
        val zero = PlayerZoomPolicy.clampPan(ZoomState(1f, 800f, 800f), SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H)
        assertEquals(0f, zero.panX, 0.001f)
        assertEquals(0f, zero.panY, 0.001f)
        val max = PlayerZoomPolicy.clampPan(ZoomState(4f, 99999f, -99999f), SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H)
        assertEquals(SCREEN_W * 1.5f, max.panX, 0.001f)
        assertEquals(-SCREEN_H * 1.5f, max.panY, 0.001f)   // 负方向平移夹到负上界，符号保留
    }

    // ---------- isZoomed：还原按钮显隐 ----------

    @Test
    fun `恒等态不算缩放（还原按钮不显示）`() {
        assertFalse(PlayerZoomPolicy.isZoomed(ZoomState()))
        assertFalse(PlayerZoomPolicy.isZoomed(ZoomState(1f, 0.5f, -0.5f)))
        assertFalse(PlayerZoomPolicy.isZoomed(ZoomState(1.005f)))   // 浮点抖动容差内
    }

    @Test
    fun `放大或平移即算缩放（还原按钮显示）`() {
        assertTrue(PlayerZoomPolicy.isZoomed(ZoomState(1.5f)))
        assertTrue(PlayerZoomPolicy.isZoomed(ZoomState(4f, 0f, 0f)))
        assertTrue(PlayerZoomPolicy.isZoomed(ZoomState(1f, 50f, 0f)))
        assertTrue(PlayerZoomPolicy.isZoomed(ZoomState(1f, 0f, -50f)))
    }

    // ---------- contentDims：内容盒几何（与 fitSize/coverSize/coverTransform 同口径） ----------

    @Test
    fun `FIT 16比9 视频在 21比9 屏上留左右黑边`() {
        val (w, h) = PlayerZoomPolicy.contentDims(AspectMode.FIT, 16f / 9f, SCREEN_W, SCREEN_H)
        assertEquals(FIT_16_9_W, w, 0.01f)
        assertEquals(FIT_16_9_H, h, 0.01f)
    }

    @Test
    fun `FIT 竖版视频左右黑边更宽`() {
        val (w, h) = PlayerZoomPolicy.contentDims(AspectMode.FIT, 9f / 16f, SCREEN_W, SCREEN_H)
        assertEquals(607.5f, w, 0.01f)
        assertEquals(1080f, h, 0.01f)
    }

    @Test
    fun `FILL 内容等于屏幕`() {
        val (w, h) = PlayerZoomPolicy.contentDims(AspectMode.FILL, 4f / 3f, SCREEN_W, SCREEN_H)
        assertEquals(SCREEN_W, w, 0.01f)
        assertEquals(SCREEN_H, h, 0.01f)
    }

    @Test
    fun `cover 模式可见内容至少盖满屏幕（16比9 框 4比3 片源 = 裁上下）`() {
        // coverSize(16:9)：(2400, 1350)；videoAspect 1.333 < target 1.778 → 高度再放大 1.778/1.333
        val (w, h) = PlayerZoomPolicy.contentDims(AspectMode.R16_9, 4f / 3f, SCREEN_W, SCREEN_H)
        assertEquals(2400f, w, 0.01f)
        assertEquals(1800f, h, 0.01f)
    }

    @Test
    fun `cover 模式可见内容至少盖满屏幕（4比3 框 16比9 片源 = 裁左右）`() {
        // coverSize(4:3)：(2400, 1800)；videoAspect 1.778 > target 1.333 → 宽度再放大 1.778/1.333
        val (w, h) = PlayerZoomPolicy.contentDims(AspectMode.R4_3, 16f / 9f, SCREEN_W, SCREEN_H)
        assertEquals(3200f, w, 0.01f)
        assertEquals(1800f, h, 0.01f)
    }

    @Test
    fun `内容盒未知尺寸与异常比例不崩溃`() {
        val (w0, h0) = PlayerZoomPolicy.contentDims(AspectMode.FIT, 0f, SCREEN_W, SCREEN_H)
        assertEquals(FIT_16_9_W, w0, 0.01f)   // 未知比例按 16:9 兜底（与 VideoSurface 同口径）
        assertEquals(FIT_16_9_H, h0, 0.01f)
        val (w1, h1) = PlayerZoomPolicy.contentDims(AspectMode.FIT, 16f / 9f, 0f, 0f)
        assertEquals(0f, w1, 0.01f)
        assertEquals(0f, h1, 0.01f)
        val z = PlayerZoomPolicy.clampPan(ZoomState(2f, 500f, 500f), 0f, 0f, SCREEN_W, SCREEN_H)
        assertEquals(0f, z.panX, 0.001f)   // 内容 0 尺寸 → 上界 0，锁定居中不出 NaN
        assertEquals(0f, z.panY, 0.001f)
    }

    // ---------- 几何不变量：夹紧后的画面四角必须覆盖屏幕 ----------

    @Test
    fun `不变量：任意倍率下夹紧平移不露黑边（FIT 主战场场景穷举）`() {
        var scale = 1.0f
        while (scale <= PlayerZoomPolicy.MAX_SCALE + 0.001f) {
            for (panX in floatArrayOf(-9999f, -720f, 0f, 720f, 9999f)) {
                for (panY in floatArrayOf(-9999f, -540f, 0f, 540f, 9999f)) {
                    val z = PlayerZoomPolicy.clampPan(ZoomState(scale, panX, panY), FIT_16_9_W, FIT_16_9_H, SCREEN_W, SCREEN_H)
                    val contentL = SCREEN_W / 2f + z.panX - FIT_16_9_W * z.scale / 2f
                    val contentR = SCREEN_W / 2f + z.panX + FIT_16_9_W * z.scale / 2f
                    val contentT = SCREEN_H / 2f + z.panY - FIT_16_9_H * z.scale / 2f
                    val contentB = SCREEN_H / 2f + z.panY + FIT_16_9_H * z.scale / 2f
                    // 只断言「盖得住」：内容框要么盖住该轴，要么完全没盖住（此时平移必为 0，即居中）
                    if (FIT_16_9_W * z.scale >= SCREEN_W) {
                        assertTrue("scale=$scale panX=$panX → 左边缘 $contentL 越进屏幕", contentL <= 0f)
                        assertTrue("scale=$scale panX=$panX → 右边缘 $contentR 越进屏幕", contentR >= SCREEN_W)
                    } else {
                        assertEquals("scale=$scale 盖不住的水平轴必须居中", 0f, z.panX, 0.001f)
                    }
                    if (FIT_16_9_H * z.scale >= SCREEN_H) {
                        assertTrue("scale=$scale panY=$panY → 上边缘 $contentT 越进屏幕", contentT <= 0f)
                        assertTrue("scale=$scale panY=$panY → 下边缘 $contentB 越进屏幕", contentB >= SCREEN_H)
                    } else {
                        assertEquals("scale=$scale 盖不住的垂直轴必须居中", 0f, z.panY, 0.001f)
                    }
                }
            }
            scale += 0.5f
        }
    }
}
