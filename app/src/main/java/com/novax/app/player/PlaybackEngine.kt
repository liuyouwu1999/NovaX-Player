@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.novax.app.R
import com.novax.app.data.playback.NvMediaSourceBuilder
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * C8 网络超时保险丝默认值：8s 连接/读取，弱网或不可达源不再无限等待（配合 PlayerScreen 错误态）。
 * C10 设置「连接超时」（novax_net_connect_timeout_seconds，5/8/15s）运行时覆盖：
 * 实际值经 Context.httpTimeoutMs() 读取（见 PlayerPrefs.kt）。
 */
const val HTTP_CONNECT_TIMEOUT_MS = 8_000
const val HTTP_READ_TIMEOUT_MS = 8_000

/**
 * C8 · 播放性能核心
 * - LoadControl 分协议 profile（SMB / WebDAV-HTTP / SFTP / 本地），参数对齐 C8 §2.1
 * - seek 分级 L0 微 seek / L1 标准 / L2 关键帧快跳 / L3 精确（C8 §1）
 * - seek 起播耗时（seek_to_first_frame_ms）、起播首帧（startup_first_frame_ms）、
 *   rebuffer 次数与时长埋点（C8 §5.1），简单 Log + 回调上报
 * - 连接复用策略：SMB session/handle 池与 OkHttp keep-alive 由 DataSource 层持有，
 *   seek 只移动游标，绝不重建播放器/DataSource（本版本网络源统一走 DefaultDataSource，
 *   smbj/WebDAV 专用 DataSource 在 FileCoreLibrary 落地后按 profile 注入）
 */

/** C8 §2.1 缓冲 profile：按来源协议选择 */
enum class BufferProfile(
    val playbackMs: Int,        // 起播门槛
    val rebufferMs: Int,        // rebuffer 后恢复门槛
    val minBufferMs: Int,       // 前向缓冲目标
    val maxBufferMs: Int,       // 上限
    val backBufferMs: Long,     // 回看缓冲（L0 微 seek 免费的关键）
) {
    SMB(800, 600, 50_000, 120_000, 45_000),
    WEBDAV_HTTP(1200, 800, 50_000, 120_000, 45_000),
    SFTP(1500, 1000, 40_000, 90_000, 30_000),
    LOCAL(300, 200, 10_000, 30_000, 0),
    // 2026-09-15 远程模式自适应（用户需求：Tailscale 外网访问）：起播前 256KB 探测
    // （NvRemoteProfileDetector）判定为慢链路时切换——起播仍快（800ms），rebuffer 恢复门槛
    // 提到 2s（防抖动链路停-起-停 thrash），前向目标 90s 让加载器始终抢跑。maxBuffer 不加
    // 大（时间优先模式下一味加大 = 内存膨胀），靠 minBuffer 90s 持续抢跑拉满前向缓冲。
    SMB_REMOTE(800, 2_000, 90_000, 120_000, 45_000),
    WEBDAV_HTTP_REMOTE(1_000, 2_000, 90_000, 120_000, 45_000);

    companion object {
        fun of(url: String): BufferProfile = when {
            url.startsWith("smb://") -> SMB
            url.startsWith("webdav://") ||
                url.startsWith("dav://") ||
                url.startsWith("http://") ||
                url.startsWith("https://") -> WEBDAV_HTTP
            url.startsWith("sftp://") -> SFTP
            else -> LOCAL
        }
    }
}

/** 网络源 targetBufferBytes 下限 24MB：DefaultLoadControl 缺省按轨型计算（弱机型可能 tiny），
 *  显式给足分配额，配合 prioritizeTimeOverSizeThresholds 按时间档分段吃满（80Mbps×50s 不现实，
 *  以 3.x 段式 64KB segment×384 起步，时间阈值优先）。
 *  2026-09-15 真机取证：内存吃紧设备（本机 Redmi 10X，物理内存仅 5.4GB，播放时整机
 *  swap 83~85%）上，本值 + 前向缓冲 120s 会把整机推到 lowmemorykiller 杀前台 App 的境地
 *  （用户实测「播放中闪退」真因），故对内存吃紧设备按 [MEMORY_TIGHT_BUFFER_SCALE] 缩减。 */
private const val NETWORK_TARGET_BUFFER_BYTES = 24 * 1024 * 1024

/** 内存吃紧设备的缓冲缩放：前向/上限/targetBufferBytes 同时 ×0.5（24MB→12MB，120s→60s） */
private const val MEMORY_TIGHT_BUFFER_SCALE = 0.5f

/** 前向缓冲在内存吃紧设备上的硬上限（毫秒）：60s 足够吸收 NAS/弱网抖动，且不会囤上百 MB */
private const val MEMORY_TIGHT_MAX_FORWARD_MS = 60_000

/**
 * 内存是否吃紧（2026-09-15 用户实测「播放中闪退」修复）：
 * 原判据只有 `ActivityManager.memoryClass <= 128`（App 堆上限 ≤128MB）——真机取证显示这台
 * 6GB 设备的 memoryClass 为 256，判据恒为 false，等于「内存守卫」从未生效；于是播放时把整机
 * 压到 swap 83~85%，被 lowmemorykiller 以 oom_score_adj 0 杀掉前台进程（用户看到的「闪退」，
 * 无崩溃日志、无弹窗）。
 * 新判据（任一成立即视为吃紧）：App 堆上限偏小 / 整机物理内存 < 6.5GB / 当前可用内存 < 1.2GB。
 * 全部为只读系统查询，异常时按「不吃紧」处理（绝不误伤大内存设备的既有体验）。
 */
fun Context.isMemoryTight(): Boolean = runCatching {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    if (am.memoryClass <= 192) return@runCatching true
    val info = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    if (info.totalMem in 1 until (6.5 * 1024 * 1024 * 1024).toLong()) return@runCatching true
    info.availMem in 1 until (1.2 * 1024 * 1024 * 1024).toLong()
}.getOrDefault(false)

/**
 * LoadControl 关键参数（纯数据，JVM 可测）：抽出来的原因是 media3 1.8 的 LoadControl
 * 只暴露 `shouldStartPlayback(Parameters)` 形式，无法从外部反查已建实例的参数，
 * 故把「算什么」与「怎么建」分离，让内存缩放策略可被单测锁定。
 */
data class LoadControlParams(
    val targetBufferBytes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferBufferMs: Int,
    val backBufferMs: Int,
)

/** 按 profile / 内存吃紧 / 缓冲档计算 LoadControl 参数（纯函数，可单测） */
fun computeLoadControlParams(
    profile: BufferProfile,
    lowMem: Boolean,
    level: BufferLevel = BufferLevel.MEDIUM,
): LoadControlParams {
    val scale = if (lowMem) MEMORY_TIGHT_BUFFER_SCALE else 1f
    val customize = profile != BufferProfile.LOCAL && level != BufferLevel.MEDIUM
    val forwardMs = if (customize) level.forwardMs else profile.minBufferMs
    val maximumMs = if (customize) level.maxMs else profile.maxBufferMs
    // 内存吃紧：前向缓冲连上限一起压（原先只压 min/max，前向仍可涨到 120s = 上百 MB 数据）
    val cappedForwardMs = if (lowMem) forwardMs.coerceAtMost(MEMORY_TIGHT_MAX_FORWARD_MS) else forwardMs
    val cappedMaximumMs = if (lowMem) maximumMs.coerceAtMost(MEMORY_TIGHT_MAX_FORWARD_MS * 2) else maximumMs
    return LoadControlParams(
        targetBufferBytes = when (profile) {
            BufferProfile.LOCAL -> C.LENGTH_UNSET  // 本地默认按轨型计算即可
            else -> (NETWORK_TARGET_BUFFER_BYTES * scale).toInt()
        },
        minBufferMs = (cappedForwardMs * scale).toInt().coerceAtLeast(profile.playbackMs),
        maxBufferMs = (cappedMaximumMs * scale).toInt(),
        playbackBufferMs = profile.playbackMs,
        rebufferBufferMs = profile.rebufferMs,
        backBufferMs = (profile.backBufferMs * scale).toInt(),
    )
}

/** C8 §2.1 LoadControl 构建（内存吃紧守卫：见 [computeLoadControlParams]） */
fun buildLoadControl(profile: BufferProfile, lowMem: Boolean, level: BufferLevel = BufferLevel.MEDIUM): LoadControl {
    val p = computeLoadControlParams(profile, lowMem, level)
    return DefaultLoadControl.Builder()
        .setTargetBufferBytes(p.targetBufferBytes)
        .setBufferDurationsMs(
            p.minBufferMs,
            p.maxBufferMs,
            p.playbackBufferMs,
            p.rebufferBufferMs,
        )
        .setBackBuffer(p.backBufferMs, true)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}

/**
 * DataSource 工厂：
 * - HTTP(S)：DefaultHttpDataSource 超时取设置「连接超时」（novax_net_connect_timeout_seconds，
 *   5/8/15s，默认 8s；C10 §3.4），避免弱网/不可达源无限缓冲；允许跨协议重定向（部分测试流 http→https 30x）。
 *   超时由调用方在 IO 协程预读后经 [timeoutMs] 传入（P2 修复：禁止主线程 runBlocking 同步等待）；
 *   未传时回落内存缓存快照（PlayerPrefs.playerPrefsSnapshot，非阻塞）。
 * - asset:///sample.mp4：DefaultDataSource 按 scheme 原生路由到 AssetDataSource
 *   （media3-datasource，随 media3-exoplayer 1.8.0 传入），file/content 同理，无需额外配置。
 */
fun buildDataSourceFactory(context: Context, timeoutMs: Int = context.httpTimeoutMs()): DefaultDataSource.Factory {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("NovaX/0.1.0")
        .setConnectTimeoutMs(timeoutMs)
        .setReadTimeoutMs(timeoutMs)
        .setAllowCrossProtocolRedirects(true)
    return DefaultDataSource.Factory(context, httpFactory)
}

/** C8 §1 seek 分级 */
sealed class SeekLevel {
    data object Micro : SeekLevel()      // L0 < 12s：原位，多半命中缓冲
    data object Standard : SeekLevel()   // L1 12s~2min：sync 落点后补齐
    data object Large : SeekLevel()      // L2 > 2min：关键帧快跳
    data object Exact : SeekLevel()      // L3 手动精确档
}

fun seekLevel(distanceMs: Long, exactMode: Boolean = false): SeekLevel = when {
    exactMode -> SeekLevel.Exact
    // 2026-09-18 用户反馈：±15s 连跳「看一秒、再跳」的场景不该卡——12s 阈值恰好漏过 ±15s
    // 档（Standard=EXACT 要顺解到精确帧 → 转圈）。45s 内一律 CLOSEST_SYNC：关键帧直达不
    // 顺解、缓冲区内命中零转圈，落点误差一两个关键帧间距对跳看场景无感
    distanceMs < 45_000 -> SeekLevel.Micro
    distanceMs <= 120_000 -> SeekLevel.Standard
    else -> SeekLevel.Large
}

/**
 * 片尾安全边界（毫秒）：seek 目标不得进入「最后这么长」的窗口。
 *
 * 2026-09-15 用户实测（拖动进度条后画面永久黑屏）真机取证：seek 目标 == duration 时，
 * DataSource 会以 `文件长 - 10KB` 打开，demuxer 取不到样本 → 永久 BUFFERING、黑屏、零读盘、
 * 无错误无 EOS（详见 [PlaybackEngine.smartSeek] 注释）。留 1 秒缓冲后落点一定有样本可解，
 * 且「播到自然结束」的语义不变（只差最后 1 秒，UI 上肉眼不可辨）。
 */
const val SEEK_END_SAFETY_MS = 1_000L

/**
 * seek 目标统一夹取（纯函数，JVM 可测）：[0, duration - SEEK_END_SAFETY_MS]。
 * - duration 未知（[C.TIME_UNSET] 或 <= 0，如直播）→ 仅保证非负，不做上界（无上界可依）；
 * - duration 很短（< 安全边界）→ 下界与上界冲突时以 0 兜底，绝不给负数。
 */
fun clampSeekTarget(targetMs: Long, durationMs: Long): Long {
    if (durationMs == C.TIME_UNSET || durationMs <= 0L) return targetMs.coerceAtLeast(0L)
    val upperBound = (durationMs - SEEK_END_SAFETY_MS).coerceAtLeast(0L)
    return targetMs.coerceIn(0L, upperBound)
}

fun SeekLevel.forSeekParameters(): SeekParameters = when (this) {
    SeekLevel.Micro -> SeekParameters.CLOSEST_SYNC
    SeekLevel.Standard -> SeekParameters.DEFAULT
    SeekLevel.Large -> SeekParameters.PREVIOUS_SYNC
    SeekLevel.Exact -> SeekParameters.EXACT
}

/** 大跳门控对齐阈值：跳转距离超过即走「暂停保音 + EXACT + 出帧续播」（2026-09-23） */
const val GATED_JUMP_MIN_DISTANCE_MS = 120_000L

/** 门控轮询周期与超时兜底（解码僵死时宁可短暂失步也不无限黑屏） */
const val GATED_JUMP_POLL_MS = 120L
const val GATED_JUMP_TIMEOUT_MS = 12_000L

/**
 * 源 IO 类错误（2026-09-14 自愈范围）：网络连接失败/超时、读取越界、未指明 IO、整体超时。
 * 这类错误源于 DataSource 层瞬时断流（NAS 抖动/弱网快进），就地重开 DataSource 续读即可恢复，
 * 不属于「格式/解码」类永久失败。快进稍快报「格式不支持」的多为这里被误展示（已修）。
 */
internal fun isSourceIoError(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_TIMEOUT -> true
    else -> false
}

/** NvAvSync 取证用：AudioTrack 编码常量 → 可读名（PCM 一律归并 PCM，省得枚举漂移） */
internal fun audioEncodingLabel(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "PCM_16"
    C.ENCODING_PCM_8BIT -> "PCM_8"
    C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
    C.ENCODING_AC3 -> "AC3"
    C.ENCODING_E_AC3 -> "E_AC3"
    C.ENCODING_E_AC3_JOC -> "E_AC3_JOC"
    C.ENCODING_AC4 -> "AC4"
    C.ENCODING_DTS, C.ENCODING_DTS_HD -> "DTS"
    C.ENCODING_AAC_LC, C.ENCODING_AAC_ELD, C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2,
    C.ENCODING_AAC_XHE, C.ENCODING_AAC_ER_BSAC -> "AAC_BITSTREAM"
    else -> "enc($encoding)"
}

/** C8 §5.1 埋点：Log + 回调（后续可写 SQLite 聚合，不外发） */
class PerfMetrics {
    var onEvent: ((name: String, valueMs: Long) -> Unit)? = null

    private var pendingSeekStartAt = 0L
    private var pendingSeekLevel = ""
    private var startupStartAt = 0L
    private var startupLogged = false
    private var bufferingSince = 0L
    private var wasReady = false
    var rebufferCount = 0
        private set

    fun markStartup() {
        startupStartAt = SystemClock.elapsedRealtime()
    }

    fun onSeekStart(level: SeekLevel, distanceMs: Long) {
        pendingSeekStartAt = SystemClock.elapsedRealtime()
        pendingSeekLevel = level::class.simpleName ?: "Unknown"
        Log.d(TAG, "seek start level=$pendingSeekLevel distance=${distanceMs}ms")
    }

    fun onRenderedFirstFrame() {
        val now = SystemClock.elapsedRealtime()
        if (pendingSeekStartAt > 0) {
            report("seek_to_first_frame_ms", now - pendingSeekStartAt, pendingSeekLevel)
            pendingSeekStartAt = 0
        } else if (!startupLogged && startupStartAt > 0) {
            startupLogged = true
            report("startup_first_frame_ms", now - startupStartAt)
        }
    }

    fun onPlaybackStateChanged(state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_READY -> {
                if (wasReady && bufferingSince > 0) {
                    report("rebuffer_duration_ms", now - bufferingSince)
                    bufferingSince = 0
                }
                wasReady = true
            }
            Player.STATE_BUFFERING -> {
                if (wasReady) {  // 播放态中触发才算 rebuffer
                    rebufferCount++
                    bufferingSince = now
                    report("rebuffer_count", rebufferCount.toLong())
                }
            }
            else -> {}
        }
    }

    fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "player error: ${error.errorCodeName}")
        onEvent?.invoke("player_error", 0L)
    }

    private fun report(name: String, valueMs: Long, detail: String = "") {
        Log.d(TAG, "$name=${valueMs}ms $detail")
        onEvent?.invoke(name, valueMs)
    }

    companion object {
        private const val TAG = "NovaXPerf"
    }
}

/**
 * 播放引擎：持有按 C8 参数构建的 ExoPlayer。
 * seek 不重建会话：seek 仅在已建立的 ExoPlayer 上移动游标。
 *
 * novax:// 接入（C8）：initialMediaSource / mediaSourceFactory / novaxSource 三者由
 * PlayerActivity 在异步准备完成后注入——novax URI 经 NvRoutingMediaSourceFactory 路由到
 * NvDataSourceFactory（连接复用），运行时 side-load 字幕同样以 novax:// URI 读取。
 */
class PlaybackEngine(
    context: Context,
    val videoUrl: String,
    startPositionMs: Long = 0L,
    /**
     * 媒体标题（P1-6 后台播放）：写入 MediaItem.mediaMetadata.title，供 MediaSession
     * 默认通知栏展示；null = 不设（通知栏回落应用名）。
     */
    private val mediaTitle: String? = null,
    /** 通知栏副文字(artist 行,spec 2026-10-10 §4.1);null=不设 */
    private val mediaSubtitle: String? = null,
    /** 通知栏封面归一化字节(≤480px JPEG ≤64KB);null=不设(app 图标兜底) */
    private val mediaArtworkData: ByteArray? = null,
    /**
     * C8 §1 L3 精确跳转（C10 设置「精确跳转」novax_precise_seek）：可变公有——
     * PlayerScreen 收集设置流实时覆盖，seek 分级随之切换 EXACT/默认。
     */
    var exactSeekMode: Boolean = false,
    initialSubtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
    /** 预构建 MediaSource（novax://）：非空时优先于 videoUrl 直接 setMediaSource */
    private val initialMediaSource: MediaSource? = null,
    /** 自定义 MediaSource.Factory（novax:// 路由）；空 = 默认 DataSource 工厂 */
    mediaSourceFactory: MediaSource.Factory? = null,
    /** novax:// 源实体：运行时 side-load 字幕把 path 解析为 novax:// URI */
    private val novaxSource: com.novax.app.data.db.SourceEntity? = null,
    /** 缓冲 profile 覆盖（novax:// 按源协议选 SMB/WEBDAV_HTTP/LOCAL） */
    profileOverride: BufferProfile? = null,
    /**
     * HTTP 超时覆盖（毫秒）：宿主在 IO 协程预读 novax_net_connect_timeout_seconds 后传入，
     * 避免主线程同步读 DataStore（P2 修复）；null = 读内存缓存快照。
     */
    httpTimeoutMs: Int? = null,
    /** 首选音轨语言（novax_audio_language，"auto" 忽略）：trackSelector preferredAudioLanguage */
    preferredAudioLanguage: String? = null,
    /** 首选字幕语言列表（novax_subtitle_languages）：trackSelector preferredTextLanguages */
    preferredTextLanguages: List<String> = emptyList(),
    /** 缓冲档（BufferLevel：LOW/MEDIUM/HIGH，覆盖 profile 的前向/上限缓冲）；watchdog 升降级读写 */
    val bufferLevel: BufferLevel = BufferLevel.MEDIUM,
    /**
     * 2026-09-15：AudioTrack 编码直通（novax_audio_passthrough，默认开）。
     * 开=跟随平台 AudioCapabilities（AC3/E-AC3 位流直送）；关=强制 PCM 解码。
     * 直通时音量增益无效（位流绕过 PCM 混音链），NvPerf 有日志取证。
     *
     * 2026-09-19 用户需求「检测到不支持就默认关，用户无感」：开≠一定直通——播放期
     * 按平台 AudioCapabilities 复核，设备不报 AC3/E-AC3 直通能力时本值自动失效
     * （effectivePassthrough=false，仅本次播放生效，不改用户设置），杜绝「选了直通
     * 却出不了声」这类静音；有能力的设备行为不变。
     */
    val audioPassthrough: Boolean = true,
) {
    private val appContext = context.applicationContext
    val profile: BufferProfile = profileOverride ?: BufferProfile.of(videoUrl)
    val metrics = PerfMetrics()

    // ---------------- 源 IO 自愈（2026-09-14：IO 断流静默重试一次，二次失败才交 UI 错误态） ----------------

    /** 首次源 IO 错误触发静默自愈时的回调（PlayerScreen 弹「正在自动重试」轻提示）；主线程调用 */
    var onSourceIoRetry: ((positionMs: Long) -> Unit)? = null

    /** 自愈兜不住的最终错误（二次失败 / 非 IO 类）：PlayerScreen 据此显示错误态；主线程调用 */
    var onFatalError: ((PlaybackException) -> Unit)? = null

    /** 最终错误挂起投递（同 pendingFatalError 语义） */
    private var pendingFatalError: PlaybackException? = null

    /**
     * 帧渲染回调（2026-09-15 用户实测「画面卡死但 position 照常推进」修复）：
     * 真机取证——position 每 500ms 稳定 +500ms（播放器自认正常播放），但画面永远停在
     * 同一镜头，且音频轨也无输出。**「位置推进」完全不能代表「帧在渲染」**，
     * 因此僵死判据必须绑定真实的帧渲染事件（本回调），而不是位置是否变化。
     * 每次 setMediaSource/setMediaItem/prepare/seek 后的首帧渲染都会触发。
     */
    var onFrameRendered: (() -> Unit)? = null

    /**
     * 视频尺寸变化回调（2026-10-22 连点压测新增，主线程）：切集自动定向消费。
     * 引擎侧事件天然属当前集——热切换后 MediaController 的镜像状态（媒体项/轨道/videoSize）
     * 同步有先后且可能长期滞留旧值，UI 侧不可依赖（压测实证 videoSize 停 0x0 超 12s）。
     */
    var onVideoSizeChanged: ((androidx.media3.common.VideoSize) -> Unit)? = null

    /** 取走挂起的未消费最终错误（若有）：PlayerScreen 错误接线注册后立即查询，补上注册前的空窗 */
    fun consumePendingFatalError(): PlaybackException? {
        val pending = pendingFatalError
        pendingFatalError = null
        return pending
    }

    /**
     * 渲染器僵死自愈（2026-09-15 用户实测「画面卡死但 position 照常推进」）：
     * 真机取证：position 每 500ms 稳定 +500ms、`playing=true`，但画面永远停在同一镜头
     * （且底部按钮显示暂停图标）——**位置推进不等于帧在渲染**。
     * flush 渲染器 + 重新 prepare 是可靠唤醒手段（会重新请求关键帧并重建渲染管线）。
     *
     * 2026-09-21 审计 P3 加固：本入口同时是 SMB 读侧僵死（NvStallRecovery.handler，每条流
     * 一次触发、每次 re-prepare 产生新流重新武装）的自愈出口——该路径此前无任何计数上限
     * （UI 心跳看门狗侧有 MAX_STALL_RECOVER=3，本旁路没有）。现加滑动窗口预算：
     * 60s 内最多 3 次，超限静默拒绝（NAS 持续读不出字节的病态下不再无限 prepare 打转，
     * 让错误态/用户操作接管）；窗口过后自动重新武装，正常间歇性自愈不受影响。
     */
    fun forceRePrepare() {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        if (now - stallReprepareWindowStart > STALL_REPREPARE_WINDOW_MS) {
            stallReprepareWindowStart = now
            stallReprepares = 0
        }
        if (++stallReprepares > MAX_STALL_REPREPARE_PER_WINDOW) {
            Log.w(
                "NvPerf",
                "forceRePrepare suppressed: $stallReprepares re-prepares in " +
                    "${STALL_REPREPARE_WINDOW_MS}ms window (self-heal budget exhausted)",
            )
            return
        }
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        Log.w("NvPerf", "renderer stall -> force re-prepare from=${position}ms")
        NvStallRecovery.noteSeek()
        runCatching {
            ioRetryAvailable = true          // 重新武装自愈，避免与 IO 自愈互相消耗
            player.seekTo(position.coerceAtLeast(0L))
            player.prepare()
        }.onFailure { Log.w("NvPerf", "force re-prepare failed", it) }
        // 重新 prepare 只重建管线；真机取证（C2MtkBufferManager: bufferqueue may disconnected）
        // 显示解码器换档后还会持有断开的旧 bufferqueue，必须同时重挂 surface 才能真正出画。
        NvStallRecovery.notifyNoPictureRecover("forceRePrepare from=${position}ms")
    }

    /** forceRePrepare 滑动窗口预算（2026-09-21 审计 P3）：60s 内最多 3 次 */
    private var stallReprepares = 0
    private var stallReprepareWindowStart = 0L

    @Volatile
    private var released = false

    /**
     * 引擎是否已释放（2026-09-15 用户实测「滑动进度条进度条不动」修复）。
     *
     * 真机复现的完整证据链（自适应升档时 UI 手势撞上旧引擎）：
     * ```
     * IllegalStateException: Handler {f6d74f0} sending message to a Handler on a dead thread
     *   at ExoPlayerImplInternal.seekTo(ExoPlayerImplInternal.java:422)
     *   at PlaybackEngine.smartSeek(PlaybackEngine.kt:639)
     *   at PlayerScreenKt.PlayerScreen$lambda$177$15(PlayerScreen.kt:1046)   ← 滑动 seek 的 commit
     * ```
     * 自适应升档会**重建引擎**（`PlaybackService.startPlaybackInternal` 里
     * `engine?.release()` → 换新引擎），而 Compose 侧那次手势仍抓着旧 engine 引用：
     * `seekTo` 被投递到**已 quit 的播放线程**，异常只以 `W MessageQueue` 形式打印、**静默丢弃**，
     * 于是「进度条怎么拖都不动」。用户看到的「上次那种情况之后」= 自适应重建之后。
     * 故：已释放的引擎必须拒绝一切操作，UI 侧改投当前活跃引擎（见 PlayerScreen.seekViaEngine）。
     */
    val isReleased: Boolean get() = released

    /** 自愈弹药：出错重试后未重新 READY 前不再重试；恢复 READY 即重新武装（下次断流仍可自愈一次） */
    private var ioRetryAvailable = true

    /**
     * 已应用到 player 的 seek 参数（避免每次 seek 都调 setSeekParameters——该调用会让渲染器丢帧，
     * 真机大文件上会把视频渲染器推入「位置走、画面不出帧」，见 [smartSeek] 注释）。
     */
    private var pendingSeekParameters: SeekParameters? = null
    private var lastSeekExactMode: Int = -1

    /** 当前 side-load 字幕轨 Format.id（C6 §3.1，供 UI 对齐选中态；null=未加载外挂字幕） */
    var activeSubtitleId: String? = null
        private set

    /**
     * 用户主动跳转已提交的回调（2026-09-15 自适应升档收敛用）。
     *
     * seek 之后的起播缓冲是**用户操作的结果**，不能当作「网络变差」的证据：
     * 真机上拖动一次大文件进度条就足以凑满旧阈值（60s/2 次）触发升档 → 重建引擎。
     * PlaybackService 据此调用 `NvAdaptivePolicy.onUserSeek()` 清观测并重置稳定起点。
     */
    var onUserSeek: ((targetMs: Long) -> Unit)? = null

    val player: ExoPlayer = run {
        // 2026-09-15 用户实测修复：改用 isMemoryTight()（memoryClass/物理内存/当前可用内存三判据），
        // 旧的 memoryClass<=128 在这台 6GB 设备上恒为 false，导致内存守卫从未生效
        val lowMem = context.isMemoryTight()
        android.util.Log.d(
            "NvPerf",
            "loadControl lowMem=$lowMem profile=$profile level=$bufferLevel",
        )

        val trackSelector = DefaultTrackSelector(context)

        // 2026-09-19 用户需求：直通能力检测——设备不报 AC3/E-AC3 直通能力时自动关（用户无感，
        // 仅本次播放生效）。AudioCapabilities 按 AudioAttributes 查询（USAGE_MEDIA 口径）
        // 2026-09-22 追加：此判定现仅留作日志取证——NvRenderersFactory 已无条件 PCM-only
        // （直通+倍速 = 渐进 desync，androidx/media #2283），本值不再改变音频路径。
        val effectivePassthrough = if (!audioPassthrough) {
            false
        } else {
            val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
            val ok = caps.supportsEncoding(C.ENCODING_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3)
            if (!ok) {
                android.util.Log.w(
                    "NvPerf",
                    "passthrough auto-off: device reports no AC3/E-AC3 passthrough capability " +
                        "(maxChan=${caps.maxChannelCount})",
                )
            }
            ok
        }

        ExoPlayer.Builder(context)
            .setLoadControl(buildLoadControl(profile, lowMem, bufferLevel))
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                mediaSourceFactory
                    ?: DefaultMediaSourceFactory(context, NvMediaSourceBuilder.nvExtractorsFactory())
                        // 字幕延迟偏移同样覆盖直连 http/file 播放及其 side-load 字幕（审核 P1-5 第 4 注入点）
                        .setSubtitleParserFactory(NvShiftedSubtitleParserFactory())
                        .setDataSourceFactory(buildDataSourceFactory(context, httpTimeoutMs ?: context.httpTimeoutMs())),
            )
            .setRenderersFactory(
                NvRenderersFactory(context, effectivePassthrough, appContext.avOffsetMs())
                    .setEnableDecoderFallback(true)
                    // 强制硬件优先：关闭任何软解扩展（av1/ffmpeg 等）抢占硬解轨
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    .also { Log.d("NvPerf", "renderers extMode=OFF hwPriority=true passthrough=$effectivePassthrough (pref=$audioPassthrough) profile=$profile") }
            )
            // P2 修复：音频焦点（来电暂停/其他应用抢焦点）+ 拔耳机暂停
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // P1-6 后台播放：锁屏/退后台后保持 CPU 与网络唤醒，SMB/HTTP 流不因休眠断流
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { exo ->
                // P2 低成本真生效：首选音轨/字幕语言（novax_audio_language / novax_subtitle_languages）
                val params = exo.trackSelectionParameters.buildUpon()
                if (!preferredAudioLanguage.isNullOrEmpty() && preferredAudioLanguage != "auto") {
                    params.setPreferredAudioLanguage(preferredAudioLanguage)
                }
                if (preferredTextLanguages.any { it.isNotBlank() }) {
                    params.setPreferredTextLanguages(*preferredTextLanguages.filter { it.isNotBlank() }.toTypedArray())
                }
                exo.trackSelectionParameters = params.build()
            }
    }

    // ---------------- 2026-09-22 NvAvSync 双审计（desync 三分法定量） ----------------
    // 每 5s 打两条（Log.w，release 可见）：
    // - videoAudit：累计丢帧 + 解码输出积压均值（avg 明显大于一帧周期 41ms = 视频侧跟不上）；
    // - clockAudit：窗口内「内容推进 vs 实际时长×速度」差（音频媒体时钟是否说谎）。
    // 定位口诀：drift 持续上涨 = 音频时钟问题；drift≈0 而 dropped/procOffset 涨 = 视频吞吐问题。
    private var droppedFramesTotal = 0L
    private var dropEvents = 0
    private var procOffsetCount = 0L
    private var procOffsetTotalUs = 0L
    private var lastAuditAt = 0L
    private var clockBaseRealtimeMs = 0L
    private var clockBasePositionMs = 0L
    private val auditHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val auditRunnable = object : Runnable {
        override fun run() {
            auditNow()
            auditHandler.postDelayed(this, 5_000)
        }
    }

    private fun startAvAuditTicker() {
        auditHandler.postDelayed(auditRunnable, 5_000)
    }

    /**
     * 审计基线重置：变速/seek 后「内容推进 vs 实际时长×速度」在当前 5s 窗口内不可比
     * （窗口跨越两种速度/含位置跳变），会报出与窗口等量级的伪 drift。置 0 让下一个
     * 窗口重新取基线，clockAudit 只在稳态下计量。
     */
    private fun resetClockAuditBase() {
        clockBaseRealtimeMs = 0L
    }

    private fun avAuditNoteDropped(droppedFrames: Int) {
        droppedFramesTotal += droppedFrames
        dropEvents++
    }

    private fun avAuditNoteProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {
        procOffsetCount += frameCount
        procOffsetTotalUs += totalProcessingOffsetUs
    }

    private fun auditNow() {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAuditAt < 4_500) return
        lastAuditAt = now
        val avgMs = if (procOffsetCount > 0) procOffsetTotalUs / procOffsetCount / 1000.0 else 0.0
        Log.w(
            "NvAvSync",
            "videoAudit dropped=$droppedFramesTotal (events=$dropEvents) " +
                "procOffset avg=${"%.1f".format(avgMs)}ms frames=$procOffsetCount",
        )
        val playing = runCatching {
            player.playbackState == Player.STATE_READY && player.playWhenReady
        }.getOrDefault(false)
        val pos = runCatching { player.currentPosition }.getOrDefault(-1L)
        if (!playing || pos < 0) {
            clockBaseRealtimeMs = 0L
            return
        }
        val spd = runCatching { player.playbackParameters.speed }.getOrDefault(1f)
        if (clockBaseRealtimeMs == 0L) {
            clockBaseRealtimeMs = now
            clockBasePositionMs = pos
            return
        }
        val realMs = now - clockBaseRealtimeMs
        val contentMs = pos - clockBasePositionMs
        val drift = contentMs - realMs * spd
        Log.w(
            "NvAvSync",
            "clockAudit window=${realMs}ms speed=$spd contentDelta=${contentMs}ms drift=${drift}ms",
        )
        clockBaseRealtimeMs = now
        clockBasePositionMs = pos
    }

    init {
        metrics.markStartup()
        startAvAuditTicker()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                metrics.onPlaybackStateChanged(playbackState)
                // 自愈成功（重新 READY）后重新武装：后续再遇 IO 断流仍可各自愈一次
                if (playbackState == Player.STATE_READY) {
                    ioRetryAvailable = true
                    attachVolumeBoostIfReady()   // 复审 M3：音频会话就绪后补挂音量增益
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // 终审 I-2：AC3↔AAC 切轨时激活 renderer 在两个 AudioSink 间切换，audioSessionId
                // 随之变化且不经 STATE_READY——增益若滞留旧会话即失效。旧 enhancer 绑死旧
                // sessionId，必须先释放，再走现有 attach 路径对新会话重挂（desiredGainMb<=0 时
                // attach 路径自然走释放分支，此处提前返回零开销）。
                if (desiredGainMb <= 0) return
                volumeEnhancer?.release()
                volumeEnhancer = null
                Log.w("NvAvSync", "audioSessionId=$audioSessionId changed, volume boost re-attach gainMb=$desiredGainMb")
                attachVolumeBoostIfReady()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // 音画偏移补偿的变速缩放（NvAvOffsetAudioProcessor）：补偿量 = offset × 倍速，
                // 音效栈墙钟延迟恒定而内容流速随倍速变化——2x 时丢固定内容量只等于一半墙钟，
                // 不缩放则倍速期间音频重新落后（1x 对齐、倍速不同步的根因）。
                NvAvOffsetAudioProcessor.currentSpeed = playbackParameters.speed
            }

            override fun onRenderedFirstFrame() {
                metrics.onRenderedFirstFrame()
                // 出帧证据（按 seek 序号计数）：见 NvStallRecovery.noteFrame 注释——
                // 该回调只在 prepare/seek 后触发一次，只能当「本次 seek 后是否出过帧」的证据
                NvStallRecovery.noteFrame(android.os.SystemClock.elapsedRealtime())
                onFrameRendered?.invoke()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // 2026-09-15 用户录屏取证：拖动进度条后「画面缩小一点，然后画面没了」。
                // 画面几何由 videoW/videoH 推导（VideoSurface），尺寸变更必须留痕——
                // 真机取证时能直接回答「是不是 Media3 报了不同的视频尺寸导致画面缩放」。
                val changed = NvStallRecovery.noteVideoSize(videoSize.width, videoSize.height)
                // W 级（非 D）：vivo 拦第三方 Log.d，真机取证切集定向全靠本条（编码尺寸 +
                // 旋转元数据是「横屏被当竖屏」类问题的双要素）
                Log.w(
                    "NvPerf",
                    "videoSize ${videoSize.width}x${videoSize.height} " +
                        "par=${videoSize.pixelWidthHeightRatio} rot=${videoSize.unappliedRotationDegrees} " +
                        "fmtRot=${player.videoFormat?.rotationDegrees ?: 0} " +
                        "changed=$changed count=${NvStallRecovery.videoSizeChanges}",
                )
                // 2026-10-22 连点压测：转发给 UI（切集自动定向消费，见 PlaybackEngine 属性注释）
                this@PlaybackEngine.onVideoSizeChanged?.invoke(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                metrics.onPlayerError(error)
                // 源 IO 类错误（网络断连/读取失败）且本次 incident 尚未重试过 → 就地静默自愈一次：
                // 记住出错位置 → seekTo 回该位置 → prepare() 重开 DataSource 续读。
                // 不清 MediaItem/MediaSource、不动 surface、不重建 engine（surface 生命周期约定见
                // PlayerScreen.VideoSurface），恢复后画面/进度无感。
                if (!released && ioRetryAvailable && isSourceIoError(error.errorCode)) {
                    ioRetryAvailable = false
                    val resumeAt = player.currentPosition.coerceAtLeast(0L)
                    val wasPlaying = player.playWhenReady
                    Log.d("NvPerf", "io self-heal re-prepare from=${resumeAt}ms code=${error.errorCodeName}")
                    player.seekTo(resumeAt)
                    player.playWhenReady = wasPlaying
                    player.prepare()
                    onSourceIoRetry?.invoke(resumeAt)
                } else {
                    pendingFatalError = error
                    onFatalError?.invoke(error)
                }
            }
        })
        // NvError 完整错误链取证（2026-09-14）：errorCodeName + cause 链全量打印——
        // 「快进稍快报格式不支持」类用户反馈先看这里定位真实错误类（IO/解码/解析）
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                val chain = buildString {
                    append("code=").append(error.errorCodeName)
                    append('(').append(error.errorCode).append(") msg=").append(error.message)
                    var cause: Throwable? = error.cause
                    var depth = 0
                    while (cause != null && depth < 8) {
                        append("\n  caused by ").append(cause.javaClass.name).append(": ").append(cause.message)
                        cause = cause.cause
                        depth++
                    }
                }
                Log.d("NvError", chain)
            }
        })
        // NvPerf 硬解确认：打印实际初始化的视频解码器名（含 "c2."/"OMX." 前缀即平台硬解，
        //MediaCodecUtil 硬件实现以 .avc/.sw 后缀区分软硬；下次真机验证据此确认）
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedDurationMs: Long,
                realtimeMs: Long,
            ) {
                Log.d("NvPerf", "videoDecoder=$decoderName initMs=$initializedDurationMs")
            }

            // 2026-09-15 直通取证：音频输入编码 + 平台直通能力判定；音量增益与直通互斥的诚实提示
            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                val caps = runCatching { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(appContext) }.getOrNull()
                val pt = caps?.isPassthroughPlaybackSupported(format) == true
                Log.d("NvPerf", "audioInput=${format.sampleMimeType} passthroughCapable=$pt desiredGainMb=$desiredGainMb")
                if (pt && desiredGainMb > 0) {
                    Log.d("NvPerf", "直通输出时音量增益无效（位流绕过 PCM 混音链，如需增益请关闭直通）")
                }
            }

            // ---- 2026-09-22 长按倍速音画不同步取证（NvAvSync；Log.w 级，release 包可见）----
            // 变速 desync 三要素一眼可辨：
            //   ① onAudioTrackInitialized 的 encoding 非 PCM → 位流直通参与了变速（#2283 型 desync）；
            //      offload=true → 平台 offload 变速（#1110 型）。
            //   ② onPlaybackParametersChanged → sink 实际接受的速度（被拒时 UI 档位与实放不符）。
            //   ③ onAudioUnderrun 频发 → 2x 喂流不足/时间戳外推被 underrun 污染。
            private var underrunCount = 0L

            override fun onAudioTrackInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
            ) {
                Log.w(
                    "NvAvSync",
                    "audioTrack init enc=${audioEncodingLabel(audioTrackConfig.encoding)} " +
                        "rate=${audioTrackConfig.sampleRate} ch=${audioTrackConfig.channelConfig} " +
                        "offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling} " +
                        "buffer=${audioTrackConfig.bufferSize}B",
                )
            }

            override fun onPlaybackParametersChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playbackParameters: PlaybackParameters,
            ) {
                // 变速即时重置审计基线（长按倍速进出不再报伪 drift，见 resetClockAuditBase 注释）
                resetClockAuditBase()
                Log.w("NvAvSync", "playbackParameters speed=${playbackParameters.speed} pitch=${playbackParameters.pitch}")
            }

            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                underrunCount++
                // 首次 + 之后每 5 次打一条（病态场景防刷屏，量级仍可读）
                if (underrunCount == 1L || underrunCount % 5L == 0L) {
                    Log.w(
                        "NvAvSync",
                        "audioUnderrun #$underrunCount bufferSize=$bufferSize(${bufferSizeMs}ms) " +
                            "sinceFeed=${elapsedSinceLastFeedMs}ms",
                    )
                }
            }

            // ---- 2026-09-22 视频侧仪表：desync 三分法定量（clock 漂移 / 视频丢帧 / 解码积压）----
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                avAuditNoteDropped(droppedFrames)
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                avAuditNoteProcessingOffset(totalProcessingOffsetUs, frameCount)
            }
        })
        if (initialMediaSource != null) {
            // novax://：MediaSource（含 side-load 字幕配置）已按源协议构建好，直接注入
            activeSubtitleId = initialSubtitles.firstOrNull()?.id
            player.setMediaSource(initialMediaSource, startPositionMs)
        } else {
            val initialItem = buildVideoItem(initialSubtitles)
            activeSubtitleId = initialSubtitles.firstOrNull()?.id
            player.setMediaItem(initialItem)
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = true
        player.prepare()
        // 2026-10-25 P0 修复配套（真机「声音在放、画面一直加载中」）：引擎换代=一次全新
        // prepare/seek 周期，重置看门狗帧基线——此前 noteSeek 只在用户 seek/forceRePrepare
        // 调用，换集重建引擎从不调，no-frame 看门狗的 framesSinceSeek 条件恒假、形同虚设
        NvStallRecovery.noteSeek()
    }

    /** 统一构建直连 MediaItem:附 side-load 字幕配置与通知栏全量元数据(spec 2026-10-10 §4.1;
     *  title 空白回落 URI 末段,转码/直连/原画三路全量覆盖——P1-6 只设 title 的旧口径作废) */
    private fun buildVideoItem(subtitles: List<MediaItem.SubtitleConfiguration>): MediaItem {
        // Task 7 i18n：标题兜底词按应用语言资源传入（appContext 长命，任意线程安全）
        val notification = NvNotificationMetadata.build(
            mediaTitle, videoUrl, mediaSubtitle, mediaArtworkData,
            appContext.getString(R.string.player_video),
        )
        return MediaItem.Builder()
            .setUri(videoUrl)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(notification.toMediaMetadata())
            .build()
    }

    /**
     * C8 §1 分级 seek：C6 的"松手才 seek"最终走这里。
     * 300ms 内同向 seek 由调用方（C6 层）去抖合并。
     *
     * 2026-09-15 用户实测修复（拖动进度条后画面永久黑屏，真机复现 2/2）：
     * 现场证据（Redmi 10X / LineageOS-21 / SMB 140MB MP4 / 片长 4:23）：
     *   拖到片尾 → `NvDataSource open pos=139966038`（文件长 139976302，只剩最后 10264 字节）
     *   → 读满这 10KB 即 EOF → demuxer 再也拿不到样本，既不报错也不进 EOS
     *   → 永久 BUFFERING / 画面全黑 / 缓冲 0 / 零网络读 / 只能退出重进
     *   （且 `seek_to_first_frame_ms=107ms` 还谎报成功，用户完全无从判断）。
     * 根因：seek 目标被 clamp 到**精确等于** duration 的边界，落进文件尾部的非样本区。
     * 修法：统一经 [clampSeekTarget] 夹到 `duration - SEEK_END_SAFETY_MS` ——
     * 拖到最右端 = 跳到片尾前一点正常续播（播完自然触发连播/ENDED），不再死锁。
     */
    // ---------------- 2026-09-23 大跳门控对齐（gated jump） ----------------
    // 用户需求：「声音有一个进度，视频也有进度，你要对齐」——大跳后音频先行、视频从
    // 关键帧慢慢追（稀疏关键帧 HEVC 实测落后 17.9s、模拟器追平以分钟计）。
    // 门控流程：暂停（音频不先跑）→ setSeekParameters(EXACT) 精确落点 → seek →
    // 每 120ms 轮询「READY + 落点已出帧」，满足即恢复原播放状态；12s 超时兜底放行
    // （解码极慢/僵死时宁可短暂失步也不无限黑屏）。
    private val gateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var gatedJumpActive = false
    private var gatedWasPlaying = false
    private var gateTicks = 0

    private val gateTick = object : Runnable {
        override fun run() {
            if (!gatedJumpActive || released) return
            val state = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
            val firstFrameRendered = NvStallRecovery.framesSinceSeek > 0
            if (state == Player.STATE_READY && firstFrameRendered) {
                finishGatedJump("first-frame")
                return
            }
            gateTicks++
            if (gateTicks * GATED_JUMP_POLL_MS >= GATED_JUMP_TIMEOUT_MS) {
                finishGatedJump("timeout")
                return
            }
            gateHandler.postDelayed(this, GATED_JUMP_POLL_MS)
        }
    }

    private fun beginGatedJump(targetMs: Long) {
        // 门控等待中用户又提交一次大跳：最初播放状态以第一次门控为准——此刻 playWhenReady
        // 已被首次门控置 false，重读会把「原来在播」丢成「原来暂停」，门控结束后停在暂停态
        if (!gatedJumpActive) {
            gatedWasPlaying = runCatching { player.playWhenReady }.getOrDefault(false)
        }
        gatedJumpActive = true
        gateTicks = 0
        NvStallRecovery.noteSeek()
        resetClockAuditBase()
        Log.w("NvAvSync", "gatedJump: hold audio, EXACT seek -> ${targetMs}ms")
        // 门控跳转恒用 EXACT：落点即出帧点（PREVIOUS_SYNC 首帧=旧关键帧，无对齐意义）
        player.setSeekParameters(SeekParameters.EXACT)
        pendingSeekParameters = SeekParameters.EXACT
        player.playWhenReady = false
        player.seekTo(player.currentMediaItemIndex, targetMs)
        onUserSeek?.invoke(targetMs)
        // 上一轮 poll 可能还在队列里，先摘再挂，避免双轮询叠加加速超时判定
        gateHandler.removeCallbacks(gateTick)
        gateHandler.postDelayed(gateTick, GATED_JUMP_POLL_MS)
    }

    private fun finishGatedJump(reason: String) {
        gatedJumpActive = false
        gateHandler.removeCallbacks(gateTick)
        val pos = runCatching { player.currentPosition }.getOrDefault(-1L)
        if (gatedWasPlaying) runCatching { player.playWhenReady = true }
        Log.w("NvAvSync", "gatedJump resume($reason) pos=$pos")
    }

    fun smartSeek(targetMs: Long, exactMode: Boolean = exactSeekMode) {
        // 已释放的引擎（自适应重建/换片后的旧引用）必须直接拒绝：继续 seek 会抛
        // IllegalStateException("sending message to a Handler on a dead thread") 并被静默吞掉，
        // 表现为「进度条怎么拖都不动」（真机取证见 isReleased 注释）
        if (released) {
            Log.w("NvPerf", "smartSeek ignored: engine released (target=${targetMs}ms)")
            return
        }
        val clamped = clampSeekTarget(targetMs, player.duration)
        val distance = abs(clamped - player.currentPosition)
        // 大跳自动对齐（2026-09-23 用户需求：「声音有一个进度，视频也有进度，你要对齐」）：
        // >[GATED_JUMP_MIN_DISTANCE_MS] 的跳转改走门控路径——先静音暂停、EXACT 精确落点、
        // 等视频解码到目标出帧后续播。原 PREVIOUS_SYNC 路径音频先行、视频从关键帧慢慢追
        // （稀疏关键帧实测落后十几秒、追平以分钟计），即用户报的「声音先放、画面卡住」。
        if (distance > GATED_JUMP_MIN_DISTANCE_MS) {
            metrics.onSeekStart(SeekLevel.Large, distance)
            beginGatedJump(clamped)
            return
        }
        val level = seekLevel(distance, exactMode)
        metrics.onSeekStart(level, distance)
        // 出帧证据：本次 seek 的帧计数从此起算（心跳里 framesSinceSeek==0 即「seek 后没出画」）
        NvStallRecovery.noteSeek()
        // 2026-09-15 用户实测「拖动后画面卡死」修复：
        // ExoPlayerImpl.setSeekParameters() 会**让渲染器丢帧并抑制输出**（实现上重置
        // MediaPeriod 的 seek 参数），对播放中的 seek 是重操作；而原先每次 seek（含连拖的每一次）
        // 都无条件调用它，容易在慢解码/大文件（真机案：13.9GB MKV + 1920x832）上把视频渲染器
        // 推到「位置在推进但不再出帧」的状态（用户实测：画面冻住、控制条可操作、无错误）。
        // 改为只在分级真正变化时调用一次（EXACT 模式切换也纳入比对）。
        val wanted = level.forSeekParameters()
        val modeKey = if (exactMode) 1 else 0
        if (pendingSeekParameters !== wanted || lastSeekExactMode != modeKey) {
            player.setSeekParameters(wanted)
            pendingSeekParameters = wanted
            lastSeekExactMode = modeKey
        }
        player.seekTo(player.currentMediaItemIndex, clamped)
        // seek 让位置跳变，「内容推进 vs 实际时长×速度」在当前审计窗口内不可比——重置基线，
        // 下一窗口重建（否则 clockAudit 会报与窗口等量级的伪 drift，排障时误导）
        resetClockAuditBase()
        // 通知观察方：这是用户主动跳转（自适应策略据此忽略随之而来的起播缓冲）
        onUserSeek?.invoke(clamped)
    }

    /** 双击 ±10s / 手势相对 seek（同样拒绝已释放引擎，见 [isReleased]） */
    fun seekBy(offsetMs: Long) {
        if (released) {
            Log.w("NvPerf", "seekBy ignored: engine released (offset=${offsetMs}ms)")
            return
        }
        smartSeek(player.currentPosition + offsetMs)
    }

    /** 变速播放；pitch 固定 1f 即"变速不变调"（C6 §2.3） */
    fun setPlaybackSpeed(speed: Float, pitch: Float = 1f) {
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    // ---------------- 2026-09-15 D6 音量增益（复审 M3 修订） ----------------
    // LoudnessEnhancer 必须挂真实音频会话：组合期 audioSessionId 可能为 UNSET(0)——
    // 用 0 构造会挂全局输出混音（或被平台拒绝）。现记录期望增益，READY 时（会话已建）挂载。

    private var volumeEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    @Volatile
    private var desiredGainMb = 0

    /** D6 音量增益唯一入口（毫贝尔；0 = 关闭并释放）。会话未就绪时挂起，READY 自动生效 */
    fun setVolumeBoostGainMb(gainMb: Int) {
        desiredGainMb = gainMb.coerceAtLeast(0)
        attachVolumeBoostIfReady()
    }

    private fun attachVolumeBoostIfReady() {
        runCatching {
            if (desiredGainMb <= 0) {
                volumeEnhancer?.release()
                volumeEnhancer = null
                return
            }
            if (player.playbackState != Player.STATE_READY) return   // 会话未建，等 READY 再挂
            val sessionId = player.audioSessionId
            if (sessionId == 0) return
            val enh = volumeEnhancer ?: android.media.audiofx.LoudnessEnhancer(sessionId)
                .also { volumeEnhancer = it }
            enh.setTargetGain(desiredGainMb)
        }
    }

    /**
     * C6 §3.1 运行时 side-load 同目录字幕：以带 SubtitleConfiguration 的 MediaItem
     * 替换当前项（进度保留，连接按 DataSource 层策略复用）。
     * P1 修复（GBK 乱码）：挂载前经 [transcodedSubtitleConfiguration] 把原始字节按
     * novax_subtitle_encoding 重编码为 UTF-8 临时文件（novax:// 与本地 file 双路统一），
     * ExoPlayer 全程按 UTF-8 解析。挂载为 IO 操作，请勿在主线程同步调用。
     * @return 该字幕轨的 Format.id（供 UI 追加 track override），文件不可 side-load 时返回 null
     */
    suspend fun loadSubtitle(candidate: SubtitleCandidate): String? {
        val encodingPref = runCatching {
            appContext.playerPrefsStore.data.first()[KEY_SUB_ENCODING]
        }.getOrNull()
        val config = transcodedSubtitleConfiguration(appContext, candidate, novaxSource, encodingPref) ?: return null
        activeSubtitleId = config.id
        swapMediaItem(listOf(config))
        return config.id
    }

    /** 移除 side-load 字幕，回到纯内嵌轨（进度保留） */
    fun clearSubtitle() {
        activeSubtitleId = null
        swapMediaItem(emptyList())
    }

    private fun swapMediaItem(subtitles: List<MediaItem.SubtitleConfiguration>) {
        val positionMs = player.currentPosition
        val wasPlaying = player.playWhenReady
        // 「关闭字幕」的文本轨禁用态会跨 MediaItem 保留，side-load 时显式重新启用
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(buildVideoItem(subtitles), positionMs)
        player.playWhenReady = wasPlaying
        player.prepare()
    }

    fun release() {
        auditHandler.removeCallbacks(auditRunnable)
        gateHandler.removeCallbacks(gateTick)
        runCatching { volumeEnhancer?.release() }
        volumeEnhancer = null
        released = true
        player.release()
    }

    companion object {
        const val LONG_PRESS_SPEED = 2.0f   // C6 §2.3（2026-09-14 修订）长按快进默认 2x（设置可配 3x）
        const val LEFT_SLOWMO_SPEED = 0.5f  // C6 §2.3（2026-09-14 修订）左手模式长按慢放 0.5x（默认关闭）
        const val SIDELOAD_ID_PREFIX = "novax-sideload-"

        /** forceRePrepare 滑动窗口（2026-09-21 审计 P3）：60s 内最多 3 次，防读侧僵死自愈旁路无限打转 */
        internal const val STALL_REPREPARE_WINDOW_MS = 60_000L
        internal const val MAX_STALL_REPREPARE_PER_WINDOW = 3

        /** side-load 字幕轨的稳定 Format.id（UI 以此对齐选中态） */
        fun sideLoadIdFor(path: String): String = SIDELOAD_ID_PREFIX + path.hashCode()
    }
}
