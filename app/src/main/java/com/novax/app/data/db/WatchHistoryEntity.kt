package com.novax.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 内容种类（C11 §2.1；Room 按 name 存 TEXT） */
enum class WatchKind { MOVIE, SERIES, EPISODE, AUDIO }

/**
 * 观看历史（C11 §2.1，MediaDb v2 新增表）。
 *
 * 唯一键 (sourceId, path)：SQLite 对 UNIQUE 索引中的 NULL 视为互异，因此**所有写入必须
 * 经 NvHistoryWriter 的 upsert（事务内 find→insert/REPLACE）**，禁止绕过直接 @Insert 新行。
 *
 * 字段语义：
 * - sourceId：novax:// 源 id；本地/网络直连/假数据影片为 null
 * - sourceType：SourceType.name，可空
 * - path：统一主键（源内路径 / 本地绝对路径 / URL；假数据影片 = mediaId）
 * - durationMs：探流取得；未知存 0（此时不进继续观看）
 */
@Entity(
    tableName = "watch_history",
    indices = [Index(value = ["sourceId", "path"], unique = true)],
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: Long?,
    val sourceType: String?,
    val path: String,
    val title: String,
    val kind: String,           // WatchKind.name
    val positionMs: Long,       // 上次播放位置
    val durationMs: Long,       // 内容总时长（未知存 0）
    val watchedAt: Long,        // 最近一次写入时间，epoch millis（排序键）
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    // 隐私功能 Task 2 尾插（MediaDb v7，ALTER TABLE ADD COLUMN 可空无 DEFAULT 一致）：
    // 非 null ⇒ 密文行（path=HMAC hex、title=标题密文、本列=路径密文）；null ⇒ 旧明文行
    val pathCipher: String? = null,
)
