@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.novax.app.NvApp
import com.novax.app.NvAppLanguage
import com.novax.app.R
import com.novax.app.data.NvCrashLog
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.playback.NvUri
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * P1-6 · 后台播放（C6/C10 novax_background_play）：MediaSessionService + MediaController 官方模式。
 *
 * 架构：
 * - ExoPlayer（PlaybackEngine，含 C8 LoadControl/DataSource/自愈）只在 Service 内构建与释放；
 * - MediaSession 包裹引擎 player，通知栏媒体控制由 MediaSessionService 默认 provider 提供；
 * - PlayerActivity 经 MediaController 驱动 session 内 player（media3 1.8 支持 TextureView Surface
 *   经 binder 转发渲染，PlayerScreen 手势/UI 零改动，仅把 engine 参数换成 PlayerHandle 适配层）；
 * - 观看历史（NvHistoryWriter）随 player 迁入 Service：心跳 10s + 暂停即落库 + 销毁兜底，
 *   保证后台播放时历史仍累计（原来挂在 Activity lifecycleScope，退后台即停写）。
 *
 * 语义矩阵（novax_background_play，默认 false）：
 * - true：HOME/锁屏/划掉任务（在播中）继续播放，通知栏可控；显式退出播放器（返回键）在播中不停播；
 * - false：HOME 暂停（回前台不自动恢复，现行为）；划掉任务/显式退出 = 暂停并停服务（现行为）。
 *
 * 规格传递（EngineSpec）：novax:// 预构建 MediaSource / 字幕转码产物 / 源实体等不可 parcel，
 * 同进程内经 [pendingSpec] 静态槽交接（Activity 预读设置并完成全部异步准备，Service 只负责构建）。
 */
class PlaybackService : MediaSessionService() {

    /** 作用域兜底（E5-1 发版审查）：handler 延迟执行，初始化期引用 applicationContext 安全——后台协程未捕获异常记日志落盘，不杀进程。 */
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.w("NvServiceScope", "uncaught in serviceScope", e)
            runCatching { NvCrashLog.recordNonFatal(applicationContext, "serviceScope", e) }
        },
    )

    private var session: MediaSession? = null
    private var engine: PlaybackEngine? = null
    private var currentSpec: EngineSpec? = null
    private var historyWriter: NvHistoryWriter? = null
    private var heartbeatJob: Job? = null

    /**
     * Task 13：通知取词 context——按应用内语言包装（33+ 原样；32- createConfigurationContext）。
     * onCreate 一次性包装存字段：基座恒为 applicationContext（长命服务绝不持 Activity，防泄漏）；
     * 代价边界：服务存续期中途改语言，通知取词到下次服务重建才跟随（brief 拍板「包装一次」）。
     */
    private lateinit var notifContext: Context

    /** session 当前挂的命令扩展 wrapper（每次引擎热切换新建，见 attachToSession） */
    private var sessionPlayerWrapper: QueueCommandsPlayer? = null

    /** wrapper 当前包裹的引擎 player（ForwardingPlayer 无公开 delegate 访问器，另行登记比较） */
    private var sessionPlayerDelegate: Player? = null

    /** 2026-09-15 播放中自适应降级/恢复：rebuffer 观测与档位决策（纯逻辑类） */
    private val adaptivePolicy = NvAdaptivePolicy(System::currentTimeMillis)
    private var adaptiveJob: Job? = null

    /** watchdog 当前观测的片源 URL：换片重置策略状态，自适应重建（同片换档）保留升降级记忆 */
    private var adaptiveUrl: String? = null

    /**
     * M3（2026-10-07）JELLYFIN 自动档决策（NvQualityAutoPolicy，纯逻辑类）。
     * var：其阶梯 current 无重置入口（防自激设计），新播放会话必须新建实例
     * （见 maybeStartAdaptiveWatchdog 顶部，裁决与 adaptivePolicy 同口径——videoUrl 变化）；
     * 同片重建（自适应降档/自动切档自身触发）保留实例，否则自动档会从原画反复降档自激。
     */
    private var qualityAutoPolicy = NvQualityAutoPolicy(System::currentTimeMillis)

    /** qualityAutoPolicy 当前观测的片源 URL（同 adaptiveUrl 口径：换片新建实例，同片保留） */
    private var qualityUrl: String? = null

    /**
     * M4（2026-10-07）：当前「实际生效」清晰度档（清晰度面板 AUTO 行副标通道）。
     * 与 spec.jellyfinTier（用户所选，AUTO = 自动档运行中）分离：起播 = 记忆档
     * （AUTO 记 ORIGINAL——自动档起播原画）；任何切档（手动/自动策略）= 目标档。
     * 非 Jellyfin 会话恒 null。
     */
    @Volatile
    private var jellyfinEffectiveTier: NvQualityTier? = null

    /** 起播时的原始档位（URL 变化时快照）：恢复目标——currentSpec 会被升级覆写，不能用它恢复 */
    private var adaptiveBaseProfile: BufferProfile? = null
    private var adaptiveBaseLevel: BufferLevel = BufferLevel.MEDIUM

    /**
     * M4（2026-10-07）：JELLYFIN 服务器进度上报目标（source + itemId + playSessionId）。
     * 全部经仓库类型路由 → client runCatching，永不抛；调用方一律 serviceScope.launch
     * fire-and-forget（不 await——起播/心跳/暂停回调在主线程，上报慢一拍无所谓）。
     */
    private class JfReporter(private val source: SourceEntity, val itemId: String, private val playSessionId: String?) {
        private val repo get() = com.novax.app.data.NvGraph.repository()
        suspend fun start(positionMs: Long, durationMs: Long = 0L) =
            repo.jellyfinReportStart(source, itemId, playSessionId, positionMs).also {
                Log.d("NvJfProgress", "start itemId=$itemId pos=$positionMs")
            }
        suspend fun progress(positionMs: Long, isPaused: Boolean, durationMs: Long = 0L) =
            repo.jellyfinReportProgress(source, itemId, playSessionId, positionMs, isPaused, durationMs)
        suspend fun stopped(positionMs: Long) =
            repo.jellyfinReportStopped(source, itemId, playSessionId, positionMs).also {
                Log.d("NvJfProgress", "stopped itemId=$itemId pos=$positionMs")
            }
    }

    /** 当前上报目标；null = 非 Jellyfin 会话 / itemId 未解析 / 无痕命中（Task 17，本会话不上报） */
    private var jfReporter: JfReporter? = null

    /** 暂停即落库（C11 §2.2）：通知栏暂停/播完/出错停住时进度不丢 */
    private val pauseRecordListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val eng = engine ?: return
            if (!isPlaying) {
                if (!eng.player.isPlaying) historyWriter?.record(eng)
                // M4：暂停单发（服务器仪表盘转「已暂停」）
                jfReporter?.let { rep ->
                    val pos = eng.player.currentPosition.coerceAtLeast(0L)
                    serviceScope.launch { rep.progress(pos, isPaused = true) }
                }
            } else {
                // M4：恢复播放单发（IsPaused=false；心跳仅在播中报，此单发补「恢复」语义）
                jfReporter?.let { rep ->
                    val pos = eng.player.currentPosition.coerceAtLeast(0L)
                    serviceScope.launch { rep.progress(pos, isPaused = false) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Task 13：通知取词按应用内语言包装（一次性，见 notifContext KDoc）
        notifContext = NvAppLanguage.wrap(applicationContext)
        // J3（2026-09-23 审计）：媒体按钮续播/START_STICKY 重启等不经 PlayerActivity 的
        // 起播路径，prefsCache 未预热会让 avOffsetMs() 等快照读静默回退默认值
        warmPlayerPrefsCache()
        // 2026-09-28 审计 P0-2：播放中网络切计量的兜底暂停（见 registerMeteredWatch）
        registerMeteredWatch()
        Log.d("NvPerf", "PlaybackService onCreate")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val spec = pendingSpec.getAndSet(null)
            if (spec != null) {
                startPlaybackInternal(spec)
            } else if (engine == null && session == null) {
                // BUG-E-REGA-01 防御：重复/空启动且无任何播放内容——立即停掉，
                // 不悬挂等待前台化（悬挂会被系统以 ForegroundServiceDidNotStartInTimeException 杀进程）
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent == null && engine == null && session == null) {
            // START_STICKY 系统重启（null intent）且无会话可恢复：同理立即停掉，
            // 否则以前台语义重启的服务永不 startForeground → 超时崩溃
            stopSelf()
            return START_NOT_STICKY
        }
        // 非 media 按钮的自定义 action 走默认分支（返回 START_STICKY），session 管理不受影响
        return super.onStartCommand(intent, flags, startId)
    }

    // 不变量（spec §4.5，2026-10-11 终审）：新增装载入口必须二选一——有计量闸门
    // 背书（经确认或免确认档）或传 refreshBaseline=false；否则装载窗口内的网络
    // 切换会被基线吞掉（终审 Important-1）
    /** 由 ACTION_START 触发（主线程）：按规格构建/重建引擎并接入 session */
    private fun startPlaybackInternal(spec: EngineSpec, refreshBaseline: Boolean = true) {
        NvQSwitchTrace.mark("svc.startPlaybackInternal enter url=${spec.videoUrl.substringAfterLast('/')}")
        Log.d(
            "NvPerf",
            "PlaybackService startPlayback url=${spec.videoUrl} startAt=${spec.startPositionMs} " +
                "resumeSpeed=${spec.restoreSpeed} hadEngine=${engine != null}",
        )
        val writer = historyWriter ?: NvHistoryWriter(
            MediaDb.get(applicationContext),
            // 隐私分类门面（无痕跳写/锁定内容密文落盘）；单例 get，缺此参则通知侧心跳照写明文历史
            com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext),
        ).also { historyWriter = it }
        // 换片/重建（2026-09-15 文件夹队列切换）：旧片快照仍在挂载，先把旧引擎当前进度落库
        //（心跳 10s 内的尾差），再 attach 新片快照——顺序不能反，否则旧进度写进新片历史
        val oldEngine = engine
        oldEngine?.let { old ->
            writer.record(old)
            // H-2（2026-10-18 发布终审）：先摘 pauseRecordListener——本块 pause 会触发
            // onIsPlayingChanged(false)，而 writer 马上挂新片快照、jfReporter 马上换新会话，
            // 迟到回调会把旧引擎位置写进新片历史/给新会话上报错状态。此前依赖「release 后
            // 不再投递回调」的 media3 实现细节，S1 把 release 改延迟后该细节不再成立，显式摘除
            old.player.removeListener(pauseRecordListener)
            // S1（切集卡顿修复）：release 延迟后旧引擎会存活一小段，立即 pause 止声防与新
            // 引擎叠播（监听已摘，pause 零副作用；位置快照 record 已先行，pause 不改 currentPosition）
            runCatching { old.player.pause() }
            // M4：旧会话停播上报（换片/切档/自适应重建），与新会话 Start 一一配对；
            // 位置取此刻值（新引擎尚未挂载，writer.record 同款时序）
            jfReporter?.let { rep ->
                val pos = old.player.currentPosition.coerceAtLeast(0L)
                serviceScope.launch { rep.stopped(pos) }
            }
        }
        writer.attach(spec.history)

        val newEngine = PlaybackEngine(
            context = applicationContext,
            videoUrl = spec.videoUrl,
            startPositionMs = spec.startPositionMs,
            mediaTitle = spec.title,
            mediaSubtitle = spec.notificationSubtitle,
            mediaArtworkData = spec.artworkData,
            initialSubtitles = spec.initialSubtitles,
            initialMediaSource = spec.initialMediaSource,
            mediaSourceFactory = spec.mediaSourceFactory,
            novaxSource = spec.novaxSource,
            profileOverride = spec.profileOverride,
            httpTimeoutMs = spec.httpTimeoutMs,
            preferredAudioLanguage = spec.preferredAudioLanguage,
            preferredTextLanguages = spec.preferredTextLanguages,
            bufferLevel = spec.bufferLevel,
        )
        NvQSwitchTrace.mark("svc.engine built")
        // P2（重建不丢倍速）：恢复倍速在 Service 侧应用（引擎归 Service 所有）
        if (abs(spec.restoreSpeed - 1f) > 0.01f) {
            newEngine.setPlaybackSpeed(spec.restoreSpeed, pitch = 1f)
        }
        newEngine.player.addListener(pauseRecordListener)
        // 用户主动跳转 → 自适应策略忽略随之而来的起播缓冲（2026-09-15 收敛升档，见 NvAdaptivePolicy 类注释）
        // M3（Task 3 review 裁决 2）：AUTO 会话的用户 seek 同样必须豁免自动档——seek 之后的
        // 长缓冲是拖动的代价而非网络劣化证据，onUserAction 重置观测+冷却恰好是豁免语义；
        // 非 JELLYFIN 会话不喂（policy 未参与，零副作用）
        newEngine.onUserSeek = {
            adaptivePolicy.onUserSeek()
            if (currentSpec?.novaxSource?.type == SourceType.JELLYFIN) qualityAutoPolicy.onUserAction()
        }

        // 2026-10-11 误判修复（spec §4.1）：基线随「新目标装载」刷新；重建类入口传
        // refreshBaseline=false 不刷——rebuildAdaptive 无计量闸门，蜂窝上重快照会把
        // Wi-Fi 起播切蜂窝后的基线刷成 true，未确认的计量播放从此漏拦（评审一轮 P1-1）
        if (refreshBaseline) {
            playbackStartMetered = NvMeteredGuard.isMeteredNetwork(applicationContext)
        }
        // session 先切到新 player 再释放旧引擎（MediaSession.setPlayer 支持热切换，controller 无感）
        currentSpec = spec
        jellyfinEffectiveTier = if (spec.novaxSource?.type == SourceType.JELLYFIN) {
            // 实际在播转码 HLS 才记所选档；AUTO、「显式转码档被服务器回退原画」（此时
            // resolveJellyfinStream 返回 mediaSource=null）均记 ORIGINAL——显示与实际画质一致
            spec.jellyfinTier.takeIf { it != NvQualityTier.AUTO && spec.initialMediaSource != null }
                ?: NvQualityTier.ORIGINAL
        } else {
            null
        }
        // M4：新会话上报目标 + Start 单发（itemId 缺失 = 不上报，播放零影响）。
        // Task 17 无痕停服务器上报（单点判定）：命中无痕规则不创建 reporter——start/暂停恢复
        // 单发/换片 stopped/心跳/收口兜底 5 形态全部经 jfReporter 消费，创建点一处拦截即全覆盖；
        // 判定输入 = 当前播放项 (sourceId, path)，判定异常按无痕处理（fail toward more masking，
        // 同下方 covers 的 getOrDefault(true) 口径）
        val jfSource = spec.novaxSource?.takeIf { spec.jellyfinItemId != null }
        jfReporter = jfSource
            ?.takeIf {
                com.novax.app.data.privacy.NvGateWiring.jfReportAllowed(
                    spec.history.sourceId,
                    spec.history.path,
                    isIncognito = { sid, p ->
                        runCatching {
                            com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext)
                                .index.isIncognito(sid, p)
                        }.getOrDefault(true)
                    },
                )
            }
            ?.let { JfReporter(it, spec.jellyfinItemId!!, spec.jellyfinPlaySessionId) }
        jfReporter?.let { rep ->
            serviceScope.launch { rep.start(spec.startPositionMs) }
        }
        attachToSession(newEngine)
        // S1 回滚（2026-10-18 vivo 真机用户反馈）：延迟 500ms release 在真实场景是净回归——
        // 旧引擎解码器拆除与新引擎起播撞窗，切集明显卡顿（Fold 本地小文件冒烟未暴露）。恢复
        // 同步 release（用户实测基线「之前很流畅」）。保留：摘 pauseRecordListener（H-2 防旧
        // 位置写进新片历史）与先 pause 止声（摘听后 pause 零副作用）。
        NvQSwitchTrace.mark("svc.session attached; releasing old engine hadEngine=${oldEngine != null}")
        oldEngine?.release()
        engine = newEngine
        activeEngineFlow.value = newEngine
        startHistoryHeartbeat()
        maybeStartAdaptiveWatchdog(spec)
        NvQSwitchTrace.mark("svc.startPlaybackInternal exit")
    }

    // ---------------- 2026-09-15 播放中自适应降级/恢复 ----------------
    // LoadControl 构建后不可变：换档 = 以当前进度重建引擎（startPlaybackInternal 既有管线，
    // 历史幂等 upsert 不重复写）。升级敏感（弱网 60s 内 2 次 rebuffer 或单次卡 >8s），
    // 恢复保守（升级后连续 180s 无 rebuffer），动作间冷却 60s。

    /** 自适应仅对 SMB/WEBDAV 族档位有意义（SFTP/LOCAL 无远程档可升，审核 P1-3④） */
    private fun maybeStartAdaptiveWatchdog(spec: EngineSpec) {
        // M3（Task 3 review 裁决 3）：JELLYFIN 新播放会话必须新建 NvQualityAutoPolicy——
        // 其阶梯 current 无重置入口，跨会话复用会把上一会话的生效档带进来。判定与
        // adaptivePolicy.reset() 同口径（videoUrl 变化=换片）；同片重建（自适应降档/自动
        // 切档自身）URL 不变 → 保留实例与阶梯，否则自动档会从原画反复降档自激。
        if (spec.videoUrl != qualityUrl) {
            qualityAutoPolicy = NvQualityAutoPolicy(System::currentTimeMillis)
        }
        qualityUrl = spec.videoUrl
        adaptiveJob?.cancel()
        // 终审修复（2026-10-07）：JELLYFIN 会话显式排除旧 SMB 自适应（ESCALATE/RESTORE）。
        // 此前 profileOverride=WEBDAV_HTTP（HLS 线性流沿用的缓冲档）让它越过下方档位闸门
        // 形成双重监控：HLS 起播缓冲被记为 rebuffer → ESCALATE/RESTORE 按 SMB 式缓冲档
        // 重建转码会话 → HLS 画面冻结（音频/进度正常，转码分支经 C-1 首次可达后实证）。
        // JELLYFIN 的自适应归 qualityAutoPolicy（循环内专用分支，保持不变）；SMB/WEBDAV
        // 会话行为不变。
        val jellyfinSession = spec.novaxSource?.type == SourceType.JELLYFIN
        val base = spec.profileOverride ?: BufferProfile.of(spec.videoUrl)
        val legacyAdaptive = !jellyfinSession && (
            base == BufferProfile.SMB || base == BufferProfile.WEBDAV_HTTP ||
                base == BufferProfile.SMB_REMOTE || base == BufferProfile.WEBDAV_HTTP_REMOTE
            )
        if (!legacyAdaptive) {
            if (!jellyfinSession) {
                // SFTP/LOCAL 等无远程档（审核 P1-3④）：原样不起 watchdog
                adaptiveJob = null
                adaptiveUrl = null
                return
            }
            // JELLYFIN：仍要启动下方 watchdog 喂 qualityAutoPolicy，但循环内不喂/不打
            // adaptivePolicy（jellyfinSession 门控）。这里 reset() 清掉上个 SMB/WEBDAV 会话
            // 的残留观测（防陈旧状态泄漏）；adaptiveUrl 照常记录，后续回到 SMB/WEBDAV
            // 会话时 URL 变化仍走下方 reset+快照分支，旧语义不受影响
            adaptivePolicy.reset()
        } else if (spec.videoUrl != adaptiveUrl) {
            adaptivePolicy.reset() // 换片：清空升降级记忆；同片自适应重建：保留（否则会连环重建）
            adaptiveBaseProfile = base
            adaptiveBaseLevel = spec.bufferLevel
        }
        adaptiveUrl = spec.videoUrl
        adaptiveJob = serviceScope.launch {
            var watched: PlaybackEngine? = null
            while (isActive) {
                delay(500)
                val eng = engine ?: continue
                if (eng !== watched) {
                    watched = eng
                    if (!jellyfinSession) {
                        adaptivePolicy.onEngineSwapped() // 自适应重建/自动连播换引擎：清观测，决策保留
                    }
                    // M3：切档重建/自动连播同样给自动档开宽限窗（清观测，阶梯保留）
                    qualityAutoPolicy.onEngineSwapped()
                }
                val p = eng.player
                val state = p.playbackState
                val buffering = state == Player.STATE_BUFFERING
                // ENDED/IDLE（播完/出错）与暂停冻结判定并清计时，防止播完/暂停后无谓重建
                val frozen = state == Player.STATE_ENDED || state == Player.STATE_IDLE || !p.playWhenReady
                val playing = p.playWhenReady && (state == Player.STATE_READY || buffering)
                if (!jellyfinSession) {
                    adaptivePolicy.onStateChanged(buffering, playing, frozen)
                }
                // M3（2026-10-07）JELLYFIN 自动档：同源 500ms 观测喂自动档决策。
                // tick 实参 = 用户所选档位 spec.jellyfinTier（记忆值；AUTO = 自动档运行中，
                // 非 AUTO 档 tick 内部短路不决策——Task 3 review 裁决 1，别传生效档）。
                // 决策触发的切档不调 onUserAction（别重置它自己的冷却，冷却由 policy 自管）。
                val qSpec = currentSpec
                if (qSpec?.novaxSource?.type == SourceType.JELLYFIN) {
                    qualityAutoPolicy.onStateChanged(buffering, playing, frozen)
                    val qd = qualityAutoPolicy.tick(qSpec.jellyfinTier)
                    if (qd.action == NvQualityAutoPolicy.Action.DOWNGRADE ||
                        qd.action == NvQualityAutoPolicy.Action.UPGRADE
                    ) {
                        qd.target?.let { switchQualityInternal(it, fromUser = false) }
                    }
                }
                if (!jellyfinSession) {
                    when (val decision = adaptivePolicy.tick().action) {
                        NvAdaptivePolicy.Action.ESCALATE -> escalateAdaptive(eng, decision.name)
                        NvAdaptivePolicy.Action.RESTORE -> restoreAdaptive(eng, decision.name)
                        NvAdaptivePolicy.Action.NONE -> Unit
                    }
                }
            }
        }
    }

    /** 弱网降级：SMB/WEBDAV_HTTP → 对应 *_REMOTE（更保守的 rebuffer 门槛+更大前向缓冲）；
     *  已是远程档 → 保持档位、BufferLevel 提到 HIGH（前向缓冲拉满）。均已到顶则不动（防连环重建）。 */
    private fun escalateAdaptive(old: PlaybackEngine, why: String) {
        val spec = currentSpec ?: return
        val alreadyRemote = old.profile == BufferProfile.SMB_REMOTE || old.profile == BufferProfile.WEBDAV_HTTP_REMOTE
        // 目标档位必须显式传非空值：传 null 会让引擎按 scheme 回落到 LOCAL 档（审核 P1-2）
        val targetProfile = when (old.profile) {
            BufferProfile.SMB -> BufferProfile.SMB_REMOTE
            BufferProfile.WEBDAV_HTTP -> BufferProfile.WEBDAV_HTTP_REMOTE
            BufferProfile.SMB_REMOTE, BufferProfile.WEBDAV_HTTP_REMOTE -> old.profile
            else -> return
        }
        val targetLevel = if (alreadyRemote) BufferLevel.HIGH else spec.bufferLevel
        if (alreadyRemote && old.bufferLevel == BufferLevel.HIGH) {
            Log.d("NvPerf", "adaptive escalate skipped: already ${old.profile.name}+HIGH")
            return
        }
        rebuildAdaptive(old, targetProfile, targetLevel, "escalate($why)")
    }

    /** 网络恢复：回到起播时的原始档位与缓冲档（快照于 URL 变化时，而非被升级覆写过的 currentSpec） */
    private fun restoreAdaptive(old: PlaybackEngine, why: String) {
        val baseProfile = adaptiveBaseProfile ?: BufferProfile.of(old.videoUrl)
        // 顶档空升级（escalate 记了 upgraded 但档位/缓冲已到顶没实际动作）后 180s 的
        // RESTORE 目标==当前配置：跳过，避免一次纯开销的整引擎重建（BUG-C-R3-06）
        if (baseProfile == old.profile && adaptiveBaseLevel == old.bufferLevel) {
            Log.d("NvPerf", "adaptive restore skipped: already at base ${old.profile.name}/${old.bufferLevel.name}")
            return
        }
        rebuildAdaptive(old, baseProfile, adaptiveBaseLevel, "restore($why)")
    }

    /**
     * 以当前进度重建引擎（审核 P1-1/P1-2）：
     * - initialMediaSource 置 null（一次性对象不可复用），靠 mediaSourceFactory+videoUrl 重建；
     * - restoreSpeed 取旧引擎实时倍速（spec 里恒 1x，后台播放时 UI 不兜底）。
     */
    private fun rebuildAdaptive(old: PlaybackEngine, targetProfile: BufferProfile?, targetLevel: BufferLevel, why: String) {
        val spec = currentSpec ?: return
        if (old !== engine) return // 已换引擎，过期决策丢弃
        val position = old.player.currentPosition.coerceAtLeast(0L)
        val speed = old.player.playbackParameters.speed
        Log.d("NvPerf", "adaptive $why -> profile=${targetProfile?.name ?: old.profile.name} level=$targetLevel pos=$position")
        startPlaybackInternal(
            EngineSpec(
                videoUrl = spec.videoUrl,
                title = spec.title,
                startPositionMs = position,
                restoreSpeed = speed,
                history = spec.history,
                bufferLevel = targetLevel,
                httpTimeoutMs = spec.httpTimeoutMs,
                preferredAudioLanguage = spec.preferredAudioLanguage,
                preferredTextLanguages = spec.preferredTextLanguages,
                initialSubtitles = spec.initialSubtitles,
                initialMediaSource = null,
                mediaSourceFactory = spec.mediaSourceFactory,
                novaxSource = spec.novaxSource,
                profileOverride = targetProfile,
                // 2026-09-19：自适应重建必须透传队列——漏传则降级后通知切集静默失效（硬性验收项）
                queueUrls = spec.queueUrls,
                queueTitles = spec.queueTitles,
                queueIndex = spec.queueIndex,
                // M3：自适应重建只换缓冲档不换清晰度——必须透传记忆档位，漏传会回落 ORIGINAL，
                // watchdog 下一次 tick 的实参不再是 AUTO，自动档链条被一次弱网降级静默打断
                jellyfinTier = spec.jellyfinTier,
                // M4：同 jellyfinTier 的透传理由——JELLYFIN 会话当前不会走到本入口（watchdog 显式
                // 排除，escalate/restore 仅 SMB/WEBDAV），显式透传是防御：未来 watchdog 若放开
                // JELLYFIN，上报目标/转码会话 id 不得静默丢失（rebuildAdaptive 是全新具名构造、
                // 不是 copy，新字段默认 null 不透传——漏传即静默，必须显式写）
                jellyfinItemId = spec.jellyfinItemId,
                jellyfinPlaySessionId = spec.jellyfinPlaySessionId,
                jellyfinSourceBitrate = spec.jellyfinSourceBitrate,
                // 2026-10-10:通知栏封面/副文字透传——rebuildAdaptive 是全新具名构造(见上注释),
                // 漏传 = 弱网自动降档后封面静默消失(spec §4.3 / 第一轮评审 P0-1 专项)
                notificationSubtitle = spec.notificationSubtitle,
                artworkData = spec.artworkData,
            ),
            // 2026-10-11 误判修复（spec §4.1）：重建入口不刷基线——本函数无计量闸门，
            // 卡顿升级重建落在蜂窝上会把基线刷成 true，迁移后未确认的计量播放漏拦
            refreshBaseline = false,
        )
        // 重建本身会带来一段缓冲：开宽限窗，避免「重建→缓冲→判定网络差→再重建」自激
        adaptivePolicy.onEngineRebuilt()
    }

    // ---------------- M3（2026-10-07）JELLYFIN 清晰度切换（spec §6）----------------
    // 换流 = 以当前进度重建引擎并替换 mediaSource（initialMediaSource / mediaSourceFactory），
    // videoUrl 恒为 novax:// 逻辑键不变（真实流地址在 mediaSource 侧闭合）。Review Focus 1：
    // videoUrl 的相等性被三处守卫依赖（行号为 M3 前的原始位置）——
    //   ① PlaybackService.maybeStartAdaptiveWatchdog :188（adaptiveUrl 比较：同片重建保留记忆）
    //   ② PlaybackService.switchQueue :449（切集挂起期间内容被并发替换的陈旧意图裁决）
    //   ③ PlayerActivity.resyncWithServiceIfSwitched :1058（spec.videoUrl == last.url 引擎复用判定）
    // 本实现经 copy() 重建规格、不触碰 videoUrl，守卫天然成立；并显式 check 断言兜底——
    // 任何改写 videoUrl 的实现路径都是缺陷，要在第一时间暴露而不是静默击穿三处守卫。

    /** 切档在途协程（I-1 终审 last-intent 串行化）：后发意图取消前次解析，最后一次必胜 */
    private var switchQualityJob: Job? = null

    /** 切清晰度（UI 调用）：仅在 JELLYFIN 会话上有效；position/倍速保持 */
    private fun switchQualityInternal(tier: NvQualityTier, fromUser: Boolean) {
        val spec = currentSpec ?: return
        val eng = engine ?: return
        if (spec.novaxSource?.type != SourceType.JELLYFIN) return
        if (spec.jellyfinTier == tier) return
        val novax = spec.novaxSource ?: return
        // EngineSpec 不存源内路径：从 videoUrl（novax://<sourceId>/<path>）解析——恰因
        // videoUrl 是恒不变的逻辑键，它是可靠的路径来源；id 不匹配（异常状态）则放弃本次切档
        val path = NvUri.parse(spec.videoUrl)
            ?.takeIf { (sourceId, _) -> sourceId == novax.id }?.second ?: return
        // I-1（2026-10-07 终审）last-intent 串行化：快速连点两档时，此前每次独立 launch，
        // 先完成者胜、后发意图被静默作废。改为后发取消前次在途解析（resolveJellyfinStream
        // 与本协程 catch 均重抛 CancellationException，取消不吞），最后一次意图必胜。
        switchQualityJob?.cancel()
        switchQualityJob = serviceScope.launch {
            // I-2（2026-10-07 终审）计量网闸门：WiFi 起播→走进蜂窝→服务自动暂停→暂停态
            // 切档，此前 startPlaybackInternal 零确认直接在计量网起播（P0-2 违背）。服务侧
            // 无 UI 弹窗，照 switchQueue 的 Toast 拦截同款：never/「仅提醒一次」会话已
            // 确认 → 放行（豁免收口在 MeteredGate）；every → 计量网下阻断本次切档并
            // Toast。置于 onUserAction（冷却封窗）之前——被闸的
            // 意图不该留下任何副作用；也在 resolveJellyfinStream（PlaybackInfo 走网络）之前
            if (MeteredGate.needsConfirm(applicationContext, spec.videoUrl, novax.id to path)) {
                Toast.makeText(
                    applicationContext,
                    // Task 7 i18n：service context 长命，统一 applicationContext.getString 防泄漏
                    applicationContext.getString(R.string.player_metered_quality_paused_toast),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            // M3 review Important：手动切档必须**先**入冷却——resolveJellyfinStream 的 0.5~3s
            // 网络窗口内 watchdog 仍按旧 jellyfinTier(=AUTO) 每 500ms tick，若冷却等到
            // startPlaybackInternal 之后才进，窗口内的自动降/升档会覆盖用户刚下的意图。
            // 代价：解析失败也冷却 60s（可接受，下次切档即重置）。自动档触发（fromUser=false）
            // 不调 onUserAction——别重置它自己的冷却，规则不变（Task 3 review 裁决 1）
            if (fromUser) qualityAutoPolicy.onUserAction()
            try {
                // 换流解析走网络（PlaybackInfo）；解析期间引擎继续播旧流，位置取重建时刻值（漂移最小）。
                // 2026-10-10 终审修复：notification 此前漏传——切到转码档后 HLS MediaItem 零元数据，
                // 卡片退化光杆「NovaX」（spec §1 根因在转码 MediaItem 注入点）。copy 的 spec 四值
                // 齐备，与 Activity 起播的 qualityResolution 注入（PlayerActivity startPlayback）同口径
                val resolution = resolveJellyfinStream(
                    applicationContext,
                    novax,
                    path,
                    tier,
                    NvNotificationMetadata.build(
                        spec.title, spec.videoUrl, spec.notificationSubtitle, spec.artworkData,
                        notifContext.getString(R.string.player_video),
                        // Task 15 脱敏：命中锁/无痕规则 → 三元组脱敏（切转码档的 HLS MediaItem 同口径；
                        // 判定失败按脱敏处理——fail toward more masking）
                        runCatching {
                            com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext)
                                .index.covers(novax.id, path)
                        }.getOrDefault(true),
                        notifContext.getString(R.string.privacy_hidden_media),
                    ),
                )
                // 解析挂起期间引擎/规格已被替换（切集、Activity 重起播）：过期意图作废
                if (engine !== eng || currentSpec !== spec) return@launch
                val position = eng.player.currentPosition.coerceAtLeast(0L)
                val speed = eng.player.playbackParameters.speed
                val newSpec = spec.copy(
                    startPositionMs = position,
                    restoreSpeed = speed,
                    // fromUser（UI 手动切档）才覆写记忆档位；自动档决策触发的切档保持 AUTO，
                    // 否则下一次 watchdog tick 的实参不再是「用户所选档位」，自动档链条断裂（裁决 1）
                    jellyfinTier = if (fromUser) tier else spec.jellyfinTier,
                    initialMediaSource = resolution.mediaSource,
                    mediaSourceFactory = resolution.mediaSourceFactory,
                    // HLS 线性流沿用 WebDAV 缓冲档；回退原画保持原档（原语义不变，仅变量换形态）
                    profileOverride = if (resolution.mediaSource != null) BufferProfile.WEBDAV_HTTP else spec.profileOverride,
                    // M4：切档换转码会话 → playSessionId 随新 PlaybackInfo 更新；itemId 沿 copy 透传
                    jellyfinPlaySessionId = resolution.playSessionId,
                )
                // Review Focus 1 自检（编译面内断言，真机取证用）：见本区块头注释的三处守卫
                // （Task 7 i18n：message 会经下方 catch 的「切换失败：%1$s」Toast 露出，资源化）
                check(newSpec.videoUrl == spec.videoUrl) {
                    applicationContext.getString(R.string.player_err_check_videourl)
                }
                // 2026-10-11 误判修复（spec §4.1）：手动切档过 L474 闸门=过同意，刷基线；
                // 自动档(fromUser=false)同 rebuildAdaptive 不刷
                startPlaybackInternal(newSpec, refreshBaseline = fromUser)
                // M4：生效档以切档目标收敛（上面 startPlaybackInternal 已按其规则赋过一次，
                // 这里覆盖为本次切档目标——自动档降/升档同样要更新）。必须带 AUTO 守卫：
                // 手动切回 AUTO 时 startPlaybackInternal 已正确收敛为 ORIGINAL（AUTO 起播
                // 原画），无条件赋值会把生效档覆写成 AUTO、面板渲染出「自动（当前生效：自动）」
                // 的无效标签。
                if (tier != NvQualityTier.AUTO) jellyfinEffectiveTier = tier
                if (fromUser && tier == NvQualityTier.AUTO) {
                    // 手动转自动档：阶梯归位（AUTO 起播原画）——current 无重置入口，按裁决 3
                    // 以「新建实例」等价实现。置于解析成功后：失败则保留旧实例，阶梯仍与
                    // 实际在播的流一致（入口处的 onUserAction 已完成封窗职责）
                    qualityAutoPolicy = NvQualityAutoPolicy(System::currentTimeMillis)
                }
                Log.d("NvQuality", "switchQuality ${if (fromUser) "manual" else "auto"} -> $tier at $position")
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 本地 catch 收敛为 Toast（serviceScope 的 E5-1 兜底 handler 只落盘不提示）：
                // 切清晰度失败必须让用户可见（同 switchQueue）
                Log.w("NvError", "switchQuality failed", t)
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(
                        R.string.player_quality_switch_failed,
                        t.message ?: applicationContext.getString(R.string.player_unknown_error),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun attachToSession(newEngine: PlaybackEngine) {
        val existing = session
        if (existing == null) {
            sessionPlayerWrapper = queueCommandsPlayer(newEngine)
            sessionPlayerDelegate = newEngine.player
            session = MediaSession.Builder(this, sessionPlayerWrapper!!)
                .setSessionActivity(sessionActivityPendingIntent())
                .build()
        } else {
            // media3 1.8 ForwardingPlayer delegate 为 private final（无 setPlayer/getPlayer），
            // 热切换 = 新建 wrapper 后 session.setPlayer（与原直挂引擎的热切换同路径）；
            // delegate 另行登记做身份比较防重复切换
            if (sessionPlayerDelegate != newEngine.player) {
                sessionPlayerWrapper = queueCommandsPlayer(newEngine)
                sessionPlayerDelegate = newEngine.player
                existing.setPlayer(sessionPlayerWrapper!!)
            }
        }
    }

    /** 引擎 player → 通知/锁屏命令扩展 wrapper（无队列时不追加 seek 命令，按钮不出） */
    private fun queueCommandsPlayer(newEngine: PlaybackEngine): QueueCommandsPlayer =
        QueueCommandsPlayer(
            newEngine.player,
            // spec 2026-10-12 §5.2 第 6 行「无队列时 ≤2 键」：单文件播放 queueUrls.size==1
            // 不算可切换队列——此前 isNotEmpty() 把单元素队列也判真，COMMAND_SEEK_TO_NEXT
            // 被追加、通知多出「下一集」键（点按必弹「已是最后一个」Toast）；size>1 才追加。
            // 无队列时的「上一集」键由底层 player 的 COMMAND_SEEK_TO_PREVIOUS（position>3s）
            // 自然出没，恰为 spec「上一集仅播放 >3s 后出现」口径。
            canSwitch = { (currentSpec?.queueUrls?.size ?: 0) > 1 },
            onSwitch = { switchQueue(it) },
        )

    /**
     * 2026-09-19：通知栏/锁屏「上/下集」（service 侧队列切换，media3 官方模式）。
     * 关键覆写：media3 1.8 `MediaSessionLegacyStub.onSkipToNext` 先查
     * `isCommandAvailable(COMMAND_SEEK_TO_NEXT)`，不可用则改派 `seekToNextMediaItem()`
     * （单 Item 无下一窗口=静默 no-op）；而 common ForwardingPlayer.isCommandAvailable
     * 直接透传被包者、不看被覆写的 getAvailableCommands——**两者必须同时覆写**，
     * 否则按钮显示但点了没反应。
     */
    private class QueueCommandsPlayer(
        player: Player,
        private val canSwitch: () -> Boolean,
        private val onSwitch: (Int) -> Unit,
    ) : ForwardingPlayer(player) {

        private fun extraCommand(command: Int): Boolean =
            canSwitch() && (
                command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS
                )

        override fun getAvailableCommands(): Player.Commands {
            val cmds = super.getAvailableCommands()
            if (!canSwitch()) return cmds
            return cmds.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            super.isCommandAvailable(command) || extraCommand(command)

        override fun seekToNext() = onSwitch(1)
        override fun seekToPrevious() = if (canSwitch()) onSwitch(-1) else super.seekToPrevious()
        override fun seekToNextMediaItem() = onSwitch(1)
        override fun seekToPreviousMediaItem() = if (canSwitch()) onSwitch(-1) else super.seekToPreviousMediaItem()
    }

    /** 队列切换在途标志（审核建议：通知栏快速双击上/下集防重复重建/双 Toast） */
    @Volatile
    private var switchingQueue = false

    /**
     * 队列切换（通知/锁屏触发）：目标为 RMVB 或带软解路由记忆 → 沿方向自动跳到下一个可由
     * 服务承载的集（libVLC 引擎在 Activity 内，后台无宿主；只 Toast 会被系统在后台抑制，
     * 用户感知是「点了没反应」——实操 BUG 换集链路-A）；全队列不可承载则保留原集并尽力提示。
     * 其余目标按与 Activity 相同的管线（字幕扫描/转码/续播裁决）组规格走 startPlaybackInternal。
     */
    private fun switchQueue(direction: Int) {
        val spec = currentSpec ?: return
        if (spec.queueUrls.isEmpty()) return
        if (switchingQueue) return
        val requested = spec.queueIndex + direction
        if (requested < 0 || requested >= spec.queueUrls.size) {
            Toast.makeText(
                applicationContext,
                applicationContext.getString(
                    if (direction < 0) R.string.player_queue_first else R.string.player_queue_last,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        switchingQueue = true
        serviceScope.launch {
            try {
                suspend fun softRouteOf(u: String): String? = runCatching {
                    // 软解路由记忆直接读 DataStore（prefsCache 可能未预热——审核 E 项建议）
                    // 2026-09-29：「音轨」类记忆读侧忽略（FFmpeg 音频升级后必然错误，与
                    // PlayerActivity 读侧同口径）；视频类记忆（杜比视界画面）继续生效
                    playerPrefsStore.data.first()[stringPreferencesKey(SOFT_ROUTE_KEY_PREFIX + u)]
                        ?.takeUnless { isSoftRouteAudioReason(it) }
                }.getOrNull()

                // 目标不可承载（RMVB/软解记忆）→ 沿方向跳过到下一个可承载项
                var target = requested
                while (target in spec.queueUrls.indices) {
                    val u = spec.queueUrls[target]
                    if (!RmvbPlayerActivity.isFallbackTarget(u) && softRouteOf(u) == null) break
                    target += direction
                }
                if (target !in spec.queueUrls.indices) {
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.player_adjacent_soft_route_toast),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                val skippedCount = abs(target - requested)
                val url = spec.queueUrls[target]
                val title = spec.queueTitles.getOrNull(target)?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/')
                val novaxTarget = if (url.startsWith(NvUri.SCHEME + "://")) NvUri.parse(url) else null
                // 隐私切集闸（隐私计划 Task 12，映射核 NvGateWiring.serviceSwitchGate）：目标集
                // 已锁 → 停连播 + 「内容已上锁」通知拉回 PlayerActivity 出解锁面板。先于计量闸
                // 与 prepareEpisodeMedia（锁定内容一次网络读都不能有）。
                // **绝不后台 recreate/startActivity**（后台启动限制 + 无 surfaced UI）——拉回
                // 复用 sessionActivityPendingIntent 同款 CLEAR_TOP|SINGLE_TOP 形态，PlayerActivity
                // onResume 再闸盖回面板。
                // 终审 C2：LOCAL 裸路径目标先反解归属源，命中仍过闸
                val viewTarget = novaxTarget ?: runCatching {
                    if (url.startsWith("/")) {
                        com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext)
                            .resolveViewPath(null, url)
                    } else null
                }.getOrNull()
                val privacyAllowed = viewTarget == null || runCatching {
                    com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext)
                        .startAllowed(viewTarget.first, viewTarget.second)
                }.getOrDefault(false)
                if (com.novax.app.data.privacy.NvGateWiring.serviceSwitchGate(
                        viewTarget != null,
                        privacyAllowed,
                    ) != com.novax.app.data.privacy.NvGateWiring.GateAction.PROCEED
                ) {
                    Log.w("NvPrivacy", "service switch gated: target locked, auto-advance stopped")
                    notifyContentLocked()
                    return@launch
                }
                // 隐私计划 Task 15：命中锁/无痕规则 → 通知三元组脱敏（与解锁态无关，covers 口径；
                // 判定失败按脱敏处理——fail toward more masking）。直连裸路径经 LOCAL 反解
                // （终审 C2），真外部文件不属于任何源规则恒不命中。占位标题按应用语言资源预解析，
                // 三个 MediaItem 构建点同口径。
                val sanitizeTarget = viewTarget != null && runCatching {
                    com.novax.app.data.privacy.NvPrivacyAccess.get(applicationContext)
                        .coversView(viewTarget.first, viewTarget.second)
                }.getOrDefault(true)
                val hiddenMediaTitle = notifContext.getString(R.string.privacy_hidden_media)
                // 2026-09-28 审计 P0-2：通知/锁屏切集也是一次起播——服务侧无 UI 弹窗，
                // never/「仅提醒一次」会话已确认 → 放行（豁免收口在 MeteredGate）；
                // every → 计量网下阻断本次切集（Wi-Fi 起播后切蜂窝、后台听剧点下一集的
                // 兜底防线）。必须在 prepareEpisodeMedia
                //（字幕扫描/转码走网络读）之前拦
                if (MeteredGate.needsConfirm(applicationContext, url, novaxTarget)) {
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.player_metered_autoswitch_paused_toast),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val prefs = runCatching { playerPrefsStore.data.first() }.getOrNull()
                // 2026-09-28 审计 A 收敛：续播裁决统一走 EpisodeMediaKit——补时长参数，
                // 后台切集同样获得「距片尾 ≤10s 视为看完」规则（此前只有 RMVB 页私有实现有）
                val resumeKey = novaxTarget?.second ?: url
                val resumeDur = episodeResumeDurationMs(applicationContext, novaxTarget?.first, resumeKey)
                val resumePos = episodeResumePositionMs(
                    applicationContext,
                    novaxTarget?.first,
                    resumeKey,
                    prefs?.get(KEY_RESUME_ON_OPEN) ?: true,
                    resumeDur,
                )
                // 字幕延迟全局单例随集刷新（与 Activity 起播同语义）
                NvSubtitleOffset.delayMs = prefs?.get(KEY_SUB_DELAY_MS) ?: 0L
                val prepared = prepareEpisodeMedia(
                    context = applicationContext,
                    url = url,
                    novax = novaxTarget,
                    title = title,
                    autoLoad = prefs?.get(KEY_SUB_AUTO_LOAD) ?: true,
                    encodingPref = prefs?.get(KEY_SUB_ENCODING),
                    fallbackTitle = notifContext.getString(R.string.player_video),
                    sanitize = sanitizeTarget,
                    hiddenMediaTitle = hiddenMediaTitle,
                )
                if (prepared == null) {
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.player_err_source_gone),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                // M4：Jellyfin 目标按记忆档位解析转码会话 + 上报目标（与 Activity 起播同口径；
                // 顺带修复 M3 遗留——通知栏切集此前未传 jellyfinTier，恒回落原画）。
                // withTimeoutOrNull = 挂起边界尽力收口（硬上界为 socket 超时，见 Task 3 KDoc）
                var jfTier = NvQualityTier.ORIGINAL
                var jfResolution: JellyfinStreamResolution? = null
                var jfTargetId: String? = null
                var notificationSubtitle: String? = null
                var artworkData: ByteArray? = null
                val jfSource = prepared.source?.takeIf { it.type == SourceType.JELLYFIN }
                if (jfSource != null && novaxTarget != null) {
                    jfTier = NvQualityTier.fromName(prefs?.get(KEY_PLAYBACK_QUALITY))
                    jfTargetId = withTimeoutOrNull(8_000) {
                        runCatching { NvGraph.repository().jellyfinItemIdOf(jfSource, novaxTarget.second) }.getOrNull()
                    }
                    // 2026-10-10:通知栏封面/副文字(spec §4.2/§4.3)——JELLYFIN 串行在 itemId 之后
                    // (session 已暖,authenticate 短路);换集链冷缓存双 5s 独立收窄(connect+read
                    // 各 5s 独立生效),单次尝试,最坏 ~10s,失败 null 兜底不阻塞切集成功
                    notificationSubtitle = NvNotificationMetadata.subtitle(
                        target, spec.queueUrls.size,
                        notifContext.getString(R.string.notify_queue_subtitle, target + 1, spec.queueUrls.size),
                        jfSource.nickname,
                    )
                    // Task 15：脱敏集不取封面（notif_art 缓存文件也不落）
                    artworkData = if (sanitizeTarget) null else runCatching {
                        resolveNotificationArtwork(applicationContext, jfSource, novaxTarget.second, jfTargetId)
                    }.getOrNull()
                    if (jfTargetId != null && jfTier != NvQualityTier.ORIGINAL) {
                        jfResolution = withTimeoutOrNull(8_000) {
                            resolveJellyfinStream(
                                applicationContext, jfSource, novaxTarget.second, jfTier,
                                NvNotificationMetadata.build(
                                    title, url, notificationSubtitle, artworkData,
                                    notifContext.getString(R.string.player_video),
                                    sanitizeTarget, hiddenMediaTitle,
                                ),
                            )
                        }
                    }
                } else {
                    // 非 JELLYFIN:已索引 novax 源按源内路径本地解析;LOCAL 队列是裸绝对路径
                    // (novaxTarget == null)——反查已索引条目出封面/源昵称(2026-10-10 补丁 B,
                    // 纯本地 DB 零网络);查无 = resolver 落空 = app 图标垫底(补丁 A)
                    val directTarget = if (novaxTarget == null) {
                        url.takeIf { it.startsWith("/") }?.let {
                            runCatching { resolveLocalDirectTarget(applicationContext, it) }.getOrNull()
                        }
                    } else {
                        null
                    }
                    notificationSubtitle = NvNotificationMetadata.subtitle(
                        target, spec.queueUrls.size,
                        notifContext.getString(R.string.notify_queue_subtitle, target + 1, spec.queueUrls.size),
                        directTarget?.first?.nickname ?: prepared.source?.nickname,
                    )
                    // Task 15：脱敏集不取封面（notif_art 缓存文件也不落）
                    artworkData = if (sanitizeTarget) null else runCatching {
                        if (directTarget != null) {
                            resolveNotificationArtwork(applicationContext, directTarget.first, directTarget.second, null,
                                absPath = url.takeIf { it.startsWith("/") })
                        } else {
                            resolveNotificationArtwork(applicationContext, prepared.source, novaxTarget?.second, null,
                                // novax 分支:仅 LOCAL 推导(根+相对路径);远程源不进 4/5 级
                                absPath = prepared.source?.takeIf { s -> s.type == SourceType.LOCAL }
                                    ?.let { s -> novaxTarget?.second?.let { p -> runCatching { File(s.shareOrPath, p).path }.getOrNull() } })
                        }
                    }.getOrNull()
                }
                // 切集挂起期间内容被并发替换（Activity 新起播）：陈旧意图作废（BUG-C-R2-05）。
                // 自适应重建只换档不换内容（videoUrl 不变），不会误伤。
                if (currentSpec?.videoUrl != spec.videoUrl) return@launch
                // 2026-10-10 终审修复：非转码路（jfResolution?.mediaSource == null）此前直接消费
                // prepared.initialMediaSource——那是 prepareEpisodeMedia 未传元数据时的产物，
                // MediaMetadata 只有 title，EngineSpec 里的封面/副文字永不被引擎消费（全部
                // SMB/LOCAL/WebDAV/SFTP 队列 + JELLYFIN 原画/转码被服务器回退的换集丢封面）。
                // 元数据已在上方解析完毕，这里原地重建 MediaSource 补齐（纯内存组装零 IO）；
                // 转码 HLS 路的元数据已在 resolveJellyfinStream 注入，不重建。
                val metadataSource = if (jfResolution?.mediaSource == null && novaxTarget != null) {
                    prepared.source?.let {
                        buildNovaxMediaSource(
                            it,
                            novaxTarget.second,
                            NvGraph.repository(),
                            prepared.subtitleConfigs,
                            title,
                            notificationSubtitle,
                            artworkData,
                            notifContext.getString(R.string.player_video),
                            sanitizeTarget, hiddenMediaTitle,
                        )
                    }
                } else {
                    null
                }
                val prev = currentSpec
                startPlaybackInternal(
                    EngineSpec(
                        videoUrl = url,
                        title = title,
                        startPositionMs = resumePos,
                        // 保持当前倍速跨集连续
                        restoreSpeed = engine?.player?.playbackParameters?.speed ?: 1f,
                        history = buildEpisodeHistorySnapshot(
                            prepared.source?.id,
                            prepared.source?.type?.name,
                            novaxTarget?.second ?: url,
                            title,
                            resumePos,
                        ),
                        bufferLevel = prev?.bufferLevel ?: BufferLevel.from(prefs?.get(KEY_BUFFER_LEVEL)),
                        httpTimeoutMs = prev?.httpTimeoutMs ?: DEFAULT_NET_TIMEOUT_SEC * 1_000,
                        preferredAudioLanguage = prev?.preferredAudioLanguage ?: (prefs?.get(KEY_AUDIO_LANG) ?: "auto"),
                        preferredTextLanguages = prev?.preferredTextLanguages
                            ?: (prefs?.get(KEY_SUB_LANGUAGES) ?: "zh")
                                .split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        initialSubtitles = prepared.subtitleConfigs,
                        initialMediaSource = jfResolution?.mediaSource ?: metadataSource ?: prepared.initialMediaSource,
                        mediaSourceFactory = jfResolution?.mediaSourceFactory ?: prepared.mediaSourceFactory,
                        novaxSource = prepared.source,
                        // 转码会话换 HLS 线性流缓冲档（与 Activity 起播同口径）；原画保持探测结果
                        profileOverride = if (jfResolution?.mediaSource != null) BufferProfile.WEBDAV_HTTP else prepared.profileOverride,
                        // M4 + M3 遗留：记忆档位与上报目标（此前缺失 → 通知栏切集恒原画且不上报）
                        jellyfinTier = jfTier,
                        jellyfinItemId = jfTargetId,
                        jellyfinPlaySessionId = jfResolution?.playSessionId,
                        // 2026-10-10:通知栏封面/副文字(四处构造点全具名口径;漏一处=换集丢封面)
                        notificationSubtitle = notificationSubtitle,
                        artworkData = artworkData,
                        queueUrls = spec.queueUrls,
                        queueTitles = spec.queueTitles,
                        queueIndex = target,
                    ),
                )
                Toast.makeText(
                    applicationContext,
                    if (skippedCount > 0) {
                        applicationContext.getString(R.string.player_skipped_soft_items_toast, skippedCount, title)
                    } else {
                        applicationContext.getString(R.string.player_switched_to_toast, title)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 本地 catch 收敛为 Toast（serviceScope 的 E5-1 兜底 handler 只落盘不提示）：
                // 后台切集失败必须收敛为提示（BUG-C-R2-01）
                Log.w("NvError", "switchQueue failed", t)
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(
                        R.string.player_err_switch_failed,
                        t.message ?: applicationContext.getString(R.string.player_unknown_error),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                switchingQueue = false
            }
        }
    }

    /** 通知栏/锁屏点按回到播放器：sessionActivity 在首次建 session 时固定，故只带标记 extra，
     *  具体播放内容由 PlayerActivity.onCreate 经 [activeSpec] 解析（切片后通知栏重进不错位）。
     *  2026-10-12 叠加 bug：CLEAR_TOP|SINGLE_TOP + 清单 singleTop 让重进交付给栈顶既有实例
     *  的 onNewIntent，不再在旧实例上再压一个全新播放页（压扁画面的第二实例） */
    private fun sessionActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(PlayerActivity.EXTRA_FROM_MEDIA_NOTIFICATION, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 隐私闸「内容已上锁」通知的独立 id（区别于 media3 媒体通知）与渠道 */
    private val contentLockedNotifId = 3001

    /**
     * 「内容已上锁」通知（隐私计划 Task 12）：通知栏切集撞上已锁目标时发——点按经
     * [sessionActivityPendingIntent] 拉回 PlayerActivity，onResume 再闸盖回解锁面板。
     * 一次性通知（autoCancel，非 ongoing）；通知失败静默（W 级留痕），连播已停是硬保证。
     */
    private fun notifyContentLocked() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                PRIVACY_LOCK_CHANNEL_ID,
                notifContext.getString(R.string.privacy_locked_notif_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
            nm?.createNotificationChannel(channel)
            val notification: Notification =
                NotificationCompat.Builder(notifContext, PRIVACY_LOCK_CHANNEL_ID)
                    .setSmallIcon(R.drawable.media3_notification_small_icon)
                    .setContentTitle(notifContext.getString(R.string.privacy_locked_notif_title))
                    .setContentText(notifContext.getString(R.string.privacy_locked_notif_text))
                    .setContentIntent(sessionActivityPendingIntent())
                    .setAutoCancel(true)
                    .build()
            nm?.notify(contentLockedNotifId, notification)
        }.onFailure {
            Log.w("NvPrivacy", "content locked notification failed: ${it.javaClass.simpleName}")
        }
    }

    /** C11 §2.2 ①心跳：每 10s，仅 isPlaying 时写（后台播放时历史仍在累计） */
    private fun startHistoryHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(10_000)
                val eng = engine ?: continue
                historyWriter?.recordIfPlaying(eng)
                // M4：服务器进度心跳（仅播中；暂停态由 pauseRecordListener 单发，不在此刷屏）。
                // fire-and-forget：launch 不 await，慢服务器不拖慢心跳循环
                jfReporter?.let { rep ->
                    if (eng.player.isPlaying) {
                        val pos = eng.player.currentPosition.coerceAtLeast(0L)
                        val dur = eng.player.duration.takeIf { it > 0 } ?: 0L
                        serviceScope.launch { rep.progress(pos, isPaused = false, durationMs = dur) }
                    }
                }
            }
        }
    }

    /** M4：收口路径（显式退出/移交兜底/服务销毁）的停播上报——NonCancellable 进入网络段后
     *  不被 serviceScope.cancel 掐断；一次性：捕获目标后即清 jfReporter，防 exitUiHost 与
     *  onDestroy 双重 Stopped。（withContext 全限定——PlaybackService 现无该 import） */
    private fun reportStoppedBestEffort(eng: PlaybackEngine) {
        val rep = jfReporter ?: return
        jfReporter = null
        val pos = eng.player.currentPosition.coerceAtLeast(0L)
        serviceScope.launch {
            kotlinx.coroutines.withContext(
                kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO,
            ) {
                runCatching { rep.stopped(pos) }
            }
        }
    }

    /**
     * PlayerActivity 显式退出（onDestroy isFinishing）时的收口：
     * 记录进度；一律暂停并停服务（2026-09-20 用户需求修正：本入口只来自「Activity 显式退出」
     * = 返回键/顶栏返回的 isFinishing 收口——应用内返回退出播放器就该停；「回桌面/锁屏继续播」
     * 由 PlayerActivity.onStop 的后台播放分支保留，非 finishing 的 onStop 不会走到这里）。
     */
    fun exitUiHost() {
        val eng = engine ?: return
        historyWriter?.record(eng)
        reportStoppedBestEffort(eng)
        Log.d("NvPerf", "PlaybackService exitUiHost -> pauseAndStop (explicit finish)")
        pauseAllPlayersAndStopSelf()
    }

    /**
     * 隐私重锁的暂停路径（隐私计划 Task 11）：逐 session 暂停（与 [pauseAllPlayersAndStopSelf]
     * 同款口径，media3 的 pause 经 wrapper 透传到引擎），但**不 stopSelf 不记进度**——服务与
     * 通知保留（用户解锁后可继续），进度由既有暂停/心跳回调自行落库。
     */
    private fun pauseAllPlayersNoStop() {
        session?.player?.let { p -> runCatching { if (p.isPlaying) p.pause() } }
        engine?.player
            ?.takeIf { it !== session?.player }
            ?.let { p -> runCatching { if (p.isPlaying) p.pause() } }
    }

    /** C10 novax_background_play（默认 false）：true = 退后台/锁屏继续播 + 通知栏 */
    private fun backgroundPlayEnabled(): Boolean =
        runCatching { playerPrefsSnapshot()?.get(KEY_BACKGROUND_PLAY) ?: false }.getOrDefault(false)

    /** 移交兜底内核前的收口：无视后台播放开关，进度落库 + 暂停并停服（BUG-C-R9-02/MG-B） */
    private fun exitUiHostForHandOff() {
        val eng = engine ?: return
        historyWriter?.record(eng)
        reportStoppedBestEffort(eng)
        Log.d("NvPerf", "PlaybackService handOffToFallback -> pauseAndStop")
        pauseAllPlayersAndStopSelf()
    }

    /**
     * 任务移除（划掉最近任务）：默认行为=在播则保留前台服务、否则暂停并自停。
     * 后台播放关闭时一律「暂停并停服务」，与既有「划掉即停」语义一致。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (backgroundPlayEnabled()) {
            super.onTaskRemoved(rootIntent)   // 在播中 → 继续后台播放；已暂停 → 停
        } else {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        Log.d("NvPerf", "PlaybackService onDestroy")
        meteredPauseJob?.cancel()
        meteredNetCallback?.let { cb ->
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.let { cm -> runCatching { cm.unregisterNetworkCallback(cb) } }
        }
        meteredNetCallback = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        adaptiveJob?.cancel()
        adaptiveJob = null
        instance = null
        engine?.let { historyWriter?.record(it); reportStoppedBestEffort(it) }
        session?.release()
        session = null
        sessionPlayerWrapper = null
        sessionPlayerDelegate = null
        engine?.release()
        engine = null
        currentSpec = null
        jfReporter = null
        jellyfinEffectiveTier = null
        activeEngineFlow.value = null
        // 2026-10-11 流量提醒三档（spec §4.5 双钩子之二）：服务销毁=引擎释放，若前台
        // 也已空（先退后台再被回收/onTaskRemoved 后台播放关停服），流量确认会话终结
        NvApp.maybeEndMeteredSession(false)
        serviceScope.cancel()   // 2026-09-16 审计 P2：服务销毁后不再留任何在途协程
        super.onDestroy()
    }

    // ---------------- 2026-09-28 审计 P0-2：播放中网络切计量的兜底暂停 ----------------
    // 起播闸门只拦「起播」；Wi-Fi 起播后中途切到蜂窝/计量热点，引擎会带着原流继续拉完半部片
    // （此前全工程没有任何播放会话的网络变化监听）。3s 去抖：onCapabilitiesChanged 对 Wi-Fi
    // 瞬断/双网卡（蜂窝事件先到、活动网络仍是 Wi-Fi）有误报，延迟后以 isActiveNetworkMetered
    // （活动网络）复核为准。仅远端源暂停（本地源无流量后果）。
    // 2026-10-11 误判修复（spec §4.1）：3s 复核只回答「现在是否计量」，基线
    // playbackStartMetered 回答「起播时是否计量」——两者相与才是真迁移；计量确认
    // 起播（基线 true）不拦，消除「确认后 3 秒被误暂停」。
    private var meteredNetCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var meteredPauseJob: Job? = null

    /** 本次 playback 会话起播瞬间活动网络是否计量（spec §4.1）：true = 起播时已计量
     *  （必经起播闸门确认或免确认档），看门狗全程不拦。默认 false 宁拦勿放——engine
     *  只由 startPlaybackInternal 创建，读取前必有同会话快照，默认值实际不可达。 */
    private var playbackStartMetered: Boolean = false

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
        meteredPauseJob = serviceScope.launch {
            kotlinx.coroutines.delay(3_000)
            if (!NvMeteredGuard.isMeteredNetwork(applicationContext)) return@launch
            val eng = engine ?: return@launch
            val spec = currentSpec ?: return@launch
            val remote = if (spec.novaxSource != null) spec.novaxSource.type != SourceType.LOCAL
            else spec.videoUrl.startsWith("http")
            // H-3（2026-10-18 发布终审）：闸门从 isPlaying 放宽到「有播生意图且正在消耗数据」
            // ——isPlaying 在 READY+未抑制才为真，起播/seek 后的 BUFFERING 窗口（playWhenReady=
            // true）isPlaying=false：SMB/大文件缓冲期恰是计量流量被拉数十 MB 的窗口，旧闸全程
            // 失防；且 onCapabilitiesChanged 只在网络事件再触发，进入 READY 后本会话防护不会
            // 补判。READY（在播）与 BUFFERING（在灌）都该拦；IDLE（无媒体）/ENDED（不再拉流）
            // 不拦。RMVB 页同口径修复见 RmvbPlayerActivity.onActiveNetworkMaybeMetered
            val consuming = eng.player.playWhenReady &&
                (eng.player.playbackState == Player.STATE_BUFFERING || eng.player.playbackState == Player.STATE_READY)
            if (remote && !playbackStartMetered && consuming) {
                eng.player.pause()
                Log.d("NvPerf", "metered network switch -> auto pause ${spec.videoUrl}")
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(R.string.player_metered_paused_toast),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * 本次起播规格（Activity 异步准备完成后经 [pendingSpec] 交接；同进程，不可 parcel 亦可）：
     * 与 PlaybackEngine 构造参数一一对应，另带历史登记快照与恢复倍速。
     * data class（2026-10-07）：switchQuality 需要 copy() 做换档重建（进度/倍速保持只换流，
     * 其余字段原样透传）；此前为普通类，现有四处构造点全部具名参数，转换无行为影响。
     */
    data class EngineSpec(
        val videoUrl: String,
        val title: String,
        val startPositionMs: Long,
        val restoreSpeed: Float,
        val history: NvHistoryWriter.Snapshot,
        val bufferLevel: BufferLevel,
        val httpTimeoutMs: Int,
        val preferredAudioLanguage: String?,
        val preferredTextLanguages: List<String>,
        val initialSubtitles: List<MediaItem.SubtitleConfiguration>,
        val initialMediaSource: MediaSource?,
        val mediaSourceFactory: MediaSource.Factory?,
        val novaxSource: SourceEntity?,
        val profileOverride: BufferProfile?,
        /** 2026-09-15 引入、2026-10-25 移除（外部审查整改 spec §3）：AudioTrack 编码直通已停用
         *  （2026-09-22，音频路径固定 PCM，见 NvRenderersFactory 头注释）；设置页置灰开关
         *  仅展示 novax_audio_passthrough 存储值，不再进入会话规格与引擎。 */
        /** 2026-09-19：文件夹队列（通知栏/锁屏上/下集用）；空 = 不可切（命令不追加） */
        val queueUrls: List<String> = emptyList(),
        val queueTitles: List<String> = emptyList(),
        val queueIndex: Int = -1,
        /**
         * M3（2026-10-07）：JELLYFIN 会话的用户所选档位（AUTO = 自动档运行中）。
         * 只作状态记录、自动档 tick 实参（记忆值）与 UI 展示，**不参与任何 URL 比较**——
         * videoUrl 恒为 novax:// 逻辑键，真实流地址在 mediaSource(initialMediaSource) /
         * mediaSourceFactory 侧闭合（见 switchQuality 的守卫注释）。
         */
        val jellyfinTier: NvQualityTier = NvQualityTier.ORIGINAL,
        /**
         * M4（2026-10-07）：JELLYFIN 会话的服务器进度上报目标。itemId 解析失败（冷索引 + walk
         * 失败）= null = 本会话不上报；playSessionId 仅转码会话非空。均只作上报状态记录，
         * **不参与任何 URL 比较**（videoUrl 恒 novax:// 逻辑键，见 jellyfinTier 注释）。
         * 切档重建（switchQuality）经 copy() 原样透传 itemId、覆写 playSessionId。
         */
        val jellyfinItemId: String? = null,
        val jellyfinPlaySessionId: String? = null,
        /**
         * 2026-10-08：JELLYFIN 片源视频码率 bps（起播时随内嵌字幕发现一并取得；未知 0 =
         * 清晰度面板全部显示，行为同旧版）。供面板隐藏「档位 ≥ 源码率」的重复项
         * （NvQualityTier.availableTiers——该形态下服务器本就不转码，原画即该档最佳）。
         * 只作 UI 判据，不参与任何 URL 比较；切档重建经 copy() 原样透传。
         */
        val jellyfinSourceBitrate: Long = 0,
        /**
         * 2026-10-10:通知栏卡片副文字(「第X集 · 共Y集」/ 源昵称;null=不显示 artist 行)。
         * **四处构造点必须显式传值**——rebuildAdaptive 是全新具名构造,新字段默认 null 不透传
         * (本文件 rebuildAdaptive 内注释同款陷阱,spec §4.3);switchQuality 切档走 copy() 天然透传。
         */
        val notificationSubtitle: String? = null,
        /** 2026-10-10:通知栏封面归一化字节(≤480px JPEG ≤64KB;null=app 图标兜底)。透传口径同上 */
        val artworkData: ByteArray? = null,
    )

    companion object {
        private const val ACTION_START = "com.novax.app.player.action.START_PLAYBACK"

        /** 隐私闸「内容已上锁」通知渠道（Task 12；minSdk 26 恒需注册 NotificationChannel） */
        private const val PRIVACY_LOCK_CHANNEL_ID = "novax_privacy_lock"

        /** 进程内规格槽（同进程交接；Service 消费后清空，最新一次 start 覆盖未消费的旧值） */
        private val pendingSpec = AtomicReference<EngineSpec?>(null)

        /** 服务内活跃引擎（PlayerActivity 观察/直读；服务销毁即置 null） */
        val activeEngineFlow = MutableStateFlow<PlaybackEngine?>(null)
        val activeEngine: PlaybackEngine? get() = activeEngineFlow.value

        @Volatile
        private var instance: PlaybackService? = null

        /** Activity 起播/重试入口：登记规格并启动服务（Activity 在前台调用，无后台启动限制） */
        fun start(context: Context, spec: EngineSpec) {
            pendingSpec.set(spec)
            context.startService(
                Intent(context, PlaybackService::class.java).setAction(ACTION_START),
            )
        }

        /** 活跃引擎是否正在播放/缓冲该地址：是则 UI 重建/通知栏重进直接复用，不重建引擎 */
        fun hasActiveEngineFor(url: String): Boolean {
            val eng = activeEngine ?: return false
            if (eng.videoUrl != url) return false
            val state = eng.player.playbackState
            return eng.player.isPlaying || state == Player.STATE_BUFFERING || state == Player.STATE_READY
        }

        /** 当前起播规格（通知栏重进时 PlayerActivity 解析 url/title/historyPath 用） */
        fun activeSpec(): EngineSpec? = instance?.currentSpec

        /** M4：当前实际生效档（清晰度面板「自动（当前生效：X）」）；服务未运行 null */
        fun activeEffectiveQualityTier(): NvQualityTier? = instance?.jellyfinEffectiveTier

        /** Activity 显式退出收口（同进程直调，主线程；无 startService 后台限制问题） */
        fun onHostFinished() {
            instance?.exitUiHost()
        }

        /**
         * 隐私重锁静态入口（隐私计划 Task 11，NvPrivacyRelockOrchestrator 调）：
         * 暂停本地播放但**不停服**——服务未运行静默；仅当确有解锁会话被重锁时才会被调
         * （编排器对空会话短路），非隐私用户的退后台行为零变化。
         */
        fun pauseAll() {
            instance?.pauseAllPlayersNoStop()
        }

        /**
         * M3（2026-10-07）切清晰度公共入口（清晰度菜单，M4 UI 调用）：转发到活跃服务实例。
         * 仅 JELLYFIN 会话有效；position/倍速保持；videoUrl 恒不变（novax:// 逻辑键，见
         * [switchQualityInternal] 区块头注释的三处守卫）。服务未运行/非 JELLYFIN 静默忽略。
         */
        fun switchQuality(context: Context, tier: NvQualityTier) {
            instance?.switchQualityInternal(tier, fromUser = true)
        }

        /**
         * 宿主移交兜底内核（RMVB 路由/软解切换）前的显式收口（BUG-C-R9-02/MG-B）：
         * 旧 Exo 引擎与新 libVLC 双路并行出声、通知栏控制的还是画面里没有的那一集。
         * 移交必须无条件落库并停服（exitUiHost 现也是无条件停，本入口保留独立语义）。
         */
        fun handOffToFallback() {
            instance?.exitUiHostForHandOff()
        }
    }
}
