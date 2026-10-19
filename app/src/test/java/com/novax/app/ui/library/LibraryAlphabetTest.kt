package com.novax.app.ui.library

import com.novax.app.ui.components.SECTION_LABEL_DIGIT
import com.novax.app.ui.components.SECTION_LABEL_HASH
import com.novax.app.ui.components.NV_ALPHABET_SIDEBAR_LETTERS
import org.junit.Assert.assertEquals
import org.junit.Test

/** 字母侧条归组（A-Z + 数字组"数" + 符号组"#"；拼音首字母复用 MediaNames） */
class LibraryAlphabetTest {

    @Test
    fun letterOf_asciiAndChinese() {
        assertEquals("A", librarySectionLetterOf("Avatar"))
        assertEquals("H", librarySectionLetterOf("海洋奇缘"))     // hai yang qi yuan
        assertEquals("B", librarySectionLetterOf("北京上空"))      // bei jing ...
    }

    @Test
    fun letterOf_digitGoesToDigitSection_notHash() {
        // 用户要求：0-9 开头统一归单独的"数字"组，不再与符号共用 #
        assertEquals(SECTION_LABEL_DIGIT, librarySectionLetterOf("12 只猴子"))
        assertEquals(SECTION_LABEL_DIGIT, librarySectionLetterOf("1080P_demo"))
        assertEquals(SECTION_LABEL_DIGIT, librarySectionLetterOf("00M_69901148_1"))
    }

    @Test
    fun letterOf_symbolAndBlankFallToHash() {
        assertEquals(SECTION_LABEL_HASH, librarySectionLetterOf(""))
        assertEquals(SECTION_LABEL_HASH, librarySectionLetterOf("..."))
        assertEquals("Z", librarySectionLetterOf("[周星驰]喜剧"))  // 符号被跳过，按首个有效字符"周"→Z
    }

    @Test
    fun letterOf_lowercaseAsciiUppercased() {
        assertEquals("N", librarySectionLetterOf("nova clip"))
    }

    @Test
    fun map_pointsAtFirstOccurrenceOnly() {
        // 已按"标题 A→Z"排序后的展示名列表
        val titles = listOf("Avocado", "海洋奇缘", "北京上空", "Harold", "12 只猴子", "Kafka")
        val map = buildInitialIndexMap(titles)
        assertEquals(0, map["A"])
        assertEquals(1, map["H"])   // 海洋奇缘 首个 H；Harold 不覆盖
        assertEquals(2, map["B"])
        assertEquals(5, map["K"])
        assertEquals(4, map[SECTION_LABEL_DIGIT])
        // 无条目的分组不出现在映射里（侧条置灰）
        assertEquals(false, map.containsKey(SECTION_LABEL_HASH))
    }

    @Test
    fun sidebarLetters_azPlusDigitPlusHash() {
        // 2026-10-16 全局序裁定：A-Z 最上（字母排最前），数字组其后，# 最后（旧序数字最上已废）
        assertEquals(('A'..'Z').map { it.toString() } + listOf(SECTION_LABEL_DIGIT, SECTION_LABEL_HASH), NV_ALPHABET_SIDEBAR_LETTERS)
        assertEquals(28, NV_ALPHABET_SIDEBAR_LETTERS.size)
    }

    /** 2026-10-16 全局序：中文"阿甘"经拼音归字母组（A）且在数字组前；符号组殿后 */
    @Test
    fun map_sectionOrderFollowsGlobalRank() {
        // 已按新全局序（A→Z→0-9→#）排序后的展示名列表
        val titles = listOf("阿甘", "Avatar", "01", "1942", "#$%")
        val map = buildInitialIndexMap(titles)
        assertEquals(0, map["A"])                  // 阿甘(anggan) 归 A，与 Avatar 同组取首下标
        assertEquals(2, map[SECTION_LABEL_DIGIT])  // 数字组从 01 起，在字母之后
        assertEquals(4, map[SECTION_LABEL_HASH])   // 符号组殿后
    }
}
