package com.novax.app.player

/**
 * 播放页锁定级（2026-10-08 用户需求，主播放器与 RMVB 页共用）：
 * - [LOCK_NONE] 未锁：一切如常；
 * - [LOCK_HALF] 半锁（软锁）：调亮度/滑动调进度/快进快退/双击暂停/长按倍速/双指缩放全部照常，
 *   唯独单击屏幕不再弹控制层；不钉朝向；
 * - [LOCK_FULL] 全锁：吞掉一切手势 + 钉死朝向（2026-09-19 既有行为，零变化）。
 * 两级的解锁都是左下角锁图标双击一步回未锁（半锁下双击也直接解锁，用户口径）。
 */
internal const val LOCK_NONE = 0
internal const val LOCK_HALF = 1
internal const val LOCK_FULL = 2

/** 底栏锁按钮点击迁移：半锁开 = 未锁→半锁→全锁；半锁关 = 一下直进全锁（novax_half_lock_enabled）。
 *  解锁不走这里——控制层在两级下都已收起，唯一出口是左下角锁图标双击。
 *  2026-10-09：锁图标（角标）**长按 = 半锁直接升全锁**（已全锁时静默幂等）——是升锁快捷路而非
 *  解锁路；**双击角标仍是唯一解锁出口**（600ms 自实现窗口，语义不变）。 */
internal fun nextLockLevel(current: Int, halfLockEnabled: Boolean): Int =
    when {
        // 全锁态控制层已收、锁按钮不可达；防御性直通，杜绝「全锁点一下退回半锁」
        current == LOCK_FULL -> LOCK_FULL
        !halfLockEnabled || current == LOCK_HALF -> LOCK_FULL
        else -> LOCK_HALF
    }
