package com.novax.app.data.scanner

import com.novax.app.data.NvPaths

/**
 * sources.scanRoots 列（TEXT，JSON 字符串数组）的编解码 + 扫描根去重。
 *
 * 语义：
 * - null / 空数组 / 解析失败 → 空列表 = 扫描器回退现状（共享留空 SMB 先枚举共享，其余扫根 "/"）；
 * - 非空 → MediaScanner 逐根 BFS（上限/去重规则沿用单根遍历）；
 * - [DISABLED]（字面 "[]"，2026-10-26「仅作文件源」）= 显式空扫描集：此源永不扫描不进媒体库
 *   （仅文件页浏览）。与 null 的区别刻意用原始字面判定而非 parse 结果——parse("[]") 也回
 *   空列表，但 null/损坏输入是"回退扫根"的兼容语义，不能误判成禁用（fail-open 扫，不误禁）。
 *
 * 刻意不引 JSON 库：路径字符集有限，转义只需 `\` `"` 与控制字符，
 * 纯 Kotlin 实现可在 JVM 单测全覆盖（编解码往返 / 损坏输入容错 / 嵌套根去重）。
 */
object ScanRoots {

    /** 「仅作文件源」禁用标记：列存此字面值 = 永不扫描（唯一合法写者 = 向导 filesOnly 保存） */
    const val DISABLED: String = "[]"

    /** 是否禁用扫描（仅作文件源）：仅认 DISABLED 字面（trim 容差），null/损坏输入恒 false */
    fun isDisabled(raw: String?): Boolean = raw?.trim() == DISABLED

    /** 编码为 JSON 字符串数组 TEXT；空白输入返回 null（= 列存 null，扫根目录） */
    fun encode(roots: Collection<String>): String? {
        val cleaned = roots.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        return cleaned.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
            transform = { "\"" + escape(it) + "\"" },
        )
    }

    /** 解析 JSON 字符串数组；null / 空串 / 格式损坏 → 空列表（回退扫根目录，绝不抛异常） */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        val n = raw.length
        fun skipWs() {
            while (i < n && raw[i].isWhitespace()) i++
        }
        skipWs()
        if (i >= n || raw[i] != '[') return emptyList()
        i++
        skipWs()
        if (i < n && raw[i] == ']') return emptyList()
        while (i < n) {
            skipWs()
            if (i >= n || raw[i] != '"') return emptyList()
            i++
            val sb = StringBuilder()
            var closed = false
            while (i < n) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < n -> {
                        when (val esc = raw[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= n) return emptyList()
                                val hex = raw.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: return emptyList()
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return emptyList()
                        }
                        i += 2
                    }
                    c == '"' -> {
                        i++
                        closed = true
                        break
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            if (!closed) return emptyList()
            out += sb.toString()
            skipWs()
            if (i < n && raw[i] == ',') {
                i++
                continue
            }
            if (i < n && raw[i] == ']') {
                i++
                skipWs()
                return if (i >= n) out else emptyList()
            }
            return emptyList()
        }
        return emptyList()
    }

    /**
     * 扫描根去重：规范化（NvPaths）→ 去空 → 去重 → 剔除被更外层根包含的嵌套根
     * （多根逐一 BFS 下嵌套根会把同一文件索引/对账两次；保序取最外层）。
     * 根 "/" 覆盖一切：集合中出现 "/" 时只保留 "/"。
     */
    fun dedup(roots: List<String>): List<String> {
        val cleaned = linkedSetOf<String>()
        for (r in roots) {
            val t = r.trim()
            if (t.isEmpty()) continue
            cleaned += NvPaths.normalize(t)
        }
        val ordered = cleaned.toList()
        return ordered.filter { r ->
            val dir = NvPaths.asDirectory(r)
            ordered.none { other ->
                other != r && NvPaths.asDirectory(other).let { prefix ->
                    dir == prefix || dir.startsWith(prefix) ||
                        (other == NvPaths.SEPARATOR)
                }
            }
        }
    }

    /** JSON 字符串转义：`\` `"` 与 <0x20 控制字符（\uXXXX） */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
