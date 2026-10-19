package com.novax.app.data.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NvTmdbNameCleanerTest {

    @Test
    fun `发布标签全剥除`() {
        assertEquals(
            "The Wild Robot",
            NvTmdbNameCleaner.clean("The.Wild.Robot.2024.2160p.WEB-DL.x265.10bit.HDR.mkv"),
        )
    }

    @Test
    fun `集数标记剥除`() {
        assertEquals("lange", NvTmdbNameCleaner.clean("lange S01E01.mkv"))
        assertEquals("lange", NvTmdbNameCleaner.clean("lange E03.mp4"))
        assertEquals("lange", NvTmdbNameCleaner.clean("lange 第02集.mkv"))
    }

    @Test
    fun `中文括号标签剥除`() {
        assertTrue(
            NvTmdbNameCleaner.clean("沙丘2【4K修复】Dune.Part.Two.2024.1080p.mkv").contains("沙丘2"),
        )
    }

    @Test
    fun `年份提取`() {
        assertEquals(2024, NvTmdbNameCleaner.extractYear("The.Wild.Robot.2024.2160p.WEB-DL.x265.mkv"))
        assertNull(NvTmdbNameCleaner.extractYear("Apollo 13.mkv"))
    }

    @Test
    fun `结尾短数字剥除但年份保留在清洗名外的情况不影响检索`() {
        // Apollo 13：结尾 "13" 被当集数剥掉是已知取舍（检索名 "Apollo" 仍可命中）
        assertEquals("Apollo", NvTmdbNameCleaner.clean("Apollo 13 (1995).mkv"))
    }

    @Test
    fun `全剥光时兜底回原文`() {
        assertEquals("2", NvTmdbNameCleaner.clean("2.mp4"))
    }

    @Test
    fun `剧集文件判定`() {
        assertTrue(NvTmdbNameCleaner.looksLikeEpisodeFile("lange S01E05.mkv"))
        assertTrue(NvTmdbNameCleaner.looksLikeEpisodeFile("第12集.mkv"))
        assertFalse(NvTmdbNameCleaner.looksLikeEpisodeFile("The.Wild.Robot.2024.2160p.mkv"))
    }

    @Test
    fun `TMDB URL 构造`() {
        val client = NvTmdbClient()
        val url = client.searchMovieUrl("KEY123", "The Wild Robot", 2024)
        assertTrue(url.startsWith("https://api.themoviedb.org/3/search/movie?api_key=KEY123&query="))
        assertTrue(url.contains("language=zh-CN"))
        assertTrue(url.contains("&year=2024"))
        val tv = client.searchTvUrl("KEY123", "漫长季节", null)
        assertTrue(tv.contains("/3/search/tv?"))
        assertTrue(!tv.contains("&year="))
        assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", client.posterUrl("/abc.jpg"))
        assertNull(client.posterUrl("abc.jpg"))
    }
}
