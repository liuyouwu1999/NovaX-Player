package com.novax.app.data.cast

import android.content.Context
import com.novax.app.R
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceType
import com.novax.app.data.smb.SmbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * 投屏会话装配单例（App 无 DI 框架，NvScannerHolder 同款惯例）：
 * 首次 init 绑定 jUPnP 服务、建会话 scope；cast 入口统一走 [cast]。
 * 生命周期 = 进程（spec C13 §6：进程被杀即会话消失，电视端停在上一帧，可接受）。
 */
object CastSessionHolder {

    @Volatile
    private var session: CastSession? = null
    private var discovery: CastDiscovery? = null

    /** 本次投屏装载瞬间活动网络是否计量（2026-10-11 误判修复，spec §4.3）。@Volatile 必需：
     *  写在调用方主线程协程（三个投屏入口的 rememberCoroutineScope），读在 Holder
     *  Dispatchers.IO scope（先例 NvMeteredGuard.sessionConfirmed）。默认 false 宁断勿放——
     *  watchdog 要求会话处于 Playing/Paused/Connecting 才动作,而会话只能由 cast() 起出,
     *  读取前必有同会话快照,默认值实际不可达。 */
    @Volatile
    private var castStartMetered: Boolean = false
    private var appContext: Context? = null
    // 2026-09-28 审计 B1：scope 补 CoroutineExceptionHandler——无处理器的 SupervisorJob scope
    // 里任何未捕获异常（如 Toast 线程错误）会走全局默认处理器直接杀进程
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.w("NvCast", "holder scope uncaught", e)
        },
    )
    // 返工⑬（2026-10-16 控制层迁移）：seam 生产实现 = jUPnP ControlPoint 适配器
    // （SOAP 序列化/fault 错误语义/URL 归一化交给库）；供给源 = 发现栈绑定的
    // AndroidUpnpService.controlPoint（活跃会话期发现栈由 Holder 守卫绝不停）。
    // i18n Task 8：userMessage 文案解析延迟读 appContext（object 字段在 init 前为 null，
    // 但 soap 执行只会发生在 init 之后）
    private val soapExecutor = UpnpControlSoapExecutor(
        controlPointProvider = { discovery()?.controlPoint() },
        text = { resId -> appContext?.getString(resId) ?: resId.toString() },
    )

    // 返工3（2026-10-16）：预处理专用 IO scope——boundedIo 超时后遗留任务在此自行收尾
    // （阻塞网络读不可协程取消，见 prepareSource kdoc），与发现栈/会话 scope 互不牵连
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 预处理有界时限：可读预检(1B)+DV 嗅探(2×256KB) 或 字幕列举，慢 NAS 也有界
    private const val PREPARE_TIMEOUT_MS = 20_000L

    /** Toast 必须发主线程（B1：collect 体跑在 IO 线程，API 26-27/30+ 直接进程崩溃） */
    private fun toastMain(msg: String) {
        val ctx = appContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun init(context: Context) {
        session ?: synchronized(this) {
            session ?: run {
                val app = context.applicationContext
                appContext = app
                // S2-F1（2026-10-09 审计 P1）：发现栈不再随 init 拉起——此前主界面所有 Tab 恒组合
                // CastOverlay → 组合期 init 即 bindService，jUPnP/Jetty/SSDP 组播监听自冷启动
                // 常驻进程终生，且 CastDiscovery.stop() 全工程零调用（MulticastLock 永不释放）。
                // init 现在只建会话对象（零网络副作用），发现栈改由 [ensureDiscovery] 在用户
                // 打开投屏设备面板时按需启动、[stopDiscoveryIfIdle] 在面板关闭后回收。
                CastSession(
                    scope,
                    CastSession.Deps(
                        // BUG-C-R11-02（2026-10-16）：出流地址按渲染设备 IP 做「同网段/路由探测」
                        // 选择——旧 siteLocalIpv4()「枚举序首个站点内 IPv4」在手机挂 VPN 时选中
                        // tun 地址，WMP 局域网回连取流不可达（「电视无法取流」真机根因）。
                        // 渲染器 host 从 controlURL 提取（发现层已保证绝对 http(s)）
                        ip = { rendererHost -> CastNetwork.bestLocalAddressFor(rendererHost) },
                        newServer = { media -> buildServer(media) },
                        soap = soapExecutor::invoke,
                        // i18n Task 8：Error 文案按当前语言解析（toast/面板用户可见）
                        text = app::getString,
                    ),
                ).also { created ->
                    observeLocks(created)
                    registerWifiWatch()
                    session = created
                }
            }
        }
    }

    /** 活跃会话判定（S2-F1 回收守卫；Connecting 起算——置位并不同步：cast() 经两段挂起 IO 后在 CastSession.kt:88 才置 Connecting，选中设备后关面板不会误回收） */
    private fun isActiveSession(st: CastUiState?): Boolean =
        st is CastUiState.Connecting || st is CastUiState.Playing || st is CastUiState.Paused

    /**
     * S2-F1：按需启动发现栈（幂等）。仅由投屏设备面板（NvCastDeviceSheet）在组合期同步调用，
     * 保证同一次组合里 discovery() 非 null（不能放进 LaunchedEffect：面板首帧读 null 后
     * 无重组触发，设备列表永远空白——原 init 同款约束）。
     */
    fun ensureDiscovery() {
        synchronized(this) {
            val app = appContext ?: return
            val d = discovery ?: CastDiscovery(app).also { discovery = it }
            d.start()   // stop 后重开面板：bound 已复位，重新 bindService，onServiceConnected 重新 search
        }
    }

    /**
     * S2-F1：投屏 UI 关闭后回收发现栈（unbind + MulticastLock 释放 + Jetty 随服务销毁）。
     * 红线：活跃会话（[isActiveSession]）绝不停；会话结束后发现栈无存在必要（cast/遥控
     * 经 jUPnP 适配器不依赖注册表；回收后的收尾 Stop SOAP 走适配器的 lastControlPoint
     * 引用尽力送达，与 OkHttp 时代「Stop 尽力而为」同级），重开面板由 [ensureDiscovery]
     * 重新拉起。
     */
    fun stopDiscoveryIfIdle() {
        if (isActiveSession(session?.state?.value)) return
        synchronized(this) {
            if (isActiveSession(session?.state?.value)) return
            discovery?.stop()
        }
    }

    private fun observeLocks(s: CastSession) {
        scope.launch {
            var wasError = false
            var wasActive = false
            s.state.collect { st ->
                when (st) {
                    is CastUiState.Playing, is CastUiState.Paused, is CastUiState.Connecting -> {
                        wasError = false
                        acquireLocks()
                    }
                    is CastUiState.Error -> {
                        // review M5：断连不能只有面板里可见——主界面 Toast 一次
                        if (!wasError) {
                            wasError = true
                            // 2026-09-28 审计 B1：Toast 统一发主线程（本 collect 体在 IO 线程；
                            // API 26-27 的 TN 构造与 API 30+ 的 show() 主线程检查都会抛未捕获
                            // 异常——此前无异常处理器，等于「电视关机断连→进程崩溃」）
                            // i18n Task 8：前缀文案资源化，原因串由会话层按语言解析
                            appContext?.let { toastMain(it.getString(R.string.data_cast_ended_with_reason, st.message)) }
                        }
                        releaseLocks()
                    }
                    else -> releaseLocks()
                }
                // T3（2026-10-09 追加批次）：任一活跃态（Connecting/Playing/Paused）→Idle/Error
                // 边沿补调一次 stopDiscoveryIfIdle——覆盖「关面板 onDispose 同步早退早于取消落
                // 定」的发现栈残留。Error 边沿必补：MulticastLock 唯一释放路径是
                // stopDiscoveryIfIdle→CastDiscovery.stop()，黑洞 8s 排空/看门狗 30s/轮询 3 连
                // 失败断连都以 Error 终态收场，漏挂即发现栈残留；Error 发射时 wasActive 仍
                // true 触发一次随后置 false，Error→Idle 不重复。源态不得收窄为 Playing/Paused
                //（关面板取消正是 Connecting→终态边沿）；幂等，活跃会话守卫在 Holder 内绝不停
                if (wasActive && (st is CastUiState.Idle || st is CastUiState.Error)) stopDiscoveryIfIdle()
                wasActive = st is CastUiState.Connecting || st is CastUiState.Playing || st is CastUiState.Paused
            }
        }
    }

    /**
     * review M4：Wi-Fi 监听——掉网/切网即时结束会话（不等轮询 3 连失败）。
     * NetworkCallback 是系统回调（App 进程存活期有效），onLost 只在 Wi-Fi 断开时触发。
     */
    private fun registerWifiWatch() {
        val app = appContext ?: return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLost(network: android.net.Network) {
                    val active = session ?: return
                    val st = active.state.value
                    if (st is CastUiState.Playing || st is CastUiState.Paused || st is CastUiState.Connecting) {
                        appContext?.let { toastMain(it.getString(R.string.data_cast_ended_wifi_lost)) }
                        active.stop()
                    }
                }
            })
        }
        // 2026-09-28 审计 P0-1：投屏中 Wi-Fi→计量网络（热点/VPN 回程）也要收口——中继循环
        // 会带着原流把整部片经手机拉给电视。3s 去抖后以活动网络复核（与播放器兜底暂停同语义）
        // 2026-10-11 误判修复（spec §4.3）：castStartMetered 基线表达「装载时是否计量」，
        // 与 3s 复核相与才是真迁移；计量确认投屏不再被 3 秒误断
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : android.net.ConnectivityManager.NetworkCallback() {
                    private val debounce = java.util.concurrent.atomic.AtomicBoolean(false)
                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        capabilities: android.net.NetworkCapabilities,
                    ) {
                        if (capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
                        if (!debounce.compareAndSet(false, true)) return
                        scope.launch {
                            kotlinx.coroutines.delay(3_000)
                            debounce.set(false)
                            val active = session ?: return@launch
                            val st = active.state.value
                            if (st !is CastUiState.Playing && st !is CastUiState.Paused && st !is CastUiState.Connecting) return@launch
                            val cm2 = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                            if (cm2 != null && !castStartMetered && com.novax.app.player.NvMeteredGuard.isMeteredNetwork(appContext!!)) {
                                appContext?.let { toastMain(it.getString(R.string.data_cast_ended_metered)) }
                                active.stop()
                            }
                        }
                    }
                },
            )
        }
    }

    fun session(): CastSession? = session
    fun discovery(): CastDiscovery? = discovery

    /**
     * 隐私重锁停投屏（隐私计划 Task 15，接 NvPrivacyRelockOrchestrator.castStopHook）。
     * 盘点结论（Step 0，`grep "fun stop|fun release|fun teardown|SetAVTransportURI|DIDL" data/cast/`）：
     * 本 Holder 为单会话设计（session 单字段，「遍历活动会话」即当前唯一会话）；CastSession
     * 唯一停止 API = stop()（Stop SOAP + 流服务器回收 + 回 Idle，幂等），无独立 release/teardown；
     * DIDL 只在 cast() 经 SetAVTransportURI 写入、无清空入口——清 DIDL 由
     * [CastSession.stopAndClearMetadata] 以空元数据 SetAVTransportURI 补发实现。
     * 未初始化（从未打开投屏面板）/无活跃会话 = 空操作（重锁无投屏是常态路径，不打扰渲染器）。
     */
    suspend fun stopAllAndClear() {
        val s = session ?: return
        if (!isActiveSession(s.state.value)) return
        s.stopAndClearMetadata()
    }

    /**
     * 会话存续期持 WifiLock(HIGH_PERF)+PARTIAL_WAKE_LOCK（spec C13 §4/§6）：
     * 中继流走手机网络栈，Doze/省电掐流表现为电视端卡死，锁随会话状态自动取放。
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireLocks() {
        val app = appContext ?: return
        if (wifiLock?.isHeld != true) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nv-cast-wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock?.isHeld != true) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "novax:nv-cast")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
    }

    /** UI 发起入口：保证已初始化后 cast；外挂字幕自动匹配（spec C13 §4.1） */
    suspend fun cast(device: CastDevice, media: CastMedia, offsetMs: Long = 0L) {
        val context = checkNotNull(appContext) { "CastSessionHolder 未初始化" }
        val session = checkNotNull(session) { "CastSessionHolder 未初始化" }
        // 返工3 诊断线（2026-10-16）：入口即落 NvCast——「NAS 视频点了没反应」类问题从这行开始对账
        android.util.Log.i(
            "NvCast",
            "cast 入口: device=\"${device.name}\" media=\"${media.title}\" path=${media.path} " +
                "size=${media.sizeBytes} offset=${offsetMs}ms",
        )
        // 电视端大概率解不了的容器（spec C13 §6）：非阻塞提醒，不拦截尝试
        val ext = media.path.substringAfterLast('.').lowercase()
        if (ext in setOf("rm", "rmvb")) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.data_cast_warn_unsupported_format),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        // 返工⑮（2026-10-16 用户真机「点投屏设备后卡一下无反馈」）：连接中态在点击瞬间同步
        // 置位（设备行 loading 由 UI 层承接），片源预处理（最长 20s 有界链）挪到 Connecting
        // 态内进行——预处理超时/失败的 toast 逻辑不变（surfacePreCastFailure），失败后经
        // abortConnecting 落 Error 复位状态机（不卡死连接中态）；协程取消（用户关面板）落
        // Idle 不弹错（T3 语义）。活跃已确立会话（Playing/Paused）beginConnecting 不碰，
        // 换投切换仍由下方 session.cast() 内部完成
        val connectingPrimed = session.beginConnecting(device, media)
        try {
            val source = withContext(Dispatchers.IO) {
                runCatching { MediaDb.get(context).sourceDao().getById(media.sourceId) }.getOrNull()
            }
            val isRemote = source != null && source.type != SourceType.LOCAL
            // 返工3-续（2026-10-16 真机 E01「无法确定片源大小，无法投屏」误杀修复）：大小不再只信
            // 入口 CastMedia——三个 UI 入口（库/文件/播放器）各自带值且语义脆弱（文件页=列表 stat、
            // 播放器=DB 回查、库页=DB 行），任一环缺值旧版即在 CastSession 硬失败。现在投屏层统一
            // 按「入口 sizeBytes → 库元数据 sizeBytes → 源 stat」三级回填（E01：入口丢值但库有
            // 1.1GB → 库元数据救回，DIDL 带 size）；三级全空 = 未知大小继续投屏（DLNA 允许：DIDL
            // 省略 @size、流服务器 connection-close 分帧），诊断行落「来源」供真机 logcat 对账
            val sizedMedia = resolveSize(source, media)
            // 返工3 修复 2（2026-10-16）：取流预处理链（可读预检 + DV 嗅探）全部有界——此前对远程源
            // 是无界阻塞读：NAS 掉线/读停滞时整个 cast 挂死在 Connecting 之前，无连接态、无 toast、
            // 无日志 = 用户实测「点了毫无反馈」。现在停滞/远程不可读一律如实失败（surfacePreCastFailure）。
            // 返工3-续：未知大小也照跑预检（可读预检对远程源更有价值），仅 DV 尾部嗅探按需跳过
            val (prepErr, dvHit) = prepareSource(source, sizedMedia, ext, isRemote)
            if (prepErr != null) {
                surfacePreCastFailure(prepErr)
                if (connectingPrimed) session.abortConnecting(device, prepErr)
                return
            }
            val dv = dvHit
            if (dv) {
                android.widget.Toast.makeText(
                    context, context.getString(R.string.data_cast_warn_dolby_vision),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            val withSub = if (sizedMedia.subtitlePath != null) {
                sizedMedia
            } else {
                // 与本机播放同一套候选评分（SubtitleMatcher.pickAuto，>=AUTO_THRESHOLD 才自动选）。
                // 2026-09-28 审计 B2：listSubtitles 走网络列目录，同嗅探一并下放 IO。
                // 返工3：字幕列举同样有界+吞异常落日志——字幕是可选增强，失败绝不拦投屏，但必须有迹可查
                val subResult: Result<String?> = boundedIo(PREPARE_TIMEOUT_MS) {
                    source?.let {
                        runCatching { CastSubtitle.resolveAuto(NvGraph.repository().listSubtitles(it, sizedMedia.path)) }
                    } ?: Result.success<String?>(null)
                } ?: run {
                    android.util.Log.w("NvCast", "字幕列举超时（${PREPARE_TIMEOUT_MS}ms），跳过自动字幕继续投屏")
                    Result.success<String?>(null)
                }
                val sub = subResult.getOrElse { e ->
                    android.util.Log.w("NvCast", "字幕列举失败，跳过自动字幕继续投屏", e)
                    null
                }
                if (sub != null) {
                    android.util.Log.d("NvCast", "自动字幕: $sub")
                    sizedMedia.copy(subtitlePath = sub)
                } else {
                    sizedMedia
                }
            }
            // 2026-10-11 误判修复（spec §4.3）：基线贴 cast() 紧前拍——上方 DV 嗅探/字幕列举
            // 可达数秒,提前拍会让旧投屏会话的最后一窗迁移事件被新基线吞掉(一轮 P2-1);
            // 确认(awaitConsent → continueCast)严格先于此处
            castStartMetered = com.novax.app.player.NvMeteredGuard.isMeteredNetwork(context)
            session.cast(device, withSub, offsetMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消照旧上抛（面板关闭/入口重组）；连接中态若由本次点击置位则静默落
            // Idle（observeLocks 对 Idle 只释放锁不弹错，与 T3 面板关闭语义一致）
            if (connectingPrimed) session.abortConnecting(device, null)
            throw e
        } catch (e: Exception) {
            // 预处理链意外异常（DB/嗅探层抛出等，正常都被内部 runCatching 吞）：不许把用户
            // 卡死在连接中态——落 Error 走既有 toast 面后原样上抛（调用方 scope 无处理器，
            // 与旧版裸抛同语义）
            android.util.Log.w("NvCast", "cast 预处理链异常", e)
            if (connectingPrimed) session.abortConnecting(device, e.message ?: context.getString(R.string.data_cast_err_cast_failed))
            throw e
        }
    }

    /**
     * 片源大小三级来源链（返工3-续 2026-10-16 E01 修复）。优先级：
     * ① 入口 CastMedia.sizeBytes（三 UI 入口自带值）→ ② 库元数据 media_items.sizeBytes
     * （与库页/详情页同一字段，DB 有 1.1GB 就该从这里救回）→ ③ 源 stat（fileSizeBlocking，
     * SMB/WebDAV 的 stat 可能失败：异常/超时/非正数一律降级）→ 都无 = 未知大小（返回 0，
     * 调用方按「不硬失败」续投）。返回回填后的 CastMedia。
     */
    private suspend fun resolveSize(
        source: com.novax.app.data.db.SourceEntity?,
        media: CastMedia,
    ): CastMedia {
        if (media.sizeBytes > 0) {
            android.util.Log.d("NvCast", "片源大小: ${media.sizeBytes}B（来源=入口）")
            return media
        }
        if (source != null) {
            // ② 库元数据（media_items 行；扫描入库时来自目录列举，与库页同源）
            val context = appContext
            if (context != null) {
                val dbSize = runCatching {
                    MediaDb.get(context).mediaItemDao().findByKey(source.id, media.path)?.sizeBytes ?: 0L
                }.getOrElse { e ->
                    android.util.Log.w("NvCast", "库元数据大小回查失败", e)
                    0L
                }
                if (dbSize > 0) {
                    android.util.Log.i("NvCast", "片源大小回填: ${media.sizeBytes} → $dbSize（来源=库元数据）")
                    return media.copy(sizeBytes = dbSize)
                }
            }
            // ③ 源 stat（阻塞网络 IO，有界；SMB 流 stat 失败属预期，失败即按未知继续）
            val stat = boundedIo(PREPARE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { NvGraph.repository().fileSizeBlocking(source, media.path) }.getOrDefault(-1L)
                }
            }
            if (stat == null) {
                android.util.Log.w("NvCast", "源 stat 超时（${PREPARE_TIMEOUT_MS}ms），按未知大小继续投屏")
            } else if (stat > 0) {
                android.util.Log.i("NvCast", "片源大小回填: ${media.sizeBytes} → $stat（来源=源stat）")
                return media.copy(sizeBytes = stat)
            }
        }
        android.util.Log.w("NvCast", "片源大小未知（入口/库元数据/源stat 均无）——按未知大小继续投屏（DIDL 省略 @size）")
        return media
    }

    /**
     * 取流预处理（返工3 修复 2）：①片源可读预检（起流前证明 NAS/本地此刻读得到——此前远程源
     * 掉线时 SetURI 照常被电视接受、取流在 TV 侧静默失败 =「连上但不播」无从排查）②DV 嗅探
     * （mp4/mov 头尾找 DV 盒标记，review M6 + 2026-09-28 审计 B2 的 IO 下放与
     * maxBlockBytes=256KB 纪律保留；未知大小时无尾偏移可算，只嗅头部）。
     *
     * 有界性：阻塞网络读不可协程取消，超时经 [boundedIo]「独立 IO scope + 放弃等待」实现——
     * 放弃后遗留任务自行按各自协议超时收尾（流经 use 关闭），结果弃置。
     *
     * @return (错误文案 null=继续投屏, DV 命中)
     */
    private suspend fun prepareSource(
        source: com.novax.app.data.db.SourceEntity?,
        media: CastMedia,
        ext: String,
        isRemote: Boolean,
    ): Pair<String?, Boolean> {
        val repo = NvGraph.repository()
        val ctx = checkNotNull(appContext) { "CastSessionHolder 未初始化" }
        val isDvExt = ext in setOf("mp4", "mov", "m4v")
        val dv = try {
            boundedIo(PREPARE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (source == null) return@withContext false
                    // ① 可读预检：开流读 1 字节证明连通（块流上限 256KB，防读放大，B2 纪律）
                    repo.openStreamBlocking(source, media.path, 0, maxBlockBytes = 256 * 1024)
                        .use { it.readNBytes(1) }
                    android.util.Log.d("NvCast", "片源预检 OK（${source.type} ${source.nickname}）")
                    // ② DV 嗅探（review M6）：命中仅提醒，不拦截；未知大小时无尾偏移，只嗅头部
                    if (isDvExt) {
                        val head = repo.openStreamBlocking(source, media.path, 0, maxBlockBytes = 256 * 1024)
                            .use { it.readNBytes(256 * 1024) }
                        val tail = if (media.sizeBytes > 0) {
                            val tailOff = (media.sizeBytes - 256 * 1024).coerceAtLeast(0)
                            repo.openStreamBlocking(source, media.path, tailOff, maxBlockBytes = 256 * 1024)
                                .use { it.readNBytes(256 * 1024) }
                        } else ByteArray(0)
                        val hit = CastCodecSniff.isDolbyVision(head, tail)
                        android.util.Log.d("NvCast", "DV 嗅探完成: $hit")
                        hit
                    } else {
                        false
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 快速失败（连接拒绝/404 等）：远程源 = 投屏必然取不到流，如实终止；本地源仅跳过 DV 提醒
            android.util.Log.w("NvCast", "片源预处理失败（remote=$isRemote）", e)
            if (isRemote) return ctx.getString(R.string.data_cast_err_source_unreadable) to false
            return null to false
        }
        if (dv == null) {
            android.util.Log.w("NvCast", "片源预处理超时（${PREPARE_TIMEOUT_MS}ms，读停滞）remote=$isRemote")
            return ctx.getString(R.string.data_cast_err_source_stalled) to false
        }
        return null to dv
    }

    /**
     * 有界阻塞 IO：独立 IO scope 跑 [block]，至多等 [timeoutMs]。超时返回 null——
     * 遗留任务不取消（阻塞读不可中断），自行按协议超时收尾，结果弃置。
     */
    private suspend fun <T> boundedIo(timeoutMs: Long, block: suspend () -> T): T? {
        val deferred = ioScope.async { block() }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /** 预处理失败统一出口（红线：不允许静默）：NvCast 落日志 + toast；空闲态再落 Error
     *  （播放器入口凭 Error 态留在本机不退出）。活跃会话不碰状态——一次无关预处理失败
     *  不得打回正在投屏的旧会话。 */
    private fun surfacePreCastFailure(msg: String) {
        android.util.Log.w("NvCast", "投屏预处理失败: $msg")
        toastMain(msg)
        session?.notifyIdleError(msg)
    }

    /**
     * 起一次性流服务器：SMB/WebDAV/本地统一经 repo.openStreamBlocking（与主播放器同源
     * 读通道，SMB 走 LANE_PLAY 播放专线防浏览驱逐）；字幕有则注册 /subtitle（同一读取管线，
     * 编码转码在服务器侧收口）。
     */
    private suspend fun buildServer(media: CastMedia): ServerHandle {
        val repo = NvGraph.repository()
        val context = checkNotNull(appContext) { "CastSessionHolder 未初始化" }
        val source = MediaDb.get(context).sourceDao().getById(media.sourceId)
            ?: throw IllegalStateException(context.getString(R.string.data_cast_err_source_missing))
        val subtitlePath = media.subtitlePath
        val server = NvCastStreamServer(
            totalBytes = media.sizeBytes,
            opener = { offset ->
                repo.openStreamBlocking(source, media.path, offset, lane = SmbClient.LANE_PLAY)
            },
            subtitleProvider = subtitlePath?.let { sp ->
                {
                    runCatching {
                        repo.openStreamBlocking(source, sp, 0, lane = SmbClient.LANE_PLAY)
                            .use { it.readBytes() }
                    }.getOrNull()
                }
            },
            bindAddress = InetAddress.getByName("0.0.0.0"),
            videoMime = CastMime.videoMimeOf(media.path),
        )
        // 2026-09-28 审计 B2：ServerSocket bind 也属阻塞 IO，随嗅探纪律一并下放
        withContext(Dispatchers.IO) { server.start() }
        return server
    }
}
