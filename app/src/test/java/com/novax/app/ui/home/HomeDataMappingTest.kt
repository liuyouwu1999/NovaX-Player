package com.novax.app.ui.home

import com.novax.app.data.MockMediaRepository
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11 首页真数据纯函数单测：续播过滤窗口 / chip 映射 / 收藏真前假后合并去重。
 * （与 WatchHistoryDao.observeContinueWatching 的 SQL 条件保持一致的行为规格）
 */
class HomeDataMappingTest {

    private fun history(
        positionMs: Long,
        durationMs: Long,
        season: Int? = null,
        episode: Int? = null,
        path: String = "p1",
        sourceId: Long? = 1L,
        watchedAt: Long = 100L,
    ) = WatchHistoryEntity(
        sourceId = sourceId,
        sourceType = if (sourceId != null) "SMB" else null,
        path = path,
        title = "测试片",
        kind = "MOVIE",
        positionMs = positionMs,
        durationMs = durationMs,
        watchedAt = watchedAt,
        seasonNumber = season,
        episodeNumber = episode,
    )

    // ---------- 续播过滤窗口 ----------

    @Test
    fun `window excludes positions at or under 5 seconds`() {
        assertFalse(isContinueWindow(0L, 600_000L))
        assertFalse(isContinueWindow(5_000L, 600_000L))     // 边界：>5000 才算
        assertTrue(isContinueWindow(5_001L, 600_000L))
    }

    @Test
    fun `window excludes unknown duration and finished items`() {
        assertFalse(isContinueWindow(60_000L, 0L))          // 时长未知不进继续观看
        assertFalse(isContinueWindow(950L * 600, 1000L * 600))  // 恰 95% = 看完，排除
        assertTrue(isContinueWindow(949L * 600, 1000L * 600))   // <95% 保留
    }

    // ---------- 首页继续观看准入（asset/公网测试流过滤 + 15s 门槛 + 12 条上限 + 源失效） ----------

    @Test
    fun `user path filter excludes built-in samples and public test streams`() {
        // 内置样片与三大公网测试流：永远不是用户真实内容
        assertFalse(isUserContentPath("asset:///sample.mp4"))
        assertFalse(isUserContentPath("https://media.w3.org/2010/05/sintel/trailer.mp4"))
        assertFalse(isUserContentPath("https://vjs.zencdn.net/v/oceans.mp4"))
        assertFalse(
            isUserContentPath(
                "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            ),
        )
        // 用户真实内容：NAS 源内路径 / 本地绝对路径 / 用户自己的 https 源
        assertTrue(isUserContentPath("/media/movies/interstellar.mkv"))
        assertTrue(isUserContentPath("/storage/emulated/0/Movies/a.mp4"))
        assertTrue(isUserContentPath("https://my-nas.example.com:5006/video/movie.mkv"))
    }

    @Test
    fun `continue entry requires at least 15 seconds watched`() {
        assertFalse(isContinueEntry(0L, 600_000L))
        assertFalse(isContinueEntry(14_999L, 600_000L))     // 边界：<15s 视为误触
        assertTrue(isContinueEntry(15_000L, 600_000L))      // 恰 15s 保留
        assertTrue(isContinueEntry(15_001L, 600_000L))
        // 与 5s 进度窗共用「未看完 + 时长已知」约束
        assertFalse(isContinueEntry(60_000L, 0L))
        assertFalse(isContinueEntry(950L * 600, 1000L * 600))
        assertTrue(isContinueEntry(949L * 600, 1000L * 600))
    }

    @Test
    fun `filter drops built-in sample and test stream entries`() {
        val rows = listOf(
            history(60_000L, 600_000L, path = "asset:///sample.mp4", sourceId = null),
            history(60_000L, 600_000L, path = "https://media.w3.org/2010/05/sintel/trailer.mp4", sourceId = null),
            history(60_000L, 600_000L, path = "/media/movies/dune.mkv"),
        )
        val filtered = filterContinueWatching(rows, validSourceIds = setOf(1L))
        assertEquals(listOf("/media/movies/dune.mkv"), filtered.map { it.path })
    }

    @Test
    fun `filter drops entries whose source no longer exists`() {
        val rows = listOf(
            history(60_000L, 600_000L, path = "/alive.mkv", sourceId = 1L),
            history(60_000L, 600_000L, path = "/deleted-source.mkv", sourceId = 9L), // 源已删
            history(60_000L, 600_000L, path = "https://my-nas.example.com/v.mkv", sourceId = null), // 直连无源
        )
        val filtered = filterContinueWatching(rows, validSourceIds = setOf(1L))
        assertEquals(listOf("/alive.mkv", "https://my-nas.example.com/v.mkv"), filtered.map { it.path })
    }

    @Test
    fun `filter keeps at most 12 most recent entries`() {
        val rows = (1..15).map { i ->
            history(60_000L, 600_000L, path = "/p$i.mkv", watchedAt = i.toLong())
        }
        val filtered = filterContinueWatching(rows, validSourceIds = setOf(1L))
        assertEquals(12, filtered.size)
        // 最近优先：watchedAt 15..4
        assertEquals((15 downTo 4).map { "/p$it.mkv" }, filtered.map { it.path })
    }

    @Test
    fun `filter combines path window and source validity`() {
        val rows = listOf(
            history(10_000L, 600_000L, path = "/too-short.mkv", watchedAt = 50L),        // <15s
            history(570_000L, 600_000L, path = "/finished.mkv", watchedAt = 40L),       // 恰 95% = 看完
            history(60_000L, 600_000L, path = "asset:///sample.mp4", sourceId = null, watchedAt = 30L),
            history(60_000L, 600_000L, path = "/gone.mkv", sourceId = 42L, watchedAt = 20L), // 源失效
            history(60_000L, 600_000L, path = "/keep.mkv", watchedAt = 10L),             // 保留
        )
        val filtered = filterContinueWatching(rows, validSourceIds = setOf(1L))
        assertEquals(listOf("/keep.mkv"), filtered.map { it.path })
    }

    // ---------- chip 映射 ----------

    @Test
    fun `chip prefers season-episode label`() {
        assertEquals("S1E05", continueChip(history(60_000L, 600_000L, season = 1, episode = 5)))
        assertEquals("S12E103", continueChip(history(60_000L, 600_000L, season = 12, episode = 103)))
    }

    @Test
    fun `chip shows continue-label when duration unknown and minutes otherwise`() {
        // durationMs=0：显示「继续观看」，不显示剩余分钟
        assertEquals("继续观看", continueChip(history(120_000L, 0L)))
        // 剩余 10:59 → ceil = 11 分钟；不足 1 分钟也至少 1
        assertEquals("剩 11 分钟", continueChip(history(60_000L, 719_000L)))
        assertEquals("剩 1 分钟", continueChip(history(659_000L, 660_000L)))
    }

    @Test
    fun `progress clamps and handles unknown duration`() {
        assertEquals(0f, continueProgress(history(60_000L, 0L)))
        assertEquals(0.5f, continueProgress(history(50_000L, 100_000L)))
    }

    // ---------- 收藏合并（真前假后 + (sourceId,path) 去重） ----------

    private fun real(sourceId: Long?, path: String, addedAt: Long) =
        FavoriteEntity(sourceId = sourceId, path = path, title = path, kind = "MOVIE", addedAt = addedAt)

    private fun mock(id: String) = MockMediaRepository.PosterEntry(id, id, "sub", "url")

    @Test
    fun `merge puts real favorites first and keeps mock order after`() {
        val merged = mergeFavorites(
            real = listOf(real(1L, "/a.mkv", 30L), real(null, "m9", 20L)),
            mock = listOf(mock("m1"), mock("m5"), mock("m9")),
        )
        // 真收藏在前（输入序 = addedAt DESC）；未被覆盖的 mock 按原序追加在后
        assertEquals(listOf("1:/a.mkv", "n:m9", "n:m1", "n:m5"), merged.map { it.key })
    }

    @Test
    fun `mock favorite covered by real is deduplicated`() {
        // 用户对假影片 m1 点收藏 → 升级为真（sourceId=null, path=m1），只在真区出现一次
        val merged = mergeFavorites(
            real = listOf(real(null, "m1", 10L)),
            mock = listOf(mock("m1"), mock("m5")),
        )
        assertEquals(listOf("n:m1", "n:m5"), merged.map { it.key })
        assertEquals(1, merged.count { it.title == "m1" })
    }

    @Test
    fun `real favorite from NAS source does not shadow mock by same path text`() {
        // sourceId != null 的收藏不能按 path 去重掉 mock（键含 sourceId）
        val merged = mergeFavorites(
            real = listOf(real(7L, "m1", 10L)),
            mock = listOf(mock("m1")),
        )
        assertEquals(listOf("7:m1", "n:m1"), merged.map { it.key })
    }

    @Test
    fun `favorite key distinguishes null sourceId from real source ids`() {
        assertEquals("n:/a.mkv", favoriteKey(null, "/a.mkv"))
        assertEquals("5:/a.mkv", favoriteKey(5L, "/a.mkv"))
        assertTrue(favoriteKey(null, "/a.mkv") != favoriteKey(5L, "/a.mkv"))
    }

    // ---------- 2026-09-15 首页改版纯函数（评审要求用例） ----------

    @Test
    fun resolutionBadge_boundaries() {
        // 4K 档：≥3800 宽 或 ≥2000 高
        assertEquals("4K", resolutionBadge(3800, 1600))
        assertEquals("4K", resolutionBadge(1920, 2000))
        assertEquals("4K", resolutionBadge(3840, 2160))
        // 2K 档：3799 宽不算 4K；2560/2559 都是 2K
        assertEquals("2K", resolutionBadge(3799, 1600))
        assertEquals("2K", resolutionBadge(2560, 1440))
        assertEquals("2K", resolutionBadge(2559, 1440))
        assertEquals("2K", resolutionBadge(2560, 1300))
        // 1080P 档：≥1600 宽
        assertEquals("1080P", resolutionBadge(1920, 1080))
        assertEquals("1080P", resolutionBadge(1600, 900))
        // 低分辨率/未知/非法 → null（不显示徽标，不造假）
        assertEquals(null, resolutionBadge(1280, 720))
        assertEquals(null, resolutionBadge(null, 1080))
        assertEquals(null, resolutionBadge(1920, null))
        assertEquals(null, resolutionBadge(0, 0))
        assertEquals(null, resolutionBadge(-1, 1080))
    }

    @Test
    fun libraryOverviewLine_segmentsAndEmpty() {
        assertEquals("3 部影片 · 12 集剧集", libraryOverviewLine(mapOf("MOVIE" to 3, "EPISODE" to 12)))
        assertEquals("5 个视频", libraryOverviewLine(mapOf("VIDEO" to 5)))
        assertEquals(null, libraryOverviewLine(emptyMap()))
        assertEquals(null, libraryOverviewLine(mapOf("MOVIE" to 0, "EPISODE" to 0)))
    }

    @Test
    fun continueRow_carriesThumbPath() {
        val e = WatchHistoryEntity(
            sourceId = 1L, sourceType = "SMB", path = "/a.mp4", title = "a",
            kind = "MOVIE", positionMs = 60_000L, durationMs = 1_200_000L, watchedAt = 9L,
        )
        val withThumb = e.toContinueRow("/cache/thumbs/x.jpg")
        assertEquals("/cache/thumbs/x.jpg", withThumb.thumbPath)
        val noThumb = e.toContinueRow(null)
        assertEquals(null, noThumb.thumbPath)
    }

    @Test
    fun favoriteRow_thumbPathDefaultNull() {
        val fav = FavoriteEntity(sourceId = 1L, path = "/a.mp4", title = "a", kind = "MOVIE", addedAt = 1L)
        assertEquals(null, fav.toFavoriteRow().thumbPath)
        assertEquals("/t.jpg", fav.toFavoriteRow("/t.jpg").thumbPath)
    }
}
