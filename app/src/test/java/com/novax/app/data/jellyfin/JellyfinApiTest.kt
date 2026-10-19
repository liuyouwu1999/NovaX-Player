package com.novax.app.data.jellyfin

import com.novax.app.player.SubtitleMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinApiTest {

    // ---- normalizeBaseUrl：host 可带也可不带 scheme/尾斜杠 ----

    @Test
    fun normalizeBaseUrl_addsSchemeAndStripsTrailingSlash() {
        assertEquals("http://192.168.1.50:8096", JellyfinApi.normalizeBaseUrl("192.168.1.50:8096"))
        assertEquals("http://192.168.1.50:8096", JellyfinApi.normalizeBaseUrl("http://192.168.1.50:8096/"))
        assertEquals("https://nas.example.com:8920", JellyfinApi.normalizeBaseUrl("https://nas.example.com:8920"))
    }

    // ---- 鉴权头/请求体 ----

    @Test
    fun authHeader_withoutToken_omitsTokenField() {
        val h = JellyfinApi.authHeader("NovaX", "Pixel", "dev-1", "1.0")
        assertTrue(h.startsWith("MediaBrowser "))
        assertTrue(h.contains("Client=\"NovaX\""))
        assertTrue(h.contains("DeviceId=\"dev-1\""))
        assertTrue(!h.contains("Token="))
    }

    @Test
    fun authHeader_withToken_appendsTokenField() {
        val h = JellyfinApi.authHeader("NovaX", "Pixel", "dev-1", "1.0", token = "tok123")
        assertTrue(h.contains("Token=\"tok123\""))
    }

    @Test
    fun authBody_escapesQuotesAndPasswordField() {
        val body = JellyfinApi.authBody("user\"1", "p\"w")
        assertTrue(body.contains("\"Username\":\"user\\\"1\""))
        assertTrue(body.contains("\"Pw\":\"p\\\"w\""))
    }

    // ---- 登录响应解析 ----

    @Test
    fun parseAuth_extractsUserIdAndToken() {
        val json = """{"User":{"Id":"uid-42","Name":"novax"},"AccessToken":"tok-9"}"""
        assertEquals(JellyfinAuth("uid-42", "tok-9"), JellyfinApi.parseAuth(json))
    }

    @Test(expected = org.json.JSONException::class)
    fun parseAuth_throwsOnMissingToken() {
        JellyfinApi.parseAuth("""{"User":{"Id":"uid-42"}}""")
    }

    // ---- 条目解析（Views 与 /Items 同型信封）----

    @Test
    fun parseItems_mapsFieldsAndTrailingSlashForFolders() {
        val json = """
            {"Items":[
              {"Name":"电影","Id":"lib-1","IsFolder":true,"Type":"CollectionFolder","ChildCount":12},
              {"Name":"杜比视界测试","Id":"it-2","IsFolder":false,"Type":"Movie","Size":54321098,
               "DateModified":"2024-05-01T10:20:30.0000000Z",
               "MediaSources":[{"Path":"/media/movies/SolLevante_Profile5_1080p.mp4","Container":"mp4","Size":141871521}]}
            ],"TotalRecordCount":2,"StartIndex":0}
        """.trimIndent()
        val items = JellyfinApi.parseItems(json)
        assertEquals(2, items.size)
        assertEquals(
            JellyfinItem("lib-1", "电影", isFolder = true, size = 0L, modifiedMs = 0L, fileName = null),
            items[0],
        )
        assertEquals("it-2", items[1].id)
        // JF-3：12.x 条目级 Size 恒空，真实大小优先取 MediaSources[0].Size（141871521 为
        // SolLevante 片 curl 实证值；条目级 54321098 仅作回退，不得覆盖 MediaSources）
        assertEquals(141871521L, items[1].size)
        // JF-2：fileName 是真实文件名（Path 末段，带扩展名），Name 只是媒体标题
        assertEquals("SolLevante_Profile5_1080p.mp4", items[1].fileName)
        assertEquals("杜比视界测试", items[1].name)
    }

    @Test
    fun parseItems_emptyItems_returnsEmptyList() {
        assertTrue(JellyfinApi.parseItems("""{"Items":[],"TotalRecordCount":0}""").isEmpty())
    }

    // ---- JF-2：真实文件名的边界（MediaSources 缺省 → 回退标题；只有 Container → 标题.容器）----

    @Test
    fun parseItems_mediaSourcesMissing_fileNameIsNull() {
        // 无 MediaSources（如 Views/老版本响应）：fileName 为 null，调用方回退标题
        val json = """{"Items":[{"Name":"阿凡达","Id":"it-3","IsFolder":false,"Size":1}]}"""
        val item = JellyfinApi.parseItems(json).single()
        assertEquals("阿凡达", item.name)
        assertEquals(null, item.fileName)
    }

    @Test
    fun parseItems_containerWithoutPath_fileNameIsTitleDotContainer() {
        // MediaSources 无 Path（流媒体/直播条目常见）：回退「标题.Container」拼出可用扩展名
        val json = """
            {"Items":[{"Name":"海岸","Id":"it-4","IsFolder":false,"Container":"mkv",
              "MediaSources":[{}]}]}
        """.trimIndent()
        val item = JellyfinApi.parseItems(json).single()
        assertEquals("海岸.mkv", item.fileName)
    }

    // ---- JF-3：条目大小优先 MediaSources[0].Size（12.x 条目级 Size 恒空）----

    @Test
    fun parseItems_noMediaSources_sizeFallsBackToItemLevel() {
        // 10.x/Views 场景：条目无 MediaSources，大小用条目级 Size（12.x 该字段恒空时最终回退 0）
        val json = """{"Items":[{"Name":"阿凡达","Id":"it-5","IsFolder":false,"Size":12345678}]}"""
        val item = JellyfinApi.parseItems(json).single()
        assertEquals(12345678L, item.size)
        assertEquals(null, item.fileName)
    }

    // ---- Review Focus 3：7 位小数秒日期 ----

    @Test
    fun parseJellyfinDate_handles7DigitFraction() {
        // 2024-05-01T10:20:30 UTC = 1714558830000（brief 原常量 1714561230000 实为 11:00:30Z，系笔误）
        assertEquals(1714558830000L, JellyfinApi.parseJellyfinDate("2024-05-01T10:20:30.0000000Z"))
    }

    @Test
    fun parseJellyfinDate_invalidOrEmpty_returnsZero() {
        assertEquals(0L, JellyfinApi.parseJellyfinDate(""))
        assertEquals(0L, JellyfinApi.parseJellyfinDate("not-a-date"))
    }

    // ---- Review Focus 4：分页 URL ----

    @Test
    fun itemsUrl_carriesParentPagingAndSort() {
        val url = JellyfinApi.itemsUrl("http://n:8096", "uid-1", "pid-9", startIndex = 300, limit = 200)
        assertTrue(url.startsWith("http://n:8096/Users/uid-1/Items?"))
        assertTrue(url.contains("ParentId=pid-9"))
        assertTrue(url.contains("StartIndex=300"))
        assertTrue(url.contains("Limit=200"))
        assertTrue(url.contains("SortBy=SortName"))
        assertTrue(url.contains("Fields=Size,Path,MediaSources"))
    }

    @Test
    fun itemsUrl_nullParentId_omitsParentParam() {
        val url = JellyfinApi.itemsUrl("http://n:8096", "uid-1", null, 0, 200)
        assertTrue(!url.contains("ParentId"))
    }

    @Test
    fun viewsUrl_pointsAtUserViews() {
        assertEquals("http://n:8096/Users/uid-1/Views", JellyfinApi.viewsUrl("http://n:8096", "uid-1"))
    }

    @Test
    fun streamUrl_isStaticEndpointWithApiKey() {
        val url = JellyfinApi.streamUrl("http://n:8096", "it-2", "tok-9")
        assertEquals("http://n:8096/Videos/it-2/stream?static=true&api_key=tok-9", url)
    }

    // ---- Content-Range 总长 ----

    @Test
    fun parseContentRangeTotal_readsTotalAfterSlash() {
        assertEquals(12345L, JellyfinApi.parseContentRangeTotal("bytes 0-0/12345"))
        assertEquals(-1L, JellyfinApi.parseContentRangeTotal("bytes */-1"))
        assertEquals(-1L, JellyfinApi.parseContentRangeTotal(null))
    }

    // ---- 错误文案 ----

    @Test
    fun httpErrorMessage_isChineseAndSpecific() {
        assertEquals("用户名或密码错误", JellyfinApi.httpErrorMessage(401))
        assertEquals("Jellyfin 服务器上找不到该内容", JellyfinApi.httpErrorMessage(404))
        assertEquals("Jellyfin 服务器内部错误", JellyfinApi.httpErrorMessage(500))
        assertTrue(JellyfinApi.httpErrorMessage(418).contains("418"))
    }

    /** i18n Task 8 评审移交①：抛出侧结构化判别（401 携带凭据 kind，其余普通 IOException） */
    @Test
    fun httpError_structuredKindOn401() {
        val e401 = JellyfinApi.httpError(401)
        assertTrue("401 必须是 NvConnectException", e401 is com.novax.app.data.NvConnectException)
        assertEquals(
            com.novax.app.data.NvConnectKind.JELLYFIN_CREDENTIAL,
            (e401 as com.novax.app.data.NvConnectException).kind,
        )
        assertEquals("用户名或密码错误", e401.message)
        assertFalse("非 401 维持普通 IOException（走通用兜底）", JellyfinApi.httpError(500) is com.novax.app.data.NvConnectException)
    }

    // ---- M3：DeviceProfile / PlaybackInfo ----

    @Test
    fun deviceProfileJson_declaresHlsTranscodeAndDirectPlay() {
        val p = JellyfinApi.deviceProfileJson()
        assertTrue(p.contains("\"Protocol\":\"hls\""))
        assertTrue(p.contains("\"Container\":\"ts\""))
        assertTrue(p.contains("\"VideoCodec\":\"h264\""))
        assertTrue(p.contains("DirectPlayProfiles"))
        // 实现声明完整容器列表 mp4,mkv,mov,webm（brief 原断言 "mp4,mkv" 带闭引号，对子串永不匹配，系笔误）
        assertTrue(p.contains("\"Container\":\"mp4,mkv,mov,webm\""))
        // S3(2026-10-10):默认参数=保守行为;能力化声明经 NvDeviceCodecs 由 JellyfinClient 传入(见 deviceProfileJson_capabilityParams_passThrough 单测)
        assertTrue(!p.contains("dts") && !p.contains("truehd") && !p.contains("ac3"))
        assertTrue(p.contains("\"AudioCodec\":\"aac,mp3,flac\""))
    }

    @Test
    fun deviceProfileJson_default_keepsLegacyConservativeProfile() {
        // S3:默认参数路径=2026-10-09 前的保守行为,逐串等价(无 hevc 设备/回退路径零变化)。
        // 断言按 JSON 层对准转码档——不能整串 !contains("hevc")/!contains("ac3"):DirectPlay
        // 档的 VideoCodec 本就是 "h264,hevc,av1,vp9"(既有声明,spec 零改动红线)
        val o = JSONObject(JellyfinApi.deviceProfileJson())
        val directPlay = o.getJSONArray("DirectPlayProfiles").getJSONObject(0)
        assertEquals("aac,mp3,flac", directPlay.getString("AudioCodec"))
        val transcode = o.getJSONArray("TranscodingProfiles").getJSONObject(0)
        assertEquals("h264", transcode.getString("VideoCodec"))
        assertEquals("aac", transcode.getString("AudioCodec"))
        assertEquals("2", transcode.getString("MaxAudioChannels"))
    }

    @Test
    fun deviceProfileJson_capabilityParams_passThrough() {
        // S3(spec §3.4 第3项):能力化三参透传——hevc 转码 + 音频直通直解。
        // F4(2026-10-10):串值对齐 NvDeviceCodecs 常量——转码串不含 flac(TsExtractor 无
        // FlacReader,copy 进 ts 整片无声),DirectPlay 串含 flac,双向断言
        val o = JSONObject(
            JellyfinApi.deviceProfileJson(
                transcodeVideoCodecs = "h264,hevc",
                transcodeAudioCodecs = "aac,ac3,eac3,dts,mp3",
                directPlayAudioCodecs = "aac,mp3,flac,ac3,eac3,dts,truehd",
            ),
        )
        val transcode = o.getJSONArray("TranscodingProfiles").getJSONObject(0)
        val directPlay = o.getJSONArray("DirectPlayProfiles").getJSONObject(0)
        assertEquals("h264,hevc", transcode.getString("VideoCodec"))
        assertEquals("aac,ac3,eac3,dts,mp3", transcode.getString("AudioCodec"))
        assertEquals("aac,mp3,flac,ac3,eac3,dts,truehd", directPlay.getString("AudioCodec"))
        assertTrue(!transcode.getString("AudioCodec").contains("flac"))
        assertTrue(directPlay.getString("AudioCodec").contains("flac"))
    }

    @Test
    fun playbackInfoBody_embedsProvidedProfile() {
        val body = JellyfinApi.playbackInfoBody("uid-1", 2_000_000, """{"k":1}""")
        assertTrue(body.contains("\"DeviceProfile\":{\"k\":1}"))
        assertTrue(body.contains("\"MaxStreamingBitrate\":2000000"))
    }

    @Test
    fun playbackInfoUrl_andBody_carryBitrate() {
        val url = JellyfinApi.playbackInfoUrl("http://n:8096", "item-1", "uid-1", 4_000_000)
        assertEquals("http://n:8096/Items/item-1/PlaybackInfo?userId=uid-1&maxStreamingBitrate=4000000", url)
        val body = JellyfinApi.playbackInfoBody("uid-1", 4_000_000)
        assertTrue(body.contains("\"UserId\":\"uid-1\""))
        assertTrue(body.contains("\"MaxStreamingBitrate\":4000000"))
        assertTrue(body.contains("DeviceProfile"))
    }

    @Test
    fun parsePlaybackInfo_readsTranscodingSession() {
        // 实证响应（12.2.0，字段截取）
        val json = """
          {"PlaySessionId":"ps-123","MediaSources":[{
            "Id":"item-1","Container":"mp4",
            "SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,
            "TranscodingUrl":"/videos/e24f/master.m3u8?DeviceId=d&VideoCodec=h264&VideoBitrate=3808000&ApiKey=k123",
            "TranscodingSubProtocol":"hls","TranscodingContainer":"ts"}]}
        """.trimIndent()
        val info = JellyfinApi.parsePlaybackInfo(json)
        assertEquals("ps-123", info.playSessionId)
        assertEquals(false, info.supportsDirectPlay)
        assertEquals(true, info.supportsTranscoding)
        assertEquals("/videos/e24f/master.m3u8?DeviceId=d&VideoCodec=h264&VideoBitrate=3808000&ApiKey=k123", info.transcodingUrl)
    }

    @Test
    fun parsePlaybackInfo_noMediaSources_yieldsDefaults() {
        val info = JellyfinApi.parsePlaybackInfo("""{"PlaySessionId":"ps","MediaSources":[]}""")
        assertEquals(false, info.supportsTranscoding)
        assertNull(info.transcodingUrl)
        assertEquals(0L, info.sourceVideoBitrate)
    }

    @Test
    fun parsePlaybackInfo_readsSourceVideoBitrate() {
        // 2026-10-08 用户缺陷实证形态（海市蜃楼.E01）：档位 ≥ 源码率 → 有直连无 TranscodingUrl，
        // 源码率取 MediaStreams 首个 Video 流的 BitRate（音频流不参与）
        val json = """
          {"PlaySessionId":"ps-1","MediaSources":[{
            "Id":"item-1","Container":"mp4",
            "SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,
            "MediaStreams":[
              {"Index":0,"Type":"Video","Codec":"hevc","BitRate":3124018,"Width":3840},
              {"Index":1,"Type":"Audio","Codec":"aac","BitRate":189891}
            ]}]}
        """.trimIndent()
        val info = JellyfinApi.parsePlaybackInfo(json)
        assertEquals(true, info.supportsDirectPlay)
        assertEquals(true, info.supportsTranscoding)
        assertNull(info.transcodingUrl)
        assertEquals(3124018L, info.sourceVideoBitrate)
    }

    @Test
    fun parsePlaybackInfo_missingBitrate_yieldsZero() {
        val info = JellyfinApi.parsePlaybackInfo(
            """{"MediaSources":[{"SupportsTranscoding":true,"MediaStreams":[{"Type":"Video","Codec":"hevc"}]}]}""",
        )
        assertEquals(0L, info.sourceVideoBitrate)
    }

    @Test
    fun absoluteTranscodingUrl_appendsApiKeyOnlyWhenAbsent() {
        assertEquals(
            "http://n:8096/videos/g/master.m3u8?a=1&api_key=tok",
            JellyfinApi.absoluteTranscodingUrl("http://n:8096", "/videos/g/master.m3u8?a=1", "tok"),
        )
        assertEquals(
            "http://n:8096/videos/g/master.m3u8?a=1&ApiKey=already",
            JellyfinApi.absoluteTranscodingUrl("http://n:8096", "/videos/g/master.m3u8?a=1&ApiKey=already", "tok"),
        )
    }

    // ---------------- M4：进度上报 / 服务器续播 / 内嵌字幕 ----------------

    @Test fun `playingBody 组装与 ticks 换算`() {
        val body = JellyfinApi.playingBody("item1", "psid", 1234L, isPaused = false, durationMs = 5000L)
        val o = JSONObject(body)
        assertEquals("item1", o.getString("ItemId"))
        assertEquals(12_340_000L, o.getLong("PositionTicks"))
        assertEquals(false, o.getBoolean("IsPaused"))
        assertEquals("psid", o.getString("PlaySessionId"))
        assertEquals(50_000_000L, o.getLong("DurationTicks"))
    }

    @Test fun `playingBody 无 playSessionId 不写字段且负位置钳零`() {
        val o = JSONObject(JellyfinApi.playingBody("item1", null, -5L, isPaused = true))
        assertFalse(o.has("PlaySessionId"))
        assertEquals(0L, o.getLong("PositionTicks"))
        assertTrue(o.getBoolean("IsPaused"))
    }

    @Test fun `进度三个端点 URL`() {
        // Start 是 POST /Sessions/Playing（PlaystateController.ReportPlaybackStart）；旧断言的
        // /Sessions/Playing/Start 是 Emby 旧路由，Jellyfin 12.2.0 恒 404（缺陷 D1 钉位）
        assertEquals("http://nas:8096/Sessions/Playing", JellyfinApi.playingStartUrl("http://nas:8096"))
        assertEquals("http://nas:8096/Sessions/Playing/Progress", JellyfinApi.playingProgressUrl("http://nas:8096/"))
        assertEquals("http://nas:8096/Sessions/Playing/Stopped", JellyfinApi.playingStoppedUrl("http://nas:8096"))
    }

    @Test fun `itemMetadataUrl 带 MediaSources 与 MediaStreams 字段`() {
        assertEquals(
            "http://nas:8096/Users/u1/Items/it1?Fields=MediaSources,MediaStreams",
            JellyfinApi.itemMetadataUrl("http://nas:8096/", "u1", "it1"),
        )
    }

    @Test fun `subtitleVttUrl 组装`() {
        val url = JellyfinApi.subtitleVttUrl("http://nas:8096", "it1", "ms1", 3, "tk")
        assertEquals("http://nas:8096/Videos/it1/ms1/Subtitles/3/0/Stream.vtt?api_key=tk", url)
    }

    @Test fun `parseResumePositionMs ticks 换算与容错`() {
        assertEquals(1234L, JellyfinApi.parseResumePositionMs("""{"UserData":{"PlaybackPositionTicks":12340000}}"""))
        assertEquals(0L, JellyfinApi.parseResumePositionMs("""{"UserData":{}}"""))
        assertEquals(0L, JellyfinApi.parseResumePositionMs("""{}"""))
        assertEquals(0L, JellyfinApi.parseResumePositionMs("""not json"""))
    }

    @Test fun `parseMediaStreams 文本与图形字幕分流`() {
        val json = """
        {"MediaSources":[{"Id":"ms1","MediaStreams":[
            {"Index":1,"Type":"Subtitle","Codec":"subrip","IsTextSubtitleStream":true,
             "DisplayTitle":"中文 (中文)","Language":"chi"},
            {"Index":2,"Type":"Subtitle","Codec":"pgssub","IsTextSubtitleStream":false,
             "DisplayTitle":"PGS","Language":"chi"},
            {"Index":3,"Type":"Audio","Codec":"aac"}
        ]}]}
        """.trimIndent()
        val parsed = JellyfinApi.parseMediaStreams(json)
        assertEquals("ms1", parsed.mediaSourceId)
        assertEquals(2, parsed.streams.size)
        val text = parsed.streams[0]
        assertEquals(1, text.index)
        assertTrue(text.isText)
        assertEquals("中文 (中文)", text.displayTitle)
        assertEquals("chi", text.language)
        assertFalse(parsed.streams[1].isText)
    }

    @Test fun `parseMediaStreams 空与畸形安全`() {
        assertEquals(0, JellyfinApi.parseMediaStreams("""{"MediaSources":[]}""").streams.size)
        assertNull(JellyfinApi.parseMediaStreams("""{}""").mediaSourceId)
        assertEquals(0, JellyfinApi.parseMediaStreams("""not json""").streams.size)
        assertEquals(0L, JellyfinApi.parseMediaStreams("""{"MediaSources":[]}""").videoBitrate)
    }

    @Test fun `parseMediaStreams 顺带解析视频源码率`() {
        // 2026-10-08：清晰度面板「档位 ≥ 源码率隐藏」的判据来源（首个视频流 BitRate）
        val json = """
          {"MediaSources":[{"Id":"ms1","MediaStreams":[
            {"Index":0,"Type":"Video","Codec":"hevc","BitRate":3124018},
            {"Index":1,"Type":"Audio","Codec":"aac","BitRate":189891},
            {"Index":2,"Type":"Subtitle","Codec":"subrip","IsTextSubtitleStream":true}
          ]}]}
        """.trimIndent()
        val parsed = JellyfinApi.parseMediaStreams(json)
        assertEquals(3124018L, parsed.videoBitrate)
        assertEquals(1, parsed.streams.size)   // 字幕流照常解析，视频/音频不入字幕列
        assertEquals(1, parsed.streams.size)   // 字幕流照常解析，视频/音频不入字幕列
    }

    @Test fun `内嵌文本字幕转 vtt 侧载候选`() {
        val streams = listOf(
            JellyfinSubtitleStream(1, "中文", "chi", isText = true),
            JellyfinSubtitleStream(2, "PGS", "chi", isText = false),
        )
        val list = JellyfinApi.embeddedSubtitleCandidates("http://nas:8096", "it1", "ms1", "tk", streams)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("jellyfin-embedded-1.vtt", c.path)
        // i18n Task 9②：dataName 只存服务器原始标题，「[内嵌] 」前缀改由 UI 侧资源拼装
        assertEquals("中文", c.displayName)
        assertTrue(c.embedded)
        assertEquals(1, c.trackIndex)
        assertEquals("zh-CN", c.languageTag)
        assertFalse(c.isExact)
        assertEquals(SubtitleMatcher.SCORE_OTHER_CAP, c.score)   // 永不自动选
        assertTrue(c.remoteUrl!!.endsWith("Subtitles/1/0/Stream.vtt?api_key=tk"))
    }

    @Test fun `内嵌候选无图形字幕且语言未知时原样保留`() {
        val list = JellyfinApi.embeddedSubtitleCandidates(
            "http://nas:8096", "it1", "ms1", "tk",
            listOf(JellyfinSubtitleStream(5, "Undetermined", null, isText = true)),
        )
        assertEquals("jellyfin-embedded-5.vtt", list[0].path)
        assertNull(list[0].languageTag)
    }

    // ---- 通知栏海报 URL(spec 2026-10-10 §4.2:定宽小图 + api_key 与 streamUrl 同口径)----

    @Test
    fun primaryImageUrl_fixedWidthWithApiKey() {
        // URLEncoder 空格编码为 + 而非 %20(brief 原断言 %20 实跑 ComparisonFailure 实证);
        // Jellyfin 端点两者均接受,实现与 streamUrl 同用 URLEncoder 故口径一致
        assertEquals(
            "http://192.168.1.50:8096/Items/ITEM+1/Images/Primary?maxWidth=360&api_key=tok123",
            JellyfinApi.primaryImageUrl("http://192.168.1.50:8096/", "ITEM 1", "tok123"),
        )
        assertTrue(JellyfinApi.primaryImageUrl("192.168.1.50:8096", "i", "t").endsWith("?maxWidth=360&api_key=t"))
    }
}
