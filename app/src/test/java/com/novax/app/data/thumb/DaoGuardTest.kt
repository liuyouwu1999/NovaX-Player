package com.novax.app.data.thumb

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** 审计 B1：DB 故障不杀进程；取消必须透传（runCatching 直接用会吞 CE——重扫 cancel 的 pass 会继续 claim DB 行）。 */
class DaoGuardTest {
    @Test
    fun `DB 故障吞错返回 null 并落日志`() {
        val boomed = RuntimeException("disk I/O error")
        var logged: Throwable? = null
        assertNull(daoGuard(log = { logged = it }) { throw boomed })
        assertSame(boomed, logged)
    }

    @Test
    fun `正常路径原样返回值`() {
        assertSame(42, daoGuard(log = {}) { 42 })
    }

    @Test
    fun `CancellationException 透传不吞`() {
        assertThrows(CancellationException::class.java) {
            daoGuard(log = {}) { throw CancellationException("cancelled") }
        }
    }
}
