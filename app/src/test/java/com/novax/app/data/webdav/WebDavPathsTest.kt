package com.novax.app.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebDAV URL/路径/时间纯函数单测 */
class WebDavPathsTest {

    // ---------- scheme / host / port ----------

    @Test
    fun schemeOf_prefixWins() {
        assertEquals("https", WebDavPaths.schemeOf("https://dav.example.com"))
        assertEquals("http", WebDavPaths.schemeOf("HTTP://dav.example.com"))
        assertEquals("http", WebDavPaths.schemeOf("dav.example.com"))
        assertEquals("http", WebDavPaths.schemeOf(" 192.168.1.10 "))
    }

    @Test
    fun hostOf_stripsScheme() {
        assertEquals("dav.example.com", WebDavPaths.hostOf("https://dav.example.com"))
        assertEquals("192.168.1.10", WebDavPaths.hostOf("http://192.168.1.10"))
        assertEquals("nas.local", WebDavPaths.hostOf("nas.local/"))
    }

    @Test
    fun defaultPort_byScheme() {
        assertEquals(443, WebDavPaths.defaultPort("https"))
        assertEquals(80, WebDavPaths.defaultPort("http"))
    }

    // ---------- encodedPath ----------

    @Test
    fun encodedPath_joinsBaseAndPath() {
        assertEquals("/dav/movies/a.mkv", WebDavPaths.encodedPath("/dav", "/movies/a.mkv", isDir = false))
        assertEquals("/dav/movies/", WebDavPaths.encodedPath("/dav/", "movies", isDir = true))
        assertEquals("/dav/", WebDavPaths.encodedPath("/dav", "", isDir = true))
        assertEquals("/", WebDavPaths.encodedPath("", "/", isDir = true))
    }

    @Test
    fun encodedPath_encodesSegments() {
        assertEquals(
            "/dav/my%20movies/%E4%B8%AD%E6%96%87%20a.mkv",
            WebDavPaths.encodedPath("/dav", "/my movies/中文 a.mkv", isDir = false),
        )
    }

    @Test
    fun encodedPath_encodesPlusAsPercent20() {
        assertEquals("/dav/a%2Bb", WebDavPaths.encodedPath("/dav", "/a+b", isDir = false))
    }

    // ---------- href 解析 ----------

    @Test
    fun fileNameFromHref_forms() {
        assertEquals("a.mkv", WebDavPaths.fileNameFromHref("/dav/movies/a.mkv"))
        assertEquals("a.mkv", WebDavPaths.fileNameFromHref("/dav/movies/a.mkv/"))       // 集合式尾斜杠
        assertEquals("a.mkv", WebDavPaths.fileNameFromHref("http://nas:5005/dav/movies/a.mkv"))
        assertEquals("my movie.mkv", WebDavPaths.fileNameFromHref("/dav/my%20movie.mkv"))
        assertEquals("中文名.mkv", WebDavPaths.fileNameFromHref("/dav/%E4%B8%AD%E6%96%87%E5%90%8D.mkv"))
        assertEquals("a+b.mkv", WebDavPaths.fileNameFromHref("/dav/a+b.mkv"))           // "+" 不转空格
        assertEquals("a.mkv", WebDavPaths.fileNameFromHref("/dav/a.mkv?op=READ"))
    }

    @Test
    fun hrefIsCollection() {
        assertTrue(WebDavPaths.hrefIsCollection("/dav/movies/"))
        assertFalse(WebDavPaths.hrefIsCollection("/dav/a.mkv"))
    }

    // ---------- decodePercent ----------

    @Test
    fun decodePercent_multibyteSafe() {
        assertEquals("中文 a", WebDavPaths.decodePercent("%E4%B8%AD%E6%96%87%20a"))
        assertEquals("a+b", WebDavPaths.decodePercent("a+b"))
        assertEquals("100 done", WebDavPaths.decodePercent("100%20done"))
        assertEquals("bad%zz", WebDavPaths.decodePercent("bad%zz"))  // 非法序列原样保留
        assertEquals("plain", WebDavPaths.decodePercent("plain"))
    }

    // ---------- 时间解析 ----------

    @Test
    fun parseDavDate_rfc1123() {
        // GMT 2024-01-01T00:00:00Z
        assertEquals(1_704_067_200_000L, WebDavPaths.parseDavDate("Mon, 01 Jan 2024 00:00:00 GMT"))
    }

    @Test
    fun parseDavDate_iso8601Fallback() {
        assertEquals(1_704_067_200_000L, WebDavPaths.parseDavDate("2024-01-01T00:00:00Z"))
    }

    @Test
    fun parseDavDate_garbageIsZero() {
        assertEquals(0L, WebDavPaths.parseDavDate(""))
        assertEquals(0L, WebDavPaths.parseDavDate("not-a-date"))
    }

    // ---------- Depth:1 自身条目剔除 ----------

    @Test
    fun hrefMatchesPath() {
        assertTrue(WebDavPaths.hrefMatchesPath("/dav/movies/", "/dav/movies/"))
        assertTrue(WebDavPaths.hrefMatchesPath("/dav/movies", "/dav/movies/"))
        assertTrue(
            WebDavPaths.hrefMatchesPath(
                "http://nas:5005/dav/my%20films/",
                "/dav/my%20films/",
            ),
        )
        assertFalse(WebDavPaths.hrefMatchesPath("/dav/movies/a.mkv", "/dav/movies/"))
        assertFalse(WebDavPaths.hrefMatchesPath("/dav/movies2/", "/dav/movies/"))
    }

    // ---------- parentOf（统一表示） ----------

    @Test
    fun parentOf_matchesNvPathsConvention() {
        assertEquals("/dav/movies", WebDavPaths.parentOf("/dav/movies/a.mkv"))
        assertEquals("/", WebDavPaths.parentOf("/a.mkv"))
    }
}
