package com.novax.app.ui.library

import com.novax.app.data.scanner.MediaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LibrarySortKeyCache 单测（性能修复）：同键稳定 / 新条目补算 / 变更失效，
 * 以及排序语义与旧 MediaNames.comparator 完全对拍（拼音自然序 + 展示名兜底）。
 */
class LibrarySortKeyCacheTest {

    private data class Row(val id: Long, val title: String, val mtimeMs: Long = 0L, val sourceId: Long = 1L, val path: String = "/$title")

    /** 旧路径：MediaNames.comparator 直接排序（对照基准） */
    private fun legacyOrder(titles: List<String>): List<String> = titles.sortedWith(MediaNames.comparator)

    /** 新路径：缓存键排序（fallback = 展示名，与 comparator 的 a.compareTo(b) 同） */
    private fun cachedOrder(cache: LibrarySortKeyCache, titles: List<String>): List<String> =
        titles.sortedWith(compareBy<String> { cache.itemKey(it.hashCode().toLong(), it, 0L) })

    @Test
    fun sameInputsReturnStableSameKeyInstance() {
        val cache = LibrarySortKeyCache()
        val a = cache.itemKey(7L, "海洋奇缘", 100L)
        val b = cache.itemKey(7L, "海洋奇缘", 100L)
        assertSame("同 id+同 title+mtime 应命中缓存返回同一实例", a, b)
        assertEquals(0, a.compareTo(b))
        // 同键多次比较结果稳定（Comparable 契约）
        assertEquals(a.compareTo(b), b.compareTo(a) * -1 + 0)
    }

    @Test
    fun newEntriesComputedOnDemand() {
        val cache = LibrarySortKeyCache()
        val first = cache.itemKey(1L, "阿凡达", 0L)
        val second = cache.itemKey(2L, "北京", 0L)
        assertNotSame("新条目独立补算，不共享键实例", first, second)
        // "阿凡达"(a) < "北京"(b)，与 legacyOrder 基准（阿凡达, 北京）一致
        assertTrue(first.compareTo(second) < 0)
        assertTrue(second.compareTo(first) > 0)
    }

    @Test
    fun titleChangeInvalidatesKey() {
        val cache = LibrarySortKeyCache()
        val old = cache.itemKey(5L, "scrapedZ", 0L)   // 模拟刮削后标题
        val new = cache.itemKey(5L, "apple", 0L)      // 标题变更 → 必须重算
        assertNotSame("标题变更后不得复用旧键", old, new)
        val fresh = LibrarySortKeyCache().itemKey(5L, "apple", 0L)
        assertEquals("新键与全新缓存计算结果一致", 0, new.compareTo(fresh))
    }

    @Test
    fun mtimeChangeInvalidatesKey() {
        val cache = LibrarySortKeyCache()
        val old = cache.itemKey(5L, "海洋奇缘", 100L)
        val new = cache.itemKey(5L, "海洋奇缘", 200L)   // 文件被替换（mtime 变）→ 重算（内容可能变名）
        assertNotSame("mtime 变更后不得复用旧键实例", old, new)
        assertEquals("同名不同 mtime 排序语义不变", 0, old.compareTo(new))
    }

    @Test
    fun orderingMatchesMediaNamesComparator() {
        val cache = LibrarySortKeyCache()
        val titles = listOf(
            "第10集", "第2集", "第1集", "阿凡达", "北京", "A film", "12只猴子",
            "海洋奇缘", "Zootopia", "bee movie", "变形金刚4", "疯狂动物城",
            "2046", "a film", "x99999999999999999999", "x100000000000000000000", "电影测试",
        )
        assertEquals("缓存键排序必须与旧 comparator 逐位一致", legacyOrder(titles), cachedOrder(cache, titles))
        // 走两遍（第二遍全命中缓存）结果不变
        assertEquals(legacyOrder(titles), cachedOrder(cache, titles))
    }

    @Test
    fun folderKeysSortLikeComparatorWithPathTieBreak() {
        val cache = LibrarySortKeyCache()
        val folders = listOf(
            LibraryFolder(1L, "/媒体/10 楼"),
            LibraryFolder(1L, "/媒体/2 楼"),
            LibraryFolder(2L, "/媒体/2 楼"),      // 同 path 不同源 → sourceId 兜底
            LibraryFolder(1L, "/相册"),
        )
        val sorted = folders.sortedWith(compareBy<LibraryFolder> { cache.folderKey(it.sourceId, it.path) }.thenBy { it.sourceId })
        val expected = folders.sortedWith(
            compareBy<LibraryFolder, String>(MediaNames.comparator) { it.path }.thenBy { it.sourceId },
        )
        assertEquals(expected, sorted)
        // 同 (sourceId,path) 稳定同实例
        assertSame(cache.folderKey(1L, "/相册"), cache.folderKey(1L, "/相册"))
    }

    @Test
    fun lruEvictionKeepsCorrectness() {
        val cache = LibrarySortKeyCache(maxEntries = 2)
        cache.itemKey(1L, "阿凡达", 0L)
        cache.itemKey(2L, "北京", 0L)
        cache.itemKey(3L, "海洋奇缘", 0L)   // 挤出 1（未再访问）
        val recomputed = cache.itemKey(1L, "阿凡达", 0L)
        assertEquals("被淘汰条目重算后语义不变", 0, recomputed.compareTo(LibrarySortKeyCache().itemKey(1L, "阿凡达", 0L)))
    }

    @Test
    fun itemRowsSortLikeLegacyPipeline() {
        // 端到端对拍：items 全管线（缓存键 → sourceId → path）与旧 compareBy(comparator) 管线一致
        val cache = LibrarySortKeyCache()
        val rows = listOf(
            Row(1, "第10集", path = "/a/10"),
            Row(2, "第2集", path = "/a/2"),
            Row(3, "同名", path = "/b/x"),
            Row(4, "同名", path = "/a/y"),
            Row(5, "bee movie"),
            Row(6, "海洋奇缘"),
        )
        val newOrder = rows.sortedWith(
            compareBy<Row> { cache.itemKey(it.id, it.title, it.mtimeMs) }.thenBy { it.sourceId }.thenBy { it.path },
        )
        val legacy = rows.sortedWith(
            compareBy<Row, String>(MediaNames.comparator) { it.title }.thenBy { it.sourceId }.thenBy { it.path },
        )
        assertEquals(legacy, newOrder)
    }

    /** 2026-10-16 全局三段序（用户裁定 A→Z→0-9→#）：键直比呈现三段序，数字组内自然序不回归 */
    @Test
    fun rankOrder_lettersThenDigitsThenSymbols() {
        val cache = LibrarySortKeyCache()
        val titles = listOf("阿甘", "Avatar", "01", "02", "10", "1942", "#$%")
        val sorted = cachedOrder(cache, titles)
        // 字母组（含中文拼音）在前，数字组居中且组内 01<02<10<1942，符号组殿后
        assertEquals(listOf("阿甘", "Avatar", "01", "02", "10", "1942", "#$%"), sorted)
        // 与更新后的 MediaNames.comparator 逐位对拍
        assertEquals(legacyOrder(titles), sorted)
    }
}
