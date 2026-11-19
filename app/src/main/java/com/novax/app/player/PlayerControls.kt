@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.novax.app.R
import com.novax.app.data.cast.CastMediaResolver
import com.novax.app.data.cast.CastUiState
import com.novax.app.data.cast.CastSessionHolder
import com.novax.app.ui.cast.NvCastDeviceSheet
import com.novax.app.ui.cast.NvCastMeteredDialogHost
import com.novax.app.ui.cast.rememberNvCastGateState
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.NvBannerKind
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvInlineBanner
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.components.NvLoader
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvSecondaryButton
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.drawNvActiveTrack
import com.novax.app.ui.components.drawNvGrooveTrack
import com.novax.app.ui.components.drawNvKnob
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

// ============================================================
// 从 PlayerScreen.kt 机械切出（2026-09-28 审计 A：巨型文件拆分，零逻辑改动，private→internal）
// ============================================================

/** 可见宽吸附的键位网格：动作键均为 48dp，封顶宽向下取整到 48dp 整倍数——滚动边界永远落在
 *  两枚键之间，永不出半截键（2026-10-12 二轮验收反馈：半截键露在渐隐下不但误导还能点响） */
private const val NvActionSlotDp = 48f

/**
 * 顶栏动作区「可滑动」暗示容器（2026-10-12 用户需求：竖屏动作区此前只有裸
 * horizontalScroll 零视觉线索，用户不知道右侧还藏着键；主播放器 NvPlayerTopBar 与
 * RMVB ControlsTop 共用本组件）。自动演示动画被用户裁决否决（2026-10-12 D1）。
 * ① 整键对齐——封顶宽向下吸附 [NvActionSlotDp] 键位网格，滚动边界永远落在两键之间，
 *    永不出半截键（二轮验收反馈：半截键露在渐隐下不但误导还能点响）；
 * ② 边缘渐隐——未滚到头的一侧叠 20dp 黑色渐隐（方向随滚动位置翻转，滚到头即消失）；
 * ③ 底部迷你滚动条——三轮反馈（2026-10-13 用户提议）：呼吸箭头像按钮、呼吸圆点存在感
 *    弱，改为图标下方 3dp 轨道+滑块（进度条语言自带「可滑」心智，且同时表达位置与总量，
 *    与底部播放进度条同一心智模型）。2026-10-12 四轮验收收窄：轨道 48dp（=单键宽，
 *    NvActionSlotDp）锚动作区右缘——原轨道=整个动作区(~240dp)比按键宽 5 倍像误画；
 *    滑块带 8dp 可感知下限。仅溢出时出现，随滚动移动；指示器不带手势（拖动
 *    以后续反馈再议——轨道只有 3dp，手势热区会吃图标触控）。
 * 横屏空间富余装得下时三重暗示零副作用（封顶只约束 max 且吸附后 ≥ 内容宽时不裁）。
 * 已知窄带副作用（spec §4.3）：内容宽落在 (吸附宽, 原封顶] 窄带时旧版装得下、新版出现
 * 滚动+暗示，属可接受新增。
 */
@Composable
internal fun NvScrollableActionRow(
    actionsMaxWidth: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    // 封顶宽向下吸附 48dp 键位网格：floor(maxWidth/48)×48
    val snappedMaxWidth = (floor(actionsMaxWidth.value / NvActionSlotDp) * NvActionSlotDp).dp
    Box(
        Modifier
            .widthIn(max = snappedMaxWidth)
            .drawWithContent {
                drawContent()
                val edge = 20.dp.toPx()
                val hasOverflow = scroll.canScrollForward || scroll.canScrollBackward
                if (scroll.canScrollForward) {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                            startX = size.width - edge,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - edge, 0f),
                        size = Size(edge, size.height),
                    )
                }
                if (scroll.canScrollBackward) {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.85f),
                            1f to Color.Transparent,
                            startX = 0f,
                            endX = edge,
                        ),
                        size = Size(edge, size.height),
                    )
                }
                // 底部迷你滚动条：图标字形（26dp 居中）下方 ~10dp 空、48dp 盒子的底部 3dp
                // 视觉上本就空白，画这里不加高顶栏；滑块几何按 视口/内容总量 精确换算
                if (hasOverflow) {
                    // 2026-10-12 用户验收:轨道=整个动作区(~240dp)比按键宽 5 倍,像误画的进度条。
                    // 改为 48dp(=单键宽)固定轨道锚动作区右缘;滑块等比换算 + 8dp 可感知下限。
                    // 画在外层静态 Box 不随内容滚动;图标行 4dp 底部呼吸间隙的竖向几何不变
                    val trackH = 3.dp.toPx()
                    val trackW = NvActionSlotDp.dp.toPx()
                    val contentPx = size.width + scroll.maxValue
                    if (contentPx > 0f && scroll.maxValue > 0) {
                        val ratio = (size.width / contentPx).coerceIn(0.08f, 1f)
                        val thumbW = (ratio * trackW).coerceAtLeast(8.dp.toPx()).coerceAtMost(trackW)
                        val x = (scroll.value.toFloat() / scroll.maxValue) * (trackW - thumbW)
                        val trackLeft = size.width - trackW
                        drawRoundRect(
                            Color.White.copy(alpha = 0.15f),
                            topLeft = Offset(trackLeft, size.height - trackH),
                            size = Size(trackW, trackH),
                            cornerRadius = CornerRadius(trackH / 2f),
                        )
                        drawRoundRect(
                            Color.White.copy(alpha = 0.55f),
                            topLeft = Offset(trackLeft + x, size.height - trackH),
                            size = Size(thumbW, trackH),
                            cornerRadius = CornerRadius(trackH / 2f),
                        )
                    }
                }
            },
    ) {
        // 图标行加 4dp 底部内边距：滚动条与图标之间留出呼吸间隙（2026-10-13 验收反馈「贴的太紧」），
        // 行高变 52dp、图标整体上移 ~2dp，轨道画在盒子最底 3dp
        Row(
            Modifier
                .horizontalScroll(scroll)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * T5 顶栏收拢（2026-11-15）：顶栏动作键枚举——NvPlayerTopBar 四维参数化的「顺序」维载体。
 * 键的「有无」由可空回调决定（onPip/onQuality/onCast 传 null 即不渲染），顺序由 [order] 列表
 * 决定，两侧各保现状顺序（收拢零重排——主 [NvTopBarOrderMain] 字幕在音轨前、RMVB
 * [NvTopBarOrderRmvb] 音轨在字幕前，spec 2026-11-15 §5.3 / 二轮评审 P2-5）。
 */
internal enum class NvTopAction { SUBTITLE, AUDIO, ASPECT, ORIENTATION, MIRROR, ROTATE, PIP, QUALITY, INFO, CAST, MORE }

/** 主播放器现状顺序（收拢时快照，与 PlayerScreen 调用现状逐键一致；PIP/QUALITY/CAST 键在、渲染与否看回调） */
internal val NvTopBarOrderMain: List<NvTopAction> = listOf(
    NvTopAction.SUBTITLE, NvTopAction.AUDIO, NvTopAction.ASPECT, NvTopAction.ORIENTATION,
    NvTopAction.MIRROR, NvTopAction.ROTATE, NvTopAction.PIP, NvTopAction.QUALITY,
    NvTopAction.INFO, NvTopAction.CAST, NvTopAction.MORE,
)

/** RMVB 兜底页现状顺序（收拢时快照，与收拢前 RmvbPlayerActivity.ControlsTop 逐键一致；无 PIP/QUALITY/CAST） */
internal val NvTopBarOrderRmvb: List<NvTopAction> = listOf(
    NvTopAction.ASPECT, NvTopAction.ORIENTATION, NvTopAction.MIRROR, NvTopAction.ROTATE,
    NvTopAction.AUDIO, NvTopAction.SUBTITLE, NvTopAction.INFO, NvTopAction.MORE,
)

@Composable
internal fun NvPlayerTopBar(
    title: String,
    subtitle: String?,
    hasSubtitleTracks: Boolean,
    onBack: () -> Unit,
    onSubtitle: () -> Unit,
    onAudio: () -> Unit,
    onAspect: () -> Unit,
    /** 2026-09-18 用户反馈：画面旋转（0/90/180/270）恢复独立按键——之前按审计 P2-5 合并进
     *  画面比例面板，用户实测「点起来太麻烦」，撤销合并 */
    onVideoRotate: () -> Unit,
    /** 2026-10-11 用户需求：画面镜像(水平翻转)开关——同先例走独立按键；filled/outlined 表达开关态
     *  （先例：FastForward/Rewind 用 filled/outlined 表态） */
    videoMirrored: Boolean,
    onVideoMirror: () -> Unit,
    /** C12 反馈：方向切换（横屏→竖屏→跟随视频循环，Toast 提示） */
    onOrientation: () -> Unit,
    /** 2026-09-18：画中画入口从底栏上移——底栏改中心对称布局（锁定|中央簇|倍速），
     *  右缘多一枚图标会破坏镜像；顶栏个别图标密度用户已认可（「个别图标无所谓」） */
    onPip: (() -> Unit)? = null,
    /** M3 清晰度面板入口（2026-10-07，仅 Jellyfin 源传入；null 隐藏按钮，非 Jellyfin 源 UI 零变化） */
    onQuality: (() -> Unit)? = null,
    /** 2026-09-15 用户需求：视频信息（分辨率/帧率/编码/音轨） */
    onInfo: () -> Unit,
    /** 2026-09-17 功能项：AB 循环 + 睡眠定时聚合入口 */
    onMore: () -> Unit,
    /** C13 投屏：非 null 显示投屏钮（有 playUrl 才传） */
    onCast: (() -> Unit)? = null,
    /** T5 顶栏收拢（2026-11-15）：动作键渲染顺序——默认主播放器现状序；RMVB 传 [NvTopBarOrderRmvb]
     *  保其现状序（收拢零重排）；null 回调的键（PIP/QUALITY/CAST）即使在列表中也跳过不渲染 */
    order: List<NvTopAction> = NvTopBarOrderMain,
    /** T5 cd 带当前值口径（spec §5.3，信息量只增不减）：非 null = 比例键整条 contentDescription
     *  （RMVB 传 stringResource(player_cd_aspect_current, 当前值)）；null 退回 player_sheet_aspect（主播放器现状） */
    aspectCurrent: String? = null,
    /** T5 cd 带当前值口径：非 null = 镜像键整条 cd（RMVB 传「镜像(当前 开/关)」）；null 退回 player_cd_mirror */
    mirrorCurrent: String? = null,
    /** T5 cd 带当前值口径：非 null = 旋转键整条 cd（RMVB 传「旋转(当前 N°)」）；null 退回 player_cd_rotate */
    rotateCurrent: String? = null,
    /** T5 收拢发现项：RMVB 更多键 cd 现状是 player_cd_more_rmvb（「更多(AB 循环/睡眠定时)」）与主播放器
     *  player_sheet_more 不同——非 null 覆盖保 RMVB 现状；null 用主播放器口径（两侧 cd 逐字不变） */
    moreContentDescription: String? = null,
) {
    // N4 顶栏竖屏适配（2026-10-09 用户需求：竖屏"这一撮"要大、放不下就拖动）：
    // 返回键固定 + 标题 weight(1f) 且 widthIn(min=96dp 最小可读宽)+ellipsis；
    // 动作区不 weight、按内容宽包 horizontalScroll——竖屏放不下可横向拖动、图标不再被挤压，
    // 横屏空间富余时无滚动零副作用；按钮集合/顺序/回调零变更（spec 批次二 N4-1）。
        // 实现注记：Row 里 weight 子项在空间不足时被分配 0 宽，而 widthIn(min) 会被入参 max
        // （=0）coerce 掉、标题照样被压没（旧布局同样压没）；故用 BoxWithConstraints 把动作区
        // 显式封顶在「总宽-返回-标题最小宽」，标题 weight 分配必得 ≥96dp，动作区其余空间内滚动。
        // 2026-10-14 用户需求（截图圈注）：竖屏标题改「少展示」——窄幅定宽 48dp 封顶（用户红笔
        // 标注截断位置 ≈46dp；48dp=一枚键位格，动作区封顶放宽后经 48dp 网格吸附恰好多露一枚键），
        // 横屏（宽幅）维持 96dp 仅保底、标题可长展示不受限
        BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            // 2026-09-16 UI 审计 P2：刘海/挖孔机型（shortEdges 生效后）两端图标避开刘海
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 标题宽口径（与下方动作区封顶配对）：窄幅（竖屏/分屏）48dp 定宽、宽幅 96dp 仅保底
        val narrow = maxWidth < 480.dp
        val titleWidth = if (narrow) 48.dp else 96.dp
        val actionsMaxWidth = maxWidth - 48.dp - titleWidth
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.player_back),
                    tint = Color.White,
                    // N4：图标视觉 24→26dp（"大小要大些"），触控热区 48dp 不变
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    // 标题宽口径与动作区封顶配对（spec N4-1）：窄幅 min=max 定宽，weight 分配的
                    // 多余空间不再回填标题；宽幅仅 96dp 保底不受限
                    .widthIn(min = titleWidth, max = if (narrow) titleWidth else Dp.Unspecified)
            ) {
                Text(
                    title,
                    style = NvTypography.TitleMedium,
                    color = NvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(it, style = NvTypography.LabelMedium, color = NvColors.TextSecondary, maxLines = 1)
                }
            }
            // 动作区：按内容宽 + 横向可拖动 + 可滑动静态暗示（半截图标/边缘渐隐，
            // 见 NvScrollableActionRow）；竖屏封顶 96dp 标题宽之外的全部空间。
            // T5 收拢：渲染顺序/键集由 order 列表驱动（主/RMVB 各保现状序，收拢零重排）；
            // PIP/QUALITY/CAST 三键回调为 null 时跳过（键的有无不靠列表、仍由回调 null 语义决定——
            // 列表只管「排在哪里」；历史注释里的相对位置描述以各顺序常量为准）
            NvScrollableActionRow(actionsMaxWidth = actionsMaxWidth) {
                order.forEach { action ->
                    when (action) {
                        NvTopAction.SUBTITLE -> IconButton(onClick = onSubtitle, modifier = Modifier.size(48.dp)) {
                            Icon(
                                // 2026-09-16 UI 审计 P2-5：无轨时保持白色可点观感（原 60% 灰像禁用）
                                if (hasSubtitleTracks) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionOff,
                                stringResource(R.string.player_sheet_subtitles),
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        NvTopAction.AUDIO -> IconButton(onClick = onAudio, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Audiotrack, stringResource(R.string.player_sheet_audio), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        NvTopAction.ASPECT -> IconButton(onClick = onAspect, modifier = Modifier.size(48.dp)) {
                            // T5 cd 带当前值口径：RMVB 传 aspectCurrent（player_cd_aspect_current 整串）；
                            // null（主播放器现状）退回 player_sheet_aspect——两侧 cd 逐字不变
                            Icon(Icons.Outlined.AspectRatio, aspectCurrent ?: stringResource(R.string.player_sheet_aspect), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        // 返工⑤（2026-10-15 用户第五项反馈）：「画面旋转」与「方向切换（竖屏切换）」两钮
                        // 位置互换（互换后=比例/方向切换/镜像/旋转），图标/语义/onClick 全部不变；
                        // T5 收拢后相对位置由 NvTopBarOrderMain/Rmvb 常量承载（两常量同序段）
                        NvTopAction.ORIENTATION -> IconButton(onClick = onOrientation, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ScreenRotation, stringResource(R.string.player_cd_orientation), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        NvTopAction.MIRROR -> IconButton(onClick = onVideoMirror, modifier = Modifier.size(48.dp)) {
                            // T5 cd 带当前值口径：RMVB 传 mirrorCurrent（player_cd_mirror_current 整串）；
                            // null 退回 player_cd_mirror（主播放器现状）
                            Icon(
                                if (videoMirrored) Icons.Filled.Flip else Icons.Outlined.Flip,
                                mirrorCurrent ?: stringResource(R.string.player_cd_mirror),
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        NvTopAction.ROTATE -> IconButton(onClick = onVideoRotate, modifier = Modifier.size(48.dp)) {
                            // T5 cd 带当前值口径：RMVB 传 rotateCurrent（player_cd_rotate_current 整串）；
                            // null 退回 player_cd_rotate（主播放器现状）
                            Icon(Icons.Outlined.Rotate90DegreesCcw, rotateCurrent ?: stringResource(R.string.player_cd_rotate), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        // 2026-09-18：画中画从底栏上移顶栏（底栏中心对称化）；null 即不渲染
                        NvTopAction.PIP -> if (onPip != null) {
                            IconButton(onClick = onPip, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.player_cd_pip), tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                        // M3 清晰度面板入口（仅 Jellyfin 源可见；相对位置在顺序常量中=信息键前）
                        NvTopAction.QUALITY -> if (onQuality != null) {
                            IconButton(onClick = onQuality, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Tune, stringResource(R.string.player_sheet_quality), tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                        NvTopAction.INFO -> IconButton(onClick = onInfo, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Info, stringResource(R.string.player_info_title), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        // C13 投屏：null 即不渲染（有 playUrl 才传）
                        NvTopAction.CAST -> if (onCast != null) {
                            IconButton(onClick = onCast, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Cast, stringResource(R.string.player_cd_cast), tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                        NvTopAction.MORE -> IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                            // T5 收拢发现项：RMVB 现状 cd=player_cd_more_rmvb（含 AB 循环/睡眠定时说明），
                            // 经 moreContentDescription 传入保两侧 cd 逐字不变
                            Icon(Icons.Outlined.MoreVert, moreContentDescription ?: stringResource(R.string.player_sheet_more), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 底部栏（C6 §1.2） ----------------

@Composable
internal fun NvPlayerBottomBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    /** 0=未锁（空心 LockOpen）1=半锁（实心 LockOpen）2=全锁（实心 Lock），图标随模式切换 */
    lockMode: Int = 0,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    /** 2026-09-18：快进/快退步长（秒）——设置档位 10/15/25/30，按钮小字随档位；与双击手势同一步长 */
    skipSec: Int,
    onSkipBy: (Long) -> Unit = {},
    onSpeed: () -> Unit,
    onLock: () -> Unit,
    /** 2026-09-15：文件夹内上一个/下一个（null = 无队列或已在边界，按钮置灰） */
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    /** 2026-09-15 修复：进度条开始被拖动（宿主据此收起父层手势 seek 浮层） */
    onDragStart: () -> Unit = {},
    /** 2026-10-07 band 计量修正：透传滑块热区高度（原量整个控制层 Column 致 band 半屏宽，见 NvChapterSlider） */
    onSliderBandPx: (Float) -> Unit = {},
) {
    // 2026-11-15 经典档紧凑化：bottom 10→6dp（spec §3.1，布局与比例零变化）
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        NvChapterSlider(
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = durationMs,
            chapters = emptyList(),   // 章节数据接入后传入
            onSeek = onSeek,
            onDragStart = onDragStart,
            onSliderBandPx = onSliderBandPx,
        )
        // 2026-09-18 用户需求三：按钮行改中心对称（锁定 | 上集·快退·暂停·快进·下集 | 倍速），
        // 暂停严格居中、簇内等距；主播放器与 RMVB 兜底播放器共用同一组件（界面同步）
        NvPlayerControlRow(
            isPlaying = isPlaying,
            speed = speed,
            lockMode = lockMode,
            skipSec = skipSec,
            onTogglePlay = onTogglePlay,
            onSkipBy = onSkipBy,
            onSpeed = onSpeed,
            onLock = onLock,
            onPrev = onPrev,
            onNext = onNext,
        )
    }
}

/**
 * 底栏七键双布局（按可用宽分档）：
 * - 横屏/宽幅（≥480dp）：**2026-09-18/09-19 原版全行等距 SpaceBetween**——2026-10-12 用户验收
 *   反馈横屏改三段簇属画蛇添足，整行还原；宽度充足七键不溢出，SpaceBetween 下暂停（第 4/7 键）
 *   天然严格居中、全行等距即用户认可的原版观感。
 * - 窄幅（竖屏/分屏竖栏，<480dp）：三段中心锚定——暂停 `align(Center)` 绝对居中（=屏幕中线），
 *   左簇（锁定|上集|快退）贴左缘、右簇（快进|下集|倍速）贴右缘。旧版整行 SpaceBetween 在窄竖屏
 *   溢出：末位倍速槽被压裁、整行左挤、暂停偏右（用户实测截图）；三段锚定后任何窄幅宽度下暂停恒
 *   在正中、裁键不可能再发生。侧簇 cap 语义：cap 优先且无下限，极窄窗口允许 <42dp，保不相触
 *   优先；取等允许贴平不允许重叠。注：padding(horizontal=16) 在 BoxWithConstraints 上，
 *   maxWidth 不含左右 padding，同名义阈值（380/330）的屏宽口径右移 32dp。
 * 七键槽位只写一遍（本地 @Composable 槽函数），两套排布共用同实现；四个小键统一
 * 「图标 + 下方小字」结构，风格一致。主播放器与 RMVB 兜底播放器共用本组件。
 */
/**
 * 窄幅底栏按键档位唯一口径（2026-10-14 抽出：NvPlayerControlRow 排布与 NvChapterSlider 进度条
 * 对齐共用，改档位只动这里）。[maxWidth] = 行级 16dp 横向 padding 之后的可用宽，与
 * NvPlayerControlRow 的 BoxWithConstraints 同口径。
 * 返回 (暂停键尺寸, 侧簇键尺寸)：侧簇上限 = (可用宽-暂停键) 两半平摊、簇内 2dp 间隙 ×2、
 * 3 键均分——超出即收缩，保证左右簇与居中暂停键永不相触（3×btn + 2×gap ≤ (宽-暂停键)/2）。
 */
internal fun nvNarrowControlMetrics(maxWidth: Dp): Pair<Dp, Dp> {
    // 2026-11-15 经典档紧凑化（spec §3.1）：pause 62/56/50 → 50/45/40，btn 52/46/42 → 42/38/36
    val pauseSize = when {
        maxWidth >= 380.dp -> 50.dp
        maxWidth >= 330.dp -> 45.dp
        else -> 40.dp
    }
    val clusterGap = 2.dp
    val sideBtnCap = ((maxWidth - pauseSize) / 2 - clusterGap * 2) / 3f
    val btn = minOf(if (maxWidth >= 380.dp) 42.dp else if (maxWidth >= 330.dp) 38.dp else 36.dp, sideBtnCap)
    return pauseSize to btn
}

/** 窄幅行图标：btn×0.48，最小档（36dp）视觉过小 → 固定 18dp 下限（spec §3.1 / 评审 P2-1）。 */
internal fun nvRowIconFor(btn: Dp): Dp = (btn * 0.48f).coerceAtLeast(18.dp)

@Composable
internal fun NvPlayerControlRow(
    isPlaying: Boolean,
    speed: Float,
    /** 0=未锁（空心 LockOpen）1=半锁（实心 LockOpen）2=全锁（实心 Lock），图标随模式切换 */
    lockMode: Int = 0,
    skipSec: Int,
    onTogglePlay: () -> Unit,
    onSkipBy: (Long) -> Unit,
    onSpeed: () -> Unit,
    onLock: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            // 返工⑥（2026-10-16 用户真机横屏截图）：72dp 磨砂大圆钮（PauseSlot/GlassFillStrong）
            // 中心虽与 15s/上集同排，但直径大一圈、下缘几乎贴屏——RMVB 宿主此行仅 2dp 竖向
            // padding（圆钮距屏底 2dp，手势条一压即「下半截被截断」），主播放器也仅
            // Column(bottom=10)+2=12dp，磨砂填充后暴露明显。改不对称 padding：bottom 抬到
            // 14dp——七键仍共控制行中线（整行同步上移），主播放器圆钮距屏底 24dp、RMVB 14dp，
            // 横屏/竖屏/窄幅三档同函数一并生效；maxWidth 口径不变（nvNarrowControlMetrics 不受影响）
            // 2026-11-15 紧凑化：top 2→1 / bottom 14→9（spec §3.1，布局与比例零变化）
            .padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 9.dp),
    ) {
        val wide = maxWidth >= 480.dp

        // ---- 七键槽位（尺寸按布局档位传入，两套排布共用） ----
        @Composable
        fun LockSlot(btn: Dp, rowIcon: Dp) {
            IconButton(onClick = onLock, modifier = Modifier.size(btn)) {
                Icon(
                    when (lockMode) {
                        2 -> Icons.Filled.Lock
                        1 -> Icons.Filled.LockOpen     // 半锁：实心开锁（与未锁的空心开锁区分）
                        else -> Icons.Outlined.LockOpen
                    },
                    stringResource(R.string.player_cd_lock),
                    Modifier.size(rowIcon),
                    tint = Color.White,
                )
            }
        }
        @Composable
        fun PrevSlot(btn: Dp, rowIcon: Dp) {
            NvClusterButton(
                icon = Icons.Outlined.SkipPrevious,
                caption = stringResource(R.string.player_prev_episode),
                enabled = onPrev != null,
                contentDescription = if (onPrev != null) stringResource(R.string.player_cd_prev)
                else stringResource(R.string.player_cd_prev_none),
                size = btn,
                iconSize = rowIcon,
                onClick = { onPrev?.invoke() },
            )
        }
        @Composable
        fun RewindSlot(btn: Dp, rowIcon: Dp) {
            NvSkipStepButton(rewind = true, sec = skipSec, size = btn, iconSize = rowIcon) {
                onSkipBy(-skipSec * 1000L)
            }
        }
        @Composable
        fun ForwardSlot(btn: Dp, rowIcon: Dp) {
            NvSkipStepButton(rewind = false, sec = skipSec, size = btn, iconSize = rowIcon) {
                onSkipBy(skipSec * 1000L)
            }
        }
        @Composable
        fun NextSlot(btn: Dp, rowIcon: Dp) {
            NvClusterButton(
                icon = Icons.Outlined.SkipNext,
                caption = stringResource(R.string.player_next_episode),
                enabled = onNext != null,
                contentDescription = if (onNext != null) stringResource(R.string.player_cd_next)
                else stringResource(R.string.player_cd_next_none),
                size = btn,
                iconSize = rowIcon,
                onClick = { onNext?.invoke() },
            )
        }
        @Composable
        fun SpeedSlot(btn: Dp, rowIcon: Dp) {
            // 倍速槽（2026-10-09 用户需求：悬空角标突兀——非 1x 时倍速文本直接融进按钮，主色激活态；
            // 1x 恢复 Speed 图标。整槽可点开倍速面板，contentDescription 带当前倍速）
            // i18n：semantics 块非组合上下文，资源解析提升到组合期
            val speedCd = if (speed != 1f) stringResource(R.string.player_speed_current, formatSpeedLabel(speed))
            else stringResource(R.string.player_speed)
            Box(
                Modifier
                    .size(btn)
                    .clickable(onClick = onSpeed)
                    .semantics {
                        contentDescription = speedCd
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (speed != 1f) {
                    Text(
                        formatSpeedLabel(speed),
                        style = NvTypography.LabelLarge,
                        color = NvColors.Primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Icon(Icons.Outlined.Speed, null, Modifier.size(rowIcon), tint = Color.White)
                }
            }
        }
        @Composable
        fun PauseSlot(pauseSize: Dp, pauseIcon: Dp) {
            // Liquid Glass（spec §10 浮层清单 1/12）：暂停圆钮 SurfaceElevated 实底→NvGlassChip
            //（同窗模糊+描边提亮；原 clickable 语义迁入 onClick，不可丢弃）
            // 返工⑤（2026-10-15 用户第一项反馈）：清玻璃圆钮压视频画面几乎隐形——改 GlassFillStrong
            // 磨砂强填充（保留玻璃形状/光效/描边，不再透视画面），播放/暂停图标对比度随之回归
            NvGlassChip(
                modifier = Modifier.size(pauseSize),
                shape = RoundedCornerShape(pauseSize / 2),
                fill = NvColors.GlassFillStrong,
                fillOverlay = true,
                onClick = onTogglePlay,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                    Modifier.size(pauseIcon).align(Alignment.Center),
                    tint = NvColors.TextPrimary,
                )
            }
        }

        if (wide) {
            // 横屏/宽幅：原版全行等距（2026-11-15 紧凑化：侧键 60/28→48/24，暂停 72/34→58/30，spec §3.1）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LockSlot(48.dp, 24.dp)
                PrevSlot(48.dp, 24.dp)
                RewindSlot(48.dp, 24.dp)
                PauseSlot(58.dp, 30.dp)
                ForwardSlot(48.dp, 24.dp)
                NextSlot(48.dp, 24.dp)
                SpeedSlot(48.dp, 24.dp)
            }
        } else {
            // 窄幅（竖屏/分屏竖栏）：三段中心锚定——暂停绝对居中，左右簇贴边防裁键
            //（档位唯一口径见 [nvNarrowControlMetrics]，NvChapterSlider 进度条对齐共用同一函数）
            val (pauseSize, btn) = nvNarrowControlMetrics(maxWidth)
            val rowIcon = nvRowIconFor(btn)
            val clusterGap = 2.dp

            // 暂停主键：绝对居中（屏幕中线），任何窄幅宽度/档位下不再偏移
            // Liquid Glass（spec §10 浮层清单 2/12）：同 PauseSlot——NvGlassChip 替换实底；
            // 返工⑤：同换 GlassFillStrong 磨砂强填充（压画面可辨，与宽幅 PauseSlot 一致）
            NvGlassChip(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(pauseSize),
                shape = RoundedCornerShape(pauseSize / 2),
                fill = NvColors.GlassFillStrong,
                fillOverlay = true,
                onClick = onTogglePlay,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                    Modifier.size(pauseSize * 0.48f).align(Alignment.Center),
                    tint = NvColors.TextPrimary,
                )
            }
            Row(
                Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(clusterGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LockSlot(btn, rowIcon)
                PrevSlot(btn, rowIcon)
                RewindSlot(btn, rowIcon)
            }
            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(clusterGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForwardSlot(btn, rowIcon)
                NextSlot(btn, rowIcon)
                SpeedSlot(btn, rowIcon)
            }
        }
    }
}

/** 2026-10-12 用户验收:上下集不可用时 38% 白(TextDisabled)黑底上几乎隐形,像按钮消失了。
 *  提亮到 50% 局部色;**严禁动 NvColors.TextDisabled token**(17 文件全局消费) */
private val NvClusterDisabled = Color.White.copy(alpha = 0.5f)

/** 中央簇通用小键（上集/下集）：与快进快退键同构（图标 + 下方小字），置灰态用局部提亮色 NvClusterDisabled。
 *  caption=null 纯图标（2026-11-19 用户拍板：极简底栏去说明文字，整条变薄）；既有调用方传非空不受影响 */
@Composable
internal fun NvClusterButton(
    icon: ImageVector,
    caption: String?,
    enabled: Boolean,
    contentDescription: String,
    size: Dp,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size), enabled = enabled) {
        if (caption == null) {
            Icon(
                icon,
                contentDescription,
                Modifier.size(iconSize),
                tint = if (enabled) Color.White else NvClusterDisabled,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    icon,
                    contentDescription,
                    Modifier.size(iconSize),
                    tint = if (enabled) Color.White else NvClusterDisabled,
                )
                Text(
                    caption,
                    style = NvTypography.LabelSmall,
                    color = if (enabled) Color.White.copy(alpha = 0.85f) else NvClusterDisabled,
                )
            }
        }
    }
}

// 2026-09-18 用户需求：快进/快退步长键（步长设置 10/15/25/30s，小字随档位显示）。
// 图标库只有 5/10/30s 变体、无 15/25s——统一用双箭头 + 下方「Ns」小字组合，
// 与上集/下集键同构（图标 + LabelSmall 小字），四键风格一致。
// showLabel=false 纯图标（2026-11-19 用户拍板：极简底栏去说明文字；步长仍由 sec 参数驱动）
@Composable
internal fun NvSkipStepButton(
    rewind: Boolean,
    sec: Int,
    size: Dp,
    iconSize: Dp = 22.dp,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        if (showLabel) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (rewind) Icons.Outlined.FastRewind else Icons.Outlined.FastForward,
                    if (rewind) stringResource(R.string.player_cd_rewind_sec, sec) else stringResource(R.string.player_cd_forward_sec, sec),
                    Modifier.size(iconSize),
                    tint = Color.White,
                )
                Text("${sec}s", style = NvTypography.LabelSmall, color = Color.White.copy(alpha = 0.85f))
            }
        } else {
            Icon(
                if (rewind) Icons.Outlined.FastRewind else Icons.Outlined.FastForward,
                if (rewind) stringResource(R.string.player_cd_rewind_sec, sec) else stringResource(R.string.player_cd_forward_sec, sec),
                Modifier.size(iconSize),
                tint = Color.White,
            )
        }
    }
}

/** NvChapterSlider 视觉模式（spec §5.2）：CLASSIC=粗轨+章节刻度+金属旋钮；LINE=贴缘细线（minimal 常驻/唤起与 streaming 底线共用）。 */
enum class NvSliderVisual { CLASSIC, LINE }

/**
 * line 细线模式未播段专用色（2026-11-15 三星真机缺陷根修）：白 50%。
 * classic 未播段用 NvColors.Track（暗板 20% 白），设计前提=「TrackUnderlay 40% 黑衬底压亮画面」；
 * 叠深色/带黑边视频时 20% 白≈不可辨（用户实证：整条线只剩左侧一小段橙）。LINE 模式宿主侧
 * 已统一 Scrim 黑块打底（minimal 底栏 Column 接线/streaming 底栏同构），细线改 50% 白保证
 * 黑底上可辨。**classic 恒用 NvColors.Track 零变化**——LINE 分支内取色分流（非参数化），
 * 调用点零改动；streaming 底线同走 LINE 连带变清晰（根修备案可接受）。
 */
internal val NvLineTrackUnplayed: Color = Color.White.copy(alpha = 0.5f)

/** 任务3：NvChapterSlider 换皮口径的可纯断言量（trackYAnchor 依赖 IntSize/Density 不可 JVM 测，故只收这三个）。
 *  T6/T7 依赖此签名接线。 */
data class NvSliderMetrics(val hPaddingDp: Float, val showsTimeRow: Boolean, val showsThumb: Boolean)

internal fun nvSliderLineMetrics(mode: NvSliderVisual): NvSliderMetrics = when (mode) {
    NvSliderVisual.CLASSIC -> NvSliderMetrics(16f, showsTimeRow = true, showsThumb = true)
    NvSliderVisual.LINE -> NvSliderMetrics(0f, showsTimeRow = false, showsThumb = false)
}

// ---------------- 三层进度条 NvChapterSlider（C6 §1.2） ----------------
// 2026-09-18：internal——RMVB 兜底播放页复用同一进度条（用户反馈「libVLC 进度条和默认的不一样」，
// 之前兜底页临时用 Material Slider：样式/拖动预览/缓冲层均不同步）

@Composable
internal fun NvChapterSlider(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    chapters: List<Pair<Long, String>>,
    onSeek: (Long) -> Unit,
    /** 2026-09-15 修复：拖动开始回调（宿主据此收起父层手势 seek 浮层并避免双重 seek 预览） */
    onDragStart: () -> Unit = {},
    /** 2026-10-07 band 计量修正：上报滑块热区自身高度（+24dp slop 余量）供父层手势 band 退场判定——
     *  原量整个控制层 Column（含渐变过渡/横幅），竖屏下量出半屏高，屏幕中下部拖动全被误拒 */
    onSliderBandPx: (Float) -> Unit = {},
    /** 任务3（spec §5.2）：视觉模式——同一手势内核（tap-seek/拖动预览/缓冲层/band 计量）换皮。
     *  CLASSIC=默认，主播放器+RMVB 既有调用方零改动零视觉变化；LINE=贴缘细线（T6/T7 接线） */
    visual: NvSliderVisual = NvSliderVisual.CLASSIC,
    /** 任务3（spec §3.3）：LINE 模式拖动态轨道 3dp→6dp 加粗（streaming 底线）；CLASSIC 恒忽略 */
    thickenOnDrag: Boolean = false,
) {
    // 任务3：视觉口径（hPaddingDp/showsTimeRow/showsThumb），classic 与既有行为逐值相同
    val metrics = nvSliderLineMetrics(visual)
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableLongStateOf(0L) }
    // 2026-09-18 用户需求：轻点进度条落点即 seek——拇指短暂放大作按压反馈
    var tapPulse by remember { mutableStateOf(false) }
    // 轻点后到下一次位置心跳（500ms）之间继续显示落点，否则拇指会先跳回旧位置再跳回来
    //（审查 P3：positionMs 只随心跳更新；650ms > 500ms 保证心跳至少刷新一次）
    var tapHoldPos by remember { mutableStateOf(false) }
    val thumbHot = dragging || tapPulse
    val showTapPos = dragging || tapHoldPos
    // 2026-10-10 S2:静息 5dp 轨/12dp 旋钮(r6),拖动 8dp 轨/22dp 旋钮(r11)——旋钮静息也可见
    // （动画过渡，松手回弹——拖起来更有「握住」感的既有交互保留）
    val thumbRadius by animateDpAsState(if (thumbHot) 11.dp else 6.dp, label = "sliderThumb")
    val trackHeight by animateDpAsState(if (thumbHot) 8.dp else 5.dp, label = "sliderTrack")
    // 任务3（spec §3.3）：line 模式轨道宽——静息 3dp 贴缘细线，thickenOnDrag=true 且拖动态加粗 6dp
    //（streaming 底线）；条件带 LINE 使 classic 调用下恒为静息值不空转，classic 粗轨 5/8dp 动画零变化
    val lineTrackHeight by animateDpAsState(
        if (visual == NvSliderVisual.LINE && thickenOnDrag && dragging) 6.dp else 3.dp,
        label = "sliderLineTrack",
    )
    // 2026-09-16 审计 P2-9：durationMs 不能做 pointerInput key——起播早期时长「未知→就绪」
    // 会重启手势协程，onDragEnd/onDragCancel 不回调，dragging 永久卡 true（进度条停住不跟随）。
    // 改为常驻手势协程 + rememberUpdatedState 读最新时长。
    val currentDuration by rememberUpdatedState(durationMs)
    LaunchedEffect(tapPulse) {
        if (tapPulse) {
            // 任务3（spec §3.3）：轻点反馈脉冲时长——classic 240ms（旋钮放大）；line 600ms
            //（无旋钮可放大，脉冲改为驱动预览气泡短现 600ms 后淡出，见气泡处；tapHoldPos 的
            //  落点保持职责两模式不变——它管 playedFrac 防心跳跳闪，不参与气泡可见性）
            delay(if (visual == NvSliderVisual.LINE) 600 else 240)
            tapPulse = false
        }
    }
    LaunchedEffect(tapHoldPos) {
        if (tapHoldPos) {
            delay(650)
            tapHoldPos = false
        }
    }

    // 2026-10-14 用户需求：竖屏进度条长度=底栏按键区长度——轨道/时间行两端与锁定、倍速键的
    // 图标外缘对齐（旧版固定 16dp 内边距，图标因 48dp 盒子内居中还会再缩 ~13.5dp，进度条
    // 每边外溢一截）；宽幅横屏维持 16dp 原版观感。档位经 [nvNarrowControlMetrics] 与
    // NvPlayerControlRow 严格同源，任何窄幅宽度下进度条与按键区都恰好等长
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 与 NvPlayerControlRow 同口径：其 16dp 横向 padding 打在 BoxWithConstraints 内，
        // maxWidth 不含 padding，故此处补减 32dp 对齐比较基准
        val rowBasis = maxWidth - 32.dp
        val trackInset = if (rowBasis < 480.dp) nvNarrowControlMetrics(rowBasis).second * 0.26f else 0.dp
        // 任务3（spec §5.2）：横向 padding 由视觉口径驱动——classic 16dp（+trackInset 窄幅与
        // 按键区对齐口径不变）；line 0dp 贴缘满宽（trackInset 仅 classic 叠加）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.hPaddingDp.dp + if (visual == NvSliderVisual.CLASSIC) trackInset else 0.dp)
        ) {
        // 2026-09-18 用户反馈「进度条判定区域还是有点小」：热区 48dp → 56dp（4dp 轨道仍垂直居中）
        // 2026-11-15 紧凑化 56→48（用户拍板，spec §3.1）
        // 2026-10-07 band 计量修正（见 [onSliderBandPx]）：滑块热区高度 +24dp 触摸 slop 余量
        // band 计量借外部 Density（onGloballyPositioned 回调无 Density/组合作用域）
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onGloballyPositioned {
                    onSliderBandPx(with(density) { (it.size.height + 24.dp.roundToPx()).toFloat() })
                },
        ) {
            // 时间行移到进度条上方（2026-09-19 用户需求：时间在杆上方左侧）；原先在热区盒外，
            // 与垂直居中的轨道视觉间隔 ~28dp——2026-10-16 用户反馈「离进度条太远太靠上」，
            // 移入盒内贴轨展示（不占列高、不缩热区高度）
            // 任务3（spec §5.2）：line 模式不渲染时间行——时间由常驻层/顶栏承载
            if (metrics.showsTimeRow) {
                Text(
                    if (durationMs > 0) "${formatTime(positionMs)} / ${formatTime(durationMs)}"
                    else stringResource(R.string.player_live, formatTime(positionMs)),
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 2.dp, top = 4.dp),
                )
            }
            // DrawScope 不是 Composable 上下文（浅色模式改造）：主题 token 提升为局部值
            val cTrackUnderlay = NvColors.TrackUnderlay
            val cTrack = NvColors.Track
            val cPrimary = NvColors.Primary
            val cChapterDivider = NvColors.ChapterDivider
            val cPrimaryPressed = NvColors.PrimaryPressed
            val cKnobHighlight = NvColors.KnobHighlight
            val cKnobBase = NvColors.KnobBase
            val cKnobEdge = NvColors.KnobEdge
            val cKnobRim = NvColors.KnobRim
            Canvas(
                Modifier
                    .fillMaxSize()
                    // 2026-09-18 用户需求：轻点进度条 → 落点即 seek。tap 检测在 drag 之前：
                    // 轻点由它消费（同时阻止事件漏到父层手势误 toggle 控制层）；
                    // 拖动超过触摸 slop 后 tap 检测器自然让位，由下面的 drag 检测接管
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (currentDuration > 0) {
                                val target = timeFromX(offset.x, size.width.toFloat(), currentDuration)
                                dragPos = target
                                tapPulse = true
                                tapHoldPos = true
                                onSeek(target)
                            }
                        }
                    }
                    // 2026-09-18 用户实测「进度条很难拉」：pointerInput 原挂在 padding 之后，
                    // 手势区只剩 4dp 高的轨道线本身——移到 padding 前，触控区扩为整条热区高，
                    // 视觉轨道仍垂直居中不变（x 坐标无水平 padding，timeFromX 换算不受影响）
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                onDragStart()
                                dragPos = timeFromX(offset.x, size.width.toFloat(), currentDuration)
                            },
                            onDragEnd = {
                                dragging = false
                                onSeek(dragPos)   // 松手才 seek
                            },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            dragPos = timeFromX(change.position.x, size.width.toFloat(), currentDuration)
                        }
                    }
                    // 2026-09-20 用户需求：轨道锚定热区底部，贴近按钮行。热区不再加 padding
                    //（审核纠正：bottom padding 会把绘制区推回顶部），绘制区 = 全高（2026-11-15 紧凑化后 48dp）
            ) {
                val w = size.width
                // 2026-11-15 真机缺陷根修：未播段凹槽取色——LINE=专用 50% 白（黑底可辨，见
                // [NvLineTrackUnplayed]）；classic=NvColors.Track 原值零变化。缓冲段叠色/末端圆头
                // 同源自 grooveColor 派生，两档视觉层级（未播/缓冲）关系保持
                val grooveColor = if (visual == NvSliderVisual.LINE) NvLineTrackUnplayed else cTrack
                // 任务3：轨道高——classic 粗轨动画（5/8dp 不变）；line 3dp 细线（拖动加粗 6dp 见 [lineTrackHeight]）
                val th = (if (visual == NvSliderVisual.LINE) lineTrackHeight else trackHeight).toPx()
                // 轨道中心锚定：classic 距热区底 14dp（热态拇指半径 11dp,最远到约 26.5dp < 48dp 不裁切）；
                // line 贴缘距底 2.5dp（spec §5.2：3dp 细线的中心=外缘 1dp/内缘 4dp；拖动加粗 6dp 时
                // 下半 0.5dp 落到热区外被 Canvas 裁切=底边贴缘平切）；tap/drag 手势挂在 Modifier 链上
                // 覆盖整 48dp 热区且只读 x，不受绘制 y 影响
                val trackCenterY = if (visual == NvSliderVisual.LINE) {
                    size.height - 2.5.dp.toPx()
                } else {
                    size.height - 14.dp.toPx()
                }
                val trackTop = trackCenterY - th / 2f
                val trackCorner = CornerRadius(th / 2f)
                if (durationMs <= 0) {
                    // 2026-09-16 UI 审计 P1：20% 白轨道在亮画面上不可辨 → 叠 40% 黑衬底
                    drawRoundRect(cTrackUnderlay, topLeft = Offset(0f, trackTop), size = Size(w, th), cornerRadius = trackCorner)
                    drawNvGrooveTrack(grooveColor, Offset(0f, trackTop), Size(w, th))
                } else {
                    val bufferedFrac = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val playedFrac = if (showTapPos) {
                        (dragPos.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    }
                    // C12 反馈：未缓冲 Track 底轨 / 缓冲段（已播位置→缓冲末端）Track 半透明叠加+末端圆头 / 已播 Primary
                    // bufferedMs 随 500ms 心跳取 player.bufferedPosition（长按倍速期间 200ms）
                    drawRoundRect(cTrackUnderlay, topLeft = Offset(0f, trackTop), size = Size(w, th), cornerRadius = trackCorner)
                    drawNvGrooveTrack(grooveColor, Offset(0f, trackTop), Size(w, th))
                    if (bufferedFrac > playedFrac) {
                        drawRoundRect(
                            grooveColor.copy(alpha = 0.5f),
                            topLeft = Offset(w * playedFrac, trackTop),
                            size = Size(w * (bufferedFrac - playedFrac), th),
                            cornerRadius = trackCorner,
                        )
                        // 缓冲末端小圆头：收小降透明，避免与橙色拇指混淆成「两个拖拽点」
                        drawCircle(
                            grooveColor.copy(alpha = 0.35f),
                            radius = 1.5.dp.toPx(),
                            center = Offset(w * bufferedFrac, trackCenterY),
                        )
                    }
                    drawNvActiveTrack(cPrimary, cPrimaryPressed, Offset(0f, trackTop), Size(w * playedFrac, th))
                    // 章节段分隔
                    chapters.forEach { (start, _) ->
                        val x = w * (start.toFloat() / durationMs)
                        drawRect(cChapterDivider, topLeft = Offset(x, trackTop), size = Size(2.dp.toPx(), th))
                    }
                    // 拇指(2026-10-10 S2:金属旋钮,按压/拖动/轻点反馈时动画放大)；
                    // 任务3（spec §5.2）：line 模式无旋钮——轻点反馈由气泡短现承载（见下）
                    if (metrics.showsThumb) {
                        drawNvKnob(
                            center = Offset(w * playedFrac, trackCenterY),
                            radius = thumbRadius.toPx(),
                            highlight = cKnobHighlight,
                            base = cKnobBase,
                            edge = cKnobEdge,
                            rim = cKnobRim,
                        )
                    }
                }
            }
            // 拖动/轻点预览气泡（目标时间 + 相对偏移）
            // 任务3（spec §3.3）：line 模式无旋钮可放大——轻点反馈改为气泡短现：可见性由
            // tapPulse(600ms，见脉冲时长处) 驱动、可见期结束 fadeOut 淡出，拖动态实时跟随；
            // classic 分支显隐逻辑零变化（if 直接显隐、瞬时）
            @Composable fun dragBubble(modifier: Modifier) {
                val delta = dragPos - positionMs
                Text(
                    "${formatTime(dragPos)}  ${if (delta >= 0) "+" else "-"}${formatTime(abs(delta))}",
                    style = NvTypography.LabelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NvColors.TextPrimary,
                    modifier = modifier
                        // 2026-11-15 紧凑化：48dp 热区下旋钮顶 23dp vs 气泡底 30dp 重叠 7dp，
                        // 上移消重叠——评审 P1-2（气泡是 Text 非 Canvas 绘制，故用 offset 而非 y 坐标减算）
                        .offset(y = (-8).dp)
                        // Liquid Glass（spec §10 浮层清单 3/12）：SurfaceElevated 0.9 实底→nvGlassSurface
                        .nvGlassSurface(NvColors.GlassFill, NvShape.Input)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (visual == NvSliderVisual.LINE) {
                // 外层 Box 承接 BoxScope.align；本 Compose 版无 BoxScope.AnimatedVisibility 扩展，
                // 裸调用会解析到外层 Column 接收者的 ColumnScope 版而编译报错——故全限定名调顶层版
                Box(Modifier.align(Alignment.TopCenter)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = (dragging || tapPulse) && durationMs > 0,
                        enter = fadeIn(tween(80)),
                        exit = fadeOut(tween(160)),
                    ) {
                        dragBubble(Modifier)
                    }
                }
            } else if (showTapPos && durationMs > 0) {
                dragBubble(Modifier.align(Alignment.TopCenter))
            }
        }
        }
    }
}

internal fun timeFromX(x: Float, width: Float, durationMs: Long): Long {
    if (width <= 0f || durationMs <= 0) return 0L
    // 2026-09-15 修复：拖到最右端不再落到 duration（片尾死锁，见 clampSeekTarget），
    // 与 smartSeek 口径一致 → 进度条拖到底 = 跳到片尾前 1 秒
    return clampSeekTarget((x / width * durationMs).toLong(), durationMs)
}

// ---------------- 手势指示浮层（C6 §1.3） ----------------

@Composable
internal fun GestureIndicators(
    showBrightness: Boolean,
    brightness: Float,
    showVolume: Boolean,
    volume: Float,
    showSeekPreview: Boolean,
    seekTarget: Long,
    seekDelta: Long,
    showDblSeek: Boolean,
    dblSide: Int,
    dblTotal: Long,
) {
    Box(Modifier.fillMaxSize()) {
        // 2026-09-15 用户反馈二轮：36dp 时条跨在 48dp 手势带边界上仍显压边 → 内移 64dp
        //（条占 64-84dp，完全出带留 16dp 空隙；评审 P1 核准）。亮度图标换太阳（原 BrightnessLow 似齿轮）
        if (showBrightness) {
            NvVerticalLevelBar(
                icon = { Icon(Icons.Filled.WbSunny, null, Modifier.size(20.dp), tint = Color.White) },
                value = brightness,
                label = if (brightness <= 0f) stringResource(R.string.player_brightness_off) else null,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 64.dp),
            )
        }
        if (showVolume) {
            NvVerticalLevelBar(
                icon = { Icon(Icons.Filled.VolumeUp, null, Modifier.size(20.dp), tint = Color.White) },
                value = volume,
                label = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 64.dp),
            )
        }
        if (showSeekPreview) {
            // 2026-09-15 用户实测修复（浮层跑到左上角）：本 Column 原先既没有 align 也没有
            // 定位约束，而 Box 的 contentAlignment 默认 TopStart → 浮层被摆在屏幕左上角、
            // 正压标题栏（用户截图证据）。改为顶部居中：与全屏滑动 seek 的「松手才提交」
            // 语义一致（预览挂在画面上方，不挡底部控制条也不压标题）。
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formatTime(seekTarget),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    "${if (seekDelta >= 0) "+" else "-"}${formatTime(abs(seekDelta))}",
                    style = NvTypography.LabelMedium,
                    color = if (seekDelta >= 0) NvColors.Success else NvColors.TextSecondary,
                )
            }
        }
        if (showDblSeek) {
            // 快进/快退提示：跟随触发侧屏心（2026-09-18 用户反馈：右侧双击 +15s 浮层出现在左侧——
            // 旧实现 align 写死 CenterStart 未按侧别分边）
            // Liquid Glass（spec §10 浮层清单 4/12）：SurfaceElevated 0.9 实底→NvGlassChip
            //（纯指示浮层原无 clickable → chip 不带 onClick，点击语义零变化）
            NvGlassChip(
                modifier = Modifier
                    .align(if (dblSide > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp),
                shape = NvShape.Dialog,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (dblSide > 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                        null,
                        Modifier.size(24.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (dblSide > 0) "+" else "-"}${abs(dblTotal) / 1000}s",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** 竖条指示器：宽 6dp 轨道 + 橙色填充（位置由调用方 align 决定：亮度左/音量右） */
@Composable
// 2026-09-20：升 internal——RMVB 兜底播放页手势 HUD 复用同一竖条指示器（浅色模式改造时
// 本 Canvas 的颜色已提升为局部值）
internal fun NvVerticalLevelBar(
    icon: @Composable () -> Unit,
    value: Float,
    label: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Spacer(Modifier.height(8.dp))
        // DrawScope 不是 Composable 上下文（浅色模式改造）：主题 token 提升为局部值
        val cTrack = NvColors.Track
        val cFillPrimary = NvColors.Primary
        val cFillPressed = NvColors.PrimaryPressed
        val cKnobHighlight = NvColors.KnobHighlight
        val cKnobBase = NvColors.KnobBase
        val cKnobEdge = NvColors.KnobEdge
        val cKnobRim = NvColors.KnobRim
        // 2026-10-10 S2(spec §2.2):绘制域加宽到帽直径 10dp,6dp 轨道画在水平居中;
        // 帽随手势指示器出现期间恒显(本 HUD 只在手势期间可见,无独立静息态),y=填充顶端
        Canvas(Modifier.width(10.dp).height(120.dp)) {
            val trackW = 6.dp.toPx()
            val x0 = (size.width - trackW) / 2f
            // 凹槽底轨:水平渐变(竖条的「顶缘受光」= 左缘),全圆头
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(cTrack.copy(alpha = (cTrack.alpha * 1.4f).coerceAtMost(1f)), cTrack),
                ),
                topLeft = Offset(x0, 0f),
                size = Size(trackW, size.height),
                cornerRadius = CornerRadius(trackW / 2f),
            )
            // 填充:纵向 Primary→PrimaryPressed;0 值:无填充+底端 6dp Track 圆点占位
            val fillTop = size.height * (1f - value)
            if (value > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(cFillPrimary, cFillPressed), startY = fillTop, endY = size.height),
                    topLeft = Offset(x0, fillTop),
                    size = Size(trackW, size.height - fillTop),
                    cornerRadius = CornerRadius(trackW / 2f),
                )
            } else {
                drawRoundRect(
                    cTrack,
                    topLeft = Offset(x0, size.height - trackW),
                    size = Size(trackW, trackW),
                    cornerRadius = CornerRadius(trackW / 2f),
                )
            }
            // 末端旋钮帽
            drawNvKnob(
                center = Offset(size.width / 2f, fillTop),
                radius = 5.dp.toPx(),
                highlight = cKnobHighlight,
                base = cKnobBase,
                edge = cKnobEdge,
                rim = cKnobRim,
            )
        }
        label?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
        }
    }
}

// ---------------- 面板（C6 §3） ----------------
