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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// ============================================================
// 从 PlayerScreen.kt 机械切出（2026-09-28 审计 A：巨型文件拆分，零逻辑改动，private→internal）
// ============================================================

// ---------------- 手势事件 ----------------

internal class GestureEvents(
    val onSingleTap: () -> Unit,
    val onDoubleTapSeek: (offsetMs: Long) -> Unit,
    /** 2026-09-20 用户需求（B 站同款）：中间 1/2 区双击 = 播放/暂停 */
    val onDoubleTapCenter: () -> Unit,
    /** 长按倍速开始（夸克式，C6 §2.3 2026-09-14 修订）：fastForward=true → 快进；false → 0.5x 慢放（左手模式） */
    val onSpeedShiftStart: (fastForward: Boolean) -> Unit,
    /** 长按倍速结束：恢复按住前的速度（倍速菜单记忆值，非固定 1.0x；锁定时保持不恢复） */
    val onSpeedShiftEnd: () -> Unit,
    /**
     * 2026-09-19 用户需求：长按倍速生效期间下滑（累计 >80dp）= 锁定/解锁倍速切换。
     * 未锁 → 锁定（松手后保持倍速）；已锁 → 立即恢复原速。同一手势内只触发一次。
     */
    val onSpeedShiftLockGesture: () -> Unit,
    /** 左手模式慢放开关（DataStore 键 novax_left_slowmo，默认 false；设置页实时写入） */
    val leftSlowmo: () -> Boolean,
    /** C10：长按倍速手势总开关（novax_long_press_speed_enabled，默认 true；关 = 长按不触发倍速） */
    val longPressEnabled: () -> Boolean,
    /** C10：长按倍速值（novax_long_press_speed，滑块六档 0.5–3x，默认 2x） */
    val longPressSpeed: () -> Float,
    /** C10：快进/快退步长秒数（novax_double_tap_seek_seconds，10/15/25/30，默认 15；双击与底栏按钮共用） */
    val doubleTapSec: () -> Int,
    /** 滑动快进步长：整屏 = 片长百分比（novax_swipe_full_percent，1~50，默认 10；2026-10-08 替代三档灵敏度） */
    val swipeFullPercent: () -> Int,
    /** 2026-09-27 等比滑动 seek：当前视频时长（0=未知，走默认步长） */
    val swipeDurationMs: () -> Long,
    /** C10：变速不变调（novax_audio_preserve_pitch，默认 true；关 = pitch 随 speed） */
    val preservePitch: () -> Boolean,
    val currentBrightness: () -> Float,
    val onBrightness: (Float) -> Unit,
    val currentVolume: () -> Float,
    val onVolume: (Float) -> Unit,
    val onSeekPreview: (deltaMs: Long) -> Unit,
    val onSeekCommit: (deltaMs: Long) -> Unit,
    val onSeekCancel: () -> Unit,
    val onOverlayEnd: () -> Unit,
    val onLockedTap: () -> Unit,
    val isLocked: () -> Boolean,
    // ---- 双指缩放（2026-09-15 用户需求，B 站同款）----
    /** 当前缩放状态（捏合起点的取值来源；delegated state，每次调用读最新值） */
    val currentZoom: () -> ZoomState,
    /**
     * 2026-09-29 真机缺陷修复（vivo/Fold3/模拟器三方实证）：底部控制层（进度条热区+按钮行）
     * 的实时像素高度；控制层隐藏时必须返回 0。父层手势状态机对落在该区域内的按下整体不接管——
     * 进度条拖动与 SlidingSeek 的触摸 slop 竞速在快速拖动时会输（父层先过 24dp 判定并消费，
     * 滑块 detectDragGestures 静默取消：拖动跟手、松手弹回、只发生相对快进）。
     */
    val controlsHeightPx: () -> Float,
    /** 当前画面内容在屏上的可见足迹（px，rotation 90/270 时已按交换口径给入）——平移夹紧用 */
    val zoomContentDims: () -> Pair<Float, Float>,
    /** 双指缩放开始（首次拿到两指距离时回调一次：提示 chip 显隐 + 触感 + 日志） */
    val onPinchStart: () -> Unit,
    /** 双指缩放进行中：夹紧后的 scale/pan（每帧） */
    val onPinch: (ZoomState) -> Unit,
    /** 双指缩放结束（任一指抬起即结束；余指吞到全部抬起，不进 tap 窗口） */
    val onPinchEnd: () -> Unit,
)

/**
 * C6 §2.9（2026-09-14 修订）手势状态机：显式 sealed class，替代原 DragMode/布尔组合。
 * 一次手势只走一条迁移路径（moved / invariant 双判定）：
 *
 *   Idle ──down──▶ Pressing
 *     （锁屏时 down 直接吞掉全部手势只弹解锁提示，长按倍速禁用，C6 §4.3）
 *   Pressing ──invariant：350ms 静止（位移 ≤24dp）且落点在触发区──▶ LongPressSpeed
 *     触发区（2026-09-14 二次修订）：全屏（含黑边/画面），仅左右各 48dp 边缘竖条除外；
 *     左手慢放关 → 竖条外全屏 2x 快进；左手慢放开 → 左半屏 0.5x 慢放 / 右半屏 2x 快进
 *   Pressing ──moved：位移 >24dp──▶ 按主导轴分派
 *     水平 → SlidingSeek（C6 §2.6，全屏）；左缘 48dp 竖条垂直 → SlidingBrightness（C6 §2.4）；
 *     右缘 48dp 竖条垂直 → SlidingVolume（C6 §2.5）
 *   Pressing ──up（位移未超限）──▶ tap 窗口：300ms 内二次 tap → 三区双击（左退/右进 ±Ns 连击累加、中间播放/暂停，C6 §2.2 + 2026-09-20 分区 1/4-1/2-1/4；2026-10-16 用户改 1/5-3/5-1/5（主流对齐后收紧边缘区））；超时 → 单击 toggle 控制层（C6 §2.1）
 *   LongPressSpeed ──生效期间累计下滑 >80dp──▶ onSpeedShiftLockGesture（一次性，2026-09-19）
 *     未锁=锁定倍速（松手保持）/ 已锁=立即恢复原速；上滑/横移不触发
 *   LongPressSpeed ──全部指抬起（多指以最后一指抬起为准）──▶ 恢复原速（锁定时保持）→ Idle
 *     生效期间单击/双击/其余滑动全部忽略（互斥）
 *   Sliding* ──up──▶ 提交/收尾 → Idle
 *   边缘竖条内静止按住 / 竖条外垂直滑动：无可挂载动作，吞掉至全部抬起 → Idle（不误触 tap）
 *   任意单指态（Pressing/Sliding*）──第二根手指按下──▶ Pinching（2026-09-15 用户需求，B 站同款）
 *     单指语义立即让位收起（seek 浮层/亮度音量条）；缩放=起点缩放×距离比，平移=质心位移
 *     （经 PlayerZoomPolicy.clampPan 夹紧：画面边缘不得拖进屏幕露黑边）
 *   Pinching ──任一指抬起──▶ 缩放结束，余指吞到全部抬起 → Idle（不进 tap 窗口）
 */
private sealed interface GestureState {
    /** 空闲：等待下一次按下 */
    data object Idle : GestureState

    /** 按下未判定：等待 350ms 长按窗口内事件（invariant：位移未超 24dp 且主指未抬起） */
    data class Pressing(val pointerId: PointerId, val startX: Float, val startY: Float) : GestureState

    /** 长按倍速生效中（C6 §2.3 修订）：fastForward=true → 2x 快进 / false → 0.5x 慢放；
     *  startY = 按下起点纵坐标（2026-09-19 下滑锁定倍速的位移基准） */
    data class LongPressSpeed(val pointerId: PointerId, val fastForward: Boolean, val startY: Float) : GestureState

    /** 左 1/3 垂直滑动：亮度（C6 §2.4） */
    data class SlidingBrightness(val pointerId: PointerId, val startY: Float) : GestureState

    /** 右 1/3 垂直滑动：音量（C6 §2.5） */
    data class SlidingVolume(val pointerId: PointerId, val startY: Float) : GestureState

    /** 水平滑动：seek 预览，松手才提交（C6 §2.6） */
    data class SlidingSeek(val pointerId: PointerId, val lastX: Float) : GestureState

    /**
     * 双指捏合缩放（2026-09-15 用户需求，B 站同款）：第二根手指按下的瞬间从任何单指手势
     * （Pressing/SlidingSeek/SlidingBrightness/SlidingVolume）接管——单指语义（seek 预览、
     * 亮度/音量条）立即让位收起，进入本态后只做「缩放 + 跟手平移」。
     * 任一指抬起即结束缩放；剩余手指吞到全部抬起（不进 tap 窗口、不触发单击/双击）。
     * startZoom 是进入本态时刻的缩放状态：捏合增量（距离比、质心位移）全部叠加在它之上。
     */
    data class Pinching(val idA: PointerId, val idB: PointerId, val startZoom: ZoomState) : GestureState
}

/**
 * 单指滑动（seek/亮度/音量）的结束方式：
 * - Commit：语义完成（如 seek 提交）；
 * - Cancel：被取消（子节点接管事件 / 主指消失）；
 * - Takeover：第二根手指按下，本次手势转交双指缩放，[otherId] 为那根新手指。
 */
internal sealed interface SlideEnd {
    data object Commit : SlideEnd
    data object Cancel : SlideEnd
    data class Takeover(val otherId: PointerId) : SlideEnd
}

/**
 * 长按落点 → 倍速动作（C6 §2.3 修订 + 2026-09-14 二次修订全屏触发区 + C10 开关联动）：
 * - 左右各 48dp 边缘竖条（[edgeStripPx]）→ null：竖条保留给亮度/音量滑动手势，长按不触发倍速；
 * - 其余全屏（含黑边、画面）均可长按倍速；
 * - 左手慢放开 → 竖条外左半屏 0.5x 慢放（独立开关，不受长按快进总闸影响）/ 右半屏快进；
 * - 左手慢放关 → 长按快进开 = 全屏快进；关（C10 novax_long_press_speed_enabled=false）→ null。
 */
internal fun longPressActionOf(
    x: Float,
    screenW: Float,
    leftSlowmo: Boolean,
    longPressEnabled: Boolean,
): Boolean? {
    if (leftSlowmo) {
        return if (x < screenW / 2f) false else if (longPressEnabled) true else null
    }
    return if (longPressEnabled) true else null
}

internal suspend fun AwaitPointerEventScope.awaitDownOrNull(timeoutMs: Long): PointerInputChange? =
    withTimeoutOrNull(timeoutMs) {
        while (true) {
            val ev = awaitPointerEvent()
            // P1 修复：tap 窗口内落点若被子节点（滑条/按钮）消费，不算双击也不算单击
            ev.changes.firstOrNull { it.pressed && !it.isConsumed }?.let { return@withTimeoutOrNull it }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

internal suspend fun AwaitPointerEventScope.awaitAllUp() {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.none { it.pressed }) return
    }
}

/**
 * C6 §2.9（2026-09-14 修订）手势状态机主循环，迁移注释见 GestureState。
 */
internal suspend fun PointerInputScope.detectPlayerGestures(events: GestureEvents) {
    awaitEachGesture {
        // 24dp 判定阈值（C6 §2.3 修订）：按下后 350ms 内位移不超过它才判定为长按开始
        val slopPx = 24.dp.toPx()
        // 左右各 48dp 边缘竖条（2026-09-14 二次修订）：亮度/音量滑动保留区，长按倍速在此不触发
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        var state: GestureState = GestureState.Idle

        // ---- Idle → Pressing ----
        // P1 修复（事件消费检查）：控制条按钮 clickable 会消费 down——已消费的 down 不进入父层状态机
        //（否则按钮上静止按住 350ms 会误触发长按倍速、抬起误判 tap 关控制层）
        val down = awaitFirstDown(requireUnconsumed = true)
        if (events.isLocked()) {
            // 锁屏（C6 §4.3）：吞掉一切手势（长按倍速禁用），仅弹解锁提示
            awaitAllUp()
            events.onLockedTap()
            return@awaitEachGesture
        }
        // 2026-09-29 控制层区域不接管（真机缺陷修复，见 GestureEvents.controlsHeightPx）：
        // 按下点落在底部控制层 band 内 = 进度条拖动/按钮/横幅的领地，父层状态机直接退场。
        // 否则快速拖动进度条时父层 SlidingSeek 先过 24dp 判定并消费，滑块拖动被静默取消
        val controlsH = events.controlsHeightPx()
        if (controlsH > 0f && down.position.y > h - controlsH) {
            // 2026-10-07 band 退场留痕（控制层 band 覆盖范围回归观察用，量级 = 滑块热区+24dp）
            android.util.Log.d("NvGesture", "band exit: y=" + down.position.y + " controlsH=" + controlsH)
            awaitAllUp()
            return@awaitEachGesture
        }
        state = GestureState.Pressing(down.id, down.position.x, down.position.y)
        val pressStartAt = SystemClock.elapsedRealtime()

        while (state != GestureState.Idle) {
            when (val s = state) {
                GestureState.Idle -> break

                is GestureState.Pressing -> {
                    val remaining = LONG_PRESS_MS - (SystemClock.elapsedRealtime() - pressStartAt)
                    val ev = if (remaining > 0) withTimeoutOrNull(remaining) { awaitPointerEvent() } else null
                    if (ev == null) {
                        // ---- 迁移① invariant：350ms 静止（位移未超 24dp）→ 长按倍速 ----
                        val action = longPressActionOf(s.startX, w, events.leftSlowmo(), events.longPressEnabled())
                        if (action == null) {
                            awaitAllUp()   // 边缘竖条内静止按住：吞掉至抬起，不误触 tap
                            state = GestureState.Idle
                        } else {
                            state = GestureState.LongPressSpeed(s.pointerId, action, s.startY)
                            events.onSpeedShiftStart(action)
                        }
                        continue
                    }
                    val change = ev.changes.firstOrNull { it.id == s.pointerId }
                    // 2026-09-15 用户需求（B 站同款）：Pressing 期间第二根手指按下 → 转交双指缩放
                    //（旧实现是吞掉至抬起防误触双击 seek；缩放功能上线后这里改为进 Pinching，
                    //  误触防护由 Pinching 态「结束时不进 tap 窗口」天然保证）
                    ev.changes.firstOrNull { it.id != s.pointerId && it.pressed }?.let { second ->
                        state = GestureState.Pinching(s.pointerId, second.id, events.currentZoom())
                        continue
                    }
                    // P1 修复（事件消费检查）：子节点（进度条滑条 detectDragGestures / 控制条按钮
                    // clickable）已消费的事件不得触发父层状态机迁移——拖进度条不再被父层二次 seek，
                    // 点按钮不再误关控制层
                    if (ev.changes.any { it.isConsumed }) {
                        awaitAllUp()
                        state = GestureState.Idle
                        continue
                    }
                    if (change != null && !change.pressed) {
                        // ---- 迁移②：未移动即抬起 → tap 窗口（三区双击：左右 1/5 ±Ns / 中间 3/5 播放暂停 / 单击 toggle）----
                        handleTapWindow(events, w)
                        state = GestureState.Idle
                        continue
                    }
                    if (change == null) continue   // 其他指针事件不参与主指判定
                    val dx = change.position.x - s.startX
                    val dy = change.position.y - s.startY
                    when (initialPlayerSlide(s.startX, w, dx, dy, slopPx)) {
                            PlayerSlide.SEEK ->
                                state = GestureState.SlidingSeek(s.pointerId, change.position.x)
                            PlayerSlide.BRIGHTNESS ->
                                state = GestureState.SlidingBrightness(s.pointerId, change.position.y)
                            PlayerSlide.VOLUME ->
                                state = GestureState.SlidingVolume(s.pointerId, change.position.y)
                            null -> Unit
                    }
                }

                is GestureState.LongPressSpeed -> {
                    // ---- 迁移④：等待全部指抬起（多指以最后一指抬起为准）----
                    // 互斥：生效期间单击/双击/滑动全部忽略，这里不转发任何手势事件。
                    // 2026-09-19 用户需求：生效期间累计下滑 >80dp = 锁定/解锁倍速切换
                    //（本次手势一次性触发；上滑/横移不触发；锁定时松手不恢复速度，见 onSpeedShiftEnd）
                    var lockFired = false
                    val speedLockPx = SPEED_LOCK_SWIPE_DP.dp.toPx()
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                            if (!lockFired) {
                                // BUG-C-R1-03：只认主指（s.pointerId）——取任意按下指针时，
                                // 主指抬起后第二指的 y 位移会误触发锁定切换
                                val c = ev.changes.firstOrNull { it.id == s.pointerId && it.pressed }
                                if (c != null && c.position.y - s.startY > speedLockPx) {
                                    lockFired = true
                                    events.onSpeedShiftLockGesture()
                                }
                            }
                        }
                    } finally {
                        events.onSpeedShiftEnd() // 取消/重组也恢复；回调自带激活判断（锁定时保持）
                    }
                    state = GestureState.Idle
                }

                is GestureState.SlidingBrightness -> {
                    state = when (val end = runVerticalLevel(events, s.pointerId, s.startY, h, brightness = true)) {
                        is SlideEnd.Takeover -> GestureState.Pinching(s.pointerId, end.otherId, events.currentZoom())
                        else -> GestureState.Idle
                    }
                }

                is GestureState.SlidingVolume -> {
                    state = when (val end = runVerticalLevel(events, s.pointerId, s.startY, h, brightness = false)) {
                        is SlideEnd.Takeover -> GestureState.Pinching(s.pointerId, end.otherId, events.currentZoom())
                        else -> GestureState.Idle
                    }
                }

                is GestureState.SlidingSeek -> {
                    state = when (val end = runHorizontalSeek(events, s.pointerId, s.lastX, w)) {
                        is SlideEnd.Takeover -> GestureState.Pinching(s.pointerId, end.otherId, events.currentZoom())
                        else -> GestureState.Idle
                    }
                }

                is GestureState.Pinching -> {
                    var startDist = 0f
                    var startCx = 0f
                    var startCy = 0f
                    var armed = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                            val a = ev.changes.firstOrNull { it.id == s.idA && it.pressed }
                            val b = ev.changes.firstOrNull { it.id == s.idB && it.pressed }
                            // 任一指抬起 → 缩放结束；剩余手指吞到全部抬起（不进 tap 窗口，防误触单击/双击）
                            if (a == null || b == null) break
                            val dist = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                            val cx = (a.position.x + b.position.x) / 2f
                            val cy = (a.position.y + b.position.y) / 2f
                            if (!armed) {
                                // 首次拿到两指实时位置：以此刻为基准记录距离与质心（接管瞬间的事件
                                // 位置可能已滞后，以第一帧实测为准）
                                armed = true
                                startDist = dist.coerceAtLeast(1f)
                                startCx = cx
                                startCy = cy
                                events.onPinchStart()
                                continue
                            }
                            val raw = s.startZoom.copy(
                                scale = PlayerZoomPolicy.scaleFromPinch(s.startZoom.scale, startDist, dist),
                                panX = s.startZoom.panX + (cx - startCx),
                                panY = s.startZoom.panY + (cy - startCy),
                            )
                            val (contentW, contentH) = events.zoomContentDims()
                            events.onPinch(PlayerZoomPolicy.clampPan(raw, contentW, contentH, w, h))
                        }
                    } finally {
                        events.onPinchEnd()   // 取消/重组也收尾；提示 chip 的淡出计时由它驱动
                    }
                    awaitAllUp()
                    state = GestureState.Idle
                }
            }
        }
    }
}

/** tap 窗口（C6 §2.1/2.2 + 2026-09-20 用户需求分区 1/4-1/2-1/4 → 2026-10-16 改 1/5-3/5-1/5）：300ms 内二次 tap → 双击；
 *  左 1/5 双击快退 Ns / 右 1/5 双击快进 Ns（连击累加 ±2N、±3N…），中间 3/5 双击播放/暂停（B 站同款，区宽 2026-10-16 收紧）；
 *  无二次 tap 才单击 toggle 控制层 */
internal suspend fun AwaitPointerEventScope.handleTapWindow(events: GestureEvents, w: Float) {
    var doubleTaps = 0
    val stepMs = events.doubleTapSec() * 1000L
    // 2026-09-20 用户需求：左 1/4 快退 / 右 1/4 快进 / 中间 1/2 暂停切换；2026-10-16 改 左 1/5 / 右 1/5 / 中间 3/5
    fun Float.tapSide(): Int = if (this < w / 5f) -1 else if (this > 4f * w / 5f) 1 else 0
    while (true) {
        val second = awaitDownOrNull(DOUBLE_TAP_WINDOW_MS) ?: break
        awaitAllUp()
        val side = second.position.x.tapSide()
        if (side == 0) events.onDoubleTapCenter() else events.onDoubleTapSeek(side * stepMs)
        doubleTaps++
        val next = awaitDownOrNull(DOUBLE_TAP_WINDOW_MS) ?: break
        awaitAllUp()
        val side2 = next.position.x.tapSide()
        if (side2 == 0) events.onDoubleTapCenter() else events.onDoubleTapSeek(side2 * stepMs)
    }
    if (doubleTaps == 0) events.onSingleTap()      // 只有纯单击才 toggle 控制层（C6 §2.1）
}

/** C6 §2.6 水平滑动 seek（2026-09-27 修订：等比步长，整屏=设置百分比（novax_swipe_full_percent），见 swipeSeekDeltaMs）；
 *  预览期间不 seek，松手才提交 */
internal suspend fun AwaitPointerEventScope.runHorizontalSeek(
    events: GestureEvents,
    downId: PointerId,
    startX: Float,
    widthPx: Float,
): SlideEnd {
    var lastX = startX
    var deltaPx = 0f
    events.onSeekPreview(0L)
    while (true) {
        val ev = awaitPointerEvent()
        val change = ev.changes.firstOrNull { it.id == downId } ?: break
        // 2026-09-15 用户需求（B 站同款）：第二根手指按下 → 本次手势转交双指缩放，
        // seek 预览浮层立即收起（单指语义让位），由调用方迁移到 Pinching
        ev.changes.firstOrNull { it.id != downId && it.pressed }?.let { second ->
            events.onSeekCancel()
            return SlideEnd.Takeover(second.id)
        }
        // 2026-09-15 用户实测修复：子节点（底部进度条 NvChapterSlider 的 detectDragGestures）
        // 越过触摸阈值后开始消费事件 —— 父层这次滑动已被它接管（进度条自己松手 seek），
        // 必须收起父层的滑动 seek 浮层并退出，否则浮层会一直停在画面上（用户截图证据：
        // 拖动进度条后「06:45 +00:02」永久留在画面）。
        if (change.isConsumed) {
            events.onSeekCancel()
            return SlideEnd.Cancel
        }
        change.consume()
        deltaPx += change.position.x - lastX
        lastX = change.position.x
        // 2026-09-27 用户需求：步长与时长挂钩（等比）；2026-10-08 百分比口径：整屏=设置百分比（novax_swipe_full_percent）
        val deltaMs = swipeSeekDeltaMs(deltaPx / widthPx, events.swipeDurationMs(), events.swipeFullPercent())
        events.onSeekPreview(deltaMs)
        if (ev.changes.none { it.pressed }) {
            events.onSeekCommit(deltaMs)
            return SlideEnd.Commit
        }
    }
    events.onSeekCancel()
    return SlideEnd.Cancel
}

/** C6 §2.4/2.5（2026-09-14 二次修订：收窄为左右各 48dp 边缘竖条）左缘亮度 / 右缘音量 */
internal suspend fun AwaitPointerEventScope.runVerticalLevel(
    events: GestureEvents,
    downId: PointerId,
    startY: Float,
    height: Float,
    brightness: Boolean,
): SlideEnd {
    val startVal = if (brightness) events.currentBrightness() else events.currentVolume()
    while (true) {
        val ev = awaitPointerEvent()
        val change = ev.changes.firstOrNull { it.id == downId } ?: break
        // 第二根手指按下 → 转交双指缩放（指示条先收起）
        ev.changes.firstOrNull { it.id != downId && it.pressed }?.let { second ->
            events.onOverlayEnd()
            return SlideEnd.Takeover(second.id)
        }
        change.consume()
        val frac = (startVal + (startY - change.position.y) / height).coerceIn(0f, 1f)
        if (brightness) events.onBrightness(frac) else events.onVolume(frac)
        if (ev.changes.none { it.pressed }) {
            events.onOverlayEnd()
            return SlideEnd.Commit
        }
    }
    events.onOverlayEnd()
    return SlideEnd.Cancel
}

// ---------------- 主屏 ----------------
