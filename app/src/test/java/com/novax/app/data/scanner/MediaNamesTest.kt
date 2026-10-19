package com.novax.app.data.scanner

import org.junit.Assert.*
import org.junit.Test

class MediaNamesTest {
    @Test fun chineseFullAndInitialKeys() {
        val keys = MediaNames.keys("电影测试")
        assertEquals("dianyingceshi", keys.first)
        assertEquals("DYCS", keys.second)
    }

    /** 2026-09-29 建库写入打通：搜索列键格式用例（毛骗 → maopian / MP） */
    @Test fun maoPianSearchKeys() {
        val keys = MediaNames.keys("毛骗")
        assertEquals("maopian", keys.first)
        // DAO initials LIKE :qUpper || '%' 前缀匹配 → 键必须大写（查询 "mp" → 'MP%' 命中）
        assertEquals("MP", keys.second)
    }

    /** 中英混排：CJK 转拼音，非 CJK 字母数字原样拼入，标点/空白丢弃（约定见 MediaNames kdoc） */
    @Test fun mixedChineseEnglishKeys() {
        val keys = MediaNames.keys("宝贝计划 Rob.B.Hood")
        assertEquals("baobeijihuarobbhood", keys.first)
        assertEquals("BBJHROBBHOOD", keys.second)
    }

    @Test fun naturalNumbersAndPinyinSort() {
        val names = listOf("第10集", "第2集", "阿凡达", "北京", "A film")
        assertEquals(listOf("阿凡达", "A film", "北京", "第2集", "第10集"), names.sortedWith(MediaNames.comparator))
        assertTrue(MediaNames.compareNatural("x99999999999999999999", "x100000000000000000000") < 0)
    }

    /** 2026-10-16 全局三段序（用户裁定）：字母(A-Z)最前 → 数字 0-9 → 符号/空键 # 殿后（旧序数字码位抢前已废） */
    @Test fun categoryRank_lettersThenDigitsThenSymbols() {
        assertEquals(0, MediaNames.categoryRank("avatar"))
        assertEquals(1, MediaNames.categoryRank("2046"))
        assertEquals(2, MediaNames.categoryRank(""))
        // 中文经拼音即字母："阿甘"→"anggan" 归字母组且在数字前
        assertEquals(0, MediaNames.categoryRank(MediaNames.keys("阿甘").first))
        assertTrue(MediaNames.compareNatural("阿甘", "1942") < 0)
        // 秩序用例（原始名经 compareNatural）：Avatar < 1942 < #它
        assertTrue(MediaNames.compareNatural("Avatar", "1942") < 0)
        assertTrue(MediaNames.compareNatural("1942", "#它") < 0)
        assertTrue(MediaNames.compareNatural("#它", "Avatar") > 0)
    }

    /** 数字组内自然序不回归（秩同 → 进自然序 token）：01 < 02 < 10 < 1942 */
    @Test fun digitGroupInternalNaturalOrderPreserved() {
        assertTrue(MediaNames.compareNatural("01", "02") < 0)
        assertTrue(MediaNames.compareNatural("02", "10") < 0)
        assertTrue(MediaNames.compareNatural("10", "9") > 0)
        assertTrue(MediaNames.compareNatural("1942", "01") > 0)
    }
}
