package com.novax.app.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * P1 修复（GBK 字幕乱码）· SubtitleCharset 单测（纯 JVM，GBK/Big5 由桌面 JVM 内置）：
 * 覆盖 BOM / 严格 UTF-8 / GBK 启发式探测，以及转码为 UTF-8 的往返一致性。
 */
class SubtitleCharsetTest {

    // ---- detect：BOM ----

    @Test
    fun `utf8 bom detected`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "字幕".toByteArray(Charsets.UTF_8)
        assertEquals("UTF-8", SubtitleCharset.detect(bytes))
    }

    @Test
    fun `utf16 bom detected`() {
        assertEquals(
            "UTF-16LE",
            SubtitleCharset.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0x00)),
        )
        assertEquals(
            "UTF-16BE",
            SubtitleCharset.detect(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x68)),
        )
    }

    // ---- detect：严格 UTF-8 ----

    @Test
    fun `plain utf8 chinese detected`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界\n".toByteArray(Charsets.UTF_8)
        assertEquals("UTF-8", SubtitleCharset.detect(bytes))
    }

    @Test
    fun `plain ascii detected as utf8`() {
        assertEquals("UTF-8", SubtitleCharset.detect("Hello, world!\r\n".toByteArray(Charsets.ISO_8859_1)))
    }

    // ---- detect：GBK 启发式 ----

    @Test
    fun `gbk chinese detected`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界。\n".toByteArray(Charset.forName("GBK"))
        assertEquals("GBK", SubtitleCharset.detect(bytes))
    }

    @Test
    fun `gbk two chars detected`() {
        // 「你好」GBK = C4 E3 BA C3：非法 UTF-8（C4 的续字节 E3 不合法）→ GBK 配对合法
        assertEquals("GBK", SubtitleCharset.detect("你好".toByteArray(Charset.forName("GBK"))))
    }

    // ---- transcodeToUtf8：往返一致 ----

    @Test
    fun `gbk transcoded to utf8 roundtrip`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n深夜代码审查：字幕不再乱码。\n"
        val gbk = text.toByteArray(Charset.forName("GBK"))
        val utf8 = SubtitleCharset.transcodeToUtf8(gbk, Charset.forName("GBK"))
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), utf8)
        assertEquals(text, String(utf8, Charsets.UTF_8))
    }

    @Test
    fun `big5 forced transcode roundtrip`() {
        val text = "繁體字幕測試"
        val big5 = text.toByteArray(Charset.forName("Big5"))
        val utf8 = SubtitleCharset.transcodeToUtf8(big5, SubtitleCharset.charsetForPref("big5"))
        assertEquals(text, String(utf8, Charsets.UTF_8))
    }

    @Test
    fun `utf8 passthrough and bom stripped`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nhello\n"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        val out = SubtitleCharset.transcodeToUtf8(withBom, Charsets.UTF_8)
        assertEquals(text, String(out, Charsets.UTF_8))
        assertFalse(out.size >= 3 && out[0] == 0xEF.toByte())
    }

    @Test
    fun `utf16le bom transcodes to utf8`() {
        val text = "16 位字幕"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val out = SubtitleCharset.transcodeToUtf8(bytes, Charsets.UTF_16LE)
        assertEquals(text, String(out, Charsets.UTF_8))
    }

    @Test
    fun `invalid bytes replaced not thrown`() {
        val garbage = byteArrayOf(0xC4.toByte(), 0x00, 0xFF.toByte(), 0x41)
        val out = SubtitleCharset.transcodeToUtf8(garbage, Charset.forName("GBK"))
        assertTrue(String(out, Charsets.UTF_8).contains("A"))
    }

    // ---- charsetForPref ----

    @Test
    fun `charset pref mapping`() {
        assertEquals(Charsets.UTF_8, SubtitleCharset.charsetForPref("utf-8"))
        assertEquals("GBK", SubtitleCharset.charsetForPref("gbk").name())
        assertEquals("Big5", SubtitleCharset.charsetForPref("big5").name())
        assertEquals(Charsets.UTF_8, SubtitleCharset.charsetForPref("unknown"))
    }

    // ---- GBK 启发式边界 ----

    @Test
    fun `pure ascii is not gbk`() {
        assertFalse(SubtitleCharset.isLikelyGbk("plain english subtitle".toByteArray(Charsets.UTF_8)))
        assertEquals("UTF-8", SubtitleCharset.detect("plain english subtitle".toByteArray(Charsets.UTF_8)))
    }
}
