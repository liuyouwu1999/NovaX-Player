package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset

class NvCastStreamServerTest {
    // 1MB 假视频 + GBK 字幕（spec C13 §8：GBK 源必须转 UTF-8 直出）
    private val video = ByteArray(1_000_000) { (it % 251).toByte() }
    private val gbkSub = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界\n"
        .toByteArray(Charset.forName("GBK"))

    private fun server(sub: ByteArray? = gbkSub, logs: MutableList<String>? = null) = NvCastStreamServer(
        totalBytes = video.size.toLong(),
        opener = { off -> ByteArrayInputStream(video, off.toInt(), video.size - off.toInt()) as InputStream },
        subtitleProvider = sub?.let { { it } },
        bindAddress = InetAddress.getByName("127.0.0.1"),
        videoMime = "video/mp4",
        // 返工3：访问日志可注入捕获（null = 默认 android.util.Log，JVM 单测 returnDefaultValues 兜底）
        accessLog = logs?.let { l -> { line: String -> l.add(line) } } ?: ({ }),
    )

    private fun get(url: String, range: String? = null, head: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            if (range != null) setRequestProperty("Range", range)
            requestMethod = if (head) "HEAD" else "GET"
        }

    /** token 鉴权后的请求路径（2026-09-28 审计 C1：服务器校验 ?k=<nonce>，测试请求统一经此） */
    private fun NvCastStreamServer.authedPath(route: String): String = "$route?$tokenQuery"

    @Test fun `全量_GET_200且MIME正确`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}")
            assertEquals(200, c.responseCode)
            assertEquals("video/mp4", c.contentType)
            assertEquals(1_000_000L, c.contentLengthLong)
            assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        } finally { s.stop() }
    }

    @Test fun `Range中段_206与Content-Range且内容偏移正确`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=100-199")
            assertEquals(206, c.responseCode)
            assertEquals("bytes 100-199/1000000", c.getHeaderField("Content-Range"))
            assertEquals(100, c.contentLengthLong)
            val body = c.inputStream.readBytes()
            assertTrue(body.contentEquals(video.copyOfRange(100, 200)))
        } finally { s.stop() }
    }

    @Test fun `后缀区间bytes负500`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=-500")
            assertEquals(206, c.responseCode)
            assertEquals("bytes 999500-999999/1000000", c.getHeaderField("Content-Range"))
        } finally { s.stop() }
    }

    @Test fun `EOF之后_416与合法Content-Range`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=2000000-")
            assertEquals(416, c.responseCode)
            assertEquals("bytes */1000000", c.getHeaderField("Content-Range"))
        } finally { s.stop() }
    }

    @Test fun `HEAD_有头无体`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", head = true)
            assertEquals(200, c.responseCode)
            assertEquals(1_000_000L, c.contentLengthLong)
            assertTrue(c.bodyOrNull()?.isEmpty() != false)
        } finally { s.stop() }
    }

    // ── BUG-C-R11-02（2026-10-16）：WMP（Windows「播放到」DMR）取流会话仿真 ──
    // WMP 拖动播放的典型请求序：HEAD 探流（常带 Range）→ GET bytes=0- 开区间 → seek 中段
    // 206。任一环不回 206/Content-Range 都会让 WMP 停在「无法取流」。真 WMP 无法在 CI 里
    // 端到端验证，这里用 HttpURLConnection 逐环断言其协议依赖。

    @Test fun `WMP式_HEAD带Range探流_206且ContentRange且无体`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=0-", head = true)
            assertEquals(206, c.responseCode)
            assertEquals("bytes 0-999999/1000000", c.getHeaderField("Content-Range"))
            assertEquals(1_000_000L, c.contentLengthLong)
            assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
            assertTrue("HEAD 不得有响应体", c.inputStream.readBytes().isEmpty())
        } finally { s.stop() }
    }

    @Test fun `WMP式_开区间GET_bytes0至EOF_206全量`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=0-")
            assertEquals(206, c.responseCode)
            assertEquals("bytes 0-999999/1000000", c.getHeaderField("Content-Range"))
            val body = c.inputStream.readBytes()
            assertEquals(1_000_000, body.size)
            assertTrue(body.contentEquals(video))
        } finally { s.stop() }
    }

    @Test fun `WMP式_会话序_探流后全量后seek中段`() {
        val s = server().also { it.start() }
        try {
            val base = "${s.url}${s.authedPath("stream")}"
            // ① 探流
            val head = get(base, "bytes=0-", head = true)
            assertEquals(206, head.responseCode)
            // ② 全量开区间拉流
            val full = get(base, "bytes=0-")
            assertEquals(206, full.responseCode)
            assertEquals(1_000_000, full.inputStream.readBytes().size)
            // ③ seek：跨连接中段区间（服务器 Connection: close，seek 即重连新 Range）
            val seek = get(base, "bytes=500000-500999")
            assertEquals(206, seek.responseCode)
            assertEquals("bytes 500000-500999/1000000", seek.getHeaderField("Content-Range"))
            assertTrue(seek.inputStream.readBytes().contentEquals(video.copyOfRange(500000, 501000)))
        } finally { s.stop() }
    }

    @Test fun `字幕路由输出UTF8`() {
        val s = server().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("subtitle")}")
            assertEquals(200, c.responseCode)
            assertTrue(c.contentType.startsWith("application/x-subrip"))
            val text = c.inputStream.readBytes().toString(Charset.forName("UTF-8"))
            assertTrue(text.contains("你好，世界"))
        } finally { s.stop() }
    }

    @Test fun `无字幕时404`() {
        val s = server(sub = null).also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("subtitle")}")
            assertEquals(404, c.responseCode)
        } finally { s.stop() }
    }

    @Test fun `并发超额回503而非裸断连接`() {
        // review M1：占满连接槽（发不完整头挂住服务端读），后续请求应得 503
        val tiny = NvCastStreamServer(
            totalBytes = video.size.toLong(),
            opener = { off -> ByteArrayInputStream(video, off.toInt(), video.size - off.toInt()) as InputStream },
            subtitleProvider = null,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            videoMime = "video/mp4",
            maxConnections = 1,
        ).also { it.start() }
        val holder = java.net.Socket(InetAddress.getByName("127.0.0.1"), tiny.port)
        try {
            // 占位请求必须带合法 token（2026-09-28 C1 鉴权后，错 token 会在头解析前被
            // 404 立即断开、占不住槽）——请求行合法但缺空行，让它停在头解析挂住槽位
            holder.getOutputStream().write("GET /${tiny.authedPath("stream")} HTTP/1.1\r\nHost: x\r\n".toByteArray())
            holder.getOutputStream().flush()
            val c = get("${tiny.url}${tiny.authedPath("stream")}")
            assertEquals(503, c.responseCode)
        } finally {
            runCatching { holder.close() }
            tiny.stop()
        }
    }

    @Test fun `stop幂等`() {
        val s = server()
        s.start(); s.stop(); s.stop()
    }

    /** 2026-09-28 审计 C1：0.0.0.0 零鉴权面收敛——缺 token/错 token 的 /stream、/subtitle 一律 404 */
    @Test fun `无token或错token一律404`() {
        val s = server().also { it.start() }
        try {
            assertEquals(404, get("${s.url}stream").responseCode)
            assertEquals(404, get("${s.url}stream?k=wrong").responseCode)
            assertEquals(404, get("${s.url}subtitle").responseCode)
            // 正确 token 仍 200（幂等断言：鉴权不破坏正常取流）
            assertEquals(200, get("${s.url}${s.authedPath("stream")}").responseCode)
        } finally { s.stop() }
    }

    private fun HttpURLConnection.bodyOrNull(): ByteArray? = runCatching {
        (errorStream ?: inputStream)?.let { it.readBytes() }
    }.getOrNull()
    // ── 返工3（2026-10-16）：访问日志——每个进站请求一行（方法/路径/Range/UA/响应码），token 不落日志 ──

    @Test fun `访问日志_一请求一行_含方法路径Range响应码UA且不落token`() {
        val logs = mutableListOf<String>()
        val s = server(sub = null, logs = logs).also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=0-9")
            c.setRequestProperty("User-Agent", "WMP-strict-probe")
            assertEquals(206, c.responseCode)
            assertEquals(10, c.inputStream.readBytes().size)
        } finally { s.stop() }
        if (logs.size != 1) fail("访问日志应恰一行: $logs")
        val line = logs[0]
        assertTrue("方法+路径: $line", line.contains("GET /stream"))
        assertTrue("Range 头: $line", line.contains("range=bytes=0-9"))
        assertTrue("UA: $line", line.contains("ua=\"WMP-strict-probe\""))
        assertTrue("响应码: $line", line.contains("→ 206"))
        assertFalse("token nonce 不得入日志: $line", line.contains("k="))
    }

    @Test fun `访问日志_错token与404路由也落行`() {
        val logs = mutableListOf<String>()
        val s = server(sub = null, logs = logs).also { it.start() }
        try {
            // 错 token → 404
            assertEquals(404, get("${s.url}/stream?k=wrong").responseCode)
            // 无 token → 404
            assertEquals(404, get("${s.url}/stream").responseCode)
            // 对的路由前缀但错路径 → 404
            assertEquals(404, get("${s.url}${s.authedPath("other")}").responseCode)
        } finally { s.stop() }
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.contains("→ 404") })
        assertTrue(logs[2].contains("GET /other"))
    }

    // ── 返工3-续（2026-10-16）：未知大小（totalBytes<=0）connection-close 分帧 ──
    // E01 真机「无法确定片源大小」误杀修复的流侧承接：不硬失败、恒 200 全量、不带 Content-Length
    // 读至 EOF、不宣示 Accept-Ranges（Range 整体忽略——RFC 9110 允许；206+星号 Content-Range 的
    // last-pos 必须为具体数字，不合规）。WMP 类探流 bytes=0-1 → 200 + 真实字节，纯进给播放成立。

    private fun unknownServer(logs: MutableList<String>? = null) = NvCastStreamServer(
        totalBytes = 0L,   // 未知大小
        opener = { off ->
            val s = off.toInt().coerceIn(0, video.size)
            ByteArrayInputStream(video, s, video.size - s) as InputStream
        },
        subtitleProvider = null,
        bindAddress = InetAddress.getByName("127.0.0.1"),
        videoMime = "video/mp4",
        accessLog = logs?.let { l -> { line: String -> l.add(line) } } ?: ({ }),
    )

    @Test fun `未知大小_GET_200无ContentLength无AcceptRanges_读至EOF全量`() {
        val s = unknownServer().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}")
            assertEquals(200, c.responseCode)
            assertEquals(-1L, c.contentLengthLong)   // 不带 Content-Length
            assertEquals("video/mp4", c.contentType)
            assertEquals(null, c.getHeaderField("Accept-Ranges"))   // 不宣示 Range 能力
            val body = c.inputStream.readBytes()     // connection-close 分帧读至 EOF
            assertEquals(1_000_000, body.size)
            assertTrue(body.contentEquals(video))
        } finally { s.stop() }
    }

    @Test fun `未知大小_WMP式探流bytes0至_忽略Range回200全量真实字节`() {
        val s = unknownServer().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=0-")
            assertEquals(200, c.responseCode)
            assertEquals(-1L, c.contentLengthLong)
            assertEquals(null, c.getHeaderField("Content-Range"))
            val body = c.inputStream.readBytes()
            assertEquals(1_000_000, body.size)
            assertTrue(body.contentEquals(video))
        } finally { s.stop() }
    }

    @Test fun `未知大小_中段区间同样忽略Range回200全量`() {
        val s = unknownServer().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=500000-500999")
            assertEquals(200, c.responseCode)
            assertEquals(-1L, c.contentLengthLong)
            assertEquals(1_000_000, c.inputStream.readBytes().size)
        } finally { s.stop() }
    }

    @Test fun `未知大小_后缀区间忽略Range回200全量`() {
        val s = unknownServer().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=-500")
            assertEquals(200, c.responseCode)
            assertEquals(-1L, c.contentLengthLong)
            assertEquals(1_000_000, c.inputStream.readBytes().size)
        } finally { s.stop() }
    }

    @Test fun `未知大小_HEAD_有头无体无ContentLength无AcceptRanges`() {
        val s = unknownServer().also { it.start() }
        try {
            val c = get("${s.url}${s.authedPath("stream")}", "bytes=0-", head = true)
            assertEquals(200, c.responseCode)
            assertEquals(-1L, c.contentLengthLong)
            assertEquals(null, c.getHeaderField("Accept-Ranges"))
            assertTrue("HEAD 不得有响应体", c.inputStream.readBytes().isEmpty())
        } finally { s.stop() }
    }

}
