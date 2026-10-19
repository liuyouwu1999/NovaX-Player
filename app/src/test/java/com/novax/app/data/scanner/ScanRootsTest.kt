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
}
