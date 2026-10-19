package com.novax.app.ui.library

import com.novax.app.data.scanner.MediaNames

/**
 * 拼音排序键缓存（性能修复）：媒体库 2846 行场景下，旧实现 [MediaNames.comparator] 每次比较
 * 都要（a）查 MediaNames LRU 取拼音键、（b）在 compareNatural 里现建两个 Regex 切自然序 token——
 * O(n log n) ≈ 3 万+ 次比较 × items/folders 两遍，全在发射循环里。
 *
 * 本类改为"每条目只算一次排序键"：拼音转换 + 自然序 token 切分在键计算时各做一次并缓存，
 * 比较阶段只对预切的 token 列表做零分配比较（语义与 [MediaNames.comparator] 逐字节一致，单测对拍）。
 *
 * 2026-10-16 全局三段序（用户裁定 A→Z→0-9→#）：键内含首字符类别秩（[MediaNames.categoryRank]），
 * compareTo 先比秩再进 token 自然序——键直比即呈现三段序；自然序 token 编码原样未动。
 *
 * 失效策略（key=id，值含校验戳）：
 * - 条目：展示名（scrapedTitle ?: name）或 mtimeMs 任一变更 → 重算（重抽帧/刮削改名后排序自动跟随）；
 * - 文件夹：path 即标题，无 mtime，(sourceId,path) 变化天然是新 key。
 *
 * 纯 JVM 类（无 Android 依赖），可注入单测；线程安全（方法级锁，比较在锁外）。
 */
class LibrarySortKeyCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /**
     * 单条目排序键：首字符类别秩（'0'字母/'1'数字/'2'符号三段序，语义同字符位）+ 自然序 token
     * （拼音全拼键一次切分）+ 展示名兜底比较。
     * 比较语义 = MediaNames.compareNatural(keys(a).first, keys(b).first) 非零取之，
     * 否则 a.compareTo(b)（展示名）——与 [MediaNames.comparator] 完全一致（含 2026-10-16 类别秩）。
     */
    class SortKey private constructor(
        private val rank: Int,
        private val tokens: List<String>,
        private val fallback: String,
    ) : Comparable<SortKey> {

        override fun compareTo(other: SortKey): Int {
            // 全局三段序：先比类别秩（A-Z→0-9→#），秩同再进自然序 token 比较
            rank.compareTo(other.rank).takeIf { it != 0 }?.let { return it }
            val left = tokens
            val right = other.tokens
            for (i in 0 until minOf(left.size, right.size)) {
                val l = left[i]
                val r = right[i]
                val c = if (l.first() in '0'..'9' && r.first() in '0'..'9') {
                    // 自然数字段：去前导零后先比长度再比字典序（"02" < "10" < "9" 位数十进制语义）
                    val ln = l.trimStart('0').ifEmpty { "0" }
                    val rn = r.trimStart('0').ifEmpty { "0" }
                    ln.length.compareTo(rn.length).takeIf { it != 0 } ?: ln.compareTo(rn)
                } else {
                    l.compareTo(r)
                }
                if (c != 0) return c
            }
            return left.size.compareTo(right.size).takeIf { it != 0 } ?: fallback.compareTo(other.fallback)
        }

        companion object {
            /** 与 MediaNames.compareNatural 同一切分规则；只在键计算时执行一次 */
            private val TOKEN = Regex("[0-9]+|[^0-9]+")

            fun of(title: String): SortKey {
                val full = MediaNames.keys(title).first
                return SortKey(MediaNames.categoryRank(full), TOKEN.findAll(full).map { it.value }.toList(), title)
            }
        }
    }

    /** 缓存行：键值 + 失效校验戳（title/version 任一不匹配即重算） */
    private class Entry(val key: SortKey, val title: String, val version: Long)

    /** items 用 Long id、folders 用 "sourceId:path" 字符串做 key——类型不同永不冲突，共池受 LRU 约束 */
    private val cache = object : LinkedHashMap<Any, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Entry>?): Boolean = size > maxEntries
    }

    /** 媒体条目排序键：id 定位缓存，展示名或 mtimeMs 变更即失效重算（新条目自动补算） */
    fun itemKey(id: Long, title: String, mtimeMs: Long): SortKey = keyOf(id, title, mtimeMs)

    /** 文件夹排序键：(sourceId, path) 定位缓存，path 即标题恒不失效 */
    fun folderKey(sourceId: Long, path: String): SortKey = keyOf("$sourceId:$path", path, 0L)

    @Synchronized
    private fun keyOf(cacheKey: Any, title: String, version: Long): SortKey {
        val hit = cache[cacheKey]
        if (hit != null && hit.title == title && hit.version == version) return hit.key
        return SortKey.of(title).also { cache[cacheKey] = Entry(it, title, version) }
    }

    companion object {
        /** 千级库全量驻留；超出按 LRU 淘汰（访问序，热条目不抖动） */
        internal const val DEFAULT_MAX_ENTRIES = 4096
    }
}
