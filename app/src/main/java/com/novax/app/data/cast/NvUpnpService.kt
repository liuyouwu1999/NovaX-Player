package com.novax.app.data.cast

import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.android.AndroidUpnpServiceImpl

/**
 * C13 投屏：jUPnP 服务。只订阅 AVTransport 渲染器（exclusive types），
 * 注册表 3s 维护一轮（更快发现、掉线更干净）。Task 5 的 CastDiscovery 绑定此服务。
 *
 * 返工⑬（2026-10-16 控制层迁移）：控制轨动作调用同经此服务的 ControlPoint
 * （[UpnpControlSoapExecutor]）；SOAP 处理器换宽容版（[TolerantSoapActionProcessor]）——
 * 缺失输出参数降级空串而非动作失败，保持手搓时代 tagValue-null 的怪电视容错（行为不回退）。
 * 只影响 SOAP body 解析，发现轨（SSDP/描述解析/GENA 订阅）不受影响。
 */
class NvUpnpService : AndroidUpnpServiceImpl() {
    override fun createConfiguration(): AndroidUpnpServiceConfiguration =
        object : AndroidUpnpServiceConfiguration() {
            override fun getExclusiveServiceTypes(): Array<org.jupnp.model.types.ServiceType> =
                arrayOf(org.jupnp.model.types.UDAServiceType("AVTransport"))

            override fun getRegistryMaintenanceIntervalMillis(): Int = 3000

            override fun createSOAPActionProcessor(): org.jupnp.transport.spi.SOAPActionProcessor =
                TolerantSoapActionProcessor()
        }
}
