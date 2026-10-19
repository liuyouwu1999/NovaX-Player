package com.novax.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvVersionCompareTest {
    @Test fun `相等包括v前缀与段数补零`() {
        assertEquals(0, NvVersionCompare.compare("1.0.0.1", "v1.0.0.1"))
        assertEquals(0, NvVersionCompare.compare("1.0", "1.0.0"))
    }
    @Test fun `远端更高则可更新`() {
        assertTrue(NvVersionCompare.isUpdateAvailable("1.0.0.0", "v1.0.0.1"))
        assertTrue(NvVersionCompare.isUpdateAvailable("1.0.0.9", "v1.0.1.0"))
    }
    @Test fun `本地更新或相等不可更新`() {
        assertFalse(NvVersionCompare.isUpdateAvailable("1.0.0.1", "v1.0.0.0"))
        assertFalse(NvVersionCompare.isUpdateAvailable("2.0.0.0", "v1.0.0.0"))
        assertFalse(NvVersionCompare.isUpdateAvailable("1.0.0.1", "v1.0.0.1"))
    }
    @Test fun `非法远端一律不可更新`() {
        for (bad in listOf(null, "", "v", "abc", "1.0.0.1-beta", "1..2")) {
            assertFalse("bad=$bad", NvVersionCompare.isUpdateAvailable("1.0.0.0", bad))
        }
    }
    @Test fun `尾随空白宽容为合法版本`() {
        // 语义裁决：trim 是刻意的宽容（GitHub tag 不带尾随空白；宽容不会误弹，只会正常比较）
        assertTrue(NvVersionCompare.isUpdateAvailable("1.0.0.0", "v1.0.0.1 "))
    }
    @Test fun `本地非法视为无更新`() {
        assertFalse(NvVersionCompare.isUpdateAvailable("dev", "v9.9.9.9"))
    }
    @Test fun `数值比较非字典序`() {
        assertTrue(NvVersionCompare.compare("1.0.0.9", "1.0.0.10") < 0)
    }
}
