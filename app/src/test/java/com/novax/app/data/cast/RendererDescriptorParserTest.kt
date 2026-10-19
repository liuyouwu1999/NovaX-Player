package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RendererDescriptorParserTest {
    private val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
 <specVersion><major>1</major><minor>0</minor></specVersion>
 <device>
  <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
  <friendlyName>客厅的小米电视</friendlyName>
  <serviceList>
   <service>
     <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
     <controlURL>/upnp/control/RCS0</controlURL>
   </service>
   <service>
     <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
     <controlURL>/upnp/control/AVT1</controlURL>
   </service>
  </serviceList>
 </device></root>"""

    @Test fun `解析名称与相对controlURL`() {
        val (name, control) = RendererDescriptorParser.parse(xml, "http://192.168.1.8:49152/desc.xml")!!
        assertEquals("客厅的小米电视", name)
        assertEquals("http://192.168.1.8:49152/upnp/control/AVT1", control)
    }

    @Test fun `顺带解析RCS音量控制地址`() {
        val r = RendererDescriptorParser.parse(xml, "http://192.168.1.8:49152/desc.xml")!!
        assertEquals("http://192.168.1.8:49152/upnp/control/RCS0", r.third)
    }

    @Test fun `无AVTransport返回null`() {
        assertNull(
            RendererDescriptorParser.parse(
                xml.replace("AVTransport", "FakeSvc"),
                "http://x/desc.xml",
            ),
        )
    }

    @Test fun `畸形XML返回null不抛`() {
        assertNull(RendererDescriptorParser.parse("<root><unclosed", "http://x/desc.xml"))
    }

    // rework3 修复 3（2026-10-16）：描述地址本身相对/无 scheme 时 resolve 结果仍无 scheme
    //（喂给 OkHttp 即「Expected URL scheme…」）——解析器必须拒绝，不能产出不可控设备
    @Test fun `描述地址无scheme解析结果非绝对返回null`() {
        assertNull(RendererDescriptorParser.parse(xml, "/desc.xml"))
        assertNull(RendererDescriptorParser.parse(xml, "192.168.1.8:49152/desc.xml"))
    }
}
