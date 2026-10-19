package com.novax.app.player

import org.junit.Assert.*
import org.junit.Test

class PlayerLockPolicyTest {
    @Test fun halfLockOn_twoStageTransition() {
        // 半锁开：未锁 → 半锁 → 全锁（用户口径「点一次进半锁，再点一次才进锁定」）
        assertEquals(LOCK_HALF, nextLockLevel(LOCK_NONE, halfLockEnabled = true))
        assertEquals(LOCK_FULL, nextLockLevel(LOCK_HALF, halfLockEnabled = true))
    }

    @Test fun halfLockOff_directToFull() {
        // 半锁关（设置关闭）：任何态一下直进全锁（现状行为）
        assertEquals(LOCK_FULL, nextLockLevel(LOCK_NONE, halfLockEnabled = false))
        assertEquals(LOCK_FULL, nextLockLevel(LOCK_HALF, halfLockEnabled = false))
    }

    @Test fun fullLockButtonNeverReachedButDefensive() {
        // 全锁态控制层已收、锁按钮不可达；防御性兜底仍是全锁（不出现「全锁点一下变半锁」回退）
        assertEquals(LOCK_FULL, nextLockLevel(LOCK_FULL, halfLockEnabled = true))
    }

    @Test fun lockLevelsDistinct() {
        assertTrue(LOCK_NONE != LOCK_HALF && LOCK_HALF != LOCK_FULL && LOCK_NONE != LOCK_FULL)
    }
}
