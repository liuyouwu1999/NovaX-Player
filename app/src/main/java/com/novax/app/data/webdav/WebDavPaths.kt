package com.novax.app.data.webdav

import com.novax.app.data.NvPaths
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * WebDAV 路径/URL/日期工具（纯函数，可单测）。
 * 不依赖 Android/OkHttp 类型，便于 JVM 单测。
 */
object WebDavPaths {

    const val DEFAULT_HTTP_PORT = 80
    const val DEFAULT_HTTPS_PORT = 443

    private val RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz"
    private val ISO8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    /**
     * host 字段 → scheme：显式 "https://"/"http://" 前缀优先，否则 http。
     * （SourceEntity 暂无独立 TLS 开关，用 host 前缀表达；后续加字段时迁移。）
     */
    fun schemeOf(hostField: String): String {
        val h = hostField.trim().lowercase(Locale.ROOT)
        return when {
            h.startsWith("https://") -> "https"
            h.startsWith("http://") -> "http"
            else -> "http"
        }
    }

    /** host 字段 → 纯主机名（去 scheme 前缀与尾部分隔符，大小写不敏感前缀匹配） */
    fun hostOf(hostField: String): String {
        val t = hostField.trim()
        val lowered = t.lowercase(Locale.ROOT)
        return when {
            lowered.startsWith("https://") -> t.substring("https://".length)
            lowered.startsWith("http://") -> t.substring("http://".length)
            else -> t
        }.trimEnd('/')
    }

    /** scheme 对应的默认端口 */
    fun defaultPort(scheme: String): Int =
        if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT

    /**
     * 拼 URL 路径：base（如 "/dav"）+ 统一路径（如 "/movies/a.mkv"）→ 段编码的绝对路径。
     * 目录传 [isDir]=true 追加尾 "/"（多数服务器以尾斜杠区分集合）。
     */
    fun encodedPath(base: String, path: String, isDir: Boolean): String {
        val segments = (splitSegments(base) + splitSegments(path)).filter { it.isNotEmpty() }
        if (segments.isEmpty()) return if (isDir) "/" else "/"
        val encoded = segments.joinToString("/") { encodeSegment(it) }
        val p = "/$encoded"
        return if (isDir && !p.endsWith("/")) "$p/" else p
    }

    /** 拆分路径为原始段（未编码；兼容 \ 与重复分隔符） */
    fun splitSegments(path: String): List<String> =
        path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }

    /** 单段路径编码：空格/中文/特殊字符 → %xx（"+" 必须编码为 %20） */
    fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /**
     * PROPFIND 响应 href 的最后一段 → 原始文件名：
     * 兼容绝对 URL/绝对路径/尾斜杠；%xx 解码保留字面 "+"。
     */
    fun fileNameFromHref(href: String): String {
        val noQuery = href.substringBefore('?').trimEnd('?')
        val stripped = noQuery.trimEnd('/')
        val segment = stripped.substringAfterLast('/')
        return decodePercent(segment)
    }

    /** href 是否指向集合（以 / 结尾，忽略查询串） */
    fun hrefIsCollection(href: String): Boolean = href.substringBefore('?').endsWith("/")

    /**
     * %xx 解码（UTF-8 多字节安全，用于中文文件名）：
     * 非法序列原样保留；"+" 保持字面加号（URLDecoder 会误转空格，故自实现）。
     */
    fun decodePercent(text: String): String {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream(text.length)
        val literal = ByteArrayOutputStream(text.length)
        var i = 0
        fun flushLiteral() {
            if (literal.size() > 0) {
                out.write(literal.toByteArray())
                literal.reset()
            }
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val v = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    // 先按 UTF-8 收集前面的字面字符，再写本字节（保证多字节序列不被截断误判）
                    flushLiteral()
                    out.write(v)
                    i += 3
                    continue
                }
            }
            val cs = c.toString().toByteArray(Charsets.UTF_8)
            literal.write(cs, 0, cs.size)
            i++
        }
        // 字面字符整体作为 UTF-8 字节流（% 序列之后的残余字面字符与中文同批写出）
        flushLiteral()
        val bytes = out.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * WebDAV 时间解析：getlastmodified 常见 RFC1123（"Tue, 01 Jan 2025 12:00:00 GMT"），
     * 部分服务器给 ISO8601；无法解析返回 0。
     */
    fun parseDavDate(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        rfc1123OrNull(text)?.let { return it }
        return try {
            val fmt = SimpleDateFormat(ISO8601, Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.isLenient = true
            fmt.parse(text)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun rfc1123OrNull(text: String): Long? = try {
        val fmt = SimpleDateFormat(RFC1123, Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        fmt.isLenient = true
        fmt.parse(text)?.time
    } catch (_: Exception) {
        null
    }

    /**
     * Depth:1 响应中剔除目录自身条目：href（可能绝对 URL/含 %xx）与
     * 请求路径（已编码形式 [requestPath]）归一化后逐段比较。
     */
    fun hrefMatchesPath(href: String, requestPath: String): Boolean {
        val h = href.substringBefore('?').trimEnd('/')
        val r = requestPath.trimEnd('/')
        if (h.equals(r, ignoreCase = true)) return true
        // href 可能是绝对 URL：只比 path 部分；也可能含未解码 %xx：解码后比较
        val hPath = if (h.startsWith("http://") || h.startsWith("https://")) {
            h.substringAfter("://").substringAfter('/')
        } else {
            h.trimStart('/')
        }
        val hSeg = hPath.split('/').map { decodePercent(it) }
        val rSeg = r.trimStart('/').split('/').map { decodePercent(it) }
        return hSeg == rSeg
    }

    /** 统一路径表示（NvPaths）下的父目录 */
    fun parentOf(path: String): String = NvPaths.parentOf(path)
}
