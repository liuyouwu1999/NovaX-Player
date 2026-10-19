package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rework3 修复 3（2026-10-16 用户真机报错「Expected URL scheme 'http' or 'https' but no
 * scheme was found for /upnph…」）：锁死投屏控制地址的绝对化/校验行为——相对 controlURL
 * 必须先对描述 XML LOCATION resolve 成绝对 http(s) 才允许进 SOAP 链路。
 */
class CastUriTest {

    // ── isAbsoluteHttp ──

    @Test fun `绝对http与https均判定为绝对`() {
        assertTrue(CastUri.isAbsoluteHttp("http://192.168.1.8:49152/upnp/control/AVT1"))
        assertTrue(CastUri.isAbsoluteHttp("https://192.168.1.8/control"))
    }

    @Test fun `相对路径与空值均非绝对`() {
        assertFalse(CastUri.isAbsoluteHttp("/upnphost/udhisapi.dll?content=uuid:x"))
        assertFalse(CastUri.isAbsoluteHttp("upnp/control/AVT1"))
        assertFalse(CastUri.isAbsoluteHttp(""))
        assertFalse(CastUri.isAbsoluteHttp(null))
    }

    @Test fun `非http_scheme判定为非绝对`() {
        assertFalse(CastUri.isAbsoluteHttp("ftp://192.168.1.8/ctl"))
        assertFalse(CastUri.isAbsoluteHttp("file:///x"))
    }

    // ── resolveAgainstBase：用户真机同款（相对 /upnph… 路径 + 描述 XML LOCATION）──

    @Test fun `相对绝对路径按LOCATION解析为设备根下绝对地址`() {
        val got = CastUri.resolveAgainstBase(
            "/upnphost/udhisapi.dll?content=uuid:1234",
            "http://192.168.1.8:2869/description.xml",
        )
        assertEquals("http://192.168.1.8:2869/upnphost/udhisapi.dll?content=uuid:1234", got)
    }

    @Test fun `相对相对路径按LOCATION逐段解析`() {
        val got = CastUri.resolveAgainstBase("ctl/AVT1", "http://192.168.1.8:49152/dd.xml")
        assertEquals("http://192.168.1.8:49152/ctl/AVT1", got)
    }

    @Test fun `已绝对地址原样返回不二次改写`() {
        val abs = "http://192.168.1.9:49152/upnp/control/AVT1"
        assertEquals(abs, CastUri.resolveAgainstBase(abs, "http://10.0.0.1/dd.xml"))
    }

    @Test fun `controlURL带空格转义为20再解析`() {
        val got = CastUri.resolveAgainstBase("/upnp control/AVT 1", "http://192.168.1.8/dd.xml")
        assertEquals("http://192.168.1.8/upnp%20control/AVT%201", got)
    }

    @Test fun `https基地址解析出https绝对地址`() {
        val got = CastUri.resolveAgainstBase("/control", "https://nas.example.com:8443/dd.xml")
        assertEquals("https://nas.example.com:8443/control", got)
    }

    // ── 无法绝对化 → null（调用方必须拒设备/拒入口，不得下传相对路径）──

    @Test fun `无基地址的相对路径返回null`() {
        assertNull(CastUri.resolveAgainstBase("/upnphost/x", null))
        assertNull(CastUri.resolveAgainstBase("/upnphost/x", ""))
    }

    @Test fun `基地址无scheme返回null不放行`() {
        assertNull(CastUri.resolveAgainstBase("/upnphost/x", "/description.xml"))
        assertNull(CastUri.resolveAgainstBase("/upnphost/x", "192.168.1.8/dd.xml"))
    }

    @Test fun `ftp基地址返回null不放行`() {
        assertNull(CastUri.resolveAgainstBase("/upnphost/x", "ftp://192.168.1.8/dd.xml"))
    }

    @Test fun `空controlURL返回null`() {
        assertNull(CastUri.resolveAgainstBase("  ", "http://192.168.1.8/dd.xml"))
        assertNull(CastUri.resolveAgainstBase("", "http://192.168.1.8/dd.xml"))
    }

    @Test fun `畸形controlURL返回null不抛`() {
        assertNull(CastUri.resolveAgainstBase("http://exa mple^", "http://192.168.1.8/dd.xml"))
    }

    // ── BUG-C-R11-02：hostOf——出流地址选择需要渲染器 IP（controlURL host） ──
    @Test fun `hostOf取绝对http地址的host`() {
        assertEquals("192.168.1.8", CastUri.hostOf("http://192.168.1.8:2869/upnphose"))
        assertEquals("tv.local", CastUri.hostOf("http://tv.local/dd.xml"))
    }

    @Test fun `hostOf畸形或无host返回null不抛`() {
        assertNull(CastUri.hostOf(null))
        assertNull(CastUri.hostOf(""))
        assertNull(CastUri.hostOf("/upnph…"))
        assertNull(CastUri.hostOf("http://exa mple^"))
    }

    // ── resolveControlUri：发现轨三级解析链（rework3 修复 5，WMP 误踢回归）──
    // Windows WMP/upnphost 描述 XML 常规带相对 controlURL——链上任一级成功即入列，不再硬排除。

    @Test fun `WMP风格相对controlURL无URLBase按LOCATION解析成功`() {
        val got = CastUri.resolveControlUri(
            "/upnphost/AVTransport/control",
            urlBase = null,
            descriptorUrl = "http://192.168.1.8:2869/dd.xml",
        )
        assertEquals("http://192.168.1.8:2869/upnphost/AVTransport/control", got)
    }

    @Test fun `带URLBase变体按URLBase解析优先于LOCATION`() {
        // 端口/路径双差异：落在 49152/upnphost 即证明用的是 URLBase 而非 LOCATION(2869)
        val got = CastUri.resolveControlUri(
            "/upnphost/AVTransport/control",
            urlBase = "http://192.168.1.8:49152/upnphost/",
            descriptorUrl = "http://192.168.1.8:2869/dd.xml",
        )
        assertEquals("http://192.168.1.8:49152/upnphost/AVTransport/control", got)
    }

    @Test fun `相对段controlURL按URLBase逐段挂接不落LOCATION`() {
        // URLBase 惯例带尾斜杠（描述 XML 实形态）；相对段挂其下而非 LOCATION(2869) 根
        val got = CastUri.resolveControlUri(
            "control/AVT1",
            urlBase = "http://192.168.1.8:49152/upnphost/",
            descriptorUrl = "http://192.168.1.8:2869/dd.xml",
        )
        assertEquals("http://192.168.1.8:49152/upnphost/control/AVT1", got)
    }

    @Test fun `URLBase畸形时逐级落到LOCATION不丢设备`() {
        val got = CastUri.resolveControlUri(
            "/control",
            urlBase = "ftp://192.168.1.8/base",
            descriptorUrl = "http://192.168.1.8:2869/dd.xml",
        )
        assertEquals("http://192.168.1.8:2869/control", got)
    }

    @Test fun `SSDP来源地址作第三级兜底`() {
        val got = CastUri.resolveControlUri(
            "/control",
            urlBase = null,
            descriptorUrl = null,
            ssdpSourceBase = "http://192.168.1.99:2869",
        )
        assertEquals("http://192.168.1.99:2869/control", got)
    }

    @Test fun `三级全空返回null调用方不入列`() {
        assertNull(CastUri.resolveControlUri("/upnphost/AVTransport/control"))
        assertNull(CastUri.resolveControlUri("ctl/AVT1", null, null, null))
        assertNull(CastUri.resolveControlUri(null, "http://x/", "http://y/", "http://z/"))
        assertNull(CastUri.resolveControlUri("", "http://x/"))
    }

    @Test fun `已绝对controlURL走链原样返回不换级改写`() {
        val abs = "http://192.168.1.9:49152/upnp/control/AVT1"
        assertEquals(abs, CastUri.resolveControlUri(abs, "http://10.0.0.1/", "http://192.168.1.8/dd.xml"))
    }
}
