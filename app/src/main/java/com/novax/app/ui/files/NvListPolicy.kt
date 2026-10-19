package com.novax.app.ui.files

import com.novax.app.data.scanner.MediaNames
import com.novax.app.ui.viewers.NvFileRouter

/**
 * C5 §5.1–5.4 文件列表四件套纯函数（无 Android 依赖，单测锁定 NvListPolicyTest）：
 * 类型过滤 / 排序比较器 / 目录内搜索匹配 / filter∘sort∘search 组合管线 / 命中高亮区间。
 *
 * 组合顺序（applyListPipeline）：**search（并入子目录结果）→ type filter（文件夹恒通过）→ sort**。
 * 三者作用维度互斥（搜索改"查哪些行"、过滤改"留哪些行"、排序改"行序"），先 filter 后 sort
 * 与先 sort 后 filter 集合等价，但 sort 必须最后一步，保证最终行序确定性。
 */

// ---------- 5.1 类型过滤 ----------

/** 六类文件类型（C5 §5.1：视频/音频/图像/字幕/文档/其他） */
enum class NvFileType(val label: String) {
    VIDEO("视频"),
    AUDIO("音频"),
    IMAGE("图像"),
    SUBTITLE("字幕"),
    DOC("文档"),
    OTHER("其他"),
}

/** 全部类型（默认勾选 = 不过滤） */
val allFileTypes: Set<NvFileType> = NvFileType.entries.toSet()

/** 字幕扩展名（NvFileRouter 把 srt/ass/ssa/vtt 归入 TEXT，过滤需在 kindOf 之前切出） */
val subtitleExtensions: Set<String> = setOf("srt", "ass", "ssa", "vtt", "sub", "smi", "sami")

/** 扩展名 → 六类（复用 NvFileRouter 路由表：PDF/文本→文档，未知→其他；字幕优先判定） */
fun fileTypeOf(fileName: String): NvFileType {
    if (NvFileRouter.extensionOf(fileName) in subtitleExtensions) return NvFileType.SUBTITLE
    return when (NvFileRouter.kindOf(fileName)) {
        NvFileRouter.Kind.VIDEO -> NvFileType.VIDEO
        NvFileRouter.Kind.AUDIO -> NvFileType.AUDIO
        NvFileRouter.Kind.IMAGE -> NvFileType.IMAGE
        NvFileRouter.Kind.PDF, NvFileRouter.Kind.TEXT -> NvFileType.DOC
        NvFileRouter.Kind.UNKNOWN -> NvFileType.OTHER
    }
}

/**
 * 类型过滤判定：**文件夹行始终显示**（不受过滤影响，C5 §5.1）；
 * 六类全勾等价于无过滤。
 */
fun passesTypeFilter(entry: NvFileEntry, enabled: Set<NvFileType>): Boolean {
    if (entry.isDir) return true
    return fileTypeOf(entry.name) in enabled
}

/**
 * 激活过滤摘要（Chip 行回显）：全勾 → null（不显示 Chip）；
 * 部分勾选 → "视频+图像"（显示保留的类型）；全不勾 → "无"。
 */
fun filterSummary(enabled: Set<NvFileType>): String? {
    if (enabled.containsAll(allFileTypes)) return null
    return NvFileType.entries.filter { it in enabled }
        .joinToString("+") { it.label }
        .ifBlank { "无" }
}

// ---------- 5.2 排序 ----------

/** 排序方式（C5 §5.2） */
enum class NvSortMode(val label: String) {
    NAME("名称（A–Z / 拼音）"),
    MODIFIED("修改时间（新 → 旧）"),
    SIZE("大小（大 → 小）"),
    TYPE("类型"),
}

/**
 * 列表比较器：
 * - [dirsFirst] 开（默认）：文件夹恒置顶，文件组内按所选方式排；
 * - 名称：MediaNames 拼音序（中文按拼音，与媒体库命名一致）；
 * - 修改时间：mtime 新 → 旧；大小：大 → 小；类型：扩展名字母序分组、组内按名称；
 * - 各方式平局兜底按名称拼音序，再按路径，保证确定性。
 */
fun listComparator(mode: NvSortMode, dirsFirst: Boolean): Comparator<NvFileEntry> {
    val byName = MediaNames.comparator
    val byEntryName = Comparator<NvFileEntry> { a, b -> byName.compare(a.name, b.name) }
    val primary: Comparator<NvFileEntry> = when (mode) {
        NvSortMode.NAME -> byEntryName
        NvSortMode.MODIFIED ->
            compareByDescending<NvFileEntry> { it.mtimeMs }.then(byEntryName)
        NvSortMode.SIZE ->
            compareByDescending<NvFileEntry> { it.size }.then(byEntryName)
        NvSortMode.TYPE ->
            compareBy<NvFileEntry> { NvFileRouter.extensionOf(it.name) }.then(byEntryName)
    }
    return if (dirsFirst) {
        compareByDescending<NvFileEntry> { it.isDir }.then(primary)
            .thenBy { it.path }
    } else {
        primary.thenBy { it.path }
    }
}

// ---------- 5.3 目录内搜索 ----------

/** 包含匹配（大小写不敏感）；query 为空白 = 不过滤 */
fun matchesQuery(name: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, ignoreCase = true)
}

/** 命中区间（全部出现位置，大小写不敏感）；无命中/空 query → 空表（供 NvHighlightText） */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val t = text.lowercase()
    val needle = q.lowercase()
    if (needle.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var idx = t.indexOf(needle)
    while (idx >= 0) {
        ranges += idx until (idx + needle.length)
        idx = t.indexOf(needle, idx + needle.length)
    }
    return ranges
}

/**
 * 条目相对当前目录的显示路径（子目录命中时次行展示，如 "子目录/文件.mp4"）：
 * 不在 dir 之下（含 dir 自身）→ null。
 */
fun relativePathUnder(entryPath: String, dirPath: String): String? {
    val dir = dirPath.trimEnd('/')
    if (dir.isEmpty()) return entryPath.trimStart('/')
    val p = entryPath.trimEnd('/')
    if (!p.startsWith("$dir/")) return null
    return p.substring(dir.length + 1)
}

// ---------- 5.1–5.3 组合管线 ----------

/** 过滤/排序/搜索组合参数（过滤与搜索随浏览态存 ViewModel；排序全局持久化） */
data class NvListQuery(
    val enabledTypes: Set<NvFileType> = allFileTypes,
    val sortMode: NvSortMode = NvSortMode.NAME,
    val dirsFirst: Boolean = true,
    val searchQuery: String = "",
    val searchActive: Boolean = false,   // 顶栏搜索态（多选快照恢复用；管线本身只看 searchQuery）
) {
    val hasTypeFilter: Boolean get() = !enabledTypes.containsAll(allFileTypes)
    val hasSearch: Boolean get() = searchQuery.isNotBlank()
}

/**
 * 组合管线：search（当前目录 + 子目录命中合并）→ type filter → sort。
 * - 搜索态下目录行也按名称匹配（仅展示命中文件夹，可点击进入）；
 * - 非搜索态文件夹恒显示（passesTypeFilter 保证）；
 * - 子目录结果（subResults）已按查询匹配，管线内统一再过类型过滤与排序；
 * - 合并后按 path 去重（2026-09-15 回归修复：子目录 BFS 从当前目录起扫，当前目录命中
 *   会同时出现在 current 与 subResults，重复 path 会让 LazyColumn key 崩溃）；
 *   current 在前，distinctBy 保留当前目录版本（无相对路径副行）。
 */
fun applyListPipeline(
    current: List<NvFileEntry>,
    query: NvListQuery,
    subResults: List<NvFileEntry> = emptyList(),
): List<NvFileEntry> {
    val searched = (current.filter { matchesQuery(it.name, query.searchQuery) } + subResults)
        .distinctBy { it.path }
    val filtered = searched.filter { passesTypeFilter(it, query.enabledTypes) }
    return filtered.sortedWith(listComparator(query.sortMode, query.dirsFirst))
}
