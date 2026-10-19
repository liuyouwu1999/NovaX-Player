package com.novax.app.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinPathIndexTest {

    @Test
    fun remember_and_idOf_roundTrip_withTrailingSlashForDirs() {
        val idx = JellyfinPathIndex()
        idx.remember("/电影/", "lib-1")
        idx.remember("/电影/阿凡达.mkv", "it-2")
        assertEquals("lib-1", idx.idOf("/电影"))
        assertEquals("lib-1", idx.idOf("/电影/"))   // 目录带不带尾分隔符等价
        assertEquals("it-2", idx.idOf("/电影/阿凡达.mkv"))
    }

    @Test
    fun idOf_unknown_returnsNull() {
        assertNull(JellyfinPathIndex().idOf("/不存在"))
    }

    // Review Focus 1：进程死亡后索引空，walkSegments 给出逐级重解析顺序
    @Test
    fun walkSegments_ordersShallowToDeep_forIncrementalResolve() {
        assertEquals(
            listOf("/电影", "/电影/动作", "/电影/动作/片单A"),
            JellyfinPathIndex().walkSegments("/电影/动作/片单A/"),
        )
        assertEquals(emptyList<String>(), JellyfinPathIndex().walkSegments("/"))
        assertEquals(emptyList<String>(), JellyfinPathIndex().walkSegments(""))
    }

    // Review Focus 5：同名后写覆盖，不崩溃不串流
    @Test
    fun remember_samePathTwice_lastWriteWins() {
        val idx = JellyfinPathIndex()
        idx.remember("/a.mkv", "old")
        idx.remember("/a.mkv", "new")
        assertEquals("new", idx.idOf("/a.mkv"))
    }

    @Test
    fun remember_normalizesDotsAndBackslashes() {
        val idx = JellyfinPathIndex()
        idx.remember("\\电影\\动作\\", "deep")
        assertEquals("deep", idx.idOf("/电影/动作"))
    }

    // ---- 跨源无歧义解析（隐私计划 Task 17 resolver 策略，纯函数） ----

    @Test
    fun unambiguousIdOf_singleSourceHit_returnsId() {
        val a = JellyfinPathIndex().also { it.remember("/电影/ep.mkv", "it-1") }
        assertEquals("it-1", JellyfinPathIndex.unambiguousIdOf(listOf(a), "/电影/ep.mkv"))
    }

    @Test
    fun unambiguousIdOf_coldIndex_returnsNull() {
        // 索引未热：null → 隐私 index 侧 fail-closed 整源临时锁（规格 §3.1 口径）
        assertNull(JellyfinPathIndex.unambiguousIdOf(listOf(JellyfinPathIndex()), "/电影/ep.mkv"))
        assertNull(JellyfinPathIndex.unambiguousIdOf(emptyList(), "/电影/ep.mkv"))
    }

    @Test
    fun unambiguousIdOf_crossSourceAmbiguity_returnsNull() {
        // 两个 jf 源对同一路径给出不同 id：判定不了归属 → null（fail-closed，宁整源临时锁不误放）
        val a = JellyfinPathIndex().also { it.remember("/电影", "lib-a") }
        val b = JellyfinPathIndex().also { it.remember("/电影", "lib-b") }
        assertNull(JellyfinPathIndex.unambiguousIdOf(listOf(a, b), "/电影"))
    }

    @Test
    fun unambiguousIdOf_sameServerAddedTwice_agreedIdsReturn() {
        // 同一服务器重复添加为两个源：id 一致不算歧义
        val a = JellyfinPathIndex().also { it.remember("/电影", "lib-x") }
        val b = JellyfinPathIndex().also { it.remember("/电影", "lib-x") }
        assertEquals("lib-x", JellyfinPathIndex.unambiguousIdOf(listOf(a, b), "/电影"))
    }
}
