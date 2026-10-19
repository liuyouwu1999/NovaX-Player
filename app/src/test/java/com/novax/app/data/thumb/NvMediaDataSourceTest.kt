package com.novax.app.data.thumb

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** NvMediaDataSource 的读满/EOF 纯语义（随机读包装的契约，C12 §三.2） */
class NvMediaDataSourceTest {

    @Test
    fun readFully_returnsMinusOne_whenNothingReadable() {
        val ins = ByteArrayInputStream(ByteArray(0))
        val buf = ByteArray(16)
        assertEquals(-1, NvMediaDataSource.readFully(ins, buf, 0, buf.size))
    }

    @Test
    fun readFully_fillsBufferAndReturnsTotal() {
        val data = ByteArray(100) { it.toByte() }
        val ins = ByteArrayInputStream(data)
        val buf = ByteArray(64)
        assertEquals(64, NvMediaDataSource.readFully(ins, buf, 0, 64))
        assertEquals(0.toByte(), buf[0])
        assertEquals(63.toByte(), buf[63])
    }

    @Test
    fun readFully_partialTail_returnsAvailableBytes() {
        val ins = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val buf = ByteArray(16)
        assertEquals(3, NvMediaDataSource.readFully(ins, buf, 0, 16))
        // 偏移写入缓冲区
        val ins2 = ByteArrayInputStream(byteArrayOf(9))
        assertEquals(1, NvMediaDataSource.readFully(ins2, buf, 4, 8))
        assertEquals(9.toByte(), buf[4])
    }
}
