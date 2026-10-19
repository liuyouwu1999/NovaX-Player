package com.novax.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 首页收藏行（2026-09-15 首页改版）：favorites 行 + media_items 真帧缩略图（可空，同继续观看 JOIN 语义） */
data class FavoriteWithThumb(
    @Embedded val favorite: FavoriteEntity,
    @ColumnInfo(name = "thumbPath") val thumbnailPath: String?,
)

/** 收藏 DAO（C11 §5.1 签名） */
@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    /** 首页收藏 rail 用：带 media_items 真帧（LOCAL 直连收藏 sourceId=null 不命中 → 渐变兜底） */
    @Query(
        "SELECT f.*, mi.thumbnailPath AS thumbPath FROM favorites f " +
            "LEFT JOIN media_items mi ON mi.sourceId = f.sourceId AND mi.path = f.path " +
            "ORDER BY f.addedAt DESC",
    )
    fun observeAllWithThumb(): Flow<List<FavoriteWithThumb>>

    @Query("SELECT * FROM favorites WHERE sourceId IS :sourceId AND path = :path LIMIT 1")
    fun observeByKey(sourceId: Long?, path: String): Flow<FavoriteEntity?>

    /**
     * 星标态展示双查（隐私计划 Task 14）：明文键 + hash 键一起命中——锁定内容收藏行在
     * hash 键上（path=HMAC hex），单查明文键会让详情页星标漏判为未收藏。
     * 详情页 toggle 走 NvFavoriteDirect（自身已双键查重），与本查询口径互补不冲突。
     */
    @Query("SELECT * FROM favorites WHERE sourceId IS :sourceId AND (path = :path OR path = :hashedPath) LIMIT 1")
    fun observeByKeyEither(sourceId: Long?, path: String, hashedPath: String): Flow<FavoriteEntity?>

    /** toggle 流程的查找半步（NULL 用 IS 比较） */
    @Query("SELECT * FROM favorites WHERE sourceId IS :sourceId AND path = :path LIMIT 1")
    suspend fun findByKey(sourceId: Long?, path: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE sourceId IS :sourceId AND path = :path")
    suspend fun deleteByKey(sourceId: Long?, path: String)

    @Query("DELETE FROM favorites")
    suspend fun clearAll()

    /** 隐私 purgeScope 用：取该源全部行（密文行 path=哈希，范围判定须取回内存过滤） */
    @Query("SELECT * FROM favorites WHERE sourceId = :sourceId")
    suspend fun allForSource(sourceId: Long): List<FavoriteEntity>

    /** 隐私迁移/计数用（隐私计划 Task 13）：null 安全取该源全部行（LOCAL 收藏 sourceId 为 NULL，语义同 WatchHistoryDao.allForKey） */
    @Query("SELECT * FROM favorites WHERE sourceId IS :sourceId")
    suspend fun allForKey(sourceId: Long?): List<FavoriteEntity>

    /** 隐私 purgeScope 用：按主键批量删（调用方自行跳过空表） */
    @Query("DELETE FROM favorites WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
