package com.novax.app.ui.files

import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「打开所在文件夹」目标推导纯函数测试（2026-10-13，逻辑即 WatchHistoryViewModel 原实现口径） */
class NvFolderTargetTest {

    /** id=2 与 id=3 前缀重叠：钉死「firstOrNull 首个匹配」现状语义 */
    private val sources = listOf(
        SourceEntity(id = 1L, type = SourceType.SMB, nickname = "NAS", shareOrPath = "smb://nas/media"),
        SourceEntity(id = 2L, type = SourceType.LOCAL, nickname = "本机", shareOrPath = "/sdcard/Movies"),
        SourceEntity(id = 3L, type = SourceType.LOCAL, nickname = "下载", shareOrPath = "/sdcard/Movies/Downloads"),
    )

    @Test fun `有源直达_面包屑去文件名`() {
        val t = folderTargetOf(1L, "电影/合集/a.mkv", sources)!!
        assertEquals(1L, t.sourceId)
        assertEquals(listOf("电影", "合集"), t.breadcrumb)
    }

    @Test fun `有源_根目录文件_空面包屑`() {
        val t = folderTargetOf(1L, "a.mkv", sources)!!
        assertEquals(1L, t.sourceId)
        assertEquals(emptyList<String>(), t.breadcrumb)
    }

    @Test fun `本地直连绝对路径回配LOCAL源`() {
        val t = folderTargetOf(null, "/sdcard/Movies/合集/a.mkv", sources)!!
        assertEquals(2L, t.sourceId)
        assertEquals(listOf("合集"), t.breadcrumb)
    }

    @Test fun `多LOCAL源前缀重叠取首个匹配`() {
        val t = folderTargetOf(null, "/sdcard/Movies/Downloads/x.mp4", sources)!!
        assertEquals(2L, t.sourceId)
        assertEquals(listOf("Downloads"), t.breadcrumb)
    }

    @Test fun `绝对路径无匹配源返回null`() {
        assertNull(folderTargetOf(null, "/storage/emulated/0/Video/a.mkv", sources))
    }

    @Test fun `远程直链URL返回null`() {
        assertNull(folderTargetOf(null, "https://example.com/a.mkv", sources))
    }

    @Test fun `breadcrumb口径_重复斜杠与文件名`() {
        assertEquals(listOf("a", "b"), breadcrumbOf("a//b/c.mkv"))
        assertEquals(emptyList<String>(), breadcrumbOf("/a.mkv"))
    }
}
