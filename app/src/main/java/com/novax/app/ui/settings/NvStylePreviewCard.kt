package com.novax.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.player.NvControlStyle
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.theme.NvPlayerBlue

/**
 * 控制层样式迷你预览卡（T9，spec 2026-11-15 §7）：Canvas 示意（非截图），
 * 黑底圆角 120×68dp + 仓内 NvColors/NvShape 语言，设置页纵排三张（spec §4/P2-3）。
 * 选中态 Primary 2dp 描边（系统主题切换式样例，用户拍板「不盲切」）；
 * 三档示意要点按 spec §7 逐条画全——
 *  经典：顶条（返回点+标题线+格子串）+ 底部两行（七点+粗线）；
 *  极简（rev4）：顶条两端蓝块 + 左/右缘三点竖排 + 底部一行七点 + 贴缘细线；
 *  长视频风：顶条（返回点+标题线+四点）+ 单行（五点+时间线段）+ 贴底细线。
 * 样式名复用 T8 三语言键（player_style_classic/minimal/streaming）。
 */
@Composable
fun NvStylePreviewCard(style: NvControlStyle, selected: Boolean, onClick: () -> Unit) {
    val primary = NvColors.Primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(NvShape.Poster)
            .background(NvColors.Surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) NvColors.Primary else NvColors.Outline,
                shape = NvShape.Poster,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier
                .size(width = 120.dp, height = 68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PreviewBg),
        ) {
            // 画布坐标按 dp 网格换算（1u = 1dp），密度无关
            val u = 1.dp.toPx()
            when (style) {
                // 共用顶条（返回点+标题线）仅经典/长视频风；极简 rev4 顶条=两端蓝块，分支内自绘
                // （不走共用顶条，防"返回点+标题线"残留）
                NvControlStyle.CLASSIC -> {
                    drawTopStrip(u)
                    drawClassic(u, primary)
                }
                NvControlStyle.MINIMAL -> drawMinimal(u, primary)
                NvControlStyle.STREAMING -> {
                    drawTopStrip(u)
                    drawStreaming(u, primary)
                }
            }
        }
        Text(
            stringResource(style.labelRes()),
            style = NvTypography.BodyMedium,
            color = if (selected) NvColors.Primary else NvColors.TextPrimary,
            modifier = Modifier.padding(start = NvSpacing.L),
        )
    }
}

/** 迷你示意黑底（视频画面象征，深浅色主题一致，spec §7「黑底圆角卡」） */
private val PreviewBg = Color(0xFF101014)

/** 三档显示名复用 T8 键（spec §6：不重复加键） */
private fun NvControlStyle.labelRes(): Int = when (this) {
    NvControlStyle.CLASSIC -> R.string.player_style_classic
    NvControlStyle.MINIMAL -> R.string.player_style_minimal
    NvControlStyle.STREAMING -> R.string.player_style_streaming
}

/** 通用顶条：返回点 + 标题线（经典/长视频风共有，spec §7；极简 rev4 顶条两端蓝块分支内自绘，不走此条） */
private fun DrawScope.drawTopStrip(u: Float) {
    drawCircle(Color.White.copy(alpha = 0.8f), radius = 2.5f * u, center = Offset(10f * u, 10.5f * u))
    drawRoundRect(
        Color.White.copy(alpha = 0.45f),
        cornerRadius = CornerRadius(2f * u),
        topLeft = Offset(18f * u, 8.5f * u),
        size = Size(34f * u, 4f * u),
    )
}

/** 贴底细线：已播段 Primary + 未播段半透明白（极简常驻线/长视频风贴底线共用画法） */
private fun DrawScope.drawBottomEdgeLine(u: Float, primary: Color, playedWidthDp: Float) {
    drawRoundRect(
        Color.White.copy(alpha = 0.25f),
        cornerRadius = CornerRadius(1.25f * u),
        topLeft = Offset(0f, 65.5f * u),
        size = Size(120f * u, 2.5f * u),
    )
    drawRoundRect(
        primary,
        cornerRadius = CornerRadius(1.25f * u),
        topLeft = Offset(0f, 65.5f * u),
        size = Size(playedWidthDp * u, 2.5f * u),
    )
}

/** 经典：顶条右侧格子串 4 个 + 底部两行（七点[中央暂停加大] + 粗线[已播 Primary+旋钮]） */
private fun DrawScope.drawClassic(u: Float, primary: Color) {
    repeat(4) { i ->
        drawRoundRect(
            Color.White.copy(alpha = 0.5f),
            cornerRadius = CornerRadius(1f * u),
            topLeft = Offset((86f + i * 7.5f) * u, 8.5f * u),
            size = Size(4f * u, 4f * u),
        )
    }
    // 七点：中央暂停钮加大（58dp 暂停钮为行高主导，§3.1）
    listOf(-42f, -28f, -14f, 0f, 14f, 28f, 42f).forEach { dx ->
        val r = if (dx == 0f) 3.2f else 2f
        drawCircle(Color.White.copy(alpha = 0.7f), radius = r * u, center = Offset((60f + dx) * u, 42f * u))
    }
    // 粗横线（经典粗轨）：未播 35% + 已播 Primary + 旋钮
    drawRoundRect(
        Color.White.copy(alpha = 0.35f),
        cornerRadius = CornerRadius(2.5f * u),
        topLeft = Offset(8f * u, 52f * u),
        size = Size(104f * u, 5f * u),
    )
    drawRoundRect(
        primary,
        cornerRadius = CornerRadius(2.5f * u),
        topLeft = Offset(8f * u, 52f * u),
        size = Size(36f * u, 5f * u),
    )
    drawCircle(Color.White.copy(alpha = 0.9f), radius = 2.8f * u, center = Offset(44f * u, 54.5f * u))
}

/**
 * 极简（rev4，spec §7 示意 1:1 对应真机形态）：顶条两端蓝块（[NvPlayerBlue] ✕/⋯ 圆角方块，
 * 44dp 块按画布比例缩至 8dp、圆角 10dp 同比缩至 1.8dp）+ 左缘三点竖排（锁/字幕/齿轮）+
 * 右缘三点竖排（倍速三件套 ⊕/速度/⊖）+ 底部一行七点（黑条七钮等距，中央暂停加大）+
 * 贴缘细线（line 进度条，已播 Primary）。顶条不走共用 [drawTopStrip]（无返回点/标题线）。
 */
private fun DrawScope.drawMinimal(u: Float, primary: Color) {
    // 顶条两端蓝块：真实形态=44dp 顶栏两端 44dp NvPlayerBlue 圆角(10dp)方块（✕ 退出/⋯ 更多）
    val blueCorner = CornerRadius(1.8f * u)
    drawRoundRect(
        NvPlayerBlue,
        cornerRadius = blueCorner,
        topLeft = Offset(5f * u, 5.5f * u),
        size = Size(8f * u, 8f * u),
    )
    drawRoundRect(
        NvPlayerBlue,
        cornerRadius = blueCorner,
        topLeft = Offset(107f * u, 5.5f * u),
        size = Size(8f * u, 8f * u),
    )
    // 左缘三点竖排（锁/CC 字幕/齿轮）+ 右缘三点竖排（倍速三件套：46dp 圆钮列）
    listOf(24f, 34f, 44f).forEach { y ->
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 2f * u, center = Offset(9f * u, y * u))
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 2f * u, center = Offset(111f * u, y * u))
    }
    // 底部一行七点：锁/上集/快退/暂停/快进/下集/比例（等距，中央暂停 58dp 加大）
    listOf(-39f, -26f, -13f, 0f, 13f, 26f, 39f).forEach { dx ->
        val r = if (dx == 0f) 2.8f else 2f
        drawCircle(Color.White.copy(alpha = 0.7f), radius = r * u, center = Offset((60f + dx) * u, 56f * u))
    }
    drawBottomEdgeLine(u, primary, playedWidthDp = 48f)
}

/** 长视频风：顶条右端四点（图标簇）+ 单行五点[中央暂停加大]+时间线段 + 贴底细线 */
private fun DrawScope.drawStreaming(u: Float, primary: Color) {
    listOf(94f, 101f, 108f, 115f).forEach { x ->
        drawCircle(Color.White.copy(alpha = 0.55f), radius = 1.6f * u, center = Offset(x * u, 10.5f * u))
    }
    // 单行：上集/快退/暂停/快进/下集（中央暂停加大）
    listOf(20f, 31f, 42f, 53f, 64f).forEachIndexed { i, x ->
        val r = if (i == 2) 2.8f else 2f
        drawCircle(Color.White.copy(alpha = 0.7f), radius = r * u, center = Offset(x * u, 40f * u))
    }
    // 内联时间线段（12:34/45:00 示意）
    drawRoundRect(
        Color.White.copy(alpha = 0.45f),
        cornerRadius = CornerRadius(1.5f * u),
        topLeft = Offset(76f * u, 38.5f * u),
        size = Size(32f * u, 3f * u),
    )
    drawBottomEdgeLine(u, primary, playedWidthDp = 48f)
}
