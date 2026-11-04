package com.novax.app.data.update

/** spec §2.1 契约 3：```changelog 围栏 → 去 Markdown 回退 → 空列表，三级降级 */
object NvChangelogExtractor {

    fun extract(body: String?, limit: Int = 12): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        fenceBlock(body)?.let { return it.take(limit) }
        return stripMarkdown(body).take(limit)
    }

    private fun fenceBlock(body: String): List<String>? {
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("```changelog")) {
                val out = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    out.add(lines[i].trim()); i++
                }
                return out.filter { it.isNotBlank() }
            }
            i++
        }
        return null
    }
    // 已知简化：普通围栏内嵌 ```changelog 行会误判为块——Release body 场景无嵌套围栏，接受。

    /**
     * 确定性回退算法（spec §2.1，规则逐条可单测）。
     * 2026-10-26 用户反馈「弹窗文字太乱」补三条规则：
     * - 标题行（#/##…）整行丢弃——弹窗自带标题，H1 重复、H2 是组织性噪音；
     * - `**加粗**` 星号剥离（此前原样透出）；
     * - 有序列表前缀（`1.`/`1、`/`1)`）剥离——弹窗统一重编号，防双重编号。
     */
    internal fun stripMarkdown(body: String): List<String> {
        // 2026-11-04：围栏内容整体跳过（含 ```sha256 校验和围栏——新发版约定，防哈希行被
        // 当 changelog 透出）；此前仅跳围栏标记行本身，围栏内普通文本行会漏出
        var inFence = false
        return body.lines().mapNotNull { raw ->
            var t = raw.trim()
            if (t.startsWith("```")) { inFence = !inFence; return@mapNotNull null }
            if (inFence || t.isEmpty() || t.startsWith("|")) return@mapNotNull null
            if (HEADING_RE.containsMatchIn(t)) return@mapNotNull null
        t = t.replace(Regex("^[-*+]\\s+"), "")
        // 有序前缀剥离（复审 P2-2 收口）：点号后须至少一空白（防行首裸小数 "1.5 倍速优化"
        // 被误剥成 "5 倍速优化"）；顿号/右括号按中文惯例允许零空白（"2、第二条"）
        t = t.replace(Regex("^\\d{1,2}\\.\\s+"), "")
        t = t.replace(Regex("^\\d{1,2}[、)]\\s*"), "")
        t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        t = t.replace("`", "")
        t = t.replace("**", "")
            t.trim().ifEmpty { null }
        }
    }

    /** 行首标题标记（1-6 级）：`#` 后必须跟空白，避免误杀含 # 的正文 */
    private val HEADING_RE = Regex("^#{1,6}\\s")
}
