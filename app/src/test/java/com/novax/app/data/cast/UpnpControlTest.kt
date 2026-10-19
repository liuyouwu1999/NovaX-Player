package com.novax.app.data.cast

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceConfiguration
import org.jupnp.controlpoint.ControlPointImpl
import org.jupnp.binding.xml.UDA10ServiceDescriptorBinderImpl
import org.jupnp.model.NetworkAddress
import org.jupnp.model.message.IncomingDatagramMessage
import org.jupnp.model.message.OutgoingDatagramMessage
import org.jupnp.model.message.StreamRequestMessage
import org.jupnp.model.message.StreamResponseMessage
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.message.control.OutgoingActionRequestMessage
import org.jupnp.model.message.header.UpnpHeader
import org.jupnp.model.meta.RemoteService
import org.jupnp.protocol.ProtocolFactoryImpl
import org.jupnp.registry.RegistryImpl
import org.jupnp.transport.Router
import org.jupnp.transport.RouterException
import org.jupnp.transport.spi.InitializationException
import org.jupnp.transport.spi.SOAPActionProcessor
import org.jupnp.transport.spi.UpnpStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 返工⑬（2026-10-16 控制层迁移）：jUPnP ControlPoint 适配器全链路单测。
 *
 * 架位：真 jUPnP 控制面（ProtocolFactoryImpl + SendingAction + 标准 SOAPActionProcessor +
 * fault 解析）+ 假 Router 拦截 `router.send(StreamRequestMessage)`——SendingAction 只经此
 * 一个口子出网，等价「电视端」：按用例回 200 响应 / 500+SOAP fault / 非 2xx / 无响应。
 * WMP 风格 fixture = 带查询串的 upnphost controlURL + 二次转义 DIDL 入参。
 */
class UpnpControlTest {

    // ── 假「电视端」router：拦截 SendingAction 的唯一出网口 ──

    private class FakeRouter(
        val requests: CopyOnWriteArrayList<OutgoingActionRequestMessage>,
        val handler: (OutgoingActionRequestMessage) -> StreamResponseMessage?,
    ) : Router {
        override fun send(msg: StreamRequestMessage): StreamResponseMessage? {
            val req = msg as OutgoingActionRequestMessage
            requests += req
            return handler(req)
        }

        override fun send(msg: OutgoingDatagramMessage<*>) = Unit
        override fun received(msg: IncomingDatagramMessage<*>) = Unit
        override fun received(msg: UpnpStream) = Unit
        override fun broadcast(bytes: ByteArray) = Unit
        override fun enable() = false
        override fun disable() = false
        override fun shutdown() = Unit
        override fun isEnabled() = false
        override fun handleStartFailure(failure: InitializationException) = Unit
        override fun getActiveStreamServers(preferredAddress: InetAddress?): List<NetworkAddress> = emptyList()
        override fun getConfiguration(): UpnpServiceConfiguration = throw UnsupportedOperationException()
        override fun getProtocolFactory() = throw UnsupportedOperationException()
    }

    /** 真 jUPnP 控制面 + 假 router 的最小 UpnpService（测试毕必须 close，防 maintainer 线程悬挂） */
    private class Harness(
        handler: (OutgoingActionRequestMessage) -> StreamResponseMessage?,
    ) : UpnpService, AutoCloseable {
        val requests = CopyOnWriteArrayList<OutgoingActionRequestMessage>()
        val router = FakeRouter(requests, handler)
        val config: UpnpServiceConfiguration = object : org.jupnp.DefaultUpnpServiceConfiguration() {
            override fun getSoapActionProcessor(): SOAPActionProcessor = TolerantSoapActionProcessor()
        }
        // 属性名避开 JVM getter 与 UpnpService 接口方法的签名冲突（getRegistry 等）
        val pf = ProtocolFactoryImpl(this)
        val reg = RegistryImpl(this)
        val cp = ControlPointImpl(config, pf, reg)

        override fun getConfiguration(): UpnpServiceConfiguration = config
        override fun getControlPoint(): org.jupnp.controlpoint.ControlPoint = cp
        override fun getProtocolFactory(): org.jupnp.protocol.ProtocolFactory = pf
        override fun getRegistry(): org.jupnp.registry.Registry = reg
        override fun getRouter(): Router = router
        override fun startup() = Unit
        override fun shutdown() {
            runCatching { reg.shutdown() }
        }

        override fun close() {
            shutdown()
        }
    }

    /** WMP/upnphost 风格 controlURL（带查询串），发现层三级解析链产物 */
    private val wmpControlUri =
        "http://192.168.1.8:2869/upnphost/udhisapi.dll?content=uuid:8a7f3c&service=AVTransport"

    private fun executor(harness: Harness, timeoutMs: Long = 5000) =
        UpnpControlSoapExecutor(controlPointProvider = { harness.cp }, callTimeoutMs = timeoutMs, text = FakeCastText)

    private fun okResponse(service: String, action: String, argsXml: String = "") =
        StreamResponseMessage(
            UpnpResponse(UpnpResponse.Status.OK),
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
                "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$argsXml" +
                "</u:${action}Response></s:Body></s:Envelope>",
        )

    private fun faultResponse(code: Int, description: String) =
        StreamResponseMessage(
            UpnpResponse(UpnpResponse.Status.INTERNAL_SERVER_ERROR),
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault>""" +
                """<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>""" +
                """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode>""" +
                """<errorDescription>$description</errorDescription></UPnPError>""" +
                "</detail></s:Fault></s:Body></s:Envelope>",
        )

    /** 2s 内轮询断言（回调在 jUPnP 执行器线程，与既有测试同款轮询纪律） */
    private fun awaitAssert(desc: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("等待超时: $desc")
            Thread.sleep(10)
        }
    }

    // ── 线格式（jUPnP 标准 SOAPActionProcessor 产物）──

    @Test fun `WMP风格SetAVTransportURI线格式标准且参数序正确`() = runBlocking {
        val escapedDidl = UpnpXml.escape("<DIDL-Lite><item><dc:title>测试片</dc:title></item></DIDL-Lite>")
        val didl = "<DIDL-Lite><item><dc:title>测试片</dc:title></item></DIDL-Lite>"
        var body: String? = null
        var uri: String? = null
        var soapAction: String? = null
        val harness = Harness { req ->
            body = req.bodyString
            uri = req.uri.toString()
            @Suppress("UNCHECKED_CAST")
            soapAction = req.headers.get(UpnpHeader.Type.SOAPACTION)?.firstOrNull()?.string
            okResponse("AVTransport", "SetAVTransportURI")
        }
        harness.use {
            val ex = executor(it)
            withTimeout(5000) {
                ex.invoke(
                    wmpControlUri, "AVTransport", "SetAVTransportURI",
                    "<CurrentURI>http://192.168.1.2:40000/stream</CurrentURI>" +
                        "<CurrentURIMetadata>${UpnpXml.escape(didl)}</CurrentURIMetadata>",
                )
            }
            awaitAssert("请求已出网") { body != null }
            // 标准信封：动作元素 + 正确服务命名空间
            assertTrue("动作元素必须在位: $body", body!!.contains("<u:SetAVTransportURI"))
            assertTrue(body!!.contains("urn:schemas-upnp-org:service:AVTransport:1"))
            // 参数序：InstanceID 前置（与旧手搓 envelope 等价），随后按声明顺序
            val instanceIdx = body!!.indexOf("<InstanceID>0</InstanceID>")
            val curiIdx = body!!.indexOf("<CurrentURI>http://192.168.1.2:40000/stream</CurrentURI>")
            val metaIdx = body!!.indexOf("<CurrentURIMetadata>")
            assertTrue("InstanceID 必须在位: $body", instanceIdx >= 0)
            assertTrue("CurrentURI 必须在位: $body", curiIdx > instanceIdx)
            assertTrue("CurrentURIMetadata 必须在位且 DIDL 已重新转义: $body", metaIdx > curiIdx)
            assertTrue(body!!.contains(escapedDidl))
            // WMP 风格 controlURL（查询串）原样出网
            assertTrue("controlURL 查询串必须保留: $uri", uri!!.startsWith(wmpControlUri))
            assertTrue("SOAPACTION 头必须在位: $soapAction", soapAction.orEmpty().contains("SetAVTransportURI"))
        }
    }

    @Test fun `Play请求补齐spec必填Speed参数`() = runBlocking {
        var body: String? = null
        val harness = Harness { req -> body = req.bodyString; okResponse("AVTransport", "Play") }
        harness.use {
            withTimeout(5000) { executor(it).invoke(wmpControlUri, "AVTransport", "Play", "") }
            awaitAssert("Play 已出网") { body != null }
            // 手搓时代只发 InstanceID；AVTransport:1 规范 Speed 是 REQUIRED，严格渲染器可回 402
            val instanceIdx = body!!.indexOf("<InstanceID>0</InstanceID>")
            val speedIdx = body!!.indexOf("<Speed>1</Speed>")
            assertTrue("Speed 必须在位: $body", speedIdx > instanceIdx)
        }
    }

    @Test fun `GetVolume走RCS命名空间且响应重建供tagValue`() = runBlocking {
        var body: String? = null
        val harness = Harness { req ->
            body = req.bodyString
            okResponse("RenderingControl", "GetVolume", "<CurrentVolume>30</CurrentVolume>")
        }
        harness.use {
            val xml = withTimeout(5000) {
                executor(it).invoke(wmpControlUri, "RenderingControl", "GetVolume", "<Channel>Master</Channel>")
            }
            awaitAssert("GetVolume 已出网") { body != null }
            assertTrue(body!!.contains("urn:schemas-upnp-org:service:RenderingControl:1"))
            assertTrue(body!!.contains("<InstanceID>0</InstanceID>"))
            assertTrue(body!!.contains("<Channel>Master</Channel>"))
            assertEquals("30", UpnpXml.tagValue(xml, "CurrentVolume"))
        }
    }

    @Test fun `GetPositionInfo响应重建且解析进度`() = runBlocking {
        val harness = Harness { _ ->
            okResponse("AVTransport", "GetPositionInfo", "<RelTime>0:01:30</RelTime><TrackDuration>0:04:00</TrackDuration>")
        }
        harness.use {
            val xml = withTimeout(5000) { executor(it).invoke(wmpControlUri, "AVTransport", "GetPositionInfo", "") }
            assertEquals(90_000L, UpnpXml.parseTime(UpnpXml.tagValue(xml, "RelTime")))
            assertEquals(240_000L, UpnpXml.parseTime(UpnpXml.tagValue(xml, "TrackDuration")))
        }
    }

    // ── 宽容读：怪电视缺输出参数不得变成动作失败（旧 tagValue-null 语义） ──

    @Test fun `响应缺失声明输出参数降级空串不失败`() = runBlocking {
        val harness = Harness { _ -> okResponse("AVTransport", "GetPositionInfo", "<RelTime>0:01:30</RelTime>") }
        harness.use {
            val xml = withTimeout(5000) { executor(it).invoke(wmpControlUri, "AVTransport", "GetPositionInfo", "") }
            assertEquals(90_000L, UpnpXml.parseTime(UpnpXml.tagValue(xml, "RelTime")))
            // 缺失的 TrackDuration 重建后为空 → parseTime 0（与手搓时代 null→0 等价）
            assertEquals(0L, UpnpXml.parseTime(UpnpXml.tagValue(xml, "TrackDuration")))
        }
    }

    // ── 错误语义：UDA 错误码 → 中文文案全量映射 + 兜底 ──

    private suspend fun assertFaultMessage(code: Int, expected: String) {
        val harness = Harness { _ -> faultResponse(code, "device says no") }
        harness.use {
            try {
                withTimeout(5000) { executor(it).invoke(wmpControlUri, "AVTransport", "Play", "") }
                throw AssertionError("必须抛 SoapException")
            } catch (e: SoapException) {
                assertEquals(code, e.errorCode)
                assertEquals(expected, e.userMessage)
                assertEquals(expected, e.message)
            }
        }
    }

    @Test fun `fault_402映射参数无效`() = runBlocking { assertFaultMessage(402, "电视拒绝该操作（参数无效）") }

    @Test fun `fault_401映射不支持`() = runBlocking { assertFaultMessage(401, "电视不支持该操作") }

    @Test fun `fault_501映射执行失败`() = runBlocking { assertFaultMessage(501, "电视执行该操作失败") }

    @Test fun `fault_未知厂商码兜底带码文案`() = runBlocking { assertFaultMessage(733, "电视执行该操作失败（错误码 733）") }

    @Test fun `连接不可达映射电视无响应`() = runBlocking {
        val harness = Harness { _ -> null }   // router 无响应 = 网络不可达/电视关机
        harness.use {
            try {
                withTimeout(5000) { executor(it).invoke(wmpControlUri, "AVTransport", "GetPositionInfo", "") }
                throw AssertionError("必须抛 SoapException")
            } catch (e: SoapException) {
                assertEquals("电视无响应，请检查网络", e.userMessage)
            }
        }
    }

    @Test fun `非2xx无fault映射执行失败且带http码`() = runBlocking {
        val harness = Harness { _ -> StreamResponseMessage(UpnpResponse(UpnpResponse.Status.NOT_FOUND), "") }
        harness.use {
            try {
                withTimeout(5000) { executor(it).invoke(wmpControlUri, "AVTransport", "Play", "") }
                throw AssertionError("必须抛 SoapException")
            } catch (e: SoapException) {
                assertEquals(404, e.httpCode)
                assertEquals("电视执行该操作失败", e.userMessage)
            }
        }
    }

    @Test fun `控制点未就绪抛可读SoapException`() = runBlocking {
        val ex = UpnpControlSoapExecutor(controlPointProvider = { null }, callTimeoutMs = 5000, text = FakeCastText)
        try {
            ex.invoke(wmpControlUri, "AVTransport", "Play", "")
            throw AssertionError("必须抛 SoapException")
        } catch (e: SoapException) {
            assertEquals("投屏服务未就绪，请重试", e.userMessage)
        }
    }

    @Test fun `调用超时有界并映射超时文案`() = runBlocking {
        val harness = Harness { _ ->
            Thread.sleep(60_000)   // 挂死「电视」：借 withTimeout + future.cancel(true) 中断
            null
        }
        harness.use {
            try {
                withTimeout(5000) {
                    executor(it, timeoutMs = 300).invoke(wmpControlUri, "AVTransport", "GetPositionInfo", "")
                }
                throw AssertionError("必须抛 SoapException")
            } catch (e: SoapException) {
                assertEquals("电视响应超时，请检查网络", e.userMessage)
            }
        }
    }

    // ── 合成 SCPD 经 jUPnP 标准 binder 水合 ──

    @Test fun `合成SCPD水合后动作与参数序正确`() {
        val binder = UDA10ServiceDescriptorBinderImpl()
        fun hydrate(service: String): RemoteService {
            val url = java.net.URI(wmpControlUri).toURL()
            val empty = RemoteService(
                org.jupnp.model.types.UDAServiceType(service), org.jupnp.model.types.UDAServiceId(service),
                url.toURI(), url.toURI(), url.toURI(),
            )
            // binder 水合要求 delegate 已挂父设备；产物是裸 service（不回挂设备）
            // ——与生产 modelFor 同款两步接线
            val identity = org.jupnp.model.meta.RemoteDeviceIdentity(
                org.jupnp.model.types.UDN("novax-test"), 1800, url, null, null,
            )
            org.jupnp.model.meta.RemoteDevice(identity, org.jupnp.model.types.UDADeviceType("MediaRenderer"), null, empty)
            val hydrated = binder.describe(empty, UpnpControlScpd.of(service))
            org.jupnp.model.meta.RemoteDevice(identity, org.jupnp.model.types.UDADeviceType("MediaRenderer"), null, hydrated)
            return hydrated
        }
        val hydrated = hydrate("AVTransport")
        assertEquals(
            listOf("InstanceID", "CurrentURI", "CurrentURIMetadata"),
            hydrated.getAction("SetAVTransportURI")!!.inputArguments.map { it.name },
        )
        assertEquals(
            listOf("InstanceID", "Unit", "Target"),
            hydrated.getAction("Seek")!!.inputArguments.map { it.name },
        )
        assertEquals(
            listOf("TrackDuration", "RelTime"),
            hydrated.getAction("GetPositionInfo")!!.outputArguments.map { it.name },
        )
        val rcs = hydrate("RenderingControl")
        assertEquals(
            listOf("InstanceID", "Channel", "DesiredVolume"),
            rcs.getAction("SetVolume")!!.inputArguments.map { it.name },
        )
        assertEquals(listOf("CurrentVolume"), rcs.getAction("GetVolume")!!.outputArguments.map { it.name })
    }

    @Test fun `错误文案对象映射与兜底`() {
        // failure=null（run() 在 failure 回调前置空等异常情形）→ 无码兜底文案
        assertEquals("投屏操作失败", UpnpErrorText.describe(null, FakeCastText))
        // jUPnP 对「无连接/无响应」的固定短语 → 电视无响应（不走错误码分支）
        assertEquals(
            "电视无响应，请检查网络",
            UpnpErrorText.describe(
                org.jupnp.model.action.ActionException(
                    org.jupnp.model.types.ErrorCode.ACTION_FAILED,
                    "Connection error or no response received",
                ),
                FakeCastText,
            ),
        )
        // 厂商私有码不在映射表 → 带码兜底
        assertEquals(
            "电视执行该操作失败（错误码 999）",
            UpnpErrorText.describe(org.jupnp.model.action.ActionException(999, "weird"), FakeCastText),
        )
        // 返工③-701 特判：Transition not available（渲染器装载中）→「未就绪 + 已重试」可读文案
        assertEquals(
            "电视还未就绪（701），已重试仍失败",
            UpnpErrorText.describe(org.jupnp.model.action.ActionException(701, "Transition not available"), FakeCastText),
        )
    }
}
