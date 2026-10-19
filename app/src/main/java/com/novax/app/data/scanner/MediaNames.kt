package com.novax.app.data.scanner

import net.sourceforge.pinyin4j.PinyinHelper
import java.util.Locale

/**
 * 离线拼音键（pinyin4j）：媒体库自然排序键 + 搜索列（media_items.pinyin/initials）的建库写入源
 * （写入点 MediaScanner.itemOf、存量回填 PinyinBackfill，2026-09-29 打通）。
 *
 * 键格式跟随 MediaItemDao.search 的匹配约定（以查询为准）：
 * - [keys].first  = 全拼小写无分隔符：CJK 逐字转拼音（去声调、lü→lv 的 "u:"→"v"）；
 *   非 CJK 字母数字原样小写拼入（"Rob.B.Hood" → "robbhood"），标点/空白丢弃；
 *   查询按 `pinyin LIKE '%q%'` 包含匹配。
 * - [keys].second = 首字母**大写**串：CJK 取各字拼音首字母，非 CJK 字母数字原样大写拼入
 *   （DAO `initials LIKE :qUpper || '%'` 前缀匹配，仅查询 ≥2 ASCII 字母时参与，故必须大写）。
 *
 * 纯函数（pinyin4j 调用封装在内，可 JVM 单测）；LRU 缓存避免排序比较期重复转换。
 *
 * 2026-10-16 用户裁定全局三段序：**字母(A-Z，中文经拼音归入)最前 → 数字(0-9)其后 → 符号/空键 # 殿后**
 * （旧序数字码位小排在字母前，用户不认可）。落地：[compareNatural]（含 [comparator]）先比
 * [categoryRank] 首字符类别秩再进自然序；键缓存侧 LibrarySortKeyCache.SortKey 同秩前置于 token 比较。
 */
object MediaNames {
    private val cache = object : LinkedHashMap<String, Pair<String, String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 4096
    }

    @Synchronized fun keys(name: String): Pair<String, String> = cache.getOrPut(name) {
        val full = StringBuilder()
        val initials = StringBuilder()
        for (ch in name) {
            val syllable = PinyinHelper.toHanyuPinyinStringArray(ch)?.firstOrNull()?.trimEnd('1', '2', '3', '4', '5')
            if (syllable != null) {
                full.append(syllable.replace("u:", "v"))
                initials.append(syllable.first())
            } else if (ch.isLetterOrDigit()) {
                full.append(ch)
                initials.append(ch)
            }
        }
        full.toString().lowercase(Locale.ROOT) to initials.toString().uppercase(Locale.ROOT)
    }

    val comparator = Comparator<String> { a, b ->
        compareNatural(keys(a).first, keys(b).first).takeIf { it != 0 } ?: a.compareTo(b)
    }

    /** 首字符类别秩（2026-10-16 全局三段序）：字母=0（中文经拼音即字母）、数字=1、其他/空键=2。
     *  入参拼音键（[keys].first）或原始名均可——按首字符判类，导出供 LibrarySortKeyCache 键缓存用。 */
    fun categoryRank(key: String): Int {
        val c = key.firstOrNull() ?: return 2
        return when {
            c.isLetter() -> 0
            c in '0'..'9' -> 1
            else -> 2
        }
    }

    fun compareNatural(a: String, b: String): Int {
        // 全局三段序（2026-10-16）：先比首字符类别秩（字母→数字→符号），秩同再进自然序；
        // 旧版跨类比较落回字符码位 → 数字码位小抢在字母前，已废
        categoryRank(a).compareTo(categoryRank(b)).takeIf { it != 0 }?.let { return it }
        val left = Regex("[0-9]+|[^0-9]+").findAll(a).map { it.value }.toList()
        val right = Regex("[0-9]+|[^0-9]+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val l = left[i]; val r = right[i]
            val c = if (l.first() in '0'..'9' && r.first() in '0'..'9') {
                val ln = l.trimStart('0').ifEmpty { "0" }; val rn = r.trimStart('0').ifEmpty { "0" }
                ln.length.compareTo(rn.length).takeIf { it != 0 } ?: ln.compareTo(rn)
            } else l.compareTo(r)
            if (c != 0) return c
        }
        return left.size.compareTo(right.size)
    }
}
