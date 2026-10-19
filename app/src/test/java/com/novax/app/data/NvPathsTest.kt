package com.novax.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NvFileEntry 路径拼接 / 父子目录解析（纯逻辑单测） */
class NvPathsTest {

    // ---------- join ----------

    @Test
    fun join_variousDirForms() {
        assertEquals("/media/a.mkv", NvPaths.join("/media", "a.mkv"))
        assertEquals("/media/a.mkv", NvPaths.join("/media/", "a.mkv"))
        assertEquals("/media/a.mkv", NvPaths.join("media", "a.mkv"))
        assertEquals("/media/a.mkv", NvPaths.join("/media//", "a.mkv"))
        assertEquals("/media/media2/a.mkv", NvPaths.join("/media/media2", "a.mkv"))
    }

    @Test
    fun join_root() {
        assertEquals("/a.mkv", NvPaths.join("/", "a.mkv"))
        assertEquals("/a.mkv", NvPaths.join("", "a.mkv"))
    }

    @Test
    fun join_backslashDirNormalized() {
        assertEquals("/media/a.mkv", NvPaths.join("\\media\\", "a.mkv"))
    }

    // ---------- normalize ----------

    @Test
    fun normalize_collapsesAndPrefixes() {
        assertEquals("/a/b/c", NvPaths.normalize("a//b\\c/"))
        assertEquals("/", NvPaths.normalize(""))
        assertEquals("/", NvPaths.normalize("/"))
        assertEquals("/a", NvPaths.normalize("///a///"))
    }

    @Test
    fun normalize_foldsDotSegments() {
        // 段级折叠（审查 P2-4）："." 跳过、".." 弹出上一段
        assertEquals("/a/c", NvPaths.normalize("/a/./c"))
        assertEquals("/a/b", NvPaths.normalize("/a/b/c/.."))
        assertEquals("/a", NvPaths.normalize("/a/b/../c/.."))
        assertEquals("/", NvPaths.normalize("/a/.."))
        assertEquals("/x", NvPaths.normalize("/a/../b/../../x"))
    }

    @Test
    fun normalize_discardsDotDotAboveRoot() {
        // 根之前多余的 ".." 丢弃（统一路径相对源根，越出根即停在根）
        assertEquals("/", NvPaths.normalize("/.."))
        assertEquals("/", NvPaths.normalize("/../../.."))
        assertEquals("/", NvPaths.normalize(".."))
        assertEquals("/etc", NvPaths.normalize("/../../etc/passwd/.."))
    }

    @Test
    fun normalize_dotFoldWithBackslashes() {
        // 混合反斜杠的穿越载荷同样被折叠消解
        assertEquals("/a", NvPaths.normalize("\\a\\b\\..\\."))
        assertEquals("/x", NvPaths.normalize("\\..\\..\\x"))
    }

    @Test
    fun normalize_normalPathsUnchanged() {
        // 正常路径零行为差异（回归护栏）
        assertEquals("/media/movies/a.mkv", NvPaths.normalize("/media/movies/a.mkv"))
        assertEquals("/media", NvPaths.normalize("media"))
        assertEquals("/a/b", NvPaths.normalize("a/b/"))
        assertEquals("/顶/层", NvPaths.normalize("/顶/层"))
    }

    // ---------- parent / fileName ----------

    @Test
    fun parentOf_file() {
        assertEquals("/media", NvPaths.parentOf("/media/a.mkv"))
        assertEquals("/media/tv/show", NvPaths.parentOf("/media/tv/show/s01e01.mkv"))
    }

    @Test
    fun parentOf_directoryWithTrailingSlash() {
        assertEquals("/media", NvPaths.parentOf("/media/tv/"))
    }

    @Test
    fun parentOf_root() {
        assertEquals("/", NvPaths.parentOf("/"))
        assertEquals("/", NvPaths.parentOf("/a.mkv"))
        assertEquals("/", NvPaths.parentOf(""))
    }

    @Test
    fun fileNameOf_bothSeparators() {
        assertEquals("a.mkv", NvPaths.fileNameOf("/media/a.mkv"))
        assertEquals("a.mkv", NvPaths.fileNameOf("\\media\\a.mkv"))
        assertEquals("tv", NvPaths.fileNameOf("/media/tv/"))
        assertEquals("a.mkv", NvPaths.fileNameOf("a.mkv"))
    }

    // ---------- toSmb ----------

    @Test
    fun toSmb_conventions() {
        assertEquals("", NvPaths.toSmb("/"))
        assertEquals("", NvPaths.toSmb(""))
        assertEquals("media", NvPaths.toSmb("/media/"))
        assertEquals("media\\movies\\a.mkv", NvPaths.toSmb("/media/movies/a.mkv"))
        assertEquals("media\\movies\\a.mkv", NvPaths.toSmb("media/movies/a.mkv"))
    }

    // ---------- isChildOf / asDirectory ----------

    @Test
    fun isChildOf_semantics() {
        assertTrue(NvPaths.isChildOf("/media", "/media/a.mkv"))
        assertTrue(NvPaths.isChildOf("/media", "/media/sub/"))
        assertTrue(NvPaths.isChildOf("/media/", "/media/sub/x.mkv"))
        assertFalse(NvPaths.isChildOf("/media", "/media"))
        assertFalse(NvPaths.isChildOf("/media", "/media2/x.mkv"))
        assertFalse(NvPaths.isChildOf("/media", "/other/x.mkv"))
    }

    @Test
    fun asDirectory_appendsSeparator() {
        assertEquals("/media/", NvPaths.asDirectory("/media"))
        assertEquals("/media/", NvPaths.asDirectory("/media/"))
        assertEquals("/", NvPaths.asDirectory(""))
    }

    // ---------- localPathInternal（本地源路径解析：拼接语义 + 穿越消解哨兵） ----------

    @Test
    fun localPath_normalSemanticsUnchanged() {
        // 既有拼接语义零行为差异
        assertEquals("/media/nas/video/a.mkv", NvSourceRepository.localPathInternal("/media/nas", "/video/a.mkv", "n"))
        assertEquals("/media/nas/video/a.mkv", NvSourceRepository.localPathInternal("/media/nas/", "video/a.mkv", "n"))
        assertEquals("/media/nas/video/a.mkv", NvSourceRepository.localPathInternal("/media/nas\\", "\\video\\a.mkv", "n"))
        assertEquals("/media/nas", NvSourceRepository.localPathInternal("/media/nas", "/", "n"))
        // root 为空 → path 视为绝对路径（补前导分隔符，行为同旧实现）；
        // absolutePath 是平台相关分隔符（Windows JVM 为反斜杠），经 normalize 断言尾段
        assertTrue(
            NvPaths.normalize(NvSourceRepository.localPathInternal("", "/media/a.mkv", "n"))
                .endsWith("/media/a.mkv"),
        )
    }

    @Test
    fun localPath_traversalPayloadStaysInsideRoot() {
        // 审查 P2-4：外部 novax:// 的 ".." 载荷经 normalize 折叠后停在源根内，
        // 不再解析为源根之外的文件系统位置（前缀哨兵对折叠后结果恒通过）
        assertEquals("/media/nas/secret", NvSourceRepository.localPathInternal("/media/nas", "/../../secret", "n"))
        assertEquals("/media/nas/b", NvSourceRepository.localPathInternal("/media/nas", "/a/../../b", "n"))
        assertEquals("/media/nas", NvSourceRepository.localPathInternal("/media/nas", "\\..\\..\\", "n"))
        assertEquals("/media/nas/etc/passwd", NvSourceRepository.localPathInternal("/media/nas", "/../../../../etc/passwd", "n"))
        assertEquals("/media/nas", NvSourceRepository.localPathInternal("/media/nas", "/./.././..", "n"))
        // 哨兵谓词本身：折叠后的拼接结果必满足「落在源根之下」。
        // 注意哨兵只防 normalize 回归（未折叠的 ".." 拼接后字符串上仍以源根为前缀、
        // 语义上已逃逸）——真正的防线是折叠，哨兵仅为纵深。
        val base = "/media/nas"
        fun passesSentinel(p: String) = (base + NvPaths.normalize(p)).startsWith("$base/")
        assertTrue(passesSentinel("/../../etc/passwd"))
        assertTrue(passesSentinel("/a/../../b/c/.."))
    }
}
