package com.novax.app.data.scanner

import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind

/**
 * 扫描批量 upsert 的合并规则（C12 §二.3）——纯函数，可单测。
 *
 * 原则：扫描只负责"发现"，绝不覆盖抽帧/刮削成果与入库时间。
 * 修正（相对 C12 §二.3 片段）：kind 保底 MOVIE 不降级——扫描期只能判 EPISODE/VIDEO，
 * MOVIE 是抽帧回填 duration 后的升级结果；重扫时 fresh 恒为 VIDEO，直接覆盖会把
 * 千级电影条目集体打回 VIDEO 且不再回升（DONE 行不会重新抽帧）。
 *
 * 图片行（kind=IMAGE）：thumbnailPath 就是文件路径本身（不抽帧），恒跟随 fresh，
 * 不走"内容变更清封面"路径——否则图片行变更后会以 NONE 进入视频抽帧状态机。
 */
object ScanMerge {

    fun merge(old: MediaItemEntity?, fresh: MediaItemEntity): MediaItemEntity {
        if (old == null) return fresh
        val isImage = fresh.kind == MediaKind.IMAGE
        val changed = old.sizeBytes != fresh.sizeBytes || old.mtimeMs != fresh.mtimeMs
        return fresh.copy(
            id = old.id,
            addedAt = old.addedAt,
            thumbnailPath = when {
                isImage -> fresh.thumbnailPath
                changed -> null
                else -> old.thumbnailPath
            },
            thumbState = when {
                isImage -> fresh.thumbState
                changed -> com.novax.app.data.db.ThumbState.NONE.name
                else -> old.thumbState
            },
            durationMs = if (isImage || changed) fresh.durationMs else old.durationMs ?: fresh.durationMs,
            width = if (changed) fresh.width else old.width ?: fresh.width,
            height = if (changed) fresh.height else old.height ?: fresh.height,
            scrapedTitle = old.scrapedTitle,
            // MediaDb v6：刮削四列同样保旧——fresh 来自扫描器恒为 null，直接覆盖会把
            // 已刮到的海报/简介集体抹掉（审核 P1-6）
            scrapeOverview = old.scrapeOverview,
            scrapePosterPath = old.scrapePosterPath,
            scrapeYear = old.scrapeYear,
            scrapeRating = old.scrapeRating,
            // 2026-09-15 修复：kind MOVIE 保底不降级（升级判定在 Thumbnailer.maybeUpgradeToMovie，
            // 重扫不再把已升级影片打回 VIDEO）；IMAGE 行恒跟随 fresh（图片不走抽帧状态机）
            kind = when {
                isImage -> fresh.kind
                old.kind == MediaKind.MOVIE -> MediaKind.MOVIE
                else -> fresh.kind
            },
        )
    }
}
