package com.novax.app.data.cast

import com.novax.app.R
import com.novax.app.data.privacy.NvPrivacyRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** UPnP AVTransport 通用错误码 701 = Transition not available（渲染器 TRANSITIONING 装载中收到的动作必被拒） */
private const val UPNP_701_TRANSITION_NOT_AVAILABLE = 701

/** Play 撞 701 的最大重试次数（初试之外；3 次重试 = 最多 4 拍 Play） */
private const val PLAY_701_MAX_RETRIES = 3

/** Seek 撞 701 的最大重试次数（叠加在既有「拒绝则先 Play 再补 Seek」回退之上） */
private const val SEEK_701_MAX_RETRIES = 1

/** 就绪等待视作就绪的传输态（UPnP TransportState）；TRANSITIONING/NO_MEDIA_PRESENT 等一律继续等 */
private val READY_TRANSPORT_STATES = setOf("STOPPED", "PAUSED_PLAYBACK", "PLAYING")

/** 会话生命周期状态（spec C13 §3 状态机） */
sealed interface CastUiState {
    data object Idle : CastUiState
    data class Connecting(val device: CastDevice, val media: CastMedia) : CastUiState
    data class Playing(val device: CastDevice, val media: CastMedia, val positionMs: Long, val durationMs: Long) : CastUiState
    data class Paused(val device: CastDevice, val media: CastMedia, val positionMs: Long, val durationMs: Long) : CastUiState
    data class Error(val message: String) : CastUiState
}

/** 流服务器句柄（生产 = NvCastStreamServer 包装；测试 = 假件）。port 供 Wi-Fi IP 组 URI。
 * tokenQuery：URL 鉴权附加段（"?k=..."），2026-09-28 审计 C1——流服务器 0.0.0.0 零鉴权时
 * 同网段任意设备扫端口即可拉走整部媒体；测试假件默认 null（无鉴权，兼容既有用例）。 */
interface ServerHandle {
    val port: Int
    val tokenQuery: String? get() = null
    fun stop()
}

/**
 * 投屏会话核心（spec C13 §3）：唯一会话所有者。cast() = 起流服务器 → SetAVTransportURI
 * （DIDL 含字幕双声明）→ 就绪等待（GetTransportInfo 轮询，返工③-701）→ 可选 Seek →
 * Play（701 退避重试）→ 轮询（GetPositionInfo+GetTransportInfo）。
 *
 * 依赖全部函数注入：测试桩掉 ip/server/soap；生产由 CastSessionHolder 装配——soap 缝的
 * 生产实现自返工⑬（2026-10-16 控制层迁移）起为 jUPnP ControlPoint 适配器
 * [UpnpControlSoapExecutor]（SOAP 序列化/UDA 错误语义/URL 归一化交给库），缝签名不变。
 * 轮询连续 failLimit 次失败判定断连（Wi-Fi 切换/电视关机），Error 前必须回收服务器
 * （Review Focus 5：会话孤儿防线）。
 */
class CastSession(
    private val scope: CoroutineScope,
    private val deps: Deps,
) {
    data class Deps(
        // BUG-C-R11-02：入参 = 渲染设备 IP（controlURL host，可 null）——出流地址选择按
        // 渲染器做同网段/路由探测（手机挂 VPN 时不得宣告 tun 地址，WMP 局域网不可达）
        val ip: (rendererHost: String?) -> String?,
        val newServer: suspend (CastMedia) -> ServerHandle,
        val soap: suspend (controlUri: String, service: String, action: String, argsXml: String) -> String,
        val pollIntervalMs: Long = 1000,
        val failLimit: Int = 3,
        // T3（2026-10-09 追加批次）：Connecting 看门狗超时。仿 pollIntervalMs 先例可注入
        //（单测注入毫秒级小值，不真等 30s）；30s 与 OkHttp SOAP 有界（4s/8s）、DV 嗅探
        // 均远距错开，不误伤慢设备
        val watchdogTimeoutMs: Long = 30_000,
        // 返工③-701（2026-10-16 真机「电视执行该操作失败（错误码 701）」时好时坏）：
        // SetAVTransportURI 成功后等待渲染器就绪的总时长（GetTransportInfo 轮询，
        // TRANSITIONING/NO_MEDIA_PRESENT = 未就绪继续等；超时按现状继续 Play——设备自担，
        // 行为兼容）。仿 pollIntervalMs 先例可注入（单测毫秒级）；默认 15s 与看门狗 30s
        // 错开（就绪等待不挤占 Play/Seek 的重试窗口，极端叠加由看门狗兜底「投屏连接超时」）
        val readyTimeoutMs: Long = 15_000,
        // 701（Transition not available）重试退避：Play 最多重试 3 次、Seek 1 次，同样可注入
        val retryBackoffMs: Long = 800,
        // i18n Task 8：用户可见 Error 文案的字符串供给（res → 当前语言串）。生产 =
        // appContext::getString（CastSessionHolder 装配）；测试注入假映射（原中文原句），
        // 默认恒返回 resId 占位（生产构造必须注入）
        val text: (Int) -> String = { it.toString() },
    )

    private val _state = MutableStateFlow<CastUiState>(CastUiState.Idle)
    val state: StateFlow<CastUiState> = _state

    private var pollJob: Job? = null
    private var pendingStop: Job? = null

    // 2026-09-28 审计 B3：会话代际号——cast() 在 newServer/soap 挂起期间可被第二次 cast()
    // 或 stop() 插入，旧会话的赋值若不复查会把已 stop 的服务器重新登记成孤儿（无人 stop，
    // ServerSocket+线程池活到进程死）。每条会话字段写入前校验代际，过期即回收退出。
    // 字段加 @Volatile：stop() 可从 ConnectivityThread（Wi-Fi 监听）与主线程并发进入
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var server: ServerHandle? = null
    @Volatile
    private var currentDevice: CastDevice? = null
    @Volatile
    private var currentMedia: CastMedia? = null

    // 返工⑭（2026-10-16 用户真机反馈「投屏成功还要手动点 chip 才能开遥控面板」）：一次性
    // 面板自动弹票据——cast() 成功确立 Playing 时签发，UI 层（CastOverlay）经
    // [takePanelAutoOpen] CAS 取走即清。票据放会话对象（进程单例）而非组合 remember：
    // CastOverlay 随 tab 切换/路由进出反复重组，组合级标志撑不住「手动关闭后同会话永不
    // 复活」的防抖语义；拉回本机/断开（stop()→Idle）与失败（Error）都不签发，不会误弹。
    private val panelAutoOpenTicket = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 面板自动弹一次性票据：CAS 取走，true = 本会话尚未弹过、本次允许自动弹出 */
    fun takePanelAutoOpen(): Boolean = panelAutoOpenTicket.compareAndSet(true, false)

    /**
     * 返工⑮（2026-10-16 用户真机「点投屏设备后卡一下无反馈」）：连接中态前置发射——
     * 点击设备瞬间同步置 Connecting（此前 Connecting 要等 Holder 预处理链全跑完才在
     * cast() 内发射，片源预处理最长 20s 全程无状态）；预处理挪到 Connecting 态内进行
     * （Holder.cast 调序），设备行 loading/列表禁点由 UI 层承接。
     *
     * 已确立活跃会话（Playing/Paused）不碰（返回 false）：换投场景的会话切换仍由
     * cast() 内部完成，此处抢跑置 Connecting 会把在投会话打断且预处理失败无处回退。
     * 返回 true = 已置 Connecting，调用方预处理失败时须 [abortConnectingAsError] 复位，
     * 不得把用户卡死在连接中态。
     */
    fun beginConnecting(device: CastDevice, media: CastMedia): Boolean {
        val st = _state.value
        if (st is CastUiState.Playing || st is CastUiState.Paused) return false
        // B3 同款代际推进：使在途旧 cast()/旧 Connecting 会话的一切后续写入过期
        generation.incrementAndGet()
        closeSession(notifyState = false)
        _state.value = CastUiState.Connecting(device, media)
        return true
    }

    /**
     * 连接中态失败复位（返工⑮预处理失败路径）：仅当仍处 Connecting 且为同一设备才落
     * 终态——message 非 null 落 Error（文案与既有 notifyIdleError/surfacePreCastFailure 链
     * 同一来源，toast 仍由 Holder.surfacePreCastFailure 发、逻辑不变），null 落 Idle
     * （协程取消=面板关闭语义，observeLocks 对 Idle 只释放锁不弹错）。设备不匹配
     * （用户已改投他处）不碰状态。
     */
    fun abortConnecting(device: CastDevice, message: String?) {
        val st = _state.value
        if (st is CastUiState.Connecting && st.device.udn == device.udn) {
            _state.value = if (message != null) CastUiState.Error(message) else CastUiState.Idle
        }
    }

    /** 发起投屏（offsetMs>0 先 Seek 再 Play，互切进度接续） */
    suspend fun cast(device: CastDevice, media: CastMedia, offsetMs: Long = 0L) {
        // 返工3 诊断线（2026-10-16）：入口即落 NvCast——用户真机 `adb logcat -s NvCast` 从这里
        // 开始能逐级对账（设备/片源/大小/offset → SetURI 全文 → Play → 轮询），不再有零日志段
        // 隐私红线（Task 7）：media.title/path 含媒体文件名与路径，出口过脱敏
        android.util.Log.i(
            "NvCast",
            NvPrivacyRedactor.redactText(
                "cast(): device=\"${device.name}\" control=${device.controlUri} " +
                    "media=\"${media.title}\" path=${media.path} size=${media.sizeBytes} offset=${offsetMs}ms",
                null,
            ),
        )
        val myGen = generation.incrementAndGet()
        closeSession(notifyState = false)
        // review M10：上一会话的 Stop SOAP 是异步发射——重投前等它落地，
        // 避免电视端收到 Stop/SetURI 乱序（拉回后快速重投的边缘场景）
        pendingStop?.let { runCatching { withTimeoutOrNull(2000) { it.join() } } }
        pendingStop = null
        if (generation.get() != myGen) return
        // 2026-09-28 审计 B2：NetworkInterface 枚举属阻塞 IO，不在调用方（主线程）做；
        // 渲染器 IP 从 controlURL 提取（BUG-C-R11-02：出流 URL host 必须与渲染器同网段可达）
        val ip = withContext(kotlinx.coroutines.Dispatchers.IO) { deps.ip(CastUri.hostOf(device.controlUri)) }
        if (ip == null) {
            _state.value = CastUiState.Error(deps.text(R.string.data_cast_err_wifi_needed))
            return
        }
        _state.value = CastUiState.Connecting(device, media)
        // 返工3-续（2026-10-16 真机 E01「无法确定片源大小」误杀修复）：sizeBytes<=0 = 未知大小，
        // **不再硬失败**——此前在此直接 Error 拒起，SMB 类列表/入口 stat 丢失时（库明明有 1.1GB）
        // 一票否决。现在 Holder.cast 已尽力按「入口值→库元数据→源 stat」三级回填，仍无则按 DLNA
        // 允许的未知大小投屏：DIDL res 省略 @size，流服务器不带 Content-Length 以 connection-close
        // 分帧（seek 能力受限可接受）；渲染器若拒，在 SetURI/取流层如实浮出，不在此误杀
        if (media.sizeBytes <= 0) {
            android.util.Log.w(
                "NvCast",
                "片源大小未知（入口/库元数据/源stat 均未取得）——按未知大小投屏" +
                    "（DIDL 无 @size、流无 Content-Length，seek 受限）",
            )
        }
        // rework3 修复 3（2026-10-16）：控制地址必须绝对 http(s)——发现层兜底失效仍漏进
        // 相对路径时，在此 fail-fast 给可读文案，绝不把 /upnph… 一类相对路径喂给 OkHttp
        //（否则 Request.Builder().url() 抛「Expected URL scheme…」英文 IAE 且被当 Error 文案）
        if (!CastUri.isAbsoluteHttp(device.controlUri)) {
            _state.value = CastUiState.Error(deps.text(R.string.data_cast_err_bad_tv_address))
            return
        }
        try {
            // T3（2026-10-09 追加批次）看门狗：进 Connecting 起 watchdogTimeoutMs（默认 30s）
            // 超时——达 Playing/取消/任何终态即随本作用域退出解除；超时未达→先显式置
            // Error("投屏连接超时")（复用 observeLocks 既有 Error toast 路径，不另加 toast
            // 防双弹），再以 closeSession(notifyState = false) 收尾——notifyState=true 是
            // 无条件写 Idle，会把 Error 覆写掉、toast 永不触发。结构化超时单写者实现，
            // 与既有 B3 代际复查语义逐点等价（gen 过期路径不碰 _state）
            // 预登记过期路径：newServer 返回时 gen 已过期→只停自有未登记 handle，
            // 绝不 closeSession（server 等共享字段可能已属新会话，B3 防的正是误回收；
            // H-6 后已登记过期同样跳过，ownHandleStopped 标志随之移除）
            val established = withTimeoutOrNull(deps.watchdogTimeoutMs) {
                val handle = deps.newServer(media)
                // B3 代际复查：挂起期间已有更新会话/stop 进入，本 handle 不得再登记（回收防孤儿）
                if (generation.get() != myGen) {
                    runCatching { handle.stop() }
                    return@withTimeoutOrNull false
                }
                server = handle
                currentDevice = device
                currentMedia = media
                val q = handle.tokenQuery?.let { "?$it" } ?: ""
                val streamUrl = "http://$ip:${handle.port}/stream$q"
                val subtitleUrl = if (media.subtitlePath != null) "http://$ip:${handle.port}/subtitle$q" else null
                val didl = CastDidl.build(media, streamUrl, subtitleUrl)
                android.util.Log.i(
                    "NvCast",
                    // 隐私红线（Task 7）：出流地址带 stream token，query 整体掩码
                    NvPrivacyRedactor.redactText(
                        "流服务器 :${handle.port} 出流地址 $streamUrl" + (subtitleUrl?.let { "（字幕 $it）" } ?: ""),
                        null,
                    ),
                )
                // WMP 类诊断（返工3）：SetAVTransportURI 全文落 NvCast——电视端拒播原因
                //（元数据校验/URI 不可达）只能靠这份全文对账
                android.util.Log.i(
                    "NvCast",
                    // 隐私红线（Task 7）：DIDL 全文含媒体标题/文件名/res URL——掩文件名不破坏 XML
                    NvPrivacyRedactor.redactText(
                        "SetAVTransportURI → ${device.controlUri}\n  CurrentURI=$streamUrl\n  DIDL=$didl",
                        null,
                    ),
                )
                deps.soap(
                    device.controlUri, "AVTransport", "SetAVTransportURI",
                    "<CurrentURI>${UpnpXml.escape(streamUrl)}</CurrentURI>" +
                        "<CurrentURIMetadata>${UpnpXml.escape(didl)}</CurrentURIMetadata>",
                )
                android.util.Log.i("NvCast", "SetAVTransportURI OK")
                // 返工③-701（2026-10-16 真机「电视执行该操作失败（错误码 701）」时好时坏）：
                // UPnP AVTransport 701 = Transition not available——SetURI 后渲染器处于
                // TRANSITIONING（装载中）时到达的 Play 必然 701，旧流程「立即 Play」纯赌设备
                // 装载速度。现在按 DLNA 控制点标准姿势先轮询 GetTransportInfo 等就绪
                // （STOPPED/PAUSED_PLAYBACK/PLAYING 才继续；NO_MEDIA_PRESENT/TRANSITIONING =
                // 未就绪继续等），总时长 readyTimeoutMs 上限；超时按现状继续 Play（设备自担，
                // 行为兼容）
                val ready = awaitTransportReady(device, myGen)
                android.util.Log.i(
                    "NvCast",
                    if (ready) "渲染器就绪" else "就绪等待未确认（超时/代际过期）——按现状继续 Play",
                )
                // review M7：STOPPED 态 Seek 是 OPTIONAL（部分机型直接拒）——
                // 拒绝则先 Play 再补 Seek；仍失败吞掉回原进度（spec C13 §7 纪律）
                var seeked = false
                if (offsetMs > 0) {
                    try {
                        seekInternal(device, offsetMs)
                        seeked = true
                    } catch (e: SoapException) {
                        android.util.Log.d("NvCast", "pre-play seek rejected, fallback after play: $e")
                    }
                }
                if (generation.get() != myGen) {
                    return@withTimeoutOrNull false
                }
                soapCallWith701Retry(device, "Play", maxRetries = PLAY_701_MAX_RETRIES)
                android.util.Log.i("NvCast", "Play OK")
                if (offsetMs > 0 && !seeked) {
                    runCatching { seekInternal(device, offsetMs) }
                }
                true
            }
            if (established != true) {
                if (generation.get() != myGen) {
                    // B3：gen 已过期路径维持现状——不碰 _state（stop() 已落 Idle，或新 cast()
                    // 已置 Connecting，旧代际再写会把新会话打回 Idle）。
                    // H-6（2026-10-18 发布终审）：原「已登记过期=closeSession 回收自有登记」
                    // 的既有模式在此废止——新 cast()/beginConnecting 入口都已先 bump gen 并
                    // closeSession（本协程登记的 server 字段已被停掉/替换），旧协程再 close
                    // 只会误停新会话的服务器（预登记/已登记两形态同一风险），统一跳过
                    return
                }
                // T3 看门狗超时：先显式 Error（observeLocks 自带 toastMain），再收尾防覆写
                _state.value = CastUiState.Error(deps.text(R.string.data_cast_err_connect_timeout))
                closeSession(notifyState = false)
                return
            }
            if (generation.get() != myGen) {
                // H-6（2026-10-18 发布终审）：gen 已过期时**不得** closeSession——新 cast()/
                // beginConnecting 入口都先 bump gen 并 closeSession（已停掉本协程登记、server
                // 字段此后要么 null 要么属新会话），旧协程再关只会误停新会话的流服务器。
                // 资源不泄漏：入口 closeSession 已按字段回收。与 :263 预登记过期跳过同口径
                return
            }
            _state.value = CastUiState.Playing(device, media, offsetMs, media.durationMs)
            // 返工⑭：投屏态确立（Connecting→Playing）即签发一次性自动弹面板票据（见字段注）
            panelAutoOpenTicket.set(true)
            startPolling()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // review I2：取消也必须回收已 start 的服务器（底本 NvRmvbProxy NonCancellable
            // 纪律的等价实现）——否则 ServerSocket+线程池孤儿化到进程死。
            // T3：面板关闭是用户正常操作，本代际取消落 Idle（observeLocks 对 Idle 只释放
            // 锁不弹错）；gen 过期维持现状不碰 _state（stop() 已落 Idle/新会话已置 Connecting）。
            // H-6：gen 过期同样跳过 closeSession（新会话入口已回收本协程登记，旧协程再关会
            // 误停新会话服务器/误清新会话设备——「投 A 超时期间改投 B，A 迟到取消杀死 B」）
            if (generation.get() == myGen) closeSession(notifyState = true)
            throw e
        } catch (e: Exception) {
            // 返工3：任何投屏路径失败必落 NvCast（红线：不允许静默）——此前此处零日志
            android.util.Log.w("NvCast", "cast() 失败: ${e.javaClass.simpleName}: ${e.message}", e)
            // H-6：gen 过期 = 异常迟到落地时新会话已在途——closeSession/Error 双跳过（旧实现
            // 无条件收尾+写 Error：投 A 超时后改投 B，A 的超时异常停掉 B 的流服务器并把 B 的
            // Connecting 覆写成 Error，B「连上电视但不播」）；资源回收同理由新会话入口完成
            if (generation.get() != myGen) return
            closeSession(notifyState = false)
            val msg = when {
                e is SoapException && e.action == "SetAVTransportURI" ->
                    deps.text(R.string.data_cast_err_tv_cannot_fetch)
                else -> e.message ?: deps.text(R.string.data_cast_err_cast_failed)
            }
            _state.value = CastUiState.Error(msg)
        }
    }

    /**
     * 预处理失败（取流准备链，会话尚未起）的 Error 落点（返工3）：仅在 Idle/Error 态写入——
     * 活跃会话（Connecting/Playing/Paused）不碰，避免旧会话被一次无关预处理失败打回 Error。
     */
    fun notifyIdleError(message: String) {
        val st = _state.value
        if (st is CastUiState.Idle || st is CastUiState.Error) _state.value = CastUiState.Error(message)
    }

    fun pause() = fire("Pause")
    fun resume() = fire("Play")

    fun seekTo(ms: Long) {
        val device = currentDevice ?: return
        scope.launch {
            runCatching { seekInternal(device, ms) }
        }
    }

    /** RCS:1 SetVolume；未声明 RCS 的设备静默忽略（UI 层本就不显示音量条） */
    fun setVolume(percent: Int) {
        val device = currentDevice ?: return
        val uri = device.volumeUri ?: return
        val v = percent.coerceIn(0, 100)
        scope.launch {
            runCatching {
                // review I4：RCS:1 的线格式是 Channel + DesiredVolume 两元素（Volume 带属性
                // 的形状是杜撰的，严格渲染器回 402 Invalid Args）
                deps.soap(uri, "RenderingControl", "SetVolume",
                    "<Channel>Master</Channel><DesiredVolume>$v</DesiredVolume>")
            }
        }
    }

    /**
     * RCS:1 GetVolume（返工⑬补完整性）：查询电视端当前音量，遥控面板音量滑杆初值跟随设备
     * （此前恒 50% 硬编码）。未声明 RCS/无会话/查询失败（含 CastService 未就绪）一律 null，
     * UI 回退既有 50% 初值——查询失败不打扰用户。
     */
    suspend fun currentVolume(): Int? {
        val uri = currentDevice?.volumeUri ?: return null
        return runCatching {
            val xml = deps.soap(uri, "RenderingControl", "GetVolume", "<Channel>Master</Channel>")
            UpnpXml.tagValue(xml, "CurrentVolume")?.trim()?.toIntOrNull()?.coerceIn(0, 100)
        }.getOrNull()
    }

    /**
     * 结束会话：向电视发 Stop、回收流服务器、回 Idle。幂等——第二次调用看到已清空
     * 的会话字段直接返回（Review Focus 5）。
     */
    fun stop() {
        generation.incrementAndGet()   // B3：使一切在途 cast() 的后续写入过期
        val device = currentDevice
        closeSession(notifyState = true)
        if (device != null) {
            pendingStop = scope.launch { runCatching { soapCall(device, "Stop") } }
        }
    }

    /**
     * 隐私重锁停投屏（隐私计划 Task 15）：[stop] 之外再向渲染器补发一次空元数据
     * SetAVTransportURI——不少渲染器 Stop 后仍显示最后一次 SetURI 的 dc:title，只发 Stop
     * 电视端会残留节目信息（spec §3.4「停止投屏会话并清除 DIDL」）。
     * 盘点结论（Task 15 Step 0）：控制面唯一停止 API 就是 [stop]（幂等，含 Stop SOAP +
     * 流服务器回收 + 回 Idle），无独立 release/teardown；DIDL 仅在 cast() 经
     * SetAVTransportURI 写入、无清空入口，故清 DIDL 在此以空 CurrentURI/CurrentURIMetadata
     * 补发实现。尽力而为：清空失败只落日志（Stop 已让电视退出播放），不阻断重锁收尾。
     */
    suspend fun stopAndClearMetadata() {
        val device = currentDevice
        stop()
        if (device == null) return   // 无活跃会话：空操作，不发任何 SOAP
        // 与 cast() 同款纪律：先等 Stop SOAP 落地再清，避免电视端收到 Stop/SetURI 乱序
        pendingStop?.let { runCatching { withTimeoutOrNull(2000) { it.join() } } }
        try {
            deps.soap(
                device.controlUri, "AVTransport", "SetAVTransportURI",
                "<CurrentURI></CurrentURI><CurrentURIMetadata></CurrentURIMetadata>",
            )
            android.util.Log.w("NvPrivacy", "NvPrivacy cast DIDL cleared on relock")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("NvPrivacy", "NvPrivacy cast DIDL clear failed (best effort)", e)
        }
    }

    private suspend fun seekInternal(device: CastDevice, ms: Long) {
        // 返工③-701：Seek 撞 701（渲染器仍在装载/状态迁移中）退避重试一次；仍失败照旧上抛
        // ——pre-Play 场景由既有「拒绝则先 Play 再补 Seek」回退承接（两层叠加），post-Play/
        // 遥控 seekTo 场景失败语义不变
        soapCallWith701Retry(
            device, "Seek", "<Unit>REL_TIME</Unit><Target>${UpnpXml.fmtTime(ms)}</Target>",
            maxRetries = SEEK_701_MAX_RETRIES,
        )
    }

    private suspend fun soapCall(device: CastDevice, action: String, argsXml: String = "") =
        deps.soap(device.controlUri, "AVTransport", action, argsXml)

    /**
     * 带 701 退避重试的 AVTransport 动作调用（返工③-701）。仅对 UDA 错误码 701
     * （Transition not available：渲染器 TRANSITIONING 装载中，动作必然被拒）按
     * [CastSession.Deps.retryBackoffMs] 退避重试至多 [maxRetries] 次；其余错误/重试耗尽
     * 原样上抛（SetAVTransportURI 细分文案、轮询 failLimit 等既有语义零改动）。
     */
    private suspend fun soapCallWith701Retry(
        device: CastDevice,
        action: String,
        argsXml: String = "",
        maxRetries: Int,
    ) {
        var attempt = 0
        while (true) {
            try {
                soapCall(device, action, argsXml)
                return
            } catch (e: SoapException) {
                if (e.errorCode != UPNP_701_TRANSITION_NOT_AVAILABLE || attempt >= maxRetries) throw e
                attempt++
                android.util.Log.w(
                    "NvCast",
                    "$action 撞 701（Transition not available，渲染器装载中）——退避 " +
                        "${deps.retryBackoffMs}ms 后重试 #$attempt/$maxRetries",
                )
                delay(deps.retryBackoffMs)
            }
        }
    }

    /**
     * 就绪等待（返工③-701）：SetAVTransportURI 成功后轮询 GetTransportInfo，直到传输态进入
     * [READY_TRANSPORT_STATES]（STOPPED/PAUSED_PLAYBACK/PLAYING；TRANSITIONING=装载中、
     * NO_MEDIA_PRESENT=无媒体都属未就绪）。单次查询失败按未就绪继续等（不消耗轮询
     * failLimit——连接层断连由既有轮询与看门狗兜底）；代际过期立即返回 false。总时长
     * [CastSession.Deps.readyTimeoutMs] 上限（nanoTime 单调时钟，JVM 单测零 Android 依赖）。
     * @return true=观察到就绪态；false=超时/代际过期（调用方按现状继续 Play，行为兼容）
     */
    private suspend fun awaitTransportReady(device: CastDevice, myGen: Long): Boolean {
        val deadlineNanos = System.nanoTime() + deps.readyTimeoutMs * 1_000_000
        while (true) {
            if (generation.get() != myGen) return false
            val state = try {
                UpnpXml.tagValue(soapCall(device, "GetTransportInfo"), "CurrentTransportState")
                    ?.trim()?.uppercase(java.util.Locale.US)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null   // 单次查询失败 = 未就绪继续等，不让瞬时抖动短路就绪判定
            }
            if (state in READY_TRANSPORT_STATES) return true
            if (System.nanoTime() >= deadlineNanos) return false
            delay(deps.pollIntervalMs)
        }
    }

    private fun fire(action: String) {
        val device = currentDevice ?: return
        scope.launch { runCatching { soapCall(device, action) } }
    }

    private fun startPolling() {
        pollJob?.cancel()
        // 2026-09-28 审计 B8：调试残留降级（release 保留 Log.w 的纪律见 AUDIT_FINAL §5）
        android.util.Log.d("NvCast", "startPolling active=${scope.coroutineContext[Job]?.isActive}")
        pollJob = scope.launch {
            var failures = 0
            while (isActive) {
                delay(deps.pollIntervalMs)
                val device = currentDevice ?: break
                try {
                    val posXml = soapCall(device, "GetPositionInfo")
                    val transXml = soapCall(device, "GetTransportInfo")
                    val pos = UpnpXml.parseTime(UpnpXml.tagValue(posXml, "RelTime"))
                    val trackedDur = UpnpXml.parseTime(UpnpXml.tagValue(posXml, "TrackDuration"))
                    failures = 0
                    val media = currentMedia ?: break
                    val duration = if (trackedDur > 0) trackedDur else media.durationMs
                    val base = _state.value
                    if (base is CastUiState.Playing || base is CastUiState.Paused) {
                        val transport = UpnpXml.tagValue(transXml, "CurrentTransportState") ?: ""
                        // 返工3：电视端传输态逐拍落 NvCast——WMP 元数据被拒时停 NO_MEDIA_PRESENT，
                        // 是「连上但不播」的关键对账字段
                        android.util.Log.d("NvCast", "poll: transport=$transport pos=$pos/${duration}ms")
                        _state.value = if (transport.equals("PAUSED_PLAYBACK", ignoreCase = true)) {
                            CastUiState.Paused(device, media, pos, duration)
                        } else {
                            CastUiState.Playing(device, media, pos, duration)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failures++
                    // 2026-09-28 审计 B8：轮询失败逐条 Log.w 降 Log.d（断连自愈是常态路径）
                    android.util.Log.d("NvCast", "poll fail #$failures: $e")
                    if (failures >= deps.failLimit) {
                        closeSession(notifyState = false)
                        _state.value = CastUiState.Error(deps.text(R.string.data_cast_err_disconnected))
                        return@launch
                    }
                }
            }
        }
    }

    private fun closeSession(notifyState: Boolean) {
        pollJob?.cancel()
        pollJob = null
        server?.stop()
        server = null
        currentDevice = null
        currentMedia = null
        if (notifyState) _state.value = CastUiState.Idle
    }
}
