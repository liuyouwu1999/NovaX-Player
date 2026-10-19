package com.novax.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5 二期四件套纯函数单测：类型过滤 / 排序比较器 / 目录内搜索 / filter∘sort∘search 组合管线。
 */
class NvListPolicyTest {

    private fun entry(
        name: String,
        isDir: Boolean = false,
        size: Long = 0L,
        mtimeMs: Long = 0L,
        path: String = "/$name",
    ) = NvFileEntry(name = name, path = path, isDir = isDir, size = size, mtimeMs = mtimeMs)

    // ---------- fileTypeOf：六类判定（复用 NvFileRouter 路由表，字幕优先） ----------

    @Test
    fun fileType_mapsAllSixCategories() {
        assertEquals(NvFileType.VIDEO, fileTypeOf("movie.mp4"))
        assertEquals(NvFileType.VIDEO, fileTypeOf("Movie.MKV"))
        assertEquals(NvFileType.AUDIO, fileTypeOf("song.flac"))
        assertEquals(NvFileType.IMAGE, fileTypeOf("poster.JPG"))
        assertEquals(NvFileType.SUBTITLE, fileTypeOf("movie.srt"))
        assertEquals(NvFileType.SUBTITLE, fileTypeOf("movie.ass"))
        assertEquals(NvFileType.DOC, fileTypeOf("manual.pdf"))
        assertEquals(NvFileType.DOC, fileTypeOf("notes.txt"))
        assertEquals(NvFileType.DOC, fileTypeOf("data.json"))
        assertEquals(NvFileType.OTHER, fileTypeOf("app.apk"))
        assertEquals(NvFileType.OTHER, fileTypeOf("noext"))
        assertEquals(NvFileType.OTHER, fileTypeOf(".hidden"))   // 隐藏文件无扩展名
    }

    // ---------- passesTypeFilter：文件夹恒显示；勾选组合即过滤 ----------

    @Test
    fun typeFilter_dirsAlwaysPass_evenWithEmptySelection() {
        assertTrue(passesTypeFilter(entry("Movies", isDir = true), enabled = emptySet()))
        assertTrue(passesTypeFilter(entry("Movies", isDir = true), enabled = setOf(NvFileType.AUDIO)))
    }

    @Test
    fun typeFilter_filesPassOnlyWhenTypeEnabled() {
        val video = entry("a.mp4")
        assertTrue(passesTypeFilter(video, setOf(NvFileType.VIDEO)))
        assertFalse(passesTypeFilter(video, setOf(NvFileType.AUDIO)))
        assertTrue(passesTypeFilter(video, allFileTypes))            // 全勾 = 无过滤
        assertFalse(passesTypeFilter(entry("a.srt"), setOf(NvFileType.DOC))) // 字幕 ≠ 文档
    }

    // ---------- filterSummary：Chip 行摘要（全勾 → null） ----------

    @Test
    fun filterSummary_nullWhenAllEnabled() {
        assertNull(filterSummary(allFileTypes))
    }

    @Test
    fun filterSummary_listsEnabledTypes() {
        assertEquals("视频+图像", filterSummary(setOf(NvFileType.VIDEO, NvFileType.IMAGE)))
        assertEquals("无", filterSummary(emptySet()))
    }

    // ---------- listComparator：四种排序 + 文件夹置顶 ----------

    @Test
    fun sortName_chineseByPinyin_consistentWithMediaNames() {
        val cmp = listComparator(NvSortMode.NAME, dirsFirst = true)
        val sorted = listOf("博物馆", "apple", "安静").map { entry(it) }.sortedWith(cmp)
        // 拼音序：安静(anjing) < apple < 博物馆(bowuguan)
        assertEquals(listOf("安静", "apple", "博物馆"), sorted.map { it.name })
    }

    @Test
    fun sortName_dirsFirstThenPinyin() {
        val cmp = listComparator(NvSortMode.NAME, dirsFirst = true)
        val sorted = listOf(entry("b.txt"), entry("阿 folder", isDir = true), entry("a.txt"))
            .sortedWith(cmp)
        assertEquals(listOf("阿 folder", "a.txt", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun sortDirsFirstOff_dirsMixedWithFiles() {
        val cmp = listComparator(NvSortMode.NAME, dirsFirst = false)
        val sorted = listOf(entry("b.txt"), entry("a dir", isDir = true), entry("a.txt"))
            .sortedWith(cmp)
        assertEquals(listOf("a dir", "a.txt", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun sortModified_newestFirst_tieByName() {
        val cmp = listComparator(NvSortMode.MODIFIED, dirsFirst = true)
        val sorted = listOf(
            entry("old.mp4", mtimeMs = 100),
            entry("new.mp4", mtimeMs = 300),
            entry("midB.mp4", mtimeMs = 200),
            entry("midA.mp4", mtimeMs = 200),
        ).sortedWith(cmp)
        assertEquals(listOf("new.mp4", "midA.mp4", "midB.mp4", "old.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortSize_largestFirst() {
        val cmp = listComparator(NvSortMode.SIZE, dirsFirst = true)
        val sorted = listOf(
            entry("small.mkv", size = 10),
            entry("big.mkv", size = 300),
            entry("mid.mkv", size = 100),
        ).sortedWith(cmp)
        assertEquals(listOf("big.mkv", "mid.mkv", "small.mkv"), sorted.map { it.name })
    }

    @Test
    fun sortType_groupByExtensionAlphabetical() {
        val cmp = listComparator(NvSortMode.TYPE, dirsFirst = true)
        val sorted = listOf(
            entry("c.txt"),
            entry("a.png"),
            entry("b.mkv"),
        ).sortedWith(cmp)
        // 扩展名字母序：mkv < png < txt，组内按名称
        assertEquals(listOf("b.mkv", "a.png", "c.txt"), sorted.map { it.name })
    }

    @Test
    fun sort_isDeterministic_onFullTie() {
        val cmp = listComparator(NvSortMode.SIZE, dirsFirst = true)
        val a = entry("a.mp4", size = 5, path = "/a.mp4")
        val b = entry("b.mp4", size = 5, path = "/b.mp4")
        assertEquals(listOf(a, b), listOf(a, b).sortedWith(cmp))
        assertEquals(listOf(a, b), listOf(b, a).sortedWith(cmp))
    }

    // ---------- matchesQuery / highlightRanges：包含匹配、大小写不敏感 ----------

    @Test
    fun matchesQuery_caseInsensitiveContains_blankMatchesAll() {
        assertTrue(matchesQuery("Week 01 S01E01.mkv", "e01"))
        assertTrue(matchesQuery("第一季.mkv", "一"))
        assertTrue(matchesQuery("anything", ""))
        assertTrue(matchesQuery("anything", "   "))
        assertFalse(matchesQuery("abc.mkv", "xyz"))
    }

    @Test
    fun highlightRanges_allOccurrences() {
        assertEquals(
            listOf(5..6, 9..10, 12..13),
            highlightRanges("Week 01 S01E01", "01"),
        )
        assertEquals(listOf(0..1, 2..3), highlightRanges("ABab", "ab"))
        assertTrue(highlightRanges("abc", "xyz").isEmpty())
        assertTrue(highlightRanges("abc", "  ").isEmpty())
    }

    // ---------- relativePathUnder：子目录命中次行展示 ----------

    @Test
    fun relativePathUnder_stripsCurrentDir() {
        assertEquals("sub/a.mp4", relativePathUnder("/media/sub/a.mp4", "/media"))
        assertEquals("a.mp4", relativePathUnder("/media/a.mp4", "/media"))
        assertEquals("a.mp4", relativePathUnder("/a.mp4", ""))   // 根目录
    }

    @Test
    fun relativePathUnder_nullWhenNotUnder() {
        assertNull(relativePathUnder("/movies/a.mp4", "/media"))
        assertNull(relativePathUnder("/media", "/media"))        // 目录自身
        assertNull(relativePathUnder("/mediax/a.mp4", "/media")) // 前缀粘连目录
    }

    // ---------- applyListPipeline：search → filter → sort 组合 ----------

    @Test
    fun pipeline_searchFiltersMergedThenTypeFilterThenSort() {
        val current = listOf(
            entry("Episodes", isDir = true, path = "/Episodes"),
            entry("video1.mp4", size = 10, path = "/video1.mp4"),
            entry("audio1.mp3", path = "/audio1.mp3"),
            entry("docs.epub", path = "/docs.epub"),     // 文档：被类型过滤剔除
        )
        val subs = listOf(
            entry("video0.mp4", size = 5, path = "/season1/video0.mp4"), // 子目录命中
            entry("noisy.log", path = "/season1/noisy.log"),             // 搜索不命中
        )
        val result = applyListPipeline(
            current,
            NvListQuery(
                enabledTypes = setOf(NvFileType.VIDEO),
                sortMode = NvSortMode.SIZE,
                dirsFirst = true,
                searchQuery = "video",
            ),
            subs,
        )
        // "Episodes" 目录名不含 "video" → 搜索剔除；video1(10) > video0(5)
        assertEquals(listOf("/video1.mp4", "/season1/video0.mp4"), result.map { it.path })
    }

    @Test
    fun pipeline_noSearch_dirsAlwaysShown_andSortedFirst() {
        val current = listOf(
            entry("b.mp4", size = 1, path = "/b.mp4"),
            entry("zips", isDir = true, path = "/zips"),
            entry("a.mp4", size = 2, path = "/a.mp4"),
        )
        val result = applyListPipeline(
            current,
            NvListQuery(enabledTypes = setOf(NvFileType.AUDIO)),   // 只勾音频 → 文件全滤掉
            emptyList(),
        )
        // 文件夹不受过滤影响，仍显示并置顶
        assertEquals(listOf("/zips"), result.map { it.path })
    }

    @Test
    fun pipeline_emptyQuery_returnsAllSorted() {
        val current = listOf(entry("b.mp4", size = 1), entry("a.mp4", size = 2))
        val result = applyListPipeline(current, NvListQuery(), emptyList())
        assertEquals(listOf("/a.mp4", "/b.mp4"), result.map { it.path })
    }

    @Test
    fun pipeline_dedupes_currentVsSubResults_byPath_keepsCurrentVersion() {
        // 2026-09-15 回归：子目录 BFS 从当前目录起扫，当前目录命中文件同时出现在
        // current 与 subResults → LazyColumn key(path) 重复直接崩溃。
        val currentHit = entry("video1.mp4", size = 10, path = "/video1.mp4")
        val current = listOf(currentHit, entry("b.mp4", path = "/b.mp4"))
        val subs = listOf(
            entry("video1.mp4", size = 10, path = "/video1.mp4"),   // 与 current 重复
            entry("video0.mp4", size = 5, path = "/season1/video0.mp4"),
        )
        val result = applyListPipeline(
            current,
            NvListQuery(searchQuery = "video"),
            subs,
        )
        val paths = result.map { it.path }
        assertEquals("path 不得重复", paths.size, paths.distinct().size)
        // 保留 current 版本（同一 path 的另一份被丢弃，不是追加）
        assertSame(currentHit, result.first { it.path == "/video1.mp4" })
        // 搜索态下不命中 query 的当前目录行（b.mp4）仍被过滤（去重不改变搜索语义）
        assertEquals(listOf("/season1/video0.mp4", "/video1.mp4"), paths.sorted())
    }

    @Test
    fun pipeline_dedupe_appliesBeforeTypeFilterAndSort() {
        val current = listOf(entry("a.mp4", path = "/a.mp4"))
        val subs = listOf(
            entry("a.mp4", path = "/a.mp4"),                        // 去重后不再参与过滤计数
            entry("noise.log", path = "/sub/noise.log"),            // 类型过滤剔除
        )
        val result = applyListPipeline(
            current,
            NvListQuery(
                enabledTypes = setOf(NvFileType.VIDEO),
                sortMode = NvSortMode.NAME,
                searchQuery = "",
            ),
            subs,
        )
        assertEquals(listOf("/a.mp4"), result.map { it.path })
    }
}
