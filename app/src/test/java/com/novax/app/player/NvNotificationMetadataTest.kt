package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NvNotificationMetadataTest {

    // ---- subtitle:副文字三态(spec §4.1;Task 7 i18n 后 queueLabel 由调用方按应用语言资源预格式化传入)----

    @Test
    fun subtitle_queueShowsPosition() {
        assertEquals("第3集 · 共12集", NvNotificationMetadata.subtitle(2, 12, "第3集 · 共12集", "SMB 影视"))
    }

    @Test
    fun subtitle_negativeIndexFallsBackToNickname() {
        assertEquals("SMB 影视", NvNotificationMetadata.subtitle(-1, 12, "第0集 · 共12集", "SMB 影视"))
    }

    @Test
    fun subtitle_singleFileShowsNickname() {
        assertEquals("WebDAV", NvNotificationMetadata.subtitle(0, 1, "第1集 · 共1集", "WebDAV"))
        assertEquals("WebDAV", NvNotificationMetadata.subtitle(5, 0, "第6集 · 共0集", "WebDAV"))
    }

    @Test
    fun subtitle_blankOrMissingNicknameReturnsNull() {
        assertNull(NvNotificationMetadata.subtitle(0, 1, "第1集 · 共1集", ""))
        assertNull(NvNotificationMetadata.subtitle(0, 1, "第1集 · 共1集", "  "))
        assertNull(NvNotificationMetadata.subtitle(0, 1, "第1集 · 共1集", null))
        assertNull(NvNotificationMetadata.subtitle(0, 0, "第1集 · 共0集", null))
    }

    // ---- displayTitle:标题兜底(spec Review Focus 4;Task 7 i18n 后兜底词由调用方按应用语言资源传入)----

    @Test
    fun displayTitle_keepsNonBlankTitle() {
        assertEquals("毛骗 S01E01", NvNotificationMetadata.displayTitle("毛骗 S01E01", "novax://x/y.mp4", "视频"))
    }

    @Test
    fun displayTitle_fallsBackToDecodedLastSegment() {
        assertEquals("第1集.mp4", NvNotificationMetadata.displayTitle(null, "novax:///tv/%E7%AC%AC1%E9%9B%86.mp4", "视频"))
        assertEquals("a b.mkv", NvNotificationMetadata.displayTitle("", "smb://share/a%20b.mkv", "视频"))
        assertEquals("末段.mkv", NvNotificationMetadata.displayTitle(" ", "/dir/%E6%9C%AB%E6%AE%B5.mkv", "视频"))
    }

    @Test
    fun displayTitle_allEmptyFallsBackToGeneric() {
        assertEquals("视频", NvNotificationMetadata.displayTitle(null, null, "视频"))
        assertEquals("视频", NvNotificationMetadata.displayTitle(" ", "/", "视频"))
    }

    // ---- build:组装与折叠 ----

    @Test
    fun build_collapsesBlankSubtitleAndAppliesTitleFallback() {
        val md = NvNotificationMetadata.build(null, "/x/%E7%AC%AC1%E9%9B%86.mp4", "  ", byteArrayOf(1), "视频")
        assertEquals("第1集.mp4", md.title)
        assertNull(md.subtitle)
        assertEquals(1, md.artworkData?.size)
    }

    @Test
    fun build_keepsNonBlankSubtitle() {
        val md = NvNotificationMetadata.build("T", "/t.mp4", "NAS", null, "视频")
        assertEquals("NAS", md.subtitle)
        assertNull(md.artworkData)
    }
}
