package com.novax.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 四语键位齐平 + 占位符 arity 一致（spec §1.5-2）；string 与 plurals 都查；新增域文件无需改本测试 */
class LocalizationKeyParityTest {

    private val resDir = File("src/main/res")
    private val dirs = listOf("values", "values-zh-rCN", "values-ko", "values-ja")
    private val argRegex = Regex("%(\\d+\\$)?[sdf]")

    private fun doc(dir: String, file: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "$dir/$file"))

    private fun names(dir: String, tag: String): Map<String, Set<String>> {
        val d = File(resDir, dir)
        check(d.isDirectory) { "缺少资源目录 $dir" }
        val files = d.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!
        return files.associate { f ->
            val list = doc(dir, f.name).getElementsByTagName(tag)
            f.name to (0 until list.length).mapTo(mutableSetOf()) {
                list.item(it).attributes.getNamedItem("name").nodeValue
            }
        }
    }

    private fun args(dir: String, tag: String, file: String, key: String): Set<String> {
        val list = doc(dir, file).getElementsByTagName(tag)
        for (i in 0 until list.length) {
            val el = list.item(i)
            if (el.attributes.getNamedItem("name").nodeValue == key)
                return argRegex.findAll(el.textContent).map { it.value.substringAfter('$') }.toSet()
        }
        error("$tag 键 $key 不存在于 $dir/$file")
    }

    @Test
    fun `四目录string与plurals键集合及占位符完全一致`() {
        for (tag in listOf("string", "plurals")) {
            val base = names("values", tag).toSortedMap()
            for (dir in dirs.drop(1)) {
                val other = names(dir, tag).toSortedMap()
                assertEquals("$dir $tag 文件集不一致", base.keys, other.keys)
                for ((file, keys) in base) {
                    assertEquals("$dir/$file $tag 键集不一致", keys, other.getValue(file))
                    for (key in keys) assertEquals(
                        "$dir/$file $tag 键 $key 占位符不一致",
                        args("values", tag, file, key), args(dir, tag, file, key),
                    )
                }
            }
        }
    }
}
