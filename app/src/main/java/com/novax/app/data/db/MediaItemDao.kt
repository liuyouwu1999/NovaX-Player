package com.novax.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 库页主查询行模型（C12 §1.6）：media_items 行 + watch_history 附带两列。
 * 已看/进度条取用：完看 = wDur>0 且 wPos ≥ wDur*0.95（与 WatchHistoryDao 继续观看窗口同式）。
 */
data class MediaItemWithWatch(
    @Embedded val item: MediaItemEntity,
    @ColumnInfo(name = "wPos") val wPos: Long?,
    @ColumnInfo(name = "wDur") val wDur: Long?,
)

/** 缩略图引用（C12 §三.7 ThumbCache LRU 淘汰被引用文件时按 id 清引用用） */
data class ThumbRef(
    val id: Long,
    val thumbnailPath: String,
)

/** kind 计数行（首页媒体库概览卡用；kind = MediaKind.name） */
data class KindCount(
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "cnt") val count: Int,
)

@Dao
interface MediaItemDao {

    @androidx.room.Transaction
    suspend fun mergeScanBatch(sourceId: Long, items: List<MediaItemEntity>) {
        val old = findByPaths(sourceId, items.map { it.path }).associateBy { it.path }
        upsertAll(items.map { com.novax.app.data.scanner.ScanMerge.merge(old[it.path], it) })
    }

    /** 扫描批量入库：调用方先查旧行合并 addedAt/thumbnailPath 等再 upsert（C12 §二.3，防覆盖抽帧成果） */
    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    /**
     * 2026-10-23 旁路封面顺带回写维度（仅已存在行——媒体库扫描建过的行）；2026-10-24 审查
     * 收敛：切集方向判定已不查库（「保持当前方向等待 + 真实尺寸一步到位」），DB 宽高仅服务
     * 分辨率徽章，无行文件不再插轻行（observeLibrary/search/刮削查询无 kind 过滤，轻行会
     * 污染展示与刮削候选）。durationMs 用 COALESCE 防 null 清掉行内已知时长
     */
    @Query(
        "UPDATE media_items SET durationMs = COALESCE(:durationMs, durationMs), width = :width, " +
            "height = :height WHERE sourceId = :sourceId AND path = :path",
    )
    suspend fun updateDimensions(
        sourceId: Long,
        path: String,
        durationMs: Long?,
        width: Int?,
        height: Int?,
    ): Int

    /** 合并用：按本批 key 取旧行 */
    @Query("SELECT * FROM media_items WHERE sourceId = :sourceId AND path IN (:paths)")
    suspend fun findByPaths(sourceId: Long, paths: List<String>): List<MediaItemEntity>

    /** 对账清扫（小源一次调用）：删除本次扫描未见的文件行 */
    @Query("DELETE FROM media_items WHERE sourceId = :sourceId AND path NOT IN (:paths)")
    suspend fun deleteNotIn(sourceId: Long, paths: List<String>): Int

    /** 对账清扫（大源分批路径删除，避免 SQL 变量上限；C12 §二.4 的补全） */
    @Query("DELETE FROM media_items WHERE sourceId = :sourceId AND path IN (:paths)")
    suspend fun deleteByPaths(sourceId: Long, paths: List<String>): Int

    /** 对账清扫：该源全部已索引路径（与本次见到的 path 集合做内存差集） */
    @Query("SELECT path FROM media_items WHERE sourceId = :sourceId")
    suspend fun allPaths(sourceId: Long): List<String>

    /**
     * 库页主查询（2026-09-15 分段重构）：源/分段/已看筛选 + 排序在 SQL 完成（千级毫秒返回）。
     * 分段（MediaType.sql，与用户分类模型一一对应）：
     * - ALL：全部视频；
     * - LONG：时长 ≥ 40 分钟（2400000ms，与 kind 升级阈值 MOVIE_MIN_MS 一致）；
     * - SHORT：时长 < 40 分钟或未知（未抽帧暂无时长的，多为短片；抽帧后自动归位）；
     * - SERIES：直通（剧集为独立聚合视图：VM 层按父目录分组并要求 ≥2 集，见 seriesGroups）。
     * 2026-09-20 用户分类模型修正：长/短视频**只按时长界定**，不再排除剧集文件夹——
     * 旧规则「同文件夹 ≥2 视频整夹归剧集」会让平铺目录（下载目录随手放几部片）长/短双空；
     * 代价是剧集条目同时出现在对应时长分段（长/短之间仍互斥且穷尽），符合「非长即短」直觉。
     * watched 子查询接 C11 watch_history（完看 = pos ≥ 95% 且 duration>0）；-1=不过滤；0=未看；1=已看。
     *
     * 2026-10-18 发布终审 BUG-4：LOCAL 源历史键换算——播放器对本地文件写历史用
     * (null, 绝对路径)（PlayerActivity.buildHistorySnapshot / FilesViewModel.mediaKeyOf /
     * NvPlaybackLauncher.localPath 同口径），而 LOCAL 的 media_items 行是 (本地源id, 源内
     * 相对路径)，旧单路 JOIN 永不相遇 → 库页已看✓/进度条/已看筛选对 LOCAL 全失效。改为
     * 双路 JOIN + COALESCE：非 LOCAL 走直连键；LOCAL 以 rtrim(源根,'/\')||mi.path 拼绝对
     * 路径（rtrim 镜像 localPath 的 trimEnd('/','\\')；mi.path 扫描入库时已 normalize），
     * 溯源 sources.type，两路互斥。watched 过滤/排序同步走 COALESCE 值。
     */
    @Query(
        "SELECT mi.*, COALESCE(wh.positionMs, whl.positionMs) AS wPos, " +
            "COALESCE(wh.durationMs, whl.durationMs) AS wDur FROM media_items mi " +
            "LEFT JOIN sources s ON s.id = mi.sourceId " +
            "LEFT JOIN watch_history wh ON wh.sourceId = mi.sourceId AND wh.path = mi.path " +
            "AND COALESCE(s.type, '') != 'LOCAL' " +
            "LEFT JOIN watch_history whl ON s.type = 'LOCAL' AND whl.sourceId IS NULL " +
            "AND whl.path = rtrim(s.shareOrPath, '/\\') || mi.path " +
            "WHERE (:sourceId IS NULL OR mi.sourceId = :sourceId) " +
            "AND (:segment = 'ALL' OR :segment = 'SERIES' " +
            "     OR (:segment = 'LONG' AND mi.durationMs >= 2400000) " +
            "     OR (:segment = 'SHORT' AND (mi.durationMs IS NULL OR mi.durationMs < 2400000))) " +
            "AND (:watched < 0 OR " +
            "     (CASE WHEN :watched = 1 THEN COALESCE(wh.durationMs, whl.durationMs, 0) > 0 " +
            "              AND COALESCE(wh.positionMs, whl.positionMs, 0) >= COALESCE(wh.durationMs, whl.durationMs, 0) * 0.95 " +
            "          ELSE (wh.id IS NULL AND whl.id IS NULL) " +
            "              OR COALESCE(wh.durationMs, whl.durationMs, 0) <= 0 " +
            "              OR COALESCE(wh.positionMs, whl.positionMs, 0) < COALESCE(wh.durationMs, whl.durationMs, 0) * 0.95 END)) " +
            "ORDER BY CASE WHEN :sort = 'NAME' THEN mi.name COLLATE NOCASE END ASC, " +
            "         CASE WHEN :sort = 'ADDED' THEN mi.addedAt END DESC, " +
            "         CASE WHEN :sort = 'SIZE' THEN mi.sizeBytes END DESC, " +
            "         CASE WHEN :sort = 'WATCHED' THEN COALESCE(wh.watchedAt, whl.watchedAt) END DESC",
    )
    fun observeLibrary(
        sourceId: Long?,
        segment: String,
        watched: Int,
        sort: String,
    ): Flow<List<MediaItemWithWatch>>

    /**
     * 搜索（C12 §五.2）：name/拼音列 LIKE；initials 命中仅当输入 ≥2 ASCII 字母（C7 规则）。
     * 2026-09-29 建库写入打通（pinyin4j，经 MediaNames.keys）：pinyin=全拼小写无分隔符
     * （`LIKE '%q%'` 包含匹配）；initials=首字母**大写**串（`LIKE :qUpper || '%'` 前缀匹配，
     * 仅查询 ≥2 ASCII 字母时生效——故键必须大写存储；非 CJK 字母数字原样大写拼入）。
     * 新行由 MediaScanner.itemOf 写入；存量空键行由 PinyinBackfill 回填（SearchViewModel init 触发）。
     */
    @Query(
        "SELECT * FROM media_items WHERE " +
            "nameLower LIKE '%' || :qLower || '%' " +
            "OR (length(:q) >= 2 AND :q GLOB '[A-Za-z]*' AND initials LIKE :qUpper || '%') " +
            "OR (pinyin LIKE '%' || :qLower || '%') " +
            "ORDER BY CASE WHEN nameLower LIKE :qLower || '%' THEN 0 ELSE 1 END, name COLLATE NOCASE LIMIT 200",
    )
    suspend fun search(q: String, qLower: String = q.lowercase(), qUpper: String = q.uppercase()): List<MediaItemEntity>

    /** 单条抽帧回写与终态流转（DONE/FAILED）；LRU 清引用同样经 updateThumb/resetThumb */
    @Query(
        "UPDATE media_items SET thumbnailPath = :path, thumbState = :state, " +
            "durationMs = :durationMs, width = :width, height = :height WHERE id = :id " +
            "AND sizeBytes = :expectedSize AND mtimeMs = :expectedMtime AND thumbState = 'RUNNING'",
    )
    suspend fun updateThumb(
        id: Long,
        path: String?,
        state: String,
        durationMs: Long?,
        width: Int?,
        height: Int?,
        expectedSize: Long,
        expectedMtime: Long,
    )

    /**
     * 可见项触发时 CAS 认领（C12 §三.5/§3.8）：NONE→RUNNING 的唯一写入点。
     * 受影响行数 = 1 才允许入队——同一 id 并发触发仅成功一次，防并发重复抽帧。
     */
    @Query("UPDATE media_items SET thumbState = 'RUNNING' WHERE id = :id AND thumbState = 'NONE'")
    suspend fun claimThumb(id: Long): Int

    /** 2026-09-18 双内核抽帧：FAILED 项的 libVLC 重试认领（NONE 也放行，与 claimThumb 同语义扩展） */
    @Query("UPDATE media_items SET thumbState = 'RUNNING' WHERE id = :id AND thumbState IN ('NONE', 'FAILED')")
    suspend fun claimThumbForRetry(id: Long): Int

    /** 冷启动对账：上一会话中断遗留的 RUNNING 复位为 NONE（防状态机卡死在 shimmer，C12 §三.5） */
    @Query("UPDATE media_items SET thumbState = 'NONE' WHERE thumbState = 'RUNNING'")
    suspend fun resetStaleRunning()

    /**
     * 2026-09-21 审计 P2：FAILED 批量复位（网络恢复时调用）。NAS 离线期间后台渐进抽帧会以
     * ≤1条/秒把 NONE 全部烧成 FAILED 墓碑，且重扫对未变化行不重置——mp4/mkv 从此无任何自动
     * 恢复通道。本查询给这些行第二次机会；失败原因无法区分（MMR 不透出 IO 错误），代价是
     * 真坏文件每次网络恢复事件多试一次（触发方有 ≥2min 防抖，见 NvThumbHolder）。
     * 与 resetThumb 同口径：必须清 thumbnailPath（防御 FAILED 行携带陈旧路径）。
     */
    @Query("UPDATE media_items SET thumbnailPath = NULL, thumbState = 'NONE' WHERE thumbState = 'FAILED'")
    suspend fun resetFailedThumbs(): Int

    /**
     * 2026-10-12 §1a:进文件夹重置本目录(仅直接子项)的 FAILED 墓碑——重试通道只有
     * 网络变化边沿,用户再进文件夹本来无重试。与 resetFailedThumbs 同口径清 thumbnailPath
     * (防御 FAILED 行携带陈旧路径);调用方必须传 escapeLikePattern(dir)(BUG-C-R5-10 同款)。
     * **绝不触碰 RUNNING**——会话内活跃行被重置会让 updateThumb 的 WHERE thumbState='RUNNING'
     * 静默 no-op → 抽好的帧被丢弃 + 双份抽帧(MediaItemDao.kt:115)。
     */
    @Query(
        "UPDATE media_items SET thumbnailPath = NULL, thumbState = 'NONE' " +
            "WHERE thumbState = 'FAILED' AND sourceId = :sourceId " +
            "AND path LIKE :escapedDir || '/%' ESCAPE '\\' AND path NOT LIKE :escapedDir || '/%/%' ESCAPE '\\'",
    )
    suspend fun resetFailedThumbsInDir(sourceId: Long, escapedDir: String): Int

    /** 抽帧回填 duration ≥ 40min 后 VIDEO 升级 MOVIE（C12 §1.3 kind 升级规则） */
    @Query("UPDATE media_items SET kind = 'MOVIE' WHERE id = :id AND kind = 'VIDEO'")
    suspend fun upgradeKindToMovie(id: Long)

    /**
     * 手动「重新抽帧」（C12 §4.4）/ LRU 淘汰引用中文件（§三.7）：清 thumbnailPath + 复位 NONE
     * （保留 durationMs/width/height 元数据）。绝不许只置状态不清路径——否则 §九B 真帧分支
     * 继续加载已删文件 → onError → 再复位 → S10 死循环。
     */
    @Query("UPDATE media_items SET thumbnailPath = NULL, thumbState = 'NONE' WHERE id = :id")
    suspend fun resetThumb(id: Long)

    /** 最近添加（C7 空输入态"最近添加"行真数据） */
    @Query("SELECT * FROM media_items ORDER BY addedAt DESC LIMIT 20")
    fun observeRecentlyAdded(): Flow<List<MediaItemEntity>>

    /** 媒体库概览（2026-09-15 首页改版）：kind 计数，纯本地 SQL 零 NAS IO */
    @Query("SELECT kind, COUNT(*) AS cnt FROM media_items GROUP BY kind")
    fun observeKindCounts(): Flow<List<KindCount>>

    /** 单条观察（Thumbnailer.observeThumb：卡片状态机订阅自己的行） */
    @Query("SELECT * FROM media_items WHERE id = :id")
    fun observeById(id: Long): Flow<MediaItemEntity?>

    /** 单条取用（requestThumb 按 id 认领成功后取实体入队） */
    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MediaItemEntity?

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun count(): Int

    @Query("SELECT * FROM media_items WHERE sourceId = :sourceId AND path = :path LIMIT 1")
    suspend fun findByKey(sourceId: Long, path: String): MediaItemEntity?

    /** 行级 Flow（BUG-C-R6-13：文件页行订阅它——抽帧完成入库后行内即时刷新缩略图/徽标） */
    @Query("SELECT * FROM media_items WHERE sourceId = :sourceId AND path = :path LIMIT 1")
    fun observeByKey(sourceId: Long, path: String): Flow<MediaItemEntity?>

    /** 删除源联动清理（C12 §八X4） */
    @Query("DELETE FROM media_items WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** 单行删除（2026-09-15 存量非媒体行清理用） */
    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 存量可疑行（2026-09-15 非媒体治理）：kind=VIDEO 且从未成功回填时长——真视频抽帧必回填
     * durationMs（DONE/FAILED 两路都写），恒 null = 元数据解析从未成功 = 高概率非媒体文件。
     * 调用方（MediaScanner.purgeSuspectRows）逐行读头复验后才删除，本查询只圈范围。
     */
    @Query(
        "SELECT * FROM media_items WHERE sourceId = :sourceId AND kind = 'VIDEO' " +
            "AND durationMs IS NULL LIMIT :limit",
    )
    suspend fun suspectVideoRows(sourceId: Long, limit: Int = 300): List<MediaItemEntity>

    /** ThumbCache LRU（C12 §三.7）：path → 行 id 映射，淘汰被引用文件时回写清引用 */
    @Query("SELECT id, thumbnailPath FROM media_items WHERE thumbnailPath IS NOT NULL")
    suspend fun allThumbRefs(): List<ThumbRef>

    /** H-4（2026-10-18 发布终审）：刮削海报引用集——purgeSource 判孤儿海报文件用
     *  （scrapePosterPath 存绝对路径；空串=TMDB 无海报墓碑，须排除） */
    @Query("SELECT DISTINCT scrapePosterPath FROM media_items WHERE scrapePosterPath IS NOT NULL AND scrapePosterPath != ''")
    suspend fun allPosterRefs(): List<String>

    // ---------------- 拼音键存量回填（2026-09-29 建库写入打通；runner 见 data/scanner/PinyinBackfill）----------------

    /**
     * 回填候选（2026-09-29）：pinyin 为空串的存量行（一期建库行未写拼音键；pinyin 列 NOT NULL
     * 无 NULL 态）。退化名（无 CJK 且无字母数字，如纯标点）keys 恒空串会永远命中本查询，
     * runner 靠「本批零进展即止」守卫收敛，不靠本查询排除。
     */
    @Query("SELECT * FROM media_items WHERE pinyin = '' LIMIT :limit")
    suspend fun pinyinBackfillCandidates(limit: Int): List<MediaItemEntity>

    /** 回填单行拼音键（仅写 pinyin/initials 两列，不触碰抽帧/刮削/addedAt 等其他列） */
    @Query("UPDATE media_items SET pinyin = :pinyin, initials = :initials WHERE id = :id")
    suspend fun updatePinyinKeys(id: Long, pinyin: String, initials: String)

    // ---------------- TMDB 刮削（MediaDb v6；写入方 data/scrape/NvScraper）----------------

    /** 单条刮削结果回写（title 传 null = 检索无命中的墓碑之一；posterPath 传 "" = 无海报墓碑、null = 下载失败可重试） */
    @Query(
        "UPDATE media_items SET scrapedTitle = :title, scrapeOverview = :overview, " +
            "scrapePosterPath = :posterPath, scrapeYear = :year, scrapeRating = :rating " +
            "WHERE id = :id",
    )
    suspend fun updateScrape(
        id: Long,
        title: String?,
        overview: String?,
        posterPath: String?,
        year: Int?,
        rating: Double?,
    )

    /** 按路径集合批量回写（剧集：同文件夹整组共用一次 /search/tv 结果） */
    @Query(
        "UPDATE media_items SET scrapedTitle = :title, scrapeOverview = :overview, " +
            "scrapePosterPath = :posterPath, scrapeYear = :year, scrapeRating = :rating " +
            "WHERE sourceId = :sourceId AND path IN (:paths)",
    )
    suspend fun updateScrapeByPaths(
        sourceId: Long,
        paths: List<String>,
        title: String?,
        overview: String?,
        posterPath: String?,
        year: Int?,
        rating: Double?,
    )

    /**
     * 刮削候选（按源分批）：还没有海报归宿的行。
     * 语义：scrapePosterPath NULL = 未刮/下载失败（可重试）；"" = 已刮但 TMDB 无海报或
     * 检索无命中（空串墓碑，不再重试）；非空 = 已完成。消费方映射时 blank→null。
     * 千级规模 + (sourceId,path) 前缀索引，无需新索引。
     */
    @Query(
        "SELECT * FROM media_items WHERE sourceId = :sourceId AND kind IN ('MOVIE','VIDEO') " +
            "AND scrapePosterPath IS NULL ORDER BY addedAt DESC LIMIT :limit",
    )
    suspend fun scrapePendingMovies(sourceId: Long, limit: Int = 200): List<MediaItemEntity>

    /** 刮削候选（剧集行）：EPISODE 且无海报归宿。父目录分组在刮削器内存完成 */
    @Query(
        "SELECT * FROM media_items WHERE sourceId = :sourceId AND kind = 'EPISODE' " +
            "AND scrapePosterPath IS NULL ORDER BY path LIMIT :limit",
    )
    suspend fun scrapePendingEpisodes(sourceId: Long, limit: Int = 500): List<MediaItemEntity>

    /** 清缓存联动（SettingsViewModel.clearCache）：海报文件已随 cacheDir 删除，引用一并清空，下次刮削自愈 */
    @Query("UPDATE media_items SET scrapePosterPath = NULL")
    suspend fun clearAllScrapePosterRefs()

    /** 详情页「重新刮削」：清单条全部刮削字段 → 重新成为刮削候选 */
    @Query(
        "UPDATE media_items SET scrapedTitle = NULL, scrapeOverview = NULL, " +
            "scrapePosterPath = NULL, scrapeYear = NULL, scrapeRating = NULL WHERE id = :id",
    )
    suspend fun resetScrape(id: Long)

    /** 详情页/剧集聚合用：同目录（不含子目录）的视频行，按名称排序。
     *  ESCAPE 转义（BUG-C-R5-10）：dir 含 %/_ 时按通配符命中相似目录（Season_1 ↔ SeasonX1）串门，
     *  调用方必须传 escapeLikePattern(dir) */
    @Query(
        "SELECT * FROM media_items WHERE sourceId = :sourceId " +
            "AND path LIKE :dir || '/%' ESCAPE '\\' AND path NOT LIKE :dir || '/%/%' ESCAPE '\\' " +
            "AND kind != 'IMAGE' ORDER BY name COLLATE NOCASE",
    )
    suspend fun videosInDir(sourceId: Long, dir: String): List<MediaItemEntity>
}

/** LIKE 模式转义（配合 videosInDir 的 ESCAPE '\\'；BUG-C-R5-10） */
fun escapeLikePattern(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
