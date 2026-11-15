package com.novax.app.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NvControlMetricsTest {
    // 窄幅新档位（spec §3.1 表）：pause 50/45/40，btn 42/38/36
    @Test fun `窄幅档位紧凑化`() {
        assertEquals(50.dp to 42.dp, nvNarrowControlMetrics(400.dp))
        assertEquals(45.dp to 38.dp, nvNarrowControlMetrics(350.dp))
        assertEquals(40.dp to 36.dp, nvNarrowControlMetrics(300.dp))
    }
    @Test fun `icon 比例与最小档 18dp 下限`() {
        // 42*0.48=20.16dp，不取整口径（测试与实现统一）
        assertEquals((42.dp * 0.48f), nvRowIconFor(42.dp))
        // 36*0.48=17.3 → 夹到 18dp 下限
        assertEquals(18.dp, nvRowIconFor(36.dp))
    }
}
