package com.novax.app.data.cast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jupnp.android.AndroidUpnpService
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry

/**
 * C13 发现双轨（spec C13 §3/§5）：
 * - jUPnP 轨：绑定 [NvUpnpService]（exclusive=AVTransport），注册表监听收 MediaRenderer，
 *   M-SEARCH 主动扫描；UDN 去重 upsert。
 * - 手动轨（[manualAdd]）：直接拉描述 XML 解析（[RendererDescriptorParser]）——
 *   空态兜底 + 模拟器测试通道（模拟器收不到组播，spec §8）。
 * 两轨产出同一 [CastDevice]，控制统一走 UpnpSoap（Task 6），不区分来源。
 */
class CastDiscovery(private val context: Context) {

    /** 手动添加复用同一 client（review M9：不再每次新建连接池） */
    private val httpClient = OkHttpClient()

    private var upnp: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var bound = false

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices

    private val avtType = UDAServiceType("AVTransport")
    private val rcsType = UDAServiceType("RenderingControl")

    private val listener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
            collect(device ?: return)
        }

        override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
            val udn = device?.identity?.udn?.identifierString ?: return
            _devices.value = _devices.value.filterNot { it.udn == udn }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val s = service as AndroidUpnpService
            upnp = s
            s.registry.addListener(listener)
            acquireMulticastLock()
            // 组播被路由器禁用时 search 静默无果——空态由 UI 引导走手动添加
            s.controlPoint.search()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            upnp = null
        }
    }

    fun start() {
        if (bound) return
        bound = true
        context.bindService(Intent(context, NvUpnpService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun rescan() {
        upnp?.controlPoint?.search()
    }

    /**
     * 控制轨控制点访问器（返工⑬）：[UpnpControlSoapExecutor] 的供给源。只读透传，
     * 发现行为零改动——服务未绑定时 null（适配器回退 lastControlPoint / 抛 SoapException）。
     */
    fun controlPoint(): org.jupnp.controlpoint.ControlPoint? = upnp?.controlPoint

    fun stop() {
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        upnp = null
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        _devices.value = emptyList()
    }

    /** 手动轨：拉描述 XML → 解析 → upsert；UDN 用 URL hash 派生（幂等）。HTTP 必须 IO 线程 */
    suspend fun manualAdd(descriptorUrl: String): CastDevice? {
        // 返工3：单次重试——模拟器 NAT/家用路由对短连接的首包偶发吞包实测存在
        //（desc.xml 服务器已应答、App 侧仍抛 IO），重试一次消掉这类瞬态；仍失败如实落日志
        val xml = withContext(Dispatchers.IO) {
            fun fetch(): String? = runCatching {
                httpClient.newCall(Request.Builder().url(descriptorUrl).build())
                    .execute().body?.string()
            }.getOrNull()
            fetch() ?: kotlin.run { delay(500); fetch() }
        }
        // 返工3（2026-10-16）：手动添加失败必落 NvCast（红线：不允许静默）——此前失败只剩一个
        // 2 秒 toast，模拟器全链路实测「desc.xml 已取、后续零动作」无一字日志可查
        if (xml == null) {
            android.util.Log.w("NvCast", "手动添加失败：描述 XML 拉取失败（网络/超时） url=$descriptorUrl")
            return null
        }
        val (name, control, rcs) = RendererDescriptorParser.parse(xml, descriptorUrl) ?: run {
            android.util.Log.w("NvCast", "手动添加失败：描述不含可用 AVTransport controlURL url=$descriptorUrl")
            null
        } ?: return null
        val device = CastDevice(
            udn = "manual-" + descriptorUrl.hashCode().toString(),
            name = name,
            controlUri = control,
            volumeUri = rcs,
        )
        upsert(device)
        return device
    }

    private fun collect(device: RemoteDevice) {
        val udn = device.identity?.udn?.identifierString ?: return
        val avt = device.findService(avtType) ?: return
        // rework3 修复 5（2026-10-16）：上一轮 d1a339c「解析不出绝对地址即不入列」把 WMP 类
        // 设备整体挡在发现列表外——Windows WMP/upnphost 的描述 XML 常规就带相对 controlURL
        //（或 <URLBase>），主流 UPnP 栈对这类设备一律补全而非丢弃。标准解析链：
        // URLBase（描述 XML 元素，若声明；jUPnP 3.0.5 绑定为 DeviceDetails.baseURL）→
        // LOCATION 派生 base（UPnP 1.0 强制字段）→ SSDP NOTIFY 来源地址。逐级尝试、任一
        // 成功即入列；jUPnP registry 不透出 NOTIFY 源地址（RemoteDeviceIdentity 只留
        // descriptorURL/interfaceMac/localAddress，javap 实证），第三级在本轨恒空、链路实际
        // 止于 LOCATION，三级全失败仅理论存在——留日志不入列。
        val urlBase = runCatching { device.details.baseURL?.toString() }.getOrNull()
        val location = device.identity?.descriptorURL?.toString()
        val control = CastUri.resolveControlUri(avt.controlURI?.toString() ?: return, urlBase, location) ?: run {
            android.util.Log.w("NvCast", "发现设备 $udn 的 controlURL 无法按 URLBase/LOCATION 绝对化，不入列")
            return
        }
        val volume = device.findService(rcsType)?.controlURI
            ?.toString()?.let { CastUri.resolveControlUri(it, urlBase, location) }
        upsert(
            CastDevice(
                udn = udn,
                name = runCatching { device.details.friendlyName }.getOrNull() ?: udn,
                controlUri = control,
                volumeUri = volume,
            ),
        )
    }

    private fun upsert(d: CastDevice) {
        _devices.value = _devices.value.filterNot { it.udn == d.udn } + d
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("nv-cast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }
}
