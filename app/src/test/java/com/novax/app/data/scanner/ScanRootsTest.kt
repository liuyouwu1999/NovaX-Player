package com.novax.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sources.scanRoots（JSON 字符串数组 TEXT）编解码 + 多根去重：
 * 往返一致（含中文/空格/引号/反斜杠）、损坏输入容错回退空表、嵌套根取最外层。
 */
class ScanRootsTest {

    // ---------- encode / parse 往返 ----------

    @Test
    fun roundTrip_simplePaths() {
        val roots = listOf("/video", "/music/华语", "/photo backup")
        assertEquals(roots, ScanRoots.parse(ScanRoots.encode(roots)))
    }

    @Test
    fun roundTrip_escapingQuotesAndBackslashes() {
        val roots = listOf("/a\"b\\c", "/d\\e/f")
        assertEquals(roots, ScanRoots.parse(ScanRoots.encode(roots)))
    }

    @Test
    fun roundTrip_virtualShareRoots() {
        val roots = listOf("/@share/media/电影", "/@share/backup")
        assertEquals(roots, ScanRoots.parse(ScanRoots.encode(roots)))
    }

    @Test
    fun encode_trimsAndDropsBlankEntries() {
        assertEquals("[\"/a\"]", ScanRoots.encode(listOf(" /a ", "")))
    }

    @Test
    fun encode_emptyOrNullEntries_returnsNull() {
        assertNull(ScanRoots.encode(emptyList()))
        assertNull(ScanRoots.encode(listOf("", "  ")))
    }

    // ---------- parse 容错（损坏/null 一律回退空表 = 扫根目录） ----------

    @Test
    fun parse_nullBlankOrGarbage_returnsEmpty() {
        assertEquals(emptyList<String>(), ScanRoots.parse(null))
        assertEquals(emptyList<String>(), ScanRoots.parse(""))
        assertEquals(emptyList<String>(), ScanRoots.parse("   "))
        assertEquals(emptyList<String>(), ScanRoots.parse("not json"))
        assertEquals(emptyList<String>(), ScanRoots.parse("[\"unterminated]"))
        assertEquals(emptyList<String>(), ScanRoots.parse("[\"a\",]"))
        assertEquals(emptyList<String>(), ScanRoots.parse("[\"a\" \"b\"]"))
        assertEquals(emptyList<String>(), ScanRoots.parse("[42]"))
        assertEquals(emptyList<String>(), ScanRoots.parse("[\"bad \\q escape\"]"))
    }

    @Test
    fun parse_emptyArray_returnsEmpty() {
        assertEquals(emptyList<String>(), ScanRoots.parse("[]"))
    }

    @Test
    fun parse_singleElement() {
        assertEquals(listOf("/only"), ScanRoots.parse("[\"/only\"]"))
    }

    // ---------- dedup：多根合并去重（嵌套根取最外层，保序） ----------

    @Test
    fun dedup_dropsRootsNestedUnderAnotherRoot() {
        val out = ScanRoots.dedup(listOf("/video", "/video/sub", "/music"))
        assertEquals(listOf("/video", "/music"), out)
    }

    @Test
    fun dedup_dropsExactDuplicates_keepsFirstOrder() {
        val out = ScanRoots.dedup(listOf("/b", "/a", "/b", "/a"))
        assertEquals(listOf("/b", "/a"), out)
    }

    @Test
    fun dedup_rootSlashSwallowsEverything() {
        val out = ScanRoots.dedup(listOf("/video", "/", "/music"))
        assertEquals(listOf("/"), out)
    }

    @Test
    fun dedup_normalizesTrailingSlashAndDuplicates() {
        val out = ScanRoots.dedup(listOf("/video/", "video", "\\video"))
        assertEquals(listOf("/video"), out)
    }

    @Test
    fun dedup_siblingDirectoriesBothKept() {
        val out = ScanRoots.dedup(listOf("/a/b", "/a/bc"))
        assertEquals(listOf("/a/b", "/a/bc"), out)
    }

    @Test
    fun dedup_blankEntriesSkipped() {
        val out = ScanRoots.dedup(listOf("", "  ", "/keep"))
        assertEquals(listOf("/keep"), out)
    }

    @Test
    fun dedup_doesNotTreatPrefixNameAsNested() {
        // "/a/bc" 不是 "/a/b" 的子目录（前缀必须整段匹配）
        val out = ScanRoots.dedup(listOf("/a/b", "/a/bc"))
        assertTrue(out.size == 2)
    }
    // ---------- 2026-10-26「仅作文件源」禁用标记 ----------

    @Test
    fun disabled_marker_isExactLiteral() {
        // 仅认 "[]" 字面（trim 容差）；唯一合法写者 = 向导 filesOnly 保存
        assertTrue(ScanRoots.isDisabled(ScanRoots.DISABLED))
        assertTrue(ScanRoots.isDisabled("  []  "))
    }

    @Test
    fun disabled_nullOrBlankOrWhitespaceInside_isNotDisabled() {
        // null = 扫整个源（老数据兼容语义），绝不能误判成禁用（fail-open 扫，不误禁）
        assertTrue(!ScanRoots.isDisabled(null))
        assertTrue(!ScanRoots.isDisabled(""))
        assertTrue(!ScanRoots.isDisabled("   "))
    }

    @Test
    fun disabled_malformedOrNonEmpty_isNotDisabled() {
        // 损坏 JSON / 非空数组 → 回退既有语义（空表=扫根），不是禁用
        assertTrue(!ScanRoots.isDisabled("[\"a"))
        assertTrue(!ScanRoots.isDisabled("[\"/video\"]"))
        assertTrue(!ScanRoots.isDisabled("[] extra"))
        assertTrue(!ScanRoots.isDisabled("[ ]x"))
    }

    @Test
    fun disabled_marker_parsesAsEmptyList() {
        // 禁用值经 parse 仍是空表（= 若守卫漏放，扫描器回退扫根——但 isDisabled 守卫在上游拦截）
        assertEquals(emptyList<String>(), ScanRoots.parse(ScanRoots.DISABLED))
    }
}
