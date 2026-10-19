package com.novax.app.ui.viewers

import org.junit.Assert.assertEquals
import org.junit.Test

/** C9 §一 路由表全表单测：扩展名（小写/大写/多级路径）→ NvFileRouter.Kind */
class NvFileRouterTest {

    // ---- 视频（全部走播放器，行为不变）----
    @Test
    fun `video extensions route to VIDEO`() {
        val exts = listOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts",
            "m2ts", "mpg", "mpeg", "3gp", "ogv", "rmvb",
        )
        exts.forEach { ext ->
            assertEquals(".$ext 应为 VIDEO", NvFileRouter.Kind.VIDEO, NvFileRouter.kindOf("movie.$ext"))
        }
    }

    // ---- 图片（ViewerActivity 图片查看器）----
    @Test
    fun `image extensions route to IMAGE`() {
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic").forEach { ext ->
            assertEquals(".$ext 应为 IMAGE", NvFileRouter.Kind.IMAGE, NvFileRouter.kindOf("photo.$ext"))
        }
    }

    // ---- PDF ----
    @Test
    fun `pdf routes to PDF regardless of case`() {
        assertEquals(NvFileRouter.Kind.PDF, NvFileRouter.kindOf("design.pdf"))
        assertEquals(NvFileRouter.Kind.PDF, NvFileRouter.kindOf("design.PDF"))
        assertEquals(NvFileRouter.Kind.PDF, NvFileRouter.kindOf("/mnt/share/Docs/报告.Pdf"))
    }

    // ---- 文本（含字幕特判：srt/ass/ssa/vtt 直接点开按 TEXT 预览）----
    @Test
    fun `text extensions route to TEXT`() {
        listOf("txt", "log", "md", "srt", "ass", "ssa", "vtt", "ini", "conf", "json", "xml", "csv").forEach { ext ->
            assertEquals(".$ext 应为 TEXT", NvFileRouter.Kind.TEXT, NvFileRouter.kindOf("notes.$ext"))
        }
    }

    // ---- 音频（播放器音频模式，不进 ViewerActivity）----
    @Test
    fun `audio extensions route to AUDIO`() {
        listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus").forEach { ext ->
            assertEquals(".$ext 应为 AUDIO", NvFileRouter.Kind.AUDIO, NvFileRouter.kindOf("song.$ext"))
        }
    }

    // ---- 未知（页内弹层「用其他应用打开」）----
    @Test
    fun `unknown extensions route to UNKNOWN`() {
        listOf("dat", "exe", "apk", "zip", "tar", "gz", "db", "iso").forEach { ext ->
            assertEquals(".$ext 应为 UNKNOWN", NvFileRouter.Kind.UNKNOWN, NvFileRouter.kindOf("file.$ext"))
        }
    }

    // ---- 边界：无扩展名 / 点结尾 / 隐藏文件 / 多级路径 ----
    @Test
    fun `edge cases fall back to UNKNOWN`() {
        assertEquals(NvFileRouter.Kind.UNKNOWN, NvFileRouter.kindOf("README"))
        assertEquals(NvFileRouter.Kind.UNKNOWN, NvFileRouter.kindOf("archive."))     // 点结尾
        assertEquals(NvFileRouter.Kind.UNKNOWN, NvFileRouter.kindOf(".gitignore"))   // 隐藏文件（ext=gitignore 非已知类型）
        assertEquals(NvFileRouter.Kind.VIDEO, NvFileRouter.kindOf("/a/b/c/movie.MKV")) // 大写 + 多级路径
        assertEquals(NvFileRouter.Kind.IMAGE, NvFileRouter.kindOf("\\\\nas\\\\share\\\\pic.JPG")) // 反斜杠路径
    }

    // ---- extensionOf 纯函数 ----
    @Test
    fun `extensionOf extracts lowercase extension`() {
        assertEquals("mkv", NvFileRouter.extensionOf("a.b.Movie.MKV"))
        assertEquals("", NvFileRouter.extensionOf("noext"))
        assertEquals("", NvFileRouter.extensionOf("trailing."))
        assertEquals("", NvFileRouter.extensionOf(""))
    }
}
