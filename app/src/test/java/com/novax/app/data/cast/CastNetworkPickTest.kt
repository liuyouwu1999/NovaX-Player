package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-C-R11-02（2026-10-16 WMP 取流失败返工）：出流地址选择纯逻辑用例。
 * 旧策略「枚举序首个站点内 IPv4」在手机挂 VPN 时选中 tun 地址（枚举序常居首），
 * WMP 在局域网按该 host 回连手机路由不可达——选择器必须以渲染设备 IP 为锚点。
 */
class CastNetworkPickTest {
    private fun f(name: String, addr: String, prefix: Int = 24, p2p: Boolean = false) =
        CastNetwork.Iface4(name, addr, prefix, p2p)

    @Test fun `挂VPN时_渲染器同网段胜过枚举序首位的tun地址`() {
        // 真机形态：tun0(10.8.0.2) 枚举在 wlan0 前；渲染器 192.168.1.100 在 Wi-Fi 网段
        val ifaces = listOf(
            f("tun0", "10.8.0.2", 24, p2p = true),
            f("wlan0", "192.168.1.23", 24),
        )
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4("192.168.1.100", null, ifaces))
    }

    @Test fun `无渲染器信息时_非隧道站点内优先于tun`() {
        // 旧 siteLocalIpv4() 的致命形态：只看 isSiteLocalAddress 且取首个 → 选中 10.8.0.2
        val ifaces = listOf(
            f("tun0", "10.8.0.2", 24, p2p = true),
            f("wlan0", "192.168.1.23", 24),
        )
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4(null, null, ifaces))
    }

    @Test fun `渲染器在VPN网段内时_隧道地址是正确答案`() {
        val ifaces = listOf(
            f("tun0", "10.8.0.2", 24, p2p = true),
            f("wlan0", "192.168.1.23", 24),
        )
        assertEquals("10.8.0.2", CastNetwork.pickOutboundIpv4("10.8.0.99", null, ifaces))
    }

    @Test fun `跨网段时_路由探测出口可用`() {
        // AP 隔离/多 AP：渲染器 192.168.2.x 与 wlan 不同网段 → 同网段无候选 → 走探测
        val ifaces = listOf(
            f("tun0", "10.8.0.2", 24, p2p = true),
            f("wlan0", "192.168.1.23", 24),
        )
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4("192.168.2.100", "192.168.1.23", ifaces))
    }

    @Test fun `跨网段无探测时_退非隧道站点内兜底`() {
        // 同网段无候选、探测无结果：只能退最优猜测（非隧道站点内）——与旧策略兜底对齐
        val ifaces = listOf(
            f("tun0", "10.8.0.2", 24, p2p = true),
            f("wlan0", "192.168.1.23", 24),
        )
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4("172.30.1.5", null, ifaces))
    }

    @Test fun `仅剩隧道站点内地址时_仍宣告之而非返回null`() {
        // 全 VPN 环境（Wi-Fi 关闭）：隧道地址是唯一候选——兜底与旧策略行为对齐不劣化
        //（返回 null 会报「投屏需要连接 Wi-Fi」，但隧道可达渲染器时白白拒投）
        val ifaces = listOf(f("tun0", "10.8.0.2", 24, p2p = true))
        assertEquals("渲染器未知也兜底宣告", "10.8.0.2", CastNetwork.pickOutboundIpv4(null, null, ifaces))
        assertEquals("渲染器在 VPN 网段内", "10.8.0.2", CastNetwork.pickOutboundIpv4("10.8.0.7", null, ifaces))
    }

    @Test fun `物理网卡名打破同分`() {
        // 两个非隧道站点内候选（USB 共享网等）：wlan 命名优先
        val ifaces = listOf(
            f("rmnet_data0", "10.42.0.7", 24),
            f("wlan0", "192.168.1.23", 24),
        )
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4(null, null, ifaces))
    }

    @Test fun `前缀不匹配不算同网段`() {
        // /16 与 /24 掩码口径：192.168.2.x 不在 wlan0 /24 内——不走同网段分支，
        // 无探测时退非隧道站点内兜底（wlan0 是唯一候选）
        val ifaces = listOf(f("wlan0", "192.168.1.23", 24))
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4("192.168.2.100", null, ifaces))
        assertEquals("同 /24 才命中", "192.168.1.23", CastNetwork.pickOutboundIpv4("192.168.1.100", null, ifaces))
    }

    @Test fun `畸形渲染器地址走兜底不抛异常`() {
        val ifaces = listOf(f("wlan0", "192.168.1.23", 24))
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4("not-an-ip", null, ifaces))
        assertEquals("192.168.1.23", CastNetwork.pickOutboundIpv4("999.1.1.1", null, ifaces))
    }

    @Test fun `探测结果0_0_0_0占位不采用`() {
        val ifaces = listOf(f("wlan0", "192.168.1.23", 24))
        assertEquals(
            CastNetwork.pickOutboundIpv4("172.30.1.5", "0.0.0.0", ifaces),
            "192.168.1.23",
        )
    }

    @Test fun `sameSubnet掩码边界`() {
        assertTrue(CastNetwork.sameSubnet("192.168.1.23", 24, "192.168.1.100"))
        assertFalse(CastNetwork.sameSubnet("192.168.1.23", 24, "192.168.2.100"))
        assertTrue("/0 全通", CastNetwork.sameSubnet("10.0.0.1", 0, "192.168.1.100"))
        assertTrue("/32 精确", CastNetwork.sameSubnet("10.0.0.1", 32, "10.0.0.1"))
        assertFalse("/32 精确不同址", CastNetwork.sameSubnet("10.0.0.1", 32, "10.0.0.2"))
    }

    @Test fun `真机siteLocalIpv4仍返回合法IPv4或null`() {
        val ip = CastNetwork.siteLocalIpv4()
        assertTrue("实际值: $ip", ip == null || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip))
    }
}
