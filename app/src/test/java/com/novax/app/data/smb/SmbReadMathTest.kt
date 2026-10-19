package com.novax.app.data.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMB 随机读的 DataSpec position→offset 映射计算（纯函数单测） */
class SmbReadMathTest {

    // ---------- chunkLength ----------

    @Test
    fun chunkLength_clampedByMaxChunk() {
        // 请求 4MB、上限 1MB → 1MB
        assertEquals(1 shl 20, SmbReadMath.chunkLength(0, requested = 4 shl 20, contentLength = -1, maxChunk = 1 shl 20))
    }

    @Test
    fun chunkLength_smallRequestUnchanged() {
        assertEquals(4096, SmbReadMath.chunkLength(0, 4096, contentLength = -1, maxChunk = 1 shl 20))
    }

    @Test
    fun chunkLength_neverCrossesFileEnd() {
        // 文件 1MB，从 (1MB - 512KB) 处请求 1MB → 只剩 512KB
        val fileSize = (1 shl 20).toLong()
        assertEquals(512 * 1024, SmbReadMath.chunkLength(fileSize - 512 * 1024, 1 shl 20, fileSize, 1 shl 20))
    }

    @Test
    fun chunkLength_atOrBeyondEnd_isZero() {
        val fileSize = 1000L
        assertEquals(0, SmbReadMath.chunkLength(fileSize, 512, fileSize, 1 shl 20))
        assertEquals(0, SmbReadMath.chunkLength(fileSize + 10, 512, fileSize, 1 shl 20))
    }

    @Test
    fun chunkLength_unknownLengthKeepsRequest() {
        assertEquals(1 shl 20, SmbReadMath.chunkLength(9_000_000_000L, 1 shl 20, -1, 1 shl 20))
    }

    @Test
    fun chunkLength_negotiatedSmallerMaxWins() {
        // 协商 maxReadSize 只有 64KB（老 NAS）→ 以其为准
        assertEquals(64 * 1024, SmbReadMath.chunkLength(0, 1 shl 20, -1, 64 * 1024))
    }

    // ---------- isValidPosition（DataSource open 的 DataSpec.position 校验） ----------

    @Test
    fun validPosition_semantics() {
        assertTrue(SmbReadMath.isValidPosition(0, -1))
        assertTrue(SmbReadMath.isValidPosition(123, -1))
        assertTrue(SmbReadMath.isValidPosition(1000, 1000))   // 末尾（0 字节可读）合法
        assertFalse(SmbReadMath.isValidPosition(-1, -1))
        assertFalse(SmbReadMath.isValidPosition(1001, 1000))
    }

    // ---------- SmbTime：FILETIME → epoch millis ----------

    @Test
    fun windowsEpochConversion() {
        // 1970-01-01T00:00:00Z 的 FILETIME = 116444736000000000 (100ns)
        assertEquals(0L, SmbTime.windowsToEpochMillis(116_444_736_000_000_000L))
        // 1601-01-01T00:00:00Z（FILETIME 纪元）→ 负的 epoch
        assertEquals(-11_644_473_600_000L, SmbTime.windowsToEpochMillis(0L))
        // 2024-01-01T00:00:00Z = 1704067200s → FILETIME = (1704067200 + 11644473600) * 1e7
        val fileTime2024 = (1_704_067_200L + 11_644_473_600L) * 10_000_000L
        assertEquals(1_704_067_200_000L, SmbTime.windowsToEpochMillis(fileTime2024))
    }
}
