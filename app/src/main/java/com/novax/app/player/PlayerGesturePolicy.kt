package com.novax.app.player

import kotlin.math.abs

internal enum class PlayerSlide { SEEK, BRIGHTNESS, VOLUME }

internal fun initialPlayerSlide(startX: Float, width: Float, dx: Float, dy: Float, slop: Float): PlayerSlide? {
    if (maxOf(abs(dx), abs(dy)) <= slop) return null
    return if (abs(dx) >= abs(dy)) PlayerSlide.SEEK
    else if (startX < width / 2f) PlayerSlide.BRIGHTNESS else PlayerSlide.VOLUME
}

// ---------------- 滑动快进（2026-10-08 用户需求：整屏步长 = 片长百分比，滑杆自由设定） ----------------
// 2026-09-27 等比方案（整屏=时长10%，30s~10min 夹取）的两个「不得劲」根因：
// ① 2 小时电影 10%=12min 被上限夹到 10min；② 短视频被下限抬高后真实占比失真。
// 现按用户口径改为唯一变量百分比（novax_swipe_full_percent，1~50 默认 10）：
// 选多少就是百分之多少，无隐藏倍率、无时长夹取；仅保留「过了 slop 的有效滑动 ≥±5s」底线。

/** 整屏滑动步长换算：fullPercent = 设置的整屏百分比（1~50，越界防御性夹取）；
 *  时长未知（直播流/慢源起播早期）按 60s@10% 基准等比缩放 */
internal fun swipeFullMsFor(durationMs: Long, fullPercent: Int): Long {
    val p = fullPercent.coerceIn(1, 50)
    if (durationMs <= 0) return SWIPE_FULL_DEFAULT_MS * p / 10
    return durationMs * p / 100
}

/** 时长未知时整屏步长的 10% 基准（百分比缩放的分母口径，见 [swipeFullMsFor]） */
internal const val SWIPE_FULL_DEFAULT_MS = 60_000L

/** 单次滑动步长的绝对下限：过了触摸 slop 的有效滑动至少跳 ±5s（不乘百分比——它就是「有意义」的底线） */
internal const val SWIPE_STEP_MIN_MS = 5_000L

/** 滑动 seek 步长：dragFraction = 滑动距离/屏宽（带符号，dp 或 px 口径皆可——比值相同） */
internal fun swipeSeekDeltaMs(dragFraction: Float, durationMs: Long, fullPercent: Int): Long {
    val raw = (dragFraction * swipeFullMsFor(durationMs, fullPercent)).toLong()
    if (raw == 0L) return 0L
    val sign = if (raw > 0) 1L else -1L
    return sign * maxOf(abs(raw), SWIPE_STEP_MIN_MS)
}
