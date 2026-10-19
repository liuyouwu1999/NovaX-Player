package com.novax.app.data.cast

import java.net.URI

/**
 * 投屏控制地址（controlURL）绝对化工具（rework3 修复 3，2026-10-16）。
 *
 * 根因链：部分渲染器（Windows「UPnP 设备主机」upnphost 一类嵌入式栈）在描述 XML 里给出
 * 无 scheme 的相对 controlURL（或相对 <URLBase>），jUPnP 发现轨把 controlURI 原样透出
 * （形如 /upnph…）；CastSession 把它交给 SoapExecutor → OkHttp `Request.Builder().url()`
 * 抛 IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was
 * found for /upnph…")——该 IAE 发生在 runCatching 之外、也不是 SoapException，CastSession
 * 的 catch-all 把它当 Error 文案 toast（「投屏已结束：Expected URL scheme …」）并塌会话。
 * 纪律：喂给 SOAP/电视的地址一律绝对且 scheme=http(s)；相对路径必须先对设备描述 XML 的
 * LOCATION（SSDP 宣告的绝对地址）resolve（UPnP 1.0 设备架构 §2.3 URLBase 规则）。
 */
object CastUri {

    private fun hasHttpScheme(u: String): Boolean =
        u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)

    /** 已是绝对 http(s) 地址（可直接作 OkHttp/SOAP 目标） */
    fun isAbsoluteHttp(uri: String?): Boolean = !uri.isNullOrBlank() && hasHttpScheme(uri.trim())

    /**
     * 取 URL 的 host（BUG-C-R11-02）：出流地址选择需要渲染设备 IP 做「同网段/路由探测」——
     * 渲染器 IP 就藏在 controlURL 的 host 里。畸形/无 host 返回 null（选择器自动退兜底策略）。
     */
    fun hostOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        return runCatching { URI(uri.trim()).host?.takeIf { it.isNotEmpty() } }.getOrNull()
    }

    /**
     * 相对/无 scheme 的 UPnP 地址按描述 XML 地址解析为绝对 http(s) 地址。
     * - 已绝对：可解析则原样返回（畸形不可解析 → null，OkHttp 同样拒收）；
     * - 相对：对 [descriptorUrl] resolve（RFC 3986；绝对路径替换 base 整个 path，符合
     *   「相对 controlURL 挂在设备根下」的 UPnP 惯例）；
     * - 无法得到绝对 http(s)（无 base、base 非 http(s)、畸形地址）返回 null——调用方必须
     *   拒绝该设备/入口，不得把相对路径继续下传。
     */
    fun resolveAgainstBase(uri: String, descriptorUrl: String?): String? {
        val u = uri.trim()
        if (u.isEmpty()) return null
        if (hasHttpScheme(u)) {
            return runCatching { URI(u).toString() }.getOrNull()?.takeIf { hasHttpScheme(it) }
        }
        val base = descriptorUrl?.trim()?.takeIf { hasHttpScheme(it) } ?: return null
        return runCatching {
            URI(base).resolve(URI(u.replace(" ", "%20"))).toString()
        }.getOrNull()?.takeIf { hasHttpScheme(it) }
    }

    /**
     * 发现轨三级解析链（rework3 修复 5，2026-10-16）：相对 controlURL 依次按
     * ① 描述 XML 内 <URLBase>（UPnP 1.0 §2.3：声明则其下所有相对地址都以它为 base）→
     * ② 描述 XML 的 LOCATION（SSDP 宣告的绝对地址，逐段 resolve 等价「按设备根派生」）→
     * ③ SSDP NOTIFY 来源地址（调用方构造成 http://<来源IP> 形式传入）
     * 逐级尝试 resolve，**任一级得到绝对 http(s) 即返回——默认修复而非丢弃**。Windows
     * WMP/upnphost 类设备描述 XML 常规就带相对 controlURL，上一轮「解析不出即不入列」的
     * 硬排除把它们整体踢出发现列表（用户真机新包见不到/连不上 WMP），主流 UPnP 栈对这类
     * 设备一律按上述链补全。全级失败返回 null（调用方不入列并留日志；LOCATION 是 UPnP 1.0
     * 强制字段几乎不可能缺，此情形仅理论存在）。已绝对的 controlURL 原样通过；空/畸形 null。
     */
    fun resolveControlUri(
        uri: String?,
        urlBase: String? = null,
        descriptorUrl: String? = null,
        ssdpSourceBase: String? = null,
    ): String? {
        if (uri.isNullOrBlank()) return null
        if (isAbsoluteHttp(uri)) {
            return runCatching { URI(uri.trim()).toString() }.getOrNull()?.takeIf { isAbsoluteHttp(it) }
        }
        return listOf(urlBase, descriptorUrl, ssdpSourceBase)
            .mapNotNull { base -> resolveAgainstBase(uri, base) }
            .firstOrNull()
    }
}
