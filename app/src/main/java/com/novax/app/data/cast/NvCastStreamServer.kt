package com.novax.app.data.cast

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * C13 投屏出流服务器：[com.novax.app.player.NvRmvbProxy] 的泛化版（spec C13 §3）。
 *
 * 与 RMVB 代理的四处差异：
 * ① 绑定地址参数化——投屏绑 0.0.0.0（电视可达），单测绑 127.0.0.1；
 * ② `/stream` 响应带真实 Content-Type，且支持 HEAD（电视先探后拉）；
 * ③ Range 三态解析与 416 逻辑照搬 RMVB 代理（保留 BUG-C-R3-04 修复语义）；
 * ④ 新增 `/subtitle`：外挂字幕字节直出，统一 UTF-8（GBK 等转码在 provider 侧完成后注入）。
 *
 * 纪律（沿用 RMVB 代理恶性 bug 防线）：
 * - 生命周期随会话（CastSession），start/stop 均幂等；
 * - 端口随机，连接线程全 daemon + 统一登记，stop 时连已建立连接一起关停；
 * - 每连接 15s 头解析超时、异常一律吞掉关闭；
 * - 并发上限按「同时在线连接」计，超额回 503 后继续 accept。
 */
class NvCastStreamServer(
    /** 总字节数；<=0 = 未知大小（返工3-续 2026-10-16：不硬失败误杀——响应不带 Content-Length，
     *  按 HTTP/1.1 connection-close 分帧读至 EOF（DLNA 允许，seek 能力受限可接受）） */
    private val totalBytes: Long,
    private val opener: StreamOpener,
    private val subtitleProvider: (() -> ByteArray?)?,
    private val bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val videoMime: String,
    /** 并发上限（测试注入小值）；超额回 503 后立刻关并继续 accept（review M1） */
    private val maxConnections: Int = 64,
    /** 访问日志汇（返工3 2026-10-16）：每个进站请求一行（方法/路径/Range/UA/响应码）。
     * 生产 = NvCast Log.i（用户真机 `adb logcat -s NvCast` 即可诊断电视取流）；
     * 单测注入捕获器断言，不触 android.util.Log。 */
    private val accessLog: (String) -> Unit = { android.util.Log.i("NvCast", it) },
) : ServerHandle {
    /** 与 NvSourceRepository.openStreamBlocking 的桥接（Holder 装配时注入） */
    fun interface StreamOpener {
        fun open(offset: Long): InputStream
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "NvCastStream").apply { isDaemon = true }
    }
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** 2026-09-28 审计 B6：在途源流登记——stop() 关 socket 只能让「写」侧失败，
     * 阻塞在 NAS 读上的复制线程要靠关输入流才能打断（否则线程池堆积半开 NAS 会话） */
    private val openInputs = java.util.concurrent.ConcurrentHashMap<Socket, InputStream>()

    /** 2026-09-28 审计 C1：URL 随机 nonce——0.0.0.0 零鉴权时同网段任意设备扫到随机端口
     * 即可拉走整部媒体；token 进 URL（DIDL 明文可嗅探是 DLNA 固有形态，nonce 至少把
     * 「扫描即可拉」抬升为「必须嗅探会话」） */
    private val token: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    /** 服务根 URL（bind 0.0.0.0 时 host 无意义，外部 URL 由 Holder 用 Wi-Fi IP 另组） */
    val url: String
        get() = "http://${bindAddress.hostAddress}:${server?.localPort ?: 0}/"

    override val port: Int get() = server?.localPort ?: 0

    /** URL 附加段（"?k=<nonce>"）；ServerHandle 接口默认 null（测试假件不受影响） */
    override val tokenQuery: String get() = "k=$token"

    @Synchronized
    fun start(): String {        if (running.get() && server != null) return url
        running.set(true)
        server = ServerSocket(0, 16, bindAddress).also { ss ->
            Thread({
                while (running.get()) {
                    // 返工3 修复 4（2026-10-16）：accept 瞬时异常绝不静默退环——旧写法
                    // `runCatching { accept() }.getOrNull() ?: break` 在 accept 一次抛错
                    // （模拟器 netstack 抖动/瞬时 EINTR 等）即无声终结 accept 线程：端口仍
                    // LISTENING、电视取流全部黑洞、App 侧零感知零日志（模拟器实测复现：
                    // SetAVTransportURI/Play 全成功但 /stream 永无人 accept）。现在：仅
                    // stop()（running=false + close）才退出；其余一律记 NvCast 后退避重试。
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!running.get()) break   // stop() 已 close socket，正常退出
                        accessLog(
                            "stream-srv: accept 失败（${e.javaClass.simpleName}: ${e.message}），" +
                                "50ms 后重试（端口 ${server?.localPort ?: "?"} 仍在服务）",
                        )
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    if (activeCount >= maxConnections) {
                        // 超额并发拒单：回 503 再关（裸 close 表现为连接重置，电视侧重试无据可依）
                        accessLog("stream-srv: (超并发拒绝 $activeCount/$maxConnections) → 503")
                        runCatching {
                            // flush + shutdownOutput 再 close：Windows 下 write 后立即 close
                            // 偶发 WSAECONNABORTED 把已写响应一起丢掉（2026-09-28 测试实测 flake）
                            val out = socket.getOutputStream()
                            out.write(
                                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
                            out.flush()
                            runCatching { socket.shutdownOutput() }
                        }
                        runCatching { socket.close() }
                        continue
                    }
                    connections.add(socket)
                    pool.execute { serve(socket) }
                }
            }, "NvCastStream-accept").apply { isDaemon = true }.start()
        }
        return url
    }

    /** 关停：先置位再关 socket（accept/读线程自然退出），幂等；再关在途源流打断阻塞读（B6） */
    @Synchronized
    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
        connections.clear()
        openInputs.values.forEach { runCatching { it.close() } }
        openInputs.clear()
        pool.shutdown()
    }

    private val activeCount: Int get() = connections.size

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            // 2026-09-28 审计 C4：头行读取加单行上限——无界 readLine 可被局域网恶意连接
            // 用 GB 级无换行数据撑爆内存（15s 超时限时长不限字节）
            val requestLine = readLineCapped(reader) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "?"
            val requestTarget = parts.getOrNull(1) ?: return
            // 访问日志（返工3 2026-10-16）：一请求一行，响应码落定的时点打出；
            // query 段（含 token nonce）不入日志。异常中断的连接此前已按所发响应码落行。
            var accessLogged = false
            var rangeSeen = ""
            var userAgent = ""
            fun access(code: Int) {
                if (accessLogged) return
                accessLogged = true
                val path = requestTarget.substringBefore('?').ifEmpty { "?" }
                accessLog(
                    "stream-srv: $method $path" +
                        (if (rangeSeen.isNotEmpty()) " range=$rangeSeen" else "") +
                        (if (userAgent.isNotEmpty()) " ua=\"$userAgent\"" else "") +
                        " → $code",
                )
            }
            // nonce 校验（C1）：/stream 与 /subtitle 都必须携带正确 token，否则 404
            val query = requestTarget.substringAfter('?', "")
            if (query.substringAfter("k=", "").takeWhile { it != '&' } != token) {
                access(404)
                out404(socket)
                return
            }
            val route = requestTarget.substringBefore('?')
            val isHead = requestLine.startsWith("HEAD")
            // 未知大小分帧（返工3-续）：knownSize=false 时总长不可知——不宣示 Accept-Ranges、
            // 任何 Range 头整体忽略（RFC 9110 允许），恒 200 全量 connection-close 分帧读至 EOF。
            // 不用「206 + 星号 Content-Range」：last-pos 必须是具体数字（RFC 9110 §14.4），
            // 宣示假 206 反而不合规；seek 能力受限可接受（真 WMP 无 Accept-Ranges 即纯进给播放）
            val knownSize = totalBytes > 0
            var hasRange = false
            var rangeStart = 0L
            var rangeEndInclusive = -1L   // -1 = 开区间（到 EOF）
            var suffixLen = -1L           // bytes=-N 后缀长度；仅已知大小时能解出起点
            while (true) {
                val line = readLineCapped(reader) ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeSeen = line.substringAfter(':').trim()
                    if (knownSize) {
                        hasRange = true
                        // Range 三态（BUG-C-R3-04）：bytes=N- / bytes=N-M / bytes=-suffix
                        val spec = line.substringAfter('=').trim()
                        val first = spec.substringBefore('-').trim()
                        val second = spec.substringAfter('-', "").trim()
                        if (first.isNotEmpty()) {
                            rangeStart = first.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                            if (second.isNotEmpty()) rangeEndInclusive = second.toLongOrNull() ?: -1L
                        } else if (second.isNotEmpty()) {
                            suffixLen = second.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        }
                    }
                } else if (line.startsWith("User-Agent:", ignoreCase = true)) {
                    userAgent = line.substringAfter(':').trim()
                }
            }
            if (knownSize && suffixLen >= 0) rangeStart = (totalBytes - suffixLen).coerceAtLeast(0L)

            val out = socket.getOutputStream()
            if (route.startsWith("/subtitle")) {
                // 路由声明 charset=utf-8 就必须保证字节是 UTF-8：GBK/UTF-16 在此统一转码
                // （探测/转码复用本机播放同一套 SubtitleCharset，契约收口在服务器侧）
                val bytes = subtitleProvider?.invoke()?.let { raw ->
                    val csName = com.novax.app.player.SubtitleCharset.detect(raw)
                    if (csName.equals("UTF-8", ignoreCase = true)) {
                        raw
                    } else {
                        runCatching {
                            com.novax.app.player.SubtitleCharset
                                .transcodeToUtf8(raw, charset(csName))
                        }.getOrDefault(raw)
                    }
                }
                if (bytes == null) {
                    access(404)
                    out.write(notFound().toByteArray())
                } else {
                    access(200)
                    out.write(
                        simpleHeaders(
                            200, "OK",
                            "Content-Type: application/x-subrip; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\n",
                        ).toByteArray(),
                    )
                    if (!isHead) out.write(bytes)
                }
                out.flush()
                return
            }
            if (!route.startsWith("/stream")) {
                access(404)
                out.write(notFound().toByteArray())
                out.flush()
                return
            }
            val rangeSatisfiable = hasRange   // Range 只在已知大小时解析（未知大小整体忽略）

            val start = if (knownSize) rangeStart.coerceIn(0L, totalBytes) else 0L
            val endExclusive = when {
                // -1 = 无界（未知大小，读到 EOF）；已知大小时按显式端点/EOF 截断
                !knownSize -> -1L
                rangeEndInclusive >= start -> (rangeEndInclusive + 1).coerceAtMost(totalBytes)
                else -> totalBytes
            }
            val bounded = endExclusive >= 0L
            val contentLength = if (bounded) (endExclusive - start).coerceAtLeast(0L) else -1L
            if (rangeSatisfiable && bounded && contentLength == 0L) {
                // 起点越界（EOF 之后）：416 + 合法 Content-Range（BUG-C-R3-04 语义）
                access(416)
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$totalBytes\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.flush()
                return
            }

            access(if (rangeSatisfiable) 206 else 200)
            val sb = StringBuilder()
            if (rangeSatisfiable) {
                sb.append("HTTP/1.1 206 Partial Content\r\n")
                sb.append("Content-Range: bytes $start-${endExclusive - 1}/$totalBytes\r\n")
            } else {
                sb.append("HTTP/1.1 200 OK\r\n")
            }
            // 未知大小：不带 Content-Length、不宣示 Accept-Ranges（Connection: close 已声明，
            // 读至 EOF 即分帧语义；WMP 类按纯进给播放，seek 关闭）
            if (contentLength >= 0L) sb.append("Content-Length: $contentLength\r\n")
            if (knownSize) sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Type: $videoMime\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
            if (isHead) return

            val stream = opener.open(start)
            openInputs[socket] = stream   // B6：登记在途源流，stop 时关闭以打断阻塞读
            stream.use { input ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                socket.soTimeout = 0   // 正文传输不再受头解析超时约束
                var remaining = if (contentLength >= 0L) contentLength else Long.MAX_VALUE
                while (running.get() && remaining > 0) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.flush()
            }
        } catch (e: Exception) {
            // 客户端断开（seek 常规操作）或读通道异常：关连接即可，调用方按 Range 重试。
            // 返工3：不再全吞——源流打开失败（电视在拉、NAS 读不动）是「连上但不播」的
            // 关键线索，必须留痕；常态断开落 Log.d 不扰人
            val opened = openInputs.containsKey(socket)
            android.util.Log.d(
                "NvCast",
                "stream-srv: 连接结束（${if (opened) "传输中断/客户端断开" else "源流打开失败/响应前异常"}）" +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        } finally {
            runCatching { socket.close() }
            connections.remove(socket)
            openInputs.remove(socket)
        }
    }

    private fun notFound(): String = simpleHeaders(404, "Not Found", "Content-Length: 0\r\n")

    private fun out404(socket: Socket) {
        runCatching {
            socket.getOutputStream().write(notFound().toByteArray())
            socket.getOutputStream().flush()
        }
    }

    /** 头行有界读取：超限抛异常由 serve 的 catch 收口关连接（防无界 readLine OOM） */
    private fun readLineCapped(reader: BufferedReader): String? {
        val sb = StringBuilder(64)
        var n = 0
        while (true) {
            val c = reader.read()
            if (c < 0) return if (n == 0) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (++n > HEADER_LINE_MAX_BYTES) throw IllegalStateException("header line too long")
            sb.append(c.toChar())
        }
    }

    private fun simpleHeaders(code: Int, reason: String, extra: String): String =
        "HTTP/1.1 $code $reason\r\n" + extra + "Connection: close\r\n\r\n"

    companion object {
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val COPY_BUFFER_BYTES = 256 * 1024

        /** 头单行字节上限（2026-09-28 审计 C4） */
        private const val HEADER_LINE_MAX_BYTES = 16 * 1024

    }
}
