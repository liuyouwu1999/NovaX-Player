package com.novax.app.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面几何纯函数单测（2026-10-03 旋转压缩 bug 回归锁定）。
 * 口径 = 用户真机 2640×1216 横屏 + 9:16 竖屏视频（旋转压缩截图的实测场景）：
 * - 图3（0° 基线）：竖条宽占比 0.261 = 684/2640，FIT 正确；
 * - 图2（90° 旧实现）：竖条宽占比 0.460 = 1216/2640 —— .size() 被父约束钳成 1216×1216
 *   正方形（RmvbPlayerActivity.kt:1707 模拟器 dumpsys 同款实证），旋转后内容横向压缩；
 * - 修复后：虚拟画布盒 1216×2161.78 用 requiredSize 表达，旋转足迹 2161.78×1216（宽占比 0.819）。
 */
class VideoSurfaceGeometryTest {

    @Test
    fun `FIT 初始显示 - 竖屏视频横屏 - 高度受限盒（图3基线）`() {
        val (w, h) = fitDims(9f / 16f, 2640.dp, 1216.dp)
        assertEquals(1216.dp, h)
        assertEquals(1216.dp * (9f / 16f), w)   // 684dp；左右大黑边是竖视频横屏的物理必然
    }

    @Test
    fun `FIT 旋转90虚拟画布 - 盒高必须超出真实屏高（钳制即图2压缩bug）`() {
        // effW=屏高1216、effH=屏宽2640：盒 = 1216×2161.78。旧实现此处被钳回 1216×1216。
        val (w, h) = fitDims(9f / 16f, 1216.dp, 2640.dp)
        assertEquals(1216.dp, w)
        assertEquals(1216.dp / (9f / 16f), h)   // 2161.78dp > 屏高 1216dp —— requiredSize 的存在理由
    }

    @Test
    fun `画布未旋转 - 尺寸不超屏（size 与 requiredSize 等同，180度零回归）`() {
        val (w, h) = fitDims(9f / 16f, 1216.dp, 1216.dp)
        assertEquals(1216.dp * (9f / 16f), w)
        assertEquals(1216.dp, h)
    }

    @Test
    fun `cover 16比9 - 足迹单轴超出屏幕（裁剪填充语义不变）`() {
        val (w, h) = coverDims(16f / 9f, 2640.dp, 1216.dp)
        assertEquals(2640.dp, w)
        assertEquals(2640.dp / (16f / 9f), h)   // 1485dp > 1216dp，出屏轴即被裁剪轴
    }
}
