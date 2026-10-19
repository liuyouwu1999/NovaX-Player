package com.novax.app.data.sftp

import com.novax.app.data.smb.RandomReadHandle
import com.novax.app.data.smb.SmbRandomReadStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * SftpReadHandle 适配契约 → SmbRandomReadStream 复用路径单测（2026-09-29）。
 *
 * 生产链路：SmbRandomReadStream(SftpReadHandle(sshj RemoteFile), 256KB, offset)。
 * sshj RemoteFile.read 语义（v0.38.0 源码核实）：短读返回实际字节（服务端按其消息上限
 * 截短应答），EOF 返回 -1——本 fake 按同一契约实现（每次 readAt 最多吐 SERVER_MAX 字节，
 * 越过末尾 -1），验证 SmbRandomReadStream 的块填充循环在「单请求短读」下的正确性：
 * 顺序读跨块数据无误、块尾 EOF 收尾、非对齐 offset 开读（ExoPlayer seek = 重新 open）。
 *
 * 真实 sshj RemoteFile 无法脱离 SSH 服务器构造，集成路径（连接/认证/真实 ls/open）
 * 不造假测试，待真源联调（见 SftpClient 类 kdoc）。
 */
class SftpReadStreamReuseTest {

    /** sshj RemoteFile.read 语义的内存 fake：单次至多 SERVER_MAX 字节（服务端短读），EOF -1 */
    private class SftpSemanticsFakeHandle(val size: Long) : RandomReadHandle {
        var readCalls = 0
            private set

        override fun readAt(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int {
            readCalls++
            if (fileOffset >= size) return -1                       // STATUS EOF → -1
            val n = minOf(length.toLong(), size - fileOffset, SERVER_MAX.toLong()).toInt()
            for (i in 0 until n) data[offset + i] = ((fileOffset + i) % 256).toByte()
            return n                                                // ≤ SERVER_MAX 的短读
        }

        override fun close() = Unit

        companion object {
            /** 模拟「服务端消息上限 100B」——远小于块大小，逼出块填充循环的短读补满路径 */
            const val SERVER_MAX = 100
        }
    }

    private fun stream(handle: SftpSemanticsFakeHandle, start: Long = 0) =
        SmbRandomReadStream(
            handle,
            maxReadSize = SftpClient.SFTP_MAX_READ_BYTES,
            startPosition = start,
            maxBlockBytes = 1024,   // 测试小块（生产 256KB，语义相同）
            log = { },
        )

    private fun readFully(s: SmbRandomReadStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(out, off, n - off)
            if (r < 0) throw IOException("EOF at $off/$n")
            off += r
        }
        return out
    }

    @Test
    fun sequentialRead_shortReadsAcrossBlocks_dataIntact() {
        val h = SftpSemanticsFakeHandle(5000L)   // 4 个整块 + 904 字节短尾块
        stream(h).use { s ->
            val got = readFully(s, 5000)
            for (i in got.indices) assertEquals("byte@$i", ((i % 256).toByte()), got[i])
        }
        assertTrue("短读补满路径应多次往返: ${h.readCalls}", h.readCalls > 5)
    }

    @Test
    fun eofAfterTailShortBlock_returnsMinusOne() {
        val h = SftpSemanticsFakeHandle(5000L)
        stream(h).use { s ->
            readFully(s, 5000)
            assertEquals(-1, s.read())
            assertEquals(-1, s.read(ByteArray(16), 0, 16))
        }
    }

    @Test
    fun openAtUnalignedOffset_seekReopenSemantics() {
        // ExoPlayer seek = close 旧流 + 新 offset 重新 open：起点 1500（非块对齐）
        val h = SftpSemanticsFakeHandle(5000L)
        stream(h, start = 1500).use { s ->
            val got = readFully(s, 2000)
            for (i in got.indices) assertEquals("byte@$i", (((1500 + i) % 256).toByte()), got[i])
        }
    }

    @Test
    fun singleReadContract_oneByteReadsWork() {
        val h = SftpSemanticsFakeHandle(3L)
        stream(h).use { s ->
            assertEquals(0, s.read())
            assertEquals(1, s.read())
            assertEquals(2, s.read())
            assertEquals(-1, s.read())
        }
    }
}
