package com.novax.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {

    private class FakeHttp(val bodies: Map<String, String>, val error: Throwable? = null) : ReleaseHttp {
        val hits = mutableListOf<String>()
        override fun get(url: String): String {
            hits.add(url)
            error?.let { throw it }
            return bodies[url] ?: throw java.io.IOException("no fixture")
        }
    }

    private val SUMS = REAL_SUMS_TEXT   // Task 4 REAL 常量改 1.0.0.1 文件名（哈希值沿用真身 fixture，编排水测不校验文件）
    private val API = UpdateRepository.DEFAULT_API_URL

    @Test fun `有新版且资产校验齐备`() {
        val http = FakeHttp(mapOf(API to REAL_JSON_LATEST, SUMS_URL to SUMS))
        val r = UpdateRepository(http).check("1.0.0.0", listOf("arm64-v8a")) as UpdateCheckResult.Available
        assertEquals("v1.0.0.1", r.release.tagName)
        assertEquals("NovaX-1.0.0.1-arm64-v8a.apk", r.asset!!.name)
        assertTrue(r.canInstall)
        assertTrue(r.changelog.isNotEmpty())
    }
    @Test fun `已最新不发sums请求`() {
        val http = FakeHttp(mapOf(API to REAL_JSON_LATEST))
        val r = UpdateRepository(http).check("1.0.0.1", listOf("arm64-v8a"))
        assertTrue(r is UpdateCheckResult.UpToDate)
        assertEquals(listOf(API), http.hits)
    }
    @Test fun `403限流与IOException分别映射`() {
        val rl = UpdateRepository(FakeHttp(emptyMap(), GitHubHttpException(403))).check("1.0.0.0", listOf("arm64-v8a"))
        assertEquals(UpdateCheckResult.Failed.Reason.RATE_LIMITED, (rl as UpdateCheckResult.Failed).reason)
        val net = UpdateRepository(FakeHttp(emptyMap(), java.io.IOException("airplane"))).check("1.0.0.0", listOf("arm64-v8a"))
        assertEquals(UpdateCheckResult.Failed.Reason.NETWORK, (net as UpdateCheckResult.Failed).reason)
    }
    @Test fun `sums取不到则Available但不可安装`() {
        val http = FakeHttp(mapOf(API to REAL_JSON_LATEST))   // sums URL 无 fixture → IOException
        val r = UpdateRepository(http).check("1.0.0.0", listOf("arm64-v8a")) as UpdateCheckResult.Available
        assertNull(r.sums)
        assertFalse(r.canInstall)          // spec §2.5：宁可不装
    }
    @Test fun `资产全不匹配则不可安装`() {
        val http = FakeHttp(mapOf(API to REAL_JSON_LATEST, SUMS_URL to SUMS))
        val r = UpdateRepository(http).check("1.0.0.0", listOf("mips")) as UpdateCheckResult.Available
        assertNull(r.asset); assertFalse(r.canInstall)
    }
    @Test fun `坏响应映射BAD_RESPONSE`() {
        val http = FakeHttp(mapOf(API to "not json"))
        assertEquals(UpdateCheckResult.Failed.Reason.BAD_RESPONSE, (UpdateRepository(http).check("1.0.0.0", listOf("arm64-v8a")) as UpdateCheckResult.Failed).reason)
    }

    companion object {
        private const val BASE = "https://github.com/liuyouwu1999/NovaX-Player/releases/download/v1.0.0.1"
        private const val SUMS_URL = "$BASE/SHA256SUMS.txt"

        /** Task 2 REAL_JSON 改 tag v1.0.0.1、资产名 1.0.0.1 */
        private val REAL_JSON_LATEST = """
{
  "url": "https://api.github.com/repos/liuyouwu1999/NovaX-Player/releases/2",
  "html_url": "https://github.com/liuyouwu1999/NovaX-Player/releases/tag/v1.0.0.1",
  "tag_name": "v1.0.0.1",
  "name": "NovaX Player v1.0.0.1",
  "body": "# NovaX Player v1.0.0.1\n\n## 更新\n\n```changelog\n修复若干问题\n提升播放稳定性\n```\n",
  "assets": [
    {"name": "NovaX-1.0.0.1-arm64-v8a.apk", "browser_download_url": "$BASE/NovaX-1.0.0.1-arm64-v8a.apk"},
    {"name": "NovaX-1.0.0.1-armeabi-v7a.apk", "browser_download_url": "$BASE/NovaX-1.0.0.1-armeabi-v7a.apk"},
    {"name": "NovaX-1.0.0.1-x86_64.apk", "browser_download_url": "$BASE/NovaX-1.0.0.1-x86_64.apk"},
    {"name": "SHA256SUMS.txt", "browser_download_url": "$SUMS_URL"}
  ]
}
""".trimIndent()

        /** Task 4 REAL 改 1.0.0.1 文件名 */
        private val REAL_SUMS_TEXT = """
974f2ad6e722a51ab8781257d15a61a7c3546940e6876738197df3543493c333 *NovaX-1.0.0.1-arm64-v8a.apk
78f5277a76ed9883266bc94e10374767d434a31043a17c2e91db0bc0e474f873 *NovaX-1.0.0.1-armeabi-v7a.apk
2189923087c60c1c1e4d5c38525b2479a59839e217585733cfe7873789be8aee *NovaX-1.0.0.1-x86_64.apk
""".trimIndent()
    }
}
