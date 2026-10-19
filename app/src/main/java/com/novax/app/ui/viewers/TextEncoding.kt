package com.novax.app.ui.viewers

import java.nio.charset.Charset

/**
 * 文本阅读器读取限额（C9 §四；纯常量 + 纯函数，可单测）：
 * - 流式最多读 [READ_CAP_BYTES]（2MB），超过判定为大文件 → 截断降级（不拦截）；
 * - 大文件只保留前 [TRUNCATE_KEEP_BYTES]（512KB）渲染，底部横幅提示 + 「完整打开」外发；
 * - 编码探测只读前 [DETECT_HEAD_BYTES]（8KB）。
 */
object NvTextLimits {
    const val READ_CAP_BYTES = 2 * 1024 * 1024
    const val TRUNCATE_KEEP_BYTES = 512 * 1024
    const val DETECT_HEAD_BYTES = 8 * 1024

    /** 本次读取字节数是否触发截断（读到 CAP+1 才算超限，恰好等于 CAP 不算） */
    fun isOversize(readCount: Int): Boolean = readCount > READ_CAP_BYTES

    /** 截断后用于渲染解码的字节数（未超限原样返回） */
    fun keepCount(readCount: Int): Int =
        if (readCount > READ_CAP_BYTES) TRUNCATE_KEEP_BYTES else readCount
}

/**
 * 编码检测（纯函数，C9 §4.1）：
 * BOM（UTF-8 / UTF-16LE / UTF-16BE）→ 严格 UTF-8 逐字节校验 → GBK 启发式（高字节比例 + 码位配对校验）
 * → 兜底 ISO-8859-1（绝不无声乱码：用户可经编码 Chip 手选重载）。
 */
object NvTextCodec {

    fun detectCharset(bytes: ByteArray, length: Int = bytes.size): Charset {
        if (length >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        // 无 BOM 的 UTF-16（BUG-C-R8-01：ASCII 段含 0x00，不先判 UTF-16 会先被
        // isStrictUtf8 放行成「带 NUL 的 UTF-8」渲染出乱码）
        when (utf16NoBomAlignment(bytes, length)) {
            1 -> return Charsets.UTF_16LE
            2 -> return Charsets.UTF_16BE
        }
        if (isStrictUtf8(bytes, length)) return Charsets.UTF_8
        if (isLikelyGbk(bytes, length)) return Charset.forName("GBK")
        return Charsets.ISO_8859_1
    }

    /**
     * TEXT 扩展名但头部像二进制 → 拒绝文本预览（外发处理）。
     * BUG-C-R8-01：原实现逐字节扫 NUL——UTF-16 编码的 ASCII 文本（含换行即中招）恒被
     * 误判二进制，BOM 探测与手选 UTF-16 编码被整体短路成死代码。
     */
    fun isBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, NvTextLimits.DETECT_HEAD_BYTES)
        if (n >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        ) {
            return false   // UTF-16 BOM：文本
        }
        if (utf16NoBomAlignment(bytes, n) != 0) return false   // 无 BOM 的 UTF-16：文本
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** 无 BOM 的 UTF-16 启发式：0=非 UTF-16，1=LE（奇数位零占多数），2=BE（偶数位零占多数） */
    private fun utf16NoBomAlignment(bytes: ByteArray, length: Int): Int {
        val n = minOf(length, NvTextLimits.DETECT_HEAD_BYTES)
        if (n < 8) return 0
        var pairs = 0
        var zeroAtEven = 0
        var zeroAtOdd = 0
        var i = 0
        while (i + 1 < n) {
            pairs++
            if (bytes[i] == 0.toByte()) zeroAtEven++
            if (bytes[i + 1] == 0.toByte()) zeroAtOdd++
            i += 2
        }
        if (pairs == 0) return 0
        return when {
            zeroAtOdd * 10 / pairs >= 6 -> 1
            zeroAtEven * 10 / pairs >= 6 -> 2
            else -> 0
        }
    }

    /**
     * 严格 UTF-8 校验：序列完整合法（含超长/代理区保护）才算通过；
     * 结尾被截断的序列按通过处理（探测头只有 8KB，恰好切断多字节字符时不应误判）。
     */
    fun isStrictUtf8(bytes: ByteArray, length: Int = bytes.size): Boolean {
        var i = 0
        while (i < length) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> i += 1
                b0 in 0xC2..0xDF -> {
                    if (i + 1 >= length) return true                       // 尾部截断：放行
                    if (!isUtf8Cont(bytes[i + 1])) return false
                    i += 2
                }
                b0 in 0xE0..0xEF -> {
                    if (i + 2 >= length) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b0 == 0xE0 && b1 < 0xA0) return false              // 超长 3 字节
                    if (b0 == 0xED && b1 > 0x9F) return false              // 代理区
                    if (!isUtf8Cont(bytes[i + 1]) || !isUtf8Cont(bytes[i + 2])) return false
                    i += 3
                }
                b0 in 0xF0..0xF4 -> {
                    if (i + 3 >= length) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b0 == 0xF0 && b1 < 0x90) return false              // 超长 4 字节
                    if (b0 == 0xF4 && b1 > 0x8F) return false              // > U+10FFFF
                    if (!isUtf8Cont(bytes[i + 1]) || !isUtf8Cont(bytes[i + 2]) || !isUtf8Cont(bytes[i + 3])) return false
                    i += 4
                }
                else -> return false                                       // 0x80-0xC1 / 0xF5+
            }
        }
        return true
    }

    /**
     * GBK 启发式：高字节占比 ≥ 5%，且所有双字节配对合法（首字节 0x81-0xFE，
     * 次字节 0x40-0xFE 除 0x7F）。出现非法配对 → 判非 GBK → ISO-8859-1。
     */
    fun isLikelyGbk(bytes: ByteArray, length: Int = bytes.size): Boolean {
        var i = 0
        var high = 0
        var pairs = 0
        var invalidHigh = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) {
                high++
                val next = if (i + 1 < length) (bytes[i + 1].toInt() and 0xFF) else -1
                when {
                    // 探测头恰在双字节首字节处截断：宽容放行不计 invalid
                    //（BUG-C-R8-04：与 isStrictUtf8 的尾部截断放行同原则，否则约半数
                    //  ≥8KB 的 GBK 文件被判 ISO-8859-1 乱码）
                    next == -1 -> i += 1
                    isGbkTrail(next) -> {
                        pairs++
                        i += 2
                    }
                    else -> {
                        invalidHigh++
                        i += 1
                    }
                }
            } else {
                i++
            }
        }
        if (high == 0 || length == 0) return false
        val ratio = high.toDouble() / length
        return invalidHigh == 0 && pairs > 0 && ratio >= 0.05
    }

    private fun isUtf8Cont(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 0x80..0xBF
    }

    private fun isGbkTrail(b: Int): Boolean = b in 0x40..0xFE && b != 0x7F
}

/**
 * 编码 Chip 手选选项（C9 §4.1 NvEncodingSheet：选中 ✓，确认后按新编码重载）。
 * GBK / Big5 / Shift_JIS 桌面 JVM 与 Android 均内置，可单测。
 */
enum class NvTextCharsetOption(val label: String, val charset: Charset) {
    UTF8("UTF-8", Charsets.UTF_8),
    GBK("GBK", Charset.forName("GBK")),
    BIG5("Big5", Charset.forName("Big5")),
    SHIFT_JIS("Shift_JIS", Charset.forName("Shift_JIS")),
    UTF16LE("UTF-16LE", Charsets.UTF_16LE),
    UTF16BE("UTF-16BE", Charsets.UTF_16BE),
    ;

    companion object {
        /** 检测结果 → 选项（匹配不到返回 UTF-8 兜底） */
        fun of(c: Charset): NvTextCharsetOption =
            entries.firstOrNull { it.charset.name().equals(c.name(), true) } ?: UTF8
    }
}
