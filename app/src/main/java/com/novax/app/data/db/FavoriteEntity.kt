package com.novax.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏（C11 §3.1，MediaDb v2 新增表）。
 *
 * 唯一键 (sourceId, path) 与 watch_history 同一套 NULL 唯一索引注意事项：
 * 增删一律经「先查后写/删」的 toggle 流程（HomeViewModel/FilesViewModel/DetailScreen）。
 * - sourceId：NAS 源 id；本地文件/假数据影片为 null
 * - path：源内路径 / 本地绝对路径；假数据影片 = mediaId（如 "m1"）
 */
@Entity(
    tableName = "favorites",
    indices = [Index(value = ["sourceId", "path"], unique = true)],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: Long?,
    val path: String,
    val title: String,
    val kind: String,       // WatchKind.name（与历史共用枚举）
    val addedAt: Long,      // epoch millis，收藏行排序键
)
