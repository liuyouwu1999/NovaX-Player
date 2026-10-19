package com.novax.app.data.update

import org.json.JSONArray
import org.json.JSONObject

data class NvReleaseAsset(val name: String, val url: String)
data class NvRelease(val tagName: String, val htmlUrl: String, val body: String?, val assets: List<NvReleaseAsset>)

/** spec §2.1：字段为 tag_name/html_url/body/assets[].name/browser_download_url（无 browser_url） */
object NvReleaseJsonParser {
    fun parse(json: String): NvRelease? = runCatching {
        val o = JSONObject(json)
        val tag = o.optString("tag_name", "")
        if (tag.isEmpty()) return@runCatching null
        val arr: JSONArray = o.optJSONArray("assets") ?: JSONArray()
        NvRelease(
            tagName = tag,
            htmlUrl = o.optString("html_url", ""),
            body = if (o.isNull("body")) null else o.optString("body", ""),
            assets = (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (name.isEmpty() || url.isEmpty()) null else NvReleaseAsset(name, url)
            },
        )
    }.getOrNull()
}
