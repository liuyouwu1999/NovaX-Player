package com.novax.app.data.webdav

import com.novax.app.data.webdav.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * parseMultistatus 防回归单测（8edc70b 命名空间前缀兼容修复）：
 * 主流 WebDAV 服务器（Apache/nginx-dav-ext/群晖等）默认返回带前缀的 Multi-Status
 * （<d:response>/<D:response>/<ns0:response>），旧实现按原始限定名 equals 全不匹配
 * → 目录永远「为空」。本测试锁定：任意前缀/无前缀/混合 均解析一致。
 */
class WebDavParseMultistatusTest {

    private fun parse(xml: String) = WebDavClient().parseMultistatus(StringReader(xml))

    /** 真机实测样本：小写 d: 前缀 + 根自引用行 + 文件行含 resourcetype 空元素 */
    @Test
    fun `lowercase d prefix`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""" +
            """<d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>""" +
            """</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
            """<d:response><d:href>/test-video.mp4</d:href><d:propstat><d:prop><d:resourcetype></d:resourcetype>""" +
            """<d:getcontentlength>4999379</d:getcontentlength><d:getlastmodified>Tue, 16 Sep 2026 16:21:00 GMT</d:getlastmodified>""" +
            """</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val entries = parse(xml)
        assertEquals(2, entries.size)
        assertEquals("/", entries[0].href)
        assertTrue(entries[0].isCollection)
        assertEquals("/test-video.mp4", entries[1].href)
        assertEquals(false, entries[1].isCollection)
        assertEquals(4999379L, entries[1].size)
    }

    /** 大写 D: 前缀（Apache httpd 默认形态） */
    @Test
    fun `uppercase D prefix`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""" +
            """<D:response><D:href>/dir/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>""" +
            """</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""" +
            """<D:response><D:href>/dir/a.mkv</D:href><D:propstat><D:prop><D:resourcetype/>""" +
            """<D:getcontentlength>10</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
        val entries = parse(xml)
        assertEquals(2, entries.size)
        assertTrue(entries[0].isCollection)
        assertEquals("/dir/a.mkv", entries[1].href)
        assertEquals(10L, entries[1].size)
    }

    /** 无前缀（部分轻量服务器形态）——前向兼容不回归 */
    @Test
    fun `no prefix`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><multistatus xmlns="DAV:">""" +
            """<response><href>/x.mp4</href><propstat><prop><resourcetype/>""" +
            """<getcontentlength>7</getcontentlength></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
        val entries = parse(xml)
        assertEquals(1, entries.size)
        assertEquals("/x.mp4", entries[0].href)
        assertEquals(7L, entries[0].size)
    }

    /** 自闭合 resourcetype 与 percent 编码 href */
    @Test
    fun `self-closed resourcetype and percent-encoded href`() {
        val xml = """<d:multistatus xmlns:d="DAV:">""" +
            """<d:response><d:href>/ milieu/%E4%B8%AD%E6%96%87.mp4</d:href><d:propstat><d:prop><d:resourcetype/>""" +
            """<d:getcontentlength>3</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"""
        val entries = parse(xml)
        assertEquals(1, entries.size)
        assertTrue(entries[0].href.endsWith("%E4%B8%AD%E6%96%87.mp4"))
        assertEquals(false, entries[0].isCollection)
    }
}
