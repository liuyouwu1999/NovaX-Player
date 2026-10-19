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
}
