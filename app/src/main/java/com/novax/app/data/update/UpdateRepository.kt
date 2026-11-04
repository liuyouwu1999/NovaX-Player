package com.novax.app.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

interface ReleaseHttp {
    @Throws(IOException::class)
    fun get(url: String): String
}

class GitHubHttpException(val code: Int) : IOException("HTTP $code")

class OkHttpReleaseHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) : ReleaseHttp {
    override fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NovaX-Player")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw GitHubHttpException(resp.code)
            return resp.body.string()
        }
    }
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult

    data class Available(
        val release: NvRelease,
        val changelog: List<String>,
        val asset: NvReleaseAsset?,
        val sums: Map<String, String>?,
    ) : UpdateCheckResult {
        /** spec §2.5：资产与校验文件齐备才允许应用内安装（宁可不装，不做未校验安装） */
        val canInstall: Boolean get() = asset != null && sums != null
    }

    data class Failed(val reason: Reason) : UpdateCheckResult {
        enum class Reason { NETWORK, RATE_LIMITED, BAD_RESPONSE }
    }
}

class UpdateRepository(private val http: ReleaseHttp) {

    /**
     * 编排：fetch json → 异常映射（403/429→RATE_LIMITED，其余 GitHubHttpException/IOException→NETWORK）
     * → parse null→BAD_RESPONSE → 无新版→UpToDate（不再发任何请求）
     * → 选 ABI 资产 → sums best-effort（失败不阻断 Available）→ Available。
     */
    fun check(localVersionName: String, deviceAbis: List<String>): UpdateCheckResult {
        val body = try {
            http.get(DEFAULT_API_URL)
        } catch (e: GitHubHttpException) {
            // GitHubHttpException extends IOException，须先捕
            return UpdateCheckResult.Failed(
                if (e.code == 403 || e.code == 429) UpdateCheckResult.Failed.Reason.RATE_LIMITED
                else UpdateCheckResult.Failed.Reason.NETWORK,
            )
        } catch (e: IOException) {
            return UpdateCheckResult.Failed(UpdateCheckResult.Failed.Reason.NETWORK)
        }

        val release = NvReleaseJsonParser.parse(body)
            ?: return UpdateCheckResult.Failed(UpdateCheckResult.Failed.Reason.BAD_RESPONSE)

        if (!NvVersionCompare.isUpdateAvailable(localVersionName, release.tagName)) {
            return UpdateCheckResult.UpToDate
        }

        val asset = NvAssetMatcher.pick(deviceAbis, release.assets)

        // sums 双通道 best-effort：取不到/解析空 → null，不影响 Available。
        // ①资产（2026-11-04 真机实证：归档自 v1.0.0.16 起用带版本号命名，固定名/带版本号双口径识别）；
        // ②正文 ```sha256 围栏回退（同日流量网络实证：文件下载域名被墙时资产取不到，而
        //   release JSON 走 api.github.com 已达——校验和随响应零额外请求，应用内安装仍可用）
        val sums = if (asset == null) null else release.assets
            .firstOrNull { isSumsAsset(it.name) }
            ?.url?.let { url ->
                runCatching { http.get(url) }.getOrNull()
                    ?.let(NvSha256SumsParser::parse)
                    ?.takeIf { it.isNotEmpty() }
            }
            ?: sumsFromBody(release.body)

        return UpdateCheckResult.Available(
            release = release,
            changelog = NvChangelogExtractor.extract(release.body),
            asset = asset,
            sums = sums,
        )
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/liuyouwu1999/NovaX-Player/releases/latest"

        /** 发版流水线固定校验和资产名（`_release/` 旧约定；现行带版本号命名由 [isSumsAsset] 识别） */
        const val SUMS_ASSET_NAME = "SHA256SUMS.txt"

        fun isSumsAsset(name: String): Boolean =
            name == SUMS_ASSET_NAME ||
                (name.startsWith("SHA256SUMS-") && name.endsWith(".txt"))

        /** 正文 ```sha256 围栏 → 校验和映射（围栏缺失/解析空 → null）。零网络：body 随 release JSON 已达 */
        private fun sumsFromBody(body: String?): Map<String, String>? {
            if (body.isNullOrBlank()) return null
            val lines = body.lines()
            var i = 0
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith("```sha256")) {
                    val out = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        out.add(lines[i]); i++
                    }
                    return NvSha256SumsParser.parse(out.joinToString("\n")).takeIf { it.isNotEmpty() }
                }
                i++
            }
            return null
        }
    }
}
