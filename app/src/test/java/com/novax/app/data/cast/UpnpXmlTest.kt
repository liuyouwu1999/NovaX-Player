package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UpnpXml 工具回归（原 UpnpSoapTest 的解析用例全数保留；envelope 线格式两例随手搓
 * SOAP 退役删除，线格式断言由 UpnpControlTest 经 jUPnP 标准 SOAPActionProcessor 承接）。
 */
class UpnpXmlTest {
    @Test fun `时间双向转换`() {
        assertEquals("1:02:03", UpnpXml.fmtTime(3_723_000L))
        assertEquals(3_723_000L, UpnpXml.parseTime("1:02:03"))
        assertEquals(90_000L, UpnpXml.parseTime("0:01:30"))
    }

    @Test fun `脏值容错`() {
        assertEquals(0L, UpnpXml.parseTime("NOT_IMPLEMENTED"))
        assertEquals(0L, UpnpXml.parseTime(""))
        assertEquals(0L, UpnpXml.parseTime("abc"))
        assertEquals(0L, UpnpXml.parseTime("1:2"))
    }

    @Test fun `取标签无视命名空间前缀`() {
        val resp = """<u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>0:04:00</TrackDuration><RelTime>0:01:30</RelTime></u:GetPositionInfoResponse>"""
        assertEquals("0:04:00", UpnpXml.tagValue(resp, "TrackDuration"))
        assertEquals("0:01:30", UpnpXml.tagValue(resp, "RelTime"))
        assertNull(UpnpXml.tagValue(resp, "AbsTime"))
    }

    @Test fun `取标签容忍带属性的标签`() {
        // review M2：部分电视给 <RelTime stamp="...">0:01:30</RelTime>
        assertEquals("0:01:30", UpnpXml.tagValue("""<RelTime stamp="x">0:01:30</RelTime>""".trim(), "RelTime"))
    }

    @Test fun `取标签容忍电视吐出的多余换行`() {
        val resp = "<TrackDuration>\n  0:04:00 </TrackDuration>"
        assertEquals("0:04:00", UpnpXml.tagValue(resp, "TrackDuration")?.trim())
    }
}
