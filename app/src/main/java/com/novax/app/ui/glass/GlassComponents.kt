package com.novax.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Activity 域接线入口：构造 env + 下发。播放器/查看器域用同款（包在各自 setContent 根部）。 */
@Composable
fun LocalNvGlassEnvProviding(content: @Composable () -> Unit) {
    val env = rememberNvGlassEnv()
    CompositionLocalProvider(LocalNvGlassEnv provides env, content = content)
}

@Composable
fun rememberNvGlassEnv(): NvGlassEnv {
    val cap = effectiveCapability(resolveGlassCapability(android.os.Build.VERSION.SDK_INT))
    // rememberHazeState() 为 haze 官方推荐的 @Composable 构造（勿裸 new HazeState()）
    val state = if (cap == NvGlassCapability.SCRIM) null else rememberHazeState()
    return remember(cap, state) { NvGlassEnv(cap, state) }
}

/** 内容容器挂 hazeSource；SCRIM 档/未接线恒等。hazeSource 非 @Composable，普通扩展即可。
 *  职责仅限「标记取样源」，与效果侧（nvGlassSurface/nvHazeOrFill）兄弟并置使用（decisions.md §4.1）。 */
fun Modifier.nvGlassSource(env: NvGlassEnv?): Modifier {
    val state = env?.hazeState as? HazeState ?: return this
    return this.hazeSource(state)
}

/** 顶栏悬浮配方的 top inset = 状态栏 + 56dp 栏高 */
@Composable
fun rememberNvTopBarInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

/** 底栏内容高（NvBottomBar 胶囊：XS2*2 padding + 60dp 最小 tab 高；rework2 收窄的是水平 padding，
 *  高度不变 → 常量维持 72） */
val NV_BOTTOM_BAR_CONTENT = 72.dp

/** 观感返工③（2026-10-15）悬浮胶囊几何：左右留边 / 底部离屏边（NvBottomBar 消费，Apple Music 参照）。
 *  rework2：inset 已移出玻璃，胶囊 bottom 边=手势区上缘再上浮 FLOAT_GAP（content-hugging），
 *  玻璃不再延伸进手势区。列表 contentPadding 避让总量语义不变，见 rememberNvGlassBottomPadding。 */
val NV_BOTTOM_BAR_SIDE_MARGIN = 16.dp
val NV_BOTTOM_BAR_FLOAT_GAP = 12.dp

/** spec §9：IME 打开→0dp（不留背景空带），否则 胶囊全高 + 导航栏 inset（底栏不随键盘上移，被键盘覆盖）。
 *  胶囊全高（自屏底量）= FLOAT_GAP(12) + NV_BOTTOM_BAR_CONTENT(72) + 导航栏 inset——
 *  rework2 后胶囊悬于手势区上方，避让总量（列表须让出的高度）与此式不变：navBar 段=手势区
 *  （无玻璃）+ FLOAT_GAP=胶囊与手势区间隙。各页消费点零改动。 */
@Composable
fun rememberNvGlassBottomPadding(): Dp {
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return if (imeOpen) 0.dp else NV_BOTTOM_BAR_FLOAT_GAP + NV_BOTTOM_BAR_CONTENT + navBar
}

/** 通栏玻璃容器（底栏/悬浮顶栏簇用） */
@Composable
fun NvGlassBar(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides NvColors.TextPrimary) {
        Box(
            modifier
                .nvGlassSurface(NvColors.GlassFill, shape),
            content = content,
        )
    }
}

/** 悬浮胶囊/按钮容器（播放器浮钮/CastOverlay chip 用）。
 *  [fill] 返工⑤（2026-10-15 用户第一项反馈）新增：默认 GlassFill 清玻璃不变；压在视频画面上的
 *  播放器中央播放/暂停钮传 GlassFillStrong 磨砂强填充——清玻璃圆钮压画面几乎隐形（用户截图实证），
 *  强填充保留玻璃形状/光效/描边但不再透视画面，播放图标对比度随填充回归。
 *  [fillOverlay] 磨砂盖印透传（haze FULL 档 tint() 不渲染，见 nvGlassSurface 注）；强填充钮置 true */
@Composable
fun NvGlassChip(
    modifier: Modifier = Modifier,
    shape: Shape,
    fill: Color = NvColors.GlassFill,
    fillOverlay: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = if (onClick != null) {
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }
    } else {
        modifier
    }
    CompositionLocalProvider(LocalContentColor provides NvColors.TextPrimary) {
        // lens 轮：NvGlassChip=小元素（胶囊钮/筛选 chip）→ 强档透镜（apple_ref_v2 准绳5，
        // 分档语义见 NvGlassLensTier；Bar/Panel 默认弱档不动）
        Box(m.nvGlassSurface(fill, shape, NvGlassLensTier.Chip, fillOverlay = fillOverlay), content = content)
    }
}

/** 填充式玻璃面板容器（独立窗口弹层的规范原语，spec §8；无 haze 依赖）。
 *  M3 AlertDialog/ModalBottomSheet 只暴露 containerColor 单参、无法整容器描边/提亮——
 *  独立窗口弹层当前只消费 GlassFillStrong 填充常量（Task 4，限制记 decisions.md）；
 *  本原语供自绘弹层与未来改造复用（播放器 SCRIM 档回退等）。 */
@Composable
fun NvGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    // @Composable getter 必须在组合期取值（Global Constraint），先提升为 val 再进 drawBehind/border
    val highlight = NvColors.GlassHighlight
    val border = NvColors.GlassBorder
    CompositionLocalProvider(LocalContentColor provides NvColors.TextPrimary) {
        Box(
            modifier
                .background(NvColors.GlassFillStrong, shape)
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(highlight, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.35f,
                        ),
                    )
                }
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(border, border.copy(alpha = border.alpha * 0.4f)),
                    ),
                    shape,
                ),
            content = content,
        )
    }
}
