package com.novax.app.player

import kotlin.math.roundToInt

/**
 * 播放速度上下限（2026-10-08 用户需求：倍速面板从固定八档扩为 0.1x~5.0x 连续可调）。
 * 两内核可行域：ExoPlayer 经 Sonic 变速不变调、libVLC setRate 均支持；≥3x 自动静音规则
 * （KEY_MUTE_ABOVE_2X）对滑杆出来的高速档照常生效。长按倍速六档（0.5~3x）是独立设置不放宽。
 */
const val PLAYBACK_SPEED_MIN = 0.1f
const val PLAYBACK_SPEED_MAX = 5f

/** 吸附到 0.1 步进并夹取到上下限（滑杆显示与松手提交必须同口径，避免显示 2.3 提交 2.3000001） */
fun snapPlaybackSpeed(v: Float): Float =
    ((v * 10).roundToInt() / 10f).coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)

/** 倍速显示文本：整数档 "2x"、小数档 "4.9x"（与 SpeedSheet 八档 chips 同款表达式，spec 钉死不新造格式） */
internal fun formatSpeedLabel(speed: Float): String =
    "${if (speed % 1f == 0f) speed.toInt() else speed}x"
