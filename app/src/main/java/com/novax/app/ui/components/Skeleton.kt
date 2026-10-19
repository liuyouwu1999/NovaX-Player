package com.novax.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

// DESIGN-SYSTEM.md §2.7 骨架屏：任意尺寸色块 + shimmer（12% 高亮 1200ms 线性循环扫过）
@Composable
fun NvSkeletonBox(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(NvMotion.ShimmerDuration, easing = LinearEasing),
        ),
        label = "shimmerX",
    )
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
            .background(NvColors.Skeleton),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = size.width * (x * 2f - 1f) }
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, NvColors.SkeletonHighlight, Color.Transparent),
                    ),
                ),
        )
    }
}

// DESIGN-SYSTEM.md §2.7 海报网格骨架（2026-09-15 重做：与真实卡片同构——
// 海报块 + 下方两条标题线，纵向间距对齐真实网格 NvSpacing.L；旧版裸大块在 2 列时
// 占满半屏且微光条横贯，观感像「框重叠」）
@Composable
fun NvSkeletonPosterGrid(columns: Int = 3, rows: Int = 4, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = NvSpacing.L),
        verticalArrangement = Arrangement.spacedBy(NvSpacing.L),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(NvSpacing.M)) {
                repeat(columns) {
                    Column(Modifier.weight(1f)) {
                        NvSkeletonBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        Spacer(Modifier.height(NvSpacing.S))
                        NvSkeletonBox(Modifier.fillMaxWidth().height(10.dp))
                        Spacer(Modifier.height(4.dp))
                        NvSkeletonBox(Modifier.fillMaxWidth(0.55f).height(10.dp))
                    }
                }
            }
        }
    }
}

// DESIGN-SYSTEM.md §2.7 列表行骨架：左头像/缩略图 + 右两行文字
@Composable
fun NvSkeletonListRow(
    modifier: Modifier = Modifier,
    leadingSize: Dp = 48.dp,
    leadingCorner: Dp = 8.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        NvSkeletonBox(Modifier.size(leadingSize), corner = leadingCorner)
        Column(
            Modifier
                .weight(1f)
                .padding(start = NvSpacing.M),
            verticalArrangement = Arrangement.spacedBy(NvSpacing.S),
        ) {
            NvSkeletonBox(Modifier.fillMaxWidth(0.55f).height(14.dp), corner = 4.dp)
            NvSkeletonBox(Modifier.fillMaxWidth(0.35f).height(12.dp), corner = 4.dp)
        }
    }
}
