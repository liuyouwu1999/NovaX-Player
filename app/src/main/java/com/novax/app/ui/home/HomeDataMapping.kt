package com.novax.app.ui.home

import com.novax.app.R
import com.novax.app.data.MockMediaRepository
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.ui.components.UiMessage
import kotlin.math.ceil

/**
 * C11 §2.4/§3.3 首页真数据映射的**纯函数**层（无 Android 依赖，JUnit 可测）：
 * 续播过滤窗口、剩余分钟 chip、真/假收藏合并去重、收藏键。
 */

// ---------- 继续观看窗口（Files 文件行进度回填用，>5s 且 <95%） ----------

/** 续播窗口：>5s 起播且未看完（positionMs < durationMs × 0.95）；durationMs=0（时长未知）排除 */
fun isContinueWindow(positionMs: Long, durationMs: Long): Boolean =
    positionMs > 5_000L && durationMs > 0L && positionMs < durationMs * 0.95

// ---------- 首页继续观看准入（比 Files 进度窗更严格，与 observeContinueWatching SQL 一致） ----------

/** 首页继续观看上限（最近优先） */
const val CONTINUE_WATCHING_LIMIT = 12

/**
 * 用户真实内容判定：asset:// 内置样片与公网测试流 host
 * （gtv-videos-bucket / media.w3.org / vjs.zencdn.net）永远不会是用户内容，一律排除。
 */
fun isUserContentPath(path: String): Boolean =
    !path.startsWith("asset://") &&
        !path.contains("gtv-videos-bucket") &&
        !path.contains("media.w3.org") &&
        !path.contains("vjs.zencdn.net")

/** 首页继续观看门槛：看过 ≥15s（误触不算）且未看完（<95%）、时长已知 */
fun isContinueEntry(positionMs: Long, durationMs: Long): Boolean =
    positionMs >= 15_000L && durationMs > 0L && positionMs < durationMs * 0.95

/**
 * 首页继续观看行过滤（VM 层 join sources）：
 * 用户真实内容 + ≥15s 未看完窗口 + 源仍存在（sourceId=null 为直连/本地/假数据，视为有效），
 * 最近优先（watchedAt DESC），最多 [CONTINUE_WATCHING_LIMIT] 条。
 */
fun filterContinueWatching(
    entities: List<WatchHistoryEntity>,
    validSourceIds: Set<Long>,
    limit: Int = CONTINUE_WATCHING_LIMIT,
): List<WatchHistoryEntity> =
    entities.asSequence()
        .filter { isUserContentPath(it.path) }
        .filter { isContinueEntry(it.positionMs, it.durationMs) }
        .filter { it.sourceId == null || it.sourceId in validSourceIds }
        .sortedByDescending { it.watchedAt }
        .take(limit)
        .toList()

/**
 * 卡片 chip（C11 §2.4）：季集号优先 S%dE%02d；真视频无时长 → 「继续观看」；
 * 否则「剩 X 分钟」= max(1, ceil((duration - position) / 60s))。
 * i18n（spec §1.3）：返回 UiMessage 载体（不落语言），UI 层 .text() 渲染；分钟数走 plurals count。
 */
fun continueChip(entity: WatchHistoryEntity): UiMessage? {
    entity.seasonNumber?.let { s ->
        entity.episodeNumber?.let { e ->
            return UiMessage(R.string.home_chip_episode, listOf(s.toString(), "%02d".format(e)))
        }
    }
    if (entity.durationMs <= 0L) return UiMessage(R.string.home_chip_continue)
    val minutes = ceil((entity.durationMs - entity.positionMs).coerceAtLeast(60_000L) / 60_000.0).toInt()
    return UiMessage(R.plurals.home_chip_remaining_min, listOf(minutes), count = minutes)
}

/** 进度 0f..1f（duration 未知时 0） */
fun continueProgress(entity: WatchHistoryEntity): Float =
    if (entity.durationMs > 0L) (entity.positionMs.toFloat() / entity.durationMs).coerceIn(0f, 1f) else 0f

// ---------- 收藏键与真/假合并 ----------

/** 唯一键字符串（sourceId 可空；可空段用 "n" 前缀避免 "5:x" 与 null 混淆） */
fun favoriteKey(sourceId: Long?, path: String): String =
    "${sourceId ?: "n"}:$path"

/**
 * 首页收藏行 = 真收藏（前，observeAll 已按 addedAt DESC）+ 假收藏（后，按原顺序追加）。
 * 去重键 (sourceId, path)：用户对假影片点收藏后该条升级为「真」，只在真区出现一次。
 */
fun mergeFavorites(real: List<FavoriteEntity>, mock: List<MockMediaRepository.PosterEntry>): List<FavoriteRow> {
    val coveredMockIds = real.filter { it.sourceId == null }.map { it.path }.toSet()
    return real.map { it.toFavoriteRow() } +
        mock.filter { it.id !in coveredMockIds }.map { it.toFavoriteRow() }
}

fun FavoriteEntity.toFavoriteRow(thumbnailPath: String? = null): FavoriteRow = FavoriteRow(
    key = favoriteKey(sourceId, path),
    title = title,
    subtitle = null,
    posterUrl = null,               // 真文件无海报 → NvPosterFallback
    entity = this,
    mockId = null,
    thumbPath = thumbnailPath,
)

fun MockMediaRepository.PosterEntry.toFavoriteRow(): FavoriteRow = FavoriteRow(
    key = favoriteKey(null, id),
    title = title,
    subtitle = subtitle,
    posterUrl = posterUrl,
    entity = null,
    mockId = id,
)

// ---------- UI 行模型（真/假统一，点击行为由 entity/mockId 是否为空决定） ----------

/** 继续观看行：entity 非空 = 真历史（续播 positionMs）；mockId 非空 = mock 占位卡 */
data class ContinueRow(
    val key: String,
    val title: String,
    val chip: UiMessage?,           // i18n：UiMessage 载体（spec §1.3），UI 层 .text() 渲染
    val progress: Float,
    val imageUrl: String?,          // mock 有 backdrop；真视频 null → NvPosterFallback
    val thumbPath: String? = null,  // 2026-09-15：media_items 真帧绝对路径（可空）
    val entity: WatchHistoryEntity? = null,
    val mockId: String? = null,
)

/**
 * 展示层标题清洗（2026-09-16 UI 审计 P2）：继续观看直接展示原始文件名时，
 * 「低俗小说.Pulp.Fiction.1994.BluRay-XXX.mkv」这类发布标记是纯噪音——
 * 去扩展名；命中「.YYYY.」年份标记即把其后整段（BluRay/1080p/压制组等）截掉。
 * 只影响展示，不改库内数据；无年份标记的普通文件名保持原样。
 */
fun displayTitleOf(raw: String): String {
    var t = raw.substringBeforeLast('.')
    val cleaned = t.replace(Regex("""\.(19|20)\d{2}\..*$"""), "")
    if (cleaned.isNotBlank()) t = cleaned
    return t
}

/** 带真帧缩略图版本（2026-09-15 首页改版：JOIN media_items；null → NvPosterFallback 渐变兜底） */
fun WatchHistoryEntity.toContinueRow(thumbnailPath: String?): ContinueRow = ContinueRow(
    key = favoriteKey(sourceId, path),
    title = displayTitleOf(title),
    chip = continueChip(this),
    progress = continueProgress(this),
    imageUrl = null,
    thumbPath = thumbnailPath,
    entity = this,
)

fun MockMediaRepository.ContinueItem.toContinueRow(): ContinueRow = ContinueRow(
    key = favoriteKey(null, id),
    title = title,
    // episodeLabel=null（电影样片）→ chip=null 不渲染空胶囊（评审 fix#1 回归）
    chip = episodeLabel?.let { UiMessage(R.string.home_chip_text, listOf(it)) },
    progress = progress,
    imageUrl = backdropUrl,
    mockId = id,
)

/** 收藏行 */
data class FavoriteRow(
    val key: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val entity: FavoriteEntity?,    // 真收藏
    val mockId: String?,            // 假收藏（mediaId）
    val thumbPath: String? = null,  // 2026-09-15：media_items 真帧绝对路径（可空）
)

// ---------- 2026-09-15 首页改版纯函数 ----------

/**
 * 分辨率徽标（最近添加卡右上角）：按抽帧回填的 width/height 分档。
 * 宽高未知（未抽帧/图片无元数据）→ null = 不显示徽标（不造假）。
 * 分档：4K（≥3800 宽或 ≥2000 高）> 2K（≥2500 或 ≥1300）> 1080P（≥1600 宽）。
 */
fun resolutionBadge(width: Int?, height: Int?): String? {
    if (width == null || height == null) return null
    if (width <= 0 || height <= 0) return null
    return when {
        width >= 3800 || height >= 2000 -> "4K"
        width >= 2500 || height >= 1300 -> "2K"
        width >= 1600 -> "1080P"
        else -> null
    }
}

/**
 * 媒体库概览分段（i18n 改造）：纯函数返回 (计数, plurals 资源) 对——非零段保留、全空库 →
 * 空表（UI 隐藏概览卡）。四语文案由 VM 层 getQuantityString 按计数解析后拼接（spec §1.3）。
 */
fun libraryOverviewSegments(kindCounts: Map<String, Int>): List<Pair<Int, Int>> =
    listOf(
        (kindCounts["MOVIE"] ?: 0) to R.plurals.home_overview_movies,
        (kindCounts["EPISODE"] ?: 0) to R.plurals.home_overview_episodes,
        (kindCounts["VIDEO"] ?: 0) to R.plurals.home_overview_videos,
        (kindCounts["IMAGE"] ?: 0) to R.plurals.home_overview_images,
    ).filter { (n, _) -> n > 0 }
