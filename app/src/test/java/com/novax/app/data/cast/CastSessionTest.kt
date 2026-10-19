package com.novax.app.data.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSessionTest {
    private val device = CastDevice("udn-1", "测试电视", "http://192.168.1.8:49152/control", "http://192.168.1.8:49152/vol")
    private val media = CastMedia(1, "/a/x.mp4", "测试片", 1000L, 240_000L, "nvx://1/a/x.mp4", "/a/x.srt")

    private class FakeServer(id: Int) : ServerHandle {
        var stopCount = 0
        override val port: Int = 40000 + id
        override fun stop() { stopCount++ }
    }

    /** 可编程假 SOAP：记录调用并按 action 出响应（handler 可挂起，供取消用例挂住） */
    private class RecordingSoap(val handler: suspend (String) -> String) {
        // COW 列表：calls 被轮询协程（Default dispatcher）追加、测试线程遍历，普通 ArrayList
        // 偶发 ConcurrentModificationException（2026-09-28 实测 flake，T 代理预警的同一族）
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        suspend operator fun invoke(controlUri: String, service: String, action: String, argsXml: String): String {
            calls += action to argsXml
            return handler(action)
        }
    }

    private fun posInfo(pos: String = "0:00:05", dur: String = "0:04:00") =
        "<GetPositionInfoResponse><RelTime>$pos</RelTime><TrackDuration>$dur</TrackDuration></GetPositionInfoResponse>"

    private fun transport(state: String = "PLAYING") =
        "<GetTransportInfoResponse><CurrentTransportState>$state</CurrentTransportState></GetTransportInfoResponse>"

    /** 组装被测会话；返回 (session, servers, soap)。ready/retry 时序缝默认生产值，701 用例注入毫秒级 */
    private fun session(
        ip: String? = "192.168.1.2",
        readyTimeoutMs: Long = 15_000,
        retryBackoffMs: Long = 800,
        handler: (String) -> String = { if (it == "GetTransportInfo") transport() else posInfo() },
    ): Triple<CastSession, MutableList<FakeServer>, RecordingSoap> {
        val servers = mutableListOf<FakeServer>()
        val soap = RecordingSoap(handler)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = CastSession(
            scope,
            CastSession.Deps(
                ip = { ip },
                newServer = { m -> FakeServer(servers.size + 1).also { servers += it } },
                soap = soap::invoke,
                pollIntervalMs = 10,
                readyTimeoutMs = readyTimeoutMs,
                retryBackoffMs = retryBackoffMs,
                text = FakeCastText,
            ),
        )
        return Triple(session, servers, soap)
    }

    @Test fun `cast成功迁移到Playing且URI带DIDL`() = runBlocking {
        val (s, servers, soap) = session()
        s.cast(device, media)
        val st = withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals(240_000L, st.durationMs)
        val setUri = soap.calls.first { it.first == "SetAVTransportURI" }
        assertTrue(setUri.second.contains("/stream"))
        assertTrue(setUri.second.contains("DIDL-Lite"))
        assertTrue(soap.calls.any { it.first == "Play" })
        assertEquals(0, servers[0].stopCount)
        s.stop()
    }

    @Test fun `无IP直接Error且不起服务器`() = runBlocking {
        val (s, servers, _) = session(ip = null)
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Error>().first() }
        assertEquals(0, servers.size)
        s.stop()
    }

    // rework3 修复 3（2026-10-16）：控制地址无 scheme（发现层兜底失效漏进相对路径 /upnph…）
    // 必须 fail-fast 可读 Error，不起服务器、不发 SOAP，绝不嗂给 OkHttp 吃英文 IAE
    @Test fun `控制地址无scheme直接Error且不起服务器不发SOAP`() = runBlocking {
        val (s, servers, soap) = session()
        val bad = CastDevice("udn-bad", "相对地址电视", "/upnphost/udhisapi.dll?content=uuid:x", null)
        s.cast(bad, media)
        val st = withTimeout(3000) { s.state.filterIsInstance<CastUiState.Error>().first() }
        assertEquals("电视地址无效，无法投屏", st.message)
        assertEquals(0, servers.size)
        assertEquals(0, soap.calls.size)
        s.stop()
    }

    @Test fun `连续轮询失败达上限进入Error且停服务器一次`() = runBlocking {
        var poll = 0
        val (s, servers, _) = session(handler = { action ->
            if (action == "GetPositionInfo" && ++poll <= 3) throw SoapException(action, null, null)
            if (action == "GetTransportInfo") transport() else posInfo()
        })
        s.cast(device, media)
        withTimeout(5000) { s.state.filterIsInstance<CastUiState.Error>().first() }
        assertEquals(1, servers[0].stopCount)
        s.stop()
    }

    @Test fun `cast两次旧服务器被停新会话独占`() = runBlocking {
        val (s, servers, _) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        s.cast(device, media.copy(title = "第二部"))
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals(1, servers[0].stopCount)
        assertEquals(0, servers[1].stopCount)
        s.stop()
    }

    @Test fun `stop幂等且只发一次Stop`() = runBlocking {
        val (s, servers, soap) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        s.stop(); s.stop()
        assertEquals(1, servers[0].stopCount)
        // Stop 是 fire-and-forget 异步发出——等它落账（也是对真实行为的断言）；
        // 落账后再给 200ms 观察窗确认没有第二发（2026-09-28 审计 T-flaky：50ms 窗太窄，
        // 慢 CI 下双发晚于窗口会漏抓；这里拉宽窗口仍非绝对证明，但漏抓概率已可忽略）
        withTimeout(3000) {
            while (soap.calls.count { it.first == "Stop" } < 1) kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(200)
        assertEquals(1, soap.calls.count { it.first == "Stop" })
    }

    @Test fun `电视端暂停后状态迁移Paused`() = runBlocking {
        var paused = false
        val (s, _, _) = session(handler = { action ->
            when {
                action == "GetTransportInfo" -> transport(if (paused) "PAUSED_PLAYBACK" else "PLAYING")
                action == "Pause" -> { paused = true; "" }
                else -> posInfo()
            }
        })
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        s.pause()
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Paused>().first() }
        s.stop()
    }

    // ── 返工⑭（2026-10-16 投屏确立自动弹遥控面板）：一次性票据签发/防抖语义 ──

    @Test fun `Playing确立签发自动弹票据且取走一次后不再签发`() = runBlocking {
        val (s, _, _) = session()
        assertFalse(s.takePanelAutoOpen())   // cast 前无票据
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertTrue(s.takePanelAutoOpen())
        // 取走即清：手动关闭面板/逐秒轮询重组都不得复活（同会话不再弹第二次）
        assertFalse(s.takePanelAutoOpen())
        s.stop()
    }

    @Test fun `cast失败Error不签发票据`() = runBlocking {
        val (s, _, _) = session(ip = null)
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Error>().first() }
        assertFalse(s.takePanelAutoOpen())
        s.stop()
    }

    @Test fun `stop后不签发票据且二次cast重新签发`() = runBlocking {
        val (s, _, _) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertTrue(s.takePanelAutoOpen())
        s.stop()   // 拉回本机/断开投屏：只走 Idle，不签发——「拉回后不弹」
        assertFalse(s.takePanelAutoOpen())
        s.cast(device, media.copy(title = "第二部"))   // 下一轮 cast 成功：重新签发
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertTrue(s.takePanelAutoOpen())
        s.stop()
    }

    // ── 返工⑮（2026-10-16 连接中态前置发射）：beginConnecting/abortConnecting 状态机语义 ──

    @Test fun `beginConnecting从Idle同步置位并返回true`() {
        val (s, _, _) = session()
        // 点击瞬间同步置 Connecting（预处理挪到 Connecting 态内进行）——不等任何挂起 IO
        assertTrue(s.beginConnecting(device, media))
        val st = s.state.value
        assertTrue("点击后状态必须已是 Connecting", st is CastUiState.Connecting)
        assertEquals(device.udn, (st as CastUiState.Connecting).device.udn)
    }

    @Test fun `beginConnecting在Playing会话不碰状态返回false`() = runBlocking {
        val (s, _, _) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        // 换投场景：活跃已确立会话不抢跑置 Connecting（预处理失败无处回退），切换仍由
        // cast() 内部完成
        val other = CastDevice("udn-2", "卧室电视", "http://192.168.1.9:49152/control", null)
        assertFalse(s.beginConnecting(other, media))
        val st = s.state.value
        assertTrue("Playing 会话不得被打回 Connecting", st is CastUiState.Playing)
        s.stop()
    }

    @Test fun `abortConnecting同设备落Error_异设备不碰`() {
        val (s, _, _) = session()
        assertTrue(s.beginConnecting(device, media))
        // 设备不匹配（用户已改投他处）：不碰状态
        val other = CastDevice("udn-2", "卧室电视", "http://192.168.1.9:49152/control", null)
        s.abortConnecting(other, "预处理失败")
        assertTrue(s.state.value is CastUiState.Connecting)
        // 同设备：落 Error（与 notifyIdleError/surfacePreCastFailure 链同一来源）
        s.abortConnecting(device, "片源无法读取（NAS 连接失败），已取消投屏")
        val err = s.state.value
        assertTrue(err is CastUiState.Error)
        assertEquals("片源无法读取（NAS 连接失败），已取消投屏", (err as CastUiState.Error).message)
    }

    @Test fun `abortConnecting消息null落Idle不弹错`() {
        val (s, _, _) = session()
        assertTrue(s.beginConnecting(device, media))
        // 协程取消（用户关面板）路径：静默落 Idle（observeLocks 对 Idle 只释放锁不弹错）
        s.abortConnecting(device, null)
        assertEquals(CastUiState.Idle, s.state.value)
    }

    // ── 终审修复回归（review C1/I2/I3/I4）──

    // 返工3-续（2026-10-16 真机 E01 误杀修复，推翻 review I3 旧拒起纪律）：sizeBytes<=0 =
    // 未知大小——不再 Error 拒起（SMB 列表 stat 丢失而库有 1.1GB 时被一票否决的真机回退），
    // 照常 SetURI→Play→Playing；DIDL res 省略 @size，流服务器侧不带 Content-Length
    @Test fun `未知大小不硬失败_照常推流且DIDL省略size`() = runBlocking {
        val (s, servers, soap) = session()
        s.cast(device, media.copy(sizeBytes = 0L))
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals(1, servers.size)
        assertTrue(soap.calls.any { it.first == "Play" })
        val setUri = soap.calls.first { it.first == "SetAVTransportURI" }
        assertTrue(setUri.second.contains("DIDL-Lite"))
        assertFalse("未知大小 DIDL 不得带 size 属性", setUri.second.contains("size="))
        s.stop()
    }

    @Test fun `已知大小DIDL带size属性`() = runBlocking {
        val (s, _, soap) = session()
        s.cast(device, media.copy(sizeBytes = 140_000_000L))
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        val setUri = soap.calls.first { it.first == "SetAVTransportURI" }
        assertTrue(setUri.second.contains("size=&quot;140000000&quot;"))
        s.stop()
    }

    @Test fun `cast被取消时回收已起的流服务器`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val servers = mutableListOf<FakeServer>()
        val soap = RecordingSoap { action ->
            if (action == "SetAVTransportURI") {
                kotlinx.coroutines.delay(60_000)   // 挂在 SOAP 挂起点，模拟组合销毁取消
            }
            ""
        }
        val s = CastSession(scope, CastSession.Deps(
            ip = { "192.168.1.2" },
            newServer = { _ -> FakeServer(1).also { servers += it } },
            soap = soap::invoke,
            pollIntervalMs = 10,
        ))
        val job = scope.launch { s.cast(device, media) }
        // 2026-09-28 审计 T-flaky：两处固定 delay 换状态轮询——慢 CI 下 300ms 硬等可能
        // 服务器未起（越界）或 SOAP 未挂起（取消进不了回收路径）造成假红
        withTimeout(5000) {
            while (servers.isEmpty() || soap.calls.none { it.first == "SetAVTransportURI" }) {
                kotlinx.coroutines.delay(10)
            }
        }
        job.cancel()
        withTimeout(5000) {
            while (servers[0].stopCount < 1) kotlinx.coroutines.delay(10)
        }
        assertEquals("取消必须回收流服务器", 1, servers[0].stopCount)
        scope.cancel()
    }

    // ── Deferred minors 修复回归（M7/M10）──

    @Test fun `STOPPED态Seek被拒时回退Play后再Seek`() = runBlocking {
        var playSeen = false
        val (s, _, soap) = session(handler = { action ->
            when {
                action == "Seek" && !playSeen -> throw SoapException(action, 500, null) // STOPPED 态拒 Seek
                action == "Play" -> { playSeen = true; "" }
                action == "GetTransportInfo" -> transport()
                else -> posInfo()
            }
        })
        s.cast(device, media, offsetMs = 60_000L)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        val actions = soap.calls.map { it.first }
        assertEquals("Seek 失败必须回退：Play 之后补 Seek", true, actions.lastIndexOf("Seek") > actions.indexOf("Play"))
        s.stop()
    }

    @Test fun `断开后的重投先等Stop落地再SetURI`() = runBlocking {
        val (s, _, soap) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        s.stop()
        s.cast(device, media.copy(title = "再投"))
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        val actions = soap.calls.map { it.first }
        val lastStop = actions.lastIndexOf("Stop")
        val secondSet = actions.lastIndexOf("SetAVTransportURI")
        assertTrue("重投的 SetURI 必须晚于上一会话的 Stop", secondSet > lastStop)
        s.stop()
    }

    @Test fun `SetVolume使用Channel加DesiredVolume线格式`() = runBlocking {
        val (s, _, soap) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        s.setVolume(30)
        withTimeout(3000) {
            while (soap.calls.none { it.first == "SetVolume" }) kotlinx.coroutines.delay(10)
        }
        val args = soap.calls.first { it.first == "SetVolume" }.second
        assertTrue(args.contains("<Channel>Master</Channel>"))
        assertTrue(args.contains("<DesiredVolume>30</DesiredVolume>"))
        s.stop()
    }

    // ── 隐私重锁停投屏（隐私计划 Task 15）：Stop 后补发空元数据 SetAVTransportURI 清 DIDL ──
    // 渲染器 Stop 后多数仍显示最后一次 SetURI 的 dc:title——只发 Stop 电视端会残留节目信息。

    @Test fun `重锁停投屏_Stop后补发空元数据SetURI且顺序正确`() = runBlocking {
        val (s, servers, soap) = session()
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        s.stopAndClearMetadata()
        assertEquals("流服务器必须回收（复用 stop 语义）", 1, servers[0].stopCount)
        assertEquals("会话必须回 Idle", CastUiState.Idle, s.state.value)
        val actions = soap.calls.map { it.first }
        val lastStop = actions.lastIndexOf("Stop")
        val clearIdx = actions.lastIndexOf("SetAVTransportURI")
        assertTrue("清 DIDL 必须晚于 Stop（乱序会被渲染器拒或复活旧元数据）", clearIdx > lastStop)
        val clearArgs = soap.calls[clearIdx].second
        assertTrue("CurrentURI 必须清空", clearArgs.contains("<CurrentURI></CurrentURI>"))
        assertTrue("CurrentURIMetadata 必须清空", clearArgs.contains("<CurrentURIMetadata></CurrentURIMetadata>"))
        assertFalse("清 DIDL 不得带任何 DIDL-Lite 残留", clearArgs.contains("DIDL-Lite"))
    }

    @Test fun `重锁停投屏_清DIDL失败不抛出_Stop照常完成`() = runBlocking {
        // 只拒「清 DIDL」那次 SetURI（第二次调用）；起播首拍照常放行
        var setUriCount = 0
        val (s, servers, soap) = session(handler = { action ->
            if (action == "SetAVTransportURI" && ++setUriCount > 1) throw RuntimeException("渲染器拒收空元数据")
            if (action == "GetTransportInfo") transport() else posInfo()
        })
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        // Stop 已让电视退出播放；清 DIDL 属尽力而为，失败只落日志不阻断重锁收尾
        s.stopAndClearMetadata()
        assertEquals(1, servers[0].stopCount)
        assertEquals(CastUiState.Idle, s.state.value)
        assertTrue(soap.calls.any { it.first == "Stop" })
    }

    @Test fun `重锁停投屏_无活跃会话为空操作不发SOAP`() = runBlocking {
        val (s, _, soap) = session()
        s.stopAndClearMetadata()
        assertEquals(0, soap.calls.size)
        assertEquals(CastUiState.Idle, s.state.value)
    }

    // ── 返工⑬（GetVolume 补完整性）：遥控面板滑杆初值跟随电视端实际音量 ──

    @Test fun `currentVolume解析电视端CurrentVolume`() = runBlocking {
        val (s, _, soap) = session(handler = { action ->
            when {
                action == "GetVolume" -> "<GetVolumeResponse><CurrentVolume>73</CurrentVolume></GetVolumeResponse>"
                action == "GetTransportInfo" -> transport()
                else -> posInfo()
            }
        })
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals(73, s.currentVolume())
        val args = soap.calls.first { it.first == "GetVolume" }.second
        assertEquals("<Channel>Master</Channel>", args)
        s.stop()
    }

    @Test fun `currentVolume无RCS或无会话返回null`() = runBlocking {
        val (s, _, _) = session()
        assertEquals(null, s.currentVolume())   // 未 cast：无会话
        s.cast(device.copy(volumeUri = null), media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals(null, s.currentVolume())   // 无 RCS 设备
        s.stop()
    }

    @Test fun `currentVolume查询失败静默返回null`() = runBlocking {
        val (s, _, _) = session(handler = { action ->
            when {
                action == "GetVolume" -> throw SoapException(action, null, null)
                action == "GetTransportInfo" -> transport()
                else -> posInfo()
            }
        })
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals(null, s.currentVolume())   // 查询失败静默
        s.stop()
    }

    // ── 返工③-701（2026-10-16 真机「电视执行该操作失败（错误码 701）」时好时坏）──
    // UPnP 701 = Transition not available：渲染器 SetURI 后 TRANSITIONING（装载中）收到的
    // Play 必被拒。修复 = ①就绪等待（GetTransportInfo 轮询至 STOPPED/PAUSED_PLAYBACK/PLAYING）
    // ②Play 撞 701 退避重试 3 次 ③Seek 撞 701 重试 1 次（叠加既有回退）。

    /** 模拟 jUPnP 适配器对设备 701 fault 的包装（userMessage 走 UpnpErrorText 真实映射） */
    private fun soap701(action: String): SoapException {
        val failure = org.jupnp.model.action.ActionException(701, "Transition not available")
        return SoapException(action, 500, failure, errorCode = 701, userMessage = UpnpErrorText.describe(failure, FakeCastText))
    }

    @Test fun `渲染器TRANSITIONING两拍后就绪等待生效_Play一次成功`() = runBlocking {
        var transPolls = 0
        var playCount = 0
        val (s, _, _) = session(handler = { action ->
            when {
                action == "Play" -> { playCount++; "" }
                action == "GetTransportInfo" -> {
                    transPolls++
                    // 前 2 拍装载中，第 3 拍就绪（STOPPED）；Play 后照常回 PLAYING
                    transport(if (playCount > 0) "PLAYING" else if (transPolls <= 2) "TRANSITIONING" else "STOPPED")
                }
                else -> posInfo()
            }
        })
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals("就绪后才 Play，且只发一次（旧流程此刻已 701）", 1, playCount)
        assertTrue("Play 前必须完成就绪轮询（2 拍 TRANSITIONING + 1 拍 STOPPED）", transPolls >= 3)
        s.stop()
    }

    @Test fun `Play撞701两次退避重试后成功`() = runBlocking {
        var playAttempts = 0
        val (s, _, _) = session(retryBackoffMs = 1, handler = { action ->
            when {
                action == "Play" -> {
                    playAttempts++
                    if (playAttempts <= 2) throw soap701(action)
                    ""
                }
                action == "GetTransportInfo" -> transport("STOPPED")   // 就绪轮询一拍即过
                else -> posInfo()
            }
        })
        s.cast(device, media)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals("初试 + 2 次重试 = 3 拍 Play", 3, playAttempts)
        s.stop()
    }

    @Test fun `Play连撞4次701重试耗尽_可读错误上抛`() = runBlocking {
        var playAttempts = 0
        val (s, servers, _) = session(retryBackoffMs = 1, handler = { action ->
            when {
                action == "Play" -> { playAttempts++; throw soap701(action) }
                action == "GetTransportInfo" -> transport("STOPPED")
                else -> posInfo()
            }
        })
        s.cast(device, media)
        val st = withTimeout(5000) { s.state.filterIsInstance<CastUiState.Error>().first() }
        assertEquals("701 特判文案（UpnpErrorText 真实映射贯通）", "电视还未就绪（701），已重试仍失败", st.message)
        assertEquals("初试 + 3 次重试共 4 拍，不再多打", 4, playAttempts)
        assertEquals("Error 前必须回收流服务器", 1, servers[0].stopCount)
        s.stop()
    }

    @Test fun `渲染器一直NO_MEDIA_PRESENT_就绪超时后仍发Play`() = runBlocking {
        var playCount = 0
        val (s, _, _) = session(readyTimeoutMs = 100, handler = { action ->
            when {
                action == "Play" -> { playCount++; "" }
                action == "GetTransportInfo" -> transport("NO_MEDIA_PRESENT")
                else -> posInfo()
            }
        })
        s.cast(device, media)
        withTimeout(5000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals("就绪等待超时不拦 Play（超时按现状继续，行为兼容）", 1, playCount)
        s.stop()
    }

    @Test fun `Seek撞701重试一次仍拒_走既有Play后再补Seek回退`() = runBlocking {
        var seekAttempts = 0
        var playSeen = false
        var postPlaySeek = 0
        val (s, _, _) = session(retryBackoffMs = 1, handler = { action ->
            when {
                action == "Seek" && !playSeen -> {
                    seekAttempts++
                    if (seekAttempts <= 2) throw soap701(action)   // 预play 首拍+重试拍都拒
                    ""
                }
                action == "Seek" -> { postPlaySeek++; "" }
                action == "Play" -> { playSeen = true; "" }
                action == "GetTransportInfo" -> transport()
                else -> posInfo()
            }
        })
        s.cast(device, media, offsetMs = 60_000L)
        withTimeout(3000) { s.state.filterIsInstance<CastUiState.Playing>().first() }
        assertEquals("pre-Play Seek 701 退避重试共 2 拍", 2, seekAttempts)
        assertEquals("重试仍拒 → 既有回退：Play 后补 Seek 一次成功", 1, postPlaySeek)
        s.stop()
    }
}
