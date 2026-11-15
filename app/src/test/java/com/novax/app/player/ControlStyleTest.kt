package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlStyleTest {
    @Test fun `三个合法值解析`() {
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("classic"))
        assertEquals(NvControlStyle.MINIMAL, parseControlStyle("minimal"))
        assertEquals(NvControlStyle.STREAMING, parseControlStyle("streaming"))
    }
    @Test fun `null 与空串回退 classic`() {
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle(null))
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle(""))
    }
    @Test fun `未知值回退 classic 且不崩`() {
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("Classic"))   // 大写脏值
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("nplayer"))  // 乱值
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("\u0000"))   // 脏字节
    }
}
