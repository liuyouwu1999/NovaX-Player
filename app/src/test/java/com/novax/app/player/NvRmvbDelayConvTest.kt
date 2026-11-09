package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** spec B-6：ms→libVLC delay 参数换算唯一钉点（setAudioDelay/setSpuDelay(long)，微秒族，符号同向正=延后）。 */
class NvRmvbDelayConvTest {
    @Test fun `正毫秒转正微秒`() = assertEquals(50_000L, delayMsToVlc(50L))
    @Test fun `负毫秒转负微秒`() = assertEquals(-250_000L, delayMsToVlc(-250L))
    @Test fun `零透零`() = assertEquals(0L, delayMsToVlc(0L))
}
