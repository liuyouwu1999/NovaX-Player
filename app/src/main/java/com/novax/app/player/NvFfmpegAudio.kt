package com.novax.app.player

import androidx.media3.common.MimeTypes

/**
 * FFmpeg 音频扩展契约常量（2026-09-29）：与 media3-ffmpeg-ext 的 native 构建参数
 * （Task 2 调用 build_ffmpeg.sh 的解码器表）一一对应。构建脚本不反查——这里锁的是
 * 「文档语义」：哪些 mime 自此可在主播放器软解出声、不再触发无声横幅/软解记忆链。
 * 改解码器集时两处同步改：build_ffmpeg.sh 调用参数 + 本表 + NvFfmpegAudioTest。
 */
object NvFfmpegAudio {
    /** native 实际启用的解码器名（ffmpeg 内部名，与 FfmpegLibrary.getCodecName 输出对应） */
    val BUILT_DECODER_NAMES = listOf("ac3", "eac3", "dca", "truehd")

    /** 覆盖的音频 mime（JOC/HD 是 E-AC3/DTS 的扩展层容器 mime，解码取核心层） */
    val COVERED_MIMES = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_TRUEHD,
    )
}
