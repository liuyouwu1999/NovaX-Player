package com.novax.app.data.thumb

import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抽帧优先级比较器与优先级队列（本任务新增：MOVIE/SERIES/EPISODE > IMAGE > VIDEO，同级 FIFO，后台降级） */
class ThumbWorkQueueTest {

    private fun item(id: Long, kind: MediaKind) = MediaItemEntity(
        id = id, sourceId = 1, path = "/p$id", name = "n$id", nameLower = "n$id",
        sizeBytes = 1, mtimeMs = 1, kind = kind, addedAt = 1,
    )

    // ── 优先级档位 ──

    @Test
    fun rankOf_kindOrdering() {
        assertEquals(ThumbPriority.RANK_MOVIE, ThumbPriority.rankOf("MOVIE"))
        assertEquals(ThumbPriority.RANK_MOVIE, ThumbPriority.rankOf("SERIES"))
        assertEquals(ThumbPriority.RANK_MOVIE, ThumbPriority.rankOf("EPISODE"))
        assertEquals(ThumbPriority.RANK_IMAGE, ThumbPriority.rankOf("IMAGE"))
        assertEquals(ThumbPriority.RANK_VIDEO, ThumbPriority.rankOf("VIDEO"))
        // 未知类型按 IMAGE 中位档，绝不落到摄像头切片的最低档
        assertEquals(ThumbPriority.RANK_IMAGE, ThumbPriority.rankOf("WHATEVER"))
    }

    // ── 比较器 ──

    @Test
    fun comparator_priorityBeatsSeq() {
        val movie = ThumbRequest(0, 99, item(1, MediaKind.MOVIE))
        val video = ThumbRequest(2, 1, item(2, MediaKind.VIDEO))
        assertTrue(movie < video)   // 后入队的高优先级仍排前
    }

    @Test
    fun comparator_samePriorityFifoBySeq() {
        val first = ThumbRequest(2, 1, item(1, MediaKind.VIDEO))
        val second = ThumbRequest(2, 2, item(2, MediaKind.VIDEO))
        assertTrue(first < second)
    }

    @Test
    fun comparator_backgroundPenaltyKeepsForegroundFirst() {
        // 前台 VIDEO（本任务用户场景：正在浏览的内容）压过全部后台降级请求
        val fgVideo = ThumbRequest(ThumbPriority.of("VIDEO", background = false), 1, item(1, MediaKind.VIDEO))
        val bgMovie = ThumbRequest(ThumbPriority.of("MOVIE", background = true), 0, item(2, MediaKind.MOVIE))
        assertEquals(ThumbPriority.RANK_VIDEO, ThumbPriority.of("VIDEO", false))
        assertEquals(ThumbPriority.RANK_MOVIE + ThumbPriority.BACKGROUND_PENALTY, ThumbPriority.of("MOVIE", true))
        assertTrue(fgVideo < bgMovie)
    }

    @Test
    fun queue_folderBoostJumpsAheadOfEverything() = runBlocking {
        // 2026-09-27 用户需求「进文件夹优先做整个文件夹的封面」：置顶档压过一切常规档
        //（含前台 MOVIE），同置顶档内仍 FIFO
        val queue = ThumbWorkQueue()
        queue.offer(item(1, MediaKind.VIDEO), ThumbPriority.RANK_VIDEO)
        queue.offer(item(2, MediaKind.MOVIE), ThumbPriority.RANK_MOVIE)
        queue.offer(item(3, MediaKind.EPISODE), ThumbPriority.RANK_FOLDER_BOOST)
        queue.offer(item(4, MediaKind.VIDEO), ThumbPriority.RANK_FOLDER_BOOST)

        assertEquals(3L, queue.take().item.id)   // 置顶档先入先出
        assertEquals(4L, queue.take().item.id)
        assertEquals(2L, queue.take().item.id)   // 常规前台
        assertEquals(1L, queue.take().item.id)
    }

    // ── 队列出队顺序 ──

    @Test
    fun queue_drainsHighestPriorityFirst() = runBlocking {
        val queue = ThumbWorkQueue()
        queue.offer(item(1, MediaKind.VIDEO), ThumbPriority.of("VIDEO", false))
        queue.offer(item(2, MediaKind.MOVIE), ThumbPriority.of("MOVIE", true))   // 后台降级排最后
        queue.offer(item(3, MediaKind.MOVIE), ThumbPriority.of("MOVIE", false))
        queue.offer(item(4, MediaKind.EPISODE), ThumbPriority.of("EPISODE", false))

        assertEquals(4, queue.pendingCount())
        assertEquals(3L, queue.take().item.id)   // 前台 MOVIE
        assertEquals(4L, queue.take().item.id)   // 前台 EPISODE
        assertEquals(1L, queue.take().item.id)   // 前台 VIDEO（前台永远优先于后台队列）
        assertEquals(2L, queue.take().item.id)   // 后台 MOVIE（低优先级渐进）
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun queue_samePriorityFifo() = runBlocking {
        val queue = ThumbWorkQueue()
        repeat(5) { queue.offer(item(it.toLong(), MediaKind.VIDEO), ThumbPriority.RANK_VIDEO) }
        repeat(5) { assertEquals(it.toLong(), queue.take().item.id) }
    }

    @Test
    fun queue_takeSuspendsUntilOffer() = runBlocking {
        val queue = ThumbWorkQueue()
        val taken = async { queue.take() }
        delay(80)   // take 已挂起（真实短等待；与 ThumbClaimGateTest 同风格）
        queue.offer(item(42, MediaKind.MOVIE), 0)
        assertEquals(42L, taken.await().item.id)
    }

    @Test
    fun queue_concurrentOfferPreservesPriorityOrder() = runBlocking {
        val queue = ThumbWorkQueue()
        val jobs = (1..50).map { i ->
            launch { queue.offer(item(i.toLong(), MediaKind.VIDEO), ThumbPriority.RANK_VIDEO) }
        }
        jobs.forEach { it.join() }
        assertEquals(50, queue.pendingCount())
        // 同优先级按 seq 严格 FIFO（seq 分配顺序即 FIFO 语义，与堆插入完成顺序无关）
        var lastSeq = Long.MIN_VALUE
        repeat(50) {
            val req = queue.take()
            assertTrue("seq 应单调递增", req.seq > lastSeq)
            lastSeq = req.seq
        }
    }
}
