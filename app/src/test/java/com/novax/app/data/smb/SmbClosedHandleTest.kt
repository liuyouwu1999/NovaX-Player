package com.novax.app.data.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * SMB「句柄/连接已失效」错误识别单测（2026-09-15 用户实测「画面卡死」根因修复的回归）。
 *
 * 现场日志（真机《低俗小说》13.9GB MKV 播放中）：
 *   NvError code=ERROR_CODE_IO_UNSPECIFIED msg=Source error
 *     caused by java.io.IOException: openRead …: DiskShare has already been closed
 *     caused by SMBRuntimeException: DiskShare has already been closed
 *   紧接着 io self-heal re-prepare **同样失败**（同一死条目）→ fatal 错误态。
 *
 * 修复要点：播放通道（LANE_PLAY）原先没有任何驱逐路径，池里一旦留下「share 已死但 TCP 仍
 * isConnected」的条目，读取就永久失败。识别该错误 → 驱逐本通道条目 → 重建重试一次。
 * 本测试锁定识别判据：真错误样本必须命中，业务类错误（路径不存在等）必须不命中
 * （否则会把「文件不存在」误判成连接故障，反复重连）。
 */
class SmbClosedHandleTest {

    /** 与 SmbClient.isClosedHandleError 同一判据的镜像实现（该类为 private，故在此固化语义） */
    private fun isClosedHandleError(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val m = t.message?.lowercase().orEmpty()
            if (m.contains("already been closed") ||
                m.contains("has been closed") ||
                m.contains("session") && m.contains("closed") ||
                m.contains("tree") && m.contains("disconnect") ||
                m.contains("broken pipe") ||
                m.contains("connection reset") ||
                m.contains("socket closed") ||
                m.contains("pipe closed")
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    private class SmbRuntimeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    @Test
    fun `真机现场样本必须命中（DiskShare 已关闭）`() {
        val live = IOException(
            "openRead /@share/media/movies/PulpFiction.mkv: DiskShare has already been closed",
            SmbRuntimeException("DiskShare has already been closed"),
        )
        assertTrue(isClosedHandleError(live))
    }

    @Test
    fun `嵌套在 cause 链深处的关闭错误也能命中`() {
        val deep = IOException(
            "SMB READ 失败 (offset=12345)",
            IOException("wrapper", SmbRuntimeException("Tree connect has been closed")),
        )
        assertTrue(isClosedHandleError(deep))
    }

    @Test
    fun `常见连接断流样本命中`() {
        assertTrue(isClosedHandleError(IOException("Broken pipe")))
        assertTrue(isClosedHandleError(IOException("Connection reset by peer")))
        assertTrue(isClosedHandleError(IOException("Socket closed")))
        assertTrue(isClosedHandleError(IOException("session is closed")))
    }

    @Test
    fun `业务类错误绝不能命中（否则会误重连）`() {
        // 文件不存在：应由上层直接报「路径无法访问」，不该当成连接故障反复重建
        assertFalse(isClosedHandleError(IOException("STATUS_OBJECT_NAME_NOT_FOUND: /movie.mkv")))
        assertFalse(isClosedHandleError(IOException("openRead /a/b.mkv: STATUS_ACCESS_DENIED")))
        // 认证失败
        assertFalse(isClosedHandleError(IOException("STATUS_LOGON_FAILURE: bad password")))
        // 空消息 / null 不得崩溃
        assertFalse(isClosedHandleError(IOException()))
        assertFalse(isClosedHandleError(RuntimeException("some unrelated failure")))
    }

    @Test
    fun `cause 链过长不会死循环`() {
        var tail: Throwable = IOException("tail")
        repeat(20) { i -> tail = IOException("layer$i", tail) }
        // 链深处没有关闭语义 → false，且必须正常返回（深度上限 8）
        assertFalse(isClosedHandleError(tail))
    }
}
