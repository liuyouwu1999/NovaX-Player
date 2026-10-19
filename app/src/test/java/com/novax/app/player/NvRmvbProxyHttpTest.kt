package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 外部审查整改（2026-10-25，spec：docs/superpowers/specs/2026-10-25-post-review-hardening-design.md §1）：
 * NvRmvbProxy HTTP 语义纯函数单测——503/404/405/416/206 响应构造与请求行方法解析。
 *
 * 测试边界（spec §1.3，诚实记录）：不做 socket 级集成测试——NvSourceRepository 为 final
 * 类且重依赖 SMB/SFTP 栈，开缝成本大于收益；「HEAD 同头无正文」「超限 503 走 pool 线程」
 * 由 serve()/start() 控制流保证，不在本层覆盖。
 *
 * 钳制语义对照 NvRmvbProxy.serve()（后缀区间 start 越界钳 0、rangeEndInclusive < start
 * 时 endExclusive 回落 totalBytes、contentLength == 0 走 416 分支）：钳制留在 serve()，
 * 本测试只断言构造函数对「已钳制入参」的逐字节输出。
 */
class NvRmvbProxyHttpTest {

    // ---- httpMethodOf：请求行首 token（无空格返回整行 trim，空串返回空）----

    @Test
    fun method_get() {
        assertEquals("GET", NvRmvbProxy.httpMethodOf("GET /stream?k=abc HTTP/1.1"))
    }

    @Test
    fun method_head() {
        assertEquals("HEAD", NvRmvbProxy.httpMethodOf("HEAD /stream?k=abc HTTP/1.1"))
    }

    @Test
    fun method_post() {
        assertEquals("POST", NvRmvbProxy.httpMethodOf("POST /stream?k=abc HTTP/1.1"))
    }

    @Test
    fun method_noSpace_returnsWholeLine() {
        // 无请求目标的畸形行：serve() 在 getOrNull(1) 处已静默关闭，到不了方法分派；
        // 纯函数层仅断言解析结果（非 GET/HEAD → 调用方按 405 处理，但此输入实际到不了那里）
        assertEquals("GARBAGE", NvRmvbProxy.httpMethodOf("GARBAGE"))
    }

    @Test
    fun method_emptyLine() {
        assertEquals("", NvRmvbProxy.httpMethodOf(""))
    }

    @Test
    fun method_leadingSpace() {
        // 前导空格：substringBefore(' ') 得空串，trim 后仍空 → 调用方按非 GET/HEAD 处理
        assertEquals("", NvRmvbProxy.httpMethodOf("  GET /x HTTP/1.1"))
    }

    // ---- statusResponse：无实体响应（CRLF、Content-Length: 0、Connection: close）----

    @Test
    fun resp_503() {
        assertEquals(
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            NvRmvbProxy.statusResponse("HTTP/1.1 503 Service Unavailable"),
        )
    }

    @Test
    fun resp_404() {
        // 与 2026-09 起 nonce 校验分支的现行输出逐字节一致（重构不改行为）
        assertEquals(
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            NvRmvbProxy.statusResponse("HTTP/1.1 404 Not Found"),
        )
    }

    @Test
    fun resp_405_withAllow() {
        assertEquals(
            "HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            NvRmvbProxy.statusResponse("HTTP/1.1 405 Method Not Allowed", "Allow: GET, HEAD"),
        )
    }

    @Test
    fun resp_416_contentRange() {
        assertEquals(
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */12345\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            NvRmvbProxy.statusResponse("HTTP/1.1 416 Range Not Satisfiable", "Content-Range: bytes */12345"),
        )
    }

    // ---- range206Headers：已钳制入参 → 206 响应头（含实体元数据，HEAD/GET 共用）----

    @Test
    fun resp_206_openRange() {
        // bytes=100-（开区间到 EOF）：totalBytes=1000 → endExclusive=1000，contentLength=900。
        // 该入参形态同时等价 serve() 钳制语义「rangeEndInclusive < start 时 endExclusive
        // 回落 totalBytes」的换算结果（钳制换算本身留在 serve()，未抽纯函数——spec §1.3 边界）
        assertEquals(
            "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Range: bytes 100-999/1000\r\n" +
                "Content-Length: 900\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Connection: close\r\n\r\n",
            NvRmvbProxy.range206Headers(start = 100, endExclusive = 1000, totalBytes = 1000, contentLength = 900),
        )
    }

    @Test
    fun resp_206_fullFile() {
        // 该入参等价 serve() 钳制语义「后缀区间 start 越界钳 0」（如 bytes=-1500 于
        // totalBytes=1000）换算后的形态（钳制在 serve()，此处只测已钳制输入的头构造）
        assertEquals(
            "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Range: bytes 0-999/1000\r\n" +
                "Content-Length: 1000\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Connection: close\r\n\r\n",
            NvRmvbProxy.range206Headers(start = 0, endExclusive = 1000, totalBytes = 1000, contentLength = 1000),
        )
    }

    @Test
    fun resp_206_singleByteTail() {
        // 闭区间 bytes=999-999：endExclusive=1000，contentLength=1 → 上界 999
        assertEquals(
            "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Range: bytes 999-999/1000\r\n" +
                "Content-Length: 1\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Connection: close\r\n\r\n",
            NvRmvbProxy.range206Headers(start = 999, endExclusive = 1000, totalBytes = 1000, contentLength = 1),
        )
    }
}
