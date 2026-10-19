package com.novax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 13：NvAppLanguage 纯 JVM 可测部分（brief 实现要点 5）——resolveTag 全映射 +
 * 未知值安全侧 + OPTIONS 顺序契约 + 键名稳定。
 * LocaleManager 调用 / recreate / wrap 的 context 装配为 Android 薄壳，按 brief 留冒烟覆盖。
 */
class NvAppLanguageTest {

    @Test
    fun `resolveTag 五语全映射`() {
        assertEquals("zh-CN", NvAppLanguage.resolveTag("zh"))
        assertEquals("zh-TW", NvAppLanguage.resolveTag("zh-TW"))
        assertEquals("en", NvAppLanguage.resolveTag("en"))
        assertEquals("ko", NvAppLanguage.resolveTag("ko"))
        assertEquals("ja", NvAppLanguage.resolveTag("ja"))
    }

    @Test
    fun `auto与未知值安全侧回null跟随系统`() {
        assertNull(NvAppLanguage.resolveTag(NvAppLanguage.AUTO))
        assertNull(NvAppLanguage.resolveTag(""))
        assertNull(NvAppLanguage.resolveTag("fr"))
        // 大小写敏感：白名单外的值一律按 auto（安全侧）
        assertNull(NvAppLanguage.resolveTag("ZH"))
        assertNull(NvAppLanguage.resolveTag("zh-tw"))
        // 只收偏好代码，不收语言标签本身
        assertNull(NvAppLanguage.resolveTag("zh-CN"))
    }

    @Test
    fun `OPTIONS 顺序固定且全部可解析`() {
        assertEquals(listOf("auto", "zh", "zh-TW", "en", "ko", "ja"), NvAppLanguage.OPTIONS)
        // 映射完整性：将来 UI 新增语言而漏配 resolveTag 时在此爆
        NvAppLanguage.OPTIONS.filter { it != NvAppLanguage.AUTO }.forEach {
            assertNotNull(NvAppLanguage.resolveTag(it))
        }
    }

    @Test
    fun `DataStore 键名与默认值稳定`() {
        assertEquals("novax_app_language", NvAppLanguage.KEY)
        assertEquals("auto", NvAppLanguage.AUTO)
    }
}
