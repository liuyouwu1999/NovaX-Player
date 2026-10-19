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

        // sums 是独立资产（SHA256SUMS.txt），best-effort：取不到/解析空 → null，不影响 Available
        val sums = if (asset == null) null else release.assets
            .firstOrNull { it.name == SUMS_ASSET_NAME }
            ?.url?.let { url ->
                runCatching { http.get(url) }.getOrNull()
                    ?.let(NvSha256SumsParser::parse)
                    ?.takeIf { it.isNotEmpty() }
            }

        return UpdateCheckResult.Available(
            release = release,
            changelog = NvChangelogExtractor.extract(release.body),
            asset = asset,
            sums = sums,
        )
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/liuyouwu1999/NovaX-Player/releases/latest"

        /** 发版流水线固定的校验和资产名（`_release/` 约定） */
        const val SUMS_ASSET_NAME = "SHA256SUMS.txt"
    }
}
