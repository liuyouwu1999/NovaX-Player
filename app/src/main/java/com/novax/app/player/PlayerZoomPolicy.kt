package com.novax.app.player

import kotlin.math.abs

/**
 * 双指缩放状态（2026-09-15 用户需求，B 站同款）。
 *
 * - [scale]：画面倍率，1.0 = 原始大小；
 * - [panX]/[panY]：画面相对屏幕中心的平移（px，View translation 语义：正值向右/向下）。
 *
 * 平移单位用 px 是刻意的：手势层（detectPlayerGestures）拿到的事件坐标本身就是 px，
 * 消费端（VideoSurface 的 view translation）也是 px，中间不做密度换算，两端口径天然一致。
 */
data class ZoomState(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

/**
 * 双指缩放纯逻辑（B 站同款：捏合缩放 + 跟手平移 + 「还原画面」一键回 1.0）。
 *
 * 两条不变量：
 * 1. scale 恒在 [MIN_SCALE, MAX_SCALE]；捏合只做「起点缩放 × 距离比」的等比换算；
 * 2. 平移夹紧：放大后的画面边缘**不得拖进屏幕内**（一旦内容边缘越过屏幕边缘就会露出黑边，
 *    视觉上等于「把视频拖丢了」）。内容框小于屏幕（FIT 黑边场景）时低倍率下不允许平移。
 *
 * 内容盒尺寸（[contentDims]）与 PlayerScreen 的 fitSize/coverSize/coverTransform 是同一套
 * 几何的 px 口径复算——两侧必须同步修改，单测用几何不变量锁住。
 */
object PlayerZoomPolicy {

    const val MIN_SCALE = 1.0f
    const val MAX_SCALE = 4.0f

    /** 判定「已缩放」的容差：吃掉浮点抖动，避免捏合后轻微越界就常驻还原按钮 */
    const val ZOOMED_EPSILON = 0.01f

    /** 平移判定容差（px）：1px 以内的抖动不算缩放态 */
    const val PAN_EPSILON = 1f

    fun clampScale(raw: Float): Float =
        if (raw.isNaN()) MIN_SCALE else raw.coerceIn(MIN_SCALE, MAX_SCALE)

    /** 捏合距离比 → 缩放值：起点缩放 × 当前两指距离 / 起始两指距离 */
    fun scaleFromPinch(startScale: Float, startDist: Float, dist: Float): Float {
        if (startDist < 1f || dist.isNaN() || startScale.isNaN()) return clampScale(startScale)
        return clampScale(startScale * (dist / startDist))
    }

    /** 是否处于缩放态（决定还原按钮显隐；恒等态不显示） */
    fun isZoomed(z: ZoomState): Boolean =
        z.scale > MIN_SCALE + ZOOMED_EPSILON ||
            abs(z.panX) > PAN_EPSILON ||
            abs(z.panY) > PAN_EPSILON

    /**
     * 平移夹紧：内容框随 scale 放大后，中心平移的绝对值上界 = (内容尺寸×scale − 屏幕)/2。
     * 该上界恰好是「画面边缘与屏幕边缘对齐」的位置——超过它就露黑边。内容框本就小于屏幕
     * 或倍率不足以盖住屏幕的轴，上界取 0（锁定居中，不允许拖动）。
     */
    fun clampPan(z: ZoomState, contentW: Float, contentH: Float, screenW: Float, screenH: Float): ZoomState {
        val maxX = ((contentW * z.scale - screenW) / 2f).coerceAtLeast(0f)
        val maxY = ((contentH * z.scale - screenH) / 2f).coerceAtLeast(0f)
        return z.copy(
            panX = z.panX.coerceIn(-maxX, maxX),
            panY = z.panY.coerceIn(-maxY, maxY),
        )
    }

    /**
     * 当前 aspect 模式下视频内容在屏上的**可见足迹**（px），平移夹紧用。
     * 与 VideoSurface 的几何一一对应：
     * - FIT → fitSize（内容 ≤ 屏幕，黑边场景）；
     * - FILL → 屏幕；
     * - R16_9/R4_3 → coverSize 足迹再乘 coverTransform 缩放（内容 ≥ 屏幕的轴已裁剪出屏）。
     *
     * [videoAspect] 为视频像素宽高比；未知时按 16:9（与 VideoSurface 同口径）。
     */
    fun contentDims(aspect: AspectMode, videoAspect: Float, screenW: Float, screenH: Float): Pair<Float, Float> {
        if (screenW <= 0f || screenH <= 0f) return screenW to screenH
        val va = if (videoAspect > 0f) videoAspect else 16f / 9f
        return when (aspect) {
            AspectMode.FIT -> fitDims(va, screenW, screenH)
            AspectMode.FILL -> screenW to screenH
            AspectMode.R16_9 -> coverContentDims(16f / 9f, va, screenW, screenH)
            AspectMode.R4_3 -> coverContentDims(4f / 3f, va, screenW, screenH)
        }
    }

    /** 在 maxW×maxH 内求最大内接尺寸（= PlayerScreen.fitSize 的 px 口径） */
    fun fitDims(aspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        if (maxW <= 0f || maxH <= 0f || aspect <= 0f) return maxW to maxH
        return if (maxW / maxH > aspect) Pair(maxH * aspect, maxH) else Pair(maxW, maxW / aspect)
    }

    private fun coverDims(aspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        if (maxW <= 0f || maxH <= 0f || aspect <= 0f) return maxW to maxH
        return if (maxW / maxH > aspect) Pair(maxW, maxW / aspect) else Pair(maxH * aspect, maxH)
    }

    /** cover 可见内容 = coverSize 足迹 × coverTransform 缩放（短轴放大到盖满，另一轴已超出屏幕） */
    private fun coverContentDims(targetAspect: Float, videoAspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val (w, h) = coverDims(targetAspect, maxW, maxH)
        return if (videoAspect > targetAspect) {
            Pair(w * videoAspect / targetAspect, h)
        } else {
            Pair(w, h * targetAspect / videoAspect)
        }
    }
}
