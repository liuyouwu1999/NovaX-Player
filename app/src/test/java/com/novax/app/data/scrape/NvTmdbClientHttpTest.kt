package com.novax.app.data.scrape

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** E2-1（发版审查）：HTTP 非 2xx 与「真无命中」分流——401/403 抛 Auth、其余非 2xx 抛 Unavailable、200 空结果才返回 null。 */
class NvTmdbClientHttpTest {

    /** 用 Interceptor 模拟任意状态码（无需 MockWebServer 依赖）。 */
    private fun client(code: Int, body: String = "{\"results\":[]}"): NvTmdbClient =
        NvTmdbClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(okhttp3.ResponseBody.create(null, body))
                    .build()
            }.build(),
        )

    @Test fun `200空结果_无命中返回null`() = runBlocking {
        assertNull(client(200).searchMovie("k", "Title", null))
    }

    @Test fun `200有结果_命中`() = runBlocking {
        val body = """{"results":[{"id":42,"title":"T","overview":"o","release_date":"2020-01-01","vote_average":7.5,"poster_path":"/p.jpg"}]}"""
        assertEquals(42L, client(200, body).searchMovie("k", "T", 2020)?.tmdbId)
    }

    @Test(expected = NvTmdbAuthException::class) fun `401抛Auth`() = runBlocking { client(401).searchMovie("k", "T", null); Unit }

    @Test(expected = NvTmdbAuthException::class) fun `403抛Auth`() = runBlocking { client(403).searchMovie("k", "T", null); Unit }

    @Test(expected = NvTmdbUnavailableException::class) fun `429抛Unavailable不写墓碑`() = runBlocking { client(429).searchMovie("k", "T", null); Unit }

    @Test(expected = NvTmdbUnavailableException::class) fun `500抛Unavailable不写墓碑`() = runBlocking { client(500).searchMovie("k", "T", null); Unit }
}
