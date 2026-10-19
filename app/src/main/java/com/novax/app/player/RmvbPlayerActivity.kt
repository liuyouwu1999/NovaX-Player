@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.novax.app.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.novax.app.BuildConfig
import com.novax.app.NvAppLanguage
import com.novax.app.R
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.WatchKind
import com.novax.app.data.NvPaths
import com.novax.app.data.playback.NvUri
import com.novax.app.data.privacy.NvPrivacyRedactor
import com.novax.app.ui.components.NvPlayerPopup
import com.novax.app.ui.components.NvSlider
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.theme.NovaXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCUtil
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * RM/RMVB 兜底播放页（2026-09-18 双内核）：ExoPlayer 不支持 RealMedia 私有容器，
 * 本类用 libVLC 引擎承接；路由入口在 PlayerActivity.startPlayback（按扩展名转入）。
 *
 * 2026-09-18 功能同步（用户反馈「功能/界面与主播放器不一致」）：
 * - 倍速改同款列表面板（0.5–3x 含 2.5x，变速不变调），不再无声循环
 * - 顶栏新增「画面比例」（libVLC ScaleType 循环：适应/裁剪/拉伸/16:9/4:3）
 * - 底栏按钮行直接复用主播放器 [NvPlayerControlRow] 中心对称组件：
 *   锁定 | 上集·快退Ns·暂停·快进Ns·下集 | 倍速（步长随设置档位，默认 15s）
 * - 2026-09-18 二次同步：双击三区（左退/右进/中间播放暂停）、「更多」上移顶栏
 * - 上一集/下一集接收文件夹队列（Extras 传播放列表）真实可切
 * - 「更多」面板：AB 循环（心跳判定越过 B 回 A）+ 睡眠定时（15/30/60 分钟到点暂停）
 * - 续播横幅：有起播位置时显示 5s「已从上次位置继续播放」
 *
 * 2026-09-20 流畅度专项 + 功能补齐（用户反馈「正常播放卡、长按倍速更卡；缺旋转等功能」）：
 * - libVLC 参数对齐官方 VLC App（NvRmvbOptions.buildLibVlcOptions）：移除假优化的全局
 *   skip-frame=1（VLC 语义 1=DEFAULT≈不跳），补官方同款自适应 skiploopfilter + soxr；
 *   「性能模式」（跳非参考帧，官方 KEY_ENABLE_FRAME_SKIP 同值）移入设置页开关
 * - 吞吐/卡顿诊断：NvRmvbProxy 送达速率 + 长按倍速期间 Buffering 计数（NvPerf）
 * - 新增「画面旋转」（主播放器 C12 同款）：0/90/180/270 循环。2026-09-21 根治「点击不转/
 *   转回 0° 画面压扁」：固定 TextureView（SurfaceView 路径 vout 创建即绑类型换不了面、
 *   换面重启后窗口口径仍是旧朝向压扁画面，应用层无解——详见 ensureAttached 注释）+
 *   画布用 requiredSize 溢出布局 + 旋转施加 View 级 rotation，任意角度即时无缝
 * - 新增「双指缩放」（B 站同款，逻辑复用 PlayerZoomPolicy）：捏合缩放 + 跟手平移 +
 *   还原按钮；变换施加在 player_surface_frame 下的实际显示面（View 级矩阵，主播放器同款）
 * - 修复存量 bug：封面截取仍按 childAt(0) 找 TextureView，切 SurfaceView 后已静默失效——
 *   改按类型遍历查找；SurfaceView 模式走 PixelCopy（minSdk 26 可用）
 *
 * 仍不支持（RMVB 格式/架构边界，诚实裁剪）：画中画、后台播放、画质自适应、
 * 远程源外挂字幕（代理只代理媒体流）。
 *
 * 读通道纪律：远程源经 [NvRmvbProxy]（127.0.0.1 Range 代理）走与主播放器完全相同的
 * repo.openStreamBlocking 连接池/lane/超时；本地源直读路径。
 */
class RmvbPlayerActivity : ComponentActivity() {

    // Task 13：应用内语言开关——base context 按偏好包装（33+ 原样返回；32- 自实现），见 NvAppLanguage
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(NvAppLanguage.wrap(newBase))


    // 2026-09-28 审计 A 第②刀：LibVLC/MediaPlayer/content fd 对象所有权移交 NvVlcEngine
    // （事件裁决与代理编排留在宿主）；vlcPlayer 以 getter 委托保持既有 42 处引用形态
    private val vlcEngine by lazy {
        NvVlcEngine(applicationContext, contentResolver) { e -> onVlcEvent(e) }
    }
    private val vlcPlayer: MediaPlayer? get() = vlcEngine.mediaPlayer
    private var proxy: NvRmvbProxy? = null
    private var historyWriter: NvHistoryWriter? = null
    private var heartbeatJob: Job? = null
    private var controlsHideJob: Job? = null
    private var loopJob: Job? = null

    /** 卡死确认看门狗（2026-10-13「卡了再出」）：播放中位置连续 STALL_CONFIRM_MS 不动才弹层 */
    private var stallWatchJob: Job? = null
    private var switchJob: Job? = null
    private var resumeApplied = false

    // 2026-10-18 发布终审 BUG-1：RMVB 兜底内核音频焦点——主内核由 ExoPlayer
    // setAudioAttributes(handleAudioFocus=true)+setHandleAudioBecomingNoisy(true) 内建处理，
    // libVLC aout 自身不请求焦点，本页此前完全缺席：RMVB 播放中来电话/其他音乐 App 抢焦点
    // 不暂停（双声道叠播）、拔耳机不暂停直接外放、导航播报不降音量。语义对齐 ExoPlayer：
    // LOSS=暂停不自动恢复；LOSS_TRANSIENT=暂停且 GAIN 恢复；CAN_DUCK=压低音量 GAIN 还原。
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    /** CAN_DUCK 降音生效中（GAIN 还原用；与长按倍速静音互斥见 duck/unduck 守卫） */
    private var audioDucked = false
    /** TRANSIENT 丢失前我们在播（仅此标志 GAIN 时自动恢复；永久 LOSS 不自动恢复） */
    private var audioTransientPaused = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        runOnUiThread {
            val mp = vlcPlayer ?: return@runOnUiThread
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    audioTransientPaused = false
                    runCatching { mp.pause() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    audioTransientPaused = mp.isPlaying
                    runCatching { mp.pause() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 长按倍速静音（setVolume(0)）期间不抬音量：duck 只在未静音时压低
                    if (!features.speedBoostMuted) {
                        runCatching { mp.setVolume(20) }
                        audioDucked = true
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (audioDucked && !features.speedBoostMuted) {
                        runCatching { mp.setVolume(100) }
                    }
                    audioDucked = false
                    if (audioTransientPaused && !mp.isPlaying) {
                        runCatching { mp.play() }
                    }
                    audioTransientPaused = false
                }
            }
        }
    }

    /** 拔耳机（ACTION_AUDIO_BECOMING_NOISY）立即暂停——与主内核 setHandleAudioBecomingNoisy 同语义 */
    private val becomingNoisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                audioTransientPaused = false   // 拔耳机属用户意图暂停，GAIN 不自动外放恢复
                runCatching { vlcPlayer?.pause() }
            }
        }
    }

    // 2026-10-12 假终守卫状态四件套+推进标志（spec §4.2c）：armed 用显式位而非 0 哨兵
    // （elapsedRealtime 开机初期可为小值）；maxTimeMs 初值=target、TimeChanged 单调取 max，
    // advancedBeyondTarget 供守卫强形态（无推进即假终）用
    private var resumeSeekArmed = false
    private var resumeSeekAtMs = 0L
    private var resumeSeekTargetMs = 0L
    private var resumeSeekMaxTimeMs = 0L
    private var resumeAdvancedBeyondTarget = false

    // 播放目标（切换上一集/下一集时可变）
    private var title: String = ""
    private var startPositionMs: Long = 0L
    private var sourceId: Long? = null
    private var sourcePath: String? = null
    private var localPath: String? = null
    /** content:// 打开时持有的文件描述符（2026-09-28 外部入口）：Media(fd) 读取期间必须
     * 存活，换集重开/释放引擎时关闭 */

    // 文件夹队列（与主播放器同一 playlist 的 url/title 列表）
    private var queue: List<Pair<String, String>> = emptyList()
    private var queueIndex = -1

    // Compose 可观察状态（VLC 事件回调统一切主线程写状态）
    private var uiPlaying by mutableStateOf(false)
    private var uiBuffering by mutableStateOf(true)
    private var uiError by mutableStateOf<String?>(null)
    /** 流量确认框可见态（2026-09-28 仅 Wi-Fi 免确认播放）：确认前不起播，零流量 */
    private var uiMeteredPrompt by mutableStateOf(false)

    /**
     * 入口已确认的移交起播（主播放器闸门确认后路由兜底页）：本次起播视为已确认，
     * onCreate 不再二次弹窗。只豁免 intent 携带的这一次起播；页内换集/连播/重试
     * 仍按 P0-2 口径每次重判。取消语义不变：非移交场景取消 = 退出。
     */
    private var entryMeteredConfirmed = false

    /** 确认框内「流量下不再提醒」勾选态（每次弹窗重置为未勾选） */
    private var uiMeteredRemindChecked by mutableStateOf(false)

    // 2026-09-28 审计 P0-2：换集也会弹流量确认（播完自动连播同径）——待裁决的目标队列索引，
    // -1 = 无待裁决换集（此时弹窗属于新开场景，取消 = 退出）
    private var pendingMeteredSwitchTarget: Int = -1

    // 2026-09-28 审计 P0-2：播放中网络切计量的兜底暂停（与 PlaybackService 同语义；
    // RMVB 页无前台服务，libVLC 软解代理流由页面自身持有）
    // 2026-10-11 误判修复（spec §4.2）：基线 playbackStartMetered 表达「起播时是否计量」，
    // 与 3s 复核相与才是真迁移；计量确认起播（基线 true）不拦
    private var meteredNetCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var meteredPauseJob: kotlinx.coroutines.Job? = null

    /** 本次 playback 会话起播瞬间活动网络是否计量（spec §4.2，与 PlaybackService 同语义）。
     *  四条装载链全部有闸门背书，openAndPlay 开头无条件重快照；默认 false 宁拦勿放。 */
    private var playbackStartMetered: Boolean = false
    private var uiPositionMs by mutableLongStateOf(0L)
    private var uiDurationMs by mutableLongStateOf(0L)
    private var uiSeekPreviewMs by mutableLongStateOf(Long.MIN_VALUE)   // MIN_VALUE = 无预览
    private var uiControlsVisible by mutableStateOf(true)
    // 2026-09-19 锁定重做（与主播放器一致）：按一下锁定按钮 = 完全锁定（钉死当前朝向），
    // 双击左下角锁图标解锁；不再用 SCREEN_ORIENTATION_LOCKED（锁屏期间会被竖屏化冻结，方向漂移根因）
    private var uiLocked by mutableStateOf(false)
    /** 2026-10-08 半锁（与主播放器同语义）：手势全可用、单击不出控制层、不钉朝向 */
    private var uiHalfLocked by mutableStateOf(false)
    private var uiHalfLockOn by mutableStateOf(true)
    /** 锁定时刻钉死的朝向；onResume 重申用它 */
    private var uiPinnedOrientation by mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    // 2026-09-20 手势 HUD（与主播放器同步）：亮度/音量竖条指示 + 倍速指示浮层共存的显隐位
    private var uiShowBrightness by mutableStateOf(false)
    private var uiBrightness by mutableFloatStateOf(0.5f)
    private var uiShowVolume by mutableStateOf(false)
    private var uiVolume by mutableFloatStateOf(0.5f)
    private var uiHudEpoch = 0   // 连续手势时旧协程不得收起新手势的 HUD

    // 2026-09-20 顶栏补键（音轨/视频信息）的 sheet 开关
    private var uiShowAudioSheet by mutableStateOf(false)
    private var uiShowSubSheet by mutableStateOf(false)
    private var uiShowInfoSheet by mutableStateOf(false)

    // 2026-09-28 审计 A 收敛：AB 循环/睡眠定时/长按倍速三态迁入 PlaybackFeatures
    // （此前三组状态与主播放器各写一份、靠注释人肉对齐）
    private val features = PlaybackFeatures()
    // 2026-09-19 长按倍速（与主播放器完全一致）：生效开关/值/左手慢放/自动静音（库表异步加载）
    /** 2026-09-19 用户需求：长按倍速期间下滑锁定（松手保持），再长按下滑取消 */
    private var uiLongPressOn by mutableStateOf(true)
    private var uiLongPressSpeed by mutableFloatStateOf(2f)
    private var uiLeftSlowmo by mutableStateOf(false)
    private var uiMuteHighSpeed by mutableStateOf(DEFAULT_MUTE_ABOVE_2X) // 2026-10-10 S4:初值随默认值关闭
    /** 每次 onLongPress 递增：滑动手势用它识别「本次手势内发起的长按」（BUG-C-R4-06） */
    private var speedShiftEpoch = 0
    /** 是否真正起播过（Event.Playing）：未起播的 flush 不得落库（BUG-C-R3-02 配套） */
    private var everStarted = false
    /** 2026-10-13 §2(P1-2):假终已证实、重播未起播(Playing 未清位)的窗口标记——
     * 窗口内 flushHistory 三调用点(Paused/EndReached 真终链/onDestroy)一律不写库 */
    private var fakeTailReplayPending = false
    /** 2026-10-13 §2 取证:DEBUG-only 注入开关——EndReached 直接走假终分支(release 常量折叠消除) */
    private var debugForceFakeTail = false
    private var uiLastLockTapAt by mutableStateOf(0L)
    private var uiLockHintVisible by mutableStateOf(false)
    /** 方向模式：0=横屏 1=竖屏 2=自动·重力（系统未锁时可入；FULL_SENSOR，2026-10-17 改听系统旋转锁定） */
    private var uiOrientationMode by mutableIntStateOf(0)
    /** 2026-10-17：系统旋转锁定状态（重力跟随改听系统，自有重力偏好开关已删） */
    private var uiSystemRotationLocked by mutableStateOf(false)
    /** 系统锁定 ContentObserver（onCreate 注册/onDestroy 注销，主线程回调） */
    private var rotationLockObserver: android.database.ContentObserver? = null
    private var uiSpeed by mutableFloatStateOf(1f)
    /** 2026-09-18：快进/快退步长（设置档位 10/15/25/30，默认 15）——DataStore 异步加载后生效 */
    private var uiSkipSec by mutableIntStateOf(15)
    /** 2026-10-08：滑动快进步长（KEY_SWIPE_FULL_PERCENT，整屏=片长百分比 1~50 默认 10）——与主播放器同键 */
    private var uiSwipeFullPercent by mutableIntStateOf(10)
    private var uiAspectIndex by mutableIntStateOf(0)
    private var uiShowSpeedSheet by mutableStateOf(false)
    private var uiShowMoreSheet by mutableStateOf(false)
    private var uiResumeBanner by mutableStateOf(false)
    private var coverCaptured = false

    // ---------------- 2026-09-20 画面旋转 / 双指缩放（主播放器 C12/B 站同款） ----------------

    /** 画面内容旋转（0→90→180→270 循环）；Activity 级状态：openAndPlay 组合前即需读取 */
    private var uiVideoRotation by mutableIntStateOf(0)
    // 2026-10-11 用户需求:画面镜像(水平翻转)——与 uiVideoRotation 同口径:会话级、换集复位、不落盘
    private var uiMirrored by mutableStateOf(false)

    /** 视频宽高（Playing/LengthChanged 后从 getSelectedTrack(Video) 读取；旋转几何与缩放夹紧共用） */
    private var uiVideoW by mutableIntStateOf(0)
    private var uiVideoH by mutableIntStateOf(0)

    /** 2026-09-27 切集自动定向：advanceQueue 置位，captureVideoTrackInfo 拿到新集宽高后应用
     *（与主播放器同语义：文件夹打开仍默认横屏；上一集手动方向/旋转不跨集保留） */
    private var pendingAutoOrientation = false

    /** 双指缩放状态（View 级变换施加在显示 surface child 上；逻辑复用 PlayerZoomPolicy） */
    private var uiZoom by mutableStateOf(ZoomState())

    /** 缩放手势生效门（压制并行的 tap/双击/长按误触；下一次手势 down 时清零） */
    private var uiZoomGestureActive = false

    /** 当前 surface attach 模式（null=未 attach；固定 TextureView 后首搭置 true） */
    private var attachedTextureView: Boolean? = null

    // ---------------- 长按倍速诊断（NvPerf：倍速期间卡顿取证） ----------------
    private var speedBoostFrom = 0f
    private var speedBoostStartAt = 0L
    private var speedBoostBufferings = 0

    // 速率过渡探针（临时取证）：倍速段前后按 TimeChanged 点列采样 Δpos/Δwall，
    // 定位「setRate 调用 → 真实倍速生效」的管线延迟；结案后移除
    private var rateProbeUntil = 0L
    private var probeLastWall = 0L
    private var probeLastPos = 0L

    // 变速冲刷（2026-09-21 用户反馈「进出倍速延迟 1~3s」根治；方案与调研见
    // docs/PLAN-rmvb-rate-latency.md、docs/RESEARCH-vlc4-rate-latency.md）：
    // - lastFlushTarget：同一目标速率不重复冲刷（换集复位路径清零）
    // - rateFlushUntilAt：冲刷后 1.2s 内抑制中心加载圈（只挡 UI 置真，原始状态照记）
    // - rawBuffering：原始缓冲态（冲刷守卫 + speedBoostBufferings 计数用，与抑制后的 uiBuffering 分离）
    // - lastManualSeekAt：手动 seek（进度条/快进退）后 600ms 内不冲刷，避免双重 seek
    private var lastFlushTarget = Float.NaN
    private var rateFlushUntilAt = 0L
    private var rawBuffering = false
    private var rawBufferingUpdatedAt = 0L
    private var lastManualSeekAt = 0L

    // 自适应冲刷（2026-09-21 NAS 毛骗终验结论）：冲刷的 seek 在「重灌贵」的路径上
    // （NAS RMVB：SMB 换流 + RM demux 重启 + RV40/Cook 软解重灌，模拟器实测 ~2s）
    // 会吞掉整个长按窗口，而这类文件裸 setRate 本就只要 ~300ms（3s→1.5s caching 后实测）；
    // 反之 WebDAV/本地样本裸 setRate 退出残留 ~1s、冲刷近零。→ 默认不冲刷，
    // 探针实测生效延迟 >600ms 的文件本次会话自动启用冲刷（换集复位）。
    private var rateFlushAdaptive = false
    // 成本感知反弹（2026-09-21 用户真机反馈「开了自适应后根本没法用」）：冲刷的 seek
    // 在重灌贵的路径（NAS RMVB：SMB 换流+RM demux 重启+软解重灌）上要停 ~2s，
    // 会把「慢」换成「冻」。冲刷后若 900ms 内管线没恢复（首条 TimeChanged），
    // 判定该文件冲刷成本过高 → 本会话拉黑冲刷，回退裸变速（两害取其轻）。
    private var rateFlushBlacklist = false
    private var flushStallStartedAt = 0L
    private var rateAdaptivePendingTarget = 0f
    private var rateAdaptivePendingAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2026-10-17 用户需求：进页默认档听系统旋转锁定——未锁=自动·重力（原「设置开」体验），
        // 锁定=横屏；applyOrientationByMode 按档施加，替代无条件 sensorLandscape
        uiSystemRotationLocked = NvOrientationPolicy.isSystemRotationLocked(this)
        uiOrientationMode = if (uiSystemRotationLocked) 0 else 2
        applyOrientationByMode()
        hideSystemBars()
        // 2026-09-28 审计 P0-2：播放中网络切计量的兜底暂停（见 registerMeteredWatch）
        registerMeteredWatch()
        // 2026-10-17：只读监听系统旋转锁定——播放中下拉切换即时生效（锁定→收拢当前朝向，解锁→恢复重力）
        rotationLockObserver = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean) {
                uiSystemRotationLocked = NvOrientationPolicy.isSystemRotationLocked(this@RmvbPlayerActivity)
                reconcileOrientationWithSystem()
            }
        }.also {
            contentResolver.registerContentObserver(NvOrientationPolicy.rotationLockUri, false, it)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 隐私锁（隐私计划 Task 12）：RMVB 兜底页同主播放器口径防截屏/录屏/最近任务缩略图
        //（锁定内容经软解路由到本页也绝不可截图）
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.player_video)
        startPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
        sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1L).takeIf { it >= 0 }
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH)
        localPath = intent.getStringExtra(EXTRA_LOCAL_PATH)
        entryMeteredConfirmed = intent.getBooleanExtra(EXTRA_METERED_CONFIRMED_START, false)
        // §2 取证注入(release 无此路径):模拟器无法自然构造真死区(5388eea 挂账),
        // 强制进假终分支以验证下游全链(停心跳/写库门/resetAbLoop 同形/重播从头)
        if (BuildConfig.DEBUG) {
            debugForceFakeTail = intent.getBooleanExtra("novax.debug.force_fake_tail", false)
        }
        @Suppress("DEPRECATION")
        queue = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS)
            ?.zip(intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES) ?: ArrayList()) { u, t -> u to t }
            ?: emptyList()
        queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)
        // E1-1（发版审查）：系统回收重建（savedInstanceState 非空）时用存档进度起播，
        // 内容键（当前队列 URL）不匹配则视为全新起播——否则旧 intent 的入口进度
        // 会覆盖心跳已落库的较新进度（用户观感：看过的片子进度回到开头）。
        savedInstanceState?.getString(STATE_URL)?.let { savedUrl ->
            if (queue.getOrNull(queueIndex)?.first == savedUrl) {
                val savedPos = savedInstanceState.getLong(STATE_POSITION_MS, -1L)
                if (savedPos >= 0L) startPositionMs = savedPos
            }
        }
        // 2026-09-21 审计 P3：取证期遗留的每起播一条 Log.i 已移除（release 保留、无排障价值）

        historyWriter = NvHistoryWriter(
            MediaDb.get(applicationContext),
            com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext), // 无痕/密文口径，漏接则 RMVB 页照写明文历史
        )
        attachHistory()

        // 2026-09-21 真机排障（vivo + Clash）：libVLC 对 http:// MRL 无条件跟随系统 HTTP 代理
        // （连 127.0.0.1 回环都不放过，且不理会绕过名单）——代理把本地 RMVB 流转发给远程节点
        // 返回 502,播放必然失败且用户无从归因。起播前检测并明确提示，把环境故障变成看得懂。
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val hp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.httpProxy
            if (hp != null && hp.host != null) {
                Log.w("NvRmvb", "system http proxy detected: ${hp.host}:${hp.port} — local rmvb proxy loopback will be hijacked (Clash/VPN)")
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.player_proxy_vpn_toast),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        // E1-2（发版审查）：与切集/重试同一串行域——首集探文件大小的窗口内点「下集」，advanceQueue 的 switchJob?.cancel() 才拦得住，否则两个 openAndPlay 交错留孤儿代理
        switchJob = lifecycleScope.launch {
            // 2026-09-28 流量确认（仅 Wi-Fi 免确认播放）：远程源在计量网络先确认再起播。
            // 2026-09-28 P0-2 收口：换集按目标重新判定（见 advanceQueue）；
            // 2026-10-11 三档化：「仅提醒一次」档会话内不再重复拦截，口径见 spec §3
            // （RMVB 页无前台服务，引擎恒不在场——本页退后台即新会话，回来换集重新弹）。
            // 2026-10-10：主播放器闸门确认后的路由移交（EXTRA_METERED_CONFIRMED_START）视为
            // 同一次已确认起播，不再二次弹窗（此前 RMVB 直开一次用户动作连弹两道确认）；
            // 页内换集/连播/重试不经过该标记，仍每次重判
            if (!entryMeteredConfirmed && needsMeteredConfirmTarget()) {
                uiMeteredRemindChecked = false
                uiMeteredPrompt = true
                return@launch
            }
            setupAndPlay()
        }
        // 2026-09-20：包 NovaXTheme(darkTheme = true)——播放界面永远深色，且 MaterialTheme
        // scheme 与 NvColors 同源（不再依赖 LocalNvPalette 默认值兜底）
        setContent {
            NovaXTheme(darkTheme = true) {
                // Liquid Glass（spec §6）：播放器域独立 HazeState——与主播放器各自独立组合树、
                // env 各自下发（decisions §4.5：source 挂视频包裹容器，控制条/弹窗面板做兄弟）
                com.novax.app.ui.glass.LocalNvGlassEnvProviding {
                    RmvbScreen()
                    if (uiMeteredPrompt) {
                        com.novax.app.ui.components.NvMeteredConfirmDialog(
                            remindDisabledChecked = uiMeteredRemindChecked,
                            onRemindDisabledCheckedChange = { uiMeteredRemindChecked = it },
                            onContinue = { onMeteredPromptResult(true) },
                            onCancel = { onMeteredPromptResult(false) },
                        )
                    }
                }
            }
        }
    }

    /** E1-1：快照当前播放位置与内容键，onCreate 优先恢复（对齐 PlayerActivity P2 修复口径）。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentUrl = queue.getOrNull(queueIndex)?.first ?: return
        outState.putString(STATE_URL, currentUrl)
        outState.putInt(STATE_QUEUE_INDEX, queueIndex)
        outState.putLong(STATE_POSITION_MS, (vlcPlayer?.time ?: uiPositionMs).coerceAtLeast(0L))
    }

    /** 历史快照按当前播放目标重挂（切集时同步刷新） */
    private fun attachHistory() {
        val keyPath = sourcePath ?: localPath ?: title
        historyWriter = NvHistoryWriter(
            MediaDb.get(applicationContext),
            com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext), // 无痕/密文口径，漏接则 RMVB 页照写明文历史
        )
        val se = parseSeasonEpisode(keyPath) ?: parseSeasonEpisode(title)
        historyWriter?.attach(
            NvHistoryWriter.Snapshot(
                sourceId = sourceId,
                sourceType = null,          // setup 阶段拿到真实 SourceEntity 后覆盖
                path = keyPath,
                title = title,
                kind = se?.let { WatchKind.EPISODE.name } ?: WatchKind.MOVIE.name,
                positionMs = startPositionMs,
                durationMs = 0L,
                seasonNumber = se?.first,
                episodeNumber = se?.second,
            ),
        )
    }

    // ---------------- 引擎搭建与切集 ----------------

    /** 播放中网络切计量的兜底暂停（2026-09-28 审计 P0-2）：3s 去抖后以活动网络复核，
     * 仍计量且在播 → 暂停 + 提示（远端源才有流量后果，本地跳过） */
    private fun registerMeteredWatch() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities,
            ) {
                if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    onActiveNetworkMaybeMetered()
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            meteredNetCallback = cb
        }
    }

    private fun onActiveNetworkMaybeMetered() {
        if (meteredPauseJob?.isActive == true) return
        meteredPauseJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(3_000)
            if (!NvMeteredGuard.isMeteredNetwork(this@RmvbPlayerActivity)) return@launch
            val type = sourceId?.let { s ->
                withContext(Dispatchers.IO) {
                    runCatching { NvGraph.sourceDao(this@RmvbPlayerActivity).getById(s) }.getOrNull()?.type
                }
            }
            val remote = NvMeteredGuard.isRemoteTarget(localPath ?: "", type)
            // H-3（2026-10-18 发布终审，与 PlaybackService.onActiveNetworkMaybeMetered 同口径）：
            // 补起播/换集的缓冲窗——uiPlaying 只在 Event.Playing 后为真，此前 VLC demux 打开 +
            // network-caching 灌数据的窗口（代理已在经 SMB 拉流）uiPlaying=false，计量切换静默
            // 漏拦。uiBuffering=true 即「正在灌」；Playing 后的 VLC 内部缓冲 uiPlaying 保持真，
            // 天然覆盖
            if (remote && !playbackStartMetered && (uiPlaying || uiBuffering)) {
                runCatching { vlcPlayer?.pause() }
                Log.d("NvRmvb", "metered network switch -> auto pause")
                toast(getString(R.string.player_metered_paused_toast))
            }
        }
    }

    /**
     * 流量确认判定（2026-09-28 仅 Wi-Fi 免确认播放；2026-10-11 三档化）：远程目标
     * （nv 源非 LOCAL / 代理 http）且当前计量网络 → 先弹确认；本地文件（content:// fd
     * 分支/绝对路径）恒免确认。档位裁决收口 [MeteredGate.remindMode]（含旧键迁移）+
     * [NvMeteredGuard.needsConfirmation]（never 免/every 恒弹/session_once 随会话标志）。
     * 2026-09-28 P0-2 扩展：可传目标 URL 供换集判定（不传 = 当前会话目标）。
     */
    private suspend fun needsMeteredConfirmTarget(targetUrl: String? = null): Boolean {
        if (!NvMeteredGuard.needsConfirmation(
                MeteredGate.remindMode(this), NvMeteredGuard.sessionConfirmed)
        ) return false
        val novax = targetUrl?.let { u ->
            if (u.startsWith(NvUri.SCHEME + "://")) NvUri.parse(u) else null
        }
        val sid = novax?.first ?: sourceId
        val type = sid?.let { s ->
            withContext(Dispatchers.IO) {
                runCatching { NvGraph.sourceDao(this@RmvbPlayerActivity).getById(s) }.getOrNull()?.type
            }
        }
        val urlForGuard = when {
            novax != null -> novax.second      // 源内路径（判定走 type 分支，这里主要防直链 http）
            targetUrl != null -> targetUrl     // 换集目标（本地绝对路径/直链）
            else -> localPath ?: ""            // 当前会话（本地分支/content://）
        }
        return NvMeteredGuard.isRemoteTarget(urlForGuard, type) && NvMeteredGuard.isMeteredNetwork(this)
    }

    /** 流量确认框结果：继续 = 起播/落地换集；取消 = 新开场景退出，换集场景仅放弃本次切换、
     * 继续播当前集。继续 → 置位会话标志（「仅提醒一次」档本会话不再弹）；勾选
     * 「流量下不再提醒」→ 写「不提醒」档（设置页三档可改回）。
     * 2026-09-28 P0-2：按待裁决上下文分流；2026-10-11 三档化（spec §4.4）。 */
    private fun onMeteredPromptResult(continuePlayback: Boolean) {
        uiMeteredPrompt = false
        val switchTarget = pendingMeteredSwitchTarget
        pendingMeteredSwitchTarget = -1
        if (!continuePlayback) {
            if (switchTarget >= 0) {
                toast(getString(R.string.player_switch_cancelled_toast))
            } else {
                finish()
            }
            return
        }
        if (uiMeteredRemindChecked) {
            // 勾「流量下不再提醒」→ 写「不提醒」档（2026-10-11 三档；设置页可改回）
            lifecycleScope.launch { runCatching { putPlayerPref(KEY_METERED_REMIND_MODE, METERED_REMIND_NEVER) } }
        }
        // 「仅提醒一次」档：本会话已确认（取消分支不置位，spec §4.4）
        NvMeteredGuard.markSessionConfirmed()
        // 2026-10-11 误判修复配套（spec §4.2，四轮 P2-3 + 五轮 P2-1）：确认后的起播/换集
        // 纳入 switchJob 取消保护——此前裸 launch,setup 数秒窗口内点「下集」,advanceQueue
        // 只 cancel switchJob,双 openAndPlay/proxy 交错(BUG-C-R4-03 同类);确认时刻
        // switchJob 为 null(onCreate 裸 launch 路径)或已完成的闸门 job(重试/换集路径),cancel 均为 no-op 不误杀
        switchJob?.cancel()
        switchJob = lifecycleScope.launch {
            if (switchTarget >= 0) {
                val entry = queue.getOrNull(switchTarget)
                if (entry != null) applyQueueSwitch(switchTarget, entry) else fail(getString(R.string.player_err_switch_target_gone))
            } else {
                // 2026-10-13（spec §2.1）：重试经确认走同一重建管线——否则「蜂窝+每次提醒」
                // 档下重试对播放中引擎裸 setMedia，R2（跳回片头）在该可达路径复发；
                // 首起/假终重播流经此处 stop() 为 no-op、兜底闸经 resumeApplied 空转，安全
                stopRebuildAndPlay()
            }
        }
    }

    private suspend fun setupAndPlay() {
        uiBuffering = true
        try {
            val playUrl = queue.getOrNull(queueIndex)?.first
                ?: sourceId?.let { sid ->
                    sourcePath?.let {
                        "novax://$sid" + android.net.Uri.encode(NvPaths.normalize(it), "/")
                    }
                }
                ?: localPath
                ?: run { fail(getString(R.string.player_err_missing_url)); return }
            openAndPlay(playUrl, resume = startPositionMs > 0)
            // 2026-10-12 续播横幅置位移入 onVlcEvent Playing 裁决分支（spec §3.6）：
            // 原位置在异步 Playing 前盲亮 0.1~1s，静态片尾裁决命中时「已从上次位置继续播放」
            // 会撒谎，且时序上 seek 落位前就宣称「已继续播放」
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 2026-09-21 审计 P2：切集取消不得走 fail() 污染新集错误态
        } catch (e: Exception) {
            Log.w("NvRmvb", "setup failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 解析播放目标（novax:// → 源内路径，远程走代理/本地走绝对路径）并起播 */
    private suspend fun openAndPlay(playUrl: String, resume: Boolean) {
        // 2026-10-11 误判修复（spec §4.2）：基线随装载刷新——四条调用链（onCreate/确认
        // 回调/advanceQueue/重试）全部有闸门背书，此处无条件快照即安全；与 3s 复核同源
        // 同 API，「起播时非计量、现在计量」的真迁移才拦
        playbackStartMetered = NvMeteredGuard.isMeteredNetwork(this)
        uiBuffering = true
        try {
            val novax = if (playUrl.startsWith(NvUri.SCHEME + "://")) NvUri.parse(playUrl) else null
            sourceId = novax?.first
            sourcePath = novax?.second
            if (novax != null) attachHistory()

            val uri: String = if (novax != null) {
                val source = withContext(Dispatchers.IO) {
                    runCatching { NvGraph.sourceDao(this@RmvbPlayerActivity).getById(novax.first) }.getOrNull()
                } ?: run { fail(getString(R.string.player_err_source_gone)); return }
                historyWriter?.retagSourceType(source.type.name)
                if (source.type.name == "LOCAL") {
                    val abs = withContext(Dispatchers.IO) {
                        runCatching { NvGraph.repository().localAbsolutePath(source, novax.second) }.getOrNull()
                    } ?: run { fail(getString(R.string.player_err_local_missing, novax.second)); return }
                    abs
                } else {
                    var size = withContext(Dispatchers.IO) {
                        runCatching {
                            NvGraph.mediaItemDao(this@RmvbPlayerActivity)
                                .findByKey(novax.first, novax.second)?.sizeBytes ?: 0L
                        }.getOrDefault(0L)
                    }
                    // 2026-09-19 用户实测修复：文件未被扫描入库（如从文件页直点、扫描根未覆盖、
                    // 新文件未重扫）时 DB 查不到大小，兜底直接失败。回退到向源列父目录 stat——
                    // 列不到才算真不可达
                    if (size <= 0L) {
                        size = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = novax.second.substringBeforeLast('/', "")
                                val name = novax.second.substringAfterLast('/')
                                // 2026-09-21 动态实验新发现：源根目录文件的 dir 恰为空串——
                                // 旧守卫 `if (dir.isEmpty()) return 0L` 把「根目录」误判为
                                // 「无法定位」，导致根目录下未入库文件的相邻集切换 100% 报
                                // 「无法获取文件大小」。list("") 经 NvPaths.asDirectory 归一
                                // 为 "/"（与文件浏览器列根目录同路径），空串合法，直接列。
                                NvGraph.repository().list(source, dir).getOrNull()
                                    ?.firstOrNull { it.path == novax.second || it.name == name }
                                    ?.size ?: 0L
                            }.getOrDefault(0L)
                        }
                        if (size > 0L) {
                            Log.w("NvRmvb", "size from source stat (db miss): $size")
                        }
                    }
                    proxy?.stop()
                    val p = NvRmvbProxy.startFor(NvGraph.repository(), source, novax.second, size)
                    if (p == null) {
                        fail(getString(R.string.player_err_size_unknown))
                        return
                    }
                    // 2026-09-21 审计 P2：startFor 内部 NonCancellable 保证「已 start 的代理必返回」；
                    // 此处若任务已被切集取消，必须自行收口，否则 ServerSocket+线程池孤儿化至进程死
                    if (!kotlin.coroutines.coroutineContext.isActive) {
                        p.stop()
                        return
                    }
                    proxy = p
                    // F-R3（spec §4.2）+ 2026-10-13「卡了再出」改版：上游读失败不再直接弹层
                    // ——只记录原因待用；是否真卡由卡死看门狗（startStallWatch）裁决：位置
                    // 连续 10s 不动才弹层，自愈型抖动（重连快于缓冲耗尽）全程无感。实例一致性
                    // 校验（spec 裁决 6）：切集/重试已换新代理时旧报告作废——代理侧 running
                    // 闸是第一道，此为第二道；回调自代理连接线程出发，切主线程
                    // i18n Task 9：代理只发 (kind, detail)，用户可见文案此处按 kind 映射资源拼装
                    p.onUpstreamFailure = { kind, detail ->
                        runOnUiThread {
                            if (proxy === p) {
                                lastUpstreamError = getString(
                                    if (kind == NvRmvbProxy.UPSTREAM_CONNECT_FAILED) {
                                        R.string.player_rmvb_err_upstream_connect
                                    } else {
                                        R.string.player_rmvb_err_upstream_interrupted
                                    },
                                    detail,
                                )
                            }
                        }
                    }
                    // F-R3 对偶（spec §2.2）：上游恢复 → 清待用原因（常见路径：失败没弹过层）；
                    // 若看门狗已用该原因弹层（uiError == reason）一并自动收层——比对保证
                    // 不误清「libVLC 无法解码」等非上游错误
                    p.onUpstreamRecovered = {
                        runOnUiThread {
                            if (proxy === p) {
                                val reason = lastUpstreamError
                                lastUpstreamError = null
                                if (uiError != null && uiError == reason) uiError = null
                            }
                        }
                    }
                    p.url
                }
            } else {
                playUrl
            }

            if (!vlcEngine.isStarted) {
                // 2026-09-20 性能模式挂起读（playerPrefsSnapshot 冷启动缓存可能未热，本页未预热）：
                // 设置「RMVB 性能模式」开 → --avcodec-skip-frame/-idct 2（官方同值，跳非参考帧）
                val perfFrameSkip = runCatching {
                    playerPrefsStore.data.first()[KEY_RMVBFramesSkip] ?: false
                }.getOrDefault(false)
                // 官方 getDeblocking 同款自适应判据（VLCUtil.getMachineSpecs 无参静态方法，
                // 读不到按弱机兜底）——详见 NvRmvbOptions.buildLibVlcOptions 注释
                val specs = runCatching { VLCUtil.getMachineSpecs() }.getOrNull()
                vlcEngine.start(
                    ArrayList(
                        // 官方 getDeblocking 同款自适应判据（VLCUtil.getMachineSpecs 无参静态方法，
                        // 读不到按弱机兜底）——详见 NvRmvbOptions.buildLibVlcOptions 注释。
                        // 2026-09-21 审计 P3：移除取证期遗留的 "--verbose=2"（R8 剥不掉 native
                        // 日志，RMVB 全程向 logcat 连刷，软解路径上叠加 CPU/电量开销）。
                        buildLibVlcOptions(
                            networkCachingMs = NvRmvbProxy.networkCachingMs(proxy != null),
                            perfFrameSkip = perfFrameSkip,
                            mipsLike = specs != null && ((specs.hasArmV6 && !specs.hasArmV7) || specs.hasMips),
                            freqMhz = specs?.frequency?.toInt() ?: 0,
                            bogoMips = specs?.bogoMIPS?.toInt() ?: 0,
                            processors = specs?.processors ?: 1,
                        ),
                    ),
                )
                var waited = 0
                while (surfaceLayout == null && waited < 3_000) {
                    delay(50)
                    waited += 50
                }
                if (surfaceLayout == null) {
                    fail(getString(R.string.player_err_surface_timeout))
                    return
                }
            }
            ensureAttached()

            // 2026-09-18 真机修复：Media(libVlc, String) 把字符串按**本地路径**解释——
            // http 代理 URL 变成 MRL 'file:////http%3A//...' 直接打不开（Fold3 实测日志）。
            // 必须按 Uri 构造；本地绝对路径转 file:// Uri 走同一构造路径
            // 2026-09-28 外部「打开方式」：content:// 的读授权随 VIEW Intent 授予，但 VLC
            // 核心不认 content MRL（Media(Uri) 只是 encodeVLCUri→nativeNewFromLocation，
            // 反编译 eap29 确认），须自开文件描述符走 Media(fd)（VLC 官方 App 同款）
            // fd/Uri 构造细节（content fd 所有权、file:// 双前缀防御）在 NvVlcEngine.openMedia
            val media = vlcEngine.openMedia(uri)
            // 2026-09-20 与主播放器同步（自动加载同名字幕开关，默认开）：本地文件时挂同目录
            // 同名 .srt 外挂字幕（libVLC addSlave 从属轨）；远程源暂不支持（代理只代理媒体流）
            if (playerPrefsSnapshot()?.get(KEY_SUB_AUTO_LOAD) ?: true) {
                if (uri.startsWith("/")) {
                    val srt = java.io.File(uri.substringBeforeLast('/') + "/" +
                        uri.substringAfterLast('/').substringBeforeLast('.') + ".srt")
                    if (srt.isFile) {
                        media.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave(
                            org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, 1,
                            android.net.Uri.fromFile(srt).toString()))
                        Log.d("NvRmvb", "srt attached: ${srt.name}")
                    }
                }
            }
            resumeApplied = false
            resetResumeSeekGuard()
            everStarted = false
            applyStartResume = resume
            vlcPlayer?.media = media
            // BUG-C-R4-09：播放器已持有 media，立即释放本次引用（原先靠 finalizer 兜底）
            media.release()
            // 发布终审 BUG-1：起播即请求焦点（幂等）——RMVB 页此前无音频焦点处理
            requestAudioFocusForPlayback()
            vlcPlayer?.play()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 2026-09-21 审计 P2：切集取消不得走 fail() 污染新集错误态
        } catch (e: Exception) {
            Log.w("NvRmvb", "setup failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private var applyStartResume = false

    // ---------------- surface 绑定（旋转类型自适应）与画面旋转 ----------------

    /**
     * surface 绑定（幂等，2026-09-20 旋转功能引入类型自适应）：
     * - rotation==0 → SurfaceView 直通（硬 overlay 合成，锐度/延迟最优，默认路径不回归）；
     * - rotation!=0 → TextureView（View 级 rotation 旋转必需）。
     *
     * 2026-09-21 终版结论（模拟器 SurfaceFlinger 逐层取证）：**固定 TextureView，放弃
     * SurfaceView 直通**。SurfaceView 路径的三个死结：
     *   ① vout 创建时绑定 surface 类型，播放中 attachViews(textures=true) 换不了面
     *      （活跃视频层仍是 SurfaceView，Z 轴旋转同步只做一半）；
     *   ② 换面重启（stop→attach）后，AWindow 给新 vout 的窗口口径仍是旧朝向，转回 0°
     *      时 buffer 仍是竖屏尺寸 → 画面压成全宽扁条；且 native 设过 setFixedSize 后
     *      view 尺寸变化不再改 buffer（±2px 扰动无效），应用层无解；
     *   ③ TextureView 走视图树绘制，随当前 view 几何/变换即时正确——全部角度、重启后
     *      均实测无一处坏。RMVB 是软解 CPU 瓶颈，合成层差异可忽略。
     * 本函数只在「未挂载」（首搭）时挂载，幂等。
     * VLCVideoLayout 内部是 ViewStub：attach 后才 inflate 出显示 child，新 child 是新 View 实例，
     * 缩放 View 变换必须重放（applyZoomToSurface）。
     */
    private fun ensureAttached() {
        val layout = surfaceLayout ?: return
        if (attachedTextureView == true) return
        runCatching { vlcPlayer?.detachViews() }
        vlcPlayer?.attachViews(layout, null, false, true)
        attachedTextureView = true
        // 旧 SurfaceView child 若存在（升级会话残留）显式 GONE，只留 TextureView 活跃面。
        (layout.getChildAt(0) as? android.view.ViewGroup)?.let { frame ->
            for (j in 0 until frame.childCount) {
                val c = frame.getChildAt(j)
                if (c is SurfaceView) c.visibility = View.GONE
            }
        }
        Log.d("NvPerf", "rmvb surface attached textureView=true rotation=${uiVideoRotation}°")
        applyZoomToSurface()
    }

    /** 顶栏「画面旋转」：0→90→180→270 循环。固定 TextureView 后纯 View 级旋转，即时无缝。 */
    private fun rotateVideo() {
        uiVideoRotation = (uiVideoRotation + 90) % 360
        resetZoom()          // 画布几何变了，缩放平移口径失效，先归位
        ensureAttached()
        toast(getString(R.string.player_rotation_toast, uiVideoRotation))
        keepControls()
    }

    /**
     * 顶栏「画面镜像」(2026-10-11 spec §4.3):状态→显式施加→toast,次序对齐 rotateVideo 模板。
     * 必须显式 applyZoomToSurface():它是 scaleX 唯一写入点——ensureAttached 已 attach 时
     * 短路不重放(:762),AndroidView.update 只重放 rotation,不施加则只变图标不出画面。
     */
    private fun toggleVideoMirror() {
        uiMirrored = !uiMirrored
        applyZoomToSurface()
        toast(getString(if (uiMirrored) R.string.player_mirror_on else R.string.player_mirror_off))
        keepControls()
    }

    /**
     * 换 surface 类型重启管线（2026-09-21 旋转根治）：记住当前进度 → 以同 URL、
     * resume=当前进度 重跑 openAndPlay——vout 以新 surface 类型重建，TextureView 从
     * 创建起就在场，View 级 rotation 从此精确跟随。对用户的可感成本 ≈ 一次 seek。
     */
    /** 视频宽高捕获（旋转几何与缩放夹紧的口径来源；Playing/LengthChanged 后各试一次） */
    /** 视频宽高捕获（VLC4：getSelectedTrack(Video) 取代 3.x currentVideoTrack） */
    private fun captureVideoTrackInfo() {
        val vt = runCatching {
            vlcPlayer?.getSelectedTrack(IMedia.Track.Type.Video) as? IMedia.VideoTrack
        }.getOrNull() ?: return
        if (vt.width > 0 && vt.height > 0 && (vt.width != uiVideoW || vt.height != uiVideoH)) {
            uiVideoW = vt.width
            uiVideoH = vt.height
            Log.d("NvPerf", "rmvb video track ${vt.width}x${vt.height}")
            applyPendingAutoOrientation()
        }
    }

    /** 2026-09-27 切集自动定向应用：竖屏视频→竖屏、横屏→横屏、方形保持；锁定 = 显式冻结
     *  朝向、自动·重力 = 用户显式选择跟随姿态，两者都不覆盖 */
    private fun applyPendingAutoOrientation() {
        if (!pendingAutoOrientation) return
        pendingAutoOrientation = false
        if (uiLocked || uiOrientationMode == 2) return
        if (uiVideoW <= 0 || uiVideoH <= 0 || uiVideoW == uiVideoH) return
        val target = if (uiVideoH > uiVideoW) 1 else 0
        if (target != uiOrientationMode) {
            // 2026-10-14(spec §2.3-3): 此处不清 uiVideoRotation——切集路径 rotation 已由 applyQueueSwitch(:1426)先行清零,依赖该先序
            uiOrientationMode = target
            applyOrientationByMode()
            toast(getString(R.string.player_orientation_toast, getString(if (target == 1) R.string.player_orientation_portrait else R.string.player_orientation_landscape)))
            Log.w("NvPerf", "rmvb auto orientation on switch -> $target (${uiVideoW}x${uiVideoH})")
        }
    }

    // ---------------- 双指缩放（B 站同款；夹紧逻辑复用 PlayerZoomPolicy） ----------------

    /**
     * 当前实际显示的 surface（attach 后 ViewStub 才 inflate）。
     *
     * libvlc 视图结构随版本变化（2026-10-11 模拟器 dumpsys 实证，libvlc 4.0.0-eap29）：
     * VLCVideoLayout → FrameLayout(无名) → FrameLayout(@id/player_surface_frame) →
     * TextureView(@id/texture_video)——**三层**；旧实现按 3.6.2 的两层结构只搜一层
     * 中间 ViewGroup，在新版下永远 miss（displaySurfaceView 恒 null → 缩放/镜像静默
     * no-op 的存量 bug）。改为 DFS 全树查找：返回第一个类型匹配（[attachedTextureView]
     * 期望）的 SurfaceView/TextureView，层级无关；类型不符时兜底取首个 surface 类 view。
     * 换类型重挂后旧 child 的 surface 已 detach 但 View 仍 VISIBLE
     * （VideoHelper.detachViews 无 visibility 变更），故按期望类型判定，不按 visibility。
     */
    private fun displaySurfaceView(): View? {
        val layout = surfaceLayout ?: return null
        val wantTexture = attachedTextureView == true
        var any: View? = null
        fun dfs(v: View): View? {
            val isTexture = v is TextureView
            if (v is SurfaceView || isTexture) {
                if (isTexture == wantTexture) return v
                any = any ?: v   // 结构意外（类型不符）时的兜底候选
            }
            (v as? android.view.ViewGroup)?.let { g ->
                for (i in 0 until g.childCount) dfs(g.getChildAt(i))?.let { return it }
            }
            return null
        }
        return dfs(layout) ?: any
    }

    /**
     * 缩放/平移施加到显示 surface：View 级矩阵（scaleX/translation）——与主播放器同款，
     * SurfaceView 的 surface 定位会读 View 变换（API 24+ SurfaceFlinger 合成，解码输出不变），
     * TextureView 直接跟随视图树。顺带把中间裁剪父级（player_surface_frame）的 clipChildren
     * 关掉：TextureView 缩放溢出发生在直接父级的绘制裁剪，只关 VLCVideoLayout 不够。
     * displaySurfaceView 为 null（attach 前/竞态）时静默，ensureAttached 重挂与 zoom 更新路径
     * 都会重放。
     */
    private fun applyZoomToSurface() {
        val layout = surfaceLayout ?: return
        for (i in 0 until layout.childCount) {
            (layout.getChildAt(i) as? android.view.ViewGroup)?.clipChildren = false
        }
        val v = displaySurfaceView() ?: return
        v.scaleX = mirroredScaleX(uiMirrored, uiZoom.scale)
        Log.d("NvPerf", "rmvb transform apply v=${v.javaClass.simpleName} scaleX=${v.scaleX} mirrored=$uiMirrored zoom=${uiZoom.scale} pivot=${v.pivotX}x${v.pivotY} w=${v.width} h=${v.height}")
        v.scaleY = uiZoom.scale
        v.translationX = uiZoom.panX
        v.translationY = uiZoom.panY
    }

    private fun setZoom(z: ZoomState) {
        uiZoom = clampZoom(z)
        applyZoomToSurface()
    }

    private fun resetZoom() {
        uiZoom = ZoomState()
        applyZoomToSurface()
    }

    /** 夹紧到当前画布口径：scale 上限 + 平移不露黑边（clampPan 在画布坐标系内计算） */
    private fun clampZoom(z: ZoomState): ZoomState {
        val layout = surfaceLayout ?: return z
        val sw = layout.width.toFloat()
        val sh = layout.height.toFloat()
        if (sw <= 0f || sh <= 0f) return PlayerZoomPolicy.clampScale(z.scale).let { ZoomState(it, z.panX, z.panY) }
        // 旋转 90/270 时画布=宽高互换的虚拟画布（变换施加在画布内的 child 上，夹紧必须同空间）
        val rotated = uiVideoRotation == 90 || uiVideoRotation == 270
        val canvasW = if (rotated) sh else sw
        val canvasH = if (rotated) sw else sh
        val va = if (uiVideoW > 0 && uiVideoH > 0) uiVideoW.toFloat() / uiVideoH else 16f / 9f
        val scaled = z.copy(scale = PlayerZoomPolicy.clampScale(z.scale))
        val (contentW, contentH) = PlayerZoomPolicy.contentDims(aspectIndexToZoomMode(uiAspectIndex), va, canvasW, canvasH)
        return PlayerZoomPolicy.clampPan(scaled, contentW, contentH, canvasW, canvasH)
    }

    // ---------------- 长按倍速诊断（NvPerf） ----------------

    private fun markSpeedBoostStart(fromSpeed: Float, toSpeed: Float) {
        speedBoostFrom = fromSpeed
        speedBoostBufferings = 0
        speedBoostStartAt = android.os.SystemClock.elapsedRealtime()
        rateProbeUntil = speedBoostStartAt + 15_000
        probeLastWall = 0L
        probeLastPos = 0L
        Log.d("NvPerf", "rmvb speed boost $fromSpeed→${toSpeed}x (soft decode)")
    }

    /** 倍速段收口：时长 + 期间 Buffering 次数（真机据此判断 2x 卡是解码还是网络） */
    private fun logSpeedBoostEnd() {
        if (speedBoostStartAt == 0L) return
        val dur = android.os.SystemClock.elapsedRealtime() - speedBoostStartAt
        Log.d("NvPerf", "rmvb speed boost end from=${speedBoostFrom}x dur=${dur}ms bufferingDuring=$speedBoostBufferings")
        speedBoostStartAt = 0L
        speedBoostBufferings = 0
        // 松手后探针再采 8s：覆盖「放手 → 倍速停」的回落延迟
        rateProbeUntil = maxOf(rateProbeUntil, android.os.SystemClock.elapsedRealtime() + 8_000)
    }

    // ---------------- 变速冲刷（2026-09-21：进出倍速延迟 1~3s 根治） ----------------

    /**
     * 变速：rate 直设 + 原地精确 seek 冲刷管线。
     *
     * 历史脉络：0.05/60ms 细斜坡（VLC3 时代防 aout flushing）→ 实验B改直通。用户实测
     * 直通仍「进入/退出倍速延迟 1~3s」：libvlc4 的 vlc_aout_stream_ChangeRate 只改
     * stream->sync.rate 不冲刷（vlc master src/audio_output/dec.c:976），AudioTrack 里
     * 残余的旧速率样本（上限 750ms，audiotrack.c AUDIOTRACK_MAX_BUFFER_US）必须播完才可闻；
     * 官方 vlc-android fastplay 同为裸 setRate、同样滞后，且全链路无「立即生效」API——
     * 官方唯一手段是 seek（decoder/aout/vout 全 flush + 时钟重建，dec.c:981）。
     * 故 rate 直设后同位置精确 seek：fast=false 绝不吸附关键帧，杜绝 RV40 稀疏关键帧回跳。
     */
    private fun rampRate(target: Float) {
        val mp = vlcPlayer ?: return
        val old = mp.rate
        mp.rate = target
        if (kotlin.math.abs(target / old - 1f) > 0.02f) {
            // 挂起「生效延迟」测量（自适应冲刷判据）：探针 eff 到达目标即结算
            rateAdaptivePendingTarget = target
            rateAdaptivePendingAt = android.os.SystemClock.elapsedRealtime()
            // 音频管线冲刷（重选音轨，见 flushAudioPipeline）：aout 换 audiotrack 后残留
            // 只剩几十 ms，默认裸 setRate 即可；此开关留作兜底实验位
            if (AUDIO_FLUSH_ON_RATE && uiPlaying) flushAudioPipeline()
        }
        maybeFlushForRate(mp, target, old)
    }

    /**
     * 重选音轨冲刷音频管线（VLC4 倍速即时生效主修）：
     * unselectTrackType(Audio) → selectTrack(id)。es_out 对音轨的取消/重选会销毁并重建
     * audio decoder 与 vlc_aout_stream（AudioTrack/aaudio 缓冲里的旧速率样本随之丢弃），
     * 新速率 ~100-300ms 内可闻；视频管线不受影响（无 seek 重灌、无位置回跳）。
     * 对照：seek 冲刷全管线有效但 NAS RMVB 重灌 ~2s（曾把「慢」换成「冻」被拉黑）；
     * 裸 setRate 官方同样滞后。eap29 API：unselectTrackType(int)/selectTrack(String)。
     */
    private fun flushAudioPipeline() {
        val mp = vlcPlayer ?: return
        val tracks = runCatching { mp.getTracks(IMedia.Track.Type.Audio) }.getOrNull() ?: return
        val id = tracks?.firstOrNull { it?.selected == true }?.id
            ?: tracks?.firstOrNull()?.id
            ?: return   // 无音轨：纯视频路径 setRate 本就即时，无需冲刷
        runCatching {
            mp.unselectTrackType(IMedia.Track.Type.Audio)
            mp.selectTrack(id)
        }
        Log.d("NvPerf", "rmvb rate audioFlush track=$id")
    }

    /**
     * 冲刷守卫（PLAN §三）：播放中、非原始缓冲、非紧随手动 seek、速率真实变化（>2%）、
     * 可 seek、位置/时长就绪、同目标不重复。冲刷后开 1.2s 加载圈抑制窗——Buffering 事件
     * 照常写 rawBuffering 与倍速段计数，只是不把 uiBuffering 置真闪圈。
     */
    private fun maybeFlushForRate(mp: MediaPlayer, target: Float, old: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFlushTarget == target) return
        val changed = kotlin.math.abs(target / old - 1f) > 0.02f
        // rawBuffering 只在「新近报告」时才算数（VLC 只在回填期间发 Buffering 事件，
        // 静默=已满；入点回填若只发一条 <100 事件就静默，残留 true 会把出点 flush 误拦）
        val bufferingFresh = rawBuffering && now - rawBufferingUpdatedAt < 800
        // 冲刷被暂时性守卫拦下（暂停/缓冲/刚手动 seek）但速率已实际变更时清 latch：
        // 否则恢复后同目标值的下一次真实变速会被去重误拦，退回 1~3s 延迟（实施审核建议1）
        if (!uiPlaying || bufferingFresh) {
            if (changed) lastFlushTarget = Float.NaN
            return
        }
        if (now - lastManualSeekAt < 600) {
            if (changed) lastFlushTarget = Float.NaN
            return
        }
        if (!changed) return
        if (rateFlushBlacklist) return
        if (!RATE_FLUSH_ENABLED && !rateFlushAdaptive) return
        if (!mp.isSeekable) return
        val dur = uiDurationMs
        val pos = mp.time
        if (dur <= 0 || pos <= 0) return
        val seekTo = (pos.coerceIn(0L, dur) - RATE_FLUSH_SEEK_NUDGE_MS).coerceAtLeast(0L)
        // fast=true 吸附关键帧（重灌快但落点回跳）；false=精确无回跳。
        // NAS 毛骗终验：fast 每次长按回跳 ~3s，弃用；重灌成本由降 network-caching 压制
        mp.setTime(seekTo, RATE_FLUSH_SEEK_FAST)
        lastFlushTarget = target
        rateFlushUntilAt = now + 1_200
        flushStallStartedAt = now   // 冲刷成本计时：等首条 TimeChanged 结算（见 sampleRateAdaptive 调用处）
        Log.d("NvPerf", "rmvb rate flush rate=$old→$target pos=$seekTo")
    }

    /**
     * 自适应冲刷判据：setRate 后挂起目标速率，采样 eff 到达目标 ±15% 时结算生效延迟；
     * 超过 600ms 视为「该文件裸 setRate 慢」，本次会话后续变速自动启用冲刷。
     * 缓冲期样本不可信（位置停滞≠变速慢），直接放弃本次测量。
     *
     * 2026-09-22 起默认禁用（ADAPTIVE_FLUSH_ENABLED=false）：该机制为 aaudio 时代设计
     * （裸变速 1~3s 时兜底）。换 `--aout=audiotrack` 后裸变速残留只剩几十 ms，而探针
     * 粒度只有 TimeChanged 的 250-350ms——松手过渡（2x→1x 的时钟减速）实测 0.64s 就
     * 越过 600ms 阈值误开冲刷，随后每次变速都带 seek：模拟器实测 807ms 内 531 次
     * Buffering + 746ms 重灌（正是用户「卡顿」的来源）。audiotrack 下裸变速即最优，
     * 冲刷只保留手动开关（RATE_FLUSH_ENABLED）。
     */
    private fun sampleRateAdaptive(now: Long, eff: Float) {
        if (!ADAPTIVE_FLUSH_ENABLED) {
            rateAdaptivePendingTarget = 0f
            return
        }
        val target = rateAdaptivePendingTarget
        if (target == 0f) return
        // 5s 超时：网络停顿/极端卡顿下 eff 长期到不了目标，测量作废（防止迟到的
        // 结算把停顿误判成「裸变速慢」而误开冲刷）
        if (now - rateAdaptivePendingAt > 5_000) {
            rateAdaptivePendingTarget = 0f
            return
        }
        // 与冲刷守卫同口径：只认 800ms 内的新近 Buffering 报告（静默=缓存已满，样本可信；
        // 残留 true 若不作时效会永久丢弃采样，自适应在慢设备上永不启用——终审必改1）
        if (rawBuffering && now - rawBufferingUpdatedAt < 800) {
            rateAdaptivePendingTarget = 0f
            return
        }
        val effX = eff / 1000f
        if (kotlin.math.abs(effX - target) <= target * 0.15f) {
            val latency = now - rateAdaptivePendingAt
            if (latency > 600 && !rateFlushAdaptive && !rateFlushBlacklist) {
                rateFlushAdaptive = true
                Log.d("NvPerf", "rmvb rate adaptive flush ON: latency=${latency}ms target=${target}x")
            }
            rateAdaptivePendingTarget = 0f
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        runOnUiThread {
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    uiPlaying = true
                    // §2 收口:任何成功起播(从 Ended 重启必发 Playing——:1404 反例仅限
                    // 「播放中换 media」)清写库门标记
                    fakeTailReplayPending = false
                    uiBuffering = false
                    uiError = null
                    everStarted = true
                    captureVideoTrackInfo()
                    if (!resumeApplied) {
                        resumeApplied = true
                        if (applyStartResume && startPositionMs > 0) {
                            val len = runCatching { vlcPlayer?.length }.getOrNull() ?: 0L
                            if (isRmvbTailZoneResume(startPositionMs, len)) {
                                // 2026-10-12 静态片尾裁决（spec §4.2a）：metadata 时长不可信，
                                // 以 libvlc 实测 length 复核；从头播、不亮续播横幅（§3.6）
                                Log.w("NvPerf", "rmvb tail-zone resume -> from 0 (len=$len, saved=$startPositionMs)")
                                startPositionMs = 0L
                            } else {
                                Log.w("NvRmvb", "start resume: seek -> ${startPositionMs}ms (len=$len)")
                                vlcPlayer?.time = startPositionMs
                                armResumeSeekGuard(startPositionMs)
                                // 横幅自 setupAndPlay 移入（spec §3.6）：seek 实际落位后才亮，
                                // 裁决命中永不亮
                                uiResumeBanner = true
                                lifecycleScope.launch {
                                    delay(5_000)
                                    uiResumeBanner = false
                                }
                            }
                        }
                    }
                    keepControls()
                    startHeartbeat()
                    startLoopWatch()
                    startStallWatch()
                    // 2026-09-18：RMVB 抽帧系统解码器不支持 → 播放起播稳定后从渲染面
                    // 截一帧写封面（TextureView.getBitmap，零额外解码；每次目标只试一次）
                    if (!coverCaptured) {
                        coverCaptured = true
                        lifecycleScope.launch {
                            delay(3_500)
                            captureCoverFromRender()
                        }
                    }
                }
                MediaPlayer.Event.Paused -> {
                    uiPlaying = false
                    // 挂起中的生效延迟测量作废（暂停时长会污染 latency，误开冲刷——终审建议2）
                    rateAdaptivePendingTarget = 0f
                    flushHistory()
                }
                MediaPlayer.Event.Stopped -> {
                    uiPlaying = false
                    stopHeartbeat()
                    stopStallWatch()
                }
                MediaPlayer.Event.EndReached -> {
                    uiPlaying = false
                    // 2026-09-21 模拟器事件级取证：VLC4 eap 的 stop() 会补发 EndReached
                    //（+14ms 一次、+30s 一次，time=0/len=0），曾被当成「播完」误触自动连播。
                    // 真播完必然 time≈length 且 length 已知；len<=0（无媒体/未知长）一律按
                    // stop 残留忽略——RMVB 源长度恒已知，不受影响。
                    val len = runCatching { vlcPlayer?.length }.getOrNull() ?: 0L
                    val time = runCatching { vlcPlayer?.time }.getOrNull() ?: 0L
                    val nearEnd = len > 0L && time >= len - 1_500L
                    Log.d("NvPerf", "rmvb EndReached nearEnd=$nearEnd time=$time len=$len")
                    if (!nearEnd) {
                        return@runOnUiThread
                    }
                    // 2026-10-12 假终守卫（spec §4.2c）：三段式判别（静态复核/强形态/弱形态）。
                    // 命中=本会话从未播出画面，执行播完链会把没看过的内容标看完并跳走——
                    // 恢复为从头重播当前文件，与 retryFromError 同形（含计量闸门，§3.5）
                    val fakeTailHit = (BuildConfig.DEBUG && debugForceFakeTail) ||
                        (resumeSeekArmed && isRmvbFakeTailEnd(
                            advancedBeyondTarget = resumeAdvancedBeyondTarget,
                            wallSinceSeekMs = android.os.SystemClock.elapsedRealtime() - resumeSeekAtMs,
                            maxTimeSeenMs = resumeSeekMaxTimeMs,
                            seekTargetMs = resumeSeekTargetMs,
                            lenAtEndMs = len,
                        ))
                    if (fakeTailHit) {
                        Log.w("NvPerf", "rmvb FAKE tail end: target=$resumeSeekTargetMs maxSeen=$resumeSeekMaxTimeMs len=$len forced=${BuildConfig.DEBUG && debugForceFakeTail} -> replay from 0")
                        resetResumeSeekGuard()
                        // §2(P1-2):假终已证实→重播起播前停心跳+立写库门——心跳与
                        // flushHistory 不得把从未播出的 ≈len 写进 watch_history
                        stopHeartbeat()
                        fakeTailReplayPending = true
                        toast(getString(R.string.player_tail_decode_restart))
                        startPositionMs = 0L
                        switchJob?.cancel()
                        switchJob = lifecycleScope.launch {
                            // 2026-10-12 终审 Minor 修入：清未决切集弹窗目标——假终与「下集」
                            // 计量弹窗并发时，确认回调不得劫持为 applyQueueSwitch(旧target)
                            pendingMeteredSwitchTarget = -1
                            // §2 同形修入:与 retryFromError(attach 在 launch 外/闸门外)对齐——
                            // 弹窗取消/继续两条路径都不丢 attachHistory 与 resetAbLoop
                            // (原注释溯源保留:AB 点若指向刚证实不可播的尾区,随重播残留
                            // 生效是陷阱 §3.8,与 applyQueueSwitch 同口径;不在尾区也一样劫持)
                            attachHistory()
                            features.resetAbLoop()
                            // 计量闸（spec §3.5）：恢复=远程目标 byte0 整文件重拉，流量后果
                            // 不小于重试；取消走确认回调既有 else 分支 finish（零流量）
                            if (needsMeteredConfirmTarget()) {
                                uiMeteredRemindChecked = false
                                uiMeteredPrompt = true
                                return@launch
                            }
                            setupAndPlay()
                        }
                        return@runOnUiThread
                    }
                    flushHistory()
                    // BUG-C-R9-03：与主播放器同口径尊重「播完自动播下一集」开关（novax_auto_next，默认开）
                    lifecycleScope.launch {
                        val autoNext = runCatching {
                            playerPrefsStore.data.first()[KEY_AUTO_NEXT] ?: true
                        }.getOrDefault(true)
                        if (autoNext) advanceQueue(1)
                    }
                }
                MediaPlayer.Event.EncounteredError -> fail(getString(R.string.player_err_vlc_decode))
                MediaPlayer.Event.Buffering -> {
                    // VLC4：事件字段改为 getter（event.buffering → getBuffering()）
                    // rawBuffering 无条件记录（变速冲刷守卫/取证计数用）；uiBuffering（中心
                    // 加载圈）在变速冲刷后 1.2s 抑制窗内不置真，防每次进出倍速闪圈；
                    // 窗内 raw 变 false 仍立即置假（圈不残留）
                    rawBuffering = event.getBuffering() < 100f
                    rawBufferingUpdatedAt = android.os.SystemClock.elapsedRealtime()
                    val suppressSpinner = android.os.SystemClock.elapsedRealtime() < rateFlushUntilAt
                    // 2026-10-13（spec §2.3 Fix C）：原表达式反相——窗外真缓冲不亮圈、窗内反而
                    // 跟随 raw 亮圈（恰是全页最不需要圈的时刻）。加 ! 对齐上方注释意图
                    if (uiPlaying) uiBuffering = rawBuffering && !suppressSpinner
                    // 长按倍速期间的卡顿取证（logSpeedBoostEnd 收口打印）
                    if (features.speedShiftActive) speedBoostBufferings++
                }
                MediaPlayer.Event.LengthChanged -> {
                    uiDurationMs = event.getLengthChanged()
                    captureVideoTrackInfo()
                }
                MediaPlayer.Event.TimeChanged -> {
                    uiPositionMs = event.getTimeChanged()
                    // 2026-10-12 假终守卫推进跟踪（spec §4.2c）：单调取 max + 越标置位，
                    // 供 EndReached 三段式判别的强/弱形态输入
                    if (resumeSeekArmed) {
                        val t = event.getTimeChanged()
                        if (t > resumeSeekMaxTimeMs) resumeSeekMaxTimeMs = t
                        if (resumeSeekMaxTimeMs > resumeSeekTargetMs) resumeAdvancedBeyondTarget = true
                    }
                    // 有效速率点列（100ms 粒度 Δpos/Δwall）：速率过渡探针（NvPerf 取证，
                    // probe 窗口内才打日志）与自适应冲刷判据共用同一条采样
                    val probeNow = android.os.SystemClock.elapsedRealtime()
                    // 冲刷成本结算：冲刷 seek 后的首条 TimeChanged = 管线恢复点；
                    // 恢复耗时 >900ms → 该文件冲刷成本过高，拉黑回退裸变速（成本感知反弹）
                    if (flushStallStartedAt != 0L) {
                        val restartCost = probeNow - flushStallStartedAt
                        flushStallStartedAt = 0L
                        if (restartCost > 900 && rateFlushAdaptive) {
                            rateFlushBlacklist = true
                            rateFlushAdaptive = false
                            Log.d("NvPerf", "rmvb rate adaptive flush OFF: restart=${restartCost}ms too costly")
                        } else {
                            Log.d("NvPerf", "rmvb rate flush restart=${restartCost}ms")
                        }
                    }
                    if (probeLastWall > 0L) {
                        val dw = probeNow - probeLastWall
                        if (dw >= 100) {
                            val dp = uiPositionMs - probeLastPos
                            val eff = dp * 1000f / dw
                            if (probeNow < rateProbeUntil) {
                                Log.d("NvPerf", "rmvb probe wall=${probeNow / 100} pos=${uiPositionMs / 100} eff=${String.format("%.2f", eff)}")
                            }
                            sampleRateAdaptive(probeNow, eff)
                            probeLastWall = probeNow
                            probeLastPos = uiPositionMs
                        }
                    } else {
                        probeLastWall = probeNow
                        probeLastPos = uiPositionMs
                    }
                    // AB 循环判定（心跳粒度不够，跟随时间事件最准）
                    val loopBack = PlaybackFeatures.loopSeekBackMs(uiPositionMs, features.loopAMs, features.loopBMs)
                    if (loopBack != null) {
                        vlcPlayer?.time = loopBack
                    }
                }
            }
        }
    }

    /**
     * 从当前渲染面截一帧写封面：RMVB 系统抽帧必失败（容器/编码都不支持），
     * 播放即有解码帧——直取渲染面，480 宽 JPEG 落到与主抽帧管线相同的缓存/DB 位置。
     * 失败静默（封面保持字母徽章），绝不影响播放。
     *
     * 2026-09-20 修复 + 旋转适配：旧实现 `layout.getChildAt(0) as? TextureView` 拿到的是
     * inflate 出来的中间 FrameLayout（player_surface_frame）而非渲染面，切 SurfaceView 后
     * 封面已静默失效——现经 [displaySurfaceView] 按 attach 类型取**实际显示面**（孙节点）：
     * TextureView 直接取 bitmap；SurfaceView 内容不过视图树，走 PixelCopy（minSdk 26 可用）。
     * JPEG 编码/落盘在 Dispatchers.IO，主线程只做像素拷贝。
     */
    private suspend fun captureCoverFromRender() {
        try {
            val w = 480
            val src = withContext(Dispatchers.Main) {
                val display = displaySurfaceView()
                when (display) {
                    is TextureView -> if (display.isAvailable) display.bitmap else null
                    is SurfaceView ->
                        if (display.holder.surface?.isValid == true) {
                            val out = android.graphics.Bitmap.createBitmap(
                                display.width.coerceAtLeast(1), display.height.coerceAtLeast(1),
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            runCatching {
                                suspendCancellableCoroutine { cont ->
                                    PixelCopy.request(
                                        display, out,
                                        { r -> cont.resume(if (r == PixelCopy.SUCCESS) out else null) },
                                        android.os.Handler(android.os.Looper.getMainLooper()),
                                    )
                                }
                            }.getOrNull()
                        } else null
                    else -> null
                }
            } ?: return
            val keyPath = sourcePath ?: localPath ?: title
            val file = withContext(Dispatchers.IO) {
                val s = if (src.width > w) {
                    val h = src.height.toLong() * w / src.width.coerceAtLeast(1)
                    android.graphics.Bitmap.createScaledBitmap(src, w, h.toInt(), true).also {
                        if (it !== src) src.recycle()
                    }
                } else src
                // 命名与主抽帧管线同目录同哈希规则（ThumbCache.hashKey）；keyPath 在采集阶段
                // 解析一次贯穿复用——3.5s 延迟窗口内切集不会造成文件名与 DB 行错位
                val f = java.io.File(
                    com.novax.app.data.thumb.ThumbCache.thumbsDir(applicationContext),
                    com.novax.app.data.thumb.ThumbCache.hashKey(sourceId ?: 0L, keyPath) + ".jpg",
                )
                f.parentFile?.mkdirs()
                f.outputStream().use { out -> s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out) }
                s.recycle()
                f
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val dao = NvGraph.mediaItemDao(applicationContext)
                    val row = sourceId?.let { sid -> dao.findByKey(sid, keyPath) }
                    if (row != null) {
                        dao.updateThumb(
                            row.id, file.absolutePath, "DONE",
                            row.durationMs, row.width, row.height, row.sizeBytes, row.mtimeMs,
                        )
                        // 隐私红线（Task 7）：防御性包裹——文件名走哈希但出口统一过脱敏
                        android.util.Log.w(
                            "NvThumb",
                            NvPrivacyRedactor.redactText("RMVB cover captured: ${file.name}", null),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("NvThumb", "cover capture failed: ${e.message}")
        }
    }

    private fun fail(msg: String) {
        uiBuffering = false
        uiError = msg
    }

    // ---------------- 队列（上一集/下一集/播完自动连播） ----------------

    private fun advanceQueue(direction: Int) {
        val target = queueIndex + direction
        val entry = queue.getOrNull(target) ?: return
        // BUG-C-R4-03：换集串行化——快速连点时旧 openAndPlay 与新任务的 proxy 停/起交错，
        // 被覆盖的 NvRmvbProxy（ServerSocket+线程池）泄漏到进程结束
        switchJob?.cancel()
        switchJob = lifecycleScope.launch {
            // 2026-09-28 审计 P0-2：换集（含播完自动连播，同径）= 新的一次起播，计量网络按
            // 目标重判；2026-10-11 三档化——「仅提醒一次」档会话内不重复拦截（spec §3），
            // Wi-Fi 开局后切蜂窝的自动连播此前会静默整集拉流的防护仍由「每次重判」保留。
            // 裁决通过前不动任何状态（取消不留半状态）
            if (needsMeteredConfirmTarget(entry.first)) {
                uiMeteredRemindChecked = false
                pendingMeteredSwitchTarget = target
                uiMeteredPrompt = true
                return@launch
            }
            applyQueueSwitch(target, entry)
        }
    }

    /** 换集落地（闸门通过/用户确认后调用）：原 advanceQueue 的状态变更与起播管线整体移入，
     * 保证「确认前零副作用」——取消切集不留下 index 已推进/倍速态已清的半状态。 */
    private suspend fun applyQueueSwitch(target: Int, entry: Pair<String, String>) {
        // 2026-10-18 发布终审 BUG-3：手动切集先落当前集进度——此前仅自动连播路径有
        // flush（EndReached 分支），手动上/下一集的进度停留在最后一次心跳（≤10s 前）。
        // 此刻旧 media 仍挂在 vlcPlayer 上，mp.time 即当前集位置；everStarted/
        // fakeTailReplayPending 守卫沿用（未起播/假终窗口不写）；与 EndReached 先冲的
        // 双 flush 无害（record 的 0 值闸 + upsert 幂等）。必须在 attachHistory() 重挂
        // 新集快照**之前**执行，否则旧位置会写进新集历史
        flushHistory()
        pendingMeteredSwitchTarget = -1
        queueIndex = target
        title = entry.second.ifBlank { entry.first.substringAfterLast('/') }
        startPositionMs = 0L
        // 2026-10-12 假终守卫提前清（spec §4.2c）：压缩「旧 media 真 EndReached 与新切集
        // 并发、旧守卫态仍武装」的窗口
        resetResumeSeekGuard()
        uiPositionMs = 0L
        uiDurationMs = 0L
        features.resetAbLoop()
        // 2026-10-11 画面镜像换集复位——次序敏感:必须先置 false 再 resetZoom()
        // (resetZoom→applyZoomToSurface 以当时 uiMirrored 写 scaleX;颠倒则留下 -1f 且无后续纠正路径,spec §4.1)
        uiMirrored = false
        resetZoom()                       // 换集画面几何重置，缩放/平移不跨集保留
        // 2026-09-27 用户需求：切集按新视频比例自动定向（与主播放器同步）——清旧集宽高防误判，旋转清零
        // 2026-10-22 补总开关（KEY_AUTO_ORIENT_ON_SWITCH 默认开；关 = 与主播放器同口径不转向）
        pendingAutoOrientation = playerPrefsSnapshot()?.get(KEY_AUTO_ORIENT_ON_SWITCH) ?: true
        uiVideoW = 0
        uiVideoH = 0
        uiVideoRotation = 0
        logSpeedBoostEnd()                // 换集打断的倍速段照常收口打印
        // 2026-09-19：换集清倍速生效/锁定态并复位速率（libVLC 的 rate 跨 Media 存留）
        // 2026-09-28 审计 A：复位口径收敛到 PlaybackFeatures.resetForEpisodeSwitch
        val wasBoostMuted = features.speedBoostMuted
        features.resetForEpisodeSwitch()
        if (wasBoostMuted) {
            // BUG-C-R4-04：长按静音（3x）期间切集必须恢复音量——原实现漏复位，
            // 下一集 1x 无声且永不再触发恢复分支
            vlcPlayer?.setVolume(100)
        }
        // 换集直设不走冲刷：media 马上重启管线天然重建，不存在稳态画面的变速过渡
        lastFlushTarget = Float.NaN
        rateFlushAdaptive = false
        rateFlushBlacklist = false
        flushStallStartedAt = 0L
        rateAdaptivePendingTarget = 0f
        rateFlushUntilAt = 0L   // 抑制窗不跨集残留（终审建议3）
        vlcPlayer?.rate = uiSpeed
        // 2026-09-25 切集/连播对齐主播放器：查历史静默续播（受「续播」设置门控），
        // 无有效进度则从头。此前硬编码 resume=false，页内切集永远从 0 播
        startPositionMs = resumePositionForEntry(entry.first)
        attachHistory()
        openAndPlay(entry.first, resume = startPositionMs > 0)
        // 实测取证（2026-09-25 模拟器）：VLC4「播放中换 media」不补发 Event.Playing——
        // onVlcEvent 里 Playing 锚定的续播 seek 在切集路径不触发，续播静默落空回到 0。
        // 此处等播放就绪（isPlaying+length 可 seek）后直接落位；与 Playing 路径重复
        // seek 时目标一致无副作用。
        if (startPositionMs > 0) {
            val mp = vlcPlayer
            var waited = 0
            while (mp != null && waited < 3_000 && !(mp.isPlaying && mp.length > 0)) {
                delay(50)
                waited += 50
            }
            if (mp != null && mp.isPlaying && mp.length > 0) {
                if (isRmvbTailZoneResume(startPositionMs, mp.length)) {
                    // 2026-10-12 切集同口径静态裁决（spec §4.2b）：toast 照旧只报集名
                    Log.w("NvPerf", "rmvb tail-zone switch resume -> from 0 (len=${mp.length}, saved=$startPositionMs)")
                    startPositionMs = 0L
                } else {
                    mp.time = startPositionMs
                    Log.w("NvRmvb", "switch resume seek -> ${startPositionMs}ms (len=${mp.length})")
                    armResumeSeekGuard(startPositionMs)
                }
            } else {
                Log.w("NvRmvb", "switch resume seek skipped: not ready in 3s (target=${startPositionMs}ms)")
            }
            // 2026-10-12 终审 Important 修入：切集会话闩在此闭合——VLC4 换 media 不补发
            // Playing，resumeApplied 停留 false；不闩则切集会话内首次 暂停→播放 重入
            // Playing 续播块：横幅重亮 5s（撒谎）+ 按 startPositionMs 回跳 seek + 守卫重武装
            resumeApplied = true
        }
        toast("$title")
    }

    /**
     * 2026-10-12 假终守卫（spec §4.2c）：实际应用续播 seek 时武装。EndReached 三段式判别
     * 只在武装期间评估——从头起播的真实 EndReached 不受影响。
     */
    private fun armResumeSeekGuard(targetMs: Long) {
        resumeSeekArmed = true
        resumeSeekAtMs = android.os.SystemClock.elapsedRealtime()
        resumeSeekTargetMs = targetMs
        resumeSeekMaxTimeMs = targetMs
        resumeAdvancedBeyondTarget = false
    }

    /** 复位唯一收敛点（spec §4.2c）：openAndPlay（四链全过）+ applyQueueSwitch 入口提前清 +
     * 守卫命中自清 + 手动 seek 解除，此外不得有第五处写 resumeSeekArmed = false。 */
    private fun resetResumeSeekGuard() {
        resumeSeekArmed = false
        resumeSeekAtMs = 0L
        resumeSeekTargetMs = 0L
        resumeSeekMaxTimeMs = 0L
        resumeAdvancedBeyondTarget = false
    }

    /**
     * 切集续播裁决：2026-09-28 审计 A 收敛——删本地私有实现，统一走 EpisodeMediaKit 共用件
     * （「续播」开 + 历史进度 ≥10s + 距片尾 >10s 才续），keyPath 口径（novax.second /
     * 本地绝对路径）与 attachHistory 一致。
     */
    private suspend fun resumePositionForEntry(playUrl: String): Long {
        val resumeOn = runCatching { playerPrefsStore.data.first()[KEY_RESUME_ON_OPEN] }
            .getOrNull() ?: true
        val novax = if (playUrl.startsWith(NvUri.SCHEME + "://")) NvUri.parse(playUrl) else null
        val keyPath = novax?.second ?: playUrl
        val durationMs = episodeResumeDurationMs(applicationContext, novax?.first, keyPath)
        val result = episodeResumePositionMs(applicationContext, novax?.first, keyPath, resumeOn, durationMs)
        Log.d("NvRmvb", "switch resume: key=$keyPath len=$durationMs -> $result")
        return result
    }

    /**
     * 错误重试（2026-09-25）：对当前目标重走 setup 管线，从出错前的位置续播——对齐主播放器
     * 「重试」语义。此前错误覆盖层只有「返回」，代理瞬断/SMB 抖动只能退出重进。
     * 2026-10-13（spec §2.1 Fix A）：重建管线收口到 stopRebuildAndPlay——先停当前播放再重走，
     * 修「播放中换 media 本栈不补发 Playing → 续播 seek 静默丢失跳回片头」（真机复现
     * 代理 start=1016 从文件头供流）。setupAndPlay 自带 CE rethrow（切集取消不污染错误态）；
     * 连点由 switchJob 串行化收口。
     */
    private fun retryFromError() {
        lastUpstreamError = null   // Fix B：比对凭据不跨重试残留（spec §2.1-1）
        val pos = uiPositionMs
        uiError = null
        startPositionMs = pos.coerceAtLeast(0L)
        attachHistory()
        switchJob?.cancel()
        switchJob = lifecycleScope.launch {
            // 2026-10-11 补闸门（spec §4.2，对齐主播放器 restartPlayback）：重试也是一次
            // 起播——计量网络下重新过闸；取消走确认回调既有 else 分支 finish（零流量）
            if (needsMeteredConfirmTarget()) {
                uiMeteredRemindChecked = false
                uiMeteredPrompt = true
                return@launch
            }
            stopRebuildAndPlay()
        }
    }

    /**
     * 重建起播共用管线（2026-10-13 spec §2.1）：stopHeartbeat + stop + setupAndPlay + 兜底闸。
     *
     * 为什么必须先 stop：本 VLC4 栈「播放中换 media」不补发 Event.Playing（applyQueueSwitch
     * 处实测注释），续播 seek/清圈/心跳/everStarted 全挂在 Playing 分支——播放中直接 setMedia
     * 会从头播且 resumeApplied 闩不闭。先停走「已停重启」路径（Playing 必发）；stop() 补发的
     * EndReached 为 time=0/len=0，被 nearEnd 门忽略，不误触连播；Stopped 事件与显式
     * stopHeartbeat 幂等。
     *
     * 消费点三处（spec §2.1-2）：retryFromError / 计量确认回调 else 分支（首起、假终重播同样
     * 流经）。首起/假终安全性：全新起播 Playing 必发 → resumeApplied 在 Playing 分支先闭 →
     * 兜底闸 seek 块经 !resumeApplied 跳过；残留与 Playing 的同目标双 seek 竞窗，与换集先例
     * （重复 seek 目标一致无副作用）同款；stop() 对空闲/Ended 态为无害 no-op。
     * 刻意不补（spec §2.1-3，防自作主张）：captureVideoTrackInfo（同集宽高不变，补打会触发
     * applyPendingAutoOrientation 意外翻转链）/ coverCaptured（首播 Playing 已置位）/
     * uiResumeBanner（横幅语义留给 Playing 分支，与换集路径同口径）。
     */
    private suspend fun stopRebuildAndPlay() {
        stopHeartbeat()
        runCatching { vlcPlayer?.stop() }
        setupAndPlay()
        // 兜底闸：仅 setup 成功且 Playing 未发时补齐 Playing 分支副作用（spec §2.1-3）。
        // Playing 已发时 resumeApplied 已闭、各状态已真，整段幂等空转。
        if (uiError != null) return
        if (startPositionMs > 0 && !resumeApplied) {
            val mp = vlcPlayer
            var waited = 0
            while (mp != null && waited < 3_000 && !(mp.isPlaying && mp.length > 0)) {
                if (uiError != null) return   // 轮询中报错：弃权落位且不闭闩——闩开着让迟到 Playing 补 seek
                delay(50)
                waited += 50
            }
            if (mp != null && mp.isPlaying && mp.length > 0 && uiError == null) {
                if (isRmvbTailZoneResume(startPositionMs, mp.length)) {
                    Log.w("NvPerf", "rmvb tail-zone retry resume -> from 0 (len=${mp.length}, saved=$startPositionMs)")
                    startPositionMs = 0L
                } else {
                    mp.time = startPositionMs
                    armResumeSeekGuard(startPositionMs)
                    Log.w("NvRmvb", "retry belt: seek -> ${startPositionMs}ms (len=${mp.length})")
                }
            }
            // 正常路径（落位/超时未就绪）闭闩（同形 applyQueueSwitch）：防之后 暂停→播放
            // 重入 Playing 续播块按过期 startPositionMs 回跳
            resumeApplied = true
        }
        if (vlcPlayer?.isPlaying == true && uiError == null) {
            uiPlaying = true
            everStarted = true
            fakeTailReplayPending = false
            uiBuffering = false
            startHeartbeat()
            startLoopWatch()
            startStallWatch()
        }
    }

    // ---------------- 心跳（进度记忆 + 睡眠定时）与 AB 循环 ----------------

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                val mp = vlcPlayer ?: break
                // 2026-10-18 发布终审 BUG-2：对齐主内核 recordIfPlaying（PlaybackService
                // startHistoryHeartbeat / NvHistoryWriter.recordIfPlaying）口径——此前无
                // isPlaying 闸，onStop 暂停/fail() 出错后 lifecycleScope 仍活着，退后台
                // 期间每 10s 一次 Room 事务（watchedAt 一路刷新）直到销毁
                if (mp.isPlaying) {
                    Log.d("NvPerf", "rmvb heartbeat record pos=${mp.time} len=${mp.length}")
                    historyWriter?.record(mp.time.coerceAtLeast(0L), mp.length.coerceAtLeast(0L))
                }
                // 睡眠定时到点暂停。仅在播才有「暂停」语义；未播（用户已暂停/出错）静默清闩
                // ——不补发 toast，也避免恢复播放后旧定时立刻补火
                if (PlaybackFeatures.sleepFired(features.sleepDeadlineMs, System.currentTimeMillis())) {
                    features.sleepDeadlineMs = -1L
                    features.sleepMinutes = 0
                    if (mp.isPlaying) {
                        mp.pause()
                        toast(getString(R.string.player_sleep_fired_toast))
                    }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun flushHistory() {
        if (!everStarted) return   // 未起播的 flush（起播失败销毁）不落库：0 进度不得覆写续播位（BUG-C-R3-02）
        // §2 写库门:假终窗口内不写库——≈len 是从未播出的位置
        if (fakeTailReplayPending) {
            Log.d("NvPerf", "rmvb flushHistory skipped: fakeTailPending=true")
            return
        }
        val mp = vlcPlayer ?: return
        Log.d("NvPerf", "rmvb flushHistory record pos=${mp.time} len=${mp.length} fakeTailPending=$fakeTailReplayPending")
        historyWriter?.record(mp.time.coerceAtLeast(0L), mp.length.coerceAtLeast(0L))
    }

    /**
     * 卡死确认看门狗（2026-10-13「卡了再出」）：上游读失败不再直接弹层（只记录
     * lastUpstreamError 待用）；真卡判据落在本看门狗——播放中画面位置连续
     * STALL_CONFIRM_MS 未动才 fail()，显示待用原因（无则通用文案）。自愈型抖动
     * （libVLC Range 重连/SMB evict+reconnect 均快于缓冲耗尽）全程无感；弹层后再
     * 自愈由 onUpstreamRecovered 自动收层。暂停（!isPlaying）按合法不动处理，
     * 计时持续顺延，恢复播放不误触发。
     */
    private fun startStallWatch() {
        stallWatchJob?.cancel()
        stallWatchJob = lifecycleScope.launch {
            var lastMoveAt = android.os.SystemClock.elapsedRealtime()
            var lastPos = Long.MIN_VALUE
            while (isActive) {
                delay(1_000)
                val mp = vlcPlayer ?: break
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                val t = runCatching { mp.time }.getOrDefault(Long.MIN_VALUE)
                if (!playing || t != lastPos) {
                    lastPos = t
                    lastMoveAt = android.os.SystemClock.elapsedRealtime()
                    continue
                }
                if (android.os.SystemClock.elapsedRealtime() - lastMoveAt > STALL_CONFIRM_MS && uiError == null) {
                    Log.w("NvPerf", "rmvb stall confirmed: no move for ${STALL_CONFIRM_MS}ms")
                    fail(lastUpstreamError ?: getString(R.string.player_err_stalled))
                }
            }
        }
    }

    private fun stopStallWatch() {
        stallWatchJob?.cancel()
        stallWatchJob = null
    }

    private fun startLoopWatch() {
        loopJob?.cancel()
        loopJob = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val mp = vlcPlayer ?: continue
                val loopBack = PlaybackFeatures.loopSeekBackMs(mp.time, features.loopAMs, features.loopBMs)
                if (loopBack != null) {
                    mp.time = loopBack
                }
            }
        }
    }

    // ---------------- 音频焦点与拔耳机（2026-10-18 发布终审 BUG-1） ----------------

    /** 起播前请求媒体焦点（幂等；GAIN 授予后同步注册拔耳机 receiver——系统广播免导出声明） */
    private fun requestAudioFocusForPlayback() {
        if (audioFocusRequest != null) return
        val am = audioManager
            ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
            ?: return
        val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(
                audioFocusListener,
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            .build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            runCatching {
                registerReceiver(
                    becomingNoisyReceiver,
                    android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                )
            }
        } else {
            // 授予失败（罕见，如来电中）：放行播放但记日志——与用户主动点击播放的意图一致
            Log.w("NvRmvb", "audio focus request failed (proceeding)")
        }
    }

    /** 释放焦点与 receiver（onDestroy；暂停态保持持有，与主内核 ExoPlayer 行为一致） */
    private fun abandonAudioFocusForPlayback() {
        audioFocusRequest?.let { req -> runCatching { audioManager?.abandonAudioFocusRequest(req) } }
        audioFocusRequest = null
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
    }

    /**
     * 2026-09-21 审计 P2：类注释声明「不支持后台播放」，但此前 onStop 无任何处理——
     * 锁屏/切走后 libVLC 软解 + 代理 + SMB 流持续满负荷（RMVB 全软解是全项目最重 CPU
     * 路径），且无前台服务保护。现与主播放器「后台播放关闭」语义对齐：退后台即暂停，
     * 用户回来手动继续（进度已由 Paused 事件的 flushHistory 落库）。
     */
    override fun onStop() {
        super.onStop()
        // E1-3（发版审查）：缓冲窗口期 uiPlaying 仍为 false，原 if (uiPlaying) 挡不住
        // 起播等待期的软解+代理+拉流满负荷（熄屏烧 CPU/流量）。对齐 PlayerActivity：
        // 非错误态一律暂停。
        if (uiError == null) {
            runCatching { vlcPlayer?.pause() }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            // 2026-10-14 方向残留修复(spec §2.1): 同主播放器,退出撤销方向强制
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Log.d("NvPerf", "rmvb exit orientation reset -> UNSPECIFIED")
        }
        // 发布终审 BUG-1：焦点与拔耳机 receiver 收口
        abandonAudioFocusForPlayback()
        rotationLockObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        rotationLockObserver = null
        meteredPauseJob?.cancel()
        meteredNetCallback?.let { cb ->
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.let { cm -> runCatching { cm.unregisterNetworkCallback(cb) } }
        }
        meteredNetCallback = null
        flushHistory()
        stopHeartbeat()
        loopJob?.cancel()
        stallWatchJob?.cancel()
        controlsHideJob?.cancel()
        runCatching { vlcPlayer?.detachViews() }
        attachedTextureView = null
        proxy?.stop()
        vlcEngine.release()   // 顺序：player → libVLC → content fd（ detachment 已先行）
        proxy = null
        super.onDestroy()
    }

    // ---------------- UI ----------------

    /** Fix B（2026-10-13 spec §2.2）+「卡了再出」改版：最近一次代理上游故障原因——
     * 弹层前的待用凭据（读失败只记录不弹层，卡死看门狗命中时取作浮层文案），
     * recovered 清位 / retryFromError 入口清位防跨抖动残留；写点全部主线程，
     * 代理迟到上报被 proxy===p 闸挡下。 */
    @Volatile
    private var lastUpstreamError: String? = null

    private var surfaceLayout: VLCVideoLayout? = null

    @Composable
    private fun RmvbScreen() {
        val context = LocalContext.current
        // N2（2026-10-09 用户需求）：长按倍速三处触觉反馈与主播放器同口径
        //（PlayerScreen.kt:1090/1101/1118 LongPress）——进/出/下滑锁定各震一下
        val haptics = LocalHapticFeedback.current
        // 2026-09-18：快进/快退步长随设置（novax_double_tap_seek_seconds，双击手势与底栏按钮共用）
        LaunchedEffect(Unit) {
            runCatching { context.playerPrefsStore.data.first() }.onSuccess { applyPrefs(it) }
        }
        // 2026-09-20 画面旋转：屏幕尺寸（px，onSizeChanged 跟随折叠/显示变更刷新）
        var screenWpx by remember { mutableIntStateOf(context.resources.displayMetrics.widthPixels) }
        var screenHpx by remember { mutableIntStateOf(context.resources.displayMetrics.heightPixels) }
        // 旋转 90/270：先在「宽高互换的虚拟画布」内布局 surface，再整体 View 级旋转——
        // 旋转后的足迹恰好落回屏幕内
        val density = LocalDensity.current
        val rotated = uiVideoRotation == 90 || uiVideoRotation == 270
        val canvasWpx = if (rotated) screenHpx else screenWpx
        val canvasHpx = if (rotated) screenWpx else screenHpx
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 手势挂外层 Box（非旋转画布）：坐标系恒为屏幕空间，旋转后单指
                // seek/亮度/音量的方向语义不变；控制层按钮为后续组合子节点，消费优先
                .onSizeChanged {
                    screenWpx = it.width
                    screenHpx = it.height
                }
                .pointerInput(uiLocked) {
                    if (uiLocked) return@pointerInput
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        // 新手势开始清缩放压制门：上一缩放手势邻近的 tap 已被门挡掉，
                        // 本手势若再进缩放会重新置位（跨手势的 tap 误触由此隔绝）
                        uiZoomGestureActive = false
                        var mode: Byte = MODE_NONE
                        var seekDeltaMs = 0L
                        var lastVolume = -1
                        // 2026-09-20 亮度基准（进入 MODE_BRIGHTNESS 首事件归一化：-1=跟随系统 → 读系统亮度）
                        var startBrightness = Float.NaN
                        // BUG-C-R4-01：基准点只在 down 时记一次——原实现在 MODE_NONE 期间
                        // 逐事件重锚 downX/downY，dx/dy 恒 0，「abs(dx)>24f」永假，
                        // 进度/亮度/音量滑动手势全部不可达（与主播放器 down 一次性锚点同款）
                        val downX = first.position.x   // 模式判定与 seek 基准（亮度用 gestureStartY，见下）
                        // 2026-09-19 下滑锁定倍速：以按下点为位移基准；本次手势一次性触发。
                        // BUG-C-R4-06：仅认「本次手势内发起的长按」——已锁定后的普通下滑不得解锁
                        val gestureStartY = first.position.y
                        val epochAtDown = speedShiftEpoch
                        val speedLockPx = 80.dp.toPx()
                        var speedLockFired = false
                        // 双指缩放起点（二指接管瞬间快照；距离比/中点位移都以此为基准）
                        var zoomStart = uiZoom
                        var zoomStartDist = 0f
                        var zoomStartMidX = 0f
                        var zoomStartMidY = 0f
                        do {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: break
                            val dx = change.position.x - downX
                            val dy = change.position.y - gestureStartY   // 纵向累计位移（2026-09-20 起亮度/音量共用此基准）
                            // ---- 二指接管（主播放器 SlideEnd.Takeover 同语义）：任意单指态下
                            // 第二指落下即转缩放——首指先漂移过 24px 也必须能进；长按倍速
                            // 生效期间互斥（倍速优先）。从 SEEK 接管时收起预览气泡且不提交 seek。
                            if (mode != MODE_ZOOM && !features.speedShiftActive && !speedLockFired &&
                                ev.changes.count { it.pressed } >= 2
                            ) {
                                mode = MODE_ZOOM
                                uiZoomGestureActive = true
                                uiSeekPreviewMs = Long.MIN_VALUE
                                zoomStart = uiZoom
                                val pts = ev.changes.filter { it.pressed }
                                zoomStartDist = hypot(
                                    pts[0].position.x - pts[1].position.x,
                                    pts[0].position.y - pts[1].position.y,
                                )
                                zoomStartMidX = (pts[0].position.x + pts[1].position.x) / 2f
                                zoomStartMidY = (pts[0].position.y + pts[1].position.y) / 2f
                            }
                            // ---- 单指模式判定（BUG-C-R4-01 一次性锚点）：MODE_NONE 期间位移超
                            // 24px 按主轴分向——横滑 seek、左半屏亮度、右半屏音量（二指接管后
                            // mode 已非 NONE，本块自然跳过）
                            if (mode == MODE_NONE && !features.speedShiftActive && !speedLockFired &&
                                (abs(dx) > 24f || abs(dy) > 24f)
                            ) {
                                mode = if (abs(dx) > abs(dy)) MODE_SEEK else
                                    (if (downX < size.width / 2f) MODE_BRIGHTNESS else MODE_VOLUME)
                            }
                            when (mode) {
                                MODE_NONE -> if (features.speedShiftActive && speedShiftEpoch != epochAtDown &&
                                    change.pressed && !speedLockFired &&
                                    change.position.y - gestureStartY > speedLockPx
                                ) {
                                    // 长按倍速生效期间滑动互斥（顺带修掉此前移动会误调亮度/音量的老问题），
                                    // 仅识别「本次长按发起后」累计下滑 >80dp = 锁定/解锁倍速切换
                                    speedLockFired = true
                                    change.consume()
                                    toggleSpeedShiftLock(haptics)
                                }
                                MODE_SEEK -> {
                                    change.consume()
                                    // 2026-09-27 用户需求：与主播放器同款等比步长；2026-10-08 百分比口径：
                                    // 整屏=设置百分比（novax_swipe_full_percent），时长未知回退 60s，
                                    // 替换原「半屏=90s」硬编码
                                    seekDeltaMs = swipeSeekDeltaMs(
                                        dx / size.width.toFloat(),
                                        uiDurationMs,
                                        uiSwipeFullPercent,
                                    )
                                    // 预览上限夹取与主播放器口径对齐（原只夹下限 0，可预览到时长外）
                                    val len = vlcPlayer?.length?.coerceAtLeast(0L) ?: 0L
                                    uiSeekPreviewMs = ((vlcPlayer?.time ?: 0L) + seekDeltaMs)
                                        .let { if (len > 0) it.coerceIn(0L, len) else it.coerceAtLeast(0L) }
                                }
                                MODE_BRIGHTNESS -> {
                                    change.consume()
                                    // 2026-09-20 修复「下滑变暗、上滑不变亮」：旧实现基准是
                                    // window.attributes.screenBrightness（初始 -1=跟随系统，首个
                                    // 下滑事件直接钳到 0.01 骤暗），且逐事件增量仅 ~0.0003 上滑不可
                                    // 感知。现与主播放器同款：-1 归一化为系统亮度，以手势起点累计
                                    // 位移映射整屏全程（负系数：下滑变暗、上滑变亮）。
                                    if (startBrightness.isNaN()) {
                                        startBrightness = try {
                                            android.provider.Settings.System.getInt(
                                                context.contentResolver,
                                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                            ) / 255f
                                        } catch (e: Exception) {
                                            0.5f
                                        }.coerceIn(0.01f, 1f)
                                    }
                                    val frac = (startBrightness -
                                        (change.position.y - gestureStartY) / size.height).coerceIn(0.01f, 1f)
                                    val attr = window.attributes
                                    attr.screenBrightness = frac
                                    window.attributes = attr
                                    uiBrightness = frac
                                    uiShowBrightness = true
                                }
                                MODE_VOLUME -> {
                                    change.consume()
                                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    if (lastVolume < 0) lastVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    // 2026-09-21 用户真机反馈「划很多只调一点」：旧映射 60px/格
                                    //（15 格需 900px，且 toInt() 截断让不足 60px 的整段滑动完全
                                    // 无效）。改为主播放器同款「全屏高度=全程 0→max」连续映射
                                    //（round 逐像素跟手），上滑加号下滑减号语义不变。
                                    val next = (lastVolume - dy / size.height.toFloat() * max + 0.5f)
                                        .toInt()
                                        .coerceIn(0, max)
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                    // 2026-09-20：音量 HUD（此前无任何可视指示，用户以为调节不了）
                                    uiVolume = if (max > 0) next.toFloat() / max else 0f
                                    uiShowVolume = true
                                }
                                MODE_ZOOM -> {
                                    // 消费全部变更：压制并行的 detectTapGestures（tap/双击/长按，
                                    // 另有 uiZoomGestureActive 门兜底——tap 判定发生在抬起事件，
                                    // 此时本手势的 consume 已不可考，门在下次 down 才清零）
                                    ev.changes.forEach { it.consume() }
                                    val pts = ev.changes.filter { it.pressed }
                                    if (pts.size >= 2 && zoomStartDist > 1f) {
                                        val dist = hypot(
                                            pts[0].position.x - pts[1].position.x,
                                            pts[0].position.y - pts[1].position.y,
                                        )
                                        val scale = PlayerZoomPolicy.scaleFromPinch(zoomStart.scale, zoomStartDist, dist)
                                        // 平移：屏幕空间的中点位移 → 画布空间（旋转后 child 的
                                        // translation 轴随 graphicsLayer 一起旋转，须做轴映射：
                                        // 90° 时画布 X = 屏幕 -Y … 180° 时两轴全反）
                                        val midX = (pts[0].position.x + pts[1].position.x) / 2f
                                        val midY = (pts[0].position.y + pts[1].position.y) / 2f
                                        val sdx = midX - zoomStartMidX
                                        val sdy = midY - zoomStartMidY
                                        val (cdx, cdy) = when (uiVideoRotation) {
                                            90 -> sdy to -sdx
                                            270 -> -sdy to sdx
                                            180 -> -sdx to -sdy
                                            else -> sdx to sdy
                                        }
                                        // 夹紧在画布坐标系内完成（clampZoom：内容足迹也按画布口径）
                                        setZoom(zoomStart.copy(scale = scale, panX = zoomStart.panX + cdx, panY = zoomStart.panY + cdy))
                                    }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (mode == MODE_SEEK) {
                            val mp = vlcPlayer
                            if (mp != null) {
                                lastManualSeekAt = android.os.SystemClock.elapsedRealtime()
                                resumeSeekArmed = false
                                mp.time = uiSeekPreviewMs.coerceAtMost(mp.length.coerceAtLeast(0L))
                            }
                        }
                        uiSeekPreviewMs = Long.MIN_VALUE
                        // 手势结束 600ms 后收起 HUD；连续手势以 epoch 护航（新手势不被旧协程收起）
                        val hudEpoch = ++uiHudEpoch
                        lifecycleScope.launch {
                            delay(600)
                            if (uiHudEpoch == hudEpoch) {
                                uiShowBrightness = false
                                uiShowVolume = false
                            }
                        }
                    }
                }
                .pointerInput(uiLocked) {
                    if (uiLocked) {
                        // 完全锁定：吞掉触摸（解锁 = 双击左下角锁图标）
                        detectTapGestures(onTap = { })
                    } else {
                        detectTapGestures(
                            // 2026-09-19 长按倍速（与主播放器完全一致）：按住 350ms 级长按
                            // → 按设置值变速（左手模式开=左半屏 0.5x 慢放），松手恢复；
                            // ≥3x 且自动静音开 → 期间静音
                            onPress = {
                                try {
                                    awaitRelease()
                                } finally {
                                    // 2026-09-19：下滑锁定期间松手不恢复（锁定语义本体）
                                    if (features.speedShiftActive && !features.speedShiftLocked) {
                                        features.speedShiftActive = false
                                        rampRate(uiSpeed)   // 恢复用户档位 uiSpeed（冲刷管线立即生效；非固定 1x，与主播放器同语义）
                                        logSpeedBoostEnd()
                                        if (features.speedBoostMuted) {
                                            vlcPlayer?.setVolume(100)
                                            features.speedBoostMuted = false
                                        }
                                        // N2：松手恢复原速震动（与主播放器 PlayerScreen.kt:1101 同口径；
                                        // 锁定态松手走不到这里，不震）
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            },
                            onLongPress = { pos ->
                                if (uiZoomGestureActive) return@detectTapGestures
                                val mp = vlcPlayer ?: return@detectTapGestures
                                // N3（2026-09-22 审核发现）：对齐主播放器语义——
                                // 「长按快进总开关」只管倍速；左手慢放开时左半屏长按=0.5x
                                // 不受总开关影响（PlayerPrefs.kt:54 注释即此意，主播放器
                                // PlayerScreen.kt:365-370 同款分支）。原一刀切 return 会把
                                // 慢放一起禁掉。
                                val spd = if (uiLeftSlowmo && pos.x < size.width / 2f) {
                                    0.5f
                                } else {
                                    if (!uiLongPressOn) return@detectTapGestures
                                    uiLongPressSpeed
                                }
                                speedShiftEpoch++   // BUG-C-R4-06：标记「本次手势内发起的长按」
                                features.speedShiftActive = true
                                features.speedShiftValue = spd
                                rampRate(spd)   // 升速（冲刷管线立即生效）
                                markSpeedBoostStart(uiSpeed, spd)   // NvPerf 倍速段取证
                                if (spd >= 3f - 0.01f && uiMuteHighSpeed) {
                                    features.speedBoostMuted = true
                                    mp.setVolume(0)
                                }
                                // N2：进入倍速震动（与主播放器 PlayerScreen.kt:1090 同口径；前置
                                // return 均已退出，走到这里即倍速真实生效）
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onTap = {
                                if (uiZoomGestureActive) return@detectTapGestures   // 缩放手势收尾不触发控制层
                                // 2026-10-08 半锁：单击只提示不弹控制层（与主播放器同口径）
                                if (uiHalfLocked) {
                                    uiLockHintVisible = true
                                } else {
                                    uiControlsVisible = !uiControlsVisible
                                    if (uiControlsVisible) keepControls()
                                }
                            },
                            onDoubleTap = { pos ->
                                if (uiZoomGestureActive) return@detectTapGestures
                                // 2026-09-20 三区双击（与主播放器同步，分区 1/4-1/2-1/4）：左 1/4 快退 / 右 1/4 快进 /
                                // 中间 1/2 播放暂停；步长随设置（10/15/25/30s，默认 15）
                                val mp = vlcPlayer ?: return@detectTapGestures
                                val w = size.width.toFloat()
                                val delta = when {
                                    pos.x < w / 4f -> -uiSkipSec * 1000L
                                    pos.x > 3f * w / 4f -> uiSkipSec * 1000L
                                    else -> 0L
                                }
                                if (delta == 0L) {
                                    if (mp.isPlaying) mp.pause() else mp.play()
                                } else {
                                    // length 未就绪时 libVLC 返回 -1：coerceIn(0, -1) 会抛
                                    // IllegalArgumentException（审查 P2），与文件内其余 seek
                                    // 同用 coerceAtLeast(0) 口径；时长未知时步进落在 0（不崩）
                                    lastManualSeekAt = android.os.SystemClock.elapsedRealtime()
                                    resumeSeekArmed = false
                                    mp.time = (mp.time + delta).coerceIn(0L, mp.length.coerceAtLeast(0L))
                                }
                            },
                        )
                    }
                },
        ) {
            // 视频层：旋转 0°=全屏 SurfaceView 直通；90/270=宽高互换虚拟画布 + 整体旋转
            // 2026-09-21 根治「点画面旋转不转」：旋转改走 **View 级 rotation**（VLCVideoLayout
            // 自身，pivot 中心）。原实现在 modifier 链上挂 compose graphicsLayer(rotationZ)——
            // 模拟器实测（2026-09-21）：90/270 时 compose 对 AndroidView 的层变换展平错位，
            // raw 布局塌成 1080×1080@x=1710、画面不转；180（不换画布）反而正常。主播放器同款
            // 手法 90° 也黑屏，属同一坑。View 级 rotation 是纯 Android 属性，TextureView 路径
            // 稳定跟随；raw 布局=宽高互换画布居中（630,-630），旋转后足迹恰回全屏。
            // 2026-09-22 修复「竖屏+画面旋转 90/270 画面错位到屏幕底部被裁」：pivot 在
            // update（组合期）读 v.width/height，布局变化（方向切换/画布互换）发生在其后
            // 一帧，pivot 停留旧值 → 旋转绕错点 → 画面跑到屏幕外。改为挂 OnLayoutChangeListener，
            // 每次布局变化后都按当前尺寸重设 pivot 并重放 rotation（update 保留作状态直达路径）。
            // Liquid Glass（decisions.md §4.5 Task 10 修订）：source 挂「视频」包裹容器——
            // 视频层 AndroidView 包进全屏 Box 挂 nvGlassSource，控制条/弹窗面板/浮层胶囊
            // 全部做 source 兄弟，弹窗玻璃由此生效。黑底写在 nvGlassSource 之后（§4.5a：
            // 录制须含背景；与根 Box 同为纯黑，视觉零变化）。VLCVideoLayout 的 View 级旋转/
            // requiredSize 虚拟画布/clipChildren 协议零触碰（align/requiredSize 移入包裹 Box
            // 后语义不变：同为 BoxScope 居中锚定）。
            Box(Modifier.fillMaxSize().nvGlassSource(com.novax.app.ui.glass.LocalNvGlassEnv.current).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        VLCVideoLayout(ctx).also {
                            surfaceLayout = it
                            // 双指缩放把 surface child 放大到布局外：不关裁剪，放大画面会被裁掉
                            //（SurfaceView 走硬件合成不受影响，TextureView 走视图树必须关，主播放器同款）
                            it.clipChildren = false
                            it.addOnLayoutChangeListener { vv, _, _, _, _, _, _, _, _ ->
                                vv.pivotX = vv.width / 2f
                                vv.pivotY = vv.height / 2f
                                vv.rotation = uiVideoRotation.toFloat()
                            }
                        }
                    },
                    update = { v ->
                        v.pivotX = v.width / 2f
                        v.pivotY = v.height / 2f
                        v.rotation = uiVideoRotation.toFloat()
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        // requiredSize 而非 size：90/270 的竖向虚拟画布(1080×2340)比父 Box(高 1080)
                        // 大，.size() 会被 incoming constraints 钳成 1080×1080（模拟器 dumpsys 实证），
                        // 旋转足迹永远回不到全屏——requiredSize 无视 incoming 约束、超界内容居中溢出
                        .requiredSize(
                            with(density) { canvasWpx.toDp() },
                            with(density) { canvasHpx.toDp() },
                        ),
                )
            }

            uiError?.let { err ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(40.dp), tint = NvColors.TextSecondary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            err,
                            style = NvTypography.BodyMedium,
                            color = NvColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        // 2026-09-25 新增：重试（对齐主播放器）——从出错前位置重走管线
                        NvPrimaryButton(
                            text = stringResource(R.string.player_retry),
                            onClick = { retryFromError() },
                            modifier = Modifier.height(44.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        // Liquid Glass（spec §10 浮层清单 8/12）：错误浮层返回胶囊
                        // SurfaceElevated 实底→NvGlassChip（原 clickable{finish()} 迁入 onClick）
                        NvGlassChip(
                            onClick = { finish() },
                            shape = NvShape.Dialog,
                        ) {
                            Text(
                                stringResource(R.string.player_back),
                                style = NvTypography.BodyMedium,
                                color = NvColors.Primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                return@Box
            }

            if (uiBuffering && !uiLocked) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NvColors.Primary)
                }
            }

            // 长按倍速提示（与主播放器一致）：画面正上方小胶囊，随长按出现/松手消失
            // 2026-11-01 黑闪修复同主播放器：缩放出场动画会连累 SurfaceView 事务被打成
            // 退化值（逐帧缩放归零）→ 画面黑闪 1~2 帧，改纯淡入淡出（取证细节见 PlayerScreen）
            AnimatedVisibility(
                visible = features.speedShiftActive && !uiLocked && uiError == null,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp),
            ) {
                // Liquid Glass（spec §10 浮层清单 9/12）：长按倍速胶囊 半透明黑浮层→NvGlassChip。
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
                            if (features.speedShiftValue >= 1f) Icons.Outlined.FastForward else Icons.Outlined.FastRewind,
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
                        // 2026-09-19：下滑锁定后胶囊常驻（uiSpeedShiftActive 保持 true），锁定态加锁图标
                        if (features.speedShiftLocked) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Lock, stringResource(R.string.player_cd_speed_locked), Modifier.size(12.dp), tint = Color.White)
                        }
                    }
                }
            }

            // 续播横幅（5s 自动消失；已自动跳过，不做二次跳转）。
            // 2026-09-20 动效对齐主播放器：顶部横幅从上缘滑入，不再纯淡入
            AnimatedVisibility(
                visible = uiResumeBanner,
                enter = fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) +
                    slideInVertically(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) { -it },
                exit = fadeOut(tween(NvMotion.DurationNormal)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                // Liquid Glass（spec §10 浮层清单 10/12）：续播横幅 SurfaceElevated 0.92 实底→
                // NvGlassChip（纯 Text、无容器 clickable → chip 不带 onClick，语义零变化）
                NvGlassChip(
                    modifier = Modifier.padding(top = 64.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        stringResource(R.string.player_resumed_toast),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            // 控制层（2026-09-20 动效与主播放器对齐）：顶行从屏幕上缘滑入/滑出、底栏从下缘滑入/滑出，
            // 时长/缓动统一引用 NvMotion（与 PlayerScreen 控制层同源）；两块 visible 同表达式保证同相位
            AnimatedVisibility(
                visible = uiControlsVisible && uiError == null,
                enter = fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) +
                    slideInVertically(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) { -it },
                exit = fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) +
                    slideOutVertically(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ControlsTop()
            }

            AnimatedVisibility(
                visible = uiControlsVisible && uiError == null,
                enter = fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) +
                    slideInVertically(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate)) { it },
                exit = fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) +
                    slideOutVertically(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ControlsBottom()
            }

            // 手势 HUD（2026-09-20 与主播放器同步）：亮度左缘 / 音量右缘竖条（CenterStart/
            // CenterEnd + 64dp 内移，主播放器同款）——此前错误地整体居中显示（用户反馈「图标跑中间」）
            if (uiShowBrightness) {
                NvVerticalLevelBar(
                    icon = { Icon(Icons.Filled.WbSunny, null, Modifier.size(20.dp), tint = Color.White) },
                    value = uiBrightness,
                    label = if (uiBrightness <= 0f) stringResource(R.string.player_brightness_off) else null,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 64.dp),
                )
            }
            if (uiShowVolume) {
                NvVerticalLevelBar(
                    icon = { Icon(Icons.Filled.VolumeUp, null, Modifier.size(20.dp), tint = Color.White) },
                    value = uiVolume,
                    label = null,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 64.dp),
                )
            }

            // 双指缩放态：还原画面按钮（B 站同款；控制条显隐间常驻，位于底栏上方）
            if (PlayerZoomPolicy.isZoomed(uiZoom) && !uiLocked && uiError == null) {
                // Liquid Glass（spec §10 浮层清单 11/12，评审整改补列）：缩放还原钮
                // 半透明黑浮层→NvGlassChip（原 clickable{resetZoom()} 迁入 onClick）
                NvGlassChip(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 132.dp),
                    shape = RoundedCornerShape(14.dp),
                    onClick = { resetZoom() },
                ) {
                    Text(
                        stringResource(R.string.player_reset_picture),
                        style = NvTypography.LabelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // 锁定层（2026-10-08 半锁扩展）：左下角锁图标（与锁定按钮同位），全锁/半锁两级都显示
            //（实心开锁=半锁），双击一步解锁并按方向模式恢复朝向。组合序必须在面板块之前：
            // 半锁下面板开着时锁角标不得浮在弹窗遮罩之上（主播放器锁层本就早于面板块，同序）。
            // 2026-10-08 e2e 发现：控制层可见（半锁过渡窗口）时角标与底栏锁按钮同位重叠、吞点击，
            // 「再点锁按钮进全锁」永远不可达——控制层可见期间隐藏角标，收起后才出现供双击解锁；
            // 全锁不受影响（uiControlsVisible 恒 false，角标恒显）。
            // 2026-10-09 用户需求（与主播放器同构）：长按角标=半锁直接升全锁；已全锁时长按幂等静默；
            // 双击解锁语义逐字保留（600ms 自实现窗口不动）
            if ((uiLocked || uiHalfLocked) && !uiControlsVisible) {
                Box(Modifier.fillMaxSize()) {
                    // IconButton 无 onLongClick——外壳换 combinedClickable；minimumInteractiveComponentSize
                    // 保 48dp 触控（IconButton 原生行为等价，不回退 a06a5d8 的 B6 成果），视觉零变化
                    val haptics = LocalHapticFeedback.current
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
                                    uiLastLockTapAt = 0L   // 清布防：防「点按布防→长按全锁→窗口内再单击」stale 解锁
                                    if (!uiLocked) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        uiLocked = true
                                        uiHalfLocked = false
                                        // 2026-10-17 评审第3轮：角标长按升级全锁同样钉朝向（onLock 全锁分支同款）——
                                        // 保证「uiLocked ⇒ requestedOrientation 已钉住」不变量，防未锁进页的
                                        // FULL_SENSOR 残留到钉死期（系统切锁定后收敛只改状态不施加，靠此钉兜住）
                                        uiPinnedOrientation =
                                            if (resources.configuration.orientation ==
                                                android.content.res.Configuration.ORIENTATION_PORTRAIT
                                            ) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        requestedOrientation = uiPinnedOrientation
                                        toast(getString(R.string.player_full_lock_toast))
                                    }
                                },
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    if (now - uiLastLockTapAt < 600) {
                                        uiLocked = false
                                        uiHalfLocked = false
                                        applyOrientationByMode()
                                        // 解锁态与单击唤出同款计时（4s 必收）：半锁收起后旧收起任务已耗尽，
                                        // 仅置可见不重武装会让控制层播放中常驻
                                        keepControls()
                                        uiLastLockTapAt = 0
                                    } else {
                                        // 单击只布防（600ms 窗内再点才解锁），提示两级手势防「按了没反应」感知
                                        uiLastLockTapAt = now
                                        toast(getString(R.string.player_lock_hint_toast))
                                    }
                                },
                            )
                            .alpha(0.6f),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (uiLocked) Icons.Filled.Lock else Icons.Filled.LockOpen, stringResource(R.string.player_cd_lock_badge), tint = Color.White) }
                }
                AnimatedVisibility(visible = uiLockHintVisible, enter = fadeIn(), exit = fadeOut(tween(500))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LaunchedEffect(uiLocked) { delay(1000); uiLockHintVisible = false }
                        Text(
                            if (uiLocked) stringResource(R.string.player_lock_hint) else stringResource(R.string.player_half_lock_hint),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                        )
                    }
                }
            }

            if (uiShowSubSheet) {
                NvPlayerPopup(title = stringResource(R.string.player_sheet_subtitles), onDismiss = { uiShowSubSheet = false }) {
                    val mp = vlcPlayer
                    // VLC4 轨道体系：getTracks(Type) + selectTrack(id)，选中态读 Track.selected
                    val tracks = mp?.getTracks(IMedia.Track.Type.Text)?.toList() ?: emptyList()
                    if (tracks.isEmpty()) {
                        Text(
                            stringResource(R.string.player_rmvb_no_subtitles),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    tracks.forEach { td ->
                        NvSheetListItem(td.name ?: stringResource(R.string.player_sub_track_num, td.id), selected = td.selected) {
                            mp?.selectTrack(td.id)
                            uiShowSubSheet = false
                        }
                    }
                }
            }

            if (uiShowAudioSheet) {
                NvPlayerPopup(title = stringResource(R.string.player_sheet_audio), onDismiss = { uiShowAudioSheet = false }) {
                    // VLC4 轨道体系：getTracks(Type) + selectTrack(id)
                    val tracks = vlcPlayer?.getTracks(IMedia.Track.Type.Audio)?.toList() ?: emptyList()
                    if (tracks.isEmpty()) {
                        Text(
                            stringResource(R.string.player_rmvb_no_audio),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    tracks.forEach { td ->
                        NvSheetListItem(td.name ?: stringResource(R.string.player_audio_track_num, td.id), selected = td.selected) {
                            vlcPlayer?.selectTrack(td.id)
                            uiShowAudioSheet = false
                        }
                    }
                }
            }

            if (uiShowInfoSheet) {
                NvPlayerPopup(title = stringResource(R.string.player_info_title), onDismiss = { uiShowInfoSheet = false }) {
                    val mp = vlcPlayer
                    // 返工⑤（2026-10-15 用户第四项反馈，与主播放器 VideoInfoSheet 同步）：
                    // 首行「文件」= 完整文件名（含扩展名）——novax 源内路径/本地绝对路径末段，
                    // 均不可得时回退标题（同 videoInfoFileName 口径）
                    val infoFile = (sourcePath ?: localPath)
                        ?.substringBefore('?')?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() } ?: title
                    listOf(
                        stringResource(R.string.player_rmvb_info_file, infoFile),
                        stringResource(R.string.player_rmvb_info_title_line, title),
                        stringResource(
                            R.string.player_rmvb_info_duration,
                            formatMs(if (uiDurationMs > 0) uiDurationMs else mp?.length?.takeIf { it > 0 } ?: 0L),
                        ),
                        stringResource(R.string.player_rmvb_info_aspect, stringResource(aspectLabelRes[uiAspectIndex])),
                        stringResource(R.string.player_rmvb_info_speed, formatSpeedLabel(uiSpeed)),
                        stringResource(R.string.player_rmvb_info_audio_count, mp?.getTracks(IMedia.Track.Type.Audio)?.size ?: 0),
                        if (queue.isNotEmpty()) {
                            stringResource(R.string.player_rmvb_info_queue, queueIndex + 1, queue.size)
                        } else null,
                    ).filterNotNull().forEach { line ->
                        Text(
                            line,
                            style = NvTypography.BodyMedium,
                            color = NvColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (uiShowSpeedSheet) {
                NvPlayerPopup(title = stringResource(R.string.player_sheet_speed), onDismiss = { uiShowSpeedSheet = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { s ->
                        NvSheetListItem(
                            formatSpeedLabel(s),
                            selected = abs(uiSpeed - s) < 0.01f,
                            onClick = {
                                uiSpeed = s
                                // 2026-09-19：显式选速优先——清掉长按倍速生效/下滑锁定态（与主播放器对齐）
                                features.speedShiftActive = false
                                features.speedShiftLocked = false
                                logSpeedBoostEnd()
                                if (features.speedBoostMuted) {
                                    vlcPlayer?.setVolume(100)
                                    features.speedBoostMuted = false
                                }
                                rampRate(s)   // 显式选速（冲刷管线立即生效）
                                // 2026-10-08 final-review fix：≥3x 自动静音（novax_mute_above_2x）——
                                // 原路径只解旧 boost 静音、从不按新速度应用静音，选 3x/滑到 5x 全音量
                                // 狂放（主播放器同操作是静音的）。必须在解旧之后应用，否则 3x→3x
                                // 连切会把刚置的静音又抬回；与长按路径 :1645 同口径
                                if (s >= 3f - 0.01f && uiMuteHighSpeed) {
                                    features.speedBoostMuted = true
                                    vlcPlayer?.setVolume(0)
                                }
                                uiShowSpeedSheet = false
                            },
                        )
                    }
                    // 2026-10-08 用户需求：0.1~5.0 连续细调（0.1 步进吸附，与主播放器 SpeedSheet 同口径）——
                    // 显示与提交同走 snapPlaybackSpeed；松手才 rampRate（拖动中不写引擎，防变速冲刷）
                    NvSheetText(stringResource(R.string.player_speed_fine_tune))
                    var fine by rememberSaveable {
                        mutableFloatStateOf(uiSpeed.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX))
                    }
                    Text(
                        String.format(java.util.Locale.US, "%.1f", snapPlaybackSpeed(fine)) + "x",
                        style = NvTypography.TitleMedium,
                        color = NvColors.Primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    NvSlider(
                        value = fine,
                        onValueChange = { fine = it },
                        onValueChangeFinished = {
                            val s = snapPlaybackSpeed(fine)
                            uiSpeed = s
                            features.speedShiftActive = false
                            features.speedShiftLocked = false
                            logSpeedBoostEnd()
                            if (features.speedBoostMuted) {
                                vlcPlayer?.setVolume(100)
                                features.speedBoostMuted = false
                            }
                            rampRate(s)   // 显式选速（冲刷管线立即生效）
                            // 2026-10-08 final-review fix：≥3x 自动静音（novax_mute_above_2x）——
                            // 滑杆高速档与八档同款补静音（根因同八档路径：只解旧不应用新）；
                            // 解旧在前、应用在后防 3x→3x 连切抬回，与长按路径 :1645 同口径
                            if (s >= 3f - 0.01f && uiMuteHighSpeed) {
                                features.speedBoostMuted = true
                                vlcPlayer?.setVolume(0)
                            }
                            uiShowSpeedSheet = false
                        },
                        valueRange = PLAYBACK_SPEED_MIN..PLAYBACK_SPEED_MAX,
                        steps = 48,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (uiShowMoreSheet) {
                NvPlayerPopup(title = stringResource(R.string.player_sheet_more), onDismiss = { uiShowMoreSheet = false }) {
                    NvSheetText(stringResource(R.string.player_ab_loop_section))
                    NvSheetListItem(stringResource(R.string.player_set_loop_a), selected = false) {
                        features.loopAMs = vlcPlayer?.time ?: 0L
                        toast(getString(R.string.player_loop_a_set, formatMs(features.loopAMs)))
                    }
                    NvSheetListItem(stringResource(R.string.player_set_loop_b), selected = false) {
                        val b = vlcPlayer?.time ?: 0L
                        if (b <= features.loopAMs) {
                            toast(getString(R.string.player_loop_b_after_a))
                        } else {
                            features.loopBMs = b
                            toast(getString(R.string.player_loop_active_toast, formatMs(features.loopAMs), formatMs(b)))
                        }
                    }
                    if (features.loopAMs >= 0 && features.loopBMs > features.loopAMs) {
                        NvSheetListItem(
                            stringResource(
                                R.string.player_clear_loop_active,
                                formatMs(features.loopAMs), formatMs(features.loopBMs),
                            ),
                            selected = true,
                        ) {
                            features.loopAMs = -1L
                            features.loopBMs = -1L
                        }
                    }
                    NvSheetText(stringResource(R.string.player_sleep_section))
                    listOf(
                        0 to R.string.player_sleep_off,
                        15 to R.string.player_sleep_15,
                        30 to R.string.player_sleep_30,
                        60 to R.string.player_sleep_60,
                    ).forEach { (m, labelRes) ->
                        NvSheetListItem(stringResource(labelRes), selected = features.sleepMinutes == m) {
                            features.sleepMinutes = m
                            features.sleepDeadlineMs =
                                if (m <= 0) -1L else System.currentTimeMillis() + m * 60_000L
                            if (m <= 0) toast(getString(R.string.player_sleep_off_toast))
                            else toast(getString(R.string.player_sleep_in_min, m))
                        }
                    }
                }
            }

            BackHandler {
                val sheetOpen = uiShowSubSheet || uiShowAudioSheet || uiShowInfoSheet ||
                    uiShowSpeedSheet || uiShowMoreSheet
                when {
                    // 2026-10-08 弹窗化后返回键先关面板（ModalBottomSheet 既有语义 parity）
                    sheetOpen -> {
                        uiShowSubSheet = false; uiShowAudioSheet = false; uiShowInfoSheet = false
                        uiShowSpeedSheet = false; uiShowMoreSheet = false
                    }
                    uiLocked || uiHalfLocked -> {
                        uiLocked = false
                        uiHalfLocked = false
                        applyOrientationByMode()
                        // 解锁态与单击唤出同款计时（4s 必收），同角标双击解锁口径
                        keepControls()
                    }
                    else -> finish()
                }
            }
        }
    }

    @Composable
    private fun NvSheetText(text: String) {
        Text(
            text,
            style = NvTypography.LabelMedium,
            color = NvColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    // 2026-09-20 动效对齐：原 Controls() 整列只做淡入淡出，现拆成顶/底两个 @Composable，
    // 由调用处分别包 AnimatedVisibility——顶行走上缘、底栏走下缘（与主播放器一致）
    @Composable
    private fun ControlsTop() {
        // N4 顶栏竖屏适配（2026-10-09 用户需求：竖屏"这一撮"要大、放不下就拖动）：
        // 与主播放器 NvPlayerTopBar 同构——返回键固定 + 标题 weight(1f)+widthIn(min=96dp 最小
        // 可读宽)+ellipsis；动作区不 weight、按内容宽包 horizontalScroll（竖屏放不下可拖动，
        // 横屏无滚动零副作用）；图标视觉 26dp、触控 48dp 不变；按钮集合/顺序/回调零变更。
        // 实现注记（与主播放器同）：weight 子项空间不足分 0 时 widthIn(min) 会被 coerce，
        // 故用 BoxWithConstraints 把动作区封顶在「总宽-返回-标题最小宽」，标题分配必得 ≥96dp。
        // 2026-10-14 用户需求（与主播放器 NvPlayerTopBar 同步）：竖屏标题改「少展示」——
        // 窄幅定宽 48dp 封顶（48dp=一枚键位格，动作区恰好多露一枚键），宽幅维持 96dp 仅保底
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 标题宽口径（与动作区封顶配对）：窄幅（竖屏/分屏）48dp 定宽、宽幅 96dp 仅保底
            val narrow = maxWidth < 480.dp
            val titleWidth = if (narrow) 48.dp else 96.dp
            val actionsMaxWidth = maxWidth - 48.dp - titleWidth
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { finish() }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.player_back),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Text(
                    title,
                    style = NvTypography.TitleMedium,
                    color = NvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 标题宽口径与动作区封顶配对（spec N4-1；2026-10-14 竖屏 48dp 定宽让位按键，
                    // weight 分配的多余空间不再回填标题）
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = titleWidth, max = if (narrow) titleWidth else Dp.Unspecified),
                )
                // 动作区：按内容宽 + 横向可拖动 + 可滑动静态暗示（与主播放器同款共用组件
                // NvScrollableActionRow：半截图标/边缘渐隐）
                NvScrollableActionRow(actionsMaxWidth = actionsMaxWidth) {
                    IconButton(onClick = { cycleAspect() }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.AspectRatio,
                            stringResource(R.string.player_cd_aspect_current, stringResource(aspectLabelRes[uiAspectIndex])),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    // 返工⑤（2026-10-15 用户第五项反馈）：与主播放器 NvPlayerTopBar 同步——
                    // 「画面旋转」与「方向切换」位置互换，其余钮顺序/图标/语义/onClick 全部不变
                    // 2026-10-11 画面镜像(主播放器同款):filled/outlined 表达开关态
                    IconButton(onClick = { toggleOrientation() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ScreenRotation, stringResource(R.string.player_cd_orientation), tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = { toggleVideoMirror() }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (uiMirrored) Icons.Filled.Flip else Icons.Outlined.Flip,
                            stringResource(
                                R.string.player_cd_mirror_current,
                                stringResource(if (uiMirrored) R.string.player_toggle_on else R.string.player_toggle_off),
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    // 2026-09-20 画面旋转（主播放器 C12 同款）：内容 0/90/180/270 循环，跨 0° 切 surface 类型
                    IconButton(onClick = { rotateVideo() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Rotate90DegreesCcw, stringResource(R.string.player_cd_rotate_current, uiVideoRotation), tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    // 2026-09-20 顶栏补键（用户反馈与主播放器不同步）：音轨选择 + 视频信息
                    IconButton(onClick = { uiShowAudioSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Audiotrack, stringResource(R.string.player_sheet_audio), tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = { uiShowSubSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ClosedCaption, stringResource(R.string.player_sheet_subtitles), tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = { uiShowInfoSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Info, stringResource(R.string.player_info_title), tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    // 2026-09-18：「更多」从底栏上移顶栏——底栏改中心对称布局（与主播放器一致）
                    IconButton(onClick = { uiShowMoreSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.player_cd_more_rmvb), tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun ControlsBottom() {
        Column(Modifier.fillMaxWidth()) {
            if (uiSeekPreviewMs != Long.MIN_VALUE) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.player_seek_preview, formatMs(uiSeekPreviewMs)),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextPrimary,
                        modifier = Modifier
                            // Liquid Glass（spec §10 浮层清单 12/12，评审整改补列）：
                            // SurfaceElevated 0.9 实底→nvGlassSurface（主播放器拖动气泡同款）
                            .nvGlassSurface(NvColors.GlassFill, NvShape.Input)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
            ) {
                // 2026-09-18 用户反馈「libVLC 进度条和默认的不一样」：复用主播放器三层进度条
                //（样式/时间行/拖动预览气泡/48dp 热区完全同款；libVLC 无缓冲位概念，传 0 不绘缓冲段）
                NvChapterSlider(
                    positionMs = uiPositionMs,
                    bufferedMs = 0L,
                    durationMs = uiDurationMs,
                    chapters = emptyList(),
                    onSeek = { target ->
                        lastManualSeekAt = android.os.SystemClock.elapsedRealtime()
                        resumeSeekArmed = false
                        vlcPlayer?.let { it.time = target.coerceIn(0L, it.length.coerceAtLeast(0L)) }
                    },
                )
                // 2026-09-18：底栏按钮行与主播放器共用同一中心对称组件（锁定 | 上集·快退·暂停·快进·下集 | 倍速）
                NvPlayerControlRow(
                    isPlaying = uiPlaying,
                    speed = uiSpeed,
                    lockMode = if (uiLocked) 2 else if (uiHalfLocked) 1 else 0,
                    skipSec = uiSkipSec,
                    onTogglePlay = {
                        val mp = vlcPlayer ?: return@NvPlayerControlRow
                        if (mp.isPlaying) mp.pause() else mp.play()
                    },
                    onSkipBy = { delta -> skip(delta) },
                    onSpeed = { uiShowSpeedSheet = true },
                    // 2026-10-08 半锁两级：半锁开 = 0→半锁→全锁；半锁关 = 一下直进全锁。
                    // 全锁钉朝向（半锁不钉），解锁=左下角锁图标双击
                    // 2026-10-10 用户需求「按下半锁，进度条立刻马上收起来，别等四秒」：半锁开即收控制层
                    //（升全锁改走左下角角标长按;半锁期手势经 keepControls 复活后仍 4s 必收,语义不变）
                    onLock = {
                        val halfOn = uiHalfLockOn
                        when (nextLockLevel(if (uiHalfLocked) LOCK_HALF else LOCK_NONE, halfOn)) {
                            LOCK_HALF -> {
                                uiHalfLocked = true
                                uiControlsVisible = false
                                // 2026-10-10 精简+口径随「立即收起」更新（升全锁入口=角标长按）
                                toast(getString(R.string.player_half_lock_toast))
                            }
                            else -> {
                                uiPinnedOrientation =
                                    if (resources.configuration.orientation ==
                                        android.content.res.Configuration.ORIENTATION_PORTRAIT
                                    ) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                requestedOrientation = uiPinnedOrientation
                                uiLocked = true
                                uiHalfLocked = false
                                uiControlsVisible = false
                                uiLockHintVisible = true   // BUG-C-R4-08：提示层原实现无人置 true，是死 UI
                                toast(getString(R.string.player_locked_toast))
                            }
                        }
                    },
                    onPrev = if (queueIndex > 0) { { advanceQueue(-1) } } else null,
                    onNext = if (queueIndex < queue.lastIndex) { { advanceQueue(1) } } else null,
                )
            }
        }
    }

    private fun skip(deltaMs: Long) {
        val mp = vlcPlayer ?: return
        lastManualSeekAt = android.os.SystemClock.elapsedRealtime()
        resumeSeekArmed = false
        mp.time = (mp.time + deltaMs).coerceIn(0L, mp.length.coerceAtLeast(0L))
        keepControls()
    }

    // 画面比例循环（libVLC ScaleType；RMVB 老片 4:3 常见）。
    // Task 7 i18n：label 改资源 ID 承载（组合层 stringResource / 非组合层 getString 解析）
    private val aspectLabelRes = listOf(
        R.string.player_rmvb_aspect_fit,
        R.string.player_rmvb_aspect_fit_screen,
        R.string.player_rmvb_aspect_fill,
        R.string.player_aspect_16_9,
        R.string.player_aspect_4_3,
    )
    private val aspectScales = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
        MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_16_9,
        MediaPlayer.ScaleType.SURFACE_4_3,
    )

    private fun cycleAspect() {
        uiAspectIndex = (uiAspectIndex + 1) % aspectScales.size
        resetZoom()   // 比例变了内容足迹随之变，缩放/平移口径失效，先归位
        vlcPlayer?.setVideoScale(aspectScales[uiAspectIndex])
        toast(getString(R.string.player_aspect_toast, getString(aspectLabelRes[uiAspectIndex])))
        keepControls()
    }

    /** 按方向模式应用朝向（0=横屏 1=竖屏 2=自动·重力 FULL_SENSOR） */
    private fun applyOrientationByMode() {
        requestedOrientation = when (uiOrientationMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** 2026-10-17：系统旋转锁定 → 方向模式收敛。钉死期间「只收敛档位状态、不施加」——
     *  不碰 requestedOrientation（钉死朝向不被覆盖，BUG-C-R1-02 口径），解除钉死时解锁
     *  路径的 applyOrientationByMode 按已收敛档位生效，堵陈旧自动档破坏系统锁定（评审第 2 轮）；
     *  无变化时 no-op 不重申、不清旋转。 */
    private fun reconcileOrientationWithSystem() {
        val newMode = NvOrientationPolicy.modeAfterSystemRotationLockChange(
            uiSystemRotationLocked, uiOrientationMode, 2, 1, 0,
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT,
        ) ?: return
        val oldMode = uiOrientationMode
        uiOrientationMode = newMode
        // 2026-10-14(spec §2.3-2) 口径：模式真实变化清画面旋转
        uiVideoRotation = NvOrientationPolicy.rotationAfterModeChange(oldMode, newMode, uiVideoRotation)
        if (!uiLocked) {
            applyOrientationByMode()
            Log.d("NvPerf", "rmvb orientation reconcile systemLocked=$uiSystemRotationLocked mode=$newMode")
        }
    }

    /** 2026-09-19 用户需求：下滑锁定/解锁长按倍速（与主播放器同语义同文案） */
    private fun toggleSpeedShiftLock(haptics: HapticFeedback) {
        if (!features.speedShiftActive) return
        if (!features.speedShiftLocked) {
            features.speedShiftLocked = true
            toast(getString(R.string.player_speed_locked_toast))
        } else {
            features.speedShiftLocked = false
            features.speedShiftActive = false
            rampRate(uiSpeed)   // 解锁恢复用户档位 uiSpeed（冲刷管线立即生效；非固定 1x）
            logSpeedBoostEnd()
            if (features.speedBoostMuted) {
                vlcPlayer?.setVolume(100)
                features.speedBoostMuted = false
            }
            toast(getString(R.string.player_speed_unlocked_toast))
        }
        // N2：锁定/解锁切换震动（与主播放器 PlayerScreen.kt:1118 同口径：两路都震；
        // 顶部 speedShiftActive 守卫等价于主播放器的同款 if 包裹）
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    private fun toggleOrientation() {
        // 2026-10-17：方向模式循环——系统未锁 = 自动→横屏→竖屏→自动；系统锁定 = 横↔竖
        val oldMode = uiOrientationMode
        uiOrientationMode = if (!uiSystemRotationLocked) {
            when (uiOrientationMode) {
                2 -> 0
                0 -> 1
                else -> 2
            }
        } else {
            if (uiOrientationMode == 1) 0 else 1
        }
        // 2026-10-14(spec §2.3-1): 方向模式变化→画面旋转归零(主播放器同口径);
        // applyOrientationByMode 被 onResume(:2769)复用,严禁挂那里(spec Review Focus 1)
        uiVideoRotation = NvOrientationPolicy.rotationAfterModeChange(oldMode, uiOrientationMode, uiVideoRotation)
        Log.d("NvPerf", "rmvb orientation mode=$uiOrientationMode rotation=$uiVideoRotation")
        applyOrientationByMode()
        toast(
            getString(
                R.string.player_orientation_toast,
                getString(
                    when (uiOrientationMode) {
                        1 -> R.string.player_orientation_portrait
                        2 -> R.string.player_orientation_auto
                        else -> R.string.player_orientation_landscape
                    },
                ),
            ),
        )
        keepControls()
    }

    /** 2026-09-19 方向漂移根治兜底：回前台重申朝向（锁定→钉死朝向，未锁→按方向模式） */
    override fun onResume() {
        super.onResume()
        // 2026-10-17：回前台重读系统锁定并收敛（后台期间切换的兜底）。注意与主播放器的
        // 语义差异：未锁状态下每次回前台都会把固定档拉回「自动·重力」——旧 applyPrefs 仅在
        // 偏好开时如此（偏好默认关，多数用户旧态无此拉回），新语义对所有未锁用户生效，
        // 属有意决策（对称语义：系统未锁 = 播放页跟重力）；锁定/已自动时才是 no-op。
        uiSystemRotationLocked = NvOrientationPolicy.isSystemRotationLocked(this)
        reconcileOrientationWithSystem()
        if (uiLocked) {
            requestedOrientation = uiPinnedOrientation
        } else {
            applyOrientationByMode()
        }
        // BUG-C-R4-10：偏好原实现只读一次（LaunchedEffect(Unit)），播放中改设置不生效
        //（T1 实测 0.5x 慢放开关打开后仍无效即此根因）——回前台重读
        lifecycleScope.launch {
            runCatching { playerPrefsStore.data.first() }.onSuccess { applyPrefs(it) }
        }
    }

    /** 播放器偏好应用（LaunchedEffect 初次 + onResume 重读共用，BUG-C-R4-10） */
    private fun applyPrefs(p: androidx.datastore.preferences.core.Preferences) {
        uiSkipSec = p[KEY_DOUBLE_TAP_SEC] ?: DEFAULT_DOUBLE_TAP_SEC
        uiSwipeFullPercent = (p[KEY_SWIPE_FULL_PERCENT] ?: 10).coerceIn(1, 50)
        uiLongPressOn = p[KEY_LONG_PRESS_ON] ?: true
        uiLongPressSpeed = (p[KEY_LONG_PRESS_SPEED] ?: 2f).coerceIn(0.5f, 3f)
        uiLeftSlowmo = p[KEY_LEFT_SLOWMO] ?: false
        uiMuteHighSpeed = p[KEY_MUTE_ABOVE_2X] ?: DEFAULT_MUTE_ABOVE_2X
        uiHalfLockOn = p[KEY_HALF_LOCK_ENABLED] ?: true
    }

    private fun keepControls() {
        uiControlsVisible = true
        // 2026-10-10 S1(spec 2026-10-09 §1.3 重武装路线):半锁期收起计时永不带 uiPlaying 门。
        // 旧实现 controlsHideJob 句柄被本函数与半锁分支共用——半锁 4s 任务会被抢占比成
        // uiPlaying 门计时器,暂停/换集间隙(uiPlaying=false)下控制层永不收、角标不现身、
        // 双击解锁不可达;且收起后 Event.Playing/skip/rotate 等复活路径无任务再武装(永久常驻)。
        // 统一:半锁期重武装一次性无门任务(距最近一次半锁期交互 4s 必收);非半锁期保持
        // uiPlaying 门(暂停常驻=既有设计)。升全锁置 uiHalfLocked=false 后残留任务自然 no-op。
        controlsHideJob?.cancel()
        controlsHideJob = lifecycleScope.launch {
            delay(4_000)
            val hide = if (uiHalfLocked && !uiLocked) {
                true
            } else {
                uiPlaying && uiSeekPreviewMs == Long.MIN_VALUE
            }
            if (hide) uiControlsVisible = false
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatMs(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
    }

    /** S01E02 / 第N集 季集标记（与主播放器同口径的宽松版，供历史行分类） */
    private fun parseSeasonEpisode(text: String): Pair<Int, Int>? {
        Regex("(?i)S(\\d{1,2})E(\\d{1,3})").find(text)?.let {
            val s = it.groupValues[1].toIntOrNull() ?: return null
            val e = it.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        Regex("第\\s*(\\d{1,3})\\s*集").find(text)?.let {
            val e = it.groupValues[1].toIntOrNull() ?: return null
            return 1 to e
        }
        return null
    }

    /** 轻量 Toast 出口（可在任意协程上下文调用，统一切主线程） */
    private fun toast(msg: String) {
        runOnUiThread {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_TITLE = "rmvb.title"

        /** 卡死确认阈值（2026-10-13「卡了再出」）：播放中位置连续不动超过此时长才弹层；
         * 须大于自愈型抖动的典型重连耗时容忍与正常 seek 缓冲，小于用户可忍受的僵死 */
        private const val STALL_CONFIRM_MS = 10_000L

        /** 同值 seek 若被内核去重（探针实测无 flush 效果时改 30 强制真实 seek） */
        private const val RATE_FLUSH_SEEK_NUDGE_MS = 0L

        /** 冲刷总开关（对照实验用）：false=setRate 直通不冲刷 */
        private const val RATE_FLUSH_ENABLED = false

        /** 冲刷 seek 是否吸附关键帧（fast）：true=重灌最快、落点回跳；false=精确、重灌稍慢 */
        private const val RATE_FLUSH_SEEK_FAST = false

        /**
         * 变速时重选音轨冲刷音频管线（flushAudioPipeline）总开关。
         * --aout=audiotrack 落地后 aout 残留只剩几十 ms，裸 setRate 已达标，默认关；
         * 若真机个别文件仍偏慢，置 true 换「即时但一次短静音」的口径。
         */
        private const val AUDIO_FLUSH_ON_RATE = false

        /**
         * 自适应 seek 冲刷总开关（2026-09-22 默认关）：aaudio 时代的兜底机制，换
         * audiotrack 后探针 600ms 阈值 vs 250-350ms 采样粒度必然误判，误开冲刷会在
         * 代理 RMVB 上制造数百次 buffering 的持续卡顿（详见 sampleRateAdaptive 注释）。
         */
        private const val ADAPTIVE_FLUSH_ENABLED = false
        const val EXTRA_POSITION_MS = "rmvb.position"
        const val EXTRA_SOURCE_ID = "rmvb.sourceId"
        const val EXTRA_SOURCE_PATH = "rmvb.sourcePath"
        const val EXTRA_LOCAL_PATH = "rmvb.localPath"
        const val EXTRA_QUEUE_URLS = "rmvb.queueUrls"
        const val EXTRA_QUEUE_TITLES = "rmvb.queueTitles"
        const val EXTRA_QUEUE_INDEX = "rmvb.queueIndex"

        /**
         * 入口闸门已确认标记（2026-10-10）：主播放器计量确认后的路由移交携带 true，
         * 兜底页本次起播不再二次弹窗。只有「同一用户动作」的直路由/软解记忆路由传
         * true；错误态转软解（onFallbackRetry）是新的起播指令，不带标记、照常重判。
         */
        const val EXTRA_METERED_CONFIRMED_START = "rmvb.meteredConfirmedStart"

        // E1-1：系统回收重建的实例状态键（对齐 PlayerActivity STATE_* 口径）
        private const val STATE_URL = "novax.rmvb.url"
        private const val STATE_POSITION_MS = "novax.rmvb.position"
        private const val STATE_QUEUE_INDEX = "novax.rmvb.queueIndex"

        private const val MODE_NONE: Byte = 0
        private const val MODE_SEEK: Byte = 1
        private const val MODE_BRIGHTNESS: Byte = 2
        private const val MODE_VOLUME: Byte = 3
        private const val MODE_ZOOM: Byte = 4

        /**
         * 兜底扩展名集：ExoPlayer 无对应容器/解码支持的格式（PlayerActivity 路由判定唯一依据）。
         * 2026-10-26 补 wma/ape/aiff/aif：随音频路由表补全（NvFileRouter.AUDIO_EXTS）一并收编，
         * VLC 原生可播；m4b/mka/oga 走 media3（MP4/Matroska/Ogg 容器，ExoPlayer 支持）不在此表。
         */
        val FALLBACK_EXTENSIONS = setOf("rm", "rmvb", "wmv", "asf", "wma", "ape", "aiff", "aif")

        fun isFallbackTarget(pathOrUrl: String): Boolean {
            // 尾部空格/点容错与 NvFileRouter.extensionOf 同式（2026-10-26）：否则脏尾巴文件
            //（"x.wma "）以 AUDIO/VIDEO 身份进播放器却匹配不上兜底集，留在 media3 必播错
            val name = pathOrUrl.substringBefore('?').substringAfterLast('/')
                .trim().trimEnd('.', ' ')
            return name.substringAfterLast('.', "").lowercase() in FALLBACK_EXTENSIONS
        }

        fun start(
            context: Activity,
            title: String,
            positionMs: Long,
            sourceId: Long?,
            sourcePath: String?,
            localPath: String?,
            queueUrls: List<String> = emptyList(),
            queueTitles: List<String> = emptyList(),
            queueIndex: Int = -1,
            meteredConfirmedStart: Boolean = false,
        ) {
            context.startActivity(Intent(context, RmvbPlayerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSITION_MS, positionMs)
                sourceId?.let { putExtra(EXTRA_SOURCE_ID, it) }
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
                putExtra(EXTRA_LOCAL_PATH, localPath)
                putStringArrayListExtra(EXTRA_QUEUE_URLS, ArrayList(queueUrls))
                putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(queueTitles))
                putExtra(EXTRA_QUEUE_INDEX, queueIndex)
                putExtra(EXTRA_METERED_CONFIRMED_START, meteredConfirmedStart)
            })
        }
    }
}
