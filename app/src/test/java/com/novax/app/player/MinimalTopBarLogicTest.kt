// app/src/test/java/com/novax/app/player/MinimalTopBarLogicTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2 极简顶栏（rev4 §3.2 蓝块顶栏）纯函数锚：剩余时间标签。
 * 语义：durationMs<=0（直播/无时长）或 position 越界/diff<=0 → null=隐藏；
 * 否则 "-" + formatTime(durationMs-positionMs)（复用仓内 formatTime，mm:ss / h:mm:ss 双档）。
 */
class MinimalTopBarLogicTest {
    @Test fun `正常剩余 45 分钟输出负号加分钟秒`() =
        assertEquals("-45:00", remainingLabel(durationMs = 3_600_000, positionMs = 900_000))

    @Test fun `小时档剩余输出负号加时分秒`() =
        assertEquals("-1:30:00", remainingLabel(durationMs = 2 * 3_600_000, positionMs = 1_800_000))

    @Test fun `直播无时长隐藏`() = assertEquals(null, remainingLabel(durationMs = 0, positionMs = 5_000))

    @Test fun `负时长按无时长隐藏`() = assertEquals(null, remainingLabel(durationMs = -1, positionMs = 0))

    @Test fun `position 越界超出时长隐藏`() =
        assertEquals(null, remainingLabel(durationMs = 60_000, positionMs = 120_000))

    @Test fun `position 为负隐藏`() = assertEquals(null, remainingLabel(durationMs = 60_000, positionMs = -1))

    @Test fun `恰播完 diff 为零隐藏`() = assertEquals(null, remainingLabel(durationMs = 60_000, positionMs = 60_000))
}
