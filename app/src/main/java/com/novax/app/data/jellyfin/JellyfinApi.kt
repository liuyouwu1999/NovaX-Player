package com.novax.app.data.jellyfin

import com.novax.app.player.SubtitleCandidate
import com.novax.app.player.SubtitleMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Jellyfin REST 纯函数层（spec 2026-10-07 §5）：URL 组装、JSON 解析、鉴权头/体、错误映射。
 * 零 Android 依赖（org.json 平台自带），JVM 可测；网络 IO 在 [JellyfinClient]。
 * JSON 解析不引序列化框架——对齐 NvTmdbClient「OkHttp + org.json」惯例。
 */

/**
 * 条目模型：[size]/[modifiedMs] 服务器不提供时为 0（与 NvFileEntry 同约定）。
 * [fileName] 是真实文件名（MediaSources[0].Path 的末段，带扩展名）；拿不到时为 null，
 * 调用方回退用 [name]——Name 是媒体标题、不带扩展名（12.x 实证），扩展名驱动的
 * 播放路由/嗅探逻辑必须用 [fileName]。
 */
data class JellyfinItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val fileName: String? = null,
)

data class JellyfinAuth(val userId: String, val accessToken: String)

// ---------------- M3：清晰度/转码（spec §6；12.2.0 实证契约见 M3 计划 Global Constraints） ----------------

/**
 * PlaybackInfo 结果：transcodingUrl 非 null 且 supportsTranscoding 时为服务器转码 HLS（绝对地址）。
 * [sourceVideoBitrate]：首个视频流的源码率 bps（未知 0）。2026-10-08 用户缺陷实证（海市蜃楼.E01，
 * 源码率 3.1M）：所选档位上限 ≥ 源码率时服务器仍报 SupportsTranscoding=true 但**不给
 * TranscodingUrl**（DirectPlay/DirectStream 便是该档位下最佳画质）——这是「无需转码」的正常
 * 结论，客户端必须与「真不可转码」分流，否则会把最佳结果误报成失败。
 */
data class JellyfinPlaybackInfo(
    val playSessionId: String,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean,
    val transcodingUrl: String?,
    val sourceVideoBitrate: Long = 0,
)

// ---------------- M4：内嵌字幕解析模型（spec 2026-10-07 §6；解析实现在 JellyfinApi.parseMediaStreams） ----------------

/**
 * 内嵌字幕流（已过滤 Type=Subtitle；isText 决定走 vtt 侧载还是归入图形字幕提示）。
 * i18n Task 9②：[displayTitle] 只携带服务器原始标题（可为空串），不再在数据层
 * 兜底合成「字幕$index」展示名——索引在 [index]，展示名由 UI 侧按当前语言拼装。
 */
data class JellyfinSubtitleStream(
    val index: Int,
    val displayTitle: String,
    val language: String?,
    val isText: Boolean,
)

/** 条目内嵌字幕解析结果：mediaSourceId 供 vtt URL 组装；缺 MediaSources 时双空。
 *  [videoBitrate]：首个视频流源码率 bps（未知 0）——清晰度面板「档位 ≥ 源码率隐藏」的判据。 */
data class JellyfinMediaStreams(
    val mediaSourceId: String?,
    val streams: List<JellyfinSubtitleStream>,
    val videoBitrate: Long = 0,
)

object JellyfinApi {

    /** /Items 单页大小（分页循环拉全用，client 侧控制硬上限 5000） */
    const val PAGE_LIMIT = 200

    /** host 规范化为 base URL：缺 scheme 补 http://，去尾斜杠（WebDav「host 可带 https://」同型） */
    fun normalizeBaseUrl(host: String): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
    }

    /**
     * `Authorization: MediaBrowser ...` 头值（首登无 token；登录后所有请求带 Token 字段）。
     * 头名必须是标准 Authorization——Jellyfin 12.x 拒绝旧头名 X-Emby-Authorization
     * （AuthenticateByName 一律 400，真机 12.2.0 实证）；10.x 两者兼容，官方客户端已迁移。
     */
    fun authHeader(client: String, device: String, deviceId: String, version: String, token: String? = null): String {
        val base = "MediaBrowser Client=\"$client\", Device=\"$device\", DeviceId=\"$deviceId\", Version=\"$version\""
        return if (token == null) base else "$base, Token=\"$token\""
    }

    /** AuthenticateByName 请求体（JSONObject 组装，引号转义由它负责） */
    fun authBody(username: String, password: String): String =
        JSONObject().put("Username", username).put("Pw", password).toString()

    fun parseAuth(json: String): JellyfinAuth {
        val o = JSONObject(json)
        return JellyfinAuth(
            userId = o.getJSONObject("User").getString("Id"),
            accessToken = o.getString("AccessToken"),
        )
    }

    fun viewsUrl(baseUrl: String, userId: String): String =
        "${normalizeBaseUrl(baseUrl)}/Users/$userId/Views"

    /**
     * /Items 分页查询（ParentId 可空 = 不限父级）；SortName 排序保证浏览顺序稳定。
     * Fields 必须带 Path,MediaSources：真实文件名在 MediaSources[0].Path 里，
     * Name 只有媒体标题（不带扩展名，JF-2 实证）。
     */
    fun itemsUrl(baseUrl: String, userId: String, parentId: String?, startIndex: Int, limit: Int): String {
        val b = StringBuilder(normalizeBaseUrl(baseUrl))
            .append("/Users/").append(userId)
            .append("/Items?SortBy=SortName&Fields=Size,Path,MediaSources")
            .append("&StartIndex=").append(startIndex)
            .append("&Limit=").append(limit)
        if (parentId != null) b.append("&ParentId=").append(parentId)
        return b.toString()
    }

    /** 原画静态流（M2 直连端点；M3 的转码 HLS 端点另行追加） */
    fun streamUrl(baseUrl: String, itemId: String, token: String): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/${URLEncoder.encode(itemId, "UTF-8")}/stream" +
            "?static=true&api_key=${URLEncoder.encode(token, "UTF-8")}"

    /**
     * 通知栏封面(spec 2026-10-10 §4.2):定宽小图;api_key 与 streamUrl 同口径(要求鉴权的服务器不静默 401)。
     * URLEncoder 空格=+,Jellyfin 端点两者均接受,与 streamUrl 同实现故口径一致。
     */
    fun primaryImageUrl(baseUrl: String, itemId: String, token: String, maxWidth: Int = 360): String =
        "${normalizeBaseUrl(baseUrl)}/Items/${URLEncoder.encode(itemId, "UTF-8")}/Images/Primary" +
            "?maxWidth=$maxWidth&api_key=${URLEncoder.encode(token, "UTF-8")}"

    fun parseItems(json: String): List<JellyfinItem> {
        val arr: JSONArray = JSONObject(json).optJSONArray("Items") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("Name").ifEmpty { o.getString("Id") }
            val source = firstMediaSource(o)
            JellyfinItem(
                id = o.getString("Id"),
                name = name,
                isFolder = o.optBoolean("IsFolder", false),
                size = parseSize(o, source),
                modifiedMs = parseJellyfinDate(o.optString("DateModified")),
                fileName = parseFileName(o, name, source),
            )
        }
    }

    /** MediaSources[0]，缺省/空数组返回 null（12.x 实证：真实文件名与大小都只在它身上） */
    private fun firstMediaSource(o: JSONObject): JSONObject? {
        val sources = o.optJSONArray("MediaSources")
        return if (sources != null && sources.length() > 0) sources.getJSONObject(0) else null
    }

    /**
     * 条目大小：12.x 的条目级 Size 恒为 null（curl 实证，即使 Fields=Size 也照空），真实大小
     * 只在 MediaSources[0].Size（SolLevante 片：141871521 vs 条目级 null）——取 0 会让
     * RMVB/VLC 兜底页误判「源不可达，无法兜底播放」。优先 MediaSources[0].Size，
     * 回退条目级 Size（10.x/Views 场景），再回退 0。
     */
    private fun parseSize(o: JSONObject, source: JSONObject?): Long {
        source?.optLong("Size", 0L)?.takeIf { it > 0 }?.let { return it }
        return o.optLong("Size", 0L)
    }

    /**
     * 真实文件名：取 MediaSources[0].Path 最后一个 '/' 之后的段（Name 是媒体标题、
     * 不带扩展名，12.x 实证 Name="杜比视界测试" 而文件是 "…_1080p.mp4"）。
     * MediaSources 缺省/空数组或 Path 为空时回退「标题.Container」，再不行回退 null
     * （调用方回退标题）。Windows 反斜杠路径不在处理范围（NAS 部署均为 POSIX 路径）。
     */
    private fun parseFileName(o: JSONObject, title: String, source: JSONObject?): String? {
        source?.optString("Path")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { return it }
        val container = o.optString("Container").ifEmpty { source?.optString("Container") ?: "" }
        return container.takeIf { it.isNotEmpty() }?.let { "$title.$it" }
    }

    /**
     * Jellyfin 日期是 7 位小数秒（"2024-05-01T10:20:30.0000000Z"），SimpleDateFormat 直接
     * 解析会抛——手工拆字段算 epoch ms（UTC），异常/空串回 0（NvFileEntry 同约定）。
     */
    fun parseJellyfinDate(s: String): Long {
        return try {
            val date = s.substringBefore('T')          // yyyy-MM-dd
            val time = s.substringAfter('T').trimEnd('Z', 'z')
            val y = date.substring(0, 4).toInt()
            val mo = date.substring(5, 7).toInt()
            val d = date.substring(8, 10).toInt()
            val h = time.substring(0, 2).toInt()
            val mi = time.substring(3, 5).toInt()
            val sec = time.substring(6, 8).toInt()
            val ms = time.substringAfter('.', "").take(3).padEnd(3, '0').ifEmpty { "0" }.toInt()
            val days = toEpochDay(y, mo, d)
            ((days * 86_400L + h * 3_600L + mi * 60L + sec) * 1_000L) + ms
        } catch (_: Exception) {
            0L
        }
    }

    /** 公历 → epoch day（纯算术，避免 java.time 的 minSdk 门槛） */
    private fun toEpochDay(y: Int, m: Int, d: Int): Long {
        // Howard Hinnant 的 civil_from_days 逆运算
        val yy = if (m <= 2) y - 1L else y.toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** "bytes 0-0/12345" → 12345；解析不出回 -1（与 fileSizeBlocking 的未知约定一致） */
    fun parseContentRangeTotal(header: String?): Long {
        if (header == null) return -1L
        val total = header.substringAfter('/', "")
        return total.toLongOrNull() ?: -1L
    }

    /** HTTP 状态码 → 中文文案（连接层错误由 client 的 toIo 风格包装；诊断原文，白名单——
     *  用户可见文案由上层按结构化 kind 归类为 UiMessage，见 [httpError]） */
    fun httpErrorMessage(code: Int): String = when (code) {
        401 -> "用户名或密码错误"
        403 -> "没有访问权限"
        404 -> "Jellyfin 服务器上找不到该内容"
        in 500..599 -> "Jellyfin 服务器内部错误"
        else -> "Jellyfin 服务器返回 $code"
    }

    /**
     * HTTP 状态码 → 抛出用异常（i18n Task 8 评审移交①）：401 携带结构化凭据判别
     * （NvConnectKind.JELLYFIN_CREDENTIAL，上层 mapConnectError 按 kind 归类、不再
     * contains 匹配中文原文「用户名或密码错误」）；其余状态码维持普通 IOException
     * （走通用兜底文案 + 诊断原文透传）。两侧一致性由 JellyfinApiTest/MapConnectErrorTest 锁定。
     */
    fun httpError(code: Int): IOException =
        if (code == 401) {
            com.novax.app.data.NvConnectException(
                com.novax.app.data.NvConnectKind.JELLYFIN_CREDENTIAL,
                httpErrorMessage(code),
            )
        } else {
            IOException(httpErrorMessage(code))
        }

    /**
     * DeviceProfile(2026-10-10 S3 参数化):默认参数 = 原「保守 DeviceProfile」行为逐串等价
     * (DirectPlay 只声明 mp4/mkv/mov/webm + aac,mp3,flac;转码 HLS/ts + h264/aac 立体声——
     * 实证:空 DeviceProfile 服务器全拒(400/全 false));能力化声明由 [com.novax.app.data.jellyfin.NvDeviceCodecs]
     * 生成、JellyfinClient 传入——视频转码追加 hevc(有 HEVC 解码器才声明)、音频按
     * NvFfmpegAudio 实证解码能力直解/直通(原「音频保守原则」退役,详见 NvDeviceCodecs KDoc)。
     * MaxAudioChannels "2" 保留:仅作用于真转码的降混,音频 copy 不受影响。
     * 字幕声明外部 vtt/srt(M4 侧载用;PGS 走服务器烧录,无需声明)。
     */
    fun deviceProfileJson(
        transcodeVideoCodecs: String = "h264",
        transcodeAudioCodecs: String = "aac",
        directPlayAudioCodecs: String = "aac,mp3,flac",
    ): String = JSONObject().put(
        "DirectPlayProfiles", JSONArray()
            .put(JSONObject().put("Container", "mp4,mkv,mov,webm").put("Type", "Video")
                .put("VideoCodec", "h264,hevc,av1,vp9")
                .put("AudioCodec", directPlayAudioCodecs)),
    ).put(
        "TranscodingProfiles", JSONArray().put(
            JSONObject().put("Container", "ts").put("Type", "Video").put("Protocol", "hls")
                .put("VideoCodec", transcodeVideoCodecs).put("AudioCodec", transcodeAudioCodecs)
                .put("MaxAudioChannels", "2")
                .put("BreakOnNonKeyFrames", true),
        ),
    ).put(
        "SubtitleProfiles", JSONArray()
            .put(JSONObject().put("Format", "vtt").put("Method", "External"))
            .put(JSONObject().put("Format", "srt").put("Method", "External")),
    ).toString()

    fun playbackInfoBody(
        userId: String,
        maxBitrateBps: Int,
        deviceProfileJson: String = deviceProfileJson(),
    ): String = JSONObject()
        .put("UserId", userId)
        .put("AutoOpenLiveStream", false)
        .put("MaxStreamingBitrate", maxBitrateBps)
        .put("DeviceProfile", JSONObject(deviceProfileJson))
        .toString()

    fun playbackInfoUrl(baseUrl: String, itemId: String, userId: String, maxBitrateBps: Int): String =
        "${normalizeBaseUrl(baseUrl)}/Items/${URLEncoder.encode(itemId, "UTF-8")}/PlaybackInfo" +
            "?userId=${URLEncoder.encode(userId, "UTF-8")}&maxStreamingBitrate=$maxBitrateBps"

    fun parsePlaybackInfo(json: String): JellyfinPlaybackInfo {
        val o = JSONObject(json)
        val ms = o.optJSONArray("MediaSources")?.optJSONObject(0)
        return JellyfinPlaybackInfo(
            playSessionId = o.optString("PlaySessionId"),
            supportsDirectPlay = ms?.optBoolean("SupportsDirectPlay", false) ?: false,
            supportsDirectStream = ms?.optBoolean("SupportsDirectStream", false) ?: false,
            supportsTranscoding = ms?.optBoolean("SupportsTranscoding", false) ?: false,
            transcodingUrl = ms?.optString("TranscodingUrl", "")?.ifEmpty { null },
            sourceVideoBitrate = firstVideoBitrate(ms),
        )
    }

    /** MediaStreams 里首个视频流的 BitRate bps（无/缺字段 0）——「无需转码」文案的数字来源 */
    private fun firstVideoBitrate(ms: JSONObject?): Long {
        val arr = ms?.optJSONArray("MediaStreams") ?: return 0L
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("Type") == "Video") return s.optLong("BitRate", 0L)
        }
        return 0L
    }

    /** 相对 TranscodingUrl → 绝对地址；无鉴权参数时追加 api_key（12.x 自带 ApiKey，旧版兜底） */
    fun absoluteTranscodingUrl(baseUrl: String, transcodingUrl: String, token: String): String {
        // fragment 防护（终审 Minor）：部分版本 TranscodingUrl 尾部可带 # 分段锚点，锚点若
        // 混进查询串会使 api_key 追加/HLS 请求失效——拼接前一律截掉
        val rel = transcodingUrl.substringBefore('#')
        val abs = if (rel.startsWith("http")) rel
        else normalizeBaseUrl(baseUrl) + rel
        val hasAuth = Regex("[?&](api_key|ApiKey)=").containsMatchIn(abs)
        return if (hasAuth) abs else "$abs${if ("?" in abs) "&" else "?"}api_key=${URLEncoder.encode(token, "UTF-8")}"
    }

    // ---------------- M4：进度上报 / 服务器续播 / 内嵌字幕（spec 2026-10-07 §6） ----------------

    /** Jellyfin 位置 ticks 换算：1 ms = 10_000 ticks（官方客户端同口径） */
    const val TICKS_PER_MS = 10_000L

    /** 条目元数据（MediaStreams 解析、UserData 续播位置都从它来；元数据查询不触发媒体探测，快） */
    fun itemMetadataUrl(baseUrl: String, userId: String, itemId: String): String =
        "${normalizeBaseUrl(baseUrl)}/Users/${URLEncoder.encode(userId, "UTF-8")}/Items/" +
            "${URLEncoder.encode(itemId, "UTF-8")}?Fields=MediaSources,MediaStreams"

    /** 内嵌文本字幕 vtt 直链（SubtitleController 五段路由，startPositionTicks=0 从头全量） */
    fun subtitleVttUrl(baseUrl: String, itemId: String, mediaSourceId: String, streamIndex: Int, token: String): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/${URLEncoder.encode(itemId, "UTF-8")}/" +
            "${URLEncoder.encode(mediaSourceId, "UTF-8")}/Subtitles/$streamIndex/0/Stream.vtt" +
            "?api_key=${URLEncoder.encode(token, "UTF-8")}"

    /** 服务器续播位置 ms；负数钳 0、缺 UserData/解析失败 0 */
    fun parseResumePositionMs(json: String): Long = try {
        val ticks = JSONObject(json).optJSONObject("UserData")?.optLong("PlaybackPositionTicks", 0L) ?: 0L
        (ticks / TICKS_PER_MS).coerceAtLeast(0L)
    } catch (_: Exception) {
        0L
    }

    /** 可外挂为 vtt 的文本字幕编码（在此之外的一律按图形字幕处理，保守即正确） */
    private val TEXT_SUB_CODECS = setOf(
        "subrip", "srt", "ass", "ssa", "webvtt", "vtt", "tx3g", "mov_text", "substationalpha", "sami", "smi",
    )

    fun parseMediaStreams(json: String): JellyfinMediaStreams {
        return try {
            val ms = JSONObject(json).optJSONArray("MediaSources")?.optJSONObject(0)
            val arr = ms?.optJSONArray("MediaStreams") ?: JSONArray()
            val streams = (0 until arr.length()).mapNotNull { i ->
                val s = arr.getJSONObject(i)
                if (s.optString("Type") != "Subtitle") return@mapNotNull null
                val index = s.optInt("Index", -1)
                if (index < 0) return@mapNotNull null
                val codec = s.optString("Codec").lowercase()
                JellyfinSubtitleStream(
                    index = index,
                    displayTitle = s.optString("DisplayTitle").ifEmpty { s.optString("Title") },
                    language = s.optString("Language").ifEmpty { null },
                    isText = s.optBoolean("IsTextSubtitleStream", false) || codec in TEXT_SUB_CODECS,
                )
            }
        JellyfinMediaStreams(
            mediaSourceId = ms?.optString("Id")?.ifEmpty { null },
            streams = streams,
            videoBitrate = firstVideoBitrate(ms),
        )
    } catch (_: Exception) {
        JellyfinMediaStreams(null, emptyList())
    }
}

/**
 * 内嵌文本字幕流 → vtt 侧载候选（纯函数，JVM 可测）。图形字幕不入列（isText=false，
 * 由调用方以「图形字幕存在性」提示直连限制）。score=SCORE_OTHER_CAP：永不自动选，
 * 只进手选面板——避免每次起播被服务器内嵌字幕自动打断（与外部字幕「无关候选不弹窗」同语义）。
 */
fun embeddedSubtitleCandidates(
    baseUrl: String,
    itemId: String,
    mediaSourceId: String,
    token: String,
    streams: List<JellyfinSubtitleStream>,
): List<SubtitleCandidate> = streams.filter { it.isText }.map { s ->
    SubtitleCandidate(
        // 扩展名驱动 mimeTypeForFile 判定（vtt）；id 稳定（同一字幕轨跨会话同 id）
        path = "jellyfin-embedded-${s.index}.vtt",
        // i18n Task 9②：displayName 只存服务器原始标题；「[内嵌] 」前缀与空标题兜底
        // 「字幕$index」改由 UI 侧以资源拼装（embedded/trackIndex 标记）
        displayName = s.displayTitle,
        languageTag = s.language?.let { SubtitleMatcher.normalizeLanguageTag(it) },
        isExact = false,
        score = SubtitleMatcher.SCORE_OTHER_CAP,
        remoteUrl = subtitleVttUrl(baseUrl, itemId, mediaSourceId, s.index, token),
        embedded = true,
        trackIndex = s.index,
    )
}

    /**
     * 进度上报三端点（POST，体见 [playingBody]；无会话/失败静默——上报永不影响播放）。
     * Start 是 POST /Sessions/Playing（Jellyfin PlaystateController.ReportPlaybackStart）——
     * 不能用 Emby 旧路由 /Sessions/Playing/Start：Jellyfin 12.2.0 无该路由（curl 404 实证），
     * 每次起播上报失败且服务器 PlayCount 不增长（缺陷 D1，2026-10-08 修复钉位）。
     * Progress/Stopped 子路由 204 正常（实证），维持不动。
     */
    fun playingStartUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/Sessions/Playing"
    fun playingProgressUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/Sessions/Playing/Progress"
    fun playingStoppedUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/Sessions/Playing/Stopped"

    fun playingBody(
        itemId: String,
        playSessionId: String?,
        positionMs: Long,
        isPaused: Boolean,
        durationMs: Long = 0L,
    ): String {
        val o = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", positionMs.coerceAtLeast(0L) * TICKS_PER_MS)
            .put("IsPaused", isPaused)
        if (playSessionId != null) o.put("PlaySessionId", playSessionId)
        if (durationMs > 0L) o.put("DurationTicks", durationMs * TICKS_PER_MS)
        return o.toString()
    }
}
