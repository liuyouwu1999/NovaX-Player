package com.novax.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 隐私锁规则 DAO（隐私功能计划 Task 2 产出；消费方 NvPrivacyAccess/NvOrphanTracker/FilesViewModel） */
@Dao
interface PrivacyRuleDao {

    /** 常驻监数据源（NvPrivacyAccess 初始化处 observeAll → index.replaceAll） */
    @Query("SELECT * FROM privacy_rules")
    fun observeAll(): Flow<List<PrivacyRuleEntity>>

    /**
     * 新建/整行覆盖（@Insert REPLACE：命中 UNIQUE(sourceId,keyHash) 时删旧行换新行）。
     * 改名跟随的 migrate 也走它（带原 id 覆盖，保留主键与 createdAt）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PrivacyRuleEntity): Long

    @Query("DELETE FROM privacy_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 删源级联（无 FK 惯例，删源处手工调用） */
    @Query("DELETE FROM privacy_rules WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** 忘码重置全清（NvSecureWipe 管道内调用） */
    @Query("DELETE FROM privacy_rules")
    suspend fun clearAll()

    /** 一次性全量（规则量小，启动补挂/测试用） */
    @Query("SELECT * FROM privacy_rules")
    suspend fun all(): List<PrivacyRuleEntity>
}
