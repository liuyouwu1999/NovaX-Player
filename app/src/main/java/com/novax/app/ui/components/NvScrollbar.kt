package com.novax.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

/**
 * 内容列右缘细滚动条(2026-10-09 用户需求「弹窗右边要稍微显示一下滚动条,说明可以往下滑」,
 * spec 2026-10-09 §5):内容超高(state.maxValue>0)时常驻低透明度拇指——静置 alpha 0.28、
 * 滚动中 0.55,保证「没滑过的人也知道能滑」;内容贴满(不超高)不画。drawBehind 实现,不拦
 * 触摸(内容区拖动即滚),不做拖拽拇指交互。全 px 域计算(dp 界先 toPx 再夹取)。
 * NvPlayerPopup/NvBottomSheet 内容列挂用,全部弹层观感一致。
 */
fun Modifier.nvVerticalScrollbar(state: ScrollState): Modifier = composed {
    val thumbWidthPx = with(LocalDensity.current) { 3.dp.toPx() }
    val insetPx = with(LocalDensity.current) { 3.dp.toPx() }
    val minThumbPx = with(LocalDensity.current) { 24.dp.toPx() }
    // DrawScope 非 Composable:token 提升到 draw 外
    val idle = NvColors.TextSecondary.copy(alpha = 0.28f)
    val active = NvColors.TextSecondary.copy(alpha = 0.55f)
    drawBehind {
        if (state.maxValue <= 0) return@drawBehind
        val viewportH = size.height
        val thumbLen = (viewportH * viewportH / (viewportH + state.maxValue))
            .coerceIn(minThumbPx, viewportH)
        val top = (viewportH - thumbLen) * (state.value.toFloat() / state.maxValue)
        drawRoundRect(
            color = if (state.isScrollInProgress) active else idle,
            topLeft = Offset(size.width - insetPx - thumbWidthPx, top),
            size = Size(thumbWidthPx, thumbLen),
            cornerRadius = CornerRadius(thumbWidthPx / 2f),
        )
    }
}
