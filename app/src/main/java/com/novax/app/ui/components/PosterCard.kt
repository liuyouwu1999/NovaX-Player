package com.novax.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.novax.app.ui.theme.NvColors

// DESIGN-SYSTEM.md §2.4 区块标题栏
@Composable
fun NvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onMoreClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 2026-09-20 间距节奏调整：标题上方 16dp（配合列表行距 8dp = 区块间 24dp，
            // 用户反馈区块贴太近）；标题→内容保持 16dp 不变
            .padding(start = NvSpacing.L, end = NvSpacing.L, top = NvSpacing.L, bottom = NvSpacing.S),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = NvTypography.TitleMedium)
        if (onMoreClick != null) {
            TextButton(onClick = onMoreClick) {
                Text("更多", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
            }
        }
    }
}

// DESIGN-SYSTEM.md §2.6 横向海报行的数据项
data class PosterItem(
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val progress: Float? = null,
    val badge: String? = null,
)

// DESIGN-SYSTEM.md §2.5 海报卡片（Plain / Progress / Badge / Fallback 变体）★核心组件
@Composable
fun NvPosterCard(
    title: String,
    subtitle: String? = null,
    posterUrl: String? = null,
    posterFile: java.io.File? = null, // 本地文件真帧（C12 帧封面管线）；非空时优先于 posterUrl
    progress: Float? = null,          // null = Plain；0f..1f = Progress 变体
    badge: String? = null,            // 角标文案（如 "新剧集"、"已看"），右上角 Badge 变体
    aspectRatio: Float = 2f / 3f,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, // 长按：操作菜单唯一入口，调用方不得再叠 combinedClickable
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) NvMotion.PressScale else 1f,
        animationSpec = tween(NvMotion.DurationFast, easing = NvMotion.CurveDecelerate),
        label = "posterPressScale",
    )
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    }
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(clickModifier)
            .widthIn(min = 110.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(NvShape.Poster)
                .background(NvColors.Skeleton)
                .graphicsLayer { if (pressed) alpha = 0.92f },
        ) {
            if (posterUrl != null || posterFile != null) {
                NvPosterFallback(title, Modifier.fillMaxWidth().aspectRatio(aspectRatio))
                var loaded by remember(title, posterFile, posterUrl) { mutableStateOf(false) }
                val imageAlpha by animateFloatAsState(
                    targetValue = if (loaded) 1f else 0f,
                    animationSpec = tween(NvMotion.DurationNormal),
                    label = "posterImageAlpha",
                )
                AsyncImage(
                    model = posterFile ?: posterUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .graphicsLayer { alpha = imageAlpha },
                    contentScale = ContentScale.Crop,
                    onSuccess = { loaded = true },
                )
            } else {
                NvPosterFallback(title, Modifier.fillMaxWidth().aspectRatio(aspectRatio))
            }
            badge?.let { b ->
                Text(
                    b,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NvSpacing.S)
                        .background(NvColors.Primary, NvShape.Badge)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = NvTypography.LabelSmall,
                    // 2026-09-16 UI 审计 P2：白对橙 #FF7A1A 对比度仅 2.6:1 → 黑字 7.6:1，
                    // 与库页分段控件选中态同一方案
                    color = Color.Black,
                )
            }
            progress?.let { p ->
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = NvColors.Primary,
                    // 2026-09-28 浅色模式：SurfaceElevated 浅色=白，白轨贴白卡不可见——用 Track token
                    trackColor = NvColors.Track,
                )
            }
        }
        Spacer(Modifier.height(NvSpacing.S))
        Text(
            title,
            style = NvTypography.LabelMedium,
            color = NvColors.TextPrimary,
            // 2026-09-18 用户反馈：两行折行不美观（中文任意字符处断行更碎）——单行省略
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = NvTypography.LabelSmall,
                color = NvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 封面缺失占位：首字母徽章（描边圆环容器）+ 标题 hash 派生的橙蓝渐变，绝不留白。
// 2026-09-18 用户反馈采纳审计 P2-6：裸露大字像半成品——字母徽章化（圆环描边 + 35% 底），
// 纯静态矢量绘制（一次构建显示列表，滚动重放），无位图/无动画，性能开销可忽略
@Composable
fun NvPosterFallback(title: String, modifier: Modifier = Modifier) {
    // 2026-09-27 用户反馈去 AI 味：标题 hash 派生的橙蓝装饰渐变换中性底（Skeleton），
    // 首字母徽章保留——海报位缺图时安静占位，不再抢视线
    Box(
        modifier = modifier.background(NvColors.Skeleton),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(NvColors.Background.copy(alpha = 0.30f))
                .border(1.dp, NvColors.TextSecondary.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "?",
                style = NvTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold,
                color = NvColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// DESIGN-SYSTEM.md §2.6 横向海报行
@Composable
fun NvPosterRow(
    header: String,
    items: List<PosterItem>,
    onMoreClick: (() -> Unit)? = null,
    onItemClick: (PosterItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        NvSectionHeader(header, onMoreClick = onMoreClick)
        LazyRow(
            contentPadding = PaddingValues(horizontal = NvSpacing.L),
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
        ) {
            items(items) { item ->
                NvPosterCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    posterUrl = item.posterUrl,
                    progress = item.progress,
                    badge = item.badge,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}
