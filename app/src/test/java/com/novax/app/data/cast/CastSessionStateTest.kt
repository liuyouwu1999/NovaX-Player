package com.novax.app.data.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3（2026-10-09 追加批次）：Cast Connecting 僵尸态修复状态机回归。
 * 根因：面板关闭→协程取消→catch(CancellationException) 只回收服务器不写状态→
 * `_state` 永卡 Connecting→锁/发现栈永驻。构造方式照抄既有 CastSessionTest
 * （全函数注入依赖，纯 JVM 直实例化，isReturnDefaultValues=true）：
 * - 用例 A：本代际取消（面板关闭）→ 终态必须 Idle，不得卡 Connecting；
 * - 用例 B：看门狗超时（watchdogTimeoutMs 注入毫秒级小值，不得真等 30s）→
 *   显式 Error("投屏连接超时") → closeSession(notifyState=false) 收尾 → 终态仍 Error。
 */
class CastSessionStateTest {
    private val device = CastDevice("udn-1", "测试电视", "http://192.168.1.8:49152/control", "http://192.168.1.8:49152/vol")
    private val media = CastMedia(1, "/a/x.mp4", "测试片", 1000L, 240_000L, "nvx://1/a/x.mp4", null)

    private class FakeServer : ServerHandle {
        var stopCount = 0
        override val port: Int = 40001
        override fun stop() { stopCount++ }
    }

    /** 可编程假 SOAP：记录调用并按 action 出响应（handler 可挂起，供取消/超时用例挂住） */
    private class RecordingSoap(val handler: suspend (String) -> String) {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        suspend operator fun invoke(controlUri: String, service: String, action: String, argsXml: String): String {
            calls += action to argsXml
            return handler(action)
        }
    }

    /** 组装被测会话：SOAP 挂在 SetAVTransportURI（60s），即进 Connecting 后永挂，模拟不可达电视 */
    private fun hangingSession(
        watchdogTimeoutMs: Long,
    ): Triple<CastSession, MutableList<FakeServer>, Pair<CoroutineScope, RecordingSoap>> {
        val servers = mutableListOf<FakeServer>()
        val soap = RecordingSoap { action ->
            if (action == "SetAVTransportURI") kotlinx.coroutines.delay(60_000)
            ""
        }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = CastSession(
            scope,
            CastSession.Deps(
                ip = { "192.168.1.2" },
                newServer = { _ -> FakeServer().also { servers += it } },
                soap = soap::invoke,
                pollIntervalMs = 10,
                watchdogTimeoutMs = watchdogTimeoutMs,
                text = FakeCastText,
            ),
        )
        return Triple(session, servers, scope to soap)
    }

    /** 等到 SetAVTransportURI 已发出（会话已进 Connecting 且挂在 SOAP 上） */
    private suspend fun awaitConnecting(soap: RecordingSoap, servers: List<FakeServer>) {
        withTimeout(5000) {
            while (servers.isEmpty() || soap.calls.none { it.first == "SetAVTransportURI" }) {
                kotlinx.coroutines.delay(10)
            }
        }
    }

    // ── 用例 A：Connecting 中取消（面板关闭→castScope 取消路径）→ 终态 Idle ──

    @Test fun `Connecting中取消落Idle且回收服务器`() = runBlocking {
        val (s, servers, pair) = hangingSession(watchdogTimeoutMs = 60_000)   // 看门狗窗口外，不参与本用例
        val (scope, soap) = pair
        val job = scope.launch { s.cast(device, media) }
        awaitConnecting(soap, servers)
        job.cancel()
        // 修复点：本代际取消必须落 Idle（observeLocks 对 Idle 只释放锁不弹错），
        // 不得永卡 Connecting（僵尸态根因）
        withTimeout(5000) {
            while (s.state.value !is CastUiState.Idle) delay(10)
        }
        assertEquals(CastUiState.Idle, s.state.value)
        assertEquals("取消必须回收已起的流服务器", 1, servers[0].stopCount)
        scope.cancel()
    }

    // ── 用例 B：看门狗超时→显式 Error→notifyState=false 收尾→终态仍 Error（防覆写）──

    @Test fun `看门狗超时显式Error且收尾不覆写`() = runBlocking {
        val (s, servers, pair) = hangingSession(watchdogTimeoutMs = 200)
        val (scope, soap) = pair
        scope.launch { s.cast(device, media) }
        awaitConnecting(soap, servers)
        // 超时先显式 Error——复用 observeLocks 既有 Error toast 路径（不另加 toast 防双弹）
        val err = withTimeout(5000) { s.state.filterIsInstance<CastUiState.Error>().first() }
        assertEquals("投屏连接超时", err.message)
        // 覆写防线：closeSession 的 notifyState=true 是无条件写 Idle——收尾必须带 false，
        // 落定后状态必须保持 Error
        delay(300)
        assertTrue("终态必须保持 Error，不得被收尾 Idle 覆写", s.state.value is CastUiState.Error)
        assertEquals("超时必须回收流服务器", 1, servers[0].stopCount)
        scope.cancel()
    }
}
