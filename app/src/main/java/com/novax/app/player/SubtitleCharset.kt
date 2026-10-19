package com.novax.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import com.novax.app.data.NvGraph
import com.novax.app.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * C10 字幕编码键（novax_subtitle_encoding：auto / utf-8 / gbk / big5）· P1 修复 GBK 字幕乱码。
 *
 * 方案：side-load 挂载前把字幕字节流重编码为 UTF-8 临时文件再交给 ExoPlayer（挂载路径
 * 本地 file 与 novax:// 双路统一过 [transcodedSubtitleConfiguration]），ExoPlayer 全程按
 * UTF-8 解析，不再出现 GBK 乱码。编码裁决：auto 用 [SubtitleCharset.detect]
 * （BOM → 严格 UTF-8 校验 → GBK 启发式），指定编码则强制。
 *
 * detect 纯函数可单测（SubtitleCharsetTest），思路参考 ui/viewers 文本阅读器实现、独立成码。
 */
object SubtitleCharset {

    private const val TAG = "SubtitleCharset"

    /**
     * 编码探测（纯函数）：UTF-8/UTF-16 BOM → 严格 UTF-8 逐字节校验 → GBK 启发式 → 兜底 UTF-8。
     * @return charset 名称（"UTF-8" / "UTF-16LE" / "UTF-16BE" / "GBK"）
     */
    fun detect(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        if (isStrictUtf8(bytes)) return "UTF-8"
        if (isLikelyGbk(bytes)) return "GBK"
        return "UTF-8"
    }

    /**
     * 按指定编码解码后重编码为 UTF-8（纯函数）：非法字节以 U+FFFD 替换（绝不抛异常），
     * 并剥掉解码后残留的 BOM（U+FEFF）。
     */
    fun transcodeToUtf8(bytes: ByteArray, charset: Charset): ByteArray {
        var text = if (charset.name().equals(Charsets.UTF_8.name(), true)) {
            String(bytes, Charsets.UTF_8)
        } else {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return text.toByteArray(Charsets.UTF_8)
    }

    /** 设置键值（"utf-8"/"gbk"/"big5"）→ Charset；未知值回退 UTF-8 */
    fun charsetForPref(pref: String): Charset = when (pref.lowercase()) {
        "utf-8" -> Charsets.UTF_8
        "gbk" -> Charset.forName("GBK")
        "big5" -> Charset.forName("Big5")
        else -> Charsets.UTF_8
    }

    /**
     * 严格 UTF-8 校验：序列完整合法（含超长/代理区保护）才算通过；
     * 结尾被截断的多字节序列放行（探测头截断不应误判）。
     */
    fun isStrictUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> i += 1
                b0 in 0xC2..0xDF -> {
                    if (i + 1 >= n) return true
                    if (!isUtf8Cont(bytes[i + 1])) return false
                    i += 2
                }
                b0 in 0xE0..0xEF -> {
                    if (i + 2 >= n) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b0 == 0xE0 && b1 < 0xA0) return false
                    if (b0 == 0xED && b1 > 0x9F) return false
                    if (!isUtf8Cont(bytes[i + 1]) || !isUtf8Cont(bytes[i + 2])) return false
                    i += 3
                }
                b0 in 0xF0..0xF4 -> {
                    if (i + 3 >= n) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b0 == 0xF0 && b1 < 0x90) return false
                    if (b0 == 0xF4 && b1 > 0x8F) return false
                    if (!isUtf8Cont(bytes[i + 1]) || !isUtf8Cont(bytes[i + 2]) || !isUtf8Cont(bytes[i + 3])) return false
                    i += 4
                }
                else -> return false   // 0x80-0xC1 / 0xF5+
            }
        }
        return true
    }

    /**
     * GBK 启发式：高字节占比 ≥5% 且双字节配对全部合法（首 0x81-0xFE，次 0x40-0xFE 除 0x7F）。
     */
    fun isLikelyGbk(bytes: ByteArray): Boolean {
        var i = 0
        var high = 0
        var pairs = 0
        var invalidHigh = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) {
                high++
                val next = if (i + 1 < n) (bytes[i + 1].toInt() and 0xFF) else -1
                if (next in 0x40..0xFE && next != 0x7F) {
                    pairs++
                    i += 2
                } else {
                    invalidHigh++
                    i += 1
                }
            } else {
                i++
            }
        }
        if (high == 0 || n == 0) return false
        return invalidHigh == 0 && pairs > 0 && high.toDouble() / n >= 0.05
    }

    private fun isUtf8Cont(b: Byte): Boolean = (b.toInt() and 0xFF) in 0x80..0xBF
}

/**
 * P1 修复 · side-load 字幕统一挂载入口（挂载路径本地 file 与 novax:// 双路共用）：
 * 读原始字节（novax:// 走仓库连接池 / 本地读文件）→ 按设置键 [KEY_SUB_ENCODING] 裁决编码
 * （auto=detect，指定=强制）→ 重编码 UTF-8 写 cache 临时文件 → 返回指向临时文件的
 * SubtitleConfiguration（id/语言/标签沿用原候选，UI 选中态对齐不变）。
 *
 * @param encodingPref 设置键原始值（null/"auto"=自动探测）；须在 IO 上下文外传入，读取由调用方完成
 * @return 可挂载配置；MIME 不支持 / 读取失败时返回 null（不阻塞播放）
 */
suspend fun transcodedSubtitleConfiguration(
    context: Context,
    candidate: SubtitleCandidate,
    novaxSource: SourceEntity?,
    encodingPref: String?,
): MediaItem.SubtitleConfiguration? {
    val mime = SubtitleMatcher.mimeTypeForFile(candidate.path) ?: return null
    val utf8File = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when {
                // M4：内嵌文本字幕的远程 vtt 直链（api_key 已在 URL 内，直开连接即可）
                candidate.remoteUrl != null ->
                    openRemote(candidate.remoteUrl).use { it.readBytes() }
                novaxSource != null -> NvGraph.repository()
                    .openStreamBlocking(novaxSource, candidate.path, 0L)
                    .use { it.readBytes() }
                else -> File(candidate.path).readBytes()
            }
            // M4：远程 vtt 服务器侧恒为 UTF-8——必须绕过 novax_subtitle_encoding 偏好：
            // 用户设了 GBK 强制时，按 GBK 解 UTF-8 字节必乱码；本地/外部候选维持既有偏好语义
            val charset = when {
                candidate.remoteUrl != null -> Charsets.UTF_8
                encodingPref == null || encodingPref == "auto" -> Charset.forName(SubtitleCharset.detect(bytes))
                else -> SubtitleCharset.charsetForPref(encodingPref)
            }
            val utf8 = SubtitleCharset.transcodeToUtf8(bytes, charset)
            val ext = SubtitleMatcher.extensionOf(SubtitleMatcher.fileName(candidate.path)) ?: "srt"
            val dir = File(context.cacheDir, "sideload_subs").apply { mkdirs() }
            File(dir, "sub${candidate.path.hashCode()}.$ext").apply { writeBytes(utf8) }
        }.onFailure { Log.w("SubtitleCharset", "transcode failed: ${candidate.path}", it) }.getOrNull()
    } ?: return null
    return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(utf8File))
        .setId(PlaybackEngine.sideLoadIdFor(candidate.path))
        .setMimeType(mime)
        .setLanguage(candidate.languageTag?.let { SubtitleMatcher.normalizeLanguageTag(it) })
        .setLabel(candidate.displayName)
        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
        .build()
}

/** M4：远程字幕直链下载（vtt 服务端已转 UTF-8；失败抛 IOException，由调用方 runCatching 兜底为「无字幕继续播」）。
 *  落点：文件顶层（与 transcodedSubtitleConfiguration 同级）——不得放进 object SubtitleCharset 内，否则调用不成立 */
private fun openRemote(url: String): java.io.InputStream {
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "NovaX/1.0")
    }
    if (conn.responseCode !in 200..299) {
        conn.disconnect()
        throw java.io.IOException("字幕下载失败: HTTP ${conn.responseCode}")
    }
    return conn.inputStream
}
