package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C6 §3.1 · SubtitleMatcher 评分与自动选择单测
 * 覆盖：完全同名自动选 / 同名+语言后缀自动选 / 多语言变体弹手选 /
 *       无关字幕不自动选 / 剧集同集号加分 / 路径分隔兼容 / MIME 映射
 */
class SubtitleMatcherTest {

    // ---- 完全同名：score 100，自动选 ----

    @Test
    fun `exact same name auto picks`() {
        val video = "/storage/emulated/0/Movies/movie.mkv"
        val files = listOf(
            "/storage/emulated/0/Movies/movie.srt",
            "/storage/emulated/0/Movies/cover.jpg",
            "/storage/emulated/0/Movies/other.txt",
        )
        val candidates = SubtitleMatcher.findSubtitles(video, files)
        assertEquals(1, candidates.size)
        val (picked, auto) = SubtitleMatcher.pickAuto(candidates)
        assertTrue(auto)
        assertEquals(SubtitleMatcher.SCORE_EXACT, picked!!.score)
        assertTrue(picked.isExact)
        assertNull(picked.languageTag)
        assertTrue(picked.path.endsWith("movie.srt"))
        assertEquals("movie.srt", picked.displayName)
    }

    @Test
    fun `extension match is case insensitive`() {
        val candidates = SubtitleMatcher.findSubtitles(
            "/storage/emulated/0/Movies/movie.mkv",
            listOf("/storage/emulated/0/Movies/movie.SRT", "/storage/emulated/0/Movies/movie.Ass"),
        )
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.score == SubtitleMatcher.SCORE_EXACT })
    }

    // ---- 同名 + 语言后缀：score 90，自动选 ----

    @Test
    fun `same name with language suffix auto picks`() {
        val video = "C:\\Movies\\movie.mkv"
        val files = listOf("C:\\Movies\\movie.zh.srt")
        val (picked, auto) = SubtitleMatcher.pickAuto(SubtitleMatcher.findSubtitles(video, files))
        assertTrue(auto)
        assertEquals(SubtitleMatcher.SCORE_LANGUAGE, picked!!.score)
        assertFalse(picked.isExact)
        assertEquals("zh", picked.languageTag)
    }

    @Test
    fun `chs cht eng kr tags normalize to bcp47`() {
        val candidates = SubtitleMatcher.findSubtitles(
            "/m/movie.mkv",
            listOf("/m/movie.chs.ass", "/m/movie.cht.ssa", "/m/movie.eng.srt", "/m/movie.kr.vtt"),
        )
        val byTag = candidates.associateBy { it.languageTag }
        assertEquals(setOf("zh-CN", "zh-TW", "en", "ko"), byTag.keys)
        assertTrue(candidates.all { it.score == SubtitleMatcher.SCORE_LANGUAGE })
    }

    @Test
    fun `dot language tag zh-CN scores language`() {
        val candidates = SubtitleMatcher.findSubtitles(
            "/m/movie.mkv",
            listOf("/m/movie.zh-CN.srt"),
        )
        assertEquals(1, candidates.size)
        assertEquals(SubtitleMatcher.SCORE_LANGUAGE, candidates[0].score)
        assertEquals("zh-CN", candidates[0].languageTag)
    }

    // ---- 多语言变体：并列最高分 → 弹手选 ----

    @Test
    fun `multiple language variants require manual pick`() {
        val video = "/m/movie.mkv"
        val files = listOf("/m/movie.zh.srt", "/m/movie.eng.srt")
        val candidates = SubtitleMatcher.findSubtitles(video, files)
        assertEquals(2, candidates.size)
        // 降序排列，同分按文件名稳定排序
        assertEquals(listOf("movie.eng.srt", "movie.zh.srt"), candidates.map { it.displayName })
        val (picked, auto) = SubtitleMatcher.pickAuto(candidates)
        assertFalse(auto)
        assertNull(picked)
    }

    @Test
    fun `exact match wins over language variants`() {
        val video = "/m/movie.mkv"
        val files = listOf("/m/movie.zh.srt", "/m/movie.srt", "/m/movie.eng.srt")
        val (picked, auto) = SubtitleMatcher.pickAuto(SubtitleMatcher.findSubtitles(video, files))
        assertTrue(auto)
        assertEquals(SubtitleMatcher.SCORE_EXACT, picked!!.score)
    }

    // ---- 无关字幕：不自动选 ----

    @Test
    fun `unrelated subtitle is not auto picked`() {
        val video = "/m/movie.mkv"
        val files = listOf("/m/totally-different.srt")
        val candidates = SubtitleMatcher.findSubtitles(video, files)
        assertEquals(1, candidates.size)
        assertTrue(candidates[0].score <= SubtitleMatcher.SCORE_OTHER_CAP)
        assertTrue(candidates[0].score >= SubtitleMatcher.SCORE_OTHER)
        val (picked, auto) = SubtitleMatcher.pickAuto(candidates)
        assertFalse(auto)
        assertNull(picked)
    }

    @Test
    fun `no candidates yields no auto pick`() {
        val (picked, auto) = SubtitleMatcher.pickAuto(emptyList())
        assertNull(picked)
        assertFalse(auto)
    }

    // ---- 同名 + 任意后缀（forced 等）：score 80，唯一时自动选 ----

    @Test
    fun `forced suffix scores same base and auto picks`() {
        val video = "/m/movie.mkv"
        val files = listOf("/m/movie.forced.srt")
        val (picked, auto) = SubtitleMatcher.pickAuto(SubtitleMatcher.findSubtitles(video, files))
        assertTrue(auto)
        assertEquals(SubtitleMatcher.SCORE_SAME_BASE, picked!!.score)
        assertNull(picked.languageTag)
    }

    @Test
    fun `similar prefix but different base is not same base rule`() {
        // "movie2" 前缀相同但非 "movie." 直系后缀，不进规则 2/3
        val candidates = SubtitleMatcher.findSubtitles("/m/movie.mkv", listOf("/m/movie2.zh.srt"))
        assertEquals(1, candidates.size)
        assertTrue(candidates[0].score < SubtitleMatcher.SCORE_SAME_BASE)
        assertFalse(SubtitleMatcher.pickAuto(candidates).second)
    }

    // ---- 剧集同集号：+20，且上限 60 ----

    @Test
    fun `same episode number gets bonus`() {
        val video = "/tv/Show.S01E01.mkv"
        val files = listOf(
            "/tv/Group.Release.S01E01.720p.srt",
            "/tv/Group.Release.S01E02.720p.srt",
            "/tv/totally-unrelated.srt",
        )
        val candidates = SubtitleMatcher.findSubtitles(video, files)
        val byName = candidates.associateBy { it.displayName }
        // 40 + 关键词（含 "s01e01"）10 + 同集号 20 = 70 → 封顶 60
        assertEquals(SubtitleMatcher.SCORE_OTHER_CAP, byName["Group.Release.S01E01.720p.srt"]!!.score)
        assertEquals(SubtitleMatcher.SCORE_OTHER, byName["Group.Release.S01E02.720p.srt"]!!.score)
        assertEquals(SubtitleMatcher.SCORE_OTHER, byName["totally-unrelated.srt"]!!.score)
        // ≤60：永不自动选
        val (picked, auto) = SubtitleMatcher.pickAuto(candidates)
        assertNull(picked)
        assertFalse(auto)
    }

    @Test
    fun `episode formats S01E01 and EP01 and 第01集 all match`() {
        val video = "/tv/Show.S01E01.mkv"
        val files = listOf("/tv/Show.EP01.1080p.srt", "/tv/Show.第01集.srt")
        val candidates = SubtitleMatcher.findSubtitles(video, files)
        assertTrue(
            candidates.all {
                it.score == SubtitleMatcher.SCORE_OTHER + SubtitleMatcher.BONUS_EPISODE_MATCH
            },
        )
    }

    // ---- 标题关键词 +10，封顶 60 ----

    @Test
    fun `title keyword bonus capped at sixty`() {
        val video = "/m/inception.2010.mkv"
        val files = listOf("/m/review-of-inception.2010.srt")   // 含关键词，无同集号
        val candidates = SubtitleMatcher.findSubtitles(video, files)
        assertEquals(SubtitleMatcher.SCORE_OTHER + SubtitleMatcher.BONUS_TITLE_KEYWORD, candidates[0].score)

        val both = SubtitleMatcher.findSubtitles(
            "/tv/Show.S01E01.mkv",
            listOf("/tv/The.Show.S01E01.1080p.srt"),   // 含关键词 + 同集号：40+10+20=70 → 封顶 60
        )
        assertEquals(SubtitleMatcher.SCORE_OTHER_CAP, both[0].score)
    }

    // ---- 路径分隔兼容 ----

    @Test
    fun `mixed path separators still match`() {
        val video = "C:\\Movies\\Movie Night.mkv"
        val files = listOf("C:/Movies/Movie Night.srt")   // 视频用 \，字幕用 /
        val (picked, auto) = SubtitleMatcher.pickAuto(SubtitleMatcher.findSubtitles(video, files))
        assertTrue(auto)
        assertEquals(SubtitleMatcher.SCORE_EXACT, picked!!.score)
    }

    // ---- MIME 映射 ----

    @Test
    fun `mime type mapping by extension`() {
        assertEquals("application/x-subrip", SubtitleMatcher.mimeTypeForFile("/m/a.srt"))
        assertEquals("text/x-ssa", SubtitleMatcher.mimeTypeForFile("/m/a.ass"))
        assertEquals("text/x-ssa", SubtitleMatcher.mimeTypeForFile("/m/a.ssa"))
        assertEquals("text/vtt", SubtitleMatcher.mimeTypeForFile("/m/a.vtt"))
        assertNull(SubtitleMatcher.mimeTypeForFile("/m/a.sub"))
        assertNull(SubtitleMatcher.mimeTypeForFile("/m/a.idx"))
    }

    @Test
    fun `iso6392 语言别名归一`() {
        assertEquals("zh-CN", SubtitleMatcher.normalizeLanguageTag("chi"))
        assertEquals("zh-CN", SubtitleMatcher.normalizeLanguageTag("zho"))
        assertEquals("de", SubtitleMatcher.normalizeLanguageTag("deu"))
        assertEquals("ru", SubtitleMatcher.normalizeLanguageTag("rus"))
    }
}
