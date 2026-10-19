@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.player

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.novax.app.data.MockMediaRepository
import com.novax.app.data.NvGraph
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.playback.NvFallbackSeekExtractor
import com.novax.app.data.playback.NvMediaSourceBuilder
import com.novax.app.data.playback.NvUri
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvLoader
import com.novax.app.ui.components.NvMeteredConfirmDialog
import com.novax.app.ui.theme.NovaXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs

/**
 * 切集/起播管线诊断插桩（2026-10-07「下一集连点卡死」取证工具，保留供未来排查）：
 * [mark] 统一打 `NvQSwitch` 日志——`t=+Xms` 是相对本管线 begin 的耗时（主线程
 * lifecycleScope 协程内调用，空洞/大值即主线程或管线卡点）；`abs=` 是绝对
 * elapsedRealtime，用于把 Activity 侧与服务侧（PlaybackService 引擎构建，同为
 * 主线程）的时间轴对齐。`begin=true` 重置起点。
 */
internal object NvQSwitchTrace {
    @Volatile
    private var t0 = 0L

    fun mark(stage: String, begin: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (begin) t0 = now
        Log.d("NvQSwitch", "stage=$stage t=+${now - t0}ms abs=$now")
    }
}

/**
 * C6/C8 接入点：全屏沉浸播放器。
 * Intent 参数：videoUrl / title / positionMs（缺省播放内置演示片）；
 * P1-6：EXTRA_FROM_MEDIA_NOTIFICATION = 通知栏重进（播放内容以 PlaybackService 当前规格为准）。
 *
 * 三类输入源（C6 §3.1 字幕）：
 * - novax://<sourceId>/<path>：Room 取 SourceEntity → NvMediaSourceBuilder 构建 MediaSource
 *   （SMB/WebDAV/本地统一 NvDataSource 随机读）；字幕经 NvSourceRepository.listSubtitles（同目录）
 *   → SubtitleMatcher.pickAuto：唯一高分命中直接 side-load，多候选弹「选择字幕」面板手选；
 * - 本地视频（file:// 或绝对路径）：扫描父目录字幕（既有路径，不回归）；
 * - 网络 URL（http/https/asset）：默认 DataSource 工厂直连。
 *
 * P1-6 后台播放（novax_background_play）架构：Activity 不再自持 ExoPlayer——
 * 异步准备（源解析/字幕扫描转码/续播位置）完成后把 [PlaybackService.EngineSpec] 交给
 * PlaybackService 构建/持有引擎并挂 MediaSession（通知栏可控）；UI 经 MediaController
 * 驱动 session 内 player（media3 1.8 支持把 TextureView Surface 转发给 session player 渲染）。
 * 观看历史随 player 迁入 Service（心跳/暂停/销毁落库），后台播放时历史仍累计。
 */
class PlayerActivity : ComponentActivity() {

    // P1-6：session 控制通道（MediaController 实现 Player 接口，setVideoTextureView 可用）
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controllerReady by mutableStateOf(false)

    // novax:// 异步准备状态（Compose 可观察）
    private var novaxLoading by mutableStateOf(false)
    private var novaxError by mutableStateOf<String?>(null)
    private var novaxCandidates by mutableStateOf<List<SubtitleCandidate>>(emptyList())
    private var novaxNeedsChoice by mutableStateOf(false)
    /** 2026-09-27 切集自动定向：每次 switchQueueVideo 递增，PlayerScreen 据此置 pending
     *（自适应重建/同片重试不递增，不会误触发重定向） */
    private var orientationSeq by mutableStateOf(0)

    /** 2026-10-12 §5:通知栏真冷启动标记(组合期稳定,构成 PlayerScreen 冷武装初值) */
    private var autoOrientationColdEntry by mutableStateOf(false)

    // 2026-09-28 外部「打开方式」：DISPLAY_NAME 查询到货后替换临时标题（Compose 可观察）
    private var externalTitle by mutableStateOf<String?>(null)

    // 2026-09-28 缺陷 B：外部 VIEW 新意图「待消费」标记。置位 = 本实例 onCreate 带来了外部
    // 意图，在 startPlayback 真正消费它（p.externalDisplayName != null）之前，resync 的
    // 「switched」适配必须让位——否则暖态下 onStart 会把新意图误判成后台切集，用服务旧
    // 规格 adopt 覆盖 lastStart（新文件被完全丢弃，只回连旧媒体）
    @Volatile
    private var pendingExternalIntent = false

    /** 流量确认框可见态（2026-09-28 仅 Wi-Fi 免确认播放）：确认前不起播，零流量 */
    private var meteredPrompt by mutableStateOf(false)

    /** 确认框内「流量下不再提醒」勾选态（每次弹窗重置为未勾选） */
    private var meteredRemindChecked by mutableStateOf(false)

    // 2026-09-28 审计 P0-2/P2 修复：闸门下沉到 MeteredGate.needsConfirm 后，确认框不再只服务
    // onCreate——重试/切集也会弹。三类待裁决上下文决定了「继续」后的动作与「取消」的语义，
    // 且任一存在期间 resync 冻结（B4：弹窗窗口内服务旧规格不得覆盖待确认的新片，否则
    // 用户确认后播的是旧片）。
    /** onCreate 新开：确认后走 launchStart 保留续播裁决（B5：此前 restartPlayback 绕开
     * 历史查询，确认后从头播并把 45 分钟旧进度覆写成 ~10s） */
    private inner class PendingMeteredLaunch(val params: StartParams, val explicitPos: Long, val allowPrompt: Boolean)
    private var pendingMeteredLaunch: PendingMeteredLaunch? = null
    /** 错误重试：确认后按原参数直接 startPlayback（重试不重做续播裁决） */
    private var pendingMeteredRetry: StartParams? = null
    /** 队列切集：确认后 applySwitch 落地；取消只放弃本次切换不退出播放 */
    private var pendingMeteredSwitch: StartParams? = null
    private var pendingMeteredSwitchIndex: Int = -1

    // 2026-09-19：时长提示（异步查库表）——引擎解析出真实时长前（SMB 慢源可达数秒），
    // 先用媒体库已知时长驱动进度条；此窗口内滑杆零反馈 = 用户反馈「开头进去进度条拖不动」
    private var durationHintMs by mutableLongStateOf(0L)

    // P2（错误态重试语义）：记录本次起播参数，重试 = 重建引擎真重试
    private var lastStart: StartParams? = null

    /** P2（C6 §2.8）退出确认：2026-09-15 用户需求已移除 —— 返回直接退出播放 */

    // P2（Activity 重建不丢倍速）：onSaveInstanceState 存，服务构建引擎后应用
    private var restoreSpeed = 1f

    // 2026-09-15：文件夹播放队列（上/下切换）——FilesScreen 经 PlayerPlaylistHolder 进程内交接，
    // Intent 只带 queueId；Activity 重建经 savedInstanceState 的 queueId 重取（holder LRU 保留）
    private var queueId: String? = null
    private var playlist: List<Pair<String, String>>? = null
    private var playlistIndex = 0

    // 2026-09-15：续播提示条（用户需求：打开视频不静默跳旧进度，显示 5 秒提示 + 跳转/关闭按钮）。
    // 非 null = PlayerScreen 顶部显示提示条；跳转/关闭/5 秒超时后置 null。
    private var resumePromptPositionMs by mutableStateOf<Long?>(null)

    // BUG-C-R9-01：续播提示条裁决出的待跳位置。主格式靠提示条承载；RMVB/软解路由没有提示条，
    // 该位置必须随路由静默透传给 RmvbPlayerActivity，否则 rm/rmvb/wmv/asf 永远从头播，
    // 且 10s 心跳把旧进度覆写为 ~10s（渐进数据丢失）。
    @Volatile
    private var pendingResumeMs = 0L

    // BUG-C-R9-05：装载窗口（novaxLoading=true）内 resync 被吞后置位，装载收敛后补跑
    @Volatile
    private var pendingResyncAfterLoading = false

    /** 本次起播参数（重试/重建复用）；novax = (sourceId, path) */
    private data class StartParams(
        val url: String,
        val title: String,
        val subtitleText: String?,
        val historyPath: String,
        val novax: Pair<Long, String>?,
        val positionMs: Long,
        /** 外部 VIEW 打开的真实文件名（含扩展名）：content URI 末段常是数字 id，
         * RMVB 兜底路由的扩展名判定必须看它而不是 url（2026-09-28） */
        val externalDisplayName: String? = null,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2026-10-03 降级 seek 激活的可见提示：坏索引 MKV 起播时告知用户（估精度 ±几秒），
        // 同时让「新构建已生效」一眼可辨（激活提示从未出现过 = 用的还是旧包）。幂等注册。
        installFallbackSeekToast()

        // C12 反馈：打开视频默认全屏横屏（清单 sensorLandscape 之外的代码兜底，覆盖重建/手动改向后的残留）
        // P2/方向断流修复（2026-09-14）：重建时恢复用户手选方向，不再强制回 sensorLandscape
        //（否则用户选了竖屏后 Activity 被平台重建 → 方向被打回横屏 + 引擎重建 = 「点了方向键要重新加载」）
        val savedOrientation = savedInstanceState?.getInt(STATE_ORIENTATION, -1) ?: -1
        requestedOrientation = if (savedOrientation >= 0) {
            savedOrientation
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // 方向断流排查日志（2026-09-14）：configChanges 已覆盖方向变化，正常只走 onConfigurationChanged；
        // 若真机切方向时出现成对的 onCreate/onDestroy（id 不同）= OEM 未守 configChanges，Activity 真被重建
        Log.d(
            "NvPerf",
            "PlayerActivity onCreate id=${identityHash()} recreated=${savedInstanceState != null} " +
                "requestedOrientation=$requestedOrientation",
        )

        // P1-6：通知栏/锁屏重进 → 播放内容以服务当前规格为准（sessionActivity 的 PendingIntent 固定，无法带逐次变化的内容）
        val fromNotification = intent.getBooleanExtra(EXTRA_FROM_MEDIA_NOTIFICATION, false)
        val activeSpec = if (fromNotification) PlaybackService.activeSpec() else null
        autoOrientationColdEntry = coldAutoOrientationGate(fromNotification, savedInstanceState != null)
        // §5 关键播种(一轮评审 P0-2 原文,勿漏):冷通知路径 seq 恒 0 会让 PlayerScreen 的
        // LaunchedEffect(autoOrientationSeq) 首跑直接 return,armed 永无消费机会——置 1 使
        // seq 效果冷启动必跑一次;正常打开/重建路径 gate=false 不置位,零行为变化
        if (autoOrientationColdEntry) orientationSeq = 1
        // 2026-09-28 外部「打开方式」入口：文件管理器/相册以 VIEW + data 拉起（清单已导出本
        // Activity）。读授权随 Intent 的 FLAG_GRANT_READ_URI_PERMISSION 授予。有 VIEW 无 data
        // 属外部空手拉起——宁可退出也不能回落到假片（Big Buck Bunny）。豁免通知路径：
        // 通知重进的 sessionActivity PendingIntent 本身就是 ACTION_VIEW 无 data
        // （PlaybackService.sessionActivityPendingIntent），必须放行给 activeSpec 解析路径
        val externalData = if (intent.action == Intent.ACTION_VIEW) intent.data else null
        if (intent.action == Intent.ACTION_VIEW && externalData == null && !fromNotification) {
            Toast.makeText(this, "无法识别要打开的文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 缺陷 B：本同步段先占位再进 onStart——外部意图被消费前，resync 的 switched
        // 适配不得用服务旧规格覆盖刚建好的 lastStart（新内容会被完全丢弃）
        if (externalData != null) pendingExternalIntent = true
        // 2026-09-28 审计 A（Mock 兜底退场）：无参数拉起此前静默播内置演示片（Big Buck
        // Bunny）——脚手架残留承担生产兜底职责。诚实退出：与外部空手 VIEW 同款提示。
        val url = activeSpec?.videoUrl
            ?: externalData?.toString()
            ?: intent.getStringExtra(EXTRA_VIDEO_URL)
        if (url == null) {
            Toast.makeText(this, "无法识别要打开的文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = activeSpec?.title
            ?: intent.getStringExtra(EXTRA_TITLE)
            ?: externalData?.let { NvExternalOpen.initialTitle(it.toString()) }
            ?: url.substringAfterLast('/').ifBlank { "视频" }
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
            ?: MockMediaRepository.subtitleFor(url)
        val intentPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
        // C11 §2.1：历史主键 path——假数据影片传 mediaId，其余 = 播放 URL/路径
        val historyPath = activeSpec?.history?.path
            ?: intent.getStringExtra(EXTRA_HISTORY_PATH)
            ?: url
        val novaxTarget = if (url.startsWith(NvUri.SCHEME + "://")) NvUri.parse(url) else null

        // 2026-09-19：时长提示异步查询（媒体库 durationMs，缺则看历史行）——IO 协程，到货即重组。
        // SMB 慢源解析容器耗时数秒，此窗口内引擎 duration 未知、进度条完全无反馈（用户实测）
        lifecycleScope.launch(Dispatchers.IO) {
            val d = runCatching {
                if (novaxTarget != null) {
                    NvGraph.mediaItemDao(this@PlayerActivity)
                        .findByKey(novaxTarget.first, novaxTarget.second)?.durationMs
                        ?: NvGraph.historyDao(this@PlayerActivity)
                            .findByKey(novaxTarget.first, novaxTarget.second)?.durationMs
                    ?: 0L
                } else 0L
            }.getOrDefault(0L)
            if (d > 0L) durationHintMs = d
        }
        // §4.1 内容隔离(轮1评审 A-P1):recreate 换内容(播 A 中外部 VIEW 开 B)时,旧存档不得回放——
        // 否则 B 被 seek 到 A 的位置、还挂上 A 的队列。键不一致(或无存档)= 全新起播
        val savedStateMatchesContent =
            savedInstanceState?.getString(STATE_URL)?.let { it == url } == true
        // spec §4.6：PiP 中外部 VIEW 换内容，recreate 的新实例不保小窗（onNewIntent 写标记）
        reenterPipOnResume =
            savedInstanceState?.getBoolean(STATE_PIP_REENTER, false) ?: false
        queueId = (if (savedStateMatchesContent) savedInstanceState?.getString(STATE_QUEUE_ID) else null)
            ?: intent.getStringExtra(EXTRA_QUEUE_ID)
        playlist = PlayerPlaylistHolder.get(queueId)
        if (playlist == null && fromNotification) {
            // BUG-C-R2-07：通知正文冷启动重进不经过 FilesScreen，queueId 槽为空 →
            // 用服务规格自带的队列字段重建（EngineSpec 已透传），应用内上/下集按钮恢复可用
            val spec = activeSpec
            if (spec != null && spec.queueUrls.isNotEmpty()) {
                playlist = spec.queueUrls.zip(spec.queueTitles)
                playlistIndex = spec.queueIndex.coerceIn(0, spec.queueUrls.size - 1)
            }
        }
        playlistIndex = playlist?.indexOfFirst { it.first == url }?.takeIf { it >= 0 } ?: playlistIndex

        // P2 修复（重建丢进度）：savedInstanceState 位置优先，其次历史库（协程内查），再次 intent 原值
        val savedPositionMs =
            (if (savedStateMatchesContent) savedInstanceState?.getLong(STATE_POSITION_MS, -1L) else null) ?: -1L
        restoreSpeed =
            (if (savedStateMatchesContent) savedInstanceState?.getFloat(STATE_SPEED, 1f) else null) ?: 1f

        lastStart = StartParams(
            url, title, subtitle, historyPath, novaxTarget, intentPositionMs,
            externalDisplayName = externalData?.let { NvExternalOpen.initialTitle(it.toString()) },
        )

        // 全屏沉浸（C6 §1）：内容延伸到系统栏 + 隐藏状态栏/导航栏（滑出为 transient，回焦自动重新隐藏）
        hideSystemBars()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // R8 系统返回键：直接退出播放（2026-09-15 用户需求，去确认框）；进度服务侧落库
        onBackPressedDispatcher.addCallback(this) { requestExit() }

        // P2 修复（主线程 runBlocking 卡起播）：预热设置内存缓存，起播超时等读缓存快照（非阻塞）
        warmPlayerPrefsCache()

        // 2026-09-29 FFmpeg 音频升级迁移：清「音轨」类旧软解路由记忆（一次性，幂等）。
        // 必须挂 App 级 scope（真机教训）：软解记忆直连路由在 onCreate 后 ~190ms 内 finish()，
        // lifecycleScope 随之取消——迁移编辑未提交即被取消，旧记忆永不清除；遇到「路由后必崩」
        // 的宿主（真机实测：DTS→libVLC 页 vlc-audiotrack abort）会死循环。App scope 的写
        // 不随 Activity 销毁取消，最坏多崩一次、下次打开必然直连主播放器
        migrateSoftRouteMemoriesInBackground()

        setContent {
            // 播放界面永远深色（2026-09-20 浅色模式：不随外观偏好切换）
            NovaXTheme(darkTheme = true) {
                // Liquid Glass（spec §6）：播放器域独立 HazeState——两 Activity 各自独立组合树，
                // env 各自下发（decisions §4.5：source 挂视频+字幕包裹容器，控制条/弹窗面板做兄弟）
                com.novax.app.ui.glass.LocalNvGlassEnvProviding {
                    PlayerRootContent()
                    if (meteredPrompt) {
                        NvMeteredConfirmDialog(
                            remindDisabledChecked = meteredRemindChecked,
                            onRemindDisabledCheckedChange = { meteredRemindChecked = it },
                            onContinue = { onMeteredPromptResult(true) },
                            onCancel = { onMeteredPromptResult(false) },
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            val prefs = readPrefs()
            // P1-6：后台播放在 33+ 依赖通知展示，开启时补一次运行时权限请求（服务化后通知栏即控制面板）
            if (prefs?.get(KEY_BACKGROUND_PLAY) == true) maybeRequestNotificationPermission()
            // 续播语义（2026-09-15 修订）：
            // - Activity 重建（savedPositionMs>=0）或调用方显式给位（intentPositionMs>0）→ 直接续播；
            // - 否则查历史：≥[RESUME_PROMPT_MIN_MS] 的进度不静默跳——起播 5 秒提示条（跳转/关闭）；
            //   不足阈值的残留进度（几秒）仍静默续播，避免反复打开同一部片被提示打扰。
            val explicitPos = if (savedPositionMs >= 0L) savedPositionMs else intentPositionMs
            val allowPrompt = savedPositionMs < 0L && intentPositionMs <= 0L
            // 2026-09-28 外部「打开方式」：起播前补全真实文件名（provider 查询毫秒级；失败
            // 保持临时标题不阻塞）。顶栏标题与 RMVB 兜底路由（扩展名判定）都依赖它——
            // 媒体库 URI 末段常是数字 id，路由若看 url 会把 rmvb 判给 ExoPlayer
            externalData?.let { data ->
                withContext(Dispatchers.IO) {
                    runCatching { NvExternalOpen.queryDisplayName(contentResolver, data) }.getOrNull()
                }?.takeIf { it.isNotBlank() }?.let { name ->
                    lastStart = lastStart?.copy(title = name, externalDisplayName = name)
                    externalTitle = name
                }
            }
            // 2026-09-28 审计 P0-2：闸门统一收口到 [MeteredGate.needsConfirm]（起播族全部入口
            // 每次起播重判；2026-10-11 三档化——「仅提醒一次」档会话豁免口径见 spec §3：
            // 前台 Activity 归零且服务引擎已释放才终结，不再有「同一段网络」约束）。
            // 确认后走 launchStart 保留续播裁决（B5）；弹窗期间 pendingMeteredLaunch 冻结 resync（B4）
            if (MeteredGate.needsConfirm(this@PlayerActivity, url, novaxTarget)) {
                pendingMeteredLaunch = PendingMeteredLaunch(lastStart!!, explicitPos, allowPrompt)
                meteredRemindChecked = false
                meteredPrompt = true
                return@launch
            }
            launchStart(lastStart!!, explicitPos, allowPrompt, prefs)
        }

        // P1-6：服务内引擎就绪后建立 MediaController 连接（先 startService 后 bind，session 必已存在）
        // 2026-09-19：controller 已建后再观察到引擎变化 = 后台/前台被通知切过集 → 再同步 UI
        lifecycleScope.launch {
            PlaybackService.activeEngineFlow.collect { engine ->
                if (engine != null && controllerFuture == null) buildMediaController()
                else if (engine != null && controllerFuture != null) resyncWithServiceIfSwitched()
            }
        }
    }

    /** 全屏沉浸：内容延伸到系统栏 + 隐藏状态栏/导航栏（滑出 transient，交互/回焦后自动重新隐藏） */
    private fun hideSystemBars() {
        // 2026-09-16 UI 审计 P2：刘海/挖孔机型内容延伸到短边，必须声明 shortEdges，
        // 否则横屏顶栏图标顶进刘海被裁（本机无挖孔，公众版机型各异）。
        // layoutInDisplayCutoutMode 字段为 API 28+，minSdk 26 必须加版本守卫，
        // 否则 Android 8.x 一进播放页即 NoSuchFieldError。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** C12 反馈：沉浸兜底——对话框/交互抢焦导致系统栏出现后，窗口重新获得焦点即再次隐藏 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * 方向变化正常路径（2026-09-14 断流修复）：configChanges 覆盖 orientation|screenSize|screenLayout|
     * smallestScreenSize|density|uiMode，requestedOrientation 切换只走这里——Activity 不重建、
     * engine 不重建、TextureView 只重布局不解绑（约定见 PlayerScreen.VideoSurface），播放绝不中断。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(
            "NvPerf",
            "PlayerActivity onConfigurationChanged id=${identityHash()} " +
                "orientation=${newConfig.orientation} requested=$requestedOrientation",
        )
    }

    /** 播放器统一内容：错误态（真重试）/ 加载态 / PlayerScreen（续播提示条 + 队列切换在屏内） */
    @Composable
    private fun PlayerRootContent() {
        // P1-6：引擎由 PlaybackService 持有（StateFlow 观察构建/重建/释放）
        val activeEngine = PlaybackService.activeEngineFlow.collectAsState().value
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val error = novaxError
            val params = lastStart
            when {
                error != null -> Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    NvErrorState(
                        title = "无法打开视频",
                        message = error,
                        onRetry = { restartPlayback() },   // P2：真重试（重建引擎），不再 finish
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
                novaxLoading || activeEngine == null || !controllerReady || params == null -> Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) { NvLoader() }
                else -> {
                    // 2026-09-19：记忆 handle 使 engine 身份在重组间稳定（PlaybackEngineHandle 无结构
                    // equals）——PlayerScreen 内 remember/倍速状态的重置键依赖该稳定性
                    val handle = remember(activeEngine, controllerReady) {
                        PlaybackEngineHandle(activeEngine, controller!!)
                    }
                    // 2026-09-20 修复「加载慢时锁定后进度条重现」：慢加载触发无画面自愈（引擎重建）
                    // 时 activeEngine 抖动 null→新实例，PlayerScreen 子树离组再回组，普通 remember 的
                    // locked/controlsVisible 被重置（进度条重现、锁丢失）。SaveableStateProvider 让
                    // 离组再回组的子树保留 rememberSaveable 状态（NavHost 同款机制），顺带修复
                    // pinnedOrientation/orientationMode/videoRotation 等存量 saveable 状态的同场景重置。
                    val playerStateHolder = rememberSaveableStateHolder()
                    playerStateHolder.SaveableStateProvider("player-screen") {
                        PlayerScreen(
                        engine = handle,
                        title = externalTitle ?: params.title,
                        // C13 投屏：播放 URL 传入屏内（投屏解析 CastMedia 用）
                        playUrl = params.url,
                        subtitle = if (params.novax != null) null else params.subtitleText,
                        subtitleCandidates = novaxCandidates,
                        needsSubtitleChoice = novaxNeedsChoice,
                        // 2026-09-15：续播提示条（5 秒，跳转/关闭）+ 文件夹队列上/下切换
                        resumePositionMs = resumePromptPositionMs,
                        // 2026-09-19：时长提示（库表已知时长先驱动进度条）
                        durationHintMs = durationHintMs,
                        // 2026-09-27：切集自动定向（按新集视频比例切横/竖屏）
                        autoOrientationSeq = orientationSeq,
                        autoOrientationColdEntry = autoOrientationColdEntry,
                        onPrevVideo = if (playlist != null && playlistIndex > 0) {
                            { switchQueueVideo(-1) }
                        } else null,
                        onNextVideo = if (playlist != null && playlistIndex < (playlist?.size ?: 0) - 1) {
                            { switchQueueVideo(1) }
                        } else null,
                        onBack = { requestExit() },
                        onRetry = { restartPlayback() },
                        // 2026-09-18 双内核：容器解析失败 → 一键转 libVLC 软解内核兜底。
                        // 2026-09-19 追加软解路由记忆写入（用户需求：不能每次打开都重新无声判定）：
                        // reason 按文件记（novax_softroute_<url>，下次打开直连软解）。
                        // 2026-09-29：per-mime 集合写入随 KEY_SOFT_UNSUPPORTED_AUDIO_MIMES 退役删除
                        //（历史「音轨」记录由 migrateSoftRouteMemoriesOnce 一次性清除）
                        onFallbackRetry = { reason ->
                            lastStart?.let { p ->
                                val routeKey = stringPreferencesKey(SOFT_ROUTE_KEY_PREFIX + p.url)
                                lifecycleScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        playerPrefsStore.edit { m ->
                                            m[routeKey] = reason
                                        }
                                    }
                                }
                                // 移交兜底内核前显式收口旧引擎（BUG-C-R9-02/MG-B：旧 Exo 引擎
                                // 必须立即落库停服，否则与 libVLC 双路并行出声、通知控制旧集）
                                PlaybackService.handOffToFallback()
                                RmvbPlayerActivity.start(
                                    this@PlayerActivity,
                                    title = p.title,
                                    positionMs = if (p.positionMs > 0L) p.positionMs else pendingResumeMs,
                                    sourceId = p.novax?.first,
                                    sourcePath = p.novax?.second,
                                    localPath = p.novax?.let { null } ?: p.url.substringBefore('?'),
                                    // 队列随路由带入（修复审核返工项：另两条 RMVB 路由都传了，
                                    // 此处漏传会让硬解失败转软解后上/下集丢失）
                                    queueUrls = playlist?.map { it.first } ?: emptyList(),
                                    queueTitles = playlist?.map { it.second } ?: emptyList(),
                                    queueIndex = playlistIndex,
                                )
                            }
                            finish()
                        },
                        // M3 清晰度菜单（2026-10-07）：点选档位 → 服务侧切流（进度/倍速保持，
                        // 非 Jellyfin/服务未运行静默忽略）+ 写记忆档位（下次起播沿用；
                        // 写法对齐 onMeteredPromptResult 的 lifecycleScope+runCatching 惯例）
                        onQualitySelected = { tier ->
                            PlaybackService.switchQuality(this@PlayerActivity, tier)
                            lifecycleScope.launch {
                                runCatching { putPlayerPref(KEY_PLAYBACK_QUALITY, tier.name) }
                            }
                        },
                        onEnterPip = { enterPip() },
                    )
                    }
                }
            }
        }
    }

    /** P2（错误态重试语义）：真重试 = 重建引擎/重新 prepare；「返回」才 finish。
     * 2026-09-28 P0-2：重试也是一次起播——计量网络下重新过闸门（此前只拦首次起播，
     * Wi-Fi 起播后网络切蜂窝的「重试」会静默继续拉流） */
    private fun restartPlayback() {
        val p = lastStart ?: return
        novaxError = null
        lifecycleScope.launch {
            if (MeteredGate.needsConfirm(this@PlayerActivity, p.url, p.novax)) {
                pendingMeteredRetry = p
                meteredRemindChecked = false
                meteredPrompt = true
                return@launch
            }
            startPlayback(p, readPrefs())
        }
    }

    /** 流量确认框结果（2026-09-28 P0-2 收口；2026-10-11 三档化）：继续 = 按待裁决上下文起播；
     * 取消 = 新建/重试场景退出（确认前零流量语义），切集场景仅放弃本次切换、继续播当前集。
     * 继续 → 置位会话标志（「仅提醒一次」档本会话不再弹）；勾选「流量下不再提醒」→
     * 写「不提醒」档（设置页三档可改回）。取消分支不置位（spec §4.4） */
    private fun onMeteredPromptResult(continuePlayback: Boolean) {
        meteredPrompt = false
        val launch = pendingMeteredLaunch
        val retry = pendingMeteredRetry
        val switch = pendingMeteredSwitch
        val switchIndex = pendingMeteredSwitchIndex
        pendingMeteredLaunch = null
        pendingMeteredRetry = null
        pendingMeteredSwitch = null
        pendingMeteredSwitchIndex = -1
        if (!continuePlayback) {
            if (switch != null) {
                switchInFlight = false
                Toast.makeText(this, "已取消切集（计量网络）", Toast.LENGTH_SHORT).show()
            } else {
                finish()
            }
            return
        }
        if (meteredRemindChecked) {
            // 勾「流量下不再提醒」→ 写「不提醒」档（2026-10-11 三档；设置页可改回）
            lifecycleScope.launch { runCatching { putPlayerPref(KEY_METERED_REMIND_MODE, METERED_REMIND_NEVER) } }
        }
        // 「仅提醒一次」档：本会话已确认，切集/重试/RMVB 移交/投屏不再弹（spec §4.4）
        NvMeteredGuard.markSessionConfirmed()
        when {
            launch != null -> lifecycleScope.launch {
                launchStart(launch.params, launch.explicitPos, launch.allowPrompt, readPrefs())
            }
            retry != null -> lifecycleScope.launch { startPlayback(retry, readPrefs()) }
            switch != null -> lifecycleScope.launch {
                try {
                    applySwitch(switch, switchIndex)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w("NvPerf", "switch pipeline failed: ${t.javaClass.simpleName}: ${t.message}")
                    novaxLoading = false
                    novaxError = "切换失败：${t.message ?: t.javaClass.simpleName}"
                } finally {
                    switchInFlight = false
                }
            }
            else -> restartPlayback()
        }
    }

    /** 设置读取统一走 IO（P2：禁止主线程同步等待 >50ms） */
    private suspend fun readPrefs(): Preferences? = runCatching {
        withContext(Dispatchers.IO) { playerPrefsStore.data.first() }
    }.getOrNull()

    /**
     * 历史登记快照：2026-09-19 起统一委托 EpisodeMediaKit.buildEpisodeHistorySnapshot
     * （kind 按 SxEy 判 EPISODE/MOVIE，与 RMVB 页、Service 后台切集一致，不再恒 MOVIE）。
     */
    private fun buildHistorySnapshot(
        sourceId: Long?,
        sourceType: String?,
        path: String,
        title: String,
        positionMs: Long,
    ): NvHistoryWriter.Snapshot =
        buildEpisodeHistorySnapshot(sourceId, sourceType, path, title, positionMs)

    /**
     * 2026-09-15 用户需求：返回直接退出播放，不弹确认框（进度由服务侧「暂停即写 + 10s 心跳」落库，
     * 退出时 exitUiHost 再兜底 record 一次，进度最多丢最后几秒心跳内的量）。
     * R8 系统返回键与 PlayerScreen 顶栏返回共用本入口。
     */
    private fun requestExit() {
        saveAndFinish()
    }

    /** 保存进度并退出（进度由 PlaybackService 在退出收口/心跳/暂停时落库；幂等 upsert） */
    private fun saveAndFinish() {
        finish()
    }

    /**
     * 续播位置裁决（2026-09-15 修订，从 onCreate 抽出供队列切换复用）：
     * - allowPrompt=false（重建/调用方显式给位）→ explicitPos 直接生效，不查历史不提示；
     * - allowPrompt=true：novax_resume_on_open 开（默认）时查历史——≥[RESUME_PROMPT_MIN_MS]
     *   的进度置 [resumePromptPositionMs]（起播 0 起 + 5 秒提示条）；不足阈值静默续播；
     *   开关关 → 从 0 起（原语义）。
     */
    private suspend fun launchStart(base: StartParams, explicitPos: Long, allowPrompt: Boolean, prefs: Preferences?) {
        NvQSwitchTrace.mark("launchStart enter allowPrompt=$allowPrompt")
        autoPipEnabled = prefs?.get(com.novax.app.player.KEY_PIP_AUTO) ?: false
        var pos = explicitPos
        var prompt = 0L
        // BUG-E（活跃会话重入，T2-05/T3-04）：同 URL 会话已在播（通知栏重进/主界面重进/后台切回）
        // 时不查历史、不弹「上次看到」提示条——引擎已在目标位置，续播语义由会话本身承载
        val reuseActive = PlaybackService.hasActiveEngineFor(base.url)
        if (!reuseActive && allowPrompt && pos <= 0L) {
            if (prefs?.get(KEY_RESUME_ON_OPEN) ?: true) {
                // 查询键必须与落库键一致（2026-09-15 修复：文件页播放此前用 novax:// URL 查，
                // 而历史快照存的是源内路径 → 永远查不到，续播/提示条双双失效）：
                // novax 源 = (sourceId, 解析出的源内路径)；本地/直连 = historyPath
                val lookupPath = base.novax?.second ?: base.historyPath
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        NvGraph.historyDao(this@PlayerActivity)
                            .getForResume(base.novax?.first, lookupPath)?.positionMs ?: 0L
                    }.getOrDefault(0L)
                }
                // M4：本地无有效进度时回读服务器续播位置（仅 Jellyfin 源；查询失败=0 静默；
                // 服务器位置同样走「≥提示条阈值弹条 / 否则静默跳转」既有口径）。
                // client 层读超时已收窄 5s（socket 级硬上界，Task 3）；withTimeoutOrNull 为
                // 二次尽力收口。不做距片尾裁决：服务器若保留已看完的末次位置，弹一次
                // 「上次看到」提示条属既有语义、用户可跳过，可接受。
                // 已知可接受重复：冷索引时本查询与 startPlayback 的 jfItemId 解析会各走一次
                // walk（进程死亡恢复的罕见路径），有超时上界，不优化。
                var effective = saved
                if (effective < RESUME_PROMPT_MIN_MS && base.novax != null) {
                    // 注意 this@PlayerActivity：withTimeoutOrNull 的 lambda 带 CoroutineScope 接收者，
                    // 裸 this 会绑定到 CoroutineScope 而非 Activity（helper 第一参是 Context）
                    val serverPos = withTimeoutOrNull(5_000) {
                        jellyfinServerResumePositionMs(this@PlayerActivity, base.novax?.first, lookupPath)
                    } ?: 0L
                    if (serverPos > effective) effective = serverPos
                }
                if (effective >= RESUME_PROMPT_MIN_MS) prompt = effective else pos = effective
            }
        }
        NvQSwitchTrace.mark("resumeResolved pos=$pos prompt=$prompt reuseActive=$reuseActive")
        resumePromptPositionMs = prompt.takeIf { it > 0L }
        // BUG-C-R9-01：裁决出的待跳位置带给 RMVB/软解路由（主格式走提示条不用它）
        pendingResumeMs = prompt
        NvQSwitchTrace.mark("launchStart exit → startPlayback")
        startPlayback(base.copy(positionMs = if (prompt > 0L) 0L else pos), prefs)
    }

    /**
     * 文件夹队列上/下切换（2026-09-15 用户需求）：上/下切换 = 文件夹内排序的相邻视频。
     * 换片 = 重新走起播管线（服务 setPlayer 热切换引擎；旧片进度由服务在换片前 record 落库），
     * 字幕候选/续播提示按新片重新裁决。
     *
     * 2026-09-27 用户报告「连点下一集视频卡死」修复：切换管线互斥——launchStart 未落定期间
     * 忽略再次切集（手动连点、播完自动连播与手动点击同帧双触发都会撞在此处）。此前并发管线
     * 会双写共享起播状态（lastStart/novaxLoading/字幕转码临时文件）并在服务侧形成引擎双建/
     * 双释竞态，画面卡死只能退出重进；RMVB 页 switchJob 串行（BUG-C-R4-03）同语义，此处对齐。
     */
    private var switchInFlight = false

    private fun switchQueueVideo(delta: Int) {
        val q = playlist ?: return
        if (switchInFlight) {
            Log.d("NvPerf", "switch ignored: pipeline in flight (delta=$delta)")
            return
        }
        val target = playlistIndex + delta
        if (target < 0 || target >= q.size) {
            Toast.makeText(
                this,
                if (delta < 0) "已是文件夹内第一个" else "已是文件夹内最后一个",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (target == playlistIndex) return
        switchInFlight = true
        NvQSwitchTrace.mark("tap delta=$delta target=$target", begin = true)
        val (url, title) = q[target]
        val novaxTarget = if (url.startsWith(NvUri.SCHEME + "://")) NvUri.parse(url) else null
        val base = StartParams(
            url = url,
            title = title,
            subtitleText = if (novaxTarget != null) null else MockMediaRepository.subtitleFor(url),
            historyPath = url,
            novax = novaxTarget,
            positionMs = 0L,
        )
        lifecycleScope.launch {
            try {
                // 2026-09-28 审计 P0-2：切集（含 ENDED 自动连播，同径）= 新的一次起播，
                // 计量网络按目标重判。状态变更全部移入 applySwitch（确认前零副作用），
                // 取消不留半状态；待裁决期间保持 switchInFlight=true 挡连点
                NvQSwitchTrace.mark("needsConfirm enter")
                val metered = MeteredGate.needsConfirm(this@PlayerActivity, url, novaxTarget)
                NvQSwitchTrace.mark("needsConfirm exit need=$metered")
                if (metered) {
                    pendingMeteredSwitch = base
                    pendingMeteredSwitchIndex = target
                    meteredRemindChecked = false
                    meteredPrompt = true
                    return@launch
                }
                applySwitch(base, target)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce   // Activity 销毁取消：互斥位随实例消亡，无需处理
            } catch (t: Throwable) {
                // 管线异常必须落定互斥位，否则后续切集被永久忽略（另一形态的「卡死」）
                Log.w("NvPerf", "switch pipeline failed: ${t.javaClass.simpleName}: ${t.message}")
                novaxLoading = false
                novaxError = "切换失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                if (pendingMeteredSwitch == null) switchInFlight = false
            }
        }
    }

    /**
     * 切集落地（闸门通过/用户确认后调用）：2026-09-28 P0-2 重构——原 switchQueueVideo 前段的
     * 状态变更（index 推进/自动定向/候选清空等）移到这里，保证「确认前零副作用」；
     * 确认路径与直通路径共用本入口，互斥位由调用方的 finally 落定。
     */
    private suspend fun applySwitch(base: StartParams, target: Int) {
        NvQSwitchTrace.mark("applySwitch enter target=$target")
        playlistIndex = target
        orientationSeq++   // 2026-09-27：通知 PlayerScreen 按新集比例自动定向
        resumePromptPositionMs = null
        novaxCandidates = emptyList()
        novaxNeedsChoice = false
        novaxError = null
        durationHintMs = 0L   // BUG-C-R2-03：新集时长未知，沿用上一集 hint 会让进度条分母错、拖动比例错
        lastStart = base
        launchStart(base, 0L, allowPrompt = true, prefs = readPrefs())
    }

    /** P1-6：Android 13+ 通知运行时权限（后台播放的通知栏控制依赖它展示） */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    // ---------- 2026-09-15 D5：画中画 ----------

    /** 自动小窗开关快照（onUserLeaveHint 同步路径不能读 DataStore；startPlayback 时刷新） */
    @Volatile
    private var autoPipEnabled = false

    /** spec §4.6：PiP 中外部 VIEW 换内容——onNewIntent 写、onSaveInstanceState 存、
     *  新实例 onCreate 读、onPostResume 消费（原位重进小窗） */
    private var reenterPipOnResume = false

    /** 2026-10-13 §3(P1-3):延迟重进 PiP 的回调句柄——可取消(用户接管/销毁),执行带存活守卫 */
    private var pipReenterRunnable: Runnable? = null

    /** modeChanged(false) 取消通道的 1s 时窗判据锚点：scheduled 时刻（elapsedRealtime） */
    private var pipReenterScheduledAtMs = 0L

    /** §3:取消待执行的自动重进——用户已接管(窗口内交互/离开 PiP)或销毁清理 */
    private fun cancelPendingPipReenter() {
        pipReenterRunnable?.let {
            Log.d("NvPerf", "pip reenter cancelled")
            window.decorView.removeCallbacks(it)
        }
        pipReenterRunnable = null
    }

    /** 一键进入画中画（Android 8.0+；§5 2026-10-12 改按真实视频比例,越界钳制,未知回退 16:9） */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "画中画需要 Android 8.0 以上", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("NvPerf", "enterPip invoked")
        runCatching {
            val (rw, rh) = pipAspectRatioCode(
                PlaybackService.activeEngineFlow.value?.player?.videoSize?.width ?: 0,
                PlaybackService.activeEngineFlow.value?.player?.videoSize?.height ?: 0,
            )
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(rw, rh))
                .build()
            enterPictureInPictureMode(params)
        }.onFailure {
            Toast.makeText(this, "画中画进入失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 退后台自动小窗（设置 novax_pip_auto，默认关）：仅播放中且无错误态。
        // BUG-N4：autoPipEnabled 快照只在 startPlayback 时刷新，会话存活期间改开关不生效；
        // 直读 prefsCache 快照（putPlayerPref 同步刷新）保证热更新。
        val pipOn = playerPrefsSnapshot()?.get(KEY_PIP_AUTO) ?: autoPipEnabled
        if (pipOn && controller?.isPlaying == true && novaxError == null) {
            enterPip()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // §3 用户接管:窗口内任何触摸/按键=要全屏,放弃自动重进小窗
        cancelPendingPipReenter()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        // §3 用户从小窗展开/离开 PiP(不经 onUserInteraction 的动作:PiP 菜单/无障碍/系统层):
        // 取消待执行的自动重进——展开早于到点时防「拉回」。
        // 1s 时窗判别(2026-10-13 实测回归根因,1286b58「PiP 中 VIEW 换片保持小窗」被打没):
        // 系统交付 VIEW 意图会先展开全屏,其 modeChanged(false) 在 scheduled 后 +4ms 到站,
        // 被旧逻辑当「用户接管」误杀 pending——spec「posting 后的 modeChanged(false) 必是
        // 用户动作」在本平台不成立。用户真在 PiP 菜单点展开是人手操作(≥1s),换片流系统
        // 展开毫秒级到站,故 <1s 判系统展开不取消;≥1s 才认用户接管。
        // onUserInteraction 与 onDestroy 的取消保持无条件,不在此时窗内。
        if (!isInPictureInPictureMode &&
            pipReenterRunnable != null &&
            android.os.SystemClock.elapsedRealtime() - pipReenterScheduledAtMs >= 1000L
        ) {
            cancelPendingPipReenter()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (reenterPipOnResume && Build.VERSION.SDK_INT >= 26) {
            reenterPipOnResume = false
            // post 延迟：recreate+旋转+VIEW 交付展开的过渡未收尾时立即 enter 会被 shell
            // 并成 LEAVE_PIP（2026-10-12 模拟器实证 enter→LEAVE 合并），等过渡落地再缩窗
            val r = Runnable {
                // §3 执行时守卫:已停/已离场不执行(治销毁后幽灵 Toast/双份);
                // 已在 PiP=用户已手动达成小窗,无需再进
                if (isFinishing || isDestroyed ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    isInPictureInPictureMode
                ) return@Runnable
                Log.d("NvPerf", "pip reenter fire (delayed 2.5s)")
                runCatching { enterPip() }
            }
            pipReenterRunnable = r
            window.decorView.postDelayed(r, 2500)
            // §3 取证锚:挂载时刻可从 logcat 判读——C2 的「cancelled 早于死线」据此自证
            Log.d("NvPerf", "pip reenter scheduled (+2500ms)")
            pipReenterScheduledAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    /** P1-6：连接 PlaybackService 的 MediaSession（引擎就绪后调用；直连 executor 回主线程收结果） */
    private fun buildMediaController() {
        buildMediaControllerAttempt(retriesLeft = 1)
    }

    /** H-7（2026-10-18 发布终审）：构建失败重试一次——此前 future.get() 异常时
     *  controllerReady 永为 false，UI 停 loading 无重试无错误出口（服务 binder 短暂
     *  未就绪即触发，只能退出重进）。重试前置空 controllerFuture，engine 就绪处的
     *  单飞闸（controllerFuture == null）在重试间隔内可再触发兜底 */
    private fun buildMediaControllerAttempt(retriesLeft: Int) {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (connected != null) {
                controller = connected
                controllerReady = true
                Log.d("NvPerf", "MediaController ready id=${identityHash()}")
            } else if (retriesLeft > 0 && !isFinishing && !isDestroyed) {
                Log.w("NvPerf", "MediaController build failed, retry once")
                controllerFuture = null
                lifecycleScope.launch {
                    delay(500)
                    if (!isFinishing && !isDestroyed && controllerFuture == null) {
                        buildMediaControllerAttempt(retriesLeft - 1)
                    }
                }
            } else {
                controllerReady = false
                Log.w("NvPerf", "MediaController build failed (final), UI stays loading")
            }
        }, { it.run() })
    }

    /**
     * 统一起播（novax:// 与直连双路）。P1/P2 修复集中点：
     * - 设置全部在 IO 协程预读（超时/自动挂载/字幕编码/首选语言），准备完成后交服务构建引擎；
     * - novax_subtitle_auto_load=false 时跳过自动挂载（候选仍给手选面板）；
     * - 字幕挂载前经 [transcodedSubtitleConfiguration] 重编码为 UTF-8 临时文件（GBK 乱码修复）；
     * - P1-6：服务内同地址仍在播（通知栏重进/Activity 重建）→ 直接复用引擎，绝不重建断流。
     */
    private suspend fun startPlayback(p: StartParams, prefs: Preferences?) {
        NvQSwitchTrace.mark("startPlayback enter url=${p.url.substringAfterLast('/')}")
        // 2026-09-28 缺陷 B：外部意图参数进入真实起播 = 新意图已被消费，resync 让位结束。
        // externalDisplayName 只在外部 VIEW 管线构造的参数上非空（通知重进/切集/resync
        // 重建的参数均为 null）。此处到 novaxLoading=true 之间无挂起点，不存在 resync 插窗
        if (p.externalDisplayName != null) pendingExternalIntent = false
        // 2026-09-18 双内核路由：RM/RMVB 容器 ExoPlayer 无法解析（文件容器解析失败），
        // 转 libVLC 兜底播放页；其余格式照常走主播放器（判定只看扩展名，零额外 IO）
        // 2026-09-28 外部打开：content URI 末段常是数字 id（无扩展名可判），兜底路由以显示名为准
        val routePath = p.novax?.second ?: p.externalDisplayName ?: p.url
        Log.d("NvPerf", "route check: path=$routePath fallback=${RmvbPlayerActivity.isFallbackTarget(routePath)}")
        if (RmvbPlayerActivity.isFallbackTarget(routePath)) {
            // 移交兜底内核前显式收口旧引擎（BUG-C-R9-02/MG-B）+ 续播位置透传（BUG-C-R9-01）
            PlaybackService.handOffToFallback()
            RmvbPlayerActivity.start(
                this,
                title = p.title,
                positionMs = if (p.positionMs > 0L) p.positionMs else pendingResumeMs,
                sourceId = p.novax?.first,
                sourcePath = p.novax?.second,
                localPath = p.novax?.let { null } ?: p.url.substringBefore('?'),
                // 2026-09-18 功能同步：文件夹队列随路由带入，兜底页上一集/下一集可用
                queueUrls = playlist?.map { it.first } ?: emptyList(),
                queueTitles = playlist?.map { it.second } ?: emptyList(),
                queueIndex = playlistIndex,
                // 2026-10-10：startPlayback 只被已过闸门的起播调用（onCreate 收口/确认框
                // 继续/已确认重试），移交即同一次已确认起播，兜底页不再二次弹确认
                meteredConfirmedStart = true,
            )
            finish()
            return
        }

        novaxLoading = true
        novaxError = null

        // 2026-09-19 软解路由记忆（用户需求：不能每次打开都无声等十几秒判定）：
        // 该文件上次在这台设备上已确认硬解不了（音轨/杜比视界画面），再次打开直接路由 libVLC，
        // 无声/黑屏窗口为零。判定记忆由 PlayerScreen 触发切换时写入（见 onFallbackRetry）
        // 2026-09-29 FFmpeg 音频升级：「音轨」类记忆读侧直接忽略（新内核已能软解 AC3/EAC3/
        // DTS/TrueHD，这些记忆升级后必然错误）——不信任写入侧迁移的提交时机（真机：
        // 冷进程 + 直连路由 + 宿主崩溃的 600ms 生命周期里 DataStore 编辑跑不赢，读侧忽略才
        // 是无竞态根治；后台迁移只负责清库存）。视频类记忆（杜比视界画面等）继续生效
        val softRouteReason = prefs?.get(stringPreferencesKey(SOFT_ROUTE_KEY_PREFIX + p.url))
            ?.takeUnless { it.contains("音轨") }
        if (softRouteReason != null) {
            Log.d("NvPerf", "soft-route memory hit: $softRouteReason → direct libVLC")
            Toast.makeText(
                this,
                "无法硬解$softRouteReason，已切换软解",
                Toast.LENGTH_SHORT,
            ).show()
            // 移交兜底内核前显式收口旧引擎（BUG-C-R9-02/MG-B）+ 续播位置透传（BUG-C-R9-01）
            PlaybackService.handOffToFallback()
            RmvbPlayerActivity.start(
                this,
                title = p.title,
                positionMs = if (p.positionMs > 0L) p.positionMs else pendingResumeMs,
                sourceId = p.novax?.first,
                sourcePath = p.novax?.second,
                localPath = p.novax?.let { null } ?: p.url.substringBefore('?'),
                queueUrls = playlist?.map { it.first } ?: emptyList(),
                queueTitles = playlist?.map { it.second } ?: emptyList(),
                queueIndex = playlistIndex,
                // 2026-10-10：软解记忆直路由同属已过闸门的本次起播，兜底页不再二次弹确认
                meteredConfirmedStart = true,
            )
            finish()
            return
        }

        val timeoutMs = ((prefs?.get(KEY_NET_TIMEOUT_SEC) ?: DEFAULT_NET_TIMEOUT_SEC)
            .takeIf { it in NET_TIMEOUT_CHOICES_SEC } ?: DEFAULT_NET_TIMEOUT_SEC) * 1_000
        val autoLoad = prefs?.get(KEY_SUB_AUTO_LOAD) ?: true
        val encodingPref = prefs?.get(KEY_SUB_ENCODING)
        val audioLang = prefs?.get(KEY_AUDIO_LANG) ?: "auto"
        val textLangs = (prefs?.get(KEY_SUB_LANGUAGES) ?: "zh")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // 字幕延迟（novax_subtitle_delay_ms）：全局单例供 NvShiftedSubtitleParser 每次 parse 读取；
        // SubtitleSheet 调节写回偏好并同步更新单例（从下一句字幕生效）
        NvSubtitleOffset.delayMs = prefs?.get(KEY_SUB_DELAY_MS) ?: 0L
        val audioPassthrough = prefs?.get(KEY_AUDIO_PASSTHROUGH) ?: true
        val engineActive = PlaybackService.hasActiveEngineFor(p.url)
        NvQSwitchTrace.mark("prefsSnapshotRead engineActive=$engineActive")

        if (p.novax != null) {
            val (sourceId, path) = p.novax
            val repository = NvGraph.repository()
            val source = withContext(Dispatchers.IO) {
                runCatching { NvGraph.sourceDao(this@PlayerActivity).getById(sourceId) }.getOrNull()
            }
            NvQSwitchTrace.mark("sourceLoaded type=${source?.type}")
            if (source == null) {
                novaxLoading = false
                novaxError = "视频源不存在或已被删除（id=$sourceId）"
                return
            }
            // M4：JELLYFIN 上报目标 itemId（索引快路径；冷索引 walk 有界）；挂起边界尽力收口，
            // 失败/超时 = 本会话不上报（播放零影响）
            val jfItemId = if (source.type == SourceType.JELLYFIN) {
                withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) {
                        runCatching { repository.jellyfinItemIdOf(source, path) }.getOrNull()
                    }
                }
            } else {
                null
            }
            // 2026-10-10:通知栏封面解析(spec §4.3 调度纪律)——itemId 解析点(首个 client 调用,
            // session 已暖,authenticate 短路无并发双登录)之后 async 挂起,与 jfDiscovery/字幕扫描/
            // 转码解析的既有网络窗口重叠;下方 qualityResolution 之前 await(转码 MediaItem 注入点)。
            // 失败/超时 = null(app 图标兜底),绝不阻塞起播成功。
            val artworkDeferred = lifecycleScope.async(Dispatchers.IO) {
                resolveNotificationArtwork(applicationContext, source, path, jfItemId,
                    // LOCAL 库页/文件页点播:shareOrPath(根)+源内相对路径=绝对路径,纯本地拼接零网络
                    absPath = if (source.type == SourceType.LOCAL)
                        runCatching { File(source.shareOrPath, path).path }.getOrNull() else null)
            }
            // M4：内嵌字幕发现（文本 → vtt 侧载候选并入既有手选面板；图形字幕只取存在性）。
            // 失败/超时 = null（无候选、无提示），与外部候选 listSubtitles 的「失败静默为空」同语义
            val jfDiscovery = if (source.type == SourceType.JELLYFIN) {
                withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) {
                        runCatching { repository.jellyfinSubtitleDiscovery(source, path, jfItemId) }.getOrNull()
                    }
                }
            } else {
                null
            }
            // 同目录字幕候选（SMB/WebDAV 经目录列表；失败静默为空，不阻塞播放）+ Jellyfin 内嵌候选
            val candidates = withContext(Dispatchers.IO) {
                runCatching { repository.listSubtitles(source, path) }.getOrDefault(emptyList())
            } + (jfDiscovery?.candidates ?: emptyList())
            if (source.type == SourceType.JELLYFIN && jfDiscovery != null) {
                // 2026-10-08 诊断口：清晰度面板按此码率隐藏「≥源码率」档位（e2e/排障取证点）
                Log.d("NvQuality", "source bitrate=${jfDiscovery.sourceVideoBitrate} bps")
            }
            val (autoPick, _) = if (autoLoad) SubtitleMatcher.pickAuto(candidates) else null to false
            val initialConfigs = if (autoPick != null) {
                listOfNotNull(
                    withContext(Dispatchers.IO) {
                        transcodedSubtitleConfiguration(applicationContext, autoPick, source, encodingPref)
                    },
                )
            } else {
                emptyList()
            }
            NvQSwitchTrace.mark("subtitlePrepared autoPick=${autoPick != null}")

            val snapshot = buildHistorySnapshot(sourceId, source.type.name, path, p.title, p.positionMs)
            if (engineActive) {
                // 2026-10-10 终审：复用会话分支不重建规格，artworkDeferred 无人消费——不取消则
                // JELLYFIN 冷缓存下海报下载在后台白跑（带宽白耗），显式取消
                artworkDeferred.cancel()
                applyRestoreSpeedToActiveEngine()
            } else {
                // 2026-10-10:副文字 + 封面收口(转码分支 MediaItem 注入点在 resolveJellyfinStream 内)
                val notificationSubtitle = NvNotificationMetadata.subtitle(
                    playlistIndex, playlist?.size ?: 0, source.nickname,
                )
                val artworkData = runCatching {
                    withTimeoutOrNull(6_000) { artworkDeferred.await() }
                }.getOrNull()
                // M3（2026-10-07）JELLYFIN 记忆档位：≠原画时先经 PlaybackInfo 解析转码 HLS
                // （resolveStream 永不抛异常，失败/服务器不给转码回原画——Review Focus 2：
                // 起播绝不因转码链路问题而失败）；原画与其他源不走新分支，下方既有管线零变化。
                // 档位记忆沿用本函数既有 prefs 快照口径（readPrefs 已在 IO 预读，非阻塞）；
                // null/未知值回 AUTO（NvQualityTier.fromName 契约，与 KEY_PLAYBACK_QUALITY 默认一致）
                val qualityTier = NvQualityTier.fromName(prefs?.get(KEY_PLAYBACK_QUALITY))
                val notification = NvNotificationMetadata.build(p.title, p.url, notificationSubtitle, artworkData)
                val qualityResolution =
                    if (source.type == SourceType.JELLYFIN && qualityTier != NvQualityTier.ORIGINAL) {
                        NvQSwitchTrace.mark("resolveJellyfinStream enter tier=$qualityTier")
                        resolveJellyfinStream(applicationContext, source, path, qualityTier, notification).also {
                            NvQSwitchTrace.mark("resolveJellyfinStream exit isHls=${it.mediaSource != null}")
                        }
                    } else {
                        null   // 原画/非 JELLYFIN：走下方 buildNovaxMediaSource 既有产物
                    }
                // C11 §2.2：novax 源在拿到 SourceEntity 后登记历史目标（sourceId + 源内路径）
                val mediaSource = qualityResolution?.mediaSource
                    ?: buildNovaxMediaSource(
                        source, path, repository, initialConfigs, p.title,
                        notificationSubtitle, artworkData,
                    )
                PlaybackService.start(
                    applicationContext,
                    PlaybackService.EngineSpec(
                        videoUrl = p.url,
                        title = p.title,
                        startPositionMs = p.positionMs,
                        restoreSpeed = restoreSpeed,
                        history = snapshot,
                        bufferLevel = BufferLevel.from(prefs?.get(KEY_BUFFER_LEVEL)),
                        httpTimeoutMs = timeoutMs,
                        preferredAudioLanguage = audioLang,
                        preferredTextLanguages = textLangs,
                        initialSubtitles = initialConfigs,
                        initialMediaSource = mediaSource,
                        mediaSourceFactory = qualityResolution?.mediaSourceFactory
                            ?: NvMediaSourceBuilder.NvRoutingMediaSourceFactory(
                                applicationContext, source, repository,
                            ),
                        novaxSource = source,
                        // 2026-09-15 远程模式自适应：起播前 256KB 探测链路（挂起函数内部切 IO，
                        // 慢链路（Tailscale 外网/DERP 中继）自动切远程缓冲档；LAN/失败维持原档
                        profileOverride = run {
                            val base = when (source.type) {
                                SourceType.SMB -> BufferProfile.SMB
                                SourceType.WEBDAV -> BufferProfile.WEBDAV_HTTP
                                SourceType.SFTP -> BufferProfile.SFTP
                                SourceType.LOCAL -> BufferProfile.LOCAL
                                // JELLYFIN 复用 WebDAV 缓冲档：同为 HTTP Range 随机读（/Videos/{id}/stream 静态端点）
                                SourceType.JELLYFIN -> BufferProfile.WEBDAV_HTTP
                            }
                            NvQSwitchTrace.mark("remoteProbe enter base=$base")
                            NvRemoteProfileDetector.classify(source, path, repository, base)
                                ?.also { NvQSwitchTrace.mark("remoteProbe exit → $it") }
                                ?: base.also { NvQSwitchTrace.mark("remoteProbe exit keep=$it") }
                        },
                        audioPassthrough = audioPassthrough,
                        // M3：JELLYFIN 会话记录用户所选档位（AUTO = 自动档运行中；仅状态记录与
                        // 自动档 tick 实参，不参与 URL 比较——videoUrl 恒为 novax:// 逻辑键）
                        jellyfinTier = if (source.type == SourceType.JELLYFIN) qualityTier
                            else NvQualityTier.ORIGINAL,
                        // M4：上报目标（itemId 解析失败=本会话不上报；playSessionId 仅转码会话非空）
                        jellyfinItemId = jfItemId,
                        jellyfinPlaySessionId = qualityResolution?.playSessionId,
                        // 2026-10-08：片源视频码率（清晰度面板隐藏「档位 ≥ 源码率」重复项的判据；
                        // 发现失败=0=面板全部显示，行为同旧版）
                        jellyfinSourceBitrate = jfDiscovery?.sourceVideoBitrate ?: 0L,
                        // 2026-10-10:通知栏封面/副文字(spec §4.3 四处构造点全具名口径)
                        notificationSubtitle = notificationSubtitle,
                        artworkData = artworkData,
                        // 2026-09-19：文件夹队列随规格交给服务（通知栏/锁屏上/下集）
                        queueUrls = playlist?.map { it.first } ?: emptyList(),
                        queueTitles = playlist?.map { it.second } ?: emptyList(),
                        queueIndex = playlistIndex,
                    ),
                )
                NvQSwitchTrace.mark("service.start dispatched (novax)")
                // M4：直连会话遇图形字幕——ExoPlayer 无 PGS 渲染、转码档当前也不带烧录，如实提示外挂
                // 替代。条件用 ?.mediaSource == null：覆盖「未走转码解析」与「显式转码档被服务器回退
                // 原画」两种实质直连形态（后者用户已收到「转码不可用」Toast，本提示补充字幕侧后果）。
                // 置于 else 内 = 仅真实新起播弹一次；通知栏重进/复用会话不重弹
                if (jfDiscovery?.hasImageSubtitles == true && qualityResolution?.mediaSource == null) {
                    Toast.makeText(
                        this,
                        "不支持 PGS 字幕，可外挂 srt/ass",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            novaxCandidates = candidates
            // BUG-N1（AE 实操）：目录里有任意 srt 就弹「选择字幕」会打断手势/起播——
            // 只有存在真实匹配分（≥AUTO_THRESHOLD，即同名/同基名系）的候选时才弹；
            // 无关字幕收进「字幕」菜单手选
            novaxNeedsChoice =
                autoLoad && autoPick == null && candidates.any { it.score >= SubtitleMatcher.AUTO_THRESHOLD }
        } else {
            // http/asset/file 直连：本地视频扫描父目录字幕（不回归）
            val localVideoPath = withContext(Dispatchers.IO) { resolveLocalVideoPath(p.url) }
            val subtitleCandidates = if (localVideoPath != null) {
                withContext(Dispatchers.IO) { scanLocalSubtitles(localVideoPath) }
            } else {
                emptyList()
            }
            val (autoPick, _) = if (autoLoad) SubtitleMatcher.pickAuto(subtitleCandidates) else null to false
            val initialSubtitles = if (autoPick != null) {
                listOfNotNull(
                    withContext(Dispatchers.IO) {
                        transcodedSubtitleConfiguration(applicationContext, autoPick, null, encodingPref)
                    },
                )
            } else {
                emptyList()
            }
            if (engineActive) {
                applyRestoreSpeedToActiveEngine()
            } else {
                // 2026-10-10 补丁 B:LOCAL 直连反查已索引条目(纯本地 DB+小图读,零网络)——库页/
                // 文件页 LOCAL 走裸绝对路径进本分支,此前封面恒 app 图标;反查命中则出抽帧/刮削
                // 封面 + 源昵称副文字。播放 URL 与历史/收藏键零改动;查无 = resolver 落空 =
                // app 图标垫底(补丁 A 在 resolver 边界统一兜底)
                val directTarget = localVideoPath?.let {
                    runCatching { resolveLocalDirectTarget(applicationContext, it) }.getOrNull()
                }
                val notificationSubtitle = directTarget?.first?.let {
                    NvNotificationMetadata.subtitle(playlistIndex, playlist?.size ?: 0, it.nickname)
                }
                val artworkData = runCatching {
                    resolveNotificationArtwork(applicationContext, directTarget?.first, directTarget?.second, null,
                        absPath = localVideoPath)
                }.getOrNull()
                PlaybackService.start(
                    applicationContext,
                    PlaybackService.EngineSpec(
                        videoUrl = p.url,
                        title = p.title,
                        startPositionMs = p.positionMs,
                        restoreSpeed = restoreSpeed,
                        history = buildHistorySnapshot(null, null, p.historyPath, p.title, p.positionMs),
                        bufferLevel = BufferLevel.from(prefs?.get(KEY_BUFFER_LEVEL)),
                        httpTimeoutMs = timeoutMs,
                        preferredAudioLanguage = audioLang,
                        preferredTextLanguages = textLangs,
                        initialSubtitles = initialSubtitles,
                        initialMediaSource = null,
                        mediaSourceFactory = null,
                        novaxSource = null,
                        profileOverride = null,
                        audioPassthrough = audioPassthrough,
                        // 2026-09-19：文件夹队列随规格交给服务（通知栏/锁屏上/下集）
                        queueUrls = playlist?.map { it.first } ?: emptyList(),
                        queueTitles = playlist?.map { it.second } ?: emptyList(),
                        queueIndex = playlistIndex,
                        // 2026-10-10:反查命中出封面/副文字,查无图标兜底;字段保持显式具名可审计
                        notificationSubtitle = notificationSubtitle,
                        artworkData = artworkData,
                    ),
                )
            }
            novaxCandidates = subtitleCandidates
            novaxNeedsChoice =
                autoLoad && autoPick == null && subtitleCandidates.any { it.score >= SubtitleMatcher.AUTO_THRESHOLD }
        }

        // P2 修复（重建丢倍速）：恢复倍速已随规格交给服务（或复用引擎时直调），本侧只清标记
        restoreSpeed = 1f
        novaxLoading = false
        // BUG-C-R9-05：装载窗口内被吞的 resync 补跑（服务切集的意图以服务规格为准）
        if (pendingResyncAfterLoading) {
            pendingResyncAfterLoading = false
            resyncWithServiceIfSwitched()
        }
        NvQSwitchTrace.mark("startPlayback exit (Activity 侧管线落定)")
    }

    /** 复用服务内活跃引擎时（同 URL 在播）应用 savedInstanceState 恢复的倍速 */
    private fun applyRestoreSpeedToActiveEngine() {
        if (abs(restoreSpeed - 1f) > 0.01f) {
            PlaybackService.activeEngine?.setPlaybackSpeed(restoreSpeed, pitch = 1f)
        }
    }

    // 2026-09-19：buildNovaxMediaSource / resolveLocalVideoPath / scanLocalSubtitles 已提升至
    // EpisodeMediaKit.kt 顶层（同包），Activity 起播与 Service 后台切集共用同一套管线。

    /**
     * 2026-10-12 通知重进叠加 bug：清单已改 singleTop + sessionActivity PendingIntent 带
     * CLEAR_TOP|SINGLE_TOP，通知栏/锁屏点回交付到本实例，不再压一个压扁画面的第二实例
     * （旧实例仍持引擎连接，第二实例还会重复 resolve/重建管线）。
     * - 通知意图（ACTION_VIEW 无 data + FROM_MEDIA_NOTIFICATION）：setIntent 后交给
     *   [resyncWithServiceIfSwitched]——stopped 态重进本回调先于 onStart（onStart 也会再跑
     *   一次，spec==last 幂等），暂停态重进（下拉通知栏未停 activity）走不到 onStart，此处补跑。
     * - 外部 VIEW 带 data 的新意图（播放中从文件管理器再开一个视频）：recreate() 走完整
     *   onCreate 外部管线，与 standard 模式「新意图开新内容」用户可见语义一致。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromNotification = intent.getBooleanExtra(EXTRA_FROM_MEDIA_NOTIFICATION, false)
        Log.d(
            "NvPerf",
            "PlayerActivity onNewIntent id=${identityHash()} fromNotification=$fromNotification " +
                "action=${intent.action} data=${intent.data}",
        )
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null && !fromNotification) {
            if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
                // spec §4.6：PiP 中外部 VIEW 换内容须「保持小窗播新片」。系统把 VIEW 意图
                // 交付给 PiP 任务时即展开全屏，recreate 的新实例也不保小窗（2026-10-12
                // 模拟器实证 shot_ux4_6_pip_view 旧图）——经 savedInstanceState 带标记，
                // 新实例 resumed 后原位重进小窗（onPostResume 消费）。
                reenterPipOnResume = true
            }
            recreate()
            return
        }
        resyncWithServiceIfSwitched()
    }

    /**
     * C11 §2.2 ②退后台：进度落库由 PlaybackService 承担（暂停即写 + 10s 心跳）。
     * P2/P1-6 语义：novax_background_play=false（默认）→ onStop 暂停、回前台不自动恢复（现行为）；
     * true → 不暂停，退后台/锁屏继续播放，通知栏可控。
     */
    override fun onStop() {
        Log.d(
            "NvPerf",
            "PlayerActivity onStop id=${identityHash()} changing=$isChangingConfigurations " +
                "ready=${controller?.playWhenReady ?: PlaybackService.activeEngine?.player?.playWhenReady} " +
                "orientation=$requestedOrientation",
        )
        // 2026-09-20 用户需求：返回键/顶栏返回显式退出（isFinishing）一律立即暂停——
        // 「后台播放」只对回桌面/锁屏（非 finishing 的 onStop）生效，不再对应用内返回生效
        if (isFinishing || !(playerPrefsSnapshot()?.get(KEY_BACKGROUND_PLAY) ?: false)) {
            if (controller != null) controller?.pause() else {
                PlaybackService.activeEngine?.player?.pause()
            }
            Log.d(
                "NvPerf",
                "onStop pause done bg=false controller=${controller != null} " +
                    "engine=${PlaybackService.activeEngine != null}",
            )
        } else {
            Log.d("NvPerf", "onStop keep playing bg=true")
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        Log.d(
            "NvPerf",
            "PlayerActivity onStart id=${identityHash()} " +
                "ready=${controller?.playWhenReady ?: PlaybackService.activeEngine?.player?.playWhenReady} " +
                "orientation=$requestedOrientation",
        )
        resyncWithServiceIfSwitched()
    }

    /**
     * 2026-09-19：后台被通知栏/锁屏「上/下集」切过片后的回前台再同步——
     * 以 activeSpec 重建本地起播参数并走既有「同 URL 引擎复用」路径（只重取字幕候选，
     * 不重建引擎）。守卫（审核 E 项）：服务无规格（已停）跳过；引擎未就绪（IDLE/ENDED
     * 窗口 hasActiveEngineFor=false 会触发整段重建引擎）跳过，下次 onStart 再试；
     * service 切换目标已保证非 RMVB/非软解记忆，复用路径的路由检查不会误跳。
     */
    private fun resyncWithServiceIfSwitched() {
        // 2026-09-28 缺陷 B：外部 VIEW 新意图未被本实例管线消费前，服务旧规格的 resync
        // 适配让位——此时 lastStart=新意图，与服务 spec 必然不等，会被下面的「switched」
        // 判定误当后台切集而 adopt 旧规格（onStart 与外部起播协程的时序竞态，file:// 上
        // DISPLAY_NAME 查询瞬回使 launchStart 拿到的已是被覆盖的 lastStart）
        if (pendingExternalIntent) return
        // 2026-09-28 审计 B4：流量确认待裁决期间（弹窗可见或已决待起播），服务旧规格的
        // resync 适配必须让位——onCreate 协程在确认前的挂起点会被 onStart 抢跑，
        // lastStart 被旧规格覆盖后，用户确认弹窗播的是旧片、新片被丢弃
        if (meteredPrompt || pendingMeteredLaunch != null ||
            pendingMeteredRetry != null || pendingMeteredSwitch != null
        ) return
        if (novaxLoading) {
            // BUG-C-R9-05：装载窗口吞掉 resync 会丢切集意图（StateFlow 无重放），
            // 置位等装载收敛后补跑，以服务规格为准
            pendingResyncAfterLoading = true
            return
        }
        val spec = PlaybackService.activeSpec() ?: return
        val last = lastStart ?: return
        if (spec.videoUrl == last.url) return
        val eng = PlaybackService.activeEngine ?: return
        val state = eng.player.playbackState
        if (!(eng.player.isPlaying || state == Player.STATE_READY || state == Player.STATE_BUFFERING)) return
        val newUrl = spec.videoUrl
        val novax = if (newUrl.startsWith(NvUri.SCHEME + "://")) NvUri.parse(newUrl) else null
        lastStart = StartParams(
            url = newUrl,
            title = spec.title,
            subtitleText = if (novax != null) null else MockMediaRepository.subtitleFor(newUrl),
            historyPath = spec.history.path,
            novax = novax,
            positionMs = 0L,
        )
        playlist?.indexOfFirst { it.first == newUrl }?.takeIf { it >= 0 }?.let { playlistIndex = it }
        resumePromptPositionMs = null
        novaxError = null
        novaxCandidates = emptyList()
        novaxNeedsChoice = false
        durationHintMs = 0L
        // 时长提示按新集回填（与 onCreate 同一查库逻辑），消除进度条时长提示空窗
        if (novax != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val d = runCatching {
                    NvGraph.mediaItemDao(this@PlayerActivity).findByKey(novax.first, novax.second)?.durationMs
                        ?: NvGraph.historyDao(this@PlayerActivity).findByKey(novax.first, novax.second)?.durationMs
                        ?: 0L
                }.getOrDefault(0L)
                if (d > 0L) durationHintMs = d
            }
        }
        Log.d("NvPerf", "resync with service: $newUrl")
        lifecycleScope.launch { startPlayback(lastStart!!, readPrefs()) }
    }

    /** P2 修复（Activity 重建丢进度/丢倍速）：快照当前 positionMs 与倍速，onCreate 优先恢复；
     *  另存用户手选方向（2026-09-14），重建时 onCreate 恢复而不强制回 sensorLandscape */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, lastStart?.url)
        outState.putInt(STATE_ORIENTATION, requestedOrientation)
        queueId?.let { outState.putString(STATE_QUEUE_ID, it) }
        if (reenterPipOnResume) outState.putBoolean(STATE_PIP_REENTER, true)
        (controller ?: PlaybackService.activeEngine?.player)?.let {
            outState.putLong(STATE_POSITION_MS, it.currentPosition.coerceAtLeast(0L))
            outState.putFloat(STATE_SPEED, it.playbackParameters.speed)
        }
    }

    /**
     * C11 §2.2 ③退出兜底：进度落库已迁 PlaybackService（心跳/暂停即写）。
     * P1-6：显式退出（isFinishing）→ 服务收口：记录进度；后台播关闭或已停播 → 暂停并停服务（现行为）；
     * 后台播开启且在播 → 继续后台播放（通知栏可控）。非 finishing 的系统级销毁不动服务。
     */
    override fun onDestroy() {
        Log.d("NvPerf", "PlayerActivity onDestroy id=${identityHash()} isFinishing=$isFinishing")
        cancelPendingPipReenter()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        controllerReady = false
        if (isFinishing) {
            // 2026-10-14 方向残留修复(spec 2026-10-14-orientation-exit-reset-and-rotation-reset §2.1):
            // 退出撤销本页一切方向强制,防 OEM 把最后强制值粘到任务上(用户真机: 退出后整机残留横屏)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Log.d("NvPerf", "exit orientation reset -> UNSPECIFIED id=${identityHash()}")
            PlaybackService.onHostFinished()
        }
        super.onDestroy()
    }

    /** 日志用实例标识：切方向时对比 onCreate/onDestroy 的 id 即可判定是否真被重建 */
    private fun identityHash(): String = Integer.toHexString(System.identityHashCode(this))

    /**
     * 降级 seek 激活 → Toast（提取器层只回调，UI 在主线程弹）。
     * 幂等：onCreate 可多次走（重建），重复注入同一形态 lambda 无副作用。
     */
    private fun installFallbackSeekToast() {
        NvFallbackSeekExtractor.onActivated = { msg ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_URL = "videoUrl"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_POSITION_MS = "positionMs"

        /** C11 §2.1：假数据影片的历史主键 = mediaId（缺省时以播放 URL/路径为主键） */
        const val EXTRA_HISTORY_PATH = "historyPath"

        /** P1-6：来自媒体通知/锁屏控件的重进标记（播放内容以 PlaybackService 当前规格为准） */
        const val EXTRA_FROM_MEDIA_NOTIFICATION = "fromMediaNotification"

        /** 2026-09-15：文件夹播放队列 id（PlayerPlaylistHolder 进程内槽的 Intent 句柄） */
        const val EXTRA_QUEUE_ID = "queueId"

        /** 续播提示条阈值：历史进度 ≥10s 才提示（几秒的残留进度静默续播，不打扰） */
        private const val RESUME_PROMPT_MIN_MS = 10_000L

        /** P2：Activity 重建恢复位置/倍速的 savedInstanceState 键 */
        private const val STATE_POSITION_MS = "novax_position_ms"
        private const val STATE_SPEED = "novax_speed"

        /** 2026-09-15：重建恢复队列句柄的 savedInstanceState 键 */
        private const val STATE_QUEUE_ID = "novax_queue_id"

        /** §4.1 内容隔离键:存档所属内容的 url;onCreate 仅当与当前意图一致才回放 queueId/position/speed */
        private const val STATE_URL = "novax_state_url"
        private const val STATE_PIP_REENTER = "novax_state_pip_reenter"

        /** 2026-09-14：重建恢复用户手选方向（requestedOrientation 原值）的 savedInstanceState 键 */
        private const val STATE_ORIENTATION = "novax_orientation"

        /** §5 门控(纯函数可单测):仅通知栏真冷启动允许按视频比例定向;带 savedInstanceState
         *  的重建会恢复用户手选方向,自动定向会把它打回(2026-09-14 修复回归,禁触发) */
        internal fun coldAutoOrientationGate(fromNotification: Boolean, hasSavedState: Boolean): Boolean =
            fromNotification && !hasSavedState

        /** §5 PiP 比例(纯函数可单测):真实视频比例;未知/非法回退 16:9;越界钳进 [0.42,2.39]
         *  (Rational 超域会在 Builder 抛异常,被 runCatching 变成"进入失败"而非回退)。
         *  2026-10-12 执行校注:合法域内按 gcd 约成最简整数对(1080x1920→9x16)——
         *  android.util.Rational 本就内部约分,PiP 行为等价;brief 测试段断言
         *  「9 to 16」与实现段「else -> w to h」不能同时逐字成立,按 TDD 以先写的测试为契约 */
        internal fun pipAspectRatioCode(w: Int, h: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0) return 16 to 9
            val f = w.toFloat() / h.toFloat()
            return when {
                f > 2.39f -> 239 to 100
                f < 0.42f -> 42 to 100
                else -> {
                    // 欧几里得约分(走到此分支时 w/h 均 >0,gcd 必为正,无除零)
                    var a = w
                    var b = h
                    while (b != 0) {
                        val t = a % b
                        a = b
                        b = t
                    }
                    w / a to h / a
                }
            }
        }
    }
}
