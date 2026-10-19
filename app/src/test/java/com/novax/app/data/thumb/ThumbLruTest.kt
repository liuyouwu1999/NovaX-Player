package com.novax.app.data.thumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRU 淘汰选样与缩略图缓存 key（纯函数，C12 §三.7 / §三.4） */
class ThumbLruTest {

    private data class Entry(val name: String, val size: Long)   // 列表序 = lastModified 升序（最旧在前）

    @Test
    fun underBudget_evictsNothing() {
        val entries = listOf(Entry("a", 10), Entry("b", 20))
        assertTrue(ThumbLru.selectEvictions(entries, { it.size }, maxBytes = 100).isEmpty())
    }

    @Test
    fun overBudget_evictsOldestFirst_untilUnderCap() {
        val entries = listOf(
            Entry("oldest", 10),
            Entry("old", 20),
            Entry("new", 30),
            Entry("newest", 40),
        )   // 总 100
        val evicted = ThumbLru.selectEvictions(entries, { it.size }, maxBytes = 65)
        // 100 → 删 10(剩 90) → 删 20(剩 70) → 删 30(剩 40 ≤ 65) 停
        assertEquals(listOf("oldest", "old", "new"), evicted.map { it.name })
    }

    @Test
    fun tinyCap_evictsEverything() {
        val entries = listOf(Entry("a", 10), Entry("b", 20))
        val evicted = ThumbLru.selectEvictions(entries, { it.size }, maxBytes = 0)
        assertEquals(listOf("a", "b"), evicted.map { it.name })
    }

    @Test
    fun referencedEntries_participateInEviction() {
        // 被引用文件同样参与淘汰（全跳过则引用集满时永不收敛）；调用方先清 DB 引用再删文件
        val entries = listOf(Entry("referenced-old", 50), Entry("unreferenced", 10))
        val evicted = ThumbLru.selectEvictions(entries, { it.size }, maxBytes = 30)
        // 60 → 删最旧 50(剩 10 ≤ 30) 停：被引用的最旧文件首先出局
        assertEquals(listOf("referenced-old"), evicted.map { it.name })
    }

    @Test
    fun exactlyAtCap_evictsNothing() {
        val entries = listOf(Entry("a", 50), Entry("b", 50))
        assertTrue(ThumbLru.selectEvictions(entries, { it.size }, maxBytes = 100).isEmpty())
    }

    // ---------- 缓存 key ----------

    @Test
    fun hashKey_deterministic_andDiscriminates() {
        assertEquals(ThumbCache.hashKey(1L, "/a/b.mkv"), ThumbCache.hashKey(1L, "/a/b.mkv"))
        assertNotEquals(ThumbCache.hashKey(1L, "/a/b.mkv"), ThumbCache.hashKey(2L, "/a/b.mkv"))
        assertNotEquals(ThumbCache.hashKey(1L, "/a/b.mkv"), ThumbCache.hashKey(1L, "/a/b.mp4"))
        // 32 位 hex（md5 摘要）
        assertTrue(Regex("[0-9a-f]{32}").matches(ThumbCache.hashKey(1L, "/x")))
    }
}
