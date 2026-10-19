package com.novax.app.data.thumb

import org.junit.Assert.assertFalse
import org.junit.Test

class NvThumbHolderGateTest {
    @Test
    fun thumbNetworkAllowed_defaultFalse_beforeInit() {
        // object 未 create()(JVM 测试无 Android 上下文)→ probe=null → 按受限保守处理
        assertFalse("宁可不抽,不可误放行", NvThumbHolder.thumbNetworkAllowed())
    }
}
