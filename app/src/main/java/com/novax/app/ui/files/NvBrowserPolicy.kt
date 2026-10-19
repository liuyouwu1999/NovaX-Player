package com.novax.app.ui.files


/**
 * 文件浏览器行为策略（纯函数，无 Android 依赖，单测锁定）。
 */

/**
 * 浏览器返回语义（系统返回键专用，入口 [FilesViewModel.onBrowserBack]）：
 * - 子目录（面包屑非空）：回上一级（dropLast(1)）；
 * - 源根（面包屑空）：null = 关闭浏览器回源列表。
 *
 * 2026-10-19 用户裁定：左上角箭头不再走本语义——箭头 = 上一级页面（closeBrowser 回
 * 源列表，深层目录一键回源汇总页）；返回键 = 文件上一级（本函数）。
 *
 * 回归锁定：旧实现把 `size - 1` 当绝对层级传 onBreadcrumbNavigate（take(depth+1)），
 * 子目录返回 = 重列当前目录，表现为返回键完全失效。
 */
internal fun browserBackTarget(breadcrumb: List<String>): List<String>? =
    if (breadcrumb.isEmpty()) null else breadcrumb.dropLast(1)

// ---- 每源「上次浏览目录」恢复决策（2026-09-29 用户需求：DataStore 持久化，进源自动恢复） ----

/**
 * DataStore 键名（novax_ui_prefs 文件，与返回键直接退出等 UI 偏好同文件）：
 * 每个源一条记录，按 sourceId 区分（删源后残留键无消费者，无害）。
 */
internal fun lastDirPrefsKey(sourceId: Long): String = "files_last_dir_$sourceId"

/**
 * 持久化目录路径 → 恢复用面包屑（与 [FilesViewModel] 的 joinToPath / FileBrowser 条目点击
 * 同一口径）：null/空白（无记录，或显式根记录 ""）→ 空面包屑 = 回落根目录；
 * "/a/b" → ["a","b"]；SMB 虚拟共享 "/@share/Media" → ["@share","Media"]（SmbClient 支持直列）。
 */
internal fun restoreBreadcrumbFrom(storedPath: String?): List<String> {
    if (storedPath.isNullOrBlank()) return emptyList()
    return storedPath.trim('/').split('/').filter { it.isNotEmpty() }
}

