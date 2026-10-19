package com.novax.app.data.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** TMDB 检索命中的最小元数据集。 */
data class NvTmdbResult(
    val tmdbId: Long,
    val title: String,
    val overview: String?,
    val year: Int?,
    val voteAverage: Double,
    val posterPath: String?,
)

/** TMDB 服务不可达（网络抖动/超时）：区别于「检索无命中」，调用方应跳过写墓碑、留待下次重试。 */
class NvTmdbUnavailableException(cause: Throwable) : Exception("TMDB unreachable: ${cause.message}", cause)

/** TMDB API Key 无效/失效（HTTP 401/403）：与网络不可用区分——中止整批刮削并让 UI 明确提示，用户须修 Key。 */
class NvTmdbAuthException(message: String) : RuntimeException(message)

/**
 * TMDB v3 API 客户端（OkHttp + org.json；语言 zh-CN 优先，无结果回退 en-US）。
 *
 * API Key 由用户在设置-媒体库里自行填入（TMDB 免费注册即得 v3 key），
 * App 不内置密钥——无 Key 时刮削器直接跳过并在 UI 如实提示。
 */
class NvTmdbClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    // ---- URL 构造（纯 Kotlin，可 JVM 测试；禁 android.net.Uri）----

    fun searchMovieUrl(apiKey: String, query: String, year: Int?, language: String = "zh-CN"): String =
        searchUrl("movie", apiKey, query, year, language)

    fun searchTvUrl(apiKey: String, query: String, year: Int?, language: String = "zh-CN"): String =
        searchUrl("tv", apiKey, query, year, language)

    private fun searchUrl(type: String, apiKey: String, query: String, year: Int?, language: String): String {
        val sb = StringBuilder("$BASE/3/search/$type?api_key=").append(enc(apiKey))
            .append("&query=").append(enc(query))
            .append("&language=").append(enc(language))
            .append("&page=1&include_adult=false")
        year?.let { sb.append("&year=").append(it) }
        return sb.toString()
    }

    /** poster_path（/xxx.jpg 相对路径）→ w500 图片 URL；无效路径返回 null。 */
    fun posterUrl(posterPath: String?): String? =
        posterPath?.takeIf { it.startsWith("/") }?.let { "$IMG_BASE/t/p/w500$it" }

    /**
     * 海报下载（[posterPath] 为 TMDB 相对路径）→ 写 [target]，成功返回 target 绝对路径。
     * .download 临时文件 + rename 原子落盘（照 ViewerFiles.materialize 范式）；任何失败返回 null
     * （调用方不写库，下次刮削重试）。
     */
    suspend fun downloadTo(posterPath: String, target: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = posterUrl(posterPath) ?: return@runCatching null
            val tmp = File(target.parentFile, target.name + ".download")
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.byteStream()?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, BUFFER_BYTES) }
                } ?: return@runCatching null
            }
            if (tmp.renameTo(target) || target.exists()) target.absolutePath else null
        }.getOrNull()
    }

    // ---- 检索 ----

    /** 电影检索：zh-CN 无命中（或无海报）回退 en-US，再无命中返回 null。网络失败抛 [NvTmdbUnavailableException]；Key 无效抛 [NvTmdbAuthException]。 */
    suspend fun searchMovie(apiKey: String, query: String, year: Int?): NvTmdbResult? =
        search(searchMovieUrl(apiKey, query, year, "zh-CN"))
            ?.takeIf { it.posterPath != null && it.overview?.isNotBlank() == true }
            ?: search(searchMovieUrl(apiKey, query, year, "en-US"))?.takeIf { it.posterPath != null && it.overview?.isNotBlank() == true }
            ?: search(searchMovieUrl(apiKey, query, null, "zh-CN"))

    /** 剧集检索（year 用首播年）。网络失败抛 [NvTmdbUnavailableException]；Key 无效抛 [NvTmdbAuthException]。 */
    suspend fun searchTv(apiKey: String, query: String, year: Int?): NvTmdbResult? =
        search(searchTvUrl(apiKey, query, year, "zh-CN"))
            ?.takeIf { it.posterPath != null && it.overview?.isNotBlank() == true }
            ?: search(searchTvUrl(apiKey, query, year, "en-US"))?.takeIf { it.posterPath != null && it.overview?.isNotBlank() == true }
            ?: search(searchTvUrl(apiKey, query, null, "zh-CN"))

    /**
     * 返回 null = 检索确无命中（HTTP 200 且 results 为空）；服务错误/限流抛
     * [NvTmdbUnavailableException]（调用方跳过、不写墓碑、下次重试）；Key 无效抛
     * [NvTmdbAuthException]（中止整批、UI 明确提示）——绝不与「无结果」混同
     * （BUG-C-R5-02；2026-10-16 发版审查 E2-1：此前一切非 2xx 按无命中处理，
     * 错 Key 或一次限流即给整批条目写永久空墓碑，永不自愈）。
     */
    private suspend fun search(url: String): NvTmdbResult? = withContext(Dispatchers.IO) {
        val body = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    if (code == 401 || code == 403) {
                        throw NvTmdbAuthException("TMDB API Key 无效或已失效（HTTP $code），请在 设置-媒体库 检查")
                    }
                    throw NvTmdbUnavailableException(java.io.IOException("TMDB HTTP $code"))
                }
                resp.body?.string()
            } ?: return@withContext null
        } catch (e: IOException) {
            throw NvTmdbUnavailableException(e)
        }
        parseResults(body)
    }

    private fun parseResults(body: String): NvTmdbResult? {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        var first: NvTmdbResult? = null
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val r = NvTmdbResult(
                tmdbId = o.optLong("id"),
                title = o.optString("title").ifEmpty { o.optString("name") },
                overview = o.optString("overview").takeIf { it.isNotBlank() },
                year = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
                    .takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull(),
                voteAverage = o.optDouble("vote_average", 0.0),
                posterPath = o.optString("poster_path").takeIf { it.isNotBlank() },
            )
            if (r.tmdbId == 0L) continue
            // 优先取首个带海报的结果；都没有则退而取首个
            if (r.posterPath != null) return r
            if (first == null) first = r
        }
        return first
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org"
        private const val IMG_BASE = "https://image.tmdb.org"
        private const val BUFFER_BYTES = 64 * 1024

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
