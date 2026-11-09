package com.novax.app.data.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/** 审计 D1：readNBytes 为 API 33+，minSdk 26 不可用——readExactly 为全版本等价替身。 */
class CastReadExactlyTest {
    @Test
    fun `读满 n 字节返回全量`() {
        val data = ByteArray(300) { (it % 251).toByte() }
        val out = readExactly(ByteArrayInputStream(data), 256)
        assertEquals(256, out.size)
        assertArrayEquals(data.copyOf(256), out)
    }

    @Test
    fun `短流部分读返回已读前缀`() {
        val data = ByteArray(100) { 7 }
        val out = readExactly(ByteArrayInputStream(data), 256)
        assertEquals(100, out.size)
        assertArrayEquals(data, out)
    }

    @Test
    fun `n 为 0 返回空数组`() {
        assertEquals(0, readExactly(ByteArrayInputStream(ByteArray(5)), 0).size)
    }

    @Test
    fun `空流返回空数组`() {
        assertEquals(0, readExactly(ByteArrayInputStream(ByteArray(0)), 256).size)
    }
}
