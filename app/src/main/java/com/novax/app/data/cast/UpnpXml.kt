package com.novax.app.data.cast

/**
 * soap 函数缝的失败类型（seam 契约保留，2026-10-16 返工⑬）：网络/非 2xx/超时/设备 fault
 * 统一抛出。message 优先取 [userMessage] 可读中文文案（jUPnP 适配器 [UpnpControlSoapExecutor]
 * 填充，未知错误也有兜底，绝不把英文异常漏给 toast）；[errorCode] 为设备回的 UDA 错误码。
 */
class SoapException(
    val action: String,
    val httpCode: Int?,
    cause: Throwable?,
    val errorCode: Int? = null,
    val userMessage: String? = null,
) : Exception(userMessage ?: "$action failed (http=$httpCode)", cause)

object UpnpXml {
    /** XML 特殊字符转义（DIDL 构造 / DIDL 塞进 CurrentURIMetadata 的二次转义共用） */
    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** ms → H:MM:SS（H 不补零，电视惯例） */
    fun fmtTime(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /** H:MM:SS → ms；NOT_IMPLEMENTED/空/畸形一律 0（电视端返回值不可信） */
    fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val parts = raw.trim().split(':')
        if (parts.size != 3) return 0L
        val h = parts[0].toLongOrNull() ?: return 0L
        val m = parts[1].toLongOrNull() ?: return 0L
        val s = parts[2].toLongOrNull() ?: return 0L
        if (h < 0 || m < 0 || m >= 60 || s < 0 || s >= 60) return 0L
        return (h * 3600 + m * 60 + s) * 1000
    }

    /** 按本地名取首个匹配标签文本，忽略命名空间前缀与属性（电视端前缀/属性五花八门）；无则 null */
    fun tagValue(xml: String, localName: String): String? {
        val open = "<$localName"
        val close = "</$localName>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        // 跳过标签属性部分（<RelTime stamp="x">），定位开标签的 '>'
        val openEnd = xml.indexOf('>', start)
        if (openEnd < 0) return null
        if (xml.getOrNull(openEnd - 1) == '/') return ""   // 自闭合 <RelTime/>
        val end = xml.indexOf(close, openEnd)
        if (end < 0) return null
        return xml.substring(openEnd + 1, end)
    }
}
