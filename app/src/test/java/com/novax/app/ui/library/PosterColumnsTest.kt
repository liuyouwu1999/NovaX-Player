package com.novax.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterColumnsTest {
    @Test fun explicitAndAutomaticColumns() {
        assertEquals(2, posterColumns("2", 800f))
        assertEquals(3, posterColumns("3", 800f))
        assertEquals(4, posterColumns("4", 800f))
        assertEquals(2, posterColumns("auto", 320f))
        assertEquals(3, posterColumns("auto", 400f))
        assertEquals(4, posterColumns("auto", 700f))
        assertEquals(5, posterColumns("auto", 1000f))
        assertEquals(3, posterColumns("999", 400f))
    }
}
