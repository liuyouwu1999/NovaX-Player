package com.novax.app.data.webdav

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Digest 认证（RFC 2617/7616 子集）单测（2026-09-29 配套 DigestAuth/DigestAuthInterceptor 落地）：
 *
 * - 质询解析：RFC 2617 §3.2.1 经典头的带引号值（qop 内含逗号）、无引号 token、键大小写、空白宽容；
 * - response 计算：RFC 2617 §3.5 Mufasa/testrealm@host.com 经典向量全链锁死
 *   （HA1 / HA2 / qop=auth response）+ RFC 2069 无 qop 回退向量；
 * - 拦截器：401 挑战应答重发一次、Basic 先发、nc 递增、换 nonce 重计、不支持算法友好失败
 *   （fake 应用拦截器充当服务器，纯 JVM 无网络）。
 */
class WebDavDigestAuthTest {

    // ---------- RFC 2617 §3.5 经典向量 ----------

    @Test
    fun rfc2617Vector_ha1_ha2_responseQop_fullChain() {
        // RFC 2617 §3.5 原文向量（缺一不可，防止编码/拼接漂移）
        val ha1 = DigestAuth.ha1("Mufasa", "testrealm@host.com", "Circle Of Life")
        assertEquals("939e7578ed9e3c518a452acee763bce9", ha1)
        val ha2 = DigestAuth.ha2("GET", "/dir/index.html")
        assertEquals("39aff3a2bab6126f332b942af96d3366", ha2)
        val response = DigestAuth.responseQop(
            ha1,
            "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            "00000001",
            "0a4f113b",
            "auth",
            ha2,
        )
        assertEquals("6629fae49393a05397450978507c4ef1", response)
    }

    @Test
    fun rfc2069Vector_responseWithoutQop() {
        // RFC 2069 回退口径（服务器未提供 qop）：response = MD5(HA1:nonce:HA2)
        val response = DigestAuth.responseRfc2069(
            "939e7578ed9e3c518a452acee763bce9",
            "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            "39aff3a2bab6126f332b942af96d3366",
        )
        assertEquals("670fd8c2df070c60b045671b8b24ff02", response)
    }

    // ---------- 质询解析 ----------

    @Test
    fun parseChallenge_rfcHeader_quotedValuesWithCommaAndSpaces() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
                "qop=\"auth,auth-int\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\", algorithm=MD5, stale=FALSE",
        )
        assertEquals("testrealm@host.com", challenge.realm)
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", challenge.nonce)
        assertEquals("auth,auth-int", challenge.qop)          // 引号内的逗号不得劈碎
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", challenge.opaque)
        assertEquals("MD5", challenge.algorithm)
        assertTrue(challenge.qopAuthAvailable)
        assertTrue(challenge.supported)
    }

    @Test
    fun parseChallenge_quotedAlgorithm_caseInsensitiveSchemeAndKeys_extraSpaces() {
        val challenge = DigestAuth.parseChallenge(
            "digest REALM = \"r 1\" ,  NONCE=\"n,1\" , QOP =\"auth\", opaque = \"o 2\", algorithm=\"md5\"",
        )
        assertEquals("r 1", challenge.realm)
        assertEquals("n,1", challenge.nonce)
        assertEquals("auth", challenge.qop)
        assertEquals("o 2", challenge.opaque)
        assertEquals("md5", challenge.algorithm)
        assertTrue(challenge.supported)   // 引号内空格保留 + 算法大小写不敏感
    }

    @Test
    fun parseChallenge_noAlgorithm_noOpaque_meansMd5AndRfc2069Fallback() {
        val challenge = DigestAuth.parseChallenge("Digest realm=\"srv\", nonce=\"abc123\"")
        assertEquals(null, challenge.algorithm)
        assertEquals(null, challenge.qop)
        assertEquals(null, challenge.opaque)
        assertTrue(challenge.supported)       // 缺省 = MD5
        assertFalse(challenge.qopAuthAvailable)  // 无 qop = RFC 2069 口径
    }

    @Test
    fun parseChallenge_mixedWithBasicToken_noValueTokenSkipped() {
        // 个别服务器把 Basic/Digest 混在一个头里：无值 token 不得毒化解析
        val challenge = DigestAuth.parseChallenge("Digest realm=\"r\", Basic, nonce=\"n\"")
        assertEquals("r", challenge.realm)
        assertEquals("n", challenge.nonce)
    }

    @Test
    fun parseChallenge_missingNonceOrRealm_throws() {
        try {
            DigestAuth.parseChallenge("Digest realm=\"r\"")
            fail("缺 nonce 应抛")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            DigestAuth.parseChallenge("Digest nonce=\"n\"")
            fail("缺 realm 应抛")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---------- 算法支持判定 ----------

    @Test
    fun unsupportedAlgorithms_md5sess_and_sha256Family() {
        assertFalse(DigestAuth.isSupportedAlgorithm("MD5-sess"))
        assertFalse(DigestAuth.isSupportedAlgorithm("SHA-256"))
        assertFalse(DigestAuth.isSupportedAlgorithm("SHA-256-sess"))
        assertFalse(DigestAuth.isSupportedAlgorithm("SHA-512-256"))
        assertTrue(DigestAuth.isSupportedAlgorithm(null))
        assertTrue(DigestAuth.isSupportedAlgorithm("MD5"))
        assertTrue(DigestAuth.isSupportedAlgorithm("md5"))
    }

    // ---------- Authorization 头组装 ----------

    @Test
    fun authorizationHeader_containsAllRfc2617Params_responseMatchesVectorInputs() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
                "qop=\"auth,auth-int\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\", algorithm=MD5",
        )
        val header = DigestAuth.authorizationHeader(
            challenge, "GET", "/dir/index.html", "Mufasa", "Circle Of Life", "00000001", "0a4f113b",
        )
        assertTrue(header.startsWith("Digest "))
        assertTrue(header.contains("username=\"Mufasa\""))
        assertTrue(header.contains("realm=\"testrealm@host.com\""))
        assertTrue(header.contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
        assertTrue(header.contains("uri=\"/dir/index.html\""))
        assertTrue(header.contains("qop=auth"))              // 单一 qop 裸 token
        assertTrue(header.contains("nc=00000001"))           // nc 裸 token 8 位 hex
        assertTrue(header.contains("cnonce=\"0a4f113b\""))
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun authorizationHeader_noQop_omitsQopNcCnonce_includesRfc2069Response() {
        val challenge = DigestAuth.parseChallenge("Digest realm=\"srv\", nonce=\"n1\"")
        val header = DigestAuth.authorizationHeader(
            challenge, "GET", "/a.mkv", "u", "p", "00000001", "ignored",
        )
        assertFalse(header.contains("qop="))
        assertFalse(header.contains("nc="))
        assertFalse(header.contains("cnonce"))
        val expected = DigestAuth.responseRfc2069(DigestAuth.ha1("u", "srv", "p"), "n1", DigestAuth.ha2("GET", "/a.mkv"))
        assertTrue(header.contains("response=\"$expected\""))
    }

    @Test
    fun authorizationHeader_quotesAreEscaped() {
        val challenge = DigestAuth.parseChallenge("Digest realm=\"weird\\\"realm\", nonce=\"n\"")
        val header = DigestAuth.authorizationHeader(
            challenge, "GET", "/x", "u", "p", "00000001", "c",
        )
        // 解析时 \" 还原为引号，回传时再次转义：realm 值 = weird"realm → 头里是 weird\"realm
        assertTrue(header.contains("realm=\"weird\\\"realm\""))
    }

    // ---------- 拦截器（fake 应用拦截器充当服务器，无网络） ----------

    /** 记录请求的 Authorization 头序列，可配置各阶段返回 */
    private class FakeServer : Interceptor {
        val authSeen = mutableListOf<String>()

        /** 第 i 次收到的请求返回的挑战；null = 直接 200 */
        val challenges = ArrayDeque<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            req.header("Authorization")?.let { authSeen += it }
            val challenge = if (challenges.isEmpty()) null else challenges.removeFirst()
            return if (challenge == null) {
                respond(req, 200, "OK")
            } else {
                respond(req, 401, "Unauthorized", "WWW-Authenticate" to challenge)
            }
        }

        private fun respond(req: Request, code: Int, message: String, vararg headers: Pair<String, String>): Response =
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body("body".toResponseBody(null))
                .build()
    }

    private fun clientWith(server: FakeServer): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(DigestAuthInterceptor())
            .addInterceptor(server)
            .build()

    /** Mufasa 凭据的 PROPFIND 风格请求（GET 即可，method 无关紧要） */
    private fun request(): Request = Request.Builder()
        .url("http://192.168.1.10:5005/dir/index.html")
        .header("Authorization", Credentials.basic("Mufasa", "Circle Of Life"))
        .tag(DigestAuthInterceptor.DigestCredentials::class.java,
            DigestAuthInterceptor.DigestCredentials("Mufasa", "Circle Of Life"))
        .build()

    private val rfcChallenge =
        "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
            "qop=\"auth\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\", algorithm=MD5"

    /** 从 Authorization 头里抠出 qop 口径 response（键值对提取，供拦截器链路对账） */
    private fun param(header: String, key: String): String {
        val m = Regex("$key=(\"?)([^,\"]+)\\1").find(header) ?: throw AssertionError("缺 $key: $header")
        return m.groupValues[2]
    }

    @Test
    fun interceptor_401DigestChallenge_retriesOnceWithDigestAuthorization() {
        val server = FakeServer().apply { challenges += rfcChallenge; challenges += null }
        val resp = clientWith(server).newCall(request()).execute()
        assertEquals(200, resp.code)
        assertEquals(2, server.authSeen.size)
        assertEquals("Basic", server.authSeen[0].substringBefore(' '))   // Basic 先发（现状保留）
        val digest = server.authSeen[1]
        assertTrue(digest.startsWith("Digest "))
        assertEquals("testrealm@host.com", param(digest, "realm"))
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", param(digest, "nonce"))
        assertEquals("/dir/index.html", param(digest, "uri"))
        assertEquals("auth", param(digest, "qop"))
        assertEquals("00000001", param(digest, "nc"))
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", param(digest, "opaque"))
        // response 用拦截器实际发出的 nc/cnonce 重算对账（cnonce 随机，故不能直接锁 RFC 字面量；
        // 纯函数本身已由 rfc2617Vector 用例锁死）
        val expected = DigestAuth.responseQop(
            DigestAuth.ha1("Mufasa", "testrealm@host.com", "Circle Of Life"),
            "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            param(digest, "nc"),
            param(digest, "cnonce"),
            "auth",
            DigestAuth.ha2("GET", "/dir/index.html"),
        )
        assertEquals(expected, param(digest, "response"))
    }

    @Test
    fun interceptor_sameNonceSecondChallenge_ncIncrements() {
        // 第一次：挑战 → 应答 → 200；第二次：同 nonce 再挑战 → nc=00000002（nonce 缓存复用）
        val server = FakeServer().apply {
            challenges += rfcChallenge; challenges += null
            challenges += rfcChallenge; challenges += null
        }
        val client = clientWith(server)
        assertEquals(200, client.newCall(request()).execute().code)
        assertEquals(200, client.newCall(request()).execute().code)
        assertEquals("00000001", param(server.authSeen[1], "nc"))
        assertEquals("00000002", param(server.authSeen[3], "nc"))
    }

    @Test
    fun interceptor_newNonce_resetsNcTo00000001() {
        val newNonceChallenge =
            "Digest realm=\"testrealm@host.com\", nonce=\"fresh1234567890\", qop=\"auth\", algorithm=MD5"
        val server = FakeServer().apply {
            challenges += rfcChallenge; challenges += null
            challenges += newNonceChallenge; challenges += null
        }
        val client = clientWith(server)
        assertEquals(200, client.newCall(request()).execute().code)
        assertEquals(200, client.newCall(request()).execute().code)
        assertEquals("00000001", param(server.authSeen[1], "nc"))
        assertEquals("00000001", param(server.authSeen[3], "nc"))     // 换 nonce 重计
        assertEquals("fresh1234567890", param(server.authSeen[3], "nonce"))
    }

    @Test
    fun interceptor_still401AfterRetry_surfacesOriginalFailurePath() {
        // 两次都回挑战（如密码错）：只重发一次，第二次 401 原样返回
        val server = FakeServer().apply {
            challenges += rfcChallenge
            challenges += rfcChallenge
        }
        val resp = clientWith(server).newCall(request()).execute()
        assertEquals(401, resp.code)
        assertEquals(2, server.authSeen.size)
    }

    @Test
    fun interceptor_unsupportedAlgorithm_sha256_throwsFriendlyIo() {
        val server = FakeServer().apply {
            challenges += "Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=SHA-256"
        }
        try {
            clientWith(server).newCall(request()).execute()
            fail("SHA-256 挑战应抛")
        } catch (e: IOException) {
            assertTrue("应为中文友好文案: ${e.message}", e.message!!.contains("不支持的 Digest 认证算法"))
        }
    }

    @Test
    fun interceptor_noDigestChallengeOrNoCredentials_passesThrough() {
        // Basic 挑战：不介入（维持原 401）
        val server = FakeServer().apply {
            challenges += "Basic realm=\"r\""
        }
        assertEquals(401, clientWith(server).newCall(request()).execute().code)
        // 无凭据请求（tag 缺失）：401 原样放行
        val anonReq = Request.Builder().url("http://192.168.1.10:5005/x").build()
        val anonServer = FakeServer().apply { challenges += rfcChallenge }
        val resp = clientWith(anonServer).newCall(anonReq).execute()
        assertEquals(401, resp.code)
        assertTrue(anonServer.authSeen.isEmpty())
    }
}
