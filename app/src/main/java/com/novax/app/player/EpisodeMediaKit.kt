@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.novax.app.data.NvGraph
import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.WatchKind
import com.novax.app.data.playback.NvDataSourceFactory
import com.novax.app.data.playback.NvMediaSourceBuilder
import com.novax.app.data.playback.NvUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2026-09-19 单集媒体准备共享件：PlayerActivity 起播与 PlaybackService 后台切集（通知栏上/下集）
 * 共用同一套「取源 → 字幕候选 → 自动挑选 → 编码转码 → MediaSource 构建」管线，避免两处漂移。
 *
 * - [prepareEpisodeMedia]：按 novax:// / 本地直连双路准备（IO 上下文安全，可从任意协程调用）；
 *   novax 源不存在时返回 null（调用方各自裁决：Activity 报错误态 / Service Toast 放弃）。
 * - [buildNovaxMediaSource]：自 PlayerActivity 提升为顶层（SMB 断流自愈 onStalled 回调接线随迁）。
 * - [buildEpisodeHistorySnapshot] / [episodeResumePositionMs]：历史登记与续播裁决共用件
 *   （后台切集无续播提示条语义，≥10s 静默续播，不足从 0 起，与 Activity 阈值一致）。
 * - [resolveLocalVideoPath] / [scanLocalSubtitles]：本地视频识别与同目录字幕扫描
 *   （自 PlayerActivity 提升，Activity 侧改为委托调用）。
 */
class EpisodeMedia(
    /** novax 源实体（直连为 null）；EngineSpec.novaxSource 直接消费 */
    val source: SourceEntity?,
    val subtitleConfigs: List<MediaItem.SubtitleConfiguration>,
    val candidates: List<SubtitleCandidate>,
    val initialMediaSource: MediaSource?,
    val mediaSourceFactory: MediaSource.Factory?,
    /** 远程模式档位探测（novax 源；直连恒 null） */
    val profileOverride: BufferProfile?,
)

suspend fun prepareEpisodeMedia(
    context: Context,
    url: String,
    novax: Pair<Long, String>?,
    title: String,
    autoLoad: Boolean,
    encodingPref: String?,
    notificationSubtitle: String? = null,
    artworkData: ByteArray? = null,
    /** Task 7 i18n：通知标题兜底词（调用方以 R.string.player_video 解析后传入） */
    fallbackTitle: String,
    /** 2026-10-24：字幕首选语言（与 Activity 起播同口径传入 pickAuto，通知栏切集不降级） */
    preferredLanguages: List<String> = emptyList(),
): EpisodeMedia? {
    val repository = NvGraph.repository()
    return if (novax != null) {
        val (sourceId, path) = novax
        val source = withContext(Dispatchers.IO) {
            runCatching { NvGraph.sourceDao(context).getById(sourceId) }.getOrNull()
        } ?: return null
        val candidates = withContext(Dispatchers.IO) {
            runCatching { repository.listSubtitles(source, path) }.getOrDefault(emptyList())
        }
        val (autoPick, _) = if (autoLoad) SubtitleMatcher.pickAuto(candidates, preferredLanguages) else null to false
        val configs = if (autoPick != null) {
            listOfNotNull(
                withContext(Dispatchers.IO) {
                    runCatching {
                        transcodedSubtitleConfiguration(context, autoPick, source, encodingPref)
                    }.getOrNull()
                },
            )
        } else {
            emptyList()
        }
        val base = when (source.type) {
            SourceType.SMB -> BufferProfile.SMB
            SourceType.WEBDAV -> BufferProfile.WEBDAV_HTTP
            SourceType.SFTP -> BufferProfile.SFTP
            SourceType.LOCAL -> BufferProfile.LOCAL
            // JELLYFIN 复用 WebDAV 缓冲档：同为 HTTP Range 随机读（/Videos/{id}/stream 静态端点）
            SourceType.JELLYFIN -> BufferProfile.WEBDAV_HTTP
        }
        val profile = NvRemoteProfileDetector.classify(source, path, repository, base) ?: base
        EpisodeMedia(
            source = source,
            subtitleConfigs = configs,
            candidates = candidates,
            initialMediaSource = buildNovaxMediaSource(source, path, repository, configs, title, notificationSubtitle, artworkData, fallbackTitle),
            mediaSourceFactory = NvMediaSourceBuilder.NvRoutingMediaSourceFactory(context, source, repository),
            profileOverride = profile,
        )
    } else {
        // 直连分支同样切 IO（BUG-C-R2-02/MG-A：resolveLocalVideoPath 的 File.isFile 与
        // scanLocalSubtitles 的 listFiles 是磁盘 IO，Service 的 serviceScope=Main.immediate
        // 调用时原本落在主线程）
        val localVideoPath = withContext(Dispatchers.IO) { resolveLocalVideoPath(url) }
        val candidates = if (localVideoPath != null) {
            withContext(Dispatchers.IO) { scanLocalSubtitles(localVideoPath) }
        } else {
            emptyList()
        }
        val (autoPick, _) = if (autoLoad) SubtitleMatcher.pickAuto(candidates, preferredLanguages) else null to false
        val configs = if (autoPick != null) {
            listOfNotNull(
                withContext(Dispatchers.IO) {
                    runCatching {
                        transcodedSubtitleConfiguration(context, autoPick, null, encodingPref)
                    }.getOrNull()
                },
            )
        } else {
            emptyList()
        }
        EpisodeMedia(
            source = null,
            subtitleConfigs = configs,
            candidates = candidates,
            initialMediaSource = null,
            mediaSourceFactory = null,
            profileOverride = null,
        )
    }
}

/** novax:// MediaSource 构建（字幕配置已转码为本地 UTF-8 临时文件；连接复用同仓库工厂） */
fun buildNovaxMediaSource(
    source: SourceEntity,
    path: String,
    repository: NvSourceRepository,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration>,
    title: String,
    notificationSubtitle: String? = null,
    artworkData: ByteArray? = null,
    /** Task 7 i18n：通知标题兜底词由调用方按应用语言资源传入（原「视频」硬编码移除） */
    fallbackTitle: String,
): MediaSource {
    val factory = NvDataSourceFactory(
        source,
        path,
        repository,
        // 2026-09-15 播放卡死修复：SMB 连接链被服务端回收后读不到任何字节时，
        // 主动重建整条连接链并重新 prepare（现场：画面冻住 + 进度条在走 + seek 也无效）。
        // forceRePrepare 会重新 open DataSource，而 openReadBlocking 已在识别到
        // 「DiskShare has already been closed」时驱逐本通道并按通道重建连接。
        // BUG-C-R3-03：回调发生在 SMB 读侧线程（open→NvStallRecovery.recover 同步调），
        // forceRePrepare 必须回主线程操作 player（media3 线程约定）。
        onStalled = {
            android.util.Log.w("NvPerf", "smb read stalled -> force re-prepare")
            forceRePrepareOnMainThread()
        },
    )
    // 读侧僵死自愈的统一入口（side-load 字幕/引擎重建后重新打开的流也会经此回调）
    NvStallRecovery.handler = {
        android.util.Log.w("NvPerf", "smb read stalled (routing factory) -> force re-prepare")
        forceRePrepareOnMainThread()
    }
    val uri = NvUri.forSource(source, path)
    val mediaItem = MediaItem.Builder()
        .setUri(uri)
        .setSubtitleConfigurations(subtitleConfigs)
        // P1-6:通知栏元数据;2026-10-10 补副文字与封面字节(spec §4.1)
        .setMediaMetadata(
            NvNotificationMetadata.build(title, uri.toString(), notificationSubtitle, artworkData, fallbackTitle).toMediaMetadata(),
        )
        .build()
    return DefaultMediaSourceFactory(
        factory,
        // 2026-09-30 坏索引 MKV 降级 seek：MatroskaExtractor 包上 NvFallbackSeekExtractor
        //（SeekHead 无 Cues 的转载片源 → Unseekable→估算 SeekMap+Cluster 重同步），
        // 有索引片源/其他容器零行为变化
        NvMediaSourceBuilder.nvExtractorsFactory(),
    )
        .setSubtitleParserFactory(NvShiftedSubtitleParserFactory())
        .createMediaSource(mediaItem)
}

/** 强制重 prepare 统一回主线程执行（引擎 player 只能在其所属线程操作，BUG-C-R3-03） */
private fun forceRePrepareOnMainThread() {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        PlaybackService.activeEngine?.forceRePrepare()
    }
}

// ---------------- M3（2026-10-07）JELLYFIN 清晰度切换：转码 HLS 换流 ----------------

/**
 * Jellyfin 转码 HLS 的专用工厂：忽略传入 MediaItem 的 URI，固定产出到 [hlsUrl] 的 HLS 源
 * （spec §6：videoUrl 保持 novax:// 逻辑键，真实流地址在工厂侧闭合——引擎自适应重建
 * （rebuildAdaptive 置 initialMediaSource=null 走工厂+videoUrl）时也据此拿到转码流而非原画）。
 * 注意 [createMediaSource] 保留传入 MediaItem 的其余字段（side-load 字幕配置等）仅改写 URI。
 */
class JellyfinHlsMediaSourceFactory(
    private val context: Context,
    private val hlsUrl: String,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        HlsMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(mediaItem.buildUpon().setUri(hlsUrl).build())
}

/**
 * M4（2026-10-07）：resolveJellyfinStream 结果。原画/回退 = mediaSource null + 既有
 * NvRoutingMediaSourceFactory；转码 = HLS 源 + JellyfinHlsMediaSourceFactory。
 * playSessionId 仅转码会话非空（PlaybackInfo 返回），供服务器进度上报锚定会话。
 */
data class JellyfinStreamResolution(
    val mediaSource: MediaSource?,
    val mediaSourceFactory: MediaSource.Factory,
    val playSessionId: String?,
)

/**
 * 按 JELLYFIN 记忆档位解析本次起播的流（M3，spec §6；suspend，内部切 IO，任意协程可调）：
 * - 非 JELLYFIN 源或原画（ORIGINAL）：返回 (null, 既有 NvRoutingMediaSourceFactory)——调用方
 *   继续走既有 buildNovaxMediaSource 管线，原画路径零变化；
 * - 档位 ≠ 原画：resolveStream（PlaybackInfo）解析转码会话，服务器给转码 → (HLS 源,
 *   JellyfinHlsMediaSourceFactory)；resolveStream 永不抛异常，失败/不给转码回原画
 *   （Review Focus 2：起播绝不因转码链路问题而失败）。
 * 防御兜底（M3 review）：本函数是 Activity 起播 / switchQuality 双调用方的公共汇点，
 * 必不成为起播失败的新路径——resolveStream 的「永不抛」契约之外（仓库访问、HLS 源构建等）
 * 任何异常同样记日志回原画（取消除外，向上透传）。
 * 字幕配置由外层既有管线拼装（转码会话的文本字幕由服务器侧处理，vtt 侧载 M4 再接）。
 * notification 仅落转码 HLS 的 MediaItem(:268);原画回退分支的元数据由调用方 buildNovaxMediaSource/EngineSpec 承载。
 */
suspend fun resolveJellyfinStream(
    context: Context,
    source: SourceEntity,
    path: String,
    tier: NvQualityTier,
    notification: NvNotificationMetadata? = null,
): JellyfinStreamResolution = runCatching {
    if (source.type != SourceType.JELLYFIN || tier == NvQualityTier.ORIGINAL) {
        return@runCatching JellyfinStreamResolution(
            null, NvMediaSourceBuilder.NvRoutingMediaSourceFactory(context, source, NvGraph.repository()), null,
        )
    }
    val plan = NvGraph.repository().jellyfin.resolveStream(source, path, tier)
    if (!plan.isHls || plan.hlsUrl == null) {
        // I-3（终审）+ re-review 修正：仅「用户显式选了转码档（P20M–P2M）而服务器不给」才提示
        // ——诉求是省流量，静默回原画 = 菜单安慰剂；本函数每次起播/切档只调用一次，Toast
        // 天然一次性，无需去重。AUTO 不提示：resolveStream 对 AUTO 按设计早退原画（KDoc
        // 自述），从未尝试转码、无任何失败，弹「转码不可用」是误报——而 AUTO 是 fromName
        // 的默认回退值，误报会打在每次默认配置的冷起播/新集起播上。
        if (tier == NvQualityTier.AUTO) {
            android.util.Log.d("NvQuality", "resolveJellyfinStream AUTO 起播原画（设计行为，未尝试转码）")
        } else if (plan.transcodeNotNeeded) {
            // 2026-10-08 用户缺陷：档位上限 ≥ 源码率时服务器不给转码 URL，原画直连就是该档位下
            // 最佳画质——正常结论，不得走「转码不可用」误导文案
            android.util.Log.i(
                "NvQuality",
                "resolveJellyfinStream 档位≥源码率 -> 原画直连 tier=$tier srcBps=${plan.sourceVideoBitrate}",
            )
            toastSourceBelowTier(context)
        } else {
            android.util.Log.w("NvQuality", "resolveJellyfinStream 转码不可用 -> 回退原画 tier=$tier")
            toastTranscodeUnavailable(context)
        }
        return@runCatching JellyfinStreamResolution(
            null, NvMediaSourceBuilder.NvRoutingMediaSourceFactory(context, source, NvGraph.repository()), null,
        )
    }
    val hlsItem = MediaItem.Builder().setUri(plan.hlsUrl).apply {
        // 2026-10-10:转码分支此前是裸 MediaItem 零元数据——光杆「NovaX」卡片的真根因(spec §1)
        notification?.let { setMediaMetadata(it.toMediaMetadata()) }
    }.build()
    val hls = HlsMediaSource.Factory(DefaultDataSource.Factory(context))
        .createMediaSource(hlsItem)
    JellyfinStreamResolution(hls, JellyfinHlsMediaSourceFactory(context, plan.hlsUrl), plan.playSessionId)
}.getOrElse { e ->
    if (e is kotlinx.coroutines.CancellationException) throw e
    if (tier == NvQualityTier.AUTO) {
        android.util.Log.d("NvQuality", "resolveJellyfinStream AUTO 兜底原画（设计行为）", e)
    } else {
        android.util.Log.w("NvQuality", "resolveJellyfinStream failed -> 原画兜底 tier=$tier", e)
        toastTranscodeUnavailable(context)
    }
    JellyfinStreamResolution(
        null, NvMediaSourceBuilder.NvRoutingMediaSourceFactory(context, source, NvGraph.repository()), null,
    )
}

/**
 * 「转码不可用，已回退原画」一次性提示（I-3 终审）：切到主线程再弹（本函数虽约定由
 * 主线程协程调用，但作为公共汇点不依赖该约定——Toast 在无 Looper 线程直接抛）。
 */
private fun toastTranscodeUnavailable(context: Context) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(
            context,
            context.getString(com.novax.app.R.string.player_transcode_unavailable_toast),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * 「档位 ≥ 源码率，按原画直连」友好提示（2026-10-08 用户缺陷）： LENGTH_LONG 给用户
 * 足够读的时间。2026-10-09 T2 精简为一句「原画已是对该档位的最佳画质」；2026-10-10 S7
 * 清理闲置参数 tier/sourceBitrateBps（文案常量化后不再入参，上批台账遗留）。
 */
private fun toastSourceBelowTier(context: Context) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(
            context,
            context.getString(com.novax.app.R.string.player_original_best_toast),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

/** 历史登记快照（C11 §5.2；kind 按路径/标题 SxEy 判 EPISODE/MOVIE） */
fun buildEpisodeHistorySnapshot(
    sourceId: Long?,
    sourceType: String?,
    path: String,
    title: String,
    positionMs: Long,
): NvHistoryWriter.Snapshot {
    val parsed = parseSeasonEpisodeMeta(path) ?: parseSeasonEpisodeMeta(title)
    return NvHistoryWriter.Snapshot(
        sourceId = sourceId,
        sourceType = sourceType,
        path = path,
        title = title,
        kind = parsed?.let { WatchKind.EPISODE.name } ?: WatchKind.MOVIE.name,
        positionMs = positionMs,
        durationMs = 0L,
        seasonNumber = parsed?.first,
        episodeNumber = parsed?.second,
    )
}

/**
 * 续播时长查询（切集共用）：库表已知时长，0=未知。与 [episodeResumePositionMs] 配套
 * （2026-09-28 审计 A：三份切集续播裁决收敛于此，此前 RMVB 私有一份带「距片尾看完」
 * 规则、Service 共用件没有，同口径靠人肉对齐）。
 */
suspend fun episodeResumeDurationMs(context: Context, sourceId: Long?, path: String): Long =
    if (sourceId == null) 0L else withContext(Dispatchers.IO) {
        runCatching {
            NvGraph.mediaItemDao(context).findByKey(sourceId, path)?.durationMs ?: 0L
        }.getOrDefault(0L)
    }

/**
 * 续播裁决（切集共用，2026-09-28 收敛统一口径）：
 * - resumeOnOpen 关 → 从 0；
 * - 历史进度 <10s → 从 0（几秒残留静默丢弃，不打扰）；
 * - 距片尾 ≤10s 视为看完 → 从 0（[durationMs] 已知时；避免切集起点粘在上一集片尾）。
 * 无提示条语义（调用方需要「上次看到」提示条的走 PlayerActivity.launchStart 既有裁决）。
 */
suspend fun episodeResumePositionMs(
    context: Context,
    sourceId: Long?,
    path: String,
    resumeOnOpen: Boolean,
    durationMs: Long = 0L,
): Long {
    if (!resumeOnOpen) return 0L
    val saved = withContext(Dispatchers.IO) {
        runCatching {
            NvGraph.historyDao(context).getForResume(sourceId, path)?.positionMs ?: 0L
        }.getOrDefault(0L)
    }
    if (saved < 10_000L) return 0L
    if (durationMs > 0L && durationMs - saved <= 10_000L) return 0L
    return saved
}

/**
 * M4：Jellyfin 服务器续播位置 ms（本地历史无有效进度时的兜底——换设备/换 App 首看同片）。
 * 非 JELLYFIN 源 / sourceId 缺 / 查询失败一律 0（起播绝不因服务器续播查询而受阻）。
 * 时延口径：元数据 GET 已在 client 层收窄 5s socket 读超时（Task 3）；冷索引 walk 段
 * 沿通用 30s（与起播必需的 openStreamBlocking 同一条路径）——调用方 withTimeoutOrNull
 * 为二次尽力收口（launchStart 已做）。
 */
suspend fun jellyfinServerResumePositionMs(context: Context, sourceId: Long?, path: String): Long =
    if (sourceId == null) 0L else withContext(Dispatchers.IO) {
        runCatching {
            val source = NvGraph.sourceDao(context).getById(sourceId) ?: return@runCatching 0L
            NvGraph.repository().jellyfinServerResumePositionMs(source, path) ?: 0L
        }.getOrDefault(0L)
    }

/** 标题/路径解析 SxEy（如 "S1E05"/"s01e5"） */
fun parseSeasonEpisodeMeta(text: String): Pair<Int, Int>? {
    val m = SEASON_EPISODE_META_REGEX.find(text) ?: return null
    val s = m.groupValues[1].toIntOrNull() ?: return null
    val e = m.groupValues[2].toIntOrNull() ?: return null
    return s to e
}

private val SEASON_EPISODE_META_REGEX = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")

/** 本地视频路径识别：file:// scheme，或无 scheme 且真实存在的绝对路径（如 /storage/...） */
fun resolveLocalVideoPath(url: String): String? = when {
    url.startsWith("file://") ->
        runCatching { Uri.parse(url).path }.getOrNull()?.takeIf { File(it).isFile }
    !url.contains("://") -> File(url).takeIf { it.isFile }?.absolutePath
    else -> null   // http/https/asset 等远端源不扫描
}

/** 父目录列表 → SubtitleMatcher 评分；目录不可读时返回空（不报错） */
fun scanLocalSubtitles(videoPath: String): List<SubtitleCandidate> {
    val directoryFiles = runCatching {
        File(videoPath).parentFile?.listFiles()?.map { it.absolutePath }
    }.getOrNull().orEmpty()
    return SubtitleMatcher.findSubtitles(videoPath, directoryFiles)
}
