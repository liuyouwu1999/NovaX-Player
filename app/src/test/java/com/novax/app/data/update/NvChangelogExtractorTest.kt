package com.novax.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NvChangelogExtractorTest {
    @Test fun `changelog围栏优先`() {
        val body = "# t\n```changelog\n新增多语言\n新增检查更新\n```\n正文应被忽略"
        assertEquals(listOf("新增多语言", "新增检查更新"), NvChangelogExtractor.extract(body))
    }
    @Test fun `围栏缺失回退去Markdown前12行`() {
        val body = "# 标题\n- 第一条\n|表格|行|\n[链接文本](https://x.y)\n`代码`\n\n尾行"
        val out = NvChangelogExtractor.extract(body, limit = 12)
        assertEquals(listOf("标题", "第一条", "链接文本", "代码", "尾行"), out)
    }
    @Test fun `超限截断`() {
        val body = (1..20).joinToString("\n") { "L$it" }
        assertEquals(12, NvChangelogExtractor.extract(body).size)
        assertEquals("L12", NvChangelogExtractor.extract(body).last())
    }
    @Test fun `null与空与围栏内空行`() {
        assertTrue(NvChangelogExtractor.extract(null).isEmpty())
        assertTrue(NvChangelogExtractor.extract("   ").isEmpty())
        assertTrue(NvChangelogExtractor.extract("```changelog\n\n```").isEmpty())
    }
    @Test fun `普通围栏不是changelog块`() {
        assertEquals(listOf("h", "z"), NvChangelogExtractor.extract("# h\n```\nz\n```"))
    }
}
