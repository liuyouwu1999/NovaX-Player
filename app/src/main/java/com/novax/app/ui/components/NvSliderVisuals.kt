package com.novax.app.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 滑杆视觉绘制内核(2026-10-10 S2「进度条重做」,spec §2.2:金属推子参考收敛到深色+橙轻拟物)。
 * DrawScope 扩展、非 Composable——颜色 token 由调用方在组合域提升后传入,禁止本文件内取主题。
 * 投影是全项目唯一豁免的裸色值(永远黑,spec §2.2 明文)。
 */

/** 旋钮:软投影 + 径向渐变主体(中心亮→中段→边缘暗,受光中心略偏左上)+ rim 描边 + 暗色旋钮芯 */
fun DrawScope.drawNvKnob(
    center: Offset,
    radius: Float,
    highlight: Color,
    base: Color,
    edge: Color,
    rim: Color,
) {
    // 1. 软投影(黑色 22%,主体其后覆盖上半、下缘残露即投影)
    drawCircle(color = Color(0x38000000), radius = radius * 1.05f, center = center + Offset(0f, radius * 0.09f))
    // 2. 主圆:径向渐变 3 档(spec §2.2:highlight@0 → base@0.65 → edge@1.0;受光中心偏左上 -8%;
    //    渐变半径 1.2r——远缘距受光中心约 1.11r,保证 edge 色在旋钮边圈真实到达。取值为实现
    //    校准,Task 9 视觉评审允许再调)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to highlight,
                0.65f to base,
                1f to edge,
            ),
            center = center - Offset(radius * 0.08f, radius * 0.08f),
            radius = radius * 1.2f,
        ),
        radius = radius,
        center = center,
    )
    // 3. rim 描边(约 1.2dp @ 11dp 半径)
    drawCircle(color = rim, radius = radius, center = center, style = Stroke(width = (radius * 0.11f).coerceAtLeast(1f)))
    // 4. 旋钮芯(edge 派生,不做新 token)
    drawCircle(color = edge.copy(alpha = 0.55f), radius = radius * 0.22f, center = center)
}

/** 凹槽底轨:纵向线性渐变 顶缘受光(Track 提亮 1.4x)→ Track,全圆头 */
fun DrawScope.drawNvGrooveTrack(track: Color, topLeft: Offset, size: Size) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(track.copy(alpha = (track.alpha * 1.4f).coerceAtMost(1f)), track),
            startY = topLeft.y,
            endY = topLeft.y + size.height,
        ),
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(size.height / 2f),
    )
}

/** 已播段:纵向 Primary→PrimaryPressed 渐变(上亮下深),全圆头 */
fun DrawScope.drawNvActiveTrack(active: Color, pressed: Color, topLeft: Offset, size: Size) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(active, pressed),
            startY = topLeft.y,
            endY = topLeft.y + size.height,
        ),
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(size.height / 2f),
    )
}
