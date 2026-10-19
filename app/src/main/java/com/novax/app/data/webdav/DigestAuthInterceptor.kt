package com.novax.app.data.webdav

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebDAV Digest 认证拦截器（2026-09-29 落地，替代「Digest 留 TODO」一期占位）。
 *
 * 工作模型（RFC 2617 服务器质询-应答）：
 * - 请求照旧先发（Basic 头仍预置——现状保留；服务器认 Basic 就在第一次 200 收场）；
 * - 响应 401 且 WWW-Authenticate 为 Digest 挑战时：解析挑战参数 → 按用户凭据计算
 *   response → 携带 Authorization: Digest **重发一次**；仍 401 则把第二次响应原样
 *   返回（走既有「HTTP 401 认证失败」错误路径，mapConnectError 归类为凭据错误）。
 * - MD5-sess / SHA-256 系挑战：直接抛中文友好 IOException（明确失败优于拿错算法
 *   撞 401 死循环，理由见 [DigestAuth.isSupportedAlgorithm]）。
 *
 * nonce/nc 缓存：按「host:port + realm」键存计数器（[nonces]，ConcurrentHashMap.compute
 * 原子换挡），OkHttp 多线程复用同一拦截器实例；同 nonce 连续复用则 nc 递增
 * （服务器按 nonce 跟踪 nc 单调性），401 换发新 nonce 则从 00000001 重计。
 *
 * 凭据来源：WebDavClient 发请求时经 Request tag 挂 [DigestCredentials]（随源实例），
 * 拦截器从请求取——不持有任何 per-source 状态，多个源共用同一 OkHttpClient 互不串号。
 *
 * 局限（kdoc 明示）：每次 401 只应答一次（无 stale=true 自动续期循环）；挑战解析
 * 失败/凭据缺失时不干预（维持原 401）。
 */
internal class DigestAuthInterceptor : Interceptor {

    /** Digest 凭据（源用户名 + 解密后口令；随请求 tag 传递） */
    internal data class DigestCredentials(val username: String, val password: String)

    /** 「host:port|realm」→ nonce 计数器；compute 内原子换挡（同 nonce 复用，异 nonce 重计） */
    private val nonces = ConcurrentHashMap<String, NonceState>()

    private class NonceState(val nonce: String) {
        val counter = AtomicInteger(0)
    }

    private val random = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        // 非 401 / 非 Digest 挑战 / 无凭据：原样放行（Basic 已在请求头里，交给服务器裁决）
        if (response.code != 401) return response
        val challengeHeader = response.header("WWW-Authenticate") ?: return response
        if (!challengeHeader.trim().startsWith("Digest", ignoreCase = true)) return response
        val creds = request.tag(DigestCredentials::class.java) ?: return response

        val challenge = try {
            DigestAuth.parseChallenge(challengeHeader)
        } catch (_: Exception) {
            return response  // 畸形挑战：无法可靠应答，维持原 401 错误路径
        }
        if (!challenge.supported) {
            // 明确不支持（MD5-sess / SHA-256 系）：抛中文友好错误（toIo 会原样透传 IOException 文案）
            throw IOException(
                "服务器要求不支持的 Digest 认证算法（${challenge.algorithm ?: "未知"}）。" +
                    "请在 NAS 的 WebDAV 设置中把摘要算法改为 MD5 后重试",
            )
        }

        val ncHex = nextNc(cacheKey(request, challenge.realm), challenge.nonce)
        val cnonce = newCnonce()
        // 请求目标 = 请求串（absolute-uri 通常取 path+query；本客户端不发查询串也兼容）
        val uri = request.url.encodedPath +
            (request.url.encodedQuery?.let { "?$it" } ?: "")
        val auth = DigestAuth.authorizationHeader(
            challenge, request.method, uri, creds.username, creds.password, ncHex, cnonce,
        )

        // 重发一次（响应体未消费必须先关，否则连接泄漏；Request.Builder.header 覆盖 Basic 头）
        response.close()
        return chain.proceed(request.newBuilder().header("Authorization", auth).build())
    }

    /**
     * 取该「host:port|realm」下 [nonce] 的下一个 nc（8 位十六进制，RFC 2617 §3.2.2）。
     * compute 原子段内完成「同 nonce 复用计数器 / 异 nonce 换新计数器」判定，
     * 并发首开（播放 + 字幕线程同时过拦截器）也不会双双拿到同一个 nc。
     */
    private fun nextNc(key: String, nonce: String): String {
        val state = nonces.compute(key) { _, existing ->
            if (existing != null && existing.nonce == nonce) existing else NonceState(nonce)
        }!!
        return "%08x".format(state.counter.incrementAndGet())
    }

    /** 拦截器级缓存键：源 host:port + realm（任务要求；realm 相同主机不同端口不串号） */
    private fun cacheKey(request: Request, realm: String): String =
        "${request.url.host}:${request.url.port}|$realm"

    /** 客户端随机数 cnonce（RFC 2617 要求每次请求唯一）：SecureRandom 16 hex */
    private fun newCnonce(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
