package com.novax.app.ui.files

import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType

/**
 * 跨 Tab 一次性「定位到文件夹」事件槽（2026-09-27 历史记录页「打开所在文件夹」）：
 * 与 [NvUiEvents] 同款单向槽模式——历史页 put 后切文件 Tab，FilesScreen 组合进入时
 * consume 并直接开到目标源+目录（不经中间层级）。不引入事件总线/共享 VM。
 */
object NvFilesDeepLink {

    /** 目标：源 ID + 源内面包屑（相对源根的目录段，与 FilesViewModel.listDir 同口径） */
    data class Target(val sourceId: Long, val breadcrumb: List<String>)

    @Volatile
    private var pending: Target? = null

    fun put(target: Target) {
        pending = target
    }

    /** 读取并复位（消费式）；非请求期恒 null，重复调用安全 */
    fun consume(): Target? = pending.also { pending = null }
}

/**
 * 「打开所在文件夹」目标推导（2026-10-13 自 WatchHistoryViewModel 提取共享：首页继续观看/收藏同构复用）：
 * - sourceId 非空：父目录段即面包屑，直接定位；
 * - 本地直连（sourceId 为空且绝对路径）：回配 shareOrPath 为其前缀的 LOCAL 源（firstOrNull 首个匹配），
 *   转成源内相对面包屑；
 * - 远程直链 URL / 无法回配：无文件夹概念，返回 null（调用方隐藏菜单项）。
 * mock mediaId 检查在调用方（NvPlaybackLauncher 在 ui.home，本文件不得反向依赖）。
 */
fun folderTargetOf(sourceId: Long?, path: String, sources: List<SourceEntity>): NvFilesDeepLink.Target? {
    if (sourceId != null) return NvFilesDeepLink.Target(sourceId, breadcrumbOf(path))
    if (path.startsWith("/")) {
        val src = sources.firstOrNull { s ->
            if (s.type != SourceType.LOCAL) return@firstOrNull false
            val root = s.shareOrPath.trimEnd('/', '\\')
            root.isNotEmpty() && (path == root || path.startsWith("$root/"))
        } ?: return null
        val root = src.shareOrPath.trimEnd('/', '\\')
        return NvFilesDeepLink.Target(src.id, breadcrumbOf(path.removePrefix(root)))
    }
    return null
}

/** 源内路径 → 面包屑段（去掉文件名；与 FilesViewModel.listDir 口径一致） */
fun breadcrumbOf(path: String): List<String> =
    path.trim('/').split('/').filter { it.isNotEmpty() }.dropLast(1)
