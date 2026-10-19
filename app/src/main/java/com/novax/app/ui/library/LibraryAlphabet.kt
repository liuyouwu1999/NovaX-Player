package com.novax.app.ui.library

import com.novax.app.data.scanner.MediaNames
import com.novax.app.ui.components.SECTION_LABEL_DIGIT
import com.novax.app.ui.components.SECTION_LABEL_HASH

/**
 * 媒体库字母侧条归组逻辑：A-Z 各一组；数字开头（0-9）统一归"数"组（用户要求：
 * 不是 0-9 九个键，是单个"数字"组）；符号/未知归 #。拼音首字母复用 [MediaNames]。
 * 2026-10-16 全局三段序（用户裁定）：侧条条上顺序 A-Z 最前 → 数 → #（见
 * NV_ALPHABET_SIDEBAR_LETTERS），与列表排序（MediaNames 类别秩三段序）同语；归组函数本身序无关。
 */

/** 展示名 → 侧条分组：拼音首字母 A-Z 原样；0-9 归"数"；其余（符号/空名）归 "#" */
fun librarySectionLetterOf(title: String): String {
    val first = MediaNames.keys(title).second.firstOrNull() ?: return SECTION_LABEL_HASH
    return when (first) {
        in 'A'..'Z' -> first.toString()
        in '0'..'9' -> SECTION_LABEL_DIGIT
        else -> SECTION_LABEL_HASH
    }
}

/**
 * 当前排序后的展示名列表 → 分组 → 该分组第一个条目的下标（侧条拖动 scrollToItem 直跳）。
 * 列表必须已是最终展示顺序（标题拼音自然排序）；重复分组只保留首个下标。
 */
fun buildInitialIndexMap(titles: List<String>): Map<String, Int> {
    val map = HashMap<String, Int>()
    titles.forEachIndexed { index, title ->
        val section = librarySectionLetterOf(title)
        if (section !in map) map[section] = index
    }
    return map
}
