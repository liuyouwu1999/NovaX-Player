// app/src/test/java/com/novax/app/player/MinimalGateTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalGateTest {
    // spec §3.2 门控矩阵 + 二轮 P1-2 唤起态顶替裁决
    @Test fun `未锁未唤起显示`() = assertEquals(true, persistentLayerVisible(false, false, false))
    @Test fun `半锁显示`() = assertEquals(true, persistentLayerVisible(false, false, false)) // 半锁不唤起控制层(单击拦截),controlsShown 恒 false
    @Test fun `全锁隐藏`() = assertEquals(false, persistentLayerVisible(true, false, false))
    @Test fun `错误态隐藏`() = assertEquals(false, persistentLayerVisible(false, true, false))
    @Test fun `唤起态隐藏由 line 进度条顶替`() = assertEquals(false, persistentLayerVisible(false, false, true))
}
