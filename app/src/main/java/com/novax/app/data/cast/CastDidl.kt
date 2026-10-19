package com.novax.app.data.cast

/**
 * DIDL-Lite 最小集 + 字幕双声明（spec C13 §4.1）：
 * sec res（海思/晶晨/LG/索尼系识别）+ CaptionInfoEx（三星系识别）。
 * 只填必要字段——多填比少填更容易触发电视端解析 bug；字幕声明是唯一有意加入的非必需字段。
 */
object CastDidl {
    fun build(media: CastMedia, streamUrl: String, subtitleUrl: String?): String {
        val subRes = subtitleUrl?.let {
            """<res protocolInfo="http-get:*:application/x-subrip:*" sec:type="srt">${esc(it)}</res>"""
        } ?: ""
        val captionEx = subtitleUrl?.let {
            """<CaptionInfoEx sec:type="srt">${esc(it)}</CaptionInfoEx>"""
        } ?: ""
        val mime = CastMime.videoMimeOf(media.path)
        val duration = formatDuration(media.durationMs)
        return buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
            append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
            append("""xmlns:sec="http://www.sec.co.kr/dlna">""")
            append("""<item id="nv-1" parentID="0" restricted="1">""")
            append("<dc:title>").append(esc(media.title)).append("</dc:title>")
            append("""<upnp:class>object.item.videoItem</upnp:class>""")
            // rework3 修复 1（2026-10-16）：补 DLNA.ORG_CI=0（无转码直出）——DLNA 指南第 4 参数
            // OP/CI 两项必填；Windows「播放到设备」（WMP/upnphost）按 DLNA 栈校验 protocolInfo，
            // 缺 CI 时 SOAP 层照常接受 SetAVTransportURI/Play 但**静默拒播**（不 fault、不取流、
            // GetTransportInfo 停在 NO_MEDIA_PRESENT）= 用户实测「连上但不播」。OP=01 可拖动，
            // CI=0 字节级直通（本 App 中继不做转码），语义如实。
            append("""<res protocolInfo="http-get:*:""").append(mime)
                .append(""":DLNA.ORG_OP=01;DLNA.ORG_CI=0"""")
            if (media.sizeBytes > 0) append(""" size="${media.sizeBytes}"""")
            if (duration != null) append(""" duration="$duration"""")
            append(">").append(esc(streamUrl)).append("</res>")
            append(subRes)
            append(captionEx)
            append("</item></DIDL-Lite>")
        }
    }

    private fun formatDuration(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    private fun esc(s: String): String = UpnpXml.escape(s)
}
