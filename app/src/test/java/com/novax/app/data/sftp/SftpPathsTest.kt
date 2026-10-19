package com.novax.app.data.sftp

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.PathComponents
import net.schmizz.sshj.sftp.RemoteResourceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SFTP 纯函数单测（2026-09-29 SFTP 源落地配套）：
 * - [SftpPaths.remotePath]：源根 + 统一路径 → sshj 远程路径（绝对/家目录相对/"." 语义）；
 * - [SftpEntries.toEntry]：sshj RemoteResourceInfo → 统一 NvFileEntry（mtime 秒→毫秒、
 *   目录尾分隔符、目录 size=0）——用真实 sshj 类型构造（FileAttributes.Builder），
 *   锁住 isDirectory/size/mtime 取值口径。
 */
class SftpPathsTest {

    // ---------- remotePath ----------

    @Test
    fun rootConfigured_joinsAbsolute() {
        assertEquals("/home/nas/videos/movie.mkv", SftpPaths.remotePath("/home/nas/videos", "/movie.mkv"))
        assertEquals("/home/nas/videos", SftpPaths.remotePath("/home/nas/videos", "/"))
    }

    @Test
    fun rootConfigured_collapsesDoubleSlashesAndTrailingSlash() {
        assertEquals("/home/nas/videos/a/b.mkv", SftpPaths.remotePath("/home//nas/videos/", "/a/b.mkv"))
        assertEquals("/home/nas/videos/a/b.mkv", SftpPaths.remotePath("home/nas/videos", "a/b.mkv"))
    }

    @Test
    fun rootBlank_relativeToSessionHome() {
        // 未配根 = 会话 CWD（家目录）：子路径相对化（不带前导 "/"），根 = "."
        assertEquals("movies/a.mkv", SftpPaths.remotePath("", "/movies/a.mkv"))
        assertEquals(".", SftpPaths.remotePath("", "/"))
        assertEquals(".", SftpPaths.remotePath("", ""))
    }

    @Test
    fun rootSlash_atRoot_dot() {
        assertEquals(".", SftpPaths.remotePath("/", "/"))
        assertEquals("videos/a.mkv", SftpPaths.remotePath("/", "/videos/a.mkv"))
    }

    @Test
    fun backslashes_foldedToForwardSlashes() {
        assertEquals("/home/nas/a.mkv", SftpPaths.remotePath("\\home\\nas", "\\a.mkv"))
    }

    // ---------- toEntry（真实 sshj 类型） ----------

    private fun info(name: String, attrs: FileAttributes) =
        RemoteResourceInfo(PathComponents("/home/nas", name, "/"), attrs)

    @Test
    fun fileEntry_mapsNamePathSizeAndMtimeToMillis() {
        val e = SftpEntries.toEntry(
            "/movies",
            info("movie.mkv", FileAttributes.Builder().withSize(12345L).withAtimeMtime(1700000000L, 1700000001L).build()),
        )
        assertEquals("movie.mkv", e.name)
        assertEquals("/movies/movie.mkv", e.path)
        assertEquals(false, e.isDir)
        assertEquals(12345L, e.size)
        assertEquals(1700000001000L, e.lastModified)   // sshj mtime = epoch 秒 → *1000
    }

    @Test
    fun dirEntry_trailingSlash_zeroSize() {
        val e = SftpEntries.toEntry(
            "/",
            info("videos", FileAttributes.Builder().withSize(4096L).withType(FileMode.Type.DIRECTORY).build()),
        )
        assertEquals("videos", e.name)
        assertEquals("/videos/", e.path)     // 目录条目带尾分隔符
        assertEquals(true, e.isDir)
        assertEquals(0L, e.size)             // 目录 size 恒 0（对齐其他源口径）
    }

    @Test
    fun entry_withoutMtimeFlag_mapsToZero() {
        val e = SftpEntries.toEntry("/", info("a.mkv", FileAttributes.Builder().withSize(1L).build()))
        assertEquals(0L, e.lastModified)     // 源不可提供时 0（统一契约）
    }

    @Test
    fun pathJoin_withSubdirectory() {
        val e = SftpEntries.toEntry(
            "/movies/动画",
            info("第 01 集.mkv", FileAttributes.Builder().withSize(7L).build()),
        )
        assertEquals("/movies/动画/第 01 集.mkv", e.path)   // 非 ASCII/空格段原样（SFTP 路径不做 URL 编码）
    }
}
