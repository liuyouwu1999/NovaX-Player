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

    /** 确定性回退算法（spec §2.1，规则逐条可单测） */
    internal fun stripMarkdown(body: String): List<String> = body.lines().mapNotNull { raw ->
        var t = raw.trim()
        if (t.isEmpty() || t.startsWith("```") || t.startsWith("|")) return@mapNotNull null
        t = t.replace(Regex("^#{1,6}\\s*"), "")
        t = t.replace(Regex("^[-*+]\\s+"), "")
        t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        t = t.replace("`", "")
        t.trim().ifEmpty { null }
    }
}
