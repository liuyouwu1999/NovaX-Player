package com.novax.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NvReleaseJsonParserTest {

    private val REAL_JSON = """
{
  "url": "https://api.github.com/repos/liuyouwu1999/NovaX-Player/releases/1",
  "html_url": "https://github.com/liuyouwu1999/NovaX-Player/releases/tag/v1.0.0.0",
  "tag_name": "v1.0.0.0",
  "name": "NovaX Player v1.0.0.0",
  "body": "# NovaX Player v1.0.0.0 · 首个公开发布\n\n## 下载\n",
  "assets": [
    {"name": "NovaX-1.0.0.0-arm64-v8a.apk", "browser_download_url": "https://github.com/liuyouwu1999/NovaX-Player/releases/download/v1.0.0.0/NovaX-1.0.0.0-arm64-v8a.apk"},
    {"name": "NovaX-1.0.0.0-armeabi-v7a.apk", "browser_download_url": "https://github.com/liuyouwu1999/NovaX-Player/releases/download/v1.0.0.0/NovaX-1.0.0.0-armeabi-v7a.apk"},
    {"name": "NovaX-1.0.0.0-x86_64.apk", "browser_download_url": "https://github.com/liuyouwu1999/NovaX-Player/releases/download/v1.0.0.0/NovaX-1.0.0.0-x86_64.apk"},
    {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/liuyouwu1999/NovaX-Player/releases/download/v1.0.0.0/SHA256SUMS.txt"}
  ]
}
""".trimIndent()

    @Test fun `解析真实样例`() {
        val r = NvReleaseJsonParser.parse(REAL_JSON)!!
        assertEquals("v1.0.0.0", r.tagName)
        assertEquals(4, r.assets.size)
        assertEquals("https://github.com/liuyouwu1999/NovaX-Player/releases/tag/v1.0.0.0", r.htmlUrl)
    }
    @Test fun `缺失tag或坏json返回null`() {
        assertNull(NvReleaseJsonParser.parse("""{"html_url":"x"}"""))
        assertNull(NvReleaseJsonParser.parse("not json"))
        assertNull(NvReleaseJsonParser.parse("""{"tag_name":""}"""))
    }
    @Test fun `body为null与空assets容错`() {
        val r = NvReleaseJsonParser.parse("""{"tag_name":"v1.0.0.1","body":null,"assets":[]}""")!!
        assertNull(r.body); assertTrue(r.assets.isEmpty())
    }
}
