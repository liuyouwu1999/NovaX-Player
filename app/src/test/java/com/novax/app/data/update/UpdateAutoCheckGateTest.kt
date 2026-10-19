package com.novax.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L

class UpdateAutoCheckGateTest {
    @Test fun `全条件满足且超24h`() { assertTrue(UpdateAutoCheckGate.shouldAutoCheck(NOW, NOW - 25L*3600*1000, true, true, true)) }
    @Test fun `24h内不查`() { assertFalse(UpdateAutoCheckGate.shouldAutoCheck(NOW, NOW - 23L*3600*1000, true, true, true)) }
    @Test fun `首查lastCheck为零放行`() { assertTrue(UpdateAutoCheckGate.shouldAutoCheck(NOW, 0L, true, true, true)) }
    @Test fun `开关隐私离线任一不满足不查`() {
        assertFalse(UpdateAutoCheckGate.shouldAutoCheck(NOW, 0L, false, true, true))
        assertFalse(UpdateAutoCheckGate.shouldAutoCheck(NOW, 0L, true, false, true))
        assertFalse(UpdateAutoCheckGate.shouldAutoCheck(NOW, 0L, true, true, false))
    }
}
