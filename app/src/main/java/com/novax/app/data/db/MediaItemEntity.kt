package com.novax.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体类型（C12 §1.1）。一期写入 MOVIE / EPISODE / VIDEO / IMAGE；
 * SERIES 为聚合概念不落行（库页"剧集" Tab = EPISODE 按父目录分组聚合，查询期完成）；
 * IMAGE 为图片（jpg/png/heic 等，不抽帧：thumbnailPath=文件路径、durationMs=0）。
 */
enum class MediaKind { MOVIE, SERIES, EPISODE, VIDEO, IMAGE }

/** 抽帧状态（C12 §1.1；写入点唯一：claimThumb(CAS)/updateThumb(终态)/resetThumb，§1.6） */
enum class ThumbState { NONE, RUNNING, DONE, FAILED }

/**
 * 媒体条目索引（C12 §1.1，MediaDb v3 新增表，文件级一行）。
 *
 * 唯一键 (sourceId, path)：与 watch_history / favorites 同范式。
 * kind 判定（C12 §1.3）：扫描期 EPISODE 正则命中 → EPISODE，否则 VIDEO；
 * 抽帧回填 durationMs ≥ 40min 后由 Thumbnailer 升级 MOVIE。
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["sourceId", "path"], unique = true),
        Index("name"),
        Index("kind"),
        Index("addedAt"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: Long,               // sources.id（索引只覆盖已配源，非空）
    val path: String,                 // 源内统一路径（NvPaths 规范："/" 分隔）
    val name: String,                 // 展示名 = 去扩展名文件名
    val nameLower: String,            // 小写冗余列（LIKE 忽略大小写不走函数，吃索引）
    val pinyin: String = "",          // 2026-09-29 建库生效：全拼小写无分隔（MediaNames.keys/pinyin4j，扫描写入+PinyinBackfill 回填）
    val initials: String = "",        // 2026-09-29 建库生效：首字母大写串（DAO initials LIKE :qUpper||'%' 前缀匹配约定）
    val sizeBytes: Long,              // list() 已带
    val mtimeMs: Long,                // list() 已带；变更检测键（内容变了才重抽帧）
    val kind: MediaKind,              // Room 按 name 存 TEXT
    val durationMs: Long? = null,     // 抽帧时由 retriever 元数据回填（C12 §三.6）
    val width: Int? = null,           // 同上
    val height: Int? = null,          // 同上
    val thumbnailPath: String? = null,// 绝对路径 cacheDir/thumbs/{hash}.jpg；null=未抽/失败
    val thumbState: String = ThumbState.NONE.name,
    val scrapedTitle: String? = null, // TMDB 匹配标题（刮削器写入）
    // TMDB 刮削元数据（MediaDb v6；刮削器 NvScraper 写入，ScanMerge 重扫保留）
    val scrapeOverview: String? = null,   // 简介（zh-CN 优先）
    val scrapePosterPath: String? = null, // 本地海报绝对路径 cacheDir/scrape_posters/{hash}.jpg
    val scrapeYear: Int? = null,          // 上映/首播年
    val scrapeRating: Double? = null,     // TMDB 评分 0~10
    val addedAt: Long,                // 首次入库时间；重扫不重置（"最近添加"排序键）
)
