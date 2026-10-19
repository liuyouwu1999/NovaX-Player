package com.novax.app.player

import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RMVB 兜底播放的本地 HTTP Range 代理（2026-09-18 双内核）。
 *
 * 为什么需要：libVLC 只认 URI，接不进我们的 NvDataSource/SMB 连接池；直接把
 * smb:// 丢给 libVLC 走它自带的访问模块，则绕开我们的连接池/超时/自愈体系且认证
 * 参数拼 URI 不可靠。因此在 127.0.0.1 起一个一次性 HTTP 服务：libVLC 以普通
 * http Range 请求读流，每条连接映射到 repo.openStreamBlocking(offset)——
 * 读通道、连接池、lane 隔离与主播放器完全同源。
 *
 * 纪律（恶性 bug 防线）：
 * - 生命周期严格随 Activity：[start] 在 onCreate，[stop] 在 onDestroy，幂等；
 * - 端口随机（ServerSocket(0)），只绑 127.0.0.1，外部不可达；
 * - 连接线程全部 daemon + 统一登记，[stop] 时连同已建立连接一起关停；
 * - 每连接上限 15s 头解析超时、异常一律吞掉关闭（libVLC 会自动重试 Range）；
 * - 并发连接上限 [MAX_CONNECTIONS]（超额回 503 关闭、继续 accept）——注意每响应
 *   Connection:close 使每次 seek 都是一条新连接，防御上限只能按并发数计。
 */
class NvRmvbProxy(
    private val repo: NvSourceRepository,
    private val source: SourceEntity,
    private val path: String,
    private val totalBytes: Long,
    /** 会话级字节预算（2026-09-28 审计：抽帧复用本代理时兜底，超限熔断；播放路径不传=不限） */
    private val maxTotalBytes: Long = Long.MAX_VALUE,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "NvRmvbProxy").apply { isDaemon = true }
    }
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeCount = java.util.concurrent.atomic.AtomicInteger()

    /** 2026-09-28 审计 B6：在途源流登记——stop() 关 socket 只能让「写」侧失败，
     * 阻塞在 NAS 读上的复制线程要靠关输入流才能打断 */
    private val openInputs = java.util.concurrent.ConcurrentHashMap<Socket, java.io.InputStream>()

    /** 2026-09-28 审计 C3：URL 随机 token（Android 不隔离 loopback，本机任意应用可扫端口
     * 拉走正在播放的文件——nonce 进 MRL，本进程构造，无兼容性代价） */
    private val token: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * F-R3（2026-10-11 spec §4.1）：上游故障一次性回调；null = 调用方不关心（抽帧路径），
     * 零报告零日志，行为字面不变。2026-10-13「卡了再出」：宿主（播放页）收到后仅记录
     * 原因，不再直接弹层——弹层时机改由播放页卡死看门狗按「位置 10s 不动」裁决。
     * i18n Task 9：参数改 (kind, detail)——kind 为中性 token（[UPSTREAM_CONNECT_FAILED] /
     * [UPSTREAM_INTERRUPTED]），detail 为底层异常摘要；用户可见文案由宿主按 kind
     * 映射资源后拼装（代理层不再产出任何语言文案）。
     */
    @Volatile
    var onUpstreamFailure: ((Int, String) -> Unit)? = null

    /** F-R3：一次性报告位（CAS）——断网后 libVLC 的 Range 重试风暴只触发一次回调 */
    private val upstreamFailureReported = AtomicBoolean(false)

    /** F-R3 对偶半边（2026-10-13 spec §2.2）：上游恢复一次性回调；null = 调用方不关心
     * （抽帧路径，零报告零日志）。触发点：曾上报失败后，任一新连接成功打开上游流
     * （= SMB 层确已恢复；若 libVLC 不再重连则永无新连接，浮层按现状常驻）。 */
    @Volatile
    var onUpstreamRecovered: (() -> Unit)? = null

    /** 起服务并返回代理 URL（http://127.0.0.1:port/stream?k=<nonce>）；重复调用返回已起实例 */
    @Synchronized
    fun start(): String {
        if (running.get() && server != null) return url
        running.set(true)
        server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).also { ss ->
            Thread({
                while (running.get()) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: break
                    if (activeCount.get() >= MAX_CONNECTIONS) {
                        // 并发上限防御（BUG-C-R3-01：原实现按「累计服务条数」触顶 break，
                        // 而每响应 Connection:close 使每次 seek 都新建连接，长会话必触顶
                        // 且 accept 线程退出后代理停摆、无任何提示）。改为只拒超额并发并继续 accept。
                        // 2026-10-25 外部审查整改（spec §1.2a）：真回 503 再关（原只关不发，与
                        // 头注释「超额回 503」不符）；「写 503+关」丢进 pool 执行——accept 线程上
                        // write 遇对端零窗口可无限阻塞，会拖垮整个代理（含正常播放连接），
                        // cachedThreadPool 无上限，阻塞至多占用一个 pool 线程。
                        // 超限连接不计 activeCount（维持原口径）。
                        val over = socket
                        // 复审建议（2026-10-25）：stop() 的 pool.shutdown() 与此处 execute 存在
                        // 竞态窗口——被拒时 RejectedExecutionException 会杀 accept 线程且漏关，
                        // 拒绝兜底直接关（正常路径 pool.execute { serve } 为既有行为，不动）。
                        runCatching {
                            pool.execute {
                                runCatching {
                                    over.getOutputStream().apply {
                                        write(statusResponse("HTTP/1.1 503 Service Unavailable").toByteArray())
                                        flush()
                                    }
                                }
                                runCatching { over.close() }
                            }
                        }.onFailure { runCatching { over.close() } }
                        continue
                    }
                    activeCount.incrementAndGet()
                    connections.add(socket)
                    pool.execute { serve(socket) }
                }
            }, "NvRmvbProxy-accept").apply { isDaemon = true }.start()
        }
        return url
    }

    val url: String
        get() = "http://127.0.0.1:${server?.localPort ?: 0}/stream?k=$token"

    /** 关停：先置位再关 socket（accept/读线程自然退出），幂等；再关在途源流打断阻塞读（B6） */
    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
        connections.clear()
        openInputs.values.forEach { runCatching { it.close() } }
        openInputs.clear()
        pool.shutdown()
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            // 2026-09-28 审计 C4：头行读取加单行上限——无界 readLine 可被本机恶意连接
            // 用 GB 级无换行数据撑爆内存（15s 超时限时长不限字节）
            val requestLine = readLineCapped(reader) ?: return
            // nonce 校验（C3）：MRL 由本进程构造（见 url），缺 token/不符直接 404
            val requestTarget = requestLine.split(" ").getOrNull(1) ?: return
            if (requestTarget.substringAfter("k=", "").takeWhile { it != '&' && it != ' ' } != token) {
                runCatching {
                    socket.getOutputStream().write(statusResponse("HTTP/1.1 404 Not Found").toByteArray())
                    socket.getOutputStream().flush()
                }
                return
            }
            var rangeStart = 0L
            var rangeEndInclusive = -1L   // -1 = 开区间（到 EOF）
            while (true) {
                val line = readLineCapped(reader) ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    // Range 三态（BUG-C-R3-04：原实现 substringBefore('-') 把后缀区间
                    // "bytes=-500" 解析成 0 返回整文件、有界区间上界被丢弃）：
                    // bytes=N- / bytes=N-M / bytes=-suffix
                    val spec = line.substringAfter('=').trim()
                    val first = spec.substringBefore('-').trim()
                    val second = spec.substringAfter('-', "").trim()
                    if (first.isNotEmpty()) {
                        rangeStart = first.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        if (second.isNotEmpty()) rangeEndInclusive = second.toLongOrNull() ?: -1L
                    } else if (second.isNotEmpty()) {
                        val suffix = second.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        rangeStart = (totalBytes - suffix).coerceAtLeast(0L)
                    }
                }
            }
            // 2026-10-25 外部审查整改（spec §1.2b）：方法显式解析——原 contains("GET") 使 HEAD
            // 被静默关闭（"HEAD" 不含 "GET" 子串），与旧注释「HEAD 按 GET 处理」不符。
            // 现行语义：GET 全量服务；HEAD 与 GET 同构响应头（206/416 判定照旧，Content-Length/
            // Content-Range 为实体元数据，HEAD 同样发送）但不开上游流、不写正文；其余方法 405。
            // 无请求目标的畸形行在上方 getOrNull(1) 处已静默关闭，到不了这里。
            val method = httpMethodOf(requestLine)
            if (method != "GET" && method != "HEAD") {
                runCatching {
                    socket.getOutputStream().write(
                        statusResponse("HTTP/1.1 405 Method Not Allowed", "Allow: GET, HEAD").toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
                return
            }
            val sendBody = method == "GET"

            val start = rangeStart.coerceIn(0L, totalBytes.coerceAtLeast(0L))
            val endExclusive = if (rangeEndInclusive >= start) {
                (rangeEndInclusive + 1).coerceAtMost(totalBytes)
            } else {
                totalBytes
            }
            val contentLength = (endExclusive - start).coerceAtLeast(0L)
            if (contentLength == 0L) {
                // 起点越界（EOF 之后）：回 416 + 合法 Content-Range（BUG-C-R3-04：原实现在此
                // 仍发 206 且 Content-Range "bytes N-(N-1)/N" 非法）
                socket.getOutputStream().write(
                    statusResponse("HTTP/1.1 416 Range Not Satisfiable", "Content-Range: bytes */$totalBytes").toByteArray(),
                )
                socket.getOutputStream().flush()
                return
            }
            socket.getOutputStream().write(range206Headers(start, endExclusive, totalBytes, contentLength).toByteArray())
            socket.getOutputStream().flush()
            // HEAD（2026-10-25 整改）：实体元数据头已发，到此为止——不开上游流、不写正文
            if (!sendBody) return

            // 读通道与主播放器同源：openStreamBlocking（SMB lane 池/超时/自愈全部生效）；
            // 走 LANE_PLAY 播放专线（BUG-C-R4-05：原实现默认 LANE_MAIN，同源浏览/扫描
            // 的驱逐会踢断播放中 DiskShare）
            // F-R3（spec §4.1）：连接上游失败=真故障（区别于客户端断开），报告后原样抛给
            // 外层 catch 关连接——libVLC 眼里只有 localhost（从未坏过），真相必须由代理递出
            val stream = try {
                repo.openStreamBlocking(
                    source,
                    path,
                    start,
                    lane = com.novax.app.data.smb.SmbClient.LANE_PLAY,
                )
            } catch (e: Exception) {
                reportUpstreamFailure(UPSTREAM_CONNECT_FAILED, e.message ?: e.javaClass.simpleName)
                throw e
            }
            // F-R3 对偶（spec §2.2）：曾上报失败后，首个成功打开上游流的连接 = 恢复。
            // CAS true→false 单 winner，恰好一次通知；复位后本连接自身再失败可再次
            // 上报（振荡有界且诚实），风暴仍被一次性位压制。
            if (upstreamFailureReported.compareAndSet(true, false)) {
                android.util.Log.d("NvPerf", "rmvb proxy upstream recovered: port=${server?.localPort} start=$start")
                onUpstreamRecovered?.invoke()
            }
            openInputs[socket] = stream   // B6：登记在途源流，stop 时关闭以打断阻塞读
            stream.use { input ->
                val out = socket.getOutputStream()
                val buf = ByteArray(COPY_BUFFER_BYTES)
                socket.soTimeout = 0   // 正文传输不再受头解析超时约束
                // 2026-09-20 吞吐诊断（卡顿取证：真机据此区分「网络喂不饱」vs「软解吃不下」）：
                // 每 [THROUGHPUT_WINDOW_MS] 一条 NvPerf 送达速率，关连接时补总数
                var servedTotal = 0L
                var windowBytes = 0L
                var windowAt = android.os.SystemClock.elapsedRealtime()
                while (running.get()) {
                    // F-R3（spec §4.1）：正文读失败=上游通道故障（断网/RST/读超时——SMB
                    // SoTimeout 15s、OkHttp 30s 把硬断开与僵死都转成异常）；out.write 维持
                    // 外层 catch = 客户端侧断开（libVLC seek 常态），不报告
                    val n = try {
                        input.read(buf)
                    } catch (e: Exception) {
                        reportUpstreamFailure(UPSTREAM_INTERRUPTED, e.message ?: e.javaClass.simpleName)
                        throw e
                    }
                    if (n < 0) break
                    out.write(buf, 0, n)
                    servedTotal += n
                    windowBytes += n
                    // 2026-09-28 审计 F4：会话级字节预算（抽帧复用路径），超限熔断本连接——
                    // 调用方（extractWithLibVlc）的超时+finally 清理会兜住，最终标 FAILED
                    if (servedTotal >= maxTotalBytes) {
                        android.util.Log.w("NvPerf", "rmvb proxy byte budget hit: total=${servedTotal shr 20}MB start=$start")
                        break
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - windowAt >= THROUGHPUT_WINDOW_MS) {
                        val kbps = windowBytes * 1000f / (now - windowAt).coerceAtLeast(1) / 1024f
                        android.util.Log.d("NvPerf", "rmvb proxy thru=%.0fKB/s total=%dMB start=$start".format(kbps, servedTotal shr 20))
                        windowBytes = 0
                        windowAt = now
                    }
                }
                out.flush()
                if (servedTotal > 0) {
                    android.util.Log.d("NvPerf", "rmvb proxy close: total=${servedTotal shr 20}MB start=$start")
                }
            }
        } catch (_: Exception) {
            // 客户端主动断开（libVLC seek 时常规操作）或读通道异常：关连接即可，
            // libVLC 会按 Range 重发请求；此处绝不向调用方抛出
        } finally {
            runCatching { socket.close() }
            connections.remove(socket)
            openInputs.remove(socket)
            activeCount.decrementAndGet()
        }
    }

    /**
     * F-R3（2026-10-11 spec §4.1）上游故障一次性上报，双闸：
     * 1. running 闸——stop() 先置 running=false 再关在途输入流（B6 收口），其制造的读异常
     *    不是故障，报告前先过此闸，收口绝不误报；
     * 2. 判空 + CAS——无监听（抽帧路径）直接返回（零报告零日志）；一次性位保证断网后
     *    libVLC 的 Range 重试风暴只触发一次回调，全程仅一条 NvPerf 日志。
     * 在代理连接线程上回调，宿主自行切线程（见 RmvbPlayerActivity 的 runOnUiThread 接线）。
     */
    private fun reportUpstreamFailure(kind: Int, detail: String) {
        if (!running.get()) return
        val cb = onUpstreamFailure ?: return
        if (!upstreamFailureReported.compareAndSet(false, true)) return
        android.util.Log.w("NvPerf", "rmvb proxy upstream failure: kind=$kind detail=$detail")
        cb(kind, detail)
    }

    companion object {
        /** i18n Task 9：上游故障类别 token（连接失败 / 正文读中断），宿主按此选资源 */
        const val UPSTREAM_CONNECT_FAILED = 1
        const val UPSTREAM_INTERRUPTED = 2

        private const val HEADER_TIMEOUT_MS = 15_000
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val MAX_CONNECTIONS = 64

        /** 头单行字节上限（2026-09-28 审计 C4：防无界 readLine OOM） */
        private const val HEADER_LINE_MAX_BYTES = 16 * 1024

        /** 头行有界读取：读一行（含 \n 终止），超限抛异常由 serve 的 catch 收口关连接 */
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

        /**
         * 2026-10-25 外部审查整改（spec §1.2c）：方法解析与响应构造抽为 internal 纯函数，
         * JVM 单测逐字节断言（NvRmvbProxyHttpTest）；serve()/start() 的内联响应串全部改走此处。
         */

        /** 请求行首 token（HTTP 方法）。无空格返回整行 trim、空串返回空——调用方按非
         * GET/HEAD 分派；无请求目标的畸形行在 serve() 更早处已静默关闭，到不了分派。 */
        internal fun httpMethodOf(requestLine: String): String = requestLine.substringBefore(' ').trim()

        /** 无实体响应：状态行 + 可选附加头 + Content-Length: 0 + Connection: close（CRLF） */
        internal fun statusResponse(statusLine: String, vararg extraHeaders: String): String = buildString {
            append(statusLine).append("\r\n")
            for (h in extraHeaders) append(h).append("\r\n")
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }

        /** 206 响应头（HEAD/GET 共用：Content-Length/Content-Range 为实体元数据，HEAD 同样发送） */
        internal fun range206Headers(start: Long, endExclusive: Long, totalBytes: Long, contentLength: Long): String =
            "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Range: bytes $start-${endExclusive - 1}/$totalBytes\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Connection: close\r\n\r\n"

        /** 吞吐诊断日志窗口（毫秒） */
        private const val THROUGHPUT_WINDOW_MS = 10_000

        /**
         * 起代理的统一入口（协程上下文切 IO）：远程源（SMB/WebDAV）先探总大小；
         * size<=0（探不到）返回 null，调用方给出诚实错误而不是起一个 Range 算不准的服务。
         *
         * NonCancellable（2026-09-21 审计 P2）：块内 `start()` 一旦执行，代理就已监听
         * 127.0.0.1——若此时调用方协程被取消（RMVB 快速切集），withContext 默认会丢弃
         * 返回值并抛 CancellationException，代理从此无人引用、无人 stop（ServerSocket+
         * 线程池孤儿化至进程死）。NonCancellable 保证「已 start 必返回」，孤儿收口由
         * 调用方在拿到实例后 isActive 检查完成（见 RmvbPlayerActivity.openAndPlay）。
         *
         * @param maxTotalBytes 会话级读出字节预算（2026-09-28 审计 F4）：远程封面抽帧复用
         * 本代理时传 64MB 熔断；RMVB 播放路径不传（不限）。
         */
        suspend fun startFor(
            repo: NvSourceRepository,
            source: SourceEntity,
            path: String,
            totalBytes: Long,
            maxTotalBytes: Long = Long.MAX_VALUE,
        ): NvRmvbProxy? = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            if (totalBytes <= 0L) return@withContext null
            NvRmvbProxy(repo, source, path, totalBytes, maxTotalBytes).also { it.start() }
        }

        /**
         * 透传给 libVLC 的读缓冲提示（ms）：远程 1.5s 抗抖动，本地不必。
         * 2026-09-21 由 3s 下调：它是 seek 后输入缓存回填的时长下限（localhost 代理
         * 吞吐远超实时，回填快），3s 会让变速冲刷/用户拖动的恢复都白等 1~2s
         * （NAS 毛骗 RMVB 终验实测：flush 后 3.4s 无输出）；SMB 抖动由 SMB lane
         * 自身的重连自愈 + 1.5s 缓冲共同兜底。
         */
        fun networkCachingMs(isRemote: Boolean): Int = if (isRemote) 1_500 else 300
    }
}
