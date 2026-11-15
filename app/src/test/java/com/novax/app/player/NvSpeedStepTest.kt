// app/src/test/java/com/novax/app/player/NvSpeedStepTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R3 极简倍速三件套（rev4 §3.2 右列 ⊕/标签/⊖）步进锚：nvSpeedStep = snapPlaybackSpeed(current+delta)。
 * 语义：0.1 网格吸附 + 0.1~5.0 clamp（复用 [snapPlaybackSpeed]，防 1.1+0.1=1.2000001 类
 * 连点浮点漂移透出到引擎与显示）；重置不走本函数（组件层直接 onSetSpeed(1f)，无重置语义可测）。
 */
class NvSpeedStepTest {
    @Test fun `单步加 0 点 1 精确落档`() = assertEquals(1.1f, nvSpeedStep(1.0f, +0.1f))

    @Test fun `连点十步 1 点 0 到 2 点 0 全档精确无漂移`() {
        // 防裸浮点累加（1.1+0.1=1.2000001 … 0.30000004 透出）：逐步吸附后必须逐档精确
        var current = 1.0f
        for (step in 1..10) {
            current = nvSpeedStep(current, +0.1f)
            assertEquals("$step 步后应精确 ${(10 + step) / 10f}x", (10 + step) / 10f, current)
        }
        assertEquals(2.0f, current)
    }

    @Test fun `上界 5 点 0 再加 clamp 不动`() = assertEquals(5.0f, nvSpeedStep(5.0f, +0.1f))

    @Test fun `上界内正常步进 4 点 9 到 5 点 0 不被 clamp 吞`() = assertEquals(5.0f, nvSpeedStep(4.9f, +0.1f))

    @Test fun `下界 0 点 1 再减 clamp 不动`() = assertEquals(0.1f, nvSpeedStep(0.1f, -0.1f))
}
