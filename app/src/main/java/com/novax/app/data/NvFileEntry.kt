package com.novax.app.data

/**
 * NAS / 本地文件条目：SmbClient、WebDavClient 与本地 File 共用的统一模型。
 *
 * 路径约定（见 [NvPaths]）：
 * - [path] 一律以 "/" 分隔、以 "/" 开头（相对源根目录），目录条目以 "/" 结尾。
 * - [lastModified] 为 epoch 毫秒；源不可提供时为 0。
 */
data class NvFileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
)

/**
 * NAS 路径工具（纯函数，可单测）。
 *
 * 统一内部路径表示："/" 分隔、"/" 开头（根为 "/" 或 ""，二者等价）、目录以 "/" 结尾。
 * 与 SMB（反斜杠、无前导分隔符）及 WebDAV（URL 段编码）之间的转换集中在这里。
 */
object NvPaths {

    /** 统一路径分隔符 */
    const val SEPARATOR = "/"

    /**
     * 拼接目录路径与子项名：join("/media/", "a.mkv") == "/media/a.mkv"。
     * dir 允许为 "" / "/" / 带或不带尾分隔符；name 不允许为空或含分隔符（原样返回 dir）。
     */
    fun join(dir: String, name: String): String {
        if (name.isEmpty() || name == "." || name == "..") return normalize(dir)
        val prefix = normalize(dir)
        return if (prefix.endsWith(SEPARATOR)) prefix + name else "$prefix$SEPARATOR$name"
    }

    /**
     * 规范化：反斜杠 → 正斜杠、去重复分隔符、段级折叠 "." 与 ".."、保证以 "/" 开头（空串视为根 "/"）。
     * ".." 弹出上一段；根之前多余的 ".." 丢弃（统一路径相对源根，库内路径来自服务器列表、
     * 从不合法含 ".."，折叠即消解外部 novax:// 载荷的穿越语义——审查 P2-4）。
     * 目录尾分隔符不做强制（由 [asDirectory] 处理）。
     */
    fun normalize(path: String): String {
        val collapsed = path.replace('\\', '/').replace(Regex("/{2,}"), "/")
        val segments = ArrayDeque<String>()
        for (seg in collapsed.split(SEPARATOR)) {
            when (seg) {
                "", "." -> {}                                            // 空段（首尾/连续分隔符）与当前段
                ".." -> if (segments.isNotEmpty()) segments.removeLast() // 已到根：多余的 ".." 丢弃
                else -> segments.addLast(seg)
            }
        }
        return if (segments.isEmpty()) SEPARATOR else SEPARATOR + segments.joinToString(SEPARATOR)
    }

    /** 规范化并保证目录尾分隔符（根为 "/"） */
    fun asDirectory(path: String): String {
        val n = normalize(path)
        return if (n.endsWith(SEPARATOR)) n else "$n$SEPARATOR"
    }

    /** 取最后一段文件/目录名：parentOf 与 fileNameOf 均兼容 \ 与 / */
    fun fileNameOf(path: String): String {
        val p = path.trimEnd('/')
        val idx = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
        return if (idx >= 0) p.substring(idx + 1) else p
    }

    /** 父目录（统一表示、目录尾分隔符）；根目录返回 "/" */
    fun parentOf(path: String): String {
        val p = normalize(path)
        if (p == SEPARATOR) return SEPARATOR
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) SEPARATOR else p.substring(0, idx)
    }

    /**
     * 统一路径 → SMB share 内部路径：去前导 "/"、"/" → "\"；根返回 ""。
     * list("") 与 openFile("a\\b.mkv") 均为 smbj 期望的形式。
     */
    fun toSmb(path: String): String {
        val n = normalize(path)
        return if (n == SEPARATOR) "" else n.trimStart('/').replace('/', '\\')
    }

    /** 判断 child 是否在 dir 之下（子文件或子目录） */
    fun isChildOf(dir: String, child: String): Boolean {
        val d = asDirectory(dir)
        val c = normalize(child)
        return c.startsWith(d) && c != d.trimEnd('/')
    }
}
