package com.novax.app.data.cast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI

/**
 * 手动添加轨（spec C13 §5 空态兜底 / §8 模拟器测试通道）：解析渲染器描述 XML，
 * 取 friendlyName 与 AVTransport controlURL（相对地址按描述 XML 的 URL 解析为绝对地址）。
 * 与 jUPnP 发现轨汇入同一 CastDevice 模型；手动轨 UDN 由 URL hash 派生（CastDiscovery）。
 */
object RendererDescriptorParser {

    /** @return (friendlyName, AVTransport controlURL, RCS controlURL?)；无 AVTransport/解析失败返回 null */
    fun parse(xml: String?, descriptorUrl: String): Triple<String, String, String?>? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var friendlyName: String? = null
            var avtControlRelative: String? = null
            var rcsControlRelative: String? = null
            var inService = false
            var serviceType: String? = null
            var controlUrl: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "friendlyName" -> friendlyName = parser.nextText()
                        "service" -> {
                            inService = true
                            serviceType = null
                            controlUrl = null
                        }
                        "serviceType" -> if (inService) serviceType = parser.nextText()
                        "controlURL" -> if (inService) controlUrl = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "service" -> {
                            if (controlUrl != null) {
                                when {
                                    serviceType?.contains("AVTransport") == true ->
                                        avtControlRelative = controlUrl
                                    serviceType?.contains("RenderingControl") == true ->
                                        rcsControlRelative = controlUrl
                                }
                            }
                            inService = false
                        }
                    }
                }
                event = parser.next()
            }
            val control = avtControlRelative ?: return null
            val base = URI(descriptorUrl)
            // 部分电视给的 controlURL 本身是绝对地址，resolve 原样保留
            val absolute = base.resolve(control.replace(" ", "%20")).toString()
            // rework3 修复 3（2026-10-16）：描述地址本身相对/畸形时 resolve 结果仍无 scheme
            //（喂给 OkHttp 即「Expected URL scheme…」）——非绝对 http(s) 一律拒绝入列
            if (!CastUri.isAbsoluteHttp(absolute)) return null
            val rcs = rcsControlRelative?.let { runCatching {
                base.resolve(it.replace(" ", "%20")).toString()
            }.getOrNull()?.takeIf { r -> CastUri.isAbsoluteHttp(r) } }
            // i18n Task 9：desc.xml 缺 friendlyName 时返回空串，「未知设备」展示名由 UI 侧回退
            Triple(friendlyName.orEmpty(), absolute, rcs)
        } catch (_: Exception) {
            null
        }
    }
}
