package com.novax.app.data.webdav

import java.security.MessageDigest

/**
 * Digest 认证（RFC 2617/7616 子集）的挑战解析与 response 计算——纯函数集，JVM 可单测。
 *
 * 2026-09-29 落地：替代 authHeader() 里「Digest 留 TODO」的一期占位。支持范围：
 * - algorithm = MD5（缺省算法；绝大多数 NAS WebDAV —— Apache/nginx/群晖/QNAP 的默认值）
 * - qop = auth（含 qop 缺省的 RFC 2069 老服务器回退）
 * 明确不支持（命中即抛中文友好 IOException，见 [DigestAuth.isSupportedAlgorithm] kdoc）：
 * MD5-sess 与 SHA-256 系（SHA-256 / SHA-256-sess / SHA-512-256…）。
 *
 * 关键哈希全部用 RFC 2617 §3.5 的 Mufasa/testrealm@host.com 经典向量在单测锁死
 * （HA1 / HA2 / qop=auth 的 response 全链），防止实现漂移。
 */
internal object DigestAuth {

    /**
     * response 哈希输出为小写 hex（服务器按精确字符串比较）。
     * 编码用 UTF-8：RFC 2617 示例向量全 ASCII 按哪个算都一致；RFC 7616 §3.4.5.1 明确
     * username/password 按挑战声明的 charset（缺省 UTF-8）编码——中文口令/用户名依赖此行。
     */
    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** RFC 2617 §3.2.2.1：HA1 = MD5(username:realm:password) */
    fun ha1(username: String, realm: String, password: String): String =
        md5Hex("$username:$realm:$password")

    /** RFC 2617 §3.2.2.2：HA2 = MD5(method:uri)（qop=auth 口径，不含 body 哈希） */
    fun ha2(method: String, uri: String): String = md5Hex("$method:$uri")

    /** qop=auth 的 response（RFC 2617 §3.2.2.1 KD 前缀写法等价展开） */
    fun responseQop(ha1Hex: String, nonce: String, ncHex: String, cnonce: String, qop: String, ha2Hex: String): String =
        md5Hex("$ha1Hex:$nonce:$ncHex:$cnonce:$qop:$ha2Hex")

    /** RFC 2069（服务器未提供 qop）的 response = MD5(HA1:nonce:HA2) */
    fun responseRfc2069(ha1Hex: String, nonce: String, ha2Hex: String): String =
        md5Hex("$ha1Hex:$nonce:$ha2Hex")

    /**
     * 解析后的 Digest 挑战参数（RFC 2617 §3.2.1 子集；键名解析为小写）。
     */
    internal data class Challenge(
        val realm: String,
        val nonce: String,
        /** 服务端声明的 qop 选项（如 "auth,auth-int"），未提供为 null（RFC 2069） */
        val qop: String?,
        /** 服务端附带的不透明串，原样回传；无则 null 且不发 */
        val opaque: String?,
        /** 算法声明（已剥引号、原样大小写），未声明为 null（按 MD5 处理） */
        val algorithm: String?,
    ) {
        /** 服务端是否接受 qop=auth（qop 缺省 = 服务器根本不支持 qop，走 RFC 2069 回退） */
        val qopAuthAvailable: Boolean
            get() = qop != null && qop.split(',').any { it.trim().equals("auth", ignoreCase = true) }

        /** 本客户端是否支持该挑战的算法（null = 缺省 MD5） */
        val supported: Boolean get() = isSupportedAlgorithm(algorithm)
    }

    /**
     * 算法支持判定：MD5（或未声明）支持；MD5-sess 与 SHA-256 系明确不支持。
     *
     * 为什么不支持（2026-09-29 决定）：
     * - MD5-sess 把 HA1 绑定到 nonce+cnonce（HA1' = H(H(user:realm:pass):nonce:cnonce)），
     *   意味着拦截器要跨请求维护「每个 nonce 的会话 HA1」状态，而它防的是
     *   「服务端口令哈希库被拖库后的离线字典攻击」——对**持有明文口令的客户端**毫无增益，
     *   却给连接池多线程复用引入一整类状态同步 bug。
     * - SHA-256 系（RFC 7616）只有极新的服务器会挑战，且按 RFC 7616 §4 协商规则，
     *   会发 SHA-256 挑战的服务器在多数实现里同时提供 MD5 挑战；命中时给出中文友好
     *   错误（提示在 NAS 上改 MD5）比半吊子支持更诚实——摘要算法算错只会得到
     *   无差错的 401 死循环，用户根本无从排查。
     */
    fun isSupportedAlgorithm(algorithm: String?): Boolean {
        val a = algorithm?.trim()?.trim('"') ?: return true   // 未声明 = RFC 2617 缺省 MD5
        return a.equals("MD5", ignoreCase = true)
    }

    /**
     * 解析 WWW-Authenticate 头为 [Challenge]。
     *
     * 输入形如：
     * `Digest realm="testrealm@host.com", nonce="dcd98b7102…", qop="auth,auth-int",
     *  opaque="5ccc…", algorithm=MD5, stale=FALSE`
     *
     * 宽容性（真机服务器五花八门）：
     * - 方案名 Digest 大小写不敏感；键名大小写不敏感；
     * - 值可带引号（引号内 `,`、空格不分割；`\"` 反斜杠转义还原）或不带引号（token）；
     * - 逗号/等号周围允许任意空白。
     * 未识别的参数（stale/userhash/domain 等）忽略——我们只消费需要的五个。
     *
     * @throws IllegalArgumentException 缺 realm 或 nonce（无法应答的畸形挑战）
     */
    fun parseChallenge(headerValue: String): Challenge {
        val text = headerValue.trim()
        // 剥方案名前缀（"Digest "）——大小写不敏感；没有前缀（裸参数表）也容忍
        val paramsText = if (text.length >= 6 && text.substring(0, 6).equals("digest", ignoreCase = true)) {
            text.substring(6)
        } else {
            text
        }
        val params = parseAuthParams(paramsText)
        val challenge = Challenge(
            realm = params["realm"] ?: "",
            nonce = params["nonce"] ?: "",
            qop = params["qop"],
            opaque = params["opaque"],
            algorithm = params["algorithm"],
        )
        if (challenge.realm.isEmpty() || challenge.nonce.isEmpty()) {
            throw IllegalArgumentException("Digest 挑战缺少 realm/nonce: $headerValue")
        }
        return challenge
    }

    /**
     * RFC 2617 §3.2.1 auth-param 列表解析（键值均转小写键/原值）：
     * `key1=value1, key2="quoted, value" , key3=token`。
     * 引号串内的逗号/转义引号不分割（状态机扫描，split(',') 会把 qop="auth,auth-int" 劈碎）。
     */
    internal fun parseAuthParams(text: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i].isWhitespace() || text[i] == ',')) i++
            if (i >= n) break
            // 键：token（遇 '=' / ',' / 空白即止）
            val keyStart = i
            while (i < n && text[i] != '=' && text[i] != ',' && !text[i].isWhitespace()) i++
            val key = text.substring(keyStart, i).lowercase()
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n || text[i] != '=') {
                // 无值 token（混排挑战里的 "Basic" 等杂质）：跳到下一个逗号
                while (i < n && text[i] != ',') i++
                continue
            }
            i++  // 吃 '='
            while (i < n && text[i].isWhitespace()) i++
            val value: String
            if (i < n && text[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < n && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < n) i++   // RFC 2617 quoted-pair：\" \\ 还原
                    sb.append(text[i])
                    i++
                }
                i++  // 吃收尾引号（头被截断时循环已到尾，i==n 无害）
                value = sb.toString()
            } else {
                val vStart = i
                while (i < n && text[i] != ',') i++
                value = text.substring(vStart, i).trim()
            }
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    /**
     * 构建 Authorization 头值（RFC 2617 §3.2.2 口径，含 "Digest " 方案前缀）。
     * - 有 qop（且支持 auth）：带 qop/nc/cnonce，response 走 [responseQop]；
     * - 无 qop（RFC 2069）：省略 qop/nc/cnonce，response 走 [responseRfc2069]。
     * username/realm/nonce/cnonce/response/opaque 加引号并转义；nc/qop 为裸 token。
     */
    fun authorizationHeader(
        challenge: Challenge,
        method: String,
        uri: String,
        username: String,
        password: String,
        ncHex: String,
        cnonce: String,
    ): String {
        require(challenge.supported) { "不支持的 Digest 算法: ${challenge.algorithm}" }
        val ha1 = ha1(username, challenge.realm, password)
        val ha2v = ha2(method, uri)
        val useQop = challenge.qop != null && challenge.qopAuthAvailable
        val response = if (useQop) {
            responseQop(ha1, challenge.nonce, ncHex, cnonce, "auth", ha2v)
        } else {
            responseRfc2069(ha1, challenge.nonce, ha2v)
        }
        val parts = buildList {
            add("username=${quote(username)}")
            add("realm=${quote(challenge.realm)}")
            add("nonce=${quote(challenge.nonce)}")
            add("uri=${quote(uri)}")
            if (useQop) {
                add("qop=auth")
                add("nc=$ncHex")
                add("cnonce=${quote(cnonce)}")
            }
            add("response=${quote(response)}")
            challenge.opaque?.let { add("opaque=${quote(it)}") }
            add("algorithm=MD5")
        }
        return "Digest " + parts.joinToString(", ")
    }

    /** RFC 2617 quoted-string：反斜杠与引号转义（服务器给出的 nonce/opaque 可能含特殊字符） */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
