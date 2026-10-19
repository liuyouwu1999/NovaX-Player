package com.novax.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 每源扫描状态 DAO（C12 §1.6 签名） */
@Dao
interface ScanStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: ScanStateEntity)

    @Query("SELECT * FROM scan_state")
    fun observeAll(): Flow<List<ScanStateEntity>>

    @Query("SELECT * FROM scan_state WHERE sourceId = :sourceId")
    suspend fun get(sourceId: Long): ScanStateEntity?

    /** 2026-09-21 审计 P2：FAILED 复位后重启渐进 pass 时取全部扫描态一次性快照 */
    @Query("SELECT * FROM scan_state")
    suspend fun getAll(): List<ScanStateEntity>

    @Query("DELETE FROM scan_state WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: Long)
}
