package com.novax.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放功能状态持有器（2026-09-28 审计 A 收敛）：AB 循环/睡眠定时/长按倍速+下滑锁定。
 *
 * 此前这三组状态在主播放器（PlayerScreen 的 remember 群）与 RMVB 软解页
 * （RmvbPlayerActivity 的字段群）各写一份，语义同步靠注释人肉对齐（「与主播放器同口径」
 * 在 RMVB 页出现 10+ 次），是两轮审计 bug 的高发产地。现收敛为本类：observable 状态
 * 可直接驱动 Compose，判定全部为纯函数（JVM 单测），两个宿主各持一份实例。
 *
 * 复位口径（与既有行为逐条对齐）：
 * - AB 循环：跟随引擎/换集清空（主播放器 remember(engine) 原语义）；
 * - 长按倍速三态：引擎重建/切集清零（BUG：换集保留锁定值会造成「UI 锁 2x、引擎 1x」错位）；
 * - 长按倍速静音（3x 直通不可听时自动静音）：切集必须恢复音量（BUG-C-R4-04）；
 * - 睡眠定时：跨引擎重建/换集仍有效（用户设的是「今晚 30 分钟」，不随换集消失）。
 */
class PlaybackFeatures {

    // ---- AB 循环（-1 = 未设点）----
    var loopAMs by mutableLongStateOf(-1L)
    var loopBMs by mutableLongStateOf(-1L)

    // ---- 睡眠定时（deadline -1 = 未启用；minutes 仅供菜单回显勾选）----
    var sleepDeadlineMs by mutableLongStateOf(-1L)
    var sleepMinutes by mutableIntStateOf(0)

    // ---- 长按倍速（夸克式）三态 + 倍速期自动静音 ----
    var speedShiftActive by mutableStateOf(false)
    var speedShiftValue by mutableFloatStateOf(PlaybackEngine.LONG_PRESS_SPEED)
    var speedShiftLocked by mutableStateOf(false)
    var speedBoostMuted by mutableStateOf(false)

    /** AB 循环清空（换集/引擎重建口径） */
    fun resetAbLoop() {
        loopAMs = -1L
        loopBMs = -1L
    }

    /** 长按倍速三态清零（引擎重建/切集口径） */
    fun resetSpeedShift() {
        speedShiftActive = false
        speedShiftValue = PlaybackEngine.LONG_PRESS_SPEED
        speedShiftLocked = false
    }

    /** 切集复位：AB + 倍速三态 + 静音恢复（RMVB applyQueueSwitch 既有口径；睡眠不动） */
    fun resetForEpisodeSwitch() {
        resetAbLoop()
        resetSpeedShift()
        speedBoostMuted = false
    }

    companion object {
        /**
         * AB 循环回跳判定（纯）：B 点已设且越过 B → 回跳到 A；null = 不循环。
         * 两宿主心跳/VLC 事件原有判定 `b > a && a >= 0 && pos >= b` 的统一形式。
         */
        fun loopSeekBackMs(positionMs: Long, loopAMs: Long, loopBMs: Long): Long? =
            if (loopBMs > loopAMs && loopAMs >= 0 && positionMs >= loopBMs) loopAMs else null

        /** 睡眠定时到期判定（纯）：deadline -1 = 未启用 */
        fun sleepFired(sleepDeadlineMs: Long, nowMs: Long): Boolean =
            sleepDeadlineMs > 0 && nowMs >= sleepDeadlineMs

        /**
         * 长按倍速目标值（纯）：已锁定 → 保持锁定值本身（BUG-C-R1-04：再次长按不得改写
         * 锁定值）；快进 → 设置值（防 DataStore 被外部改写出异常速率，0.5x~3x 夹取）；
         * 左手慢放 → 固定慢放速。
         */
        fun longPressTargetSpeed(
            locked: Boolean,
            currentValue: Float,
            fastForward: Boolean,
            configuredSpeed: Float,
            slowmoSpeed: Float,
        ): Float = when {
            locked -> currentValue
            fastForward -> configuredSpeed.coerceIn(0.5f, 3f)
            else -> slowmoSpeed
        }
    }
}
