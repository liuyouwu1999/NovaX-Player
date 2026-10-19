package com.novax.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-09 C:致谢清单与实际依赖一致;LGPL 合规声明句红线(spec) */
class LicensesScreenTest {
    @Test fun `条目字段完整`() {
        licenseEntries.forEach { e ->
            assertTrue(e.name.isNotBlank() && e.license.isNotBlank() && e.contribution.isNotBlank())
        }
    }
    @Test fun `sshj 已列入`() { assertTrue(licenseEntries.any { it.name.startsWith("sshj") }) }
    @Test fun `LGPL 条目保留合规声明句`() {
        licenseEntries.filter { it.license == "LGPL 2.1" }.forEach {
            assertTrue(it.name + " 缺合规句", it.contribution.contains("按 LGPL 2.1 要求完整提供协议文本与源码获取方式"))
        }
    }
    @Test fun `vendor 署名保留`() { assertTrue(licenseEntries.any { it.name.contains("Nova Video Player") }) }
    // 2026-10-16 同步：上游 daddcc2 致谢对账补 Haze 2.0.1 条目（真实依赖，液态玻璃渲染库），
    // 屏端实际展示 16 条而测试未同步——15→16。此前计数：13−Material Symbols(无字体实证)+sshj+BouncyCastle+eddsa(批次二评审补)
    @Test fun `数量与预期一致`() { assertEquals(16, licenseEntries.size) }
}
