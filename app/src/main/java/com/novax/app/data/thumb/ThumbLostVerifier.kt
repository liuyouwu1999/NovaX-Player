package com.novax.app.data.thumb

import com.novax.app.data.db.MediaItemEntity

/**
 * 封面文件丢失自检（2026-11-01）：显示时点检 DONE 行的缩略图文件是否真的还在，
 * 丢了就复位走正常认领重抽。纯逻辑类（函数注入，仿 [ThumbClaimGate]），单测直验。
 *
 * 安全边界（用户红线）：检查本身零网络（本地 stat），只对正在显示的行触发，
 * 绝不扫全库；重抽经 [ThumbLostVerifier.verify] → 正常 requestThumb 通道，
 * 隐私锁 / 蜂窝计量推迟 / 并发预算等既有闸门全部原样生效。
 */
class ThumbLostVerifier(
    private val findById: suspend (Long) -> MediaItemEntity?,
    private val resetThumb: suspend (Long) -> Unit,
    private val request: suspend (Long) -> Unit,
    private val fileExists: (String) -> Boolean,
    private val privacyBlocked: (sourceId: Long, path: String) -> Boolean,
) {
    private val once = SessionOnceSet()

    /**
     * 显示时点检入口。判定一律以 DB 行重读为准（复审 P1-1：UI 传入的显示路径可能是
     * TMDB 刮削海报，不得作为依据）；DONE+null 是隐私锁占位终态，恢复走解锁时机专路
     * （resetBlockedThumbsInScope），本通道不接手（复审 P1-2）。去重键 = id:path
     * （复审 P1-4：重抽落盘新路径后若再丢失，本会话内可重新武装）。
     */
    suspend fun verify(id: Long) {
        val row = findById(id) ?: return
        if (row.thumbState != com.novax.app.data.db.ThumbState.DONE.name) return
        val path = row.thumbnailPath ?: return
        if (fileExists(path)) return
        // 隐私双保险（在去重标记之前：解锁后同会话再显示仍可自愈）
        if (privacyBlocked(row.sourceId, row.path)) return
        if (!once.once("$id:$path")) return
        resetThumb(id)
        request(id)
    }
}
