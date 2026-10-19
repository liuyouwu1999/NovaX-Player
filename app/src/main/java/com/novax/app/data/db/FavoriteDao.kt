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

    /** toggle 流程的查找半步（NULL 用 IS 比较） */
    @Query("SELECT * FROM favorites WHERE sourceId IS :sourceId AND path = :path LIMIT 1")
    suspend fun findByKey(sourceId: Long?, path: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE sourceId IS :sourceId AND path = :path")
    suspend fun deleteByKey(sourceId: Long?, path: String)

    @Query("DELETE FROM favorites")
    suspend fun clearAll()
}
