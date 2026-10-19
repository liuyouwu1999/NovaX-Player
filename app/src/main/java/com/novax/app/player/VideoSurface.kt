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
import androidx.compose.foundation.layout.requiredSize
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

@Composable
internal fun VideoSurface(
    player: Player?,
    aspect: AspectMode,
    videoW: Int,
    videoH: Int,
    rotationDeg: Int,
    /** 每次「位置跳转」递增：供 surface 重挂使用（同位置重复 seek 也必须触发重挂，见下） */
    seekSeq: Int = 0,
    /** 每次「在播但没出画」自愈递增：同样触发 surface 重挂（修复断开的 bufferqueue） */
    recoverSeq: Int = 0,
    cues: List<androidx.media3.common.text.Cue> = emptyList(),
    subtitleSizeSp: Float = 18f,
    /**
     * 双指缩放状态（2026-09-15 用户需求，B 站同款）：以 **View 属性**（scaleX/scaleY/
     * translationX/translationY）施加在 SurfaceView/TextureView 上，而非 Compose
     * graphicsLayer——View 级矩阵会进入 SurfaceView 的 surface 定位计算（API 24 起官方
     * 支持 view 变换与 surface 同步缩放，由 SurfaceFlinger 硬件合成器完成，不重新分配
     * 缓冲、解码输出尺寸不变）；graphicsLayer 是 RenderNode 祖先层变换，SurfaceView 的
     * surface 定位**读不到**它，缩放会静默不生效。恒等态（1.0/零平移）零行为差异。
     * 平移夹紧（不露黑边）由手势层 PlayerZoomPolicy.clampPan 保证。
     */
    zoomState: androidx.compose.runtime.State<ZoomState>? = null,
    /** 画面镜像(2026-10-11 spec):水平翻转开关,与 zoom 共用 View 级 scaleX 一次写入(spec §4.2) */
    mirrored: Boolean = false,
    /**
     * 2026-10-18 竖屏切集拉伸修复：重挂触发键与显示几何分离——[videoW]/[videoH] 现为
     * display 口径（切集保持旧值，穿透不到 0x0），本键由调用方合成「display 值 + 真实几何
     * 丢失标志」：切集（新 player）只经 player key 重挂一次，不再经历 旧值→0x0→新值 三连
     * 重挂（每次重挂=黑屏+解码器重连 bufferqueue，是切集卡顿的构成之一）；同 player 的
     * 解码器换档仍经 lost 标志触发 rebind 自愈（2026-09-15「画面没了」修复语义不变）。
     */
    geometryResetKey: String = "$videoW x $videoH",
) {
    val context = LocalContext.current
    // 2026-10-11 实现期修订（spec §4.2）：镜像态强制 TextureView——模拟器实测 SurfaceView 的
    // View 级 scaleX=-1 被合成器静默忽略（NvPerf 确认写入 sv=-1.0 画面不翻；正缩放/旋转可用）。
    // TextureView 走视图树绘制、变换即时正确（RMVB 固定 TextureView 同款结论）；rotation≠0
    // 本就强制 TextureView，「跨 0° 切 surface 类型 + rebind」机制现成，镜像 toggle 复用。
    val useSurfaceView = rotationDeg == 0 && !mirrored
    // 唯一性约定：AndroidView 只挂这一个容器；surface 类型切换只换容器内 child（见上）
    // clipChildren=false：双指缩放把 surface child 放大到容器外（SurfaceView 走硬件合成
    // 不受此开关影响，TextureView 走视图树绘制会被默认裁剪——放大画面在容器外露不出）
    val container = remember { FrameLayout(context).apply { clipChildren = false } }
    val surfaceView = remember { SurfaceView(context) }
    val textureView = remember { TextureView(context) }

    DisposableEffect(player, useSurfaceView) {
        val rebindOnAttach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // 重挂载后 Surface/SurfaceTexture 会新建：重绑当前活跃 surface 才能恢复出画（幂等）
                bindSurface(player, container, surfaceView, textureView, useSurfaceView)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        container.addOnAttachStateChangeListener(rebindOnAttach)
        // 2026-09-15 用户录屏取证修复（「拖动进度条后画面缩小一点、随即画面没了，进度条与声音照走」）：
        // SurfaceView 的 Surface 在窗口重排/尺寸变化（含画中画、旋屏、display 变更、OEM 重建窗口）后
        // 会被销毁并新建一个 **新的** Surface 对象。只监听 View attach 是不够的：容器没换父节点时
        // surfaceDestroyed→surfaceCreated 不会触发 attach 回调，播放器就一直对着已销毁的旧 Surface
        // 输出 → 位置与声音正常、画面永久黑掉（正是用户看到的现象）。
        // 这里补上 holder 级监听：Surface 一销毁就解除绑定、一重建就立刻重绑，形成完整闭环。
        val holderCb = object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                Log.d("NvPerf", "surface recreated -> rebind (useSurfaceView=$useSurfaceView)")
                bindSurface(player, container, surfaceView, textureView, useSurfaceView)
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                Log.d("NvPerf", "surface changed ${width}x$height")
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                // 只解绑该 SurfaceView：Surface 已销毁，继续持有只会让渲染器向失效 surface 输出
                Log.d("NvPerf", "surface destroyed -> unbind")
                player?.clearVideoSurfaceView(surfaceView)
            }
        }
        surfaceView.holder.addCallback(holderCb)
        bindSurface(player, container, surfaceView, textureView, useSurfaceView)
        onDispose {
            container.removeOnAttachStateChangeListener(rebindOnAttach)
            surfaceView.holder.removeCallback(holderCb)
            // 仅最终离开组合（或切类型后随即重绑）走到这里：统一收口解绑。
            // runCatching：onDispose 可能在引擎已 release 后执行（P2-12，media3 MediaController
            // 路径 log-and-ignore，但未来 PlaybackEngineHandle(engine) 直连路径会抛 IllegalStateException）
            runCatching { player?.clearVideoSurfaceView(surfaceView) }
            runCatching { player?.clearVideoTextureView(textureView) }
        }
    }

    // 画面几何变更 / 自愈 → 主动重挂 surface（2026-09-15 真机复现「画面没了」修复）。
    //
    // 真机现场证据（NvPerf 日志，4K HEVC → 自适应切 1080P AVC）：
    //   21:42:59.096  video geometry changed -> 0x0 changes=2      ← 旧解码器销毁、尺寸归零
    //   21:42:59.662  videoDecoder=c2.mtk.avc.decoder initMs=...   ← 新解码器起来了
    //   21:43:00.099  NO-PICTURE: pos=428606 ... framesSinceSeek=0 ← 一帧都不出
    //   21:43:03.728  E C2MtkVdec: fetch graphic block err = 22
    //                 C2MtkBufferManager: bufferqueue may disconnected
    //   → 位置每 500ms 推进、音频正常、`playing=true`，画面永久黑。用户的「画面没了」= 这个。
    //
    // 关键（原实现漏掉的正是这一点）：几何变成 **0x0** 时，旧代码 `if (videoW<=0) return` 直接跳过，
    // 于是解码器换档时**恰好不重挂**，新解码器只能挂到旧 surface 的 consumer 上 → decode-only 不出画。
    //
    // 2026-09-18 用户反馈「±15s 每按一下卡一下」：seek 不改几何、不换解码器，原本附加的
    // seekSeq 重挂触发条件被移除——rebind 是 clear+setVideoSurface，强制解码器重连
    // bufferqueue，正是每次小跳变顿挫的来源。普通 seek 出画异常由 NvStallRecovery 看门狗
    // （framesSinceSeek==0 → recoverSeq → rebind-only）兜底，安全网仍在。
    val videoGeometryKey = geometryResetKey
    LaunchedEffect(player, videoGeometryKey, recoverSeq, useSurfaceView) {
        if (player == null) return@LaunchedEffect
        Log.d(
            "NvPerf",
            "surface rebind geometry=${videoGeometryKey} seekSeq=$seekSeq " +
                "recover=${NvStallRecovery.noPictureRecoverCount}",
        )
        rebindSurface(player, surfaceView, textureView, useSurfaceView)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val rotated = rotationDeg == 90 || rotationDeg == 270
        val effW = if (rotated) maxHeight else maxWidth
        val effH = if (rotated) maxWidth else maxHeight
        val videoAspect = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 16f / 9f
        // 旋转作为最外层 transform：先缩放/裁剪再整体转，pivot 中心 → 画面保持居中；
        // 0° 不挂 layer（SurfaceView overlay 直通的前提，见约定）
        val rotateLayer: Modifier = if (rotationDeg != 0) {
            Modifier.graphicsLayer { rotationZ = rotationDeg.toFloat() }
        } else {
            Modifier
        }
        // 旋转 90/270 的虚拟画布（effH=屏宽）算出的盒高可达屏宽级、比父 Box 高——.size() 会被
        // incoming constraints 静默钳制（2640×1216 屏上钳成 1216×1216 正方形；RmvbPlayerActivity
        // 1707 有同款模拟器 dumpsys 实证），旋转足迹永远回不到全屏、内容横向压缩（2026-10-03
        // 用户截图实测：竖条宽占比 0.460 = 1216/2640 精确吻合）。requiredSize 无视 incoming
        // 约束、超界内容居中溢出，旋转后的包围盒恰好落回屏幕；0°/180° 画布=真实屏幕，size 即可。
        val sized: Modifier = when (aspect) {
            AspectMode.FIT -> fitSize(videoAspect, effW, effH, required = rotated)
            AspectMode.FILL -> sizeBox(effW to effH, rotated)
            AspectMode.R16_9 -> coverSize(16f / 9f, effW, effH, required = rotated)
            AspectMode.R4_3 -> coverSize(4f / 3f, effW, effH, required = rotated)
        }
        // cover 裁剪（16:9/4:3）也以 modifier 表达：比例切换只更新 graphicsLayer，不换组合节点（约定见上）
        val coverLayer: Modifier = when (aspect) {
            AspectMode.R16_9 -> Modifier.graphicsLayer { coverTransform(videoAspect, 16f / 9f) }
            AspectMode.R4_3 -> Modifier.graphicsLayer { coverTransform(videoAspect, 4f / 3f) }
            else -> Modifier
        }
        // 双指缩放：读一次 zoom 触发本组件重组 → AndroidView.update 施加 view 级变换
        //（捏合期间每帧只重组本组件，不波及屏幕主体，见 PlayerScreen 侧说明）
        val zoom = zoomState?.value ?: ZoomState()
        AndroidView(
            factory = { container },
            update = {
                // 变换只施加在 surface child 自身（自有矩阵是 SurfaceView/TextureView 都
                // 明确支持的缩放路径）；不同时施加在 container——若框架把父链矩阵与自身
                // 矩阵都计入 surface 定位，两层叠加会二次相乘。两个 child 都写无妨，
                // 非活动 child 不参与渲染。
                surfaceView.scaleX = mirroredScaleX(mirrored, zoom.scale)
                surfaceView.scaleY = zoom.scale
                surfaceView.translationX = zoom.panX
                surfaceView.translationY = zoom.panY
                textureView.scaleX = mirroredScaleX(mirrored, zoom.scale)
                textureView.scaleY = zoom.scale
                textureView.translationX = zoom.panX
                textureView.translationY = zoom.panY
                Log.d(
                    "NvPerf",
                    "surface transform apply sv=${surfaceView.scaleX} tv=${textureView.scaleX} " +
                        "mirrored=$mirrored zoom=${zoom.scale} useSV=$useSurfaceView",
                )
            },
            modifier = rotateLayer.then(sized).then(coverLayer),
        )
        // 2026-09-15 D4：软字幕渲染层——与画面同一 sized 足迹（字幕随视频框定位/缩放）。
        // 只是 BoxWithConstraints 的兄弟节点，不进 surface 容器（单容器协议红线不受影响）；
        // SubtitleView 不消费触控，手势状态机事件路径不变。
        // 2026-09-27 用户需求：画面旋转只转画面，字幕保持水平——旋转时字幕挂屏幕坐标系：
        // 90/270° 足迹 = 旋转后的视频包围盒（内容宽高比互换后适配；非 FIT 的填充/cover
        // 模式旋转后必然盖满全屏，直接铺满）；180° 包围盒不变，仅去掉倒转。
        val subtitleModifier: Modifier = if (rotationDeg == 0) {
            rotateLayer.then(sized).then(coverLayer)
        } else if (rotationDeg == 90 || rotationDeg == 270) {
            when (aspect) {
                AspectMode.FIT -> fitSize(1f / videoAspect, maxWidth, maxHeight)
                else -> Modifier.fillMaxSize()
            }
        } else {
            sized.then(coverLayer)
        }
        AndroidView(
            factory = { ctx -> androidx.media3.ui.SubtitleView(ctx) },
            update = { view ->
                view.setCues(cues)
                // 复审 MINOR-2：字号档位变化即时生效（旧写法只在 factory 应用一次）
                view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp)
            },
            modifier = subtitleModifier,
        )
    }
}

/**
 * 绑定当前活跃 surface（幂等）：先 clearVideo* 再换容器 child、绑新 surface
 * （切换协议见 [VideoSurface] 约定）。player 未就绪时只保证 child 结构正确。
 */
internal fun bindSurface(
    player: Player?,
    container: FrameLayout,
    surfaceView: SurfaceView,
    textureView: TextureView,
    useSurfaceView: Boolean,
) {
    player?.clearVideoSurfaceView(surfaceView)
    player?.clearVideoTextureView(textureView)
    val active = if (useSurfaceView) surfaceView else textureView
    if (container.childCount != 1 || container.getChildAt(0) !== active) {
        container.removeAllViews()
        container.addView(
            active,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }
    if (useSurfaceView) {
        player?.setVideoSurfaceView(surfaceView)
    } else {
        player?.setVideoTextureView(textureView)
    }
}

/**
 * 重绑当前活跃 surface（不换容器 child，只重挂到播放器）——2026-09-15 用户录屏取证新增。
 *
 * 与 [bindSurface] 的区别：容器结构与 child 已由 [bindSurface] 保证，这里只把「当前活跃
 * surface」重新交给播放器。使用场景是 Surface 被平台销毁/重建（窗口重排、画中画、display 变更、
 * OEM 重建窗口）或画面几何变更之后——此时容器没换、attach 回调不触发，只有 holder 级回调
 * （surfaceCreated）或几何变化能发现，重挂后画面立即恢复。
 *
 * 幂等性：Surface 对象未变时 `setVideoSurfaceView` 是同对象重挂，播放器侧是一次同步赋值，
 * 不重建解码器（不会闪断）；因此可以在尺寸变化时放心调用。
 */
internal fun rebindSurface(
    player: Player?,
    surfaceView: SurfaceView,
    textureView: TextureView,
    useSurfaceView: Boolean,
) {
    if (player == null) return
    if (useSurfaceView) {
        // 先 clear 再 set：清掉可能指向已失效 Surface 的引用，避免渲染器继续向旧 Surface 输出
        player.clearVideoSurfaceView(surfaceView)
        player.setVideoSurfaceView(surfaceView)
    } else {
        player.clearVideoTextureView(textureView)
        player.setVideoTextureView(textureView)
    }
}
/** 无失真 cover 裁剪（原 CroppedVideo 内部逻辑，改以 GraphicsLayerScope 表达）：s_x/s_y = videoAspect / targetAspect */
internal fun GraphicsLayerScope.coverTransform(videoAspect: Float, targetAspect: Float) {
    if (videoAspect > targetAspect) {
        scaleX = videoAspect / targetAspect
        scaleY = 1f
    } else {
        scaleX = 1f
        scaleY = targetAspect / videoAspect
    }
}

/**
 * 画面镜像（2026-10-11 spec §4.2）：镜像 = 水平负缩放，与双指缩放共用 View 级 scaleX
 * 一次写入，无矩阵叠加顺序问题；mirrored=false 逐位恒等（零行为差异红线，VideoMirrorTest 锚点）。
 * 必须走 View 级而非 graphicsLayer：SurfaceView 的 surface 定位读不到祖先层变换
 * （graphicsLayer 缩放静默失效，真机取证见 [VideoSurface] zoomState 注释）。
 */
internal fun mirroredScaleX(mirrored: Boolean, zoomScale: Float): Float =
    if (mirrored) -zoomScale else zoomScale

/** 尺寸 modifier：required=true 用 requiredSize（无视父约束——旋转虚拟画布比真实屏幕高，见 [VideoSurface] 注释） */
private fun sizeBox(dims: Pair<Dp, Dp>, required: Boolean): Modifier =
    if (required) Modifier.requiredSize(dims.first, dims.second) else Modifier.size(dims.first, dims.second)

/** 在 maxW×maxH 内按 aspect 求最大内接尺寸（FIT：完整显示，留黑边）——纯几何拆出，JVM 单测直接测 [fitDims] */
internal fun fitSize(aspect: Float, maxW: Dp, maxH: Dp, required: Boolean = false): Modifier =
    sizeBox(fitDims(aspect, maxW, maxH), required)

internal fun fitDims(aspect: Float, maxW: Dp, maxH: Dp): Pair<Dp, Dp> =
    if (maxH > 0.dp && maxW / maxH > aspect) Pair(maxH * aspect, maxH) else Pair(maxW, maxW / aspect)

/** 在 maxW×maxH 内按 aspect 求最小覆盖尺寸（cover：配合 [coverTransform] graphicsLayer 缩放实现裁剪填充） */
internal fun coverSize(aspect: Float, maxW: Dp, maxH: Dp, required: Boolean = false): Modifier =
    sizeBox(coverDims(aspect, maxW, maxH), required)

internal fun coverDims(aspect: Float, maxW: Dp, maxH: Dp): Pair<Dp, Dp> =
    if (maxH > 0.dp && maxW / maxH > aspect) Pair(maxW, maxW / aspect) else Pair(maxH * aspect, maxH)

// ---------------- 顶部栏（C6 §1.1） ----------------
