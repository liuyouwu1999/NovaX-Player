package com.novax.app.ui.viewers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** C9 §4.1 编码检测 + 二进制判定 + 截断阈值单测（纯 JVM，GBK/Big5/Shift_JIS 由桌面 JVM 提供） */
class NvTextCodecTest {

    // ---- BOM ----
    @Test
    fun `utf8 bom detected`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()
        assertEquals(Charsets.UTF_8, NvTextCodec.detectCharset(bytes))
    }

    @Test
    fun `utf16 bom detected`() {
        assertEquals(
            Charsets.UTF_16LE,
            NvTextCodec.detectCharset(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0x00)),
        )
        assertEquals(
            Charsets.UTF_16BE,
            NvTextCodec.detectCharset(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x68)),
        )
    }

    // ---- 无 BOM：严格 UTF-8 ----
    @Test
    fun `plain ascii is utf8`() {
        assertEquals(Charsets.UTF_8, NvTextCodec.detectCharset("2026-09-13 INFO NovaX ready\n".toByteArray()))
    }

    @Test
    fun `valid utf8 multibyte is utf8`() {
        // "中文abc" UTF-8（含 3 字节序列）
        assertEquals(Charsets.UTF_8, NvTextCodec.detectCharset("中文abc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `invalid utf8 sequence rejects`() {
        // 0xE5 后跟 0x41（'A'）：非 UTF-8 continuation 字节 → 严格校验失败；
        // 但 0x41 在 GBK 次字节区间（0x40-0xFE）→ 高字节启发式判 GBK
        val bad = byteArrayOf(0xE5.toByte(), 0x41, 0x42)
        assertFalse(NvTextCodec.isStrictUtf8(bad))
        assertTrue(NvTextCodec.isLikelyGbk(bad))
        assertEquals(Charset.forName("GBK"), NvTextCodec.detectCharset(bad))
    }

    // ---- GBK 启发式（高字节比例 + 码位配对）----
    @Test
    fun `gbk chinese text detected as gbk`() {
        // 4+ 个汉字的 GBK 编码必然出现非 UTF-8 合法序列（如 0xB2 0xE2）
        val bytes = "中文测试文本检测".toByteArray(Charset.forName("GBK"))
        assertFalse(NvTextCodec.isStrictUtf8(bytes))
        assertEquals(Charset.forName("GBK"), NvTextCodec.detectCharset(bytes))
    }

    @Test
    fun `gbk bytes decode back to original with detected charset`() {
        val text = "乱码不无声：可手选编码重载\n第二行"
        val bytes = text.toByteArray(Charset.forName("GBK"))
        val detected = NvTextCodec.detectCharset(bytes)
        assertEquals(text, String(bytes, detected))
    }

    // ---- ISO-8859-1 兜底 ----
    @Test
    fun `invalid gbk pairing falls back to iso8859_1`() {
        // 0x81 是 GBK 首字节但次字节 0x20 非法 → GBK 配对失败 → ISO-8859-1
        val bytes = byteArrayOf(0x81.toByte(), 0x20, 0x20, 0x20, 0x20)
        assertFalse(NvTextCodec.isLikelyGbk(bytes))
        assertEquals(Charsets.ISO_8859_1, NvTextCodec.detectCharset(bytes))
    }

    // ---- 二进制判定（TEXT 扩展名但含 NUL）----
    @Test
    fun `nul byte means binary`() {
        val bytes = "text".toByteArray() + byteArrayOf(0) + "more".toByteArray()
        assertTrue(NvTextCodec.isBinary(bytes))
        assertFalse(NvTextCodec.isBinary("plain text".toByteArray()))
    }

    // ---- 截断阈值（>2MB 判大文件，只保留前 512KB）----
    @Test
    fun `truncation thresholds`() {
        val cap = NvTextLimits.READ_CAP_BYTES
        val keep = NvTextLimits.TRUNCATE_KEEP_BYTES
        assertEquals(2 * 1024 * 1024, cap)
        assertEquals(512 * 1024, keep)
        assertFalse(NvTextLimits.isOversize(cap))        // 恰好等于 CAP 不算超限
        assertTrue(NvTextLimits.isOversize(cap + 1))     // 多读 1 字节即超限
        assertEquals(cap, NvTextLimits.keepCount(cap))
        assertEquals(keep, NvTextLimits.keepCount(cap + 1))
        assertEquals(42, NvTextLimits.keepCount(42))     // 小文件原样
    }

    // ---- 手选编码选项 ----
    @Test
    fun `charset options resolve detected charsets`() {
        assertEquals(NvTextCharsetOption.UTF8, NvTextCharsetOption.of(Charsets.UTF_8))
        assertEquals(NvTextCharsetOption.GBK, NvTextCharsetOption.of(Charset.forName("GBK")))
        assertEquals(NvTextCharsetOption.BIG5, NvTextCharsetOption.of(Charset.forName("Big5")))
        assertEquals(NvTextCharsetOption.SHIFT_JIS, NvTextCharsetOption.of(Charset.forName("Shift_JIS")))
        assertEquals(NvTextCharsetOption.UTF16LE, NvTextCharsetOption.of(Charsets.UTF_16LE))
        assertEquals(NvTextCharsetOption.UTF16BE, NvTextCharsetOption.of(Charsets.UTF_16BE))
    }
}
