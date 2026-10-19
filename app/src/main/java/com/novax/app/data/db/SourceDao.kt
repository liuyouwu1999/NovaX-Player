package com.novax.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** NAS 源增删改查 */
@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY id ASC")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources ORDER BY id ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observeById(id: Long): Flow<SourceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<SourceEntity>): List<Long>

    @Update
    suspend fun update(source: SourceEntity)

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 连通性检查后回写状态（配合 SmbClient/WebDavClient connect） */
    @Query("UPDATE sources SET lastCheckedAt = :checkedAt, status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, checkedAt: Long, status: SourceStatus)
}
