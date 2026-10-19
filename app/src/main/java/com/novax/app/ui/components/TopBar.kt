package com.novax.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.ui.glass.LocalNvGlassEnv
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.theme.NvColors

// Liquid Glass（spec §6/§9）：env 已接线域自动玻璃（SCRIM 档经 nvGlassSurface 自动升 GlassFillStrong
// 半透明——门禁是 env != null 而非 blurCapable，否则 API 26-30 顶栏错成实色）；未接线域自动回退实色。
// 调用方 API 零改动、过渡期不脏。internal：Task 7 FileBrowser 三态顶栏复用。
// 2026-10-15 观感返工①：scrolled=false 时背景层整体渐隐（animateFloatAsState 150ms）——静止态顶栏
// 隐形、标题/图标直接压内容（iOS 26 Fitness 同款）；滚入渐显。alpha 置于玻璃链之前且调用方须挂在
// 无内容背景 Box 上（NvTopBar 已内置），alpha 才只作用于背景不伤标题/图标。
@Composable
internal fun nvTopBarBackground(scrolled: Boolean = true): Modifier {
    val env = LocalNvGlassEnv.current
    val bgAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            NvMotion.DurationFast,
            easing = NvMotion.CurveDecelerate,
        ),
        label = "nvTopBarBgAlpha",
    )
    val glass = if (env != null) {
        Modifier.nvGlassSurface(NvColors.GlassFill, RectangleShape)
    } else {
        Modifier.background(NvColors.Background)
    }
    return Modifier.alpha(bgAlpha).then(glass)
}

/** 观感返工①滚动渐显信号（DetailScreen 先例统一化）：首项滚离（index>0 或 offset>48px）为「已滚动」。
 *  LazyListState 版（LazyColumn）。derivedStateOf：仅布尔翻转时触发重组。 */
@Composable
fun rememberNvListScrolled(listState: androidx.compose.foundation.lazy.LazyListState): State<Boolean> =
    remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48
        }
    }

/** 同上，LazyGridState 版（LazyVerticalGrid 网格页；offset 语义同为首可见项像素偏移）。 */
@Composable
fun rememberNvGridScrolled(gridState: androidx.compose.foundation.lazy.grid.LazyGridState): State<Boolean> =
    remember(gridState) {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 48
        }
    }

// DESIGN-SYSTEM.md §2.13 二级页面顶部标题栏（高 56dp + 状态栏 inset）
// 2026-10-15 观感返工①：新增 scrolled 参数（默认 true=兼容未接线调用方，行为同旧版）。
// 背景层独立 Box（matchParentSize）承载渐隐——标题/图标在内容层恒不透明（静止态压内容）。
@Composable
fun NvTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: List<@Composable RowScope.() -> Unit> = emptyList(),
    modifier: Modifier = Modifier,
    scrolled: Boolean = true,
) {
    Box(modifier.fillMaxWidth()) {
        // 背景/玻璃层（可整体渐隐）：先声明绘制在下；无内容子级，alpha 不伤文字
        Box(Modifier.matchParentSize().then(nvTopBarBackground(scrolled)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = NvColors.TextPrimary,
                    )
                }
            }
            Text(
                title,
                Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) NvSpacing.S else NvSpacing.L, end = NvSpacing.S),
                style = NvTypography.TitleMedium,
                color = NvColors.TextPrimary,   // 2026-09-15：显式色（ViewerActivity 无 Scaffold 时 LocalContentColor=黑，标题黑上黑不可见）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions.forEach { it() }
        }
    }
}
