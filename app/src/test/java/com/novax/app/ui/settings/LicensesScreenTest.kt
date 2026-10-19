package com.novax.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novax.app.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 2026-10-09 C:致谢清单与实际依赖一致;LGPL 合规声明句红线(spec)。
 *  i18n 后贡献文案走 strings_settings.xml 资源（contributionRes），红线改为对 values-zh-rCN 资源文本断言。 */
class LicensesScreenTest {
    @Test fun `条目字段完整`() {
        licenseEntries.forEach { e ->
            assertTrue(e.name.isNotBlank() && e.license.isNotBlank() && e.contributionRes != 0)
        }
    }
    @Test fun `sshj 已列入`() { assertTrue(licenseEntries.any { it.name.startsWith("sshj") }) }
    @Test fun `LGPL 条目保留合规声明句`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values-zh-rCN/strings_settings.xml"))
        val list = doc.getElementsByTagName("string")
        val zhStrings = (0 until list.length).associate {
            list.item(it).attributes.getNamedItem("name").nodeValue to list.item(it).textContent
        }
        // LGPL 2.1 条目（libVLC/FFmpeg/jcifs-ng）的资源键，与 licenseEntries 表一一对应
        val lgplKeys = mapOf(
            "libVLC" to R.string.settings_license_libvlc,
            "FFmpeg" to R.string.settings_license_ffmpeg,
            "jcifs-ng" to R.string.settings_license_jcifs,
        )
        val lgplEntries = licenseEntries.filter { it.license == "LGPL 2.1" }
        assertEquals(3, lgplEntries.size)
        // 屏显贡献确取对应资源（键位对齐防错挂）
        lgplEntries.forEach { e ->
            val expected = lgplKeys.entries.first { e.name.startsWith(it.key) }.value
            assertEquals(e.name + " 贡献键错挂", expected, e.contributionRes)
        }
        // 红线：zh 资源文本含合规声明句
        lgplKeys.forEach { (label, res) ->
            val text = zhStrings.getValue(resKeyOf(res))
            assertTrue("$label 缺合规句", text.contains("按 LGPL 2.1 要求完整提供协议文本与源码获取方式"))
        }
    }
    @Test fun `vendor 署名保留`() { assertTrue(licenseEntries.any { it.name.contains("Nova Video Player") }) }
    // 2026-10-16 同步：上游 daddcc2 致谢对账补 Haze 2.0.1 条目（真实依赖，液态玻璃渲染库），
    // 屏端实际展示 16 条而测试未同步——15→16。此前计数：13−Material Symbols(无字体实证)+sshj+BouncyCastle+eddsa(批次二评审补)
    @Test fun `数量与预期一致`() { assertEquals(16, licenseEntries.size) }

    /** R 常量 → 资源键名（单测无运行时资源表，按本域约定显式映射） */
    private fun resKeyOf(res: Int): String = when (res) {
        R.string.settings_license_libvlc -> "settings_license_libvlc"
        R.string.settings_license_ffmpeg -> "settings_license_ffmpeg"
        R.string.settings_license_jcifs -> "settings_license_jcifs"
        else -> error("未登记的资源常量 $res")
    }
}
