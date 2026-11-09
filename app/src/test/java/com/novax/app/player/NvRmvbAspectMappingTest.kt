package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** spec B-4：aspectModeToIndex 与 aspectIndexToZoomMode 互逆；唯一不对合点=下标 1（SURFACE_FIT_SCREEN→FILL→2）钉死。 */
class NvRmvbAspectMappingTest {
    @Test fun `四个对合档互逆`() {
        for (i in listOf(0, 2, 3, 4)) assertEquals(i, aspectModeToIndex(aspectIndexToZoomMode(i)))
    }
    @Test fun `不对合点下标1钉死为2`() = assertEquals(2, aspectModeToIndex(aspectIndexToZoomMode(1)))
    @Test fun `映射表逐值`() {
        assertEquals(0, aspectModeToIndex(AspectMode.FIT))
        assertEquals(2, aspectModeToIndex(AspectMode.FILL))
        assertEquals(3, aspectModeToIndex(AspectMode.R16_9))
        assertEquals(4, aspectModeToIndex(AspectMode.R4_3))
    }
}
