package com.novax.app.data.thumb

import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.ThumbState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面文件丢失自检（显示时存在性点检，2026-11-01 设计复审定稿）：
 * DONE 且 thumbnailPath 指向的文件已消失 → resetThumb + requestThumb 走正常认领重抽。
 * 边界（复审 P1-1/P1-2/P1-4）：判定以 DB 行重读为准（不信任 UI 传入路径）；
 * DONE+null（隐私占位）不在本通道（解锁恢复另有专路）；会话去重键 = id:path
 * （重抽后新路径再丢失可重新武装）。
 */
class ThumbLostVerifierTest {

    private fun row(
        id: Long,
        state: String = ThumbState.DONE.name,
        thumbPath: String? = "/cache/thumbs/$id.jpg",
    ) = MediaItemEntity(
        id = id,
        sourceId = 7L,
        path = "/movies/e$id.mp4",
        name = "e$id",
        nameLower = "e$id",
        sizeBytes = 1L,
        mtimeMs = 1L,
        kind = MediaKind.EPISODE,
        thumbnailPath = thumbPath,
        thumbState = state,
        addedAt = 1L,
    )

    /** 可编程 fake：行由测试直接摆放；记录 reset/request 调用 */
    private class Harness(
        var current: MediaItemEntity?,
        var exists: Boolean = false,
        var blocked: Boolean = false,
    ) {
        val resets = AtomicInteger(0)
        val requests = AtomicInteger(0)

        fun verifier() = ThumbLostVerifier(
            findById = { current },
            resetThumb = { resets.incrementAndGet() },
            request = { requests.incrementAndGet() },
            fileExists = { exists },
            privacyBlocked = { _, _ -> blocked },
        )
    }

    @Test
    fun doneRow_lostFile_resetsAndRequests() = runBlocking {
        val h = Harness(current = row(1L), exists = false)
        h.verifier().verify(1L)
        assertEquals(1, h.resets.get())
        assertEquals(1, h.requests.get())
    }

    @Test
    fun doneRow_filePresent_noop() = runBlocking {
        val h = Harness(current = row(1L), exists = true)
        h.verifier().verify(1L)
        assertEquals(0, h.resets.get())
        assertEquals(0, h.requests.get())
    }

    @Test
    fun sameIdAndPath_secondVerify_deduped() = runBlocking {
        // 模拟 DB 回写延迟：第一次 verify 后行仍是 DONE+同路径（竞争窗口），
        // 第二次触发必须被会话去重拦下，不得二次 reset/request
        val h = Harness(current = row(1L), exists = false)
        val v = h.verifier()
        v.verify(1L)
        v.verify(1L)
        assertEquals(1, h.resets.get())
        assertEquals(1, h.requests.get())
    }

    @Test
    fun repairedThenLostAgain_newPath_rearms() = runBlocking {
        val h = Harness(current = row(1L, thumbPath = "/cache/thumbs/a.jpg"), exists = false)
        val v = h.verifier()
        v.verify(1L)
        assertEquals(1, h.resets.get())
        // 重抽完成：同 id 换了新路径（DEDUP KEY = id:path，必须重新武装）
        h.current = row(1L, thumbPath = "/cache/thumbs/b.jpg")
        v.verify(1L)
        assertEquals(2, h.resets.get())
        assertEquals(2, h.requests.get())
    }

    @Test
    fun privacyBlocked_noResetNoRequest() = runBlocking {
        val h = Harness(current = row(1L), exists = false, blocked = true)
        h.verifier().verify(1L)
        assertEquals(0, h.resets.get())
        assertEquals(0, h.requests.get())
    }

    @Test
    fun missingRow_noop() = runBlocking {
        val h = Harness(current = null, exists = false)
        h.verifier().verify(99L)
        assertEquals(0, h.resets.get())
        assertEquals(0, h.requests.get())
    }

    @Test
    fun nonDoneStates_noop() = runBlocking {
        for (state in listOf(ThumbState.NONE, ThumbState.RUNNING, ThumbState.FAILED)) {
            val h = Harness(current = row(1L, state = state.name), exists = false)
            h.verifier().verify(1L)
            assertEquals("state=$state", 0, h.resets.get())
            assertEquals("state=$state", 0, h.requests.get())
        }
    }

    @Test
    fun doneWithNullPath_noop_privacyPlaceholderOutOfScope() = runBlocking {
        // 复审 P1-2：DONE+null 是隐私锁占位终态，恢复走解锁时机专路（resetBlockedThumbsInScope），
        // 本通道若接手会造成锁定卡「shimmer 闪一下再回占位」+ 每会话两次无谓 DB 写
        val h = Harness(current = row(1L, thumbPath = null), exists = false)
        h.verifier().verify(1L)
        assertEquals(0, h.resets.get())
        assertEquals(0, h.requests.get())
    }

    @Test
    fun concurrentVerify_sameIdAndPath_exactlyOneRepair() = runBlocking {
        // 复审 P2-3①：两处 UI 同一瞬间触发（首页+媒体库）——SessionOnceSet 原子 add
        // 保证只放行一次，恰一次 reset+request
        val h = Harness(current = row(1L), exists = false)
        val v = h.verifier()
        (1..2).map { async { v.verify(1L) } }.awaitAll()
        assertEquals(1, h.resets.get())
        assertEquals(1, h.requests.get())
    }

    @Test
    fun lockedThenUnlocked_sameSession_stillHeals() = runBlocking {
        // 复审 P2-3②：privacy 判定必须在 dedup 标记之前——锁定行首次显示零动作且不占去重，
        // 同会话解锁后再显示仍可自愈（若有人把 once 提到 privacy 前，本用例会红）
        val h = Harness(current = row(1L), exists = false, blocked = true)
        val v = h.verifier()
        v.verify(1L)
        assertEquals(0, h.resets.get())
        // 解锁
        h.blocked = false
        v.verify(1L)
        assertEquals(1, h.resets.get())
        assertEquals(1, h.requests.get())
    }
}
