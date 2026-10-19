package com.novax.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 2026-09-20 浅色模式改造：token 从「静态深色常量」升级为「双调色板 + CompositionLocal」。
 * NvColors.X 改为 @Composable getter 读取当前调色板——全 app 数百处 `NvColors.X` 调用点零改动；
 * 播放器遮罩类 token（Scrim/ScrimStrong/TrackUnderlay）双套同值（视频上的遮罩永远深色）。
 * 用户偏好（跟随系统/浅色/深色）存 novax_ui_prefs `KEY_UI_THEME`（MainActivity 传入 darkTheme）。
 */
data class NvPalette(
    val Background: Color,
    val Surface: Color,
    val SurfaceElevated: Color,
    val Primary: Color,
    val Secondary: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextDisabled: Color,
    val SurfacePressed: Color,
    val PrimaryPressed: Color,
    val PrimaryContainer: Color,
    val Outline: Color,
    val Divider: Color,
    val Skeleton: Color,
    val Success: Color,
    val Track: Color,
    val ChapterDivider: Color,
    val Scrim: Color,
    /** 播放器顶/底渐变端色（90% 黑，UI 审计 P1-2 亮画面可读性修复后档位） */
    val ScrimStrong: Color,
    /** 播放器进度条底轨衬底（40% 黑，亮画面下托起 20% 白轨道） */
    val TrackUnderlay: Color,
    val SkeletonHighlight: Color,
    /** 滑杆旋钮视觉(2026-10-10 S2):径向渐变亮面/中段/暗圈与外圈描边 */
    val KnobHighlight: Color,
    val KnobBase: Color,
    val KnobEdge: Color,
    val KnobRim: Color,
    /** 毛玻璃组 token（spec §7；GlassFillStrong 消费点=SCRIM 档填充 + 独立窗口弹层填充[M3 单参限制，见 decisions.md]） */
    val GlassFill: Color,
    val GlassFillStrong: Color,
    val GlassBorder: Color,
    /** 返工⑤（2026-10-15 用户真机第五项反馈）：FULL/BLUR 档极细描边端色（1dp hairline，比 GlassBorder
     *  弱一档、且亮色改黑基——白描边在浅背景上不可辨是「亮色玻璃融底」主因）。仅 nvGlassSurface
     *  FULL/BLUR 档消费；GlassBorder 维持 SCRIM 档/独立弹层语义不动 */
    val GlassBorderHairline: Color,
    val GlassHighlight: Color,
    /** rework3 液态玻璃：顶缘镜面高光端色（左上光源亮边，nvGlassSurface FULL/BLUR 档 drawBehind 消费） */
    val GlassSheen: Color,
    /** lens 轮：右下暗色内影端色（不对称光效暗侧，nvGlassSurface FULL/BLUR 档 drawBehind 消费；
     *  apple_ref_v2 准绳4：左上亮边 + 右下暗影，暗色模式 12% 黑、亮色模式环带四周皆亮减半 6% 黑） */
    val GlassInnerShadow: Color,
    val Error: Color,
)

internal val DarkPalette = NvPalette(
    Background = Color(0xFF0A0A0F),
    Surface = Color(0xFF16161D),
    SurfaceElevated = Color(0xFF1F1F29),
    Primary = Color(0xFFFF7A1A),
    Secondary = Color(0xFF4C8DFF),
    TextPrimary = Color(0xE6FFFFFF),
    TextSecondary = Color(0x99FFFFFF),
    TextDisabled = Color(0x61FFFFFF),
    SurfacePressed = Color(0xFF262633),
    PrimaryPressed = Color(0xFFE66A00),
    PrimaryContainer = Color(0xFF33200D),
    Outline = Color(0x1FFFFFFF),
    Divider = Color(0x14FFFFFF),
    Skeleton = Color(0x14FFFFFF),
    Success = Color(0xFF4CAF50),
    Track = Color(0x33FFFFFF),
    ChapterDivider = Color(0x66FFFFFF),
    Scrim = Color(0x99000000),
    ScrimStrong = Color(0xE6000000),
    TrackUnderlay = Color(0x66000000),
    SkeletonHighlight = Color(0x1FFFFFFF),
    KnobHighlight = Color(0xFFF4F4F8),
    KnobBase = Color(0xFFB9BAC6),
    KnobEdge = Color(0xFF63646F),
    KnobRim = Color(0x73000000),
    // 2026-10-15 观感返工②：GlassFill 0x7314141A→0x26000000（45%→15% 黑）——tint 极淡靠 blur 透内容色
    //（Apple Music 参照：封面形状可辨）；GlassFillStrong 保持（SCRIM 档/弹层可读性红线）
    GlassFill = Color(0x26000000),
    GlassFillStrong = Color(0xB30F0F16),
    GlassBorder = Color(0x2EFFFFFF),
    GlassBorderHairline = Color(0x1FFFFFFF),  // 返工⑤：12% 白 hairline（GlassBorder 18% 白弱一档，暗色不劣化）
    GlassHighlight = Color(0x14FFFFFF),
    GlassSheen = Color(0x21FFFFFF),   // lens 轮：10%→13% 白（准绳4 主亮边强度略升一档）
    GlassInnerShadow = Color(0x1F000000),  // lens 轮：右下暗影 12% 黑（暗色模式主档）
    Error = Color(0xFFFF5252),
)

/** 浅色调色板（Material 3 惯例：品牌橙浅色下加深保对比，层级靠白底 + 黑 alpha 梯度） */
internal val LightPalette = NvPalette(
    Background = Color(0xFFF4F4F7),
    Surface = Color(0xFFFFFFFF),
    SurfaceElevated = Color(0xFFFFFFFF),
    Primary = Color(0xFFE05F00),
    Secondary = Color(0xFF3B6FD4),
    TextPrimary = Color(0xE6000000),
    TextSecondary = Color(0x99000000),
    TextDisabled = Color(0x61000000),
    SurfacePressed = Color(0xFFECECF1),
    PrimaryPressed = Color(0xFFC25400),
    PrimaryContainer = Color(0xFFFFE9D6),
    Outline = Color(0x1F000000),
    Divider = Color(0x14000000),
    Skeleton = Color(0x14000000),
    Success = Color(0xFF388E3C),
    Track = Color(0x33000000),
    ChapterDivider = Color(0x66000000),
    Scrim = Color(0x99000000),
    ScrimStrong = Color(0xE6000000),
    TrackUnderlay = Color(0x66000000),
    SkeletonHighlight = Color(0x1F000000),
    KnobHighlight = Color(0xFFFFFFFF),
    KnobBase = Color(0xFFDCDCE4),
    KnobEdge = Color(0xFFA8A8B4),
    KnobRim = Color(0x33000000),
    // 2026-10-15 观感返工②：GlassFill 0xB3FFFFFF→0x59FFFFFF（70%→35% 白）——亮板同调，去「白板」割裂感
    // rework2 精修（HIG clear 档）：0x59FFFFFF→0x4DFFFFFF（35%→30% 白）——blur 降 12dp 后内容透出更多，
    // 白 tint 再减一档控亮板泛白；标签可读性实拍回归（§11）钉定
    // 返工⑤（2026-10-15 第五项反馈）：0x4D→0x55（30%→33% 白）微升一档托浅背景上的玻璃量感，
    // 远离被用户否决的「白板」档（0xB3=70% 红线；上限 0x59 不越）
    GlassFill = Color(0x55FFFFFF),
    GlassFillStrong = Color(0xE6FFFFFF),
    GlassBorder = Color(0x33FFFFFF),
    GlassBorderHairline = Color(0x14000000),  // 返工⑤：8% 黑 hairline（亮色黑基——白描边浅背景不可辨）
    GlassHighlight = Color(0x0A000000),
    GlassSheen = Color(0x2EFFFFFF),   // lens 轮：13%→18% 白（准绳4 主亮边强度略升一档）
    GlassInnerShadow = Color(0x0F000000),  // lens 轮：右下暗影 6% 黑（亮色环带四周皆亮，12% 减半档）
    Error = Color(0xFFE53935),
)

val LocalNvPalette = staticCompositionLocalOf { DarkPalette }

/** 全 app 视觉 token 出口：getter 挂 @Composable 从当前调色板取值，调用点写法与旧静态对象完全一致 */
object NvColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Surface
    val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.SurfaceElevated
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Primary
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Secondary
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.TextPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.TextSecondary
    val TextDisabled: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.TextDisabled
    val SurfacePressed: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.SurfacePressed
    val PrimaryPressed: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.PrimaryPressed
    val PrimaryContainer: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.PrimaryContainer
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Outline
    val Divider: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Divider
    val Skeleton: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Skeleton
    val Success: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Success
    val Track: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Track
    val ChapterDivider: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.ChapterDivider
    val Scrim: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Scrim
    val ScrimStrong: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.ScrimStrong
    val TrackUnderlay: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.TrackUnderlay
    val SkeletonHighlight: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.SkeletonHighlight
    val KnobHighlight: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.KnobHighlight
    val KnobBase: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.KnobBase
    val KnobEdge: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.KnobEdge
    val KnobRim: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.KnobRim
    val GlassFill: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassFill
    val GlassFillStrong: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassFillStrong
    val GlassBorder: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassBorder
    val GlassBorderHairline: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassBorderHairline
    val GlassHighlight: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassHighlight
    val GlassSheen: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassSheen
    val GlassInnerShadow: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.GlassInnerShadow
    val Error: Color @Composable @ReadOnlyComposable get() = LocalNvPalette.current.Error
}

private fun nvColorScheme(p: NvPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.Primary,
        secondary = p.Secondary,
        background = p.Background,
        surface = p.Surface,
        surfaceVariant = p.SurfaceElevated,
        error = p.Error,
    )
} else {
    lightColorScheme(
        primary = p.Primary,
        secondary = p.Secondary,
        background = p.Background,
        surface = p.Surface,
        surfaceVariant = p.SurfaceElevated,
        error = p.Error,
    )
}

/**
 * [darkTheme] 由宿主传入：MainActivity = 用户偏好（novax_ui_prefs KEY_UI_THEME）+ 系统合成；
 * 播放器 Activity 固定 true（视频界面永远深色遮罩/白字）；未传时跟随系统。
 */
@Composable
fun NovaXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalNvPalette provides palette) {
        MaterialTheme(
            colorScheme = nvColorScheme(palette, darkTheme),
            content = content,
        )
    }
}
