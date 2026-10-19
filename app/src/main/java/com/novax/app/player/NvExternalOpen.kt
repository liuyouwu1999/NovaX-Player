package com.novax.app.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/**
 * 外部「打开方式」入口（2026-09-28）：文件管理器/相册以 ACTION_VIEW + content/file URI
 * 拉起 PlayerActivity，本对象集中该入口的标题解析与显示名查询：
 * - initialTitle：纯 JVM 函数，URI 字符串 → 立即可显示的临时标题（真实显示名随后异步补全）；
 * - queryDisplayName：ContentResolver 查 OpenableColumns.DISPLAY_NAME（IO 线程调用）。
 * 为什么标题要两段式：媒体库 URI 末段常是数字 id（…/video/media/42），只有 DISPLAY_NAME
 * 才是用户认识的文件名；RMVB 兜底路由的扩展名判定也依赖真实文件名（Task：PlayerActivity
 * 外部分支在起播前用它补全）。临时标题只兜底查询失败的场景，不阻塞起播。
 */
object NvExternalOpen {

    /** URI 字符串 → 临时标题：去查询串 + 百分号解码 + 取末段；空回退占位（纯 JVM，可单测） */
    fun initialTitle(uriString: String): String {
        val noQuery = uriString.substringBefore('?')
        val decoded = percentDecode(noQuery)
        val name = decoded.substringAfterLast('/')
        return name.ifBlank { "未知文件" }
    }

    /**
     * 百分号解码，只认 %XX：路径段里的 '+' 是字面加号（URI 路径语义），不按表单空格转义——
     * 所以不用 java.net.URLDecoder（它会把 + 变空格，"a+b.mp4" 的标题就错了）。
     * 先解码再取末段：下载提供器把真实路径整体编码进一个段（raw%2F…%2Fmovie.rmvb），
     * 解码后才能露出真正的文件名。
     */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            val hex = if (b == 0x25 && i + 2 < bytes.size) String(bytes, i + 1, 2) else null
            val v = hex?.toIntOrNull(16)
            if (v != null) {
                out.write(v)
                i += 3
            } else {
                out.write(b)
                i += 1
            }
        }
        return out.toString("UTF-8")
    }

    /** 查显示名（OpenableColumns.DISPLAY_NAME）；无行/无列/SecurityException 一律 null */
    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
