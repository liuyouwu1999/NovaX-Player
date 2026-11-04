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
        // 2026-10-26：标题行（#/##）整行丢弃——弹窗自带标题，H1 重复 H2 是组织噪音
        val body = "# 标题\n- 第一条\n|表格|行|\n[链接文本](https://x.y)\n`代码`\n\n尾行"
        val out = NvChangelogExtractor.extract(body, limit = 12)
        assertEquals(listOf("第一条", "链接文本", "代码", "尾行"), out)
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
    @Test fun `普通围栏内容整块跳过`() {
        // 2026-11-04 语义升级：围栏内内容整体跳过（防 ```sha256 校验和围栏哈希行透出），
        // 此前仅跳围栏标记行、内容 z 会漏出——行为变更非放宽
        assertEquals(emptyList<String>(), NvChangelogExtractor.extract("# h\n```\nz\n```"))
    }
    @Test fun `无changelog围栏时sha256围栏内容也不透出`() {
        // 2026-11-04 防御：正文出现 ```sha256 校验和围栏（新发版约定）而 pipeline 漏写
        // ```changelog 时，回退路径不得把哈希行当 changelog 透出
        val body = "## 修复\n```sha256\n" +
            "974f2ad6e722a51ab8781257d15a61a7c3546940e6876738197df3543493c333 *NovaX-1.0.0.20-arm64-v8a.apk\n```"
        assertTrue(NvChangelogExtractor.extract(body).isEmpty())
    }

    // ---------- 2026-10-26「弹窗文字太乱」新规则 ----------

    @Test fun `各级标题行全部丢弃`() {
        val body = "# NovaX v1.0.0.7（新功能）\n正文引言\n## 修复与优化\n- 条目一\n### 三级标题也不出\n- 条目二"
        assertEquals(
            listOf("正文引言", "条目一", "条目二"),
            NvChangelogExtractor.extract(body),
        )
    }

    @Test fun `加粗星号剥离`() {
        val body = "- **修复：横幅可关闭**——点 × 即可\n普通 **加粗** 行"
        assertEquals(
            listOf("修复：横幅可关闭——点 × 即可", "普通 加粗 行"),
            NvChangelogExtractor.extract(body),
        )
    }

    @Test fun `有序列表前缀剥离防双重编号`() {
        val body = "1. 第一条\n2、中文顿号零空白\n3) 括号\n12. 上限两位"
        assertEquals(
            listOf("第一条", "中文顿号零空白", "括号", "上限两位"),
            NvChangelogExtractor.extract(body),
        )
    }

    @Test fun `行首裸小数不误剥`() {
        // 复审 P2-2：点号后无空白 = 小数不是序号，原样保留
        assertEquals(
            listOf("1.5 倍速优化", "3.14 是圆周率"),
            NvChangelogExtractor.extract("1.5 倍速优化\n3.14 是圆周率"),
        )
    }

    @Test fun `真实release形态回归样例`() {
        val body = """
            # NovaX v1.0.0.7（新功能：仅作文件源）

            ## 新功能

            - **添加/编辑网络存储源时，可以选择「仅作文件源（不进媒体库）」**——此前必须二选一
            - 把已入库的源改为"仅文件源"时会清掉已入库条目

            ## 说明

            - 从 v1.0.0.x 任一版本直接覆盖安装即可
            - SHA256SUMS.txt 用于安装包完整性校验

            **安装包下载**：按设备 CPU 选择——现代手机选 `arm64-v8a`。
        """.trimIndent()
        assertEquals(
            listOf(
                "添加/编辑网络存储源时，可以选择「仅作文件源（不进媒体库）」——此前必须二选一",
                "把已入库的源改为\"仅文件源\"时会清掉已入库条目",
                "从 v1.0.0.x 任一版本直接覆盖安装即可",
                "SHA256SUMS.txt 用于安装包完整性校验",
                "安装包下载：按设备 CPU 选择——现代手机选 arm64-v8a。",
            ),
            NvChangelogExtractor.extract(body),
        )
    }

    @Test fun `井号后无空白不误判标题`() {
        assertEquals(
            listOf("#hashtag 不是标题"),
            NvChangelogExtractor.extract("#hashtag 不是标题"),
        )
    }
}
