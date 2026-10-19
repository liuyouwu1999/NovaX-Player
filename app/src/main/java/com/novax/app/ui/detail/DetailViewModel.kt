package com.novax.app.ui.detail

import android.app.Application
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novax.app.R
import com.novax.app.data.MockMediaRepository
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.scrape.NvScrapeHolder
import com.novax.app.data.scrape.NvScraper
import com.novax.app.ui.home.resolutionBadge
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ---- 详情页数据模型（C4-detail.md §四 状态模型） ----

data class CastMember(
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
)

data class TechInfo(
    val badges: List<String>,
    val details: List<Pair<String, String>> = emptyList(), // 标题-值（"详细信息"折叠区）
)

data class ResumePoint(
    val label: String,        // "S2E3" 或 null（电影时为空串）
    val positionMs: Long,
    val remainingMin: Int,
)

data class EpisodeItem(
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String,
    val durationMin: Int,
    val thumbUrl: String?,
    val watched: Boolean,
    val progress: Float?,     // 未看完的续播进度，null = 未开始/已看完
    val resumeMs: Long? = null,
)

data class Season(
    val number: Int,
    val episodeCount: Int,
)

data class TvInfo(
    val seasons: List<Season>,
    val selectedSeason: Int,
    val episodes: List<EpisodeItem>,   // 当前选中季的集，empty → S6 无选集
)

sealed interface NvDetailUiState {
    data object Loading : NvDetailUiState
    data class Error(val message: String) : NvDetailUiState
    data class Success(
        val mediaId: String,
        val title: String,
        val fileName: String,
        val metaLine: String,
        val backdropUrl: String?,
        val overview: String?,          // null → S3 刮削失败降级
        val cast: List<CastMember>,     // empty → 区块整体不渲染（S5）
        val techInfo: TechInfo,
        val resume: ResumePoint?,
        val isSeries: Boolean,
        val chasingLine: String? = null, // 剧集"看到 S2E3"
        val tv: TvInfo? = null,
        val noEpisodesAtAll: Boolean = false, // S7 整部无任何集
        val scraping: Boolean = false,  // S9 正在重新刮削
        // BUG-C-R7-01：真实条目的收藏键（LOCAL=null+绝对路径 / 远程=(sourceId,path)）——
        // 原实现对真实条目也写死 (null, mediaId)，收藏后成首页死卡、三页心形态互斥；
        // null = mock/演示条目沿用旧 (null, mediaId) 语义
        val favoriteKey: Pair<Long?, String>? = null,
    ) : NvDetailUiState
}

/**
 * 详情页 ViewModel（2026-09-15 接真）：
 * - mediaId 为纯数字 = media_items 真实条目：标题/简介/海报取刮削列（NvScraper 产出），
 *   剧集 = 同文件夹真集数（videosInDir，与媒体库 SERIES 聚合同一模型），续播取 watch_history；
 * - 其余（假数据影片 id）保留 Mock 分支作为内置样片详情。
 */
class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext

    private val _state = MutableStateFlow<NvDetailUiState>(NvDetailUiState.Loading)
    val state: StateFlow<NvDetailUiState> = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(mediaId: String) {
        if (loadedId == mediaId) return
        loadedId = mediaId
        _state.value = NvDetailUiState.Loading
        viewModelScope.launch {
            val realId = mediaId.substringBefore('#').toLongOrNull()
            _state.value = if (realId != null) {
                buildRealState(mediaId, realId)
            } else {
                buildMockState(mediaId)
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        val cur = _state.value as? NvDetailUiState.Success ?: return
        val tv = cur.tv ?: return
        // 真实条目目前只有单「季」（文件夹 = 剧， season 恒 1）；mock 分支保持原语义
        val episodes = if (cur.mediaId.toLongOrNull() != null) {
            tv.episodes
        } else {
            episodesForMock(cur.mediaId, seasonNumber)
        }
        _state.value = cur.copy(tv = tv.copy(selectedSeason = seasonNumber, episodes = episodes))
    }

    // 3.3 长按头图 / Error 重试 → 重新加载（S9 进度 + 完成刷新）
    fun rescrape() {
        val mediaId = (_state.value as? NvDetailUiState.Success)?.mediaId ?: loadedId ?: return
        val realId = mediaId.substringBefore('#').toLongOrNull()
        val cur = _state.value
        _state.value = if (cur is NvDetailUiState.Success) cur.copy(scraping = true) else NvDetailUiState.Loading
        viewModelScope.launch {
            if (realId != null) {
                val dao = MediaDb.get(appContext).mediaItemDao()
                val item = dao.findById(realId)
                if (item == null) {
                    // 条目已被删：不能带着 scraping=true 返回（否则详情页永久转圈）
                    _state.value = NvDetailUiState.Error(appContext.getString(R.string.detail_err_deleted))
                    return@launch
                }
                dao.resetScrape(realId)
                NvScraper.get(appContext).scrapeSource(item.sourceId)
                // 轮询到刮削真正结束：isRunning（任务在途）与 progress.running（进度条目）
                // 双条件。刮削被并发取消时 NvScrapeHolder 会 clear 掉条目——此处同样按
                // 终态退出，绝不能等「条目存在且 running=false」（会永久挂起、按钮无响应）。
                // 超时兜底：即使状态异常也强制刷新，不让转圈无限持续。
                withTimeoutOrNull(180_000) {
                    while (NvScraper.get(appContext).isRunning(item.sourceId) ||
                        NvScrapeHolder.progress.value[item.sourceId]?.running == true
                    ) {
                        delay(300)
                    }
                }
                _state.value = buildRealState(mediaId, realId)
            } else {
                delay(1200)
                _state.value = buildMockState(mediaId)
            }
        }
    }

    // ---------------- 真实条目（media_items） ----------------

    private suspend fun buildRealState(mediaId: String, realId: Long): NvDetailUiState {
        val db = MediaDb.get(appContext)
        val item = db.mediaItemDao().findById(realId)
            ?: return NvDetailUiState.Error(appContext.getString(R.string.detail_err_not_found, mediaId))
        val source = runCatching { db.sourceDao().getById(item.sourceId) }.getOrNull()

        val dir = item.path.substringBeforeLast('/', "")
        val siblings = if (dir.isBlank()) listOf(item) else db.mediaItemDao().videosInDir(item.sourceId, com.novax.app.data.db.escapeLikePattern(dir))
        // 隐私口径（Task 9）：真续播点三级链读（明文行 → hash 行解密 → 会话内进度兜底）；
        // 返回行恒为明文形态。集列表展示侧回填同口径（Task 14 已接，见下方 episodeHistory）
        val history = com.novax.app.player.NvHistoryDirect.findForPlayback(
            db,
            com.novax.app.data.privacy.NvPrivacyAccess.get(appContext),
            item.sourceId,
            item.path,
        )
        val isSeries = siblings.size >= 2 && item.kind == com.novax.app.data.db.MediaKind.EPISODE

        val scrapedTitle = item.scrapedTitle?.takeIf { it.isNotBlank() }
        val title = scrapedTitle ?: item.name
        val poster = item.scrapePosterPath?.takeIf { it.isNotBlank() }
        val backdropUrl = poster?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }

        val parts = buildList {
            item.scrapeYear?.let { add("$it") }
            resolutionBadge(item.width, item.height)?.let { add(it) }
            if (!isSeries) item.durationMs?.takeIf { it > 0 }?.let {
                val mins = (it / 60_000L).toInt()
                add(appContext.resources.getQuantityString(R.plurals.detail_duration_min, mins, mins))
            }
            if (isSeries) {
                add(appContext.resources.getQuantityString(R.plurals.detail_total_episodes, siblings.size, siblings.size))
            }
            item.sizeBytes.takeIf { it > 0 }?.let {
                add(Formatter.formatShortFileSize(appContext, it))
            }
            item.scrapeRating?.takeIf { it > 0.0 }?.let { add("★ ${"%.1f".format(it)}") }
        }
        val metaLine = parts.joinToString(" · ").ifBlank { item.path.substringAfterLast('/') }

        val badges = buildList {
            resolutionBadge(item.width, item.height)?.let { add(it) }
            item.sizeBytes.takeIf { it > 0 }?.let { add(Formatter.formatShortFileSize(appContext, it)) }
            item.durationMs?.takeIf { it > 0 }?.let {
                val mins = (it / 60_000L).toInt()
                add(appContext.resources.getQuantityString(R.plurals.detail_duration_min, mins, mins))
            }
        }
        val tech = TechInfo(
            badges = badges,
            details = listOf(
                appContext.getString(R.string.detail_info_path) to item.path,
                appContext.getString(R.string.detail_info_source) to
                    (source?.nickname ?: appContext.getString(R.string.detail_unknown_source)),
                appContext.getString(R.string.detail_info_resolution) to
                    listOfNotNull(item.width, item.height).joinToString("×")
                        .ifBlank { appContext.getString(R.string.detail_unknown) },
                appContext.getString(R.string.detail_info_added) to
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(item.addedAt)),
            ),
        )

        val resume = history?.let { h ->
            ResumePoint(
                label = "",
                positionMs = h.positionMs,
                remainingMin = if ((item.durationMs ?: 0L) > 0) {
                    (((item.durationMs ?: 0L) - h.positionMs).coerceAtLeast(0) / 60_000L).toInt()
                } else 0,
            )
        }

        // BUG-C-R7-01：真实条目收藏键与库页 favoriteKeyOf 同构
        val favoriteKey = if (source?.type == com.novax.app.data.db.SourceType.LOCAL) {
            null to com.novax.app.ui.home.NvPlaybackLauncher.localPath(source.shareOrPath, item.path)
        } else {
            item.sourceId to item.path
        }

        // BUG-C-R7-05/MG-C：集列表回填观看历史（watched✓/进度条/续播位）——原实现恒 false/null，
        // 详情页「继续播放」主按钮必落第 1 集而标签却称继续播放。
        // 隐私口径（Task 14，T9 移交展示点）：getForResume 只查明文行，锁定内容的历史行在
        // hash 键上会整列漏判——改走 findForPlayback 三级链（明文 → hash 行解密 → 会话内进度），
        // 返回行恒为明文形态；行内仅消费 watched/progress/resumeMs，不外露密文形态
        val episodeHistory = runCatching {
            siblings.mapNotNull { sib ->
                com.novax.app.player.NvHistoryDirect.findForPlayback(
                    db,
                    com.novax.app.data.privacy.NvPrivacyAccess.get(appContext),
                    sib.sourceId,
                    sib.path,
                )?.let { h -> (sib.sourceId to sib.path) to h }
            }.toMap()
        }.getOrDefault(emptyMap())
        val episodes = siblings.mapIndexed { i, sib ->
            val h = episodeHistory[sib.sourceId to sib.path]
            EpisodeItem(
                season = 1,
                number = i + 1,
                title = sib.scrapedTitle?.takeIf { it.isNotBlank() } ?: sib.name,
                overview = sib.scrapeOverview.orEmpty(),
                durationMin = ((sib.durationMs ?: 0L) / 60_000L).toInt(),
                thumbUrl = sib.scrapePosterPath?.takeIf { it.isNotBlank() }
                    ?.let { android.net.Uri.fromFile(java.io.File(it)).toString() },
                // replay 归零口径（NvHistoryDirect.findForPlayback）：已看完行返回 positionMs=0
                // （落库原值不动），故 0 亦是看完信号——否则已看完集的 ✓ 徽标会消失
                watched = h != null && (h.durationMs ?: 0L) > 0L &&
                    (h.positionMs == 0L || h.positionMs >= (h.durationMs ?: 0L) * 0.95),
                progress = h?.takeIf {
                    (it.durationMs ?: 0L) > 0L && it.positionMs >= 15_000L &&
                        it.positionMs < (it.durationMs ?: 0L) * 0.95
                }?.let { (it.positionMs.toFloat() / (it.durationMs ?: 1L)).coerceIn(0f, 1f) },
                resumeMs = h?.positionMs?.takeIf { it >= 10_000L },
            )
        }
        val tv = if (isSeries) {
            TvInfo(seasons = listOf(Season(1, episodes.size)), selectedSeason = 1, episodes = episodes)
        } else null

        return NvDetailUiState.Success(
            mediaId = mediaId,
            title = title,
            fileName = item.name,
            metaLine = metaLine,
            backdropUrl = backdropUrl,
            overview = item.scrapeOverview?.takeIf { it.isNotBlank() },
            cast = emptyList(), // TMDB 演员表未刮取：区块整体不渲染（S5 既有规则）
            techInfo = tech,
            resume = resume,
            isSeries = isSeries,
            chasingLine = null,
            tv = tv,
            noEpisodesAtAll = isSeries && episodes.isEmpty(),
            favoriteKey = favoriteKey,
        )
    }

    // ---------------- 假数据影片（内置样片） ----------------

    private fun buildMockState(mediaId: String, scraping: Boolean = false): NvDetailUiState {
        val item = MockMediaRepository.byId(mediaId)
            ?: return NvDetailUiState.Error(appContext.getString(R.string.detail_err_not_found, mediaId))

        val fileName = item.path.substringAfterLast('/').ifBlank { item.title }
        val cont = MockMediaRepository.continueWatching.firstOrNull { it.id == mediaId }
        val scrapeFailed = mediaId == "m11"

        val backdropUrl: String? = if (scrapeFailed) null else cont?.backdropUrl

        val resume = cont?.let {
            ResumePoint(
                label = it.episodeLabel ?: "",
                positionMs = (it.progress * 90 * 60_000L).toLong(),
                remainingMin = ((1f - it.progress) * item.durationMinutes).toInt(),
            )
        }

        val cast = if (scrapeFailed || mediaId == "m6") emptyList() else listOf(
            CastMember("马修·麦康纳", "饰 Cooper"),
            CastMember("安妮·海瑟薇", "饰 Brand"),
            CastMember("杰西卡·查斯坦", "饰 Murph"),
        )

        val badges = if (scrapeFailed) {
            listOf(item.resolution, "10.2 GB")
        } else {
            listOf(item.resolution, item.codec, "HDR10", "AAC 5.1", "10.2 GB")
        }
        val tech = TechInfo(
            badges = badges,
            details = listOf(
                appContext.getString(R.string.detail_info_path) to item.path,
                appContext.getString(R.string.detail_info_source) to item.source,
                appContext.getString(R.string.detail_info_codec) to item.codec,
                appContext.getString(R.string.detail_info_resolution) to item.resolution,
            ),
        )

        val metaLine = when {
            scrapeFailed -> "${item.resolution} · 10.2 GB"
            item.kind == "series" -> {
                val eps = allEpisodesMock(mediaId)
                val seasons = eps.map { it.season }.distinct().size
                val q = appContext.resources
                // 评审 fix#2：第二段用裸计数键（「12 集」），「共」只在第一段出现——
                // zh 还原原串「共 2 季 12 集」；detail_total_episodes（共 N 集）留给真实剧集路径
                "${item.year} · " +
                    q.getQuantityString(R.plurals.detail_total_seasons, seasons, seasons) + " " +
                    q.getQuantityString(R.plurals.detail_episodes_count, eps.size, eps.size) +
                    " · ★ 8.7"
            }
            else -> {
                val q = appContext.resources.getQuantityString(
                    R.plurals.detail_duration_min, item.durationMinutes, item.durationMinutes,
                )
                "${item.year} · $q · ★ 8.7"
            }
        }

        val tv = if (item.kind != "series") null else {
            val eps = allEpisodesMock(mediaId)
            val seasons = eps.map { it.season }.distinct().sorted()
            val list = seasons.map { s -> Season(s, eps.count { it.season == s }) }
            TvInfo(
                seasons = list,
                selectedSeason = list.firstOrNull()?.number ?: 1,
                episodes = episodesForMock(mediaId, list.firstOrNull()?.number ?: 1),
            )
        }

        return NvDetailUiState.Success(
            mediaId = mediaId,
            title = item.title,
            fileName = fileName,
            metaLine = metaLine,
            backdropUrl = backdropUrl,
            overview = if (scrapeFailed || mediaId == "m6") null
            else appContext.getString(
                R.string.detail_mock_overview, item.source, item.codec, item.resolution,
            ),
            cast = cast,
            techInfo = tech,
            resume = resume,
            isSeries = item.kind == "series",
            chasingLine = if (item.kind == "series") {
                cont?.episodeLabel?.let { appContext.getString(R.string.detail_chasing, it) }
            } else null,
            tv = tv,
            noEpisodesAtAll = item.kind == "series" && allEpisodesMock(mediaId).isEmpty(),
            scraping = scraping,
        )
    }

    /** 假数据剧集分集（仅 mock id 使用） */
    private fun allEpisodesMock(mediaId: String): List<EpisodeItem> = when (mediaId) {
        "m4" -> (1..6).map { mockEp(it, 1, "第${it}集", 58, watched = it <= 4, progress = if (it == 5) 0.35f else null, resume = if (it == 5) 18 * 60_000L else null) }
        "m5" -> (1..9).map { mockEp(it, 1, "感染纪元 · $it", 55, watched = it <= 2) }
        "m8" -> (1..6).map { mockEp(it, 1, "夜之国度 · 第${it}夜", 62, watched = false) }
        "m10" -> (1..10).map { mockEp(it, 1, "将军 · 第${it}话", 60, watched = it <= 2, progress = if (it == 3) 0.78f else null, resume = if (it == 3) 14 * 60_000L else null) }
        "m12" -> (1..10).map { mockEp(it, 2, "熊家餐馆 S2 · 第${it}集", 34, watched = it <= 3) }
        else -> emptyList()
    }

    private fun episodesForMock(mediaId: String, season: Int): List<EpisodeItem> =
        allEpisodesMock(mediaId).filter { it.season == season }

    private fun mockEp(
        n: Int,
        season: Int,
        title: String,
        duration: Int,
        watched: Boolean,
        progress: Float? = null,
        resume: Long? = null,
    ) = EpisodeItem(
        season = season,
        number = n,
        title = title,
        overview = "本集简介占位：危机加深，人物关系迎来新的转折点。",
        durationMin = duration,
        thumbUrl = null,
        watched = watched,
        progress = progress,
        resumeMs = resume,
    )
}
