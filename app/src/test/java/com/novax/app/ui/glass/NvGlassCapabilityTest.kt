package com.novax.app.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class NvGlassCapabilityTest {
    @Test fun capabilityLadder() {
        assertEquals(NvGlassCapability.SCRIM, resolveGlassCapability(26))
        assertEquals(NvGlassCapability.SCRIM, resolveGlassCapability(30))
        assertEquals(NvGlassCapability.BLUR, resolveGlassCapability(31))
        assertEquals(NvGlassCapability.BLUR, resolveGlassCapability(32))
        assertEquals(NvGlassCapability.FULL, resolveGlassCapability(33))
        assertEquals(NvGlassCapability.FULL, resolveGlassCapability(34))
    }
    @Test fun fullCollapsesToBlurWhenRefractionUnavailable() {
        // 退路分支：REFRACTION_AVAILABLE=false 时 env 构造把 FULL 折叠为 BLUR（逻辑在 effectiveCapability）
        assertEquals(
            if (REFRACTION_AVAILABLE) NvGlassCapability.FULL else NvGlassCapability.BLUR,
            effectiveCapability(NvGlassCapability.FULL),
        )
    }
}
