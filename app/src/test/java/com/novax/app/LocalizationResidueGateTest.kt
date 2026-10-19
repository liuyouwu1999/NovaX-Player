package com.novax.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 源码中文残留门禁（spec §1.5-1）：ui/ player/ data/ + 根包，注释行、Log.x 行、白名单豁免 */
class LocalizationResidueGateTest {

    private val root = File("src/main/java/com/novax/app")
    private val scopeDirs = setOf("ui", "player", "data")
    private val whitelist by lazy {
        File("src/test/resources/i18n-whitelist.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }
    private val cjkInString = Regex("\"[^\"]*[\\u4e00-\\u9fff][^\"]*\"")
    private val cjkChar = Regex("'[\\u4e00-\\u9fff]'")
    // 已知检测面局限：三引号 raw string 的跨行内容（行内无引号）不被本门禁覆盖——
    // 现库 raw string 无中文；如未来引入含中文的 raw string，须整文件入白名单并在注释声明。

    @Test
    fun `界面域源码无白名单外中文串`() {
        val violations = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val inScope = rel.contains('/') && rel.substringBefore('/').let { it in scopeDirs } ||
                !rel.contains('/')   // 根包文件
            if (!inScope) emptyList() else f.readLines().withIndex().filter { (_, line) ->
                val t = line.trimStart()
                !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && !t.startsWith("Log.")
                    && (cjkInString.containsMatchIn(line) || cjkChar.containsMatchIn(line))
                    && whitelist.none { w ->
                        (w.startsWith(rel) && !w.contains(" :: ") && w == rel) ||
                            (w.startsWith("$rel :: ") && line.contains(w.substringAfter(" :: ")))
                    }
            }.map { (i, line) -> "$rel:${i + 1}: ${line.trim().take(80)}" }
        }.toList()
        assertTrue("白名单外中文残留:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}
