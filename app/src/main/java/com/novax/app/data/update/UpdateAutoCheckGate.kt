package com.novax.app.data.update

/**
 * spec §2.4：自动检查门——开关 + 隐私同意 + 在线 + 24h 节流，四者全过才放行。
 *
 * 约定：仅「自动检查」经此门并写 last_check；**手动检查不写 last_check**
 * （spec §2.4：手动检查不受节流，也不产生节流），故 lastCheckMillis 只反映自动检查时刻。
 */
object UpdateAutoCheckGate {

    /** 节流阈值：24 小时 */
    const val THRESHOLD_MILLIS = 24L * 60 * 60 * 1000

    fun shouldAutoCheck(
        now: Long,
        lastCheckMillis: Long,
        autoEnabled: Boolean,
        privacyAgreed: Boolean,
        online: Boolean,
    ): Boolean {
        if (!autoEnabled || !privacyAgreed || !online) return false
        if (lastCheckMillis <= 0L) return true
        return now - lastCheckMillis >= THRESHOLD_MILLIS
    }
}
