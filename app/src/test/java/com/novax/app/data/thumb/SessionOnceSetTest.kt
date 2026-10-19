package com.novax.app.data.thumb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOnceSetTest {
    @Test
    fun firstCallTrue_secondCallFalse() {
        val s = SessionOnceSet()
        assertTrue(s.once("6:/a/b"))
        assertFalse(s.once("6:/a/b"))
    }
    @Test
    fun differentKeysIndependent() {
        val s = SessionOnceSet()
        assertTrue(s.once("6:/a"))
        assertTrue(s.once("6:/b"))
        assertFalse(s.once("6:/a"))
    }
}
