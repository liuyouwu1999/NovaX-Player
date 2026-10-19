package com.novax.app.data.cast

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 出流地址选择（BUG-C-R11-02，2026-10-16 真机 WMP 取流失败返工）。
 *
 * 根因链：旧 [siteLocalIpv4] 取「枚举序首个站点内 IPv4」——手机挂 VPN 时 tun 虚拟网卡
 * 的 10.x/192.168.x 地址常排枚举首位被选中，SetAVTransportURI 里 CurrentURI 的 host 即
 * VPN 地址；WMP 在局域网按该 IP 回连手机取流路由不可达（PC 无 VPN 回程路由）→ 探流失败
 * → SOAP 报错 → CastSession 映射为「电视无法取流，请检查网络」。本地/NAS 片源都复现：
 * 取流 URL 的 host 恒为手机本机地址，与片源位置无关。
 *
 * 修法（三层递进，纯逻辑 [pickOutboundIpv4] 抽出可单测）：
 * ① **同网段优先**：以渲染设备 IP 为目标做子网比对（本机地址 & 掩码 == 渲染器 & 掩码）——
 *    同网段 = 局域网回程必然可达；非隧道接口优先，wlan/eth 次之（VPN tun 是为「渲染器也
 *    在 VPN 网段内」的合法形态保留的兜底）；
 * ② **路由探测**：`DatagramSocket.connect(rendererIp)`（UDP connect 只查内核路由表不发包）
 *    取出口源地址——渲染器跨网段（AP 隔离/多 AP）时的真实可达地址；
 * ③ **兜底退化为旧策略**：非隧道站点内 IPv4（wlan/eth 优先）→ 任一站点内 → null（上层报
 *    「投屏需要连接 Wi-Fi」）。
 *
 * 监听侧不动：NvCastStreamServer 仍绑 0.0.0.0（Holder 组装处），选出的地址只用于对外
 * 宣告的 URL host，任意本机接口都能应答。
 */
object CastNetwork {

    /** 局域网 IPv4 候选快照（纯数据，单测可造）：接口名 / 地址 / 前缀长 / 点对点(VPN tun 常带) */
    data class Iface4(val name: String, val address: String, val prefixLen: Int, val pointToPoint: Boolean)

    /** 隧道/虚拟网卡名特征：tun/tap/wg(ppp)/ipsec/gqdtun(clash)/utun 等（大小写不敏感） */
    private val TUNNEL_NAME = Regex("(tun|tap|wg|ppp|ipsec|utun|gqdtun|clash|proxy)", RegexOption.IGNORE_CASE)

    /** 有线/Wi-Fi 物理网卡名优先（Android wlan0；模拟器 eth0） */
    private val PHYSICAL_NAME = Regex("^(wlan|eth|en)", RegexOption.IGNORE_CASE)

    /** 对外宣告地址：渲染器 IP 可知时做同网段/路由探测，不可知时兜底站点内 */
    fun bestLocalAddressFor(rendererIp: String?): String? {
        val ifaces = snapshotIfaces()
        val probe = rendererIp?.let { routeProbe(it) }
        return pickOutboundIpv4(rendererIp, probe, ifaces)
    }

    /** 兼容旧入口（= 无渲染器信息的兜底选择）；真机带 VPN 时由 [bestLocalAddressFor] 排除 tun */
    fun siteLocalIpv4(): String? = bestLocalAddressFor(null)

    /**
     * 纯选择逻辑（单测注入假接口集）。
     * @param rendererIp 渲染设备 IP（controlURL host；null/畸形 = 未知）
     * @param routeProbe 以渲染器为目标 UDP connect 得到的出口源地址（可 null）
     */
    internal fun pickOutboundIpv4(rendererIp: String?, routeProbe: String?, ifaces: List<Iface4>): String? {
        val isTunnel: (Iface4) -> Boolean = { it.pointToPoint || TUNNEL_NAME.containsMatchIn(it.name) }
        // ① 同网段候选：非隧道 > 隧道，物理网卡 > 其他，取序首
        if (rendererIp != null && isInetAddress(rendererIp)) {
            ifaces.filter { sameSubnet(it.address, it.prefixLen, rendererIp) }
                .minByOrNull { ifn -> (if (isTunnel(ifn)) 2 else 0) + (if (PHYSICAL_NAME.containsMatchIn(ifn.name)) 0 else 1) }
                ?.let { return it.address }
        }
        // ② 路由探测出口（内核路由表的真实答案；0.0.0.0 = connect 失败占位不用）
        if (routeProbe != null && routeProbe != "0.0.0.0" && isInetAddress(routeProbe)) return routeProbe
        // ③ 兜底：非隧道站点内（物理优先）→ 任一站点内 → null
        val siteLocal = ifaces.filter { isSiteLocalV4(it.address) }
        siteLocal.filterNot(isTunnel)
            .minByOrNull { if (PHYSICAL_NAME.containsMatchIn(it.name)) 0 else 1 }
            ?.let { return it.address }
        return siteLocal.minByOrNull { if (PHYSICAL_NAME.containsMatchIn(it.name)) 0 else 1 }?.address
    }

    /** 真机枚举：up + 非回环接口的全部 IPv4（prefixLen 取该地址所属 interfaceAddress） */
    private fun snapshotIfaces(): List<Iface4> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                val prefix = ni.interfaceAddresses
                    .firstOrNull { ia -> ia.address is Inet4Address }?.networkPrefixLength?.toInt() ?: -1
                val p2p = runCatching { ni.isPointToPoint }.getOrDefault(false)
                ni.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { v4 -> v4.hostAddress?.let { Iface4(ni.name, it, prefix, p2p) } }
            }
            .toList()
    }.getOrDefault(emptyList())

    /** UDP connect 路由探测：不实际发包，只让内核路由表选出出口（源地址、按渲染器目标） */
    private fun routeProbe(rendererIp: String?): String? = runCatching {
        if (!isInetAddress(rendererIp ?: return null)) return null
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(rendererIp), 9)   // port 9 discard，UDP connect 不发包
            (s.localAddress as? Inet4Address)?.hostAddress
        }
    }.getOrNull()

    /** address/prefix 是否容纳 [ip]（IPv4 掩码比对；prefix 越界按 /32 处理） */
    internal fun sameSubnet(address: String, prefixLen: Int, ip: String): Boolean {
        val a = inet4ToLong(address) ?: return false
        val b = inet4ToLong(ip) ?: return false
        val p = prefixLen.coerceIn(0, 32)
        val mask = if (p == 0) 0L else (-1L shl (32 - p)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    private fun inet4ToLong(ip: String): Long? {
        val parts = ip.split(".").map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it < 0 || it > 255 }) return null
        return ((parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or (parts[2].toLong() shl 8) or parts[3].toLong())
    }

    private fun isInetAddress(ip: String): Boolean = inet4ToLong(ip) != null

    /** 站点内 IPv4（10/8、172.16/12、192.168/16）；与 Inet4Address.isSiteLocalAddress 同口径 */
    private fun isSiteLocalV4(ip: String): Boolean {
        val v = inet4ToLong(ip) ?: return false
        val b1 = (v ushr 24).toInt()
        val b2 = ((v ushr 16) and 0xFF).toInt()
        return b1 == 10 || (b1 == 172 && b2 in 16..31) || (b1 == 192 && b2 == 168)
    }
}
