@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import com.novax.app.data.db.SourceType
import com.novax.app.ui.cast.NvCastDeviceSheet
import com.novax.app.ui.cast.NvCastMeteredDialogHost
import com.novax.app.ui.cast.rememberNvCastGateState
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
import com.novax.app.ui.components.UiMessage
import com.novax.app.ui.components.text
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// ============================================================
// C6 · 播放器控制层（100% Compose 自研，不用 media3-ui PlayerView）
// 手势互斥状态机见 detectPlayerGestures（C6 §2.9；2026-09-14 二次修订：
// 长按倍速触发区扩至全屏，仅左右各 48dp 边缘竖条保留给亮度/音量滑动）
// ============================================================

enum class AspectMode(val labelRes: Int) {
    FIT(R.string.player_aspect_fit),
    FILL(R.string.player_aspect_fill),
    R16_9(R.string.player_aspect_16_9),
    R4_3(R.string.player_aspect_4_3),
}

// M3（2026-10-07）：QUALITY = 清晰度面板（仅 Jellyfin 源可达，入口按源隐藏）
enum class PlayerSheet { SUBTITLE, AUDIO, SPEED, ASPECT, QUALITY, INFO, MORE }

/** C12 反馈：顶栏「方向切换」循环模式。2026-10-17 重力跟随改听系统旋转锁定（自有设置已删）：
 *  系统未锁时进入/循环可达 AUTO_SENSOR（FULL_SENSOR 四向跟随手机姿态）。
 *  2026-09-26 用户需求：移除 FOLLOW_VIDEO（跟随视频）——三态循环里朝向跟视频走与预期错位
 *  （返回横屏要点两次、旋转后首个滑动分区体感错乱），只保留横屏/竖屏两种。 */
private enum class OrientationMode(val labelRes: Int) {
    LANDSCAPE(R.string.player_orientation_landscape),
    PORTRAIT(R.string.player_orientation_portrait),
    AUTO_SENSOR(R.string.player_orientation_auto),
}

private const val CONTROL_AUTO_HIDE_MS = 4000L
internal const val DOUBLE_TAP_WINDOW_MS = 300L

/** 2026-09-19 用户需求：长按倍速生效期间累计下滑超过该距离 = 锁定/解锁倍速切换（一次性触发） */
internal const val SPEED_LOCK_SWIPE_DP = 80

/**
 * 片尾卡住看门狗参数（2026-09-15 真机复现修复）：
 * - [END_STALL_NEAR_END_MS]：判定「已在片尾」的位置容差（位置与缓冲都要落在片尾这么近内）；
 * - [END_STALL_TIMEOUT_MS]：连续缓冲无推进超过该时长即认定「播到文件尾但不出 ENDED」并收口。
 * 正常播放/正常网络短缓冲都远达不到这两个条件（2.5 秒无任何位置推进 + 缓冲已满到片尾）。
 */
private const val END_STALL_NEAR_END_MS = 2_000L
private const val END_STALL_TIMEOUT_MS = 2_500L

/** 位置离开片尾这么多就算「用户又回去了」→ 允许下一次片尾重新收口 */
private const val END_STALL_RESET_DISTANCE_MS = 5_000L

/**
 * 「seek 后无画面」看门狗参数（2026-09-15 用户录屏取证后重写）：
 * - [NO_FRAME_MIN_POSITION_ADVANCE_MS]：位置必须已推进这么多，才认定「在播但没出画」，
 *   避免把 seek 落点解复用/首帧解码的短暂等待误判成僵死；
 * - [NO_FRAME_RECOVER_MS]：连续这么久「在播 + 位置推进 + 本次 seek 后零出帧」→ 自愈一次；
 * - [MAX_STALL_RECOVER]：自愈上限，避免无法恢复时无限 prepare 打转。
 *
 * 【判据演进记录 —— 前三版全错，勿重踩】
 *   ① position 不推进 → 无效：僵死时 position 每 500ms 稳定 +500ms（播放器自认正常）；
 *   ② 位置推进 + 缓冲领先 → 同上，无效；
 *   ③ `onRenderedFirstFrame` 当「每帧信号」→ **必然误报**：该回调每次 prepare/seek 只触发一次，
 *      正常播放时「距上次出帧」会一路涨到几十秒（真机实测 53s），据此自愈会打断正常播放。
 * 本版把 ③ 的语义摆正：**它只能证明「本次 seek 之后出过帧」**，因此判据是
 * 「seek 后帧计数仍为 0 且位置在推进」——正常播放不会命中（早已出过帧）。
 */
private const val NO_FRAME_MIN_POSITION_ADVANCE_MS = 1_500L
private const val NO_FRAME_RECOVER_MS = 4_000L
private const val MAX_STALL_RECOVER = 3
internal const val LONG_PRESS_MS = 350L          // C6 §2.3（修订）按住 350ms 内位移 ≤24dp → 长按倍速
private const val SPEED_SHIFT_HEARTBEAT_MS = 200L // 倍速期间心跳：进度条同速推进 + N 秒刷新

/**
 * 2026-09-14 二次修订（用户实测反馈）：长按倍速触发区扩至**全屏**（含黑边、画面，pointerInput 挂全屏根 Box），
 * 仅左右各 [EDGE_STRIP_DP] 的边缘竖条除外——这两条竖条保留给亮度/音量滑动手势，竖条内长按不触发倍速。
 */
private const val EDGE_STRIP_DP = 48
private const val LOADER_DELAY_MS = 300L
private const val INDICATOR_FADE_MS = 800L

/** 还原画面回弹时长（easeOutQuad 手写插值；不用 animate* API——AnimationVector3D 分量为内部属性） */
private const val ZOOM_RESET_MS = 180L
@Composable
fun PlayerScreen(
    // P1-6 后台播放：控制面只依赖 PlayerHandle 适配层（旧路=引擎内建 ExoPlayer；
    // 新路=service 内 session player 经 MediaController 驱动，渲染 Surface 由 media3 转发）
    engine: PlayerHandle,
    title: String,
    subtitle: String? = null,
    subtitleCandidates: List<SubtitleCandidate> = emptyList(),
    needsSubtitleChoice: Boolean = false,
    onBack: () -> Unit = {},
    /** 错误态「重试」：由宿主重建引擎/重新 prepare（真重试），PlayerScreen 只清本地错误态 */
    onRetry: () -> Unit = {},
    /** 2026-09-18 双内核：容器解析类错误时提供「软解内核重试」（非 null 才显示按钮）。
     *  参数 reason：宿主持久化「此文件本机硬解不了」的结论（写按文件路由记忆），
     *  下次打开同文件直接路由软解。（2026-09-29 mime 参数随 per-mime 记忆退役删除） */
    onFallbackRetry: ((reason: String) -> Unit)? = null,
    /** M3 清晰度菜单（2026-10-07）：用户点选档位回调——宿主负责切流（PlaybackService.switchQuality，
     *  进度/倍速保持）与记忆档位写盘；非 Jellyfin 源顶栏入口隐藏，回调不可达 */
    onQualitySelected: (NvQualityTier) -> Unit = {},
    /** C13 投屏：播放 URL（novax://id/path 或 LOCAL 绝对路径）——非 null 才显示投屏入口 */
    playUrl: String? = null,
    // 2026-09-15 用户需求：续播提示条——非 null = 顶部显示 5 秒「跳转上次位置」提示；
    // 跳转（smartSeek）/关闭由屏内处理，宿主只给位置
    resumePositionMs: Long? = null,
    /** 2026-09-19 用户反馈「开头进去进度条拖不动」：引擎解析出真实时长前（SMB 慢源可达数秒），
     *  先用媒体库已知时长驱动进度条——无时长窗口内滑杆零反馈且 seek 落 0。宿主从库表异步查询 */
    durationHintMs: Long = 0L,
    // 2026-09-15 用户需求：文件夹内播放上/下切换（null = 无队列或已在边界，按钮置灰）
    onPrevVideo: (() -> Unit)? = null,
    onNextVideo: (() -> Unit)? = null,
    /** 2026-09-27 切集自动定向：宿主每次切上/下集递增——屏内据此清旧集几何并按新集
     *  视频比例自动切横/竖屏（文件夹打开不受影响；自适应重建引擎不递增） */
    autoOrientationSeq: Int = 0,
    /** 2026-10-12 §5:通知栏真冷启动(fromNotification && 无 savedInstanceState)置 true——
     *  引擎复用时 onVideoSizeChanged 不再补发,seq 清零后须回读播种;应用内切集恒 false,
     *  维持"清零等新事件"防旧比例误判的既有语义 */
    autoOrientationColdEntry: Boolean = false,
    // 2026-09-15 D5：一键画中画（null = 设备不支持，按钮隐藏）
    onEnterPip: (() -> Unit)? = null,
) {
    val player = engine.player
    val context = LocalContext.current
    val activity = context as? Activity

    // 2026-09-18：不再驱动 surface 重挂（见下方 LaunchedEffect 注释——每次 seek 重挂正是
    // 「±15s 每按一下卡一下」的元凶）。保留计数仅作日志观测。
    var seekSeq by remember { mutableIntStateOf(0) }

    /**
     * 位置跳转统一入口（2026-09-15 修复「自适应升档后滑动进度条进度条不动」）。
     *
     * 自适应升档会让 Service **重建引擎**（旧的被 `release()`），而本次组合持有的 `engine`
     * 可能已经是那个旧引用：对已释放引擎 `seekTo` 会抛
     * `IllegalStateException: sending message to a Handler on a dead thread`，
     * 且异常只以 `W MessageQueue` 打印、**静默丢弃** → 进度条怎么拖都不动（真机取证的调用栈）。
     *
     * 因此：engine 已释放时改投「当前活跃引擎」；两者都没有才丢弃（并留日志）。
     * 相对 seek 的语义基准（当前播放位置）同样取自活跃引擎，避免用到旧位置。
     */
    val seekViaEngine: (Long, Boolean) -> Unit = remember(engine) {
        { value, absolute ->
            val active = if (engine.isReleased) PlaybackService.activeEngine else null
            val target = when {
                active != null -> if (absolute) value else active.player.currentPosition + value
                else -> if (absolute) value else player.currentPosition + value
            }
            if (active != null) {
                Log.w("NvPerf", "seek routed to active engine (held engine released) target=$target")
                active.smartSeek(target)
                seekSeq++
            } else if (!engine.isReleased) {
                engine.smartSeek(target)
                seekSeq++
            } else {
                Log.w("NvPerf", "seek dropped: no live engine (target=$target)")
            }
        }
    }

    /**
     * 播放/暂停 与 变速 的统一入口（2026-09-18 审查修复）：与 [seekViaEngine] 同款活跃引擎兜底。
     * 自适应升档重建引擎后，本次组合持有的 engine/player 可能是已 release 的旧引用，
     * 直接调用会被静默丢弃（表现：双击中间区不暂停、长按倍速不生效）。
     * lambda 每次调用读旧 engine 的 isReleased 再改投活跃引擎——同 seekViaEngine 口径。
     */
    val togglePlayViaEngine: () -> Unit = remember(engine) {
        {
            val active = if (engine.isReleased) PlaybackService.activeEngine else null
            when {
                active != null -> {
                    Log.w("NvPerf", "toggle routed to active engine (held engine released)")
                    val p = active.player
                    if (p.isPlaying) p.pause() else p.play()
                }
                !engine.isReleased -> if (player.isPlaying) player.pause() else player.play()
                else -> Log.w("NvPerf", "toggle dropped: no live engine")
            }
        }
    }
    val setSpeedViaEngine: (Float, Float) -> Unit = remember(engine) { { spd, pitch ->
        val active = if (engine.isReleased) PlaybackService.activeEngine else null
        when {
            active != null -> active.player.playbackParameters =
                androidx.media3.common.PlaybackParameters(spd, pitch)
            !engine.isReleased -> engine.setPlaybackSpeed(spd, pitch = pitch)
            else -> Log.w("NvPerf", "setSpeed dropped: no live engine")
        }
    } }

    // ---- 播放状态 ----
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 时长提示落位（异步查询到达后补种；真实时长就绪后心跳会覆盖回引擎值）
    LaunchedEffect(durationHintMs) {
        if (durationHintMs > 0 && durationMs <= 0L) durationMs = durationHintMs
    }
    var speed by remember { mutableFloatStateOf(1f) }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    // 2026-10-18 vivo 用户反馈「竖屏播放切集先横屏拉大再回竖屏」：显示几何与定向判定分离——
    // 切集清零 videoW/H（防旧比例误定向）后，VideoSurface/zoomContentDims 的 fallback 是 16:9
    // 横屏画幅，竖屏视频瞬间被撑成全宽横幅、且触发 surface rebind（观感=先横屏拉大+卡一下，
    // metadata 到达才恢复）。displayW/H 只在真实尺寸事件（播种/回读/onVideoSizeChanged）时更新，
    // 切集清零不清它：metadata 先于首帧渲染到达，新集首帧不会被旧比例拉伸
    var displayW by remember { mutableIntStateOf(0) }
    var displayH by remember { mutableIntStateOf(0) }
    var cues by remember { mutableStateOf<androidx.media3.common.text.CueGroup?>(null) }
    var hasTextTracks by remember { mutableStateOf(false) }
    var trackRefresh by remember { mutableIntStateOf(0) }

    // ---- 控制层状态 ----
    // 2026-09-20：locked/controlsVisible 用 rememberSaveable——慢加载触发引擎重建时
    // PlayerScreen 子树会离组再回组（宿主 SaveableStateProvider 承接），普通 remember 会
    // 重置成「未锁定+控制层常显」（用户报：锁定后视频加载出来进度条又出现）
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    // 2026-09-29 进度条拖动抢触修复：底部控制层（时间行+进度条热区+按钮行）的实时像素高度，
    // 喂给父层手势状态机做「控制层区域不接管」判定（见 GestureEvents.controlsHeightPx）。
    // 由控制层 Column 的布局 bounds 实时回填；控制层隐藏（AnimatedVisibility 移除内容）后
    // 由消费端 lambda 归零，隐藏时全屏手势照旧
    var controlsBandHeightPx by remember { mutableFloatStateOf(0f) }
    // 2026-09-29 不可跳转片源的提示只弹一次（每次拖/点都弹会刷屏）
    var unseekableToastShown by remember { mutableStateOf(false) }
    // 2026-09-19 用户需求（锁定交互重做）：按一下锁定按钮 = 完全锁定（钉死当前朝向），
    // 双击左下角锁图标解锁。不再用 SCREEN_ORIENTATION_LOCKED——它冻结「当前旋转角」，
    // 锁屏（电源键）期间系统按竖屏渲染会把竖屏冻下来，解锁后方向漂移（真机 bug 根因）。
    var locked by rememberSaveable { mutableStateOf(false) }
    /** 2026-10-08 半锁（软锁）：手势全可用、单击不出控制层、不钉朝向；全锁语义不变 */
    var halfLocked by rememberSaveable { mutableStateOf(false) }
    /** 锁定时刻钉死的朝向（SENSOR_LANDSCAPE / SENSOR_PORTRAIT）；ON_RESUME 重申用它 */
    var pinnedOrientation by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    // C13 投屏：设备选择弹层开关
    var showCastSheet by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(AspectMode.FIT) }
    // 长按倍速（夸克式，C6 §2.3 修订）：生效开关 + 生效值（快进=设置值 2x/3x，慢放=0.5x）。
    // C12 反馈：中央大胶囊已删除，生效期间由底部倍速 chip 高亮+脉冲展示（见 NvPlayerBottomBar）
    // 2026-09-28 审计 A 收敛：AB/睡眠/长按倍速三态迁入 PlaybackFeatures（与 RMVB 页共用同一
    // 状态语义与复位口径）；无 key 捕获注意点不变（GestureEvents lambda 捕获 holder 引用，
    // holder 内部为 observable 状态，无 stale 问题），随 engine 重置走下方 LaunchedEffect——
    // 换集/自动连播/自适应重建引擎后统一清零，防「UI 锁定 2x、引擎 1x」错位
    val features = remember { PlaybackFeatures() }
    LaunchedEffect(engine) {
        features.resetAbLoop()      // 原 AB 状态 remember(engine) 的换集清空语义
        features.resetSpeedShift()
    }
    // 音轨缺失自动转软解（2026-09-19 用户需求）：设备无该音频解码器 → 直接切软解内核
    // 播放（libVLC 软解 DTS/TrueHD/E-AC-3），Toast 提示 2 秒自动消失；每部片只切一次
    var audioSwitchDone by remember { mutableStateOf(false) }
    // C12 反馈：顶栏「方向切换」循环模式（横屏↔竖屏；系统未锁时可达自动·重力）与「画面旋转」角度（0/90/180/270）
    // 2026-09-26：FOLLOW_VIDEO 移除后旧存档可能含已删除枚举值——按名恢复、失败回退横屏
    var orientationMode by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { it.name },
            restore = { runCatching { OrientationMode.valueOf(it) }.getOrDefault(OrientationMode.LANDSCAPE) },
        ),
    ) { mutableStateOf(OrientationMode.LANDSCAPE) }
    var videoRotation by rememberSaveable { mutableIntStateOf(0) }
    // 2026-10-11 用户需求:画面镜像(水平翻转)——与 videoRotation 同口径:会话级、换集复位、不落盘
    var videoMirrored by rememberSaveable { mutableStateOf(false) }
    // 2026-09-27 用户需求：切上下集按新视频比例自动定向——切集置 pending，拿到新集
    // 宽高后在下方 applyOrientationMode 定义之后应用（文件夹打开仍默认横屏，不变）。
    // 上一集的手动方向与画面旋转不跨集保留（旋转对新集无意义）
    var pendingAutoOrientation by rememberSaveable { mutableStateOf(false) }
    // 2026-10-23 慢源晚到自愈：最近一次「已成功定向（或确认无需定向）」的切集序号——
    // 引擎尺寸事件回调据此判断本切集是否还没定向过（落后 = 晚到尺寸需补触发判定）
    var orientedSeq by remember { mutableIntStateOf(-1) }
    /** 每次「在播但没出画」自愈递增：驱动 VideoSurface 重挂 surface（修复断开的 bufferqueue）。
     *  2026-10-25 上移声明：切集定向消费方超时自愈（P0 修复配套）也要递增它 */
    var noPictureRecoverSeq by remember { mutableIntStateOf(0) }
    // 2026-10-24 用户报告：切集瞬间旧帧仍留在 surface 上，而屏幕方向已先转——旧帧被拉伸
    // 成新比例「压扁变形」卡到新视频加载完成（慢源 6~9s）。切集即盖黑幕，新集首帧渲染后
    // 揭开（onFrameRendered）；配合既有 loader 转圈 = 正常加载观感
    var switchBlackout by remember { mutableStateOf(false) }
    var coldAutoOrientationArmed by remember { mutableStateOf(autoOrientationColdEntry) }
    // 2026-10-22 上移到切集定向效果/轨道监听之前：两处应用点都需要本 lambda
    val applyOrientationMode: () -> Unit = {
        val requested = when (orientationMode) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.AUTO_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        activity?.requestedOrientation = requested
    }
    LaunchedEffect(autoOrientationSeq) {
        if (autoOrientationSeq <= 0) return@LaunchedEffect
        val coldSeed = coldAutoOrientationArmed
        coldAutoOrientationArmed = false
        // 2026-10-22 切集自动定向总开关（默认开；关 = 切集不改变方向，冷启动通知重进的自动定向一并关闭）。
        // 2026-10-22 模拟器实测修正：必须读 playerPrefsSnapshot 而非 rememberPlayerPrefs 组合态——
        // 切集 = PlayerScreen 卸载重进，重组首帧 collectAsState 尚为 initial=null，组合态读恒取
        // 默认 true，用户关掉的开关在切集瞬间形同虚设（真机复现：关开关切集仍被转竖屏）；
        // 快照由 warmPlayerPrefsCache(onCreate 预热)+putPlayerPref(设置页写后同步) 维护，无此窗口
        if (playerPrefsSnapshot()?.get(KEY_AUTO_ORIENT_ON_SWITCH) ?: true) {
            pendingAutoOrientation = true
            // 2026-10-24 用户定稿的切集方向规范：加载等待期间**保持当前正在播放的方向**——
            // 竖→横在竖屏等、横→竖在横屏等，中间零来回切换；新集画面出来后由下方消费方按
            // 真实尺寸一步切到位。锁定态不动（locked ⇒ 用户已手动钉住方向）。早期的「库宽高
            // 预判提前转目标方向」与本规范冲突，已整体移除（applySwitch 也不再查库）
            // 2026-10-25 用户报告修复：pin 的参照物此前是**系统物理朝向**（configuration.
            // orientation）——横持手机看竖屏视频时，竖→竖切集被 pin 成横屏（先横后竖）；
            // 竖持看横屏时横→横会闪竖。「保持当前方向」应保持**当前视频的生效方向
            //（orientationMode）**，与消费方落位同口径，直接复用 applyOrientationMode
            if (!locked) {
                applyOrientationMode()
            }
        }
        // 2026-10-24 复审 P1：复用重进（后台通知切集后回前台 resync）会以陈旧 seq 重放本效果——
        // 此时引擎已在播本集（engineMediaUrl == playUrl），首帧不会重发：盖黑幕会**长挂**、清零
        // videoW/H 且 displayW/H 无播种 → 竖屏片 16:9 拉伸。判定「引擎已落到目标集」= 无切集的
        // 重进，跳过清零与黑幕（保播种值；pin/pending/coldSeed 播种仍执行，行为与改版前一致）。
        // 应用内切集 seq 效果跑在 setMediaItem 之前（launchStart 挂起点让出主线程），引擎 URL
        // 仍是旧集 → 判定 false，切集路径不受影响
        val resyncReentry = run {
            val target = android.net.Uri.parse(playUrl ?: "")
            val engineUrl = engine.engineMediaUrl
            playUrl != null && engineUrl != null &&
                (engineUrl == playUrl || android.net.Uri.parse(engineUrl).path == target.path)
        }
        if (!resyncReentry) {
            videoW = 0   // 清旧集宽高：防用旧比例误判（定向判定以新 metadata 为准；显示几何走 displayW/H 保持，见其声明处注释）
            videoH = 0
            videoRotation = 0
            videoMirrored = false
        // 2026-10-24 切集黑幕：转屏先于新集首帧，旧帧会被拉伸变形（用户报告「压扁卡住到
        // 加载完成」）——立即盖黑，onFrameRendered（新集首帧）揭开；黑幕期间 loader 转圈照常。
        // 冷启动重进（coldSeed）引擎已在播本集、无旧帧变形问题，不盖
        if (!coldSeed) switchBlackout = true
        }
        // 2026-10-23 用户真机「横屏视频被当竖屏」竞态修复：此处原有一段「直读引擎尺寸兜底
        // 盖章」——切集是异步的，组合瞬间引擎 videoSize 还是上一集残留值，直读=拿旧尺寸盖
        // 新戳，消费方立即按上一集比例判定（模拟器实证：竖→横切换被定成竖屏，且 pending
        // 已消费、真实回调到达后不再纠正）。兜底已删：判定统一走消费方的「归属核对+直读
        // 自旋」，事件/直读都不再直接当判定数据
        Log.w("NvOrientDbg", "seq armed: seq=$autoOrientationSeq engineSize=${engine.engineVideoSize.width}x${engine.engineVideoSize.height} rot=${engine.engineVideoRotation} pending=$pendingAutoOrientation orientOn=${playerPrefsSnapshot()?.get(KEY_AUTO_ORIENT_ON_SWITCH)}")
        if (coldSeed) {
            // §5 组合序陷阱修复:上面清零会抹掉 DisposableEffect 同步播种的真实宽高,而引擎
            // 复用路径 onVideoSizeChanged 永不再来——冷启动一次性回读;新引擎(切集)videoSize
            // =UNKNOWN 时回读自然 no-op,落回等事件语义
            player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                videoW = it.width
                videoH = it.height
                displayW = it.width
                displayH = it.height
            }
        }
    }
    // 2026-10-25 黑幕卡死兜底（用户真机报告「声音在放、画面一直加载中、进度条在走」）：
    // 揭黑幕的唯一信号是 onFrameRendered，而 PlayerScreen 离组（onDispose 置空监听）到回组
    // 重注册之间发生的新引擎首帧事件会**丢失**——快源（Jellyfin 直连/缓存命中）新引擎起播
    // 快于「离组→回组」窗口时，黑幕永挂（引擎照常出声、进度照走，画面却是加载中）。两层
    // 防御：①本 3 秒兜底强制揭开（宁可短暂露出加载帧也不永久卡死）；②DisposableEffect
    // (engine) 重注册完成时若引擎已 READY+播放，立即揭开（见其尾部）。loader 由 isBuffering
    // 独立驱动，揭幕后仍在缓冲则转圈继续，观感不变
    LaunchedEffect(autoOrientationSeq) {
        if (autoOrientationSeq <= 0) return@LaunchedEffect
        // 2026-10-25 黑幕 3 秒兜底（子代理审查修正）：key 用切集世代而非 switchBlackout——
        // placeholder surface 的「首帧」也会回调 onFrameRendered（揭幕并取消 switchBlackout
        // key 的计时器），兜底被无声取消后黑画面+转圈依旧（placeholder 无内容）。世代 key
        // 下计时器不可被提前揭幕取消：3 秒后若黑幕仍在（surface 重绑自愈未及），强制揭开
        if (switchBlackout) {
            delay(3000)
            if (switchBlackout) {
                Log.w("NvOrientDbg", "blackout timeout 3s: first-frame event missed, force reveal")
                switchBlackout = false
            }
        }
    }
    // 2026-10-23 切集定向消费方（归属核对 + 直读自旋，替代原「事件/直读盖章 + 世代门控」）：
    // 模拟器压测实证两条竞态——①切集瞬间引擎 videoSize 仍是上一集残留值，旧「直读兜底盖章」
    // 会拿上一集比例判本集方向；②真实尺寸事件到达时 pending 已被消费，永不纠正。新形态：
    // 判定数据一律消费时刻直读引擎（engineVideoSize + engineVideoRotation），且应用内切集
    // 必须等引擎 currentMediaItem 落到目标集（URL 核对，见 PlayerHandle.engineMediaUrl）——
    // 拿不到就自旋等待（切集管线最长数秒），超时放弃保持当前方向（宁可不转、不可转错）。
    // 连点安全：循环内检测 seq 递增即刷新截止，单循环可跨多次切集持续服务
    val currentOrientSeq by rememberUpdatedState(autoOrientationSeq)
    LaunchedEffect(pendingAutoOrientation) {
        if (!pendingAutoOrientation) return@LaunchedEffect
        if (!(playerPrefsSnapshot()?.get(KEY_AUTO_ORIENT_ON_SWITCH) ?: true)) {
            pendingAutoOrientation = false
            return@LaunchedEffect
        }
        // 冷启动复用（autoOrientationColdEntry）引擎已在播本集、无切换窗口，URL 必然匹配；
        // playUrl 为 null（极端入口）时退化为仅等尺寸就绪
        val needUrlCheck = !autoOrientationColdEntry && playUrl != null
        val playPath = android.net.Uri.parse(playUrl ?: "").path
        var deadline = SystemClock.elapsedRealtime() + 4000L
        var lastSeq = currentOrientSeq
        var decided = false
        while (pendingAutoOrientation && SystemClock.elapsedRealtime() < deadline) {
            if (currentOrientSeq != lastSeq) {   // 连点：新切集进来，重开等待窗口
                lastSeq = currentOrientSeq
                deadline = SystemClock.elapsedRealtime() + 4000L
            }
            val cur = engine.engineVideoSize
            val engineUrl = engine.engineMediaUrl
            val urlOk = !needUrlCheck || (engineUrl != null &&
                (engineUrl == playUrl || android.net.Uri.parse(engineUrl).path == playPath))
            if (cur.width > 0 && cur.height > 0 && urlOk) {
                pendingAutoOrientation = false
                decided = true
                orientedSeq = currentOrientSeq   // 本切集已定向（方形视频亦算处理完毕），后续晚到事件不再补触发
                // 2026-10-23 真机（MTK 平台）实证：ExoPlayer 把 rotation 写进 MediaCodec
                //（rotation-degrees=90），解码器输出**已旋转**的帧，VideoSize.width/height
                // 即解码输出=最终显示方向（output format 的 rotationDegrees 是残留元数据，
                // 渲染端不重复应用）——判定直接用 VideoSize，不可再按 rotation 交换（二次
                // 旋转会判反，4 (1).MOV 案例：判 LANDSCAPE 实际竖画面）
                val target = when {
                    cur.height > cur.width -> OrientationMode.PORTRAIT
                    cur.width > cur.height -> OrientationMode.LANDSCAPE
                    else -> break   // 方形视频保持当前方向
                }
                if (!locked && target != orientationMode) {
                    orientationMode = target
                    applyOrientationMode()
                    Toast.makeText(
                        context,
                        context.getString(R.string.player_orientation_toast, context.getString(target.labelRes)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                Log.w(
                    "NvPerf",
                    "auto orientation on episode switch -> $target (videoSize=${cur.width}x${cur.height} fmtRot=${engine.engineVideoRotation} urlOk=$urlOk)",
                )
                break
            }
            delay(100)
        }
        if (!decided) {
            pendingAutoOrientation = false
            Log.w("NvOrientDbg", "orientation spin timeout: seq=$currentOrientSeq engineUrl=${engine.engineMediaUrl} playUrl=$playUrl size=${engine.engineVideoSize.width}x${engine.engineVideoSize.height}")
            // 2026-10-25 子代理建议（P0 修复配套）：超时多为 surface 未 attach（placeholder
            // decode-only）或解码异常——触发一次 VideoSurface 重挂自愈，顺带救回画面
            noPictureRecoverSeq++
        }
    }
    // 左手模式慢放 + C10 播放器设置（novax_player_prefs，实时收集：设置页改动即时反映）
    val playerPrefs by rememberPlayerPrefs()
    val haptics = LocalHapticFeedback.current
    var loaderVisible by remember { mutableStateOf(false) }
    var showNoSubtitleBanner by remember { mutableStateOf(false) }
    var lastLockTapAt by remember { mutableLongStateOf(0L) }
    // 播放错误态（非 null 时显示全屏错误，替代无限转圈）；key=engine：错误重试重建引擎后自动清除
    // i18n：错误文案以 UiMessage（资源键+参数）承载，UI 层 text() 渲染（spec §1.3）
    var errorMessage by remember(engine) { mutableStateOf<UiMessage?>(null) }
    // 2026-09-18 双内核：容器解析类错误 → 提示可转 libVLC 软解内核重试
    var fallbackRetryable by remember(engine) { mutableStateOf(false) }

    // ---- 网络恢复自动重连（2026-09-17 用户需求，调研 backlog 项）----
    // 状态机在 NvAutoReconnect（纯逻辑，单测覆盖）：预算 3 次防无限循环，播放成功即重置。
    // 此处只剩接线：致命错误分类入状态机；系统网络恢复事件驱动自动重试。
    var lastFatalWasNetwork by remember { mutableStateOf(false) }
    val autoReconnect = remember {
        NvAutoReconnect(
            onRetry = {
                errorMessage = null
                isBuffering = true
                onRetry()   // 真重试——宿主重建引擎
            },
            onAutoRetry = {
                Toast.makeText(context, context.getString(R.string.player_reconnected_toast), Toast.LENGTH_SHORT).show()
            },
        )
    }

    // ---- AB 循环 + 睡眠定时（2026-09-17 功能项，调研 backlog）----
    // AB 循环：跟随引擎（换集/重建后清空）；B 点越过即跳回 A（心跳内判定）。
    // 睡眠定时：到点暂停并清空，跨引擎重建仍有效。

    // ---- 同目录字幕匹配（C6 §3.1 外挂字幕） ----
    // 多候选待手选：进入播放器自动弹出「选择字幕」
    var showSubtitlePicker by remember { mutableStateOf(needsSubtitleChoice) }
    // 待选中的 side-load 字幕轨 Format.id：onTracksChanged 捕获到对应轨后应用 override
    var pendingSideLoadSelect by remember { mutableStateOf<String?>(engine.activeSubtitleId) }
    val uiScope = rememberCoroutineScope()

    // 画面比例记忆（novax_aspect_mode）：首次加载读取；用户手选即写盘
    var aspectTouched by remember { mutableStateOf(false) }
    LaunchedEffect(playerPrefs) {
        val p = playerPrefs ?: return@LaunchedEffect
        if (!aspectTouched) {
            p[KEY_ASPECT_MODE]?.let { saved ->
                aspect = runCatching { AspectMode.valueOf(saved) }.getOrDefault(AspectMode.FIT)
                aspectTouched = true
            }
        }
    }

    // ---- M3 清晰度面板数据（2026-10-07）----
    // 当前生效档与源类型：读服务侧会话规格（activeSpec().jellyfinTier 由起播/切档链路维护，
    // 手动切档经 startPlaybackInternal 覆写 currentSpec 并重建引擎——engine 身份变化触发本快照
    // 重算，面板重开即显示最新档）。与 seekViaEngine 直读 PlaybackService.activeEngine 同款
    // 既有通道；无规格（直连 URL/其他宿主）按非 Jellyfin 处理 → 顶栏入口隐藏，UI 零变化
    val qualitySpec = remember(engine) { PlaybackService.activeSpec() }
    val isJellyfinSource = qualitySpec?.novaxSource?.type == SourceType.JELLYFIN
    val currentQualityTier = qualitySpec?.jellyfinTier ?: NvQualityTier.AUTO
    // M4：自动档当前生效档（AUTO 行副标）；引擎重建（含自动档切流）触发重算，同 activeSpec 通道
    val effectiveQualityTier = remember(engine) { PlaybackService.activeEffectiveQualityTier() }
    // 2026-10-08：片源视频码率（0=未知）——面板据此隐藏「≥源码率」的转码档（原画的重复项）
    val jellyfinSourceBitrate = qualitySpec?.jellyfinSourceBitrate ?: 0L

    // ---- 双指缩放（2026-09-15 用户需求，B 站同款）----
    // 捏合缩放 + 跟手平移；缩放态常驻「还原画面」按钮，点击回弹 1.0。
    // 状态用 MutableState 整体下传（VideoSurface / 还原按钮 / 提示 chip 内部各自读取），
    // PlayerScreen 本体不直接读 zoom 值（只有 derivedStateOf 的 zoomed 阈值翻转才重组本体，
    // 捏合期间每帧只重组 VideoSurface 这一小块，不重组建制庞大的屏幕主体）。
    val zoomState = remember { mutableStateOf(ZoomState()) }
    /** 已缩放（还原按钮显隐）：阈值判定走 PlayerZoomPolicy.isZoomed */
    val zoomed by remember { derivedStateOf { PlayerZoomPolicy.isZoomed(zoomState.value) } }
    /** 缩放百分比提示 chip 显隐：捏合期间常显，结束后由 zoomTick 驱动 800ms 淡出 */
    var showZoomHint by remember { mutableStateOf(false) }
    var zoomTick by remember { mutableIntStateOf(0) }
    // 还原画面（用户实测修订：按钮随控制层同显同隐）：180ms easeOutQuad 回弹到 1.0
    //（不是瞬跳，保留「松手归位」的动效反馈）。手写插值而非 compose animate：
    // AnimationVector3D 的分量属性是 internal，公开 API 无转换器可挂
    val resetZoom: () -> Unit = {
        val from = zoomState.value
        if (PlayerZoomPolicy.isZoomed(from)) {
            Log.d("NvPerf", "pinch zoom reset from scale=${from.scale} pan=(${from.panX},${from.panY})")
            uiScope.launch {
                val startAt = SystemClock.elapsedRealtime()
                while (true) {
                    val t = ((SystemClock.elapsedRealtime() - startAt) / ZOOM_RESET_MS.toFloat()).coerceIn(0f, 1f)
                    val e = 1f - (1f - t) * (1f - t)
                    zoomState.value = ZoomState(
                        scale = from.scale + (1f - from.scale) * e,
                        panX = from.panX * (1f - e),
                        panY = from.panY * (1f - e),
                    )
                    if (t >= 1f) break
                    delay(16)
                }
            }
            zoomTick++
            controlsTick++   // 还原期间控制层保持显示（重置 4s 自动隐藏计时），动画不被打断
        }
    }
    LaunchedEffect(zoomTick) {
        if (zoomTick > 0) {
            delay(INDICATOR_FADE_MS)
            showZoomHint = false
        }
    }
    // 换片（队列上/下一集）复位缩放：新画面从原始比例起播（B 站同款）
    LaunchedEffect(title, subtitle) {
        zoomState.value = ZoomState()
        showZoomHint = false
        // 换片重置音轨自动切换：新视频重新侦测
        audioSwitchDone = false
    }
    // 根 Box 实际像素尺寸：平移夹紧的屏幕口径（onSizeChanged 写入 state，
    // 手势 lambda 每次调用读最新值——旋屏/画中画尺寸变化后无需重建 gestureEvents）
    var screenSizePx by remember { mutableStateOf(0f to 0f) }

    // ---- 手势指示状态 ----
    var brightnessVal by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f)
    }
    var showBrightnessBar by remember { mutableStateOf(false) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volumeVal by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()) }
    var showVolumeBar by remember { mutableStateOf(false) }
    var seekPreviewDelta by remember { mutableLongStateOf(0L) }
    var showSeekPreview by remember { mutableStateOf(false) }
    var dblSeekSide by remember { mutableIntStateOf(0) }
    var dblSeekTotal by remember { mutableLongStateOf(0L) }
    var showDblSeek by remember { mutableStateOf(false) }

    // ---- Player 监听 ----
    // 播完收口（ENDED 语义）：自动连播下一集 / 否则停在末尾。
    // 抽成局部函数的原因见下方「片尾卡住看门狗」——真机取证发现存在「播到文件尾但永不 ENDED」的状态，
    // 必须能用同一套收口逻辑补上，否则用户看到的就是永久黑屏 + 只能退出重进。
    val onPlaybackFinished: () -> Unit = {
        if (onNextVideo != null && (playerPrefs?.get(com.novax.app.player.KEY_AUTO_NEXT) ?: true)) {
            Toast.makeText(context, context.getString(R.string.player_next_up_toast), Toast.LENGTH_SHORT).show()
            onNextVideo?.invoke()
        }
    }

    // 片尾卡住看门狗（2026-09-15 用户实测修复·真机复现 2/2）：
    // 现场：拖动到片尾 → seek 落到文件尾部非样本区 → 读满最后 10KB 即 EOF →
    // 永久 BUFFERING、位置恒等于 duration、缓冲恒满、零网络读、**不报错也不 ENDED** →
    // 画面黑屏、进程活着、只能退出重进（`seek_to_first_frame_ms` 还谎报成功）。
    // 判据保守：仅在「缓冲中 + 位置与缓冲都已到 duration 附近 + 持续 2.5 秒无推进」时收口，
    // 正常播放/正常短缓冲不会命中。
    var endStallSince by remember { mutableLongStateOf(0L) }
    var endStallHandled by remember { mutableStateOf(false) }
    /** 上一次心跳看到的位置：位置不再变化即「冻结」，与播放状态值无关（真机取证见看门狗注释） */
    var lastStallPositionMs by remember { mutableLongStateOf(-1L) }
    /** 最近一次「渲染器真实出帧」的时刻（0 = 尚未出过帧，按当前时刻起算） */
    var frameRenderedAt by remember { mutableLongStateOf(0L) }
    var renderRecoverCount by remember { mutableIntStateOf(0) }
    /** 看的门狗：本次 seek 后的出帧证据基线（见 NvStallRecovery.noteSeek / 心跳里的判定） */
    var seekFrameFloor by remember { mutableLongStateOf(0L) }
    /** 看门狗：最近一次「位置推进但不出帧」被记录时的位置（同一位置只记一次，避免刷屏） */
    var stallLoggedAtPosition by remember { mutableLongStateOf(-1L) }
    /** 看门狗：最近一次侦测到「无出帧且位置在推进」的时刻（0 = 未处于该状态） */
    var noFrameSinceMs by remember { mutableLongStateOf(0L) }
    /** 看门狗：本次 seek 后由哪一位置开始计「位置推进」（出帧恢复后随之抬升） */
    var noFrameBaselinePositionMs by remember { mutableLongStateOf(0L) }
    /** 画面几何变更留痕（拖动后画面缩放/比例跳变的直接证据） */
    var lastSeenVideoW by remember { mutableIntStateOf(0) }
    var lastSeenVideoH by remember { mutableIntStateOf(0) }

    // 出帧看门狗信号源：渲染器真实出帧即刷新时间戳并复位自愈计数。
    // 放在这里（而非心跳内）是因为它是事件回调，需要随 engine 变化重新注册。
    // 2026-09-15 修正：该回调恢复其唯一可信含义——「本次 seek/prepare 之后是否出过帧」
    // （此前把它当「每帧信号」，导致 sinceFrame 在正常播放时涨到 53s 而连续误报，已撤除）。
    DisposableEffect(engine) {
        val thisEngine = engine
        // 2026-10-25 P0 修复（真机「声音在放、画面一直加载中」子代理根因 A）：服务热切换
        // 重建引擎后，MediaController 身份不变、videoGeometryKey 已被 seq 效果清零——
        // VideoSurface 的 surface 重绑 effect 无人触发，新引擎以 placeholder surface 进入
        // decode-only 模式：音频/position 正常、画面永黑、videoSize 与首帧事件永不回调。
        // 引擎换代即递增重挂序号，强制 VideoSurface 重挂 surface 重新下发到新引擎
        noPictureRecoverSeq++
        noFrameSinceMs = 0L
        renderRecoverCount = 0
        engine.onFrameRendered = {
            frameRenderedAt = SystemClock.elapsedRealtime()
            renderRecoverCount = 0
            noFrameSinceMs = 0L
            stallLoggedAtPosition = -1L
            switchBlackout = false   // 新集首帧已渲染，揭切集黑幕（冷启动/同集 seek 时本就为 false）
        }
        // 2026-10-22 连点压测：引擎侧 onVideoSizeChanged 留痕 videoW/H（诊断/看门狗口径）。
        // 2026-10-23 切集定向判定已不再消费此信号做判定数据（改「归属核对+直读自旋」，见消费
        // 方注释）——事件与直读都可能带上一集残留值。但它保留一个唤醒职责：慢源（SMB 实测
        // 5s+）下自旋窗口超时放弃后，真实尺寸事件晚到——此时若本切集尚未成功定向（ orientedSeq
        // 落后），补置 pending 重新进入消费方，直读已就绪的引擎尺寸完成判定（晚到自愈）。
        // 仅限切集场景（seq>0）：冷启动普通打开（seq=0）不自动定向——「文件夹打开仍默认
        // 横屏」的既有设计不因本回调被打破（2026-10-24 复审 P2）
        thisEngine.onVideoSizeChanged = { size ->
            if (thisEngine === engine && size.width > 0 && size.height > 0) {
                videoW = size.width
                videoH = size.height
                if (currentOrientSeq > 0 && orientedSeq != currentOrientSeq) pendingAutoOrientation = true
            }
        }
        // 画面侧自愈 → 递增 noPictureRecoverSeq，VideoSurface 的 LaunchedEffect 随之重挂 surface
        // （重挂 = MediaCodec.setOutputSurface，是解码器换档后唯一能修复断开 bufferqueue 的手段）
        NvStallRecovery.onNoPictureRecover = {
            noPictureRecoverSeq++
            noFrameSinceMs = 0L
            stallLoggedAtPosition = -1L
        }
        // 2026-10-25 黑幕揭幕补偿（真机卡死修复第二层）：离组（onDispose 置空监听）到本
        // DisposableEffect 重注册之间，快源（Jellyfin 直连/缓存命中）的新引擎首帧事件已经
        // 发完——onRenderedFirstFrame 是一次性事件不重发。回组注册完成的此刻，若引擎已在
        // READY+播放（画面实际已出），黑幕立即揭开，不等 3 秒兜底
        if (switchBlackout &&
            player.playbackState == Player.STATE_READY && player.isPlaying
        ) {
            Log.w("NvOrientDbg", "blackout reveal on reattach: engine already playing (first-frame missed)")
            switchBlackout = false
        }
        onDispose {
            engine.onFrameRendered = null
            engine.onVideoSizeChanged = null
            NvStallRecovery.onNoPictureRecover = null
        }
    }
    DisposableEffect(player) {
        // §5 播种:引擎复用(通知重进/回前台重连)时监听器错过历史 onVideoSizeChanged,组合时同步
        // 取一次;新引擎 VideoSize.UNKNOWN(0x0)不播种走等事件路径
        player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
            videoW = it.width
            videoH = it.height
        }
        // 2026-10-25 加载指示补偿（子代理根因 C）：离组窗口错过 BUFFERING→READY 事件时
        // isBuffering 卡 true，与黑幕互相掩护成「永远加载中」——回组按引擎实际状态收敛
        if (player.playbackState == Player.STATE_READY) isBuffering = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                // 2026-09-15 D4：软字幕 cue 输出（此前只选轨无渲染层——外挂/内嵌软字幕不显示）
                cues = cueGroup
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                // 2026-09-15 D1 自动连播：队列内播完 → 自动切下一集（设置 novax_auto_next 默认开；
                // onNextVideo 仅在存在下一集时非空，边界不会误触）
                if (state == Player.STATE_ENDED) {
                    endStallSince = 0L
                    onPlaybackFinished()
                }
            }
            // 注意：onPlayerError 的错误态展示不走这里——由引擎侧错误接线（下方 DisposableEffect）
            // 统一裁决：源 IO 类首次失败被引擎静默自愈重试，只有未兜住的最终错误才进错误态（2026-09-14）
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoW = size.width
                videoH = size.height
                // 显示几何只在真实尺寸时更新：解码器换档会报 0x0（videoW/H 需归零供
                // NvStallRecovery 的 geometry-LOST 看门狗判定），display 若跟随归零会重新
                // 穿透 16:9 fallback（换档瞬间画面拉伸闪动）
                if (size.width > 0 && size.height > 0) {
                    displayW = size.width
                    displayH = size.height
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                hasTextTracks = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                trackRefresh++
                // 2026-10-22 连点压测注：切集定向不走轨道事件——控制器「媒体项先更新、轨道后
                // 更新」，同步滞后期间的事件可能携带上一集宽高，无法与真实事件区分（事件与
                // currentTracks 同源，互相印证无效）。定向统一由 seq 效果内的轮询完成，唯一
                // 可信信号是 player.videoSize（解码输出，必属当前集）。视频轨缺失侦测（Profile 5 黑屏归因）在心跳里持续判定，见下方 LaunchedEffect 注释
                // side-load 字幕就绪后应用 track override，确保默认选中（C6 §3.1）
                val pending = pendingSideLoadSelect
                if (pending != null) {
                    val match = tracks.groups.firstOrNull { g ->
                        g.type == C.TRACK_TYPE_TEXT &&
                            (0 until g.length).any { g.getTrackFormat(it).id == pending }
                    }
                    if (match != null) {
                        setTextTrack(player, match)
                        pendingSideLoadSelect = null
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 2026-09-15 D6 音量增益（VLC/MX 同款能力）：LoudnessEnhancer 挂音频会话，
    // 档位来自设置（1.0 关 / 1.5 / 2.0）；引擎重建（错误重试/换集）自动重挂
    DisposableEffect(engine, playerPrefs?.get(com.novax.app.player.KEY_VOLUME_BOOST)) {
        val boost = playerPrefs?.get(com.novax.app.player.KEY_VOLUME_BOOST)?.toFloatOrNull() ?: 1.0f
        // 倍数→毫贝尔：×2 ≈ +6dB（600mB），×1.5 ≈ +3.5dB（350mB）；经引擎侧挂真 ExoPlayer 音频会话
        engine.setVolumeBoostGainMb(((kotlin.math.ln(boost) / kotlin.math.ln(2.0f)) * 600f).toInt())
        onDispose { engine.setVolumeBoostGainMb(0) }
    }

    // 错误接线（2026-09-14 快进报错修复，配合引擎自愈）：
    // - onSourceIoRetry：引擎首次源 IO 错误已就地静默重试，只弹轻提示不进错误态；
    // - onFatalError：二次失败 / 非 IO 类错误才显示全屏错误态，文案按 errorCode 精确映射（errorMessageFor）。
    DisposableEffect(engine) {
        engine.onSourceIoRetry = {
            Toast.makeText(context, context.getString(R.string.player_io_retry_toast), Toast.LENGTH_SHORT).show()
        }
        engine.onFatalError = { error ->
            // 2026-09-15 真机诊断固化：把 errorCode + 完整 cause 链打进 NvError，
            // 便于定位「拖动进度条后 SMB 读取报错/画面黑屏」这类源层故障（此前只有 errorCodeName，
            // 详情在 AnalyticsListener 的另一条日志里，真机排障时会被 logcat 滚动冲掉）。
            Log.w("NvError", "fatal code=${error.errorCodeName}(${error.errorCode}) msg=${error.message}")
            var cause: Throwable? = error.cause
            var depth = 0
            while (cause != null && depth < 8) {
                Log.w("NvError", "  caused by ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            lastFatalWasNetwork = isNetworkRecoverableErrorCode(error.errorCode)   // 自动重连判定依据
            autoReconnect.onFatalError(lastFatalWasNetwork)
            errorMessage = errorMessageFor(error)
            fallbackRetryable = fallbackRetryableFor(error)
        }
        // 出帧看门狗信号源改为下方的独立 DisposableEffect 注册（需随 engine 变化重挂）
        // 补空窗：引擎创建后、本接线生效前已发生的最终错误（如初始 prepare 即失败）
        engine.consumePendingFatalError()?.let {
            lastFatalWasNetwork = isNetworkRecoverableErrorCode(it.errorCode)
            autoReconnect.onFatalError(lastFatalWasNetwork)
            errorMessage = errorMessageFor(it)
            fallbackRetryable = fallbackRetryableFor(it)   // 与 onFatalError 同规则（此前漏置，软解按钮不出现）
        }
        onDispose {
            engine.onSourceIoRetry = null
            engine.onFatalError = null
        }
    }

    // ---- 网络恢复自动重连（调研 backlog 项，2026-09-17）----
    // 仅当致命错误为网络类时挂系统网络监听：WiFi/数据/以太网任一恢复 → 交给
    // NvAutoReconnect 状态机（预算 3 次防无限循环）自动走与「重试」按钮相同的真重试。
    // 非网络类错误（文件不存在/解码失败等）不挂监听，保持手动。
    DisposableEffect(engine, errorMessage != null, lastFatalWasNetwork) {
        if (errorMessage == null || !lastFatalWasNetwork) {
            return@DisposableEffect onDispose { }
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (errorMessage != null && lastFatalWasNetwork) {
                        autoReconnect.onNetworkAvailable()
                    }
                }
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    // 播放成功即重置自动重连预算（同一次观看中的多次断网各自享有全额额度）
    LaunchedEffect(isPlaying, errorMessage) {
        if (isPlaying && errorMessage == null) autoReconnect.onPlaybackStarted()
    }
    // 位置/缓冲心跳：长按倍速期间提速到 200ms，底部进度条与倍速同速推进（C6 §2.3 修订）
    // 2026-09-15 追加：片尾卡住看门狗（见 onPlaybackFinished 上方注释）——真机复现的
    // 「播到文件尾永不出 ENDED」死锁在此收口，否则用户面对永久黑屏只能退出重进。
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0)
            val d = player.duration
            // 2026-09-19：引擎时长未就绪（TIME_UNSET/0）时保留现值（含库表时长提示）——
            // 原来直接覆写 0 会把提示抹掉，进度条在慢源解析窗口内完全死区
            durationMs = if (d != C.TIME_UNSET && d > 0) d else durationMs
            // ---- AB 循环（2026-09-17 功能项）：越过 B 点即跳回 A（播放中持续判定）----
            val loopBack = PlaybackFeatures.loopSeekBackMs(positionMs, features.loopAMs, features.loopBMs)
            if (loopBack != null) {
                Log.d("NvABLoop", "loop seek back: pos=$positionMs -> A=$loopBack (B=$features.loopBMs)")
                player.seekTo(loopBack)
            }
            if (features.loopAMs >= 0 || features.loopBMs >= 0) {
                Log.d("NvABLoop", "hb state A=$features.loopAMs B=$features.loopBMs pos=$positionMs")
            }
            // ---- 睡眠定时（2026-09-17 功能项）：到点暂停并清空 ----
            if (PlaybackFeatures.sleepFired(features.sleepDeadlineMs, System.currentTimeMillis())) {
                features.sleepDeadlineMs = -1L
                features.sleepMinutes = 0
                player.pause()
                Toast.makeText(context, context.getString(R.string.player_sleep_fired_toast), Toast.LENGTH_LONG).show()
            }
            // ---- 视频轨缺失侦测（杜比视界 Profile 5 静默丢弃，见 videoTrackMissingMessage）----
            // 必须放在心跳里持续判定：真机取证显示 onTracksChanged 触发时 duration 往往还没就绪，
            // 一次性检查会永久错过（首版实现即因此不生效，Profile 5 仍然只有黑屏没有任何提示）。
            if (errorMessage == null) {
                videoTrackMissingMessage(
                    player.currentTracks,
                    durationMs,
                    unknownCodecLabel = context.getString(R.string.player_unknown),
                )?.let { miss ->
                    errorMessage = miss.message
                    // 2026-09-19：杜比视界无解码器 → libVLC 软解可出基础层画面（nPlayer 同理），
                    // 给出软解重试按钮；其他编码 libVLC 也无解，不给
                    fallbackRetryable = miss.softDecoderRetryable
                }
            }
            // ---- 音轨缺失侦测（2026-09-19 新机 vivo 实测「视频在播、全程无声音」）----
            // 2026-09-29 FFmpeg 音频扩展后 AC3/EAC3/DTS/TrueHD 由 FFmpeg renderer 软解，
            // 本判定对这些编码不再触发；保留为通用安全网（FFmpeg 也没有的 mime 仍兜底）。
            // 原 mime 快路（KEY_SOFT_UNSUPPORTED_AUDIO_MIMES）随 mime 记忆删除。
            if (errorMessage == null && !audioSwitchDone && onFallbackRetry != null) {
                val tracksNow = player.currentTracks
                if (isPlaying && audioTrackMissing(tracksNow, durationMs)) {
                    audioSwitchDone = true
                    // i18n Task 9③：mime 展示名（资源）与软路由协议值（中性 token）拆分——
                    // 协议值不再携带任何语言文案，en/ko/ja 用户不再见中文 mime 串
                    val mime = audioMimeLabel(unselectedAudioMime(tracksNow)) { res -> context.getString(res) }
                    Log.w(
                        "NvAudio",
                        "audio track present but unselected (no decoder) → soft-decode handoff mime",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.player_audio_unsupported_toast, mime),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onFallbackRetry(SOFT_ROUTE_REASON_AUDIO)
                }
            }
            // ---- 片尾卡住看门狗 ----
            // 真机取证（两次押错判据的教训）：该状态**不是** STATE_BUFFERING（dumpsys 里
            // BUFFERING 计数为 0），也不是 ENDED；表现为「位置冻结在 duration 附近 + 缓冲已满到
            // duration + 没有错误」。因此判据必须基于「位置是否还在推进」，而不是播放状态值。
            val nearEnd = durationMs > 0 && positionMs >= durationMs - END_STALL_NEAR_END_MS
            val bufferedToEnd = durationMs > 0 && bufferedMs >= durationMs - END_STALL_NEAR_END_MS
            if (nearEnd && bufferedToEnd) {
                if (positionMs != lastStallPositionMs) {
                    lastStallPositionMs = positionMs
                    endStallSince = SystemClock.elapsedRealtime()
                }
                if (!endStallHandled &&
                    SystemClock.elapsedRealtime() - endStallSince >= END_STALL_TIMEOUT_MS
                ) {
                    endStallHandled = true
                    Log.d("NvPerf", "end-of-file stall detected pos=$positionMs dur=$durationMs -> finish")
                    onPlaybackFinished()
                }
            } else {
                endStallSince = 0L
                lastStallPositionMs = -1L
                // 位置已明显离开片尾（用户拖动/换片）→ 允许下一次片尾再收口
                if (durationMs > 0 && positionMs < durationMs - END_STALL_RESET_DISTANCE_MS) {
                    endStallHandled = false
                }
            }
            // ---- 视频渲染器出帧看门狗见下方（判据见下）----
            // ---- 看门狗①：seek 之后「位置在推进但一帧都没出」= 用户报的「进度条在走、画面没了」----
            // 判据只用在 seek 之后（帧计数基线来自 NvStallRecovery.noteSeek），
            // 因此不会像「sinceFrame 涨到多少秒」那样在正常播放里误报。
            run {
                val nowRt = SystemClock.elapsedRealtime()
                val advanced = positionMs - noFrameBaselinePositionMs >= NO_FRAME_MIN_POSITION_ADVANCE_MS
                val framesSinceSeek = NvStallRecovery.framesSinceSeek
                val conditions = errorMessage == null && positionMs > 1_000 && durationMs > 0 &&
                    !isBuffering && framesSinceSeek == 0L &&
                    positionMs < durationMs - END_STALL_NEAR_END_MS
                if (conditions && advanced) {
                    if (noFrameSinceMs == 0L) {
                        noFrameSinceMs = nowRt
                    }
                    if (positionMs != stallLoggedAtPosition) {
                        stallLoggedAtPosition = positionMs
                        Log.w(
                            "NvPerf",
                            "NO-PICTURE: pos=${positionMs} advanced but framesSinceSeek=0 " +
                                "for ${nowRt - noFrameSinceMs}ms size=${videoW}x$videoH " +
                                "sizeChanges=${NvStallRecovery.videoSizeChanges} playing=${player.isPlaying}",
                        )
                    }
                    if (nowRt - noFrameSinceMs >= NO_FRAME_RECOVER_MS) {
                        // 自愈分两件事，各自限流：
                        //   ① 重新 prepare（重建解复用/解码管线）——受 MAX_STALL_RECOVER 上限保护；
                        //   ② 重挂 surface（setOutputSurface，修复断开的 bufferqueue）——不设上限，
                        //      因为它只是重挂、不重启解码，是唯一能在换档后真正恢复出画的手段
                        //      （真机证据：C2MtkBufferManager: bufferqueue may disconnected）。
                        if (renderRecoverCount < MAX_STALL_RECOVER) {
                            renderRecoverCount++
                            Log.w("NvPerf", "NO-PICTURE recover#$renderRecoverCount -> forceRePrepare")
                            // BUG-C-R1-05：心跳 LaunchedEffect(Unit) 捕获首帧 engine——自适应重建后
                            // 旧引擎已 release，直调 forceRePrepare 是静默 no-op（自愈空转约 12s 黑屏）。
                            // 与 seekViaEngine 同款活跃引擎路由。
                            val activeEng = if (engine.isReleased) PlaybackService.activeEngine else null
                            when {
                                activeEng != null -> activeEng.forceRePrepare()
                                !engine.isReleased -> engine.forceRePrepare()   // 内部会连带触发 notifyNoPictureRecover
                                else -> Log.w("NvPerf", "forceRePrepare dropped: no live engine")
                            }
                        } else {
                            Log.w("NvPerf", "NO-PICTURE rebind-only (prepare 自愈已用尽)")
                            NvStallRecovery.notifyNoPictureRecover("watchdog rebind-only")
                        }
                        noFrameSinceMs = 0L
                    }
                } else {
                    noFrameSinceMs = 0L
                    if (framesSinceSeek > 0L) {
                        noFrameBaselinePositionMs = positionMs
                    }
                }
            }
            if (videoW != lastSeenVideoW || videoH != lastSeenVideoH) {
                val lost = (videoW <= 0 || videoH <= 0) && lastSeenVideoW > 0 && lastSeenVideoH > 0
                lastSeenVideoW = videoW
                lastSeenVideoH = videoH
                if (lost) {
                    // 只有「曾经有画面 → 突然归零」才是异常（解码器换档/重配），起播时本来就是 0x0
                    Log.w(
                        "NvPerf",
                        "video geometry LOST -> ${videoW}x$videoH (解码器换档/重配，surface 已重挂)",
                    )
                } else {
                    Log.d(
                        "NvPerf",
                        "video geometry -> ${videoW}x$videoH changes=${NvStallRecovery.videoSizeChanges}",
                    )
                }
            }
            // 心跳快照（每 5 秒的偶数窗口内 500ms 一条）：真机排障的可信信号源。
            // 注：判据必须按 elapsedRealtime()/5000 的**区间**取整，早先写成 %2==0 会变成
            // 「10 秒连打 10 条、再静默 5 秒」，排障时容易误以为心跳停了（已修）。
            if (SystemClock.elapsedRealtime() / 5000 % 2 == 0L) {
                Log.d(
                    "NvHbDiag",
                    "hb pos=$positionMs buf=$bufferedMs dur=$durationMs playing=${player.isPlaying} " +
                        // 2026-09-19 加音量/倍速字段：用户反馈「没声音」时先看 vol 是否被压到 0
                        //（变速静音残留）还是 1（下游：直通/格式/焦点问题），一步分层
                        "vol=${player.volume} spd=$speed shift=$features.speedShiftActive " +
                        "buffering=$isBuffering framesSinceSeek=${NvStallRecovery.framesSinceSeek} " +
                        "frameAge=${frameRenderedAt.let { if (it == 0L) -1L else SystemClock.elapsedRealtime() - it }} " +
                        "size=${videoW}x$videoH sizeChanges=${NvStallRecovery.videoSizeChanges} " +
                        "recover=$renderRecoverCount noFrameMs=${if (noFrameSinceMs == 0L) 0L else SystemClock.elapsedRealtime() - noFrameSinceMs}",
                )
            }
            // ---- 渲染器僵死看门狗：已撤除（判据无法成立，见下）----
            // 尝试过三代判据，全部不成立，如实记录以免后人重踩：
            //   ① position 不推进 → 无效：僵死时 position 每 500ms 稳定 +500ms（播放器自认正常）；
            //   ② 位置推进 + 缓冲领先 → 同上，无效；
            //   ③ 渲染器出帧事件（AnalyticsListener.onRenderedFirstFrame）→ **会误报**：
            //      该事件每次 prepare/seek 后只触发一次，不是每帧信号，正常播放时
            //      sinceFrame 也会涨到数十秒（真机实测 53s），导致连续误判并打断正常播放。
            // 即：**公开 API 拿不到「每帧是否真的画到屏幕上」的信号**。
            // 僵死的确切触发条件仍待取证（用户侧录屏最直接），在拿到证据前不再用错误判据改动播放行为。
            delay(if (features.speedShiftActive) SPEED_SHIFT_HEARTBEAT_MS else 500)
        }
    }

    // 换片（队列入队参数变化）→ 片尾卡住标记必须复位，否则下一集到片尾不再收口
    LaunchedEffect(title, durationMs) {
        endStallSince = 0L
        endStallHandled = false
    }

    // 缓冲 loader 延迟 300ms（C6 §4.1 / C8 §5.2）
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            delay(LOADER_DELAY_MS)
            loaderVisible = isBuffering
        } else {
            loaderVisible = false
        }
    }

    // 控制层 4s 自动隐藏
    LaunchedEffect(controlsVisible, controlsTick) {
        if (controlsVisible && !locked) {
            delay(CONTROL_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // ---- C10 设置接线（novax_player_prefs）----
    // 3x 及以上自动静音（设置键沿用 novax_mute_above_2x，2026-09-18 阈值由 >2x 提至 ≥3x）：
    // 按当前生效速度开/关播放音量。档位封顶 3x，条件必须含等号，否则无生效档位
    val applySpeedMute = { activeSpeed: Float ->
        val mute = activeSpeed >= 3f - 0.01f && (playerPrefs?.get(KEY_MUTE_ABOVE_2X) ?: DEFAULT_MUTE_ABOVE_2X)
        // 活跃引擎兜底（与上方 seekViaEngine/setSpeedViaEngine 同口径）：gestureEvents 是无 key
        // remember，会把本 lambda 连同首帧 player 一起捕获到整个组合生命周期——引擎重建（错误
        // 重试/自适应升档/音轨缺失转软解）后旧 player 已 release，直写 volume 被静默丢弃，
        // 表现为长按 3x 自动静音失效。engine 引用同样可能是旧实例，isReleased 在调用时读取即自愈
        val target = if (engine.isReleased) PlaybackService.activeEngine?.player else player
        if (target != null) {
            target.volume = if (mute) 0f else 1f
        } else {
            Log.w("NvPerf", "speedMute dropped: no live engine")
        }
    }
    // 起播初速（novax_playback_speed_mode / novax_playback_last_speed，仅首帧设置一次）：
    // fixed → 1x；remember → 恢复上次倍速（跨会话记忆）。
    // key=engine：错误重试重建引擎后，把当前档位速度应用到新引擎。
    var initialSpeedApplied by remember { mutableStateOf(false) }
    LaunchedEffect(engine, playerPrefs) {
        val p = playerPrefs ?: return@LaunchedEffect
        // 精确跳转（novax_precise_seek）：seek 分级实时切换 EXACT（C8 §1 L3）
        engine.exactSeekMode = p[KEY_PRECISE_SEEK] ?: false
        if (!initialSpeedApplied) {
            initialSpeedApplied = true
            if ((p[KEY_SPEED_MODE] ?: "fixed") == "remember") {
                val restored = (p[KEY_LAST_SPEED] ?: 1f).coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)
                if (abs(restored - 1f) > 0.01f) {
                    speed = restored
                    engine.setPlaybackSpeed(restored, pitch = pitchFor(restored, p[KEY_PRESERVE_PITCH] ?: true))
                }
            }
        } else {
            engine.setPlaybackSpeed(speed, pitch = pitchFor(speed, p[KEY_PRESERVE_PITCH] ?: true))
        }
        // >3x 静音即时应用：开关切换 / 速度变化后都按当前生效速度刷新（novax_mute_above_2x）
        applySpeedMute(speed)
    }
    // 退出播放器记录「上次倍速」（novax_playback_last_speed；长按结束已恢复，此值即用户档位）。
    // 记 Compose 镜像态 speed 而非 playbackParameters：dispose 晚于 Activity.onDestroy 的 engine.release()
    DisposableEffect(Unit) {
        onDispose { context.recordLastSpeed(speed) }
    }

    // 无字幕横幅：进入 2s 后出现，8s 自动收起（C6 §4.2）
    // 有同目录字幕候选时改由「选择字幕」面板承接，横幅仅在用户放弃手选后出现
    LaunchedEffect(hasTextTracks, subtitleCandidates) {
        if (!hasTextTracks) {
            if (subtitleCandidates.isEmpty()) {
                delay(2000)
                showNoSubtitleBanner = true
                delay(8000)
                showNoSubtitleBanner = false
            }
        } else {
            showNoSubtitleBanner = false
        }
    }

    // 续播提示条（2026-09-15 用户需求）：出现后 5 秒自动收起；跳转/关闭立即收起。
    // 换片（队列切换）宿主先置 null 再设新值 → LaunchedEffect 以 resumePositionMs 为 key 重启计时
    var resumeBannerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(resumePositionMs) {
        if (resumePositionMs != null) {
            resumeBannerVisible = true
            delay(5_000)
            resumeBannerVisible = false
        } else {
            resumeBannerVisible = false
        }
    }

    // 双击指示 800ms 后淡出（C6 §2.2）
    LaunchedEffect(showDblSeek, dblSeekTotal) {
        if (showDblSeek) {
            delay(INDICATOR_FADE_MS)
            showDblSeek = false
            // 2026-09-18 修复：浮层收起即清零累计值——否则跨次双击累加（+15s 后再 -15s 显示「-0s」）
            dblSeekTotal = 0
        }
    }

    val gestureEvents = remember {
        GestureEvents(
            onSingleTap = {
                when {
                    // 全锁态本分支不可达（isLocked 已吞掉一切，仅提示），兜底同提示
                    locked -> lockHintVisible = true
                    // 2026-10-08 半锁：单击只提示不弹控制层（用户口径「点击屏幕不会出进度条」）
                    halfLocked -> lockHintVisible = true
                    else -> {
                        controlsVisible = !controlsVisible
                        controlsTick++
                    }
                }
            },
            onDoubleTapSeek = { offset ->
                val side = if (offset < 0) -1 else 1
                // 同侧连击累加（B 站同款 +2N/+3N）；换侧或浮层已收起则重新起算
                if (showDblSeek && dblSeekSide == side) dblSeekTotal += offset else dblSeekTotal = offset
                dblSeekSide = side
                showDblSeek = true
                seekViaEngine(offset, false)
                controlsTick++
            },
            onDoubleTapCenter = {
                // 2026-09-20 三区双击：中间区 = 播放/暂停（与底栏暂停键同语义；区宽 1/2→3/5，2026-10-16）。
                // 经 togglePlayViaEngine 活跃引擎兜底：引擎重建后旧 player 已释放，直调会静默失效
                togglePlayViaEngine()
                controlsTick++
            },
            onSpeedShiftStart = { fast ->
                // 立即以 playbackParameters 变速（C6 §2.3 修订；pitch 由「变速不变调」键裁决，C10）。
                // BUG-C-R1-04：已锁定时再次长按不得改写锁定值（0.5x 锁定被 2x 静默替换）——
                // 重设为锁定值本身，松手不下滑则仍保持锁定
                // 2026-09-28 审计 A：目标值判定收敛 PlaybackFeatures.longPressTargetSpeed
                //（锁定保持现值——BUG-C-R1-04；clamp 防 DataStore 异常速率，与 RMVB 页同口径）
                val spd = PlaybackFeatures.longPressTargetSpeed(
                    locked = features.speedShiftLocked,
                    currentValue = features.speedShiftValue,
                    fastForward = fast,
                    configuredSpeed = playerPrefs?.get(KEY_LONG_PRESS_SPEED) ?: PlaybackEngine.LONG_PRESS_SPEED,
                    slowmoSpeed = PlaybackEngine.LEFT_SLOWMO_SPEED,
                )
                features.speedShiftActive = true
                features.speedShiftValue = spd
                setSpeedViaEngine(spd, pitchFor(spd, playerPrefs?.get(KEY_PRESERVE_PITCH) ?: true))
                applySpeedMute(spd)
                Log.d("NvGesture", "speed=$spd on")
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                controlsTick++
            },
            onSpeedShiftEnd = {
                // 恢复按住前的速度（倍速菜单记忆值，如 1.5x，而非固定 1.0x）；
                // 2026-09-19：下滑锁定期间松手不恢复（锁定语义本体）
                if (features.speedShiftActive && !features.speedShiftLocked) {
                    features.speedShiftActive = false
                    setSpeedViaEngine(speed, pitchFor(speed, playerPrefs?.get(KEY_PRESERVE_PITCH) ?: true))
                    applySpeedMute(speed)
                    Log.d("NvGesture", "speed=$speed off")
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    controlsTick++
                }
            },
            onSpeedShiftLockGesture = {
                // 2026-09-19 用户需求：长按倍速期间下滑 = 锁定/解锁切换（同一手势只触发一次）
                if (features.speedShiftActive) {
                    if (!features.speedShiftLocked) {
                        features.speedShiftLocked = true
                        Toast.makeText(context, context.getString(R.string.player_speed_locked_toast), Toast.LENGTH_SHORT).show()
                    } else {
                        features.speedShiftLocked = false
                        features.speedShiftActive = false
                        setSpeedViaEngine(speed, pitchFor(speed, playerPrefs?.get(KEY_PRESERVE_PITCH) ?: true))
                        applySpeedMute(speed)
                        Toast.makeText(context, context.getString(R.string.player_speed_unlocked_toast), Toast.LENGTH_SHORT).show()
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    controlsTick++
                }
            },
            leftSlowmo = { playerPrefs?.get(KEY_LEFT_SLOWMO) ?: false },
            longPressEnabled = { playerPrefs?.get(KEY_LONG_PRESS_ON) ?: true },
            longPressSpeed = {
                (playerPrefs?.get(KEY_LONG_PRESS_SPEED) ?: PlaybackEngine.LONG_PRESS_SPEED)
                    .coerceIn(0.5f, 3f)
            },
            doubleTapSec = { playerPrefs?.get(KEY_DOUBLE_TAP_SEC) ?: DEFAULT_DOUBLE_TAP_SEC },
            swipeFullPercent = { playerPrefs?.get(KEY_SWIPE_FULL_PERCENT) ?: 10 },
            swipeDurationMs = { durationMs },
            preservePitch = { playerPrefs?.get(KEY_PRESERVE_PITCH) ?: true },
            currentBrightness = { brightnessVal },
            onBrightness = {
                brightnessVal = it
                showBrightnessBar = true
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = it
                }
                controlsTick++
            },
            currentVolume = { volumeVal },
            onVolume = {
                volumeVal = it
                showVolumeBar = true
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (it * maxVolume).roundToInt().coerceIn(0, maxVolume),
                    0,
                )
                controlsTick++
            },
            onSeekPreview = {
                seekPreviewDelta = it
                showSeekPreview = true
                controlsTick++
            },
            onSeekCommit = { delta ->
                showSeekPreview = false
                // 相对 seek：基准位置由 seekViaEngine 从「活跃引擎」取，避免用到已释放引擎的位置
                seekViaEngine(delta, false)
                controlsTick++
            },
            onSeekCancel = { showSeekPreview = false },
            onOverlayEnd = {
                showBrightnessBar = false
                showVolumeBar = false
            },
            onLockedTap = { lockHintVisible = true },
            isLocked = { locked },
            // ---- 双指缩放（B 站同款）----
            currentZoom = { zoomState.value },
            // 2026-09-29 进度条拖动抢触修复：控制层可见时提供其 band 高度，父层手势对
            // 该区域内的按下整体退场（隐藏/锁定/错误态 = 0，不设防）
            controlsHeightPx = {
                if (controlsVisible && !locked && errorMessage == null) controlsBandHeightPx else 0f
            },
            zoomContentDims = {
                // rotation 90/270 时画面以「宽高互换的虚拟画布」计算再整体旋转（见 VideoSurface），
                // 平移夹紧按旋转后的可见足迹口径给入（宽高互换）
                val rotated = videoRotation == 90 || videoRotation == 270
                val (sw, sh) = screenSizePx
                val sx = if (rotated) sh else sw
                val sy = if (rotated) sw else sh
                val va = if (displayW > 0 && displayH > 0) displayW.toFloat() / displayH else 16f / 9f
                PlayerZoomPolicy.contentDims(aspect, va, sx, sy)
            },
            onPinchStart = {
                showZoomHint = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                Log.d("NvPerf", "pinch zoom start from scale=${zoomState.value.scale}")
            },
            onPinch = { zoomState.value = it },
            onPinchEnd = {
                zoomTick++
                val z = zoomState.value
                Log.d("NvPerf", "pinch zoom end scale=${z.scale} pan=(${z.panX},${z.panY})")
            },
        )
    }

    // 退出播放恢复系统亮度（C6 §2.4）
    DisposableEffect(Unit) {
        val original = activity?.window?.attributes?.screenBrightness
        onDispose {
            original?.let { activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = it } }
        }
    }

    // 2026-09-15 修复（拖动到片尾黑屏死锁）：预览落点与真实 seek 落点必须同一口径——
    // 都经 clampSeekTarget 夹到片尾前 1 秒，否则气泡显示的位置与实际 seek 位置不一致
    val dragSeekTarget = clampSeekTarget(positionMs + seekPreviewDelta, durationMs)

    // C12 反馈：顶栏「方向切换」——横屏 ↔ 竖屏（系统未锁时可达自动·重力），Toast 提示当前模式
    // （2026-09-14 断流修复：configChanges 覆盖下 Activity 不重建、surface 只重布局不解绑，
    //   正常路径不应出现任何 onCreate/onDestroy；真机若见重建日志即 OEM 未守 configChanges）
    // 2026-10-17 用户需求：重力跟随改听系统旋转锁定（自有「重力感应自动旋转」设置已删）——
    // 锁定 = 不跟重力（默认横屏、循环仅横↔竖）；未锁 = 进页默认自动·重力、三档循环。
    // FULL_SENSOR 只在系统未锁时施加；系统锁定状态只读永不写（用户红线）。
    var systemRotationLocked by remember {
        mutableStateOf(NvOrientationPolicy.isSystemRotationLocked(context))
    }
    // 只读监听：播放中下拉快捷开关切系统锁定即时生效（主线程回调，退出注销）
    DisposableEffect(activity) {
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean) {
                systemRotationLocked = NvOrientationPolicy.isSystemRotationLocked(context)
            }
        }
        context.contentResolver.registerContentObserver(NvOrientationPolicy.rotationLockUri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    // 2026-10-22 连点压测重构记录：本处原为「pending 一次性标记 + 宽高状态驱动」的轮询应用协程，
    // 压测发现两处不可修复的竞态后整体删除——
    //   ① 快速连点两切 ~300ms 内连发，上一集的宽高事件抢消费 pending，本集横屏永久停竖屏；
    //   ② 轮询的状态快照会踩中「媒体项已切新集、轨道还是上一集」的失配窗口（控制器两者
    //      同步有先后），按陈旧宽高定向后同样消费 pending 无法纠正。
    // 定向应用已迁往两处事件安全点：onTracksChanged 快路径（事件参数即同步后状态）+
    // seq 效果尾部 1.6s 延迟兜底（同步必已完成）。世代安全由 LaunchedEffect(seq) 取消语义保证。
    // 锁定（重做后唯一语义）：钉死当前实际朝向的具体值，不用 SCREEN_ORIENTATION_LOCKED
    val pinCurrentOrientation: () -> Unit = {
        val portrait = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        pinnedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        activity?.requestedOrientation = pinnedOrientation
    }
    val cycleOrientation: () -> Unit = {
        val oldMode = orientationMode
        orientationMode = if (!systemRotationLocked) {
            // 系统未锁：自动 → 横屏 → 竖屏 → 自动
            when (orientationMode) {
                OrientationMode.AUTO_SENSOR -> OrientationMode.LANDSCAPE
                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                else -> OrientationMode.AUTO_SENSOR
            }
        } else {
            // 2026-10-17：系统锁定 = 仅横屏 ↔ 竖屏两种（重力不可用）
            when (orientationMode) {
                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                else -> OrientationMode.LANDSCAPE
            }
        }
        // 2026-10-14(spec §2.2-1): 方向模式真实变化→画面旋转归零(不跨方向存活,防旋转虚拟画布跨窗口resize失步);
        // 严禁移入 applyOrientationMode——ON_RESUME 重申路径会误清(spec Review Focus 1)
        videoRotation = NvOrientationPolicy.rotationAfterModeChange(oldMode, orientationMode, videoRotation)
        applyOrientationMode()
        Log.d("NvPerf", "orientation mode=$orientationMode rotation=$videoRotation")
        Toast.makeText(
            context,
            context.getString(R.string.player_orientation_toast, context.getString(orientationMode.labelRes)),
            Toast.LENGTH_SHORT,
        ).show()
        controlsTick++
    }
    // 2026-10-17 用户需求：系统旋转锁定 → 方向模式收敛（替代原偏好开关双向驱动）——
    // 锁定：自动·重力 → 当前实际朝向固定档（画面不跳）；解锁：立即恢复自动·重力。
    // 初次组合亦走此路 = 进页默认档（未锁=自动·重力，锁定=横屏）。收敛不弹 toast
    // （旋转即反馈，与原开关路径口径一致；用户动作的 cycleOrientation 才 toast）。
    // 钉死期间「只收敛档位状态、不施加」：pin 继续钉原朝向不被覆盖，解除钉死时
    // 解锁路径的 applyOrientationMode 自然按已收敛档位施加——堵「钉死中切系统锁定→
    // 解除钉死→陈旧自动档施加 FULL_SENSOR 破坏系统锁定」漏洞（评审第 2 轮）。
    LaunchedEffect(systemRotationLocked) {
        val target = NvOrientationPolicy.modeAfterSystemRotationLockChange(
            systemRotationLocked, orientationMode,
            OrientationMode.AUTO_SENSOR, OrientationMode.PORTRAIT, OrientationMode.LANDSCAPE,
            context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT,
        ) ?: return@LaunchedEffect
        val oldMode = orientationMode
        orientationMode = target
        // 2026-10-14(spec §2.2-2) 口径：模式真实变化清画面旋转
        videoRotation = NvOrientationPolicy.rotationAfterModeChange(oldMode, target, videoRotation)
        // BUG-C-R1-02 口径演变：钉死期间绝不 applyOrientationMode（会覆盖 pinnedOrientation
        // 致方向漏转）；改为仅收敛状态，解除钉死时生效
        // 2026-10-22 同口径扩展：切集待定向（pendingAutoOrientation）期间也只收敛不施加——
        // 否则解锁态下收敛成 AUTO_SENSOR 会在新集宽高就绪前把方向打回 FULL_SENSOR，正是
        // 「竖屏视频切下一集先横后竖」的加载窗口闪烁源；宽高到达后自动定向一次性落位
        if (!locked && !pendingAutoOrientation) {
            applyOrientationMode()
            controlsTick++
        }
    }
    // 2026-09-19 方向漂移根治兜底：回前台（锁屏解锁/切回应用）重申朝向——
    // 锁定时重申 pinnedOrientation；未锁按当前方向模式重申（等值时是 no-op，不打断播放）
    DisposableEffect(activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 2026-10-17：回前台重读系统锁定（后台期间切换的兜底；等值时收敛效果为 no-op）
                systemRotationLocked = NvOrientationPolicy.isSystemRotationLocked(context)
                // 2026-10-22：切集待定向期间不重申（切换加载窗口钉住当前朝向，见 seq 效果注释）
                if (locked) {
                    activity?.requestedOrientation = pinnedOrientation
                } else if (!pendingAutoOrientation) {
                    applyOrientationMode()
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    // C12 反馈：顶栏「画面旋转」——0°→90°→180°→270° 循环（graphicsLayer rotationZ，控制层显隐间保持）
    val rotateVideo: () -> Unit = {
        videoRotation = (videoRotation + 90) % 360
        controlsTick++
    }
    // 2026-10-11 用户需求:顶栏「画面镜像」(水平翻转)——状态翻转即可,镜像走 View 级 scaleX,
    // 由重组驱动 AndroidView.update 重放(spec §4.2,无需 RMVB 式显式施加);Toast 反馈开关态
    val toggleVideoMirror: () -> Unit = {
        videoMirrored = !videoMirrored
        controlsTick++
        Toast.makeText(
            context,
            context.getString(if (videoMirrored) R.string.player_mirror_on else R.string.player_mirror_off),
            Toast.LENGTH_SHORT,
        ).show()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { screenSizePx = it.width.toFloat() to it.height.toFloat() }
            .pointerInput(Unit) { detectPlayerGestures(gestureEvents) },
    ) {
        // Liquid Glass（decisions.md §4.5 Task 10 修订）：source 挂「视频+字幕」包裹容器——
        // VideoSurface（内含 surface 容器与 SubtitleView 两个 AndroidView）整体包进全屏 Box 挂
        // nvGlassSource，控制条/弹窗面板/浮层胶囊全部做 source 兄弟，弹窗玻璃由此生效。
        // 黑底写在 nvGlassSource 之后（§4.5a：录制须含背景；与根 Box 同为纯黑，视觉零变化）。
        // VideoSurface.kt 本体与 surface 协议零触碰（AndroidView 单容器/重挂/View 级矩阵不动）。
        Box(Modifier.fillMaxSize().nvGlassSource(com.novax.app.ui.glass.LocalNvGlassEnv.current).background(Color.Black)) {
            // 重挂键合成（见 VideoSurface.geometryResetKey 注释）：display 值 + 真实几何丢失
            // 标志——切集零多余重挂，解码器换档（videoW/H 归 0 而 display 保持）仍触发自愈
            val geometryResetKey =
                "${displayW}x${displayH}-" + if (videoW <= 0 && videoH <= 0 && displayW > 0) "lost" else "ok"
            VideoSurface(
                player,
                aspect,
                displayW,
                displayH,
                videoRotation,
                seekSeq = seekSeq,
                recoverSeq = noPictureRecoverSeq,
                geometryResetKey = geometryResetKey,
                cues = cues?.cues ?: emptyList(),
                subtitleSizeSp = 18f * (playerPrefs?.get(com.novax.app.player.KEY_SUBTITLE_SIZE)?.toFloatOrNull() ?: 1.0f),
                zoomState = zoomState,
                mirrored = videoMirrored,
            )
            // 切集黑幕（盖 VideoSurface，含其字幕层）：旧帧不再被转屏拉伸变形
            if (switchBlackout) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // 双指缩放百分比提示（捏合期间常显，结束 800ms 后淡出；锁屏时不显示）
        AnimatedVisibility(
            visible = showZoomHint && !locked && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 96.dp),
        ) {
            Text(
                "${(zoomState.value.scale * 100).roundToInt()}%",
                style = NvTypography.TitleMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(NvColors.Scrim)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 续播提示条（2026-09-15）：顶部居中悬浮，独立于控制层显隐；5 秒自动收起
        AnimatedVisibility(
            visible = resumeBannerVisible && resumePositionMs != null && errorMessage == null,
            enter = fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) +
                slideInVertically(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) { -it },
            exit = fadeOut(tween(NvMotion.DurationNormal)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // Liquid Glass（spec §10 浮层清单 5/12）：续播横幅 SurfaceElevated 0.95 实底→NvGlassChip
            //（容器无 clickable，交互仍在子级 TextButton/IconButton——chip 不带 onClick）
            NvGlassChip(
                modifier = Modifier.padding(top = 64.dp),
                shape = NvShape.Button,
            ) {
                Row(
                    Modifier.padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.player_resume_banner, formatTime(resumePositionMs ?: 0L)),
                        style = NvTypography.BodyMedium,
                        color = NvColors.TextPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        resumePositionMs?.let { seekViaEngine(it, true) }
                        resumeBannerVisible = false
                    }) {
                        Text(stringResource(R.string.player_resume_jump), color = NvColors.Primary, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { resumeBannerVisible = false }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.player_cd_close), Modifier.size(18.dp), tint = NvColors.TextSecondary)
                    }
                }
            }
        }

        // 中央手势指示浮层
        GestureIndicators(
            showBrightnessBar, brightnessVal,
            showVolumeBar, volumeVal,
            showSeekPreview, dragSeekTarget, seekPreviewDelta,
            showDblSeek, dblSeekSide, dblSeekTotal,
        )
        // 控制层（出错时隐藏，让位全屏错误态）。
        // 2026-09-20 动效重做：原来顶/底栏挤在一个全屏 Column 里随整体「从上掉下来」，割裂感根因；
        // 现拆成两块对齐的 AnimatedVisibility——顶栏走屏幕上缘、底栏走屏幕下缘（主流播放器同款），
        // 两块 visible 共用同一局部量保证同相位；时长/缓动统一走 NvMotion（主+RMVB 两播放器一致）。
        val controlsShown = controlsVisible && !locked && errorMessage == null
        // 顶栏：顶部渐变遮罩 + 顶栏，从上缘滑入/滑出
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) +
                slideInVertically(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) { -it },
            exit = fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) +
                slideOutVertically(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(NvColors.ScrimStrong, Color.Transparent))),
            ) {
                NvPlayerTopBar(
                    title = title,
                    subtitle = subtitle,
                    hasSubtitleTracks = hasTextTracks,
                    onBack = onBack,
                    onSubtitle = { sheet = PlayerSheet.SUBTITLE },
                    onAudio = { sheet = PlayerSheet.AUDIO },
                    onAspect = { sheet = PlayerSheet.ASPECT },
                    // 2026-09-18：画面旋转恢复独立顶栏按键（0/90/180/270 循环）；无视频流时点了无效果
                    onVideoRotate = rotateVideo,
                    // 2026-10-11:画面镜像开关(独立顶栏按键,先例:旋转合并进面板被否决)
                    videoMirrored = videoMirrored,
                    onVideoMirror = toggleVideoMirror,
                    onOrientation = cycleOrientation,
                    // 2026-09-18：画中画从底栏上移顶栏（底栏中心对称化）
                    onPip = onEnterPip,
                    // M3 清晰度面板入口（仅 Jellyfin 源显示；非 Jellyfin 传 null → 按钮隐藏，UI 零变化）
                    onQuality = if (isJellyfinSource) ({ sheet = PlayerSheet.QUALITY }) else null,
                    // 2026-09-15 用户需求：视频信息（分辨率/帧率/编码）
                    onInfo = { sheet = PlayerSheet.INFO },
                    // 2026-09-17 功能项：AB 循环 + 睡眠定时（低频聚合到「更多」）
                    onMore = { sheet = PlayerSheet.MORE },
                    // C13 §5 入口三：播放器顶栏投屏（有播放 URL 才显示）
                    onCast = if (playUrl != null) ({ showCastSheet = true }) else null,
                )
            }
        }
        // C13 §5 入口三：投屏设备选择——选中即以当前进度起播到电视，随后走正常退出
        // 路径（onBack 存历史/停引擎），本机停止
        // 2026-09-28 审计 P0-1：计量网络投屏确认（gate 无条件 remember，弹窗期间 sheet 可关）
        val castGate = rememberNvCastGateState()
        if (showCastSheet) {
            val castScope = rememberCoroutineScope()
            NvCastDeviceSheet(
                onDismiss = { showCastSheet = false },
                onCast = { device ->
                    castScope.launch {
                        val url = playUrl
                        if (url != null) {
                            val media = CastMediaResolver.resolve(context.applicationContext, url, title)
                            if (media != null) {
                                // 2026-09-28 审计 P0-1：投屏 = 经手机中继整片拉流，计量网络先确认
                                if (!castGate.awaitConsent(media)) {
                                    showCastSheet = false
                                    return@launch
                                }
                                CastSessionHolder.init(context.applicationContext)
                                CastSessionHolder.cast(device, media, offsetMs = positionMs)
                                // review I1：只在投屏真正发起成功时才退本机——失败（无 Wi-Fi/
                                // 取流失败等 Error 态）留在播放器，避免两头落空
                                val st = CastSessionHolder.session()?.state?.value
                                if (st !is CastUiState.Error) {
                                    onBack()
                                } else {
                                    android.widget.Toast.makeText(
                                        context, st.message, android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } else {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.player_cast_unsupported_toast), android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        showCastSheet = false
                    }
                },
            )
        }
        NvCastMeteredDialogHost(castGate)
        // 底栏：无字幕横幅 + 底部渐变遮罩 + 底栏，从下缘滑入/滑出
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) +
                slideInVertically(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) { it },
            exit = fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) +
                slideOutVertically(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth(),
            ) {
                // 无字幕提示横幅（顶在底栏上方）
                AnimatedVisibility(visible = showNoSubtitleBanner && controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            stringResource(R.string.player_no_subtitle_banner),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                            modifier = Modifier
                                .clip(NvShape.Input)
                                .background(NvColors.SurfaceElevated)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                // 「还原画面」按钮（2026-09-15 用户需求）：缩放态时出现，随底栏同进退——
                // 2026-09-20 从独立 align 块移入本 Column（横幅之下、渐变之上），
                // 横幅显隐时按钮与底栏同高度变化，不再有固定 152dp 的脱节位置
                AnimatedVisibility(visible = zoomed, enter = fadeIn(tween(NvMotion.DurationFast)), exit = fadeOut(tween(NvMotion.DurationFast))) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                        // Liquid Glass（spec §10 浮层清单 6/12，评审整改补列）：还原画面钮
                        // SurfaceElevated 0.92 实底→NvGlassChip（原 clickable 迁入 onClick）
                        NvGlassChip(
                            onClick = { resetZoom() },
                            shape = NvShape.Dialog,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp), tint = NvColors.Primary)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.player_reset_picture), style = NvTypography.LabelMedium, color = NvColors.TextPrimary)
                            }
                        }
                    }
                }
                // 底部渐变遮罩 + 底栏。2026-09-16 UI 审计 P1：原渐变只盖底栏自身且末端仅 70% 黑，
                // 亮画面（白墙/雪景）下白色图标与 20% 白轨道几乎隐形——加浓到 90% 黑、上探 40dp 过渡
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to NvColors.Scrim,
                                1f to NvColors.ScrimStrong,
                            ),
                        )
                        .padding(top = 40.dp),
                ) {
                    NvPlayerBottomBar(
                        positionMs = positionMs,
                        bufferedMs = bufferedMs,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        speed = speed,
                        onTogglePlay = {
                            // 同款活跃引擎兜底（与双击中间区共用入口）
                            togglePlayViaEngine()
                            controlsTick++
                        },
                        onSeek = { target ->
                            // 2026-09-30 兜底防线：坏索引 MKV 由 NvFallbackSeekExtractor 降级 seek
                            // （估算+Cluster 重同步）覆盖「Unseekable+长度已知」；长度未知等极端
                            // 片源时间线仍 unseekable——此时 seek 会被 Unseekable SeekMap 落回开头
                            // （真机实证 Rob.B.Hood），与其静默破坏播放位置，不如明确告知并拒绝
                            if (!player.isCurrentMediaItemSeekable) {
                                if (!unseekableToastShown) {
                                    unseekableToastShown = true
                                    Toast.makeText(context, context.getString(R.string.player_unseekable_toast), Toast.LENGTH_SHORT).show()
                                }
                                controlsTick++
                                return@NvPlayerBottomBar
                            }
                            // 绝对落点（进度条松手）：同样经统一入口，已释放引擎时改投活跃引擎
                            seekViaEngine(target, true)
                            controlsTick++
                        },
                        // 2026-09-18：快进/快退步长（设置档位，按钮小字随档位；与双击手势共用一步长）
                        skipSec = playerPrefs?.get(KEY_DOUBLE_TAP_SEC) ?: DEFAULT_DOUBLE_TAP_SEC,
                        lockMode = if (locked) 2 else if (halfLocked) 1 else 0,
                        // 相对 seek（底栏 ±Ns 按钮），与手势滑动同路径：取活跃引擎当前位置
                        onSkipBy = { delta ->
                            seekViaEngine(delta, false)
                            controlsTick++
                        },
                        // 2026-09-15 修复：进度条一旦开始被拖动，父层手势 seek 浮层立即收起
                        //（子节点接管本次滑动，两套 seek 预览不得共存；见 runHorizontalSeek）
                        onDragStart = { showSeekPreview = false },
                        // 2026-10-07 band 计量修正：band=滑块热区自身（原量整个控制层 Column，
                        // 竖屏量出 693px≈半屏高，屏幕中下部拖动全被 band 误拒——真机探针实证）
                        onSliderBandPx = { controlsBandHeightPx = it },
                        onSpeed = { sheet = PlayerSheet.SPEED },
                        // 2026-10-08 半锁两级：半锁开 = 0→半锁→全锁；半锁关 = 一下直进全锁。
                        // 全锁钉朝向（半锁不钉），解锁=左下角锁图标双击
                        // 2026-10-10 S1 诊断:五行矩阵全绿(证据 E:/tmp/s1-diag/),LaunchedEffect(controlsVisible, controlsTick) 交互重武装语义已满足 spec §1.3,不加代码
                        // 2026-10-10 用户需求「按下半锁，进度条立刻马上收起来，别等四秒」：半锁开即收控制层
                        //（升全锁改走左下角角标长按,原「控制层暂不收、可再点」口径退役）
                        onLock = {
                            val halfOn = playerPrefs?.get(KEY_HALF_LOCK_ENABLED) ?: true
                            when (nextLockLevel(if (halfLocked) LOCK_HALF else LOCK_NONE, halfOn)) {
                                LOCK_HALF -> {
                                    halfLocked = true
                                    controlsVisible = false
                                    // 2026-10-10 精简+口径随「立即收起」更新（升全锁入口=角标长按）
                                    Toast.makeText(context, context.getString(R.string.player_half_lock_toast), Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    pinCurrentOrientation()
                                    locked = true
                                    halfLocked = false
                                    controlsVisible = false
                                    Toast.makeText(context, context.getString(R.string.player_locked_toast), Toast.LENGTH_SHORT).show()
                                }
                            }
                            controlsTick++
                        },
                        onPrev = onPrevVideo,
                        onNext = onNextVideo,
                    )
                }
            }
        }

        // 长按倍速提示（2026-09-18 用户需求，B 站同款）：画面正上方小胶囊，随长按出现/松手消失。
        // 不随控制层显隐——长按倍速多发生在控制层隐藏时；黑底半透明 + 小尺寸，尽量不挡画面。
        // 2026-09-19：下滑锁定后 speedShiftActive 保持 true → 胶囊常驻显示，锁定态加锁图标
        AnimatedVisibility(
            visible = features.speedShiftActive && !locked && errorMessage == null,
            enter = scaleIn(tween(120)) + fadeIn(tween(120)),
            exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
        ) {
            // Liquid Glass（spec §10 浮层清单 7/12）：长按倍速胶囊 半透明黑浮层→NvGlassChip。
            // 2026-10-14 spec §4 决议：浮层玻璃化取代半透明黑惯例（本胶囊为浮层非容器）；
            // 胶囊形状与字号沿用设计 token（Pill）、倍速文本收敛 formatSpeedLabel（2026-10-09 B4/M2 口径不变）。
            // 纯指示浮层原无 clickable → chip 不带 onClick，点击穿透语义零变化
            NvGlassChip(
                shape = NvShape.Pill,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (features.speedShiftValue >= 1f) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                        null,
                        Modifier.size(14.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        formatSpeedLabel(features.speedShiftValue) +
                            if (features.speedShiftValue >= 1f) "" else stringResource(R.string.player_slowmo_suffix),
                        style = NvTypography.LabelLarge,
                        color = Color.White,
                    )
                    if (features.speedShiftLocked) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Lock, stringResource(R.string.player_cd_speed_locked), Modifier.size(12.dp), tint = Color.White)
                    }
                }
            }
        }

        // 缓冲指示（300ms 延迟显示；出错时让位全屏错误态）。2026-10-24：切集黑幕期间也显示
        // （转圈 + 「加载中」文字盖在黑幕上 = 明确的加载观感，替代被拉伸的旧帧）
        if ((loaderVisible || switchBlackout) && errorMessage == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NvLoader()
                    Spacer(Modifier.height(NvSpacing.S))
                    Text(
                        stringResource(R.string.player_loading),
                        style = NvTypography.BodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }

        // 全屏错误态：错误原因中文文案 + 行动按钮 + 返回
        // 2026-09-19 用户反馈：确定性失败（杜比视界无解码器）重试没有意义——这类错误
        // 不渲染「重试」，把「软解内核播放」升为主按钮；其余错误保持重试为主按钮
        if (errorMessage != null) {
            val em = errorMessage
            val fallbackUsable = onFallbackRetry != null && fallbackRetryable
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NvErrorState(
                        title = stringResource(R.string.player_err_play_title),
                        message = em?.text().orEmpty(),
                        retryText = stringResource(R.string.player_retry),
                        onRetry = if (fallbackUsable) null else {
                            {
                                autoReconnect.onManualRetry()   // 手动重试与自动重连互斥
                                errorMessage = null
                                isBuffering = true
                                onRetry()   // P2：真重试——宿主重建引擎/重新 prepare（修复 novax 假重试）
                            }
                        },
                    )
                    if (fallbackUsable) {
                        Spacer(Modifier.height(14.dp))
                        NvPrimaryButton(
                            text = stringResource(R.string.player_fallback_play),
                            onClick = { onFallbackRetry?.invoke(SOFT_ROUTE_REASON_DV) },   // Task 9③：协议值为中性 token
                            modifier = Modifier.height(44.dp),
                        )
                    }
                    if (lastFatalWasNetwork) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.player_auto_reconnect_hint),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    NvSecondaryButton(text = stringResource(R.string.player_back), onClick = onBack)
                }
            }
        }

        // C12 反馈：长按倍速中央胶囊浮层已删除（挡画面），生效指示移至底部倍速 chip（NvPlayerBottomBar）

        // 锁屏态（C6 §4.3 + 2026-09-19 锁定重做 + 2026-10-08 半锁）：锁图标在**左下角**
        //（与锁定按钮同位），全锁/半锁两级都显示（实心开锁=半锁），双击一步解锁；
        // 解锁后按当前方向模式重申朝向（AUTO_SENSOR 时回 FULL_SENSOR）。
        // 2026-10-08 e2e 发现：控制层可见（半锁过渡窗口）时角标与底栏锁按钮同位重叠、吞点击，
        // 「再点锁按钮进全锁」永远不可达——控制层可见期间隐藏角标，收起后才出现供双击解锁；
        // 全锁不受影响（controlsShown 恒 false，角标恒显）。
        // 2026-10-09 用户需求：长按角标=半锁直接升全锁（免「双击解锁→锁→锁」四步路）；
        // 已全锁时长按幂等静默（不震动不提示）；双击解锁语义逐字保留（600ms 自实现窗口不动）
        // 错误层为全屏黑底，其上再浮锁角标双击无可见反馈——错误态不组合角标
        //（与 RMVB 页错误层早退后角标不出现同口径）
        if (errorMessage == null && (locked || halfLocked) && !controlsShown) {
            Box(Modifier.fillMaxSize()) {
                // IconButton 无 onLongClick——外壳换 combinedClickable；minimumInteractiveComponentSize
                // 保 48dp 触控（IconButton 原生行为等价，不回退 a06a5d8 的 B6 成果），视觉零变化
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 20.dp)
                        .minimumInteractiveComponentSize()  // 保 48dp 触控（IconButton 原生行为等价）
                        .clip(CircleShape)
                        .combinedClickable(
                            // hapticFeedbackEnabled=false:关掉 combinedClickable 框架层长按自带震动,
                            // 全锁长按才真正静默(spec T1「不震动」);半锁→全锁震动由 onLongClick 内
                            // 显式 performHapticFeedback 承担(仅转换成功时)
                            hapticFeedbackEnabled = false,
                            onLongClick = {
                                lastLockTapAt = 0L   // 清布防：防「点按布防→长按全锁→窗口内再单击」stale 解锁
                                if (!locked) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    locked = true
                                    halfLocked = false
                                    // 2026-10-17 评审第3轮：角标长按升级全锁同样钉朝向（onLock 全锁分支同款，
                                    // 复用本文件既有的 pinCurrentOrientation）——保证「locked ⇒
                                    // requestedOrientation 已钉住」不变量，防未锁进页的 FULL_SENSOR
                                    // 残留到钉死期（系统切锁定后收敛只改状态不施加，靠此钉兜住）
                                    pinCurrentOrientation()
                                    Toast.makeText(context, context.getString(R.string.player_full_lock_toast), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (now - lastLockTapAt < 600) {
                                    locked = false
                                    halfLocked = false
                                    applyOrientationMode()
                                    controlsVisible = true
                                    lastLockTapAt = 0
                                } else {
                                    // 单击只布防（600ms 窗内再点才解锁），提示两级手势防「按了没反应」感知
                                    lastLockTapAt = now
                                    Toast.makeText(context, context.getString(R.string.player_lock_hint_toast), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        .alpha(0.6f),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, stringResource(R.string.player_cd_lock_badge), tint = Color.White) }
            }
            AnimatedVisibility(visible = lockHintVisible, enter = fadeIn(), exit = fadeOut(tween(500))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LaunchedEffect(locked) { delay(1000); lockHintVisible = false }
                    Text(
                        if (locked) stringResource(R.string.player_lock_hint) else stringResource(R.string.player_half_lock_hint),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                    )
                }
            }
        }

        // 面板
        sheet?.let { current ->
            when (current) {
                PlayerSheet.SUBTITLE -> SubtitleSheet(
                    player = player,
                    engine = engine,
                    candidates = subtitleCandidates,
                    trackRefresh = trackRefresh,
                    // 2026-09-17 功能项：外挂字幕编码播放器内快捷切换
                    encodingPref = playerPrefs?.get(KEY_SUB_ENCODING) ?: "auto",
                    onEncodingPick = { v ->
                        uiScope.launch { context.putPlayerPref(KEY_SUB_ENCODING, v) }
                        // 反查当前激活的外挂候选（自动选择器与手动点选两条路径都覆盖）
                        val active = subtitleCandidates.firstOrNull {
                            engine.activeSubtitleId == PlaybackEngine.sideLoadIdFor(it.path)
                        }
                        if (active != null) {
                            uiScope.launch {
                                engine.loadSubtitle(active)?.let { id ->
                                    pendingSideLoadSelect = id
                                    Toast.makeText(context, context.getString(R.string.player_subtitle_reloaded_toast), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    // side-load 挂载前经编码转码（IO），成功才关面板
                    onPickFile = { candidate ->
                        uiScope.launch {
                            engine.loadSubtitle(candidate)?.let { id ->
                                pendingSideLoadSelect = id
                                sheet = null
                            }
                        }
                    },
                    onClose = { sheet = null },
                )
                PlayerSheet.AUDIO -> AudioTrackSheet(player, trackRefresh, onClose = { sheet = null })
                PlayerSheet.SPEED -> SpeedSheet(
                    current = speed,
                    muteHighSpeed = playerPrefs?.get(KEY_MUTE_ABOVE_2X) ?: DEFAULT_MUTE_ABOVE_2X,
                    onDismiss = { sheet = null },   // BUG-C-R1-01：仅关闭面板不得回写旧速度/清锁定
                    onPick = { picked ->
                        speed = picked
                        // 2026-09-19：显式选速优先——清掉长按倍速生效/下滑锁定态
                        features.speedShiftActive = false
                        features.speedShiftLocked = false
                        engine.setPlaybackSpeed(picked, pitch = pitchFor(picked, playerPrefs?.get(KEY_PRESERVE_PITCH) ?: true))
                        applySpeedMute(picked)
                        context.recordLastSpeed(picked)   // C10：跨会话倍速记忆写入端
                        sheet = null
                    },
                )
                PlayerSheet.ASPECT -> AspectRatioSheet(
                    aspect,
                    onPick = {
                        aspect = it
                        aspectTouched = true
                        // 画面比例记忆（novax_aspect_mode）：手选即持久化，下次进播放器恢复
                        uiScope.launch { runCatching { context.putPlayerPref(KEY_ASPECT_MODE, it.name) } }
                        sheet = null
                    },
                )
                // M3 清晰度面板：点选即切——面板先关（切档重建引擎期间不悬挂），宿主侧
                // switchQuality 保持进度/倍速换流并写记忆档位（下次起播沿用）
                PlayerSheet.QUALITY -> QualitySheet(
                    current = currentQualityTier,
                    isJellyfinSource = isJellyfinSource,
                    effectiveTier = effectiveQualityTier,
                    sourceBitrateBps = jellyfinSourceBitrate,
                    onDismiss = { sheet = null },
                    onPick = { tier ->
                        sheet = null
                        onQualitySelected(tier)
                    },
                )
                // 2026-09-15 用户需求：视频信息（分辨率/帧率/编码/动态范围/音轨）
                // 返工⑤：首行加「文件」=完整文件名（含扩展名，playUrl 末段解码、回退 title）
                PlayerSheet.INFO -> VideoInfoSheet(
                    player = player,
                    videoW = videoW,
                    videoH = videoH,
                    trackRefresh = trackRefresh,
                    fileName = videoInfoFileName(playUrl, title),
                    onClose = { sheet = null },
                )
                // 2026-09-17 功能项：AB 循环 + 睡眠定时
                PlayerSheet.MORE -> MoreActionsSheet(
                    loopAMs = features.loopAMs,
                    loopBMs = features.loopBMs,
                    sleepMinutes = features.sleepMinutes,
                    sleepRemainingMs = (features.sleepDeadlineMs - System.currentTimeMillis()).takeIf { it > 0 },
                    onSetLoopA = {
                        Log.d("NvABLoop", "set A = ${player.currentPosition}")
                        features.loopAMs = player.currentPosition
                        Toast.makeText(context, context.getString(R.string.player_loop_a_set, formatTime(features.loopAMs)), Toast.LENGTH_SHORT).show()
                        controlsTick++
                    },
                    onSetLoopB = {
                        Log.d("NvABLoop", "set B candidate = ${player.currentPosition} (A=$features.loopAMs)")
                        val a = features.loopAMs.coerceAtLeast(0L)
                        val b = player.currentPosition
                        if (b <= a) {
                            Toast.makeText(context, context.getString(R.string.player_loop_b_invalid, formatTime(b)), Toast.LENGTH_SHORT).show()
                        } else {
                            features.loopBMs = b
                            Toast.makeText(context, context.getString(R.string.player_loop_active_toast, formatTime(a), formatTime(b)), Toast.LENGTH_SHORT).show()
                        }
                        controlsTick++
                    },
                    onClearLoop = {
                        features.loopAMs = -1L
                        features.loopBMs = -1L
                        Toast.makeText(context, context.getString(R.string.player_loop_cleared_toast), Toast.LENGTH_SHORT).show()
                        controlsTick++
                    },
                    onSleepPick = { minutes ->
                        features.sleepMinutes = minutes
                        features.sleepDeadlineMs = if (minutes <= 0) -1L else System.currentTimeMillis() + minutes * 60_000L
                        Toast.makeText(
                            context,
                            if (minutes <= 0) context.getString(R.string.player_sleep_off_toast)
                            else context.getString(R.string.player_sleep_in_min, minutes),
                            Toast.LENGTH_SHORT,
                        ).show()
                        controlsTick++
                    },
                    onClose = { sheet = null },
                )
            }
        }

        // 同目录多字幕候选：进入播放器自动弹出「选择字幕」（C6 §3.1）
        if (showSubtitlePicker && subtitleCandidates.isNotEmpty()) {
            SubtitlePickerSheet(
                candidates = subtitleCandidates,
                onPick = {
                    showSubtitlePicker = false
                    uiScope.launch {
                        engine.loadSubtitle(it)?.let { id -> pendingSideLoadSelect = id }
                    }
                },
                onDecline = {
                    showSubtitlePicker = false
                    showNoSubtitleBanner = true   // 放弃加载 → 落回「无字幕」横幅
                },
                onClose = { showSubtitlePicker = false },
            )
        }
    }
}

// ---------------- 播放错误反馈 ----------------

/**
 * 视频轨是否根本没起来（2026-09-15 用户实测「杜比视界测试片有声音无画面」根因）：
 *
 * 真机取证（Redmi 10X / MT6875，系统无任何 video/dolby-vision 解码器；文件级证据：
 * media_codecs*.xml 里 "dolby" 出现 0 次）：
 * - Profile 8.1（dvhe.08.03）：MediaCodecInfo 报 NoSupport 后**成功回退** c2.mtk.hevc.decoder，
 *   画面正常 → 我们的链路是对的；
 * - Profile 5（dvhe.05.x）：**完全没有解码器初始化日志、没有任何错误、没有 PlaybackException**，
 *   Media3 在轨道选择阶段就把该轨标成不可选 → 永久黑屏 + 只有声音 + 进度条照走。
 *
 * 判据（真机实测的精确形态，[NvTrackDiag] 取证）：
 *   `groups=t2[n=1,sel=false,fmt=video/dolby-vision]` —— 视频轨**存在**但 **isSelected=false**，
 *   即「没有任何解码器能选上它」。因此判据是「有视频轨但一条都没被选中」，而不是「没有视频轨」。
 * 返回错误文案；正常播放（视频轨已选中）返回 null。
 */
/**
 * 视频轨缺失归因（2026-09-15 杜比视界 Profile 5 静默丢弃取证；2026-09-19 加软解可救标记）：
 * message = 全屏错误文案；softDecoderRetryable = 该编码可交 libVLC 软解兜底
 * （杜比视界无解码器时，libVLC/软解能像 nPlayer 一样解出 HEVC 基础层——无 RPU 校色会偏色，
 * 但有画面可用，好过黑屏）。其他冷门编码 libVLC 同样无解，不给按钮免得二次失败。
 */
internal data class MissingVideoTrack(val message: UiMessage, val softDecoderRetryable: Boolean)

/**
 * 音轨缺失侦测（2026-09-19 新机 vivo 实测）：音轨存在但一条都没被选中 = 设备无该音频解码器
 * （实测样本为杜比全景声 E-AC-3 JOC 轨）。前置条件：duration 已知 + 视频轨已选中（或纯音频
 * 文件）——保证轨道选择流程已完成，排除起播竞态误报。
 */
/** 音轨缺失判定（常路）：结构满足 + duration 已知（护栏防起播竞态误判） */
private fun audioTrackMissing(tracks: Tracks, durationMs: Long): Boolean =
    durationMs > 0L && audioMissingShape(tracks)

/**
 * 音轨缺失的结构判定（2026-09-19 从 audioTrackMissing 拆出）：
 * 音轨组存在但整组未被选中（设备无该解码器）、视频轨正常选中。
 * （2026-09-29 mime 快路随 per-mime 记忆退役删除，本函数仅剩 audioTrackMissing 一个消费方）
 */
private fun audioMissingShape(tracks: Tracks): Boolean {
    if (tracks.groups.isEmpty()) return false
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isEmpty()) return false
    if (audioGroups.any { it.isSelected }) return false
    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (videoGroups.isNotEmpty() && videoGroups.none { it.isSelected }) return false
    return true
}

/**
 * 音轨 mime → 展示名（横幅用）。i18n Task 9③：杜比系商业名由资源注入
 * （[text] 缝，生产 = context::getString）；其余为中性专有名词（白名单）。
 */
private fun audioMimeLabel(mime: String?, text: (Int) -> String): String = when {
    mime == null -> text(R.string.player_codec_unknown)
    mime.contains("eac3-joc", true) || mime.contains("eac3_joc", true) -> text(R.string.player_codec_eac3_joc)
    mime.contains("eac3", true) || mime.contains("ec3", true) -> text(R.string.player_codec_eac3)
    mime.contains("ac3", true) || mime.contains("ac-3", true) -> text(R.string.player_codec_ac3)
    mime.contains("ac4", true) -> "AC-4"
    mime.contains("true-hd", true) || mime.contains("truehd", true) -> "Dolby TrueHD"
    mime.contains("dts", true) -> "DTS"
    mime.contains("opus", true) -> "Opus"
    else -> mime
}

/** 音轨缺失的展示名（未选中的第一条音轨 mime） */
private fun unselectedAudioMime(tracks: Tracks): String? {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    return audioGroups.firstNotNullOfOrNull { g ->
        (0 until g.length).map { g.getTrackFormat(it) }.firstOrNull()?.sampleMimeType
    }
}

private fun videoTrackMissingMessage(
    tracks: Tracks,
    durationMs: Long,
    /** 「未知编码」占位（i18n：资源解析需 Context，由调用方传入已解析串） */
    unknownCodecLabel: String,
): MissingVideoTrack? {
    if (durationMs <= 0L) return null
    if (tracks.groups.isEmpty()) return null                 // 轨道信息尚未到达
    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (videoGroups.isEmpty()) return null                   // 纯音频片源，不打扰
    if (videoGroups.any { it.isSelected }) return null        // 有选中的视频轨 = 正常出画
    val codec = videoGroups.firstNotNullOfOrNull { g ->
        (0 until g.length).map { g.getTrackFormat(it) }.firstOrNull()?.sampleMimeType
    }
    val isDv = codec?.contains("dolby-vision", ignoreCase = true) == true
    return if (isDv) {
        MissingVideoTrack(
            UiMessage(R.string.player_dv_note),
            softDecoderRetryable = true,
        )
    } else {
        MissingVideoTrack(
            UiMessage(R.string.player_no_decoder_note, listOf(codec ?: unknownCodecLabel)),
            softDecoderRetryable = false,
        )
    }
}

/**
 * PlaybackException → 错误文案（2026-09-14 修订：按 errorCode 精确分类，
 * 不再一律显示「格式不支持或已损坏」）。i18n：UiMessage 载体，UI 层 text() 渲染。
 * 注意：源 IO 类错误首次失败已被引擎静默自愈重试一次，能走到这里 = 二次失败。
 */
/** 网络类致命错误（自动重连判定）：源读取中断/超时/连接类失败——这类错误恢复网络后重试有意义 */
internal fun isNetworkRecoverableErrorCode(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> true
    else -> false
}

private fun errorMessageFor(error: PlaybackException): UiMessage = if (isInaccessiblePlaybackPath(error)) {
    UiMessage(R.string.player_err_path)
} else when (error.errorCode) {    // 源读取类（自愈重试后仍失败）：告知用户系统已自动重试过
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_TIMEOUT ->
        UiMessage(R.string.player_err_io_retried)
    // 连接类：NAS / 网络不可达
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        UiMessage(R.string.player_err_nas_disconnected)
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
        UiMessage(R.string.player_err_cleartext)
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        UiMessage(R.string.player_err_http_status)
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
        UiMessage(R.string.player_err_path)
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        UiMessage(R.string.player_err_container)
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        UiMessage(R.string.player_err_decoder_unsupported)
    else -> UiMessage(R.string.player_err_generic, listOf(error.errorCodeName))
}

/**
 * 错误态是否给「用软解内核（libVLC）播放」按钮（2026-09-19 修订，模拟器 dvp81 实测补口）。
 * 4xxx 解码类错误 = 渲染器确定性失败（实例：DV P8.1 的 HEVC 主盟规硬解初始化失败，
 * format_supported=NO_EXCEEDS_CAPABILITIES）——重试点到天黑还是这个样（用户原话裁定这类
 * 失败不摆重试按钮），改为与容器解析失败一样给软解按钮；libVLC 软解音/视频双向兜底。
 */
private fun fallbackRetryableFor(error: PlaybackException): Boolean = error.errorCode in listOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

// ---------------- 视频层 ----------------

/**
 * 视频画面容器（C12 反馈）：画面比例 + 画面旋转 + surface 类型自适应（画质修订 2026-09-14）。
 *
 * 【surface 生命周期约定 —— 2026-09-14 方向切换断流修复 + SurfaceView 画质修订】
 * - AndroidView 组合节点全程**唯一**：factory 只创建一个 FrameLayout 容器并持有它，方向切换
 *   （requestedOrientation，Activity 经 configChanges 处理不重建）、画面比例（aspect）、画面旋转
 *   （rotationDeg）都只改变 modifier（重布局/重变换）→ 容器不重挂载、engine 不重建、播放不中断。
 * - surface 类型自适应（放大画质/延迟修复）：rotationDeg==0 用 **SurfaceView**
 *   （setVideoSurfaceView，硬件 overlay 直通，出厂直出最锐、延迟最低，nPlayer 等主流播放器
 *   的默认选择）；rotationDeg!=0 切回 **TextureView**（graphicsLayer rotationZ 旋转必需，
 *   SurfaceView 无法跟随 Compose 变换树做任意角度旋转）。
 * - 类型切换只发生在 rotationDeg 跨 0° 的瞬间（用户点「画面旋转」按钮）：协议为
 *   clearVideo* → 容器换 child → setVideoSurfaceView/setVideoTextureView 绑新 surface，
 *   接受一次性短暂闪黑（用户主动操作）。**方向切换（requestedOrientation）与 rotationDeg
 *   是两回事，前者绝不触发 surface 类型切换**。
 * - 禁止按 aspect / surface 类型分支切换到不同 composable：旧实现 FIT/FILL ↔ 16:9/4:3 曾替换
 *   AndroidView 节点，surface 被重新挂到新父容器 → SurfaceTexture 销毁且无人重新绑定 →
 *   黑屏断流（已修）。
 * - 兜底：容器意外脱离窗口（OEM 重建窗口等平台行为）重新 attach 时自动重绑当前活跃 surface
 *   （SurfaceView 重挂载后 Surface 重建、TextureView 重挂载后 SurfaceTexture 重建，均需重绑；
 *   幂等，代价可忽略）；detach 侧不 clear（中途解绑只会黑屏，释放由 engine.release() 统一收口）。
 * - rotationDeg=90/270 时先在「宽高互换的虚拟画布」（effW×effH）内按原逻辑求尺寸，
 *   再整体 graphicsLayer rotationZ 旋转 —— 旋转后的足迹 (effH×effW) 恰好落回屏幕内，
 *   视频保持完整铺满可见、不裁切出屏。0° 时**不挂任何 graphicsLayer**（rotationZ=0 的空
 *   layer 也会把 SurfaceView 拉进 GPU 合成路径，失去 overlay 直通的锐度收益），
 *   effW/effH 即屏幕尺寸（行为与旧版一致）。
 *   （盒尺寸以 requiredSize 表达：.size() 会被父约束钳成正方形——2026-10-03 旋转压缩修复）
 */
