package com.novax.app.data.cast

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.jupnp.controlpoint.ActionCallback
import org.jupnp.controlpoint.ControlPoint
import org.jupnp.binding.xml.UDA10ServiceDescriptorBinderImpl
import org.jupnp.model.action.ActionException
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.RemoteDeviceIdentity
import org.jupnp.model.meta.RemoteService
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.types.UDAServiceId
import org.jupnp.model.types.UDAServiceType
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDN
import org.jupnp.transport.spi.SOAPActionProcessor
import org.w3c.dom.NodeList
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * 控制层 jUPnP 标准适配器（2026-10-16 返工⑬，控制层架构迁移）。
 *
 * 现状根因链：jUPnP 只用于发现，动作调用走 UpnpSoap 手搓 SOAP + OkHttp——近三轮 WMP 类
 * 兼容问题（相对 controlURL 崩溃 → 硬排除误踢 → VPN 选址）全部出在手搓层与发现层的拼缝上。
 * 本文件把「SOAP 序列化、fault/UDA 错误语义、URL 归一化」全部交给 jUPnP ControlPoint
 * （[ActionCallback] → SendingAction → 标准 SOAPActionProcessor）：
 * - 设备模型：按（服务类型 + controlURL）合成最小 SCPD（[UpnpControlScpd]，全 string
 *   datatype 保证线格式与旧手搓信封等价），经 jUPnP 标准
 *   [UDA10ServiceDescriptorBinderImpl] 水合成 RemoteService，套一层父 RemoteDevice
 *   （identity.descriptorURL = controlURL 作归一化 base）——ActionCallback.run 内部按
 *   `device.normalizeURI(service.controlURI)` 解析地址（绝对原样、相对对 base 补全），
 *   与发现层三级解析链互为纵深；
 * - 异步→挂起桥：`controlPoint.execute(callback)` 返回 Future，[suspendCancellableCoroutine]
 *   等待，[withTimeout] 有界（默认 12s = 旧 OkHttp connect 4s + read 8s 最坏等价）；
 *   协程取消 → `Future.cancel(true)` 中断 jUPnP 发送线程（InterruptedException →
 *   ActionCancelledException → failure 回调，已取消的 continuation 上为 no-op）；
 * - 错误映射：SOAP fault 由 jUPnP 解析为 [ActionException]（携带设备回的 UDA 错误码），
 *   [UpnpErrorText] 全量映射为既有可读中文文案 + 未知码兜底，包成 [SoapException]
 *   抛给 seam——CastSession 的 SetAVTransportURI 细分文案与轮询 failLimit 语义零改动。
 *
 * 合成 SCPD 而不抓设备真实 SCPD 的取舍（decisions §20.2）：真实 SCPD 的 datatype 声明
 * 五花八门（time/ui4/受限枚举），jUPnP 按声明强类型解析，一台怪设备就能把旧版「最宽容
 * tagValue」路径变成动作失败；全 string 合成保证①线格式与旧手搓逐字节等价②解析永不因
 * datatype 翻车，兼容性收益聚焦在 fault 语义与 URL 归一化上。
 */

/** UDA 错误码/传输失败 → 可读中文文案（未知码兜底，绝不漏英文给 toast） */
object UpnpErrorText {
    /** jUPnP SendingAction 对「无连接/无响应」的固定短语（ldc 常量，3.0.x 稳定） */
    private const val JUPNP_CONNECTION_ERROR = "Connection error or no response received"

    /** @param failure ActionCallback.failure 收到的 invocation.failure（可 null） */
    fun describe(failure: ActionException?): String {
        if (failure == null) return "投屏操作失败"
        val code = failure.errorCode
        val msg = failure.message ?: ""
        return when {
            msg.contains(JUPNP_CONNECTION_ERROR) -> "电视无响应，请检查网络"
            code == 401 -> "电视不支持该操作"                       // INVALID_ACTION
            code == 402 -> "电视拒绝该操作（参数无效）"              // INVALID_ARGS
            code == 403 -> "电视状态不同步，请重试"                  // OUT_OF_SYNC
            code == 404 -> "电视拒绝该操作（参数值无效）"            // INVALID_VALUE
            code == 501 -> "电视执行该操作失败"                      // ACTION_FAILED
            code == 600 -> "电视拒绝该操作（参数无效）"              // ARGUMENT_VALUE_INVALID
            code == 601 -> "电视拒绝该操作（参数值越界）"            // ARGUMENT_VALUE_OUT_OF_RANGE
            code == 602 -> "电视不支持该操作"                        // OPTIONAL_ACTION 被拒
            code == 603 -> "电视内存不足"                            // OUT_OF_MEMORY
            code == 604 -> "电视执行该操作失败（需人工干预）"        // HUMAN_INTERVENTION_REQUIRED
            code == 612 -> "电视投屏会话已失效，请重新投屏"          // NO_SUCH_SESSION
            // 701 TRANSITION_NOT_AVAILABLE（返工③-701 特判）：渲染器装载中（TRANSITIONING）
            // 收到的动作必被拒——控制点侧已按退避重试仍失败，文案如实说「未就绪 + 已重试」，
            // 不再落「错误码 701」裸码兜底（用户真机时好时坏的报错即此码）
            code == 701 -> "电视还未就绪（701），已重试仍失败"
            code != null -> "电视执行该操作失败（错误码 $code）"     // 厂商私有/AVT 7xx 等
            else -> "电视执行该操作失败"                             // 无码兜底
        }
    }
}

/**
 * 控制层合成 SCPD（全 string datatype）。动作集合 = CastSession 行为面全集：
 * AVTransport:1 七动作 + RenderingControl:1 两动作（SetVolume/GetVolume）。
 * 顺序即线格式：InstanceID 恒第一，其余按 CastSession 传参顺序。
 */
object UpnpControlScpd {

    /** @return 该服务类型的合成 SCPD XML（AVTransport/RenderingControl，均 :1） */
    fun of(service: String): String {
        val actions = when (service) {
            "AVTransport" -> listOf(
                Action("SetAVTransportURI", listOf("CurrentURI", "CurrentURIMetadata"), emptyList()),
                Action("Play", listOf("Speed"), emptyList()),
                Action("Pause", emptyList(), emptyList()),
                Action("Stop", emptyList(), emptyList()),
                Action("Seek", listOf("Unit", "Target"), emptyList()),
                Action("GetPositionInfo", emptyList(), listOf("TrackDuration", "RelTime")),
                Action("GetTransportInfo", emptyList(), listOf("CurrentTransportState")),
            )
            "RenderingControl" -> listOf(
                Action("SetVolume", listOf("Channel", "DesiredVolume"), emptyList()),
                Action("GetVolume", listOf("Channel"), listOf("CurrentVolume")),
            )
            else -> error("未支持的投屏服务类型: $service")
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")
            append("<actionList>")
            actions.forEach { a ->
                append("<action><name>").append(a.name).append("</name>")
                val args = buildList {
                    add("InstanceID" to "in")
                    a.inputs.forEach { add(it to "in") }
                    a.outputs.forEach { add(it to "out") }
                }
                if (args.isNotEmpty()) {
                    append("<argumentList>")
                    args.forEach { (name, dir) ->
                        append("<argument><name>").append(name).append("</name>")
                        append("<direction>").append(dir).append("</direction>")
                        append("<relatedStateVariable>A_ARG_TYPE_").append(name).append("</relatedStateVariable>")
                        append("</argument>")
                    }
                    append("</argumentList>")
                }
                append("</action>")
            }
            append("</actionList>")
            append("<serviceStateTable>")
            actions.flatMap { listOf("InstanceID") + it.inputs + it.outputs }
                .distinct()
                .forEach { stateVar("A_ARG_TYPE_$it") }
            append("</serviceStateTable></scpd>")
        }
    }

    private fun StringBuilder.stateVar(name: String) {
        append("<stateVariable sendEvents=\"no\"><name>").append(name)
        append("</name><dataType>string</dataType></stateVariable>")
    }

    private data class Action(val name: String, val inputs: List<String>, val outputs: List<String>)
}

/**
 * 宽容 SOAP 处理器：jUPnP 默认实现读到「声明的输出参数在设备响应里缺失」直接抛
 * ActionException(603)——手搓时代的 tagValue 对缺失标签是返回 null→0，照默认实现迁移
 * 会把一台省略 RelTime 的怪电视从「进度条归零」变成「三连失败断连」，行为回退。
 * 本子类逐参尽力读取：缺失/解析失败降级空串（语义 = 旧 tagValue null 路径），其余走标准实现。
 */
class TolerantSoapActionProcessor : org.jupnp.transport.impl.SOAPActionProcessorImpl() {
    override fun readArgumentValues(
        nodes: NodeList,
        arguments: Array<org.jupnp.model.meta.ActionArgument<*>>,
    ): Array<org.jupnp.model.action.ActionArgumentValue<*>> =
        arguments.map { arg ->
            val text = findNodeText(nodes, arg)
            runCatching { createValue(arg, text) }.getOrElse { createValue(arg, "") }
        }.toTypedArray()

    /** 逐节点匹配本地名（宽容前缀/别名），任一命中即取文本；无命中返回空串 */
    private fun findNodeText(nodes: NodeList, arg: org.jupnp.model.meta.ActionArgument<*>): String {
        val aliases = runCatching { arg.aliases.toList() }.getOrDefault(emptyList())
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) ?: continue
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val local = node.localName ?: node.nodeName?.substringAfterLast(':') ?: continue
            if (local == arg.name || local in aliases) {
                return runCatching { org.jupnp.model.XMLUtil.getTextContent(node) }.getOrNull() ?: ""
            }
        }
        return ""
    }
}

/**
 * [CastSession.Deps.soap] 缝的生产实现：jUPnP ControlPoint ActionCallback 适配器。
 * 线程模型：execute 提交到 jUPnP 同步协议执行器（NvUpnpService 配置继承的 JUPnPExecutor），
 * 回调线程 resume 回调用方上下文；单调用 [callTimeoutMs] 有界（默认 12s ≈ 旧 OkHttp
 * connect 4s + read 8s 最坏路径），取消经 `Future.cancel(true)` 传播进 jUPnP 发送线程。
 */
class UpnpControlSoapExecutor(
    /** 控制点供给（生产 = CastDiscovery 持有的 AndroidUpnpService.controlPoint） */
    private val controlPointProvider: () -> ControlPoint?,
    private val callTimeoutMs: Long = 12_000,
) {
    /** 最后一次可用的控制点。发现栈随面板关闭回收（S2-F1）与 stop() 的异步 Stop SOAP 有
     * 窄竞态——回收后 pendingStop 仍可用此引用尽力送达；jUPnP 服务若已真销毁该调用自然
     * 失败，与旧 OkHttp 时代「Stop 尽力而为（runCatching）」同级。 */
    private val lastControlPoint = AtomicReference<ControlPoint?>()

    /** (服务类型 + controlURL) → 水合后的设备模型；SCPD 合成结果确定，进程级缓存 */
    private val modelCache = ConcurrentHashMap<String, Pair<RemoteService, RemoteDevice>>()

    /** @return 响应 XML 全文（与旧 SoapExecutor 契约一致，调用方用 [UpnpXml.tagValue] 取字段） */
    suspend fun invoke(controlUri: String, service: String, action: String, argsXml: String): String {
        val cp = controlPointProvider() ?: lastControlPoint.get()
        if (cp == null) throw SoapException(action, null, null, userMessage = "投屏服务未就绪，请重试")
        lastControlPoint.set(cp)
        val url = runCatching { URI(controlUri.trim()).toURL() }.getOrElse {
            // 上游 cast() 入口 isAbsoluteHttp 已 fail-fast；防御性保留（发现层链路外的手动轨等）
            throw SoapException(action, null, it, userMessage = "电视地址无效，无法投屏")
        }
        val invocation = runCatching { buildInvocation(url, service, action, argsXml) }.getOrElse {
            throw SoapException(action, null, it, userMessage = "电视拒绝该操作（参数无效）")
        }
        return try {
            withTimeout(callTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : ActionCallback(invocation) {
                        override fun success(inv: ActionInvocation<*>) {
                            if (cont.isActive) cont.resume(reconstructResponse(service, action, inv), onCancellation = null)
                        }

                        override fun failure(inv: ActionInvocation<*>, response: UpnpResponse?, msg: String?) {
                            if (!cont.isActive) return
                            val f = inv.failure
                            val exception = SoapException(
                                action = action,
                                httpCode = response?.statusCode,
                                cause = f,
                                errorCode = f?.errorCode,
                                userMessage = UpnpErrorText.describe(f),
                            )
                            cont.resumeWith(Result.failure(exception))
                        }
                    }
                    val future: Future<*> = cp.execute(callback)
                    cont.invokeOnCancellation { runCatching { future.cancel(true) } }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 单调用有界超时（替代 OkHttp connect/read 双超时）：转 SoapException 走调用方
            // 既有细分文案路径；绝不能让 TimeoutCancellationException 逃逸成协程取消
            throw SoapException(action, null, e, userMessage = "电视响应超时，请检查网络")
        }
    }

    /** 组装 ActionInvocation：合成模型水合 → 入参（InstanceID 前置 + Play 补 spec 必填 Speed） */
    private fun buildInvocation(url: java.net.URL, service: String, action: String, argsXml: String): ActionInvocation<RemoteService> {
        val (svc, _) = modelFor(url, service)
        val act = svc.getAction(action)
            ?: throw ActionException(org.jupnp.model.types.ErrorCode.INVALID_ACTION, "未合成的动作: $action")
        val invocation = ActionInvocation(act)
        val inputs = parseArgs(argsXml).toMutableMap()
        inputs.remove("InstanceID")
        invocation.setInput("InstanceID", "0")
        // AVTransport:1 规范 Play 的 Speed 是 REQUIRED 入参：手搓时代只发 InstanceID，严格
        // 渲染器（WMP/upnphost 按 SCPD 校验）可回 402——迁移一并补齐（旧线格式超集，规范栈全兼容）
        if (action == "Play" && !inputs.containsKey("Speed")) invocation.setInput("Speed", "1")
        inputs.forEach { (name, value) -> invocation.setInput(name, value) }
        return invocation
    }

    /**
     * 合成 SCPD → jUPnP 标准 binder 水合 RemoteService → 套父 RemoteDevice（归一化 base）。
     * 设备接线纪律（javap 实证 3.0.5）：binder 水合（MutableService.build）要求 delegate 已挂
     * 父设备（走 device.newInstance 重建图），但其产物是**裸** RemoteService（不回挂设备）——
     * 所以先给空 service 造设备外壳让水合通过，再给水合产物单独套一层父设备。
     */
    private fun modelFor(url: java.net.URL, service: String): Pair<RemoteService, RemoteDevice> {
        val key = "$service@$url"
        modelCache[key]?.let { return it }
        val empty = RemoteService(
            UDAServiceType(service), UDAServiceId(service),
            url.toURI(), url.toURI(), url.toURI(),
        )
        RemoteDevice(
            RemoteDeviceIdentity(UDN("novax-control-$service"), 1800, url, null, null),
            UDADeviceType("MediaRenderer"), null, empty,
        )
        val hydrated = UDA10ServiceDescriptorBinderImpl().describe(empty, UpnpControlScpd.of(service))
        val device = RemoteDevice(
            RemoteDeviceIdentity(UDN("novax-control-$service-hydrated"), 1800, url, null, null),
            UDADeviceType("MediaRenderer"), null, hydrated,
        )
        modelCache[key] = hydrated to device
        return hydrated to device
    }

    /**
     * 顶层入参片段解析（`<Name>text</Name>` 序列）。入参值是纯文本（DIDL 以转义文本内联，
     * 不可能出现嵌套元素），故包装根后遇到的每个 START_TAG 即一个入参；nextText() 已按 XML
     * 规则解出实体（还原为原始串），序列化时由 jUPnP 标准重新转义。
     */
    private fun parseArgs(argsXml: String): Map<String, String> {
        if (argsXml.isBlank()) return emptyMap()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(java.io.StringReader("<args>$argsXml</args>"))
        val result = LinkedHashMap<String, String>()
        var wrapperSkipped = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (wrapperSkipped) {
                    result[parser.name] = parser.nextText()
                } else {
                    wrapperSkipped = true
                }
            }
            event = parser.next()
        }
        return result
    }

    /** 输出值重建为响应 XML（旧 seam 契约：调用方 tagValue 按本地名取字段） */
    private fun reconstructResponse(service: String, action: String, inv: ActionInvocation<*>): String {
        val ns = "urn:schemas-upnp-org:service:$service:1"
        return buildString {
            append('<').append(action).append("Response xmlns:u=\"").append(ns).append("\">")
            inv.outputMap.forEach { (name, value) ->
                append('<').append(name).append('>')
                append(UpnpXml.escape(value.toString()))
                append("</").append(name).append('>')
            }
            append("</").append(action).append("Response>")
        }
    }
}
