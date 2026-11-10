package com.novax.app.data.playback

import com.novax.app.data.scanner.ScanRules
import com.novax.app.ui.viewers.NvFileRouter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec B-8（审计 A6）：扩展名/路由域单一权威表——历史漂移形态钉死（BUG-C-R6-10 缺 rm 族、v1.0.0.5 缺 m4v）。 */
class MediaExtensionsTest {
    @Test fun `libVLC 抽帧子集是 Fallback 子集`() = assertTrue(MediaExtensions.LIBVLC_THUMB_EXTS.all { it in MediaExtensions.FALLBACK })
    @Test fun `rm 族与 avi 在 Fallback`() = assertTrue(listOf("rm", "rmvb", "avi", "wmv", "asf").all { it in MediaExtensions.FALLBACK })
    @Test fun `m4v 在 Video`() = assertTrue("m4v" in MediaExtensions.VIDEO)
    @Test fun `pathOrUrl 容错判定`() = assertTrue(MediaExtensions.isFallbackTarget("/a/x.wma ") && MediaExtensions.isFallbackTarget("http://h/b.AVI?q"))
    @Test fun `裸扩展名不是合法输入形态`() = assertFalse(MediaExtensions.isFallbackTarget("rmvb"))  // 现状语义钉死：无点无斜杠取不到扩展名（防后来者"顺手修成吃 ext"）
    @Test fun `三副本与权威表同一`() {
        // 委托形态下三者字面同一；若将来有人解开委托手抄一份，此测试立即可见
        assertTrue(MediaExtensions.VIDEO == ScanRules.VIDEO_EXTS && ScanRules.VIDEO_EXTS == NvFileRouter.VIDEO_EXTS)
        assertTrue(MediaExtensions.IMAGE == ScanRules.IMAGE_EXTS && ScanRules.IMAGE_EXTS == NvFileRouter.IMAGE_EXTS)
        assertTrue(MediaExtensions.AUDIO == NvFileRouter.AUDIO_EXTS)
    }
}
