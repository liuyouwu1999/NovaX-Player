package com.novax.app.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.novax.app.data.playback.NvUri
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

    /**
     * URI 字符串 → 临时标题：去查询串 + 百分号解码 + 取末段；空回退占位
     * （纯 JVM，可单测）。i18n Task 9：兜底名由调用方传入资源值（本函数不再
     * 内嵌任何语言文案）。
     */
    fun initialTitle(uriString: String, fallbackTitle: String): String {
        val noQuery = uriString.substringBefore('?')
        val decoded = percentDecode(noQuery)
        val name = decoded.substringAfterLast('/')
        return name.ifBlank { fallbackTitle }
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

    /**
     * 外部 ACTION_VIEW 放行 scheme 白名单（2026-11-02 机构整改 spec §6.2-4，机构 2.4 验收标准
     * 「外部 URI scheme/授权/路由边界明确，非法输入被拒绝」）。
     *
     * 判别用 `Uri.parse(url).scheme?.lowercase()`（机构 2.4 原话：不得只用字符串 startsWith——
     * startsWith 挡不住 "contentXX://" 之类的伪造前缀，也不规范处理大小写）。
     * - 放行 content|file|http|https：外部「打开方式」现状能播的全部入口，零行为变化；
     * - ftp 有意收窄不放行：两播放引擎实际均不支持 ftp 播放（NvMeteredGuard 计 ftp 入远程仅是
     *   保守计量口径，见其 TODO 注释），现状传 ftp 本就播不了——此改只是把「莫名播放失败」
     *   变「明确拒绝」；
     * - novax:// 内部命令入口与外部打开入口天然分离（NvUri.parse 需 DB 真实 sourceId），外部
     *   VIEW 传 novax:// 在此一并明确拒绝；
     * - 无 scheme（裸本地路径）放行：引擎 File 分支既有支持，不在「其余 scheme」字面范围内；
     * - 空值拒绝（调用方外部 data 非空的分支才进本函数，空值属防御）。
     */
    val ALLOWED_EXTERNAL_SCHEMES = setOf("content", "file", "http", "https")

    fun isAllowedExternalUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val scheme = Uri.parse(uriString).scheme?.lowercase() ?: return true
        return scheme in ALLOWED_EXTERNAL_SCHEMES
    }

    /**
     * novax:// 内部命令判别（三期 spec §4-4.3 双口径收口）。
     * 与 [isAllowedExternalUri] 是两个语义、不可互替：
     * - 本函数（内部命令）：**大小写敏感严格前缀**——内部命令 URL 全由应用自身构造（恒小写），
     *   严格匹配防 lookalike scheme（NOVAX:// 等）混入内部路由；误判方向=漏判（fail-closed，
     *   漏判落入外部白名单被拒），机构 2.4「不用 startsWith」针对的是外部白名单（已由
     *   isAllowedExternalUri 的 Uri.parse+lowercase 解决），内部入口保持严格口径。
     * - isAllowedExternalUri（外部放行）：Uri.parse().scheme 小写判别，宽容外部输入大小写。
     */
    fun isNovaxCommandUri(uriString: String?): Boolean =
        uriString != null && uriString.startsWith(NvUri.SCHEME + "://")
}
