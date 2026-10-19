package com.novax.app.ui.viewers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ViewerSiblingsHandoff 单测（2026-10-30 大图库闪退修复）：
 * 文件页点图把同目录全部图片 uri 塞 Intent extras，超 Binder 1MB 事务上限时
 * TransactionTooLargeException 直接闪退（vivo 实测 + 模拟器 6000 张复现）。
 * 兜底：超阈值落盘 cache 文件、Intent 只带路径、查看器读回。
 */
class ViewerSiblingsHandoffTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun novaxUri(i: Int): String =
        "novax://2/imgcrash/%E5%BE%AE%E4%BF%A1%E5%9B%BE%E7%89%87_202001011234" + i.toString().padStart(2, '0') + ".jpg"

    @Test
    fun `小列表走原 extras 不落盘`() {
        val uris = (0 until 100).map { novaxUri(it) }
        assertFalse(ViewerSiblingsHandoff.shouldOffload(uris))
    }

    @Test
    fun `大列表估算体积超过安全预算`() {
        val uris = (0 until 6000).map { novaxUri(it) }
        assertTrue(ViewerSiblingsHandoff.shouldOffload(uris))
        assertTrue(ViewerSiblingsHandoff.estimatedBundleBytes(uris) > ViewerSiblingsHandoff.MAX_EXTRAS_BYTES)
    }

    @Test
    fun `往返写读保持顺序与内容`() {
        val uris = listOf(
            "file:///storage/emulated/0/Download/微信图片_2019080316400726.jpg",
            "novax://3/a/b/%E5%BE%AE%E4%BF%A1%E5%9B%BE%E7%89%87.jpg",
            novaxUri(5999),
        )
        val f = ViewerSiblingsHandoff.writeTo(tmp.newFolder(), uris)
        assertNotNull(f)
        assertEquals(uris, ViewerSiblingsHandoff.read(f!!))
    }

    @Test
    fun `六千条真实规模列表落盘后完整读回`() {
        val uris = (0 until 6000).map { novaxUri(it) }
        val f = ViewerSiblingsHandoff.writeTo(tmp.newFolder(), uris)!!
        // 文件在 cache,不经 Binder,体积不受 extras 预算约束;Intent 里只剩一个路径字符串
        assertTrue(f.length() > 0)
        val back = ViewerSiblingsHandoff.read(f)!!
        assertEquals(6000, back.size)
        assertEquals(uris, back)
    }

    @Test
    fun `损坏与缺失文件读回为 null`() {
        assertNull(ViewerSiblingsHandoff.read(File(tmp.newFolder(), "missing.bin")))
        val broken = tmp.newFile("broken.bin")
        broken.writeBytes(byteArrayOf(0x7F, 0x00, 0x00, 0x01, 0x02))
        assertNull(ViewerSiblingsHandoff.read(broken))
    }

    @Test
    fun `条数为负视为损坏`() {
        val f = tmp.newFile("neg.bin")
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(f))).use {
            it.writeInt(-5)
        }
        assertNull(ViewerSiblingsHandoff.read(f))
    }
}
