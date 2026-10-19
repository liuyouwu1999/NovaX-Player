package com.novax.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 首页继续观看行（2026-09-15 首页改版）：watch_history 行 + media_items 真帧缩略图引用。
 * LEFT JOIN 保行：media_items 无匹配（本地直连/假数据影片/未索引）时 thumbnailPath=null，
 * UI 落 NvPosterFallback 渐变兜底。media_items (sourceId,path) 唯一 → 不产生行膨胀。
 */
data class ContinueWatchingRow(
    @Embedded val entry: WatchHistoryEntity,
    @ColumnInfo(name = "thumbPath") val thumbnailPath: String?,
)

/** 观看历史 DAO（C11 §5.1 签名） */
@Dao
interface WatchHistoryDao {

    /** 统一 upsert 的查找半步：NULL 须用 IS 比较（= 对 NULL 永假） */
    @Query("SELECT * FROM watch_history WHERE sourceId IS :sourceId AND path = :path LIMIT 1")
    suspend fun findByKey(sourceId: Long?, path: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)   // 仅经 NvHistoryWriter 携带 id 调用
    suspend fun insert(e: WatchHistoryEntity)

    /**
     * 首页继续观看：看过 ≥15s（<15s 视为误触）、未看完（<95%）、时长已知、排除音频，
     * 且排除内置样片/公网测试流（asset:// 与 gtv-videos-bucket / media.w3.org /
     * vjs.zencdn.net——这些永远不会是用户真实内容），最近优先。
     * LIMIT 50 预放大（BUG-C-R7-08）：UI 层还要按失效源/样片过滤后取 12，
     * 先 LIMIT 12 会让被滤条目无回补、横排偏少。
     * 与 ui/home/HomeDataMapping.filterContinueWatching 纯函数条件保持一致。
     */
    @Query(
        "SELECT wh.*, mi.thumbnailPath AS thumbPath FROM watch_history wh " +
            "LEFT JOIN media_items mi ON mi.sourceId = wh.sourceId AND mi.path = wh.path " +
            "WHERE wh.positionMs >= 15000 AND wh.durationMs > 0 " +
            "AND wh.positionMs < wh.durationMs * 0.95 AND wh.kind IN ('MOVIE','SERIES','EPISODE') " +
            "AND wh.path NOT LIKE 'asset://%' " +
            "AND wh.path NOT LIKE '%gtv-videos-bucket%' " +
            "AND wh.path NOT LIKE '%media.w3.org%' " +
            "AND wh.path NOT LIKE '%vjs.zencdn.net%' " +
            "ORDER BY wh.watchedAt DESC LIMIT 50",
    )
    fun observeContinueWatching(): Flow<List<ContinueWatchingRow>>

    /** 继续观看长按「从继续观看移除」：标记为已看完（positionMs=durationMs），自然退出 <95% 窗口 */
    @Query("UPDATE watch_history SET positionMs = durationMs WHERE id = :id")
    suspend fun markWatchedToEnd(id: Long)

    /** 继续观看长按「清除这条记录」：按主键删除该条历史 */
    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM watch_history WHERE sourceId IS :sourceId AND path = :path LIMIT 1")
    suspend fun getForResume(sourceId: Long?, path: String): WatchHistoryEntity?

    /** 全表流（BUG-C-R6-03：文件页行回填不能订阅继续观看 Flow——它的 LIMIT 12/<95% 过滤
     *  会让「已看完✓」永不出现、清除历史后残留幽灵进度、12 条外无回填） */
    @Query("SELECT * FROM watch_history")
    fun observeAllForBackfill(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watch_history)")
    suspend fun hasAny(): Boolean

    /** 条数 Flow（首页 hasAnyFlow / 后续历史管理页计数用） */
    @Query("SELECT COUNT(*) FROM watch_history")
    fun observeCount(): Flow<Int>

    /** 设置-隐私「清除观看历史」入口（C11 §2.3 权威规格） */
    @Query("DELETE FROM watch_history")
    suspend fun clearAll()

    /** 隐私 purgeScope 用：取该源全部行（密文行 path=哈希，范围判定须取回内存过滤） */
    @Query("SELECT * FROM watch_history WHERE sourceId = :sourceId")
    suspend fun allForSource(sourceId: Long): List<WatchHistoryEntity>

    /**
     * 隐私迁移/计数用（隐私计划 Task 13）：null 安全取该源全部行。LOCAL 源的历史键为
     * (NULL, 绝对路径)，[allForSource] 的 `=` 比较对 NULL 永假查不到——加锁迁移明文行
     * 与无痕清历史计数必须走本方法（`IS` 对 NULL 与具体值都命中）。
     */
    @Query("SELECT * FROM watch_history WHERE sourceId IS :sourceId")
    suspend fun allForKey(sourceId: Long?): List<WatchHistoryEntity>

    /** 隐私 purgeScope 用：按主键批量删（调用方自行跳过空表） */
    @Query("DELETE FROM watch_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
