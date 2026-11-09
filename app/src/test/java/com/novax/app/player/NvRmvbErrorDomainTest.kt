package com.novax.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** spec B-3：兜底页错误域网络判定（与主播放器 media3 错误码域分域，勿混用）。 */
class NvRmvbErrorDomainTest {
    @Test fun `IOException 判网络类`() = assertTrue(isRmvbNetworkRecoverable(IOException("reset")))
    @Test fun `cause 链含 IOException 判网络类`() =
        assertTrue(isRmvbNetworkRecoverable(IllegalStateException("wrap", IOException("timeout"))))
    @Test fun `纯运行时异常判非网络`() = assertFalse(isRmvbNetworkRecoverable(IllegalStateException("decode")))
    @Test fun `null 判非网络`() = assertFalse(isRmvbNetworkRecoverable(null))
    @Test fun `cause 环不死循环`() {
        val a = IllegalStateException("a"); val b = IllegalStateException("b", a); a.initCause(b)
        assertFalse(isRmvbNetworkRecoverable(a))
    }
}
