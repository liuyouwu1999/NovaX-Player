package com.novax.app.data.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SMB 未选共享源的虚拟根目录约定（纯函数）：entryPath / shareNameOf 往返一致 */
class SmbShareRootTest {

    @Test fun resolve_virtualIndexedFile_preservesChineseAndSpaces() {
        val target = SmbShareRoot.resolve("", "/@share/media/下载/我的 视频.mp4")
        assertEquals("media", target.share)
        assertEquals("/下载/我的 视频.mp4", target.path)
    }

    @Test fun resolve_namedShare_doesNotInterpretRealAtShareDirectory() {
        val target = SmbShareRoot.resolve("media", "/@share/archive/a.mp4")
        assertEquals("media", target.share)
        assertEquals("/@share/archive/a.mp4", target.path)
    }

    @Test(expected = java.io.IOException::class)
    fun resolve_missingShare_doesNotGuess() {
        SmbShareRoot.resolve("", "/下载/a.mp4")
    }

    @Test fun listingToRead_roundTripAcrossSharesAndNestedDirectories() {
        for (share in listOf("media", "media2", "私人 视频")) {
            val directory = SmbShareRoot.virtualPath(share, "/目录/", true)
            assertEquals("/@share/$share/目录/", directory)
            val file = SmbShareRoot.virtualPath(share, "/目录/同名.mp4", false)
            val target = SmbShareRoot.resolve("", file)
            assertEquals(share, target.share)
            assertEquals("/目录/同名.mp4", target.path)
        }
    }

    @Test
    fun entryPath_isVirtualDirectoryEntry() {
        assertEquals("/@share/media/", SmbShareRoot.entryPath("media"))
        assertEquals("/@share/video/", SmbShareRoot.entryPath("video"))
    }

    @Test
    fun shareNameOf_roundTrip() {
        assertEquals("media", SmbShareRoot.shareNameOf(SmbShareRoot.entryPath("media")))
    }

    @Test
    fun shareNameOf_acceptsTrailingSlash_andNestedSubPath() {
        assertEquals("media", SmbShareRoot.shareNameOf("/@share/media"))
        assertEquals("media", SmbShareRoot.shareNameOf("/@share/media/"))
        assertEquals("media", SmbShareRoot.shareNameOf("/@share/media/电影/2024"))
    }

    @Test
    fun shareNameOf_nonVirtualPaths_returnNull() {
        assertNull(SmbShareRoot.shareNameOf("/"))
        assertNull(SmbShareRoot.shareNameOf(""))
        assertNull(SmbShareRoot.shareNameOf("/movies"))
        assertNull(SmbShareRoot.shareNameOf("/@share"))
        assertNull(SmbShareRoot.shareNameOf("/@share/"))
    }

    @Test
    fun shareNameOf_fullSegmentMatch_only() {
        // 共享名取第一段：/media2 不得被 /media 前缀误匹配
        assertEquals("media2", SmbShareRoot.shareNameOf("/@share/media2"))
        assertEquals("media2", SmbShareRoot.shareNameOf("/@share/media2/sub"))
    }
}
