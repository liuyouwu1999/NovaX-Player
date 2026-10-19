package com.novax.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.novax.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器媒体信息快照（视频信息面板数据，2026-09-15 用户需求）。
 *
 * [of] 在组合期从 player 当前选中轨抽取；label 映射为纯函数（mime/数值 → 标签），
 * 无 Android 依赖可直接单测。未就绪/未提供的字段显示「--」。
 * i18n Task 9：用户可见标签（杜比视界、像素比、声道、归因提示）经 [text] 缝注入
 * （生产 = PlayerSheets 组合期 context::getString，单测注入 zh 原句假映射）；
 * 其余为语言中性专有名词（编见名/fps/Mbps）。
 */
data class MediaTrackInfo(
    val videoCodec: String,
    val resolution: String,
    val frameRate: String,
    val videoBitrate: String,
    val dynamicRange: String,
    val audioCodec: String,
    val channels: String,
    val sampleRate: String,
    val audioBitrate: String,
    val audioTrackCount: Int,
    val textTrackCount: Int,
    /**
     * 2026-09-15 用户实测：杜比视界实片「有声音无画面」归因提示。
     * 非空 = 该片源当前设备无法正确出画（如 DV Profile 5 无回退层），面板如实展示原因，
     * 不再让用户面对黑屏无解释。null = 正常。
     */
    val dynamicRangeNote: String? = null,
) {
    companion object {

        /** 选中轨的 Format（Player 接口无 videoFormat getter，从 currentTracks 取选中组） */
        private fun selectedFormat(tracks: Tracks, type: Int): Format? =
            tracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { g ->
                val idx = (0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0
                g.getTrackFormat(idx)
            }

        fun of(
            tracks: Tracks,
            videoW: Int,
            videoH: Int,
            // Task 9 fix（评审建议 4）：必传——防未来新调用点漏传时面板静态渲染成资源 ID 数字串
            text: (Int) -> String,
        ): MediaTrackInfo {
            val vf = selectedFormat(tracks, C.TRACK_TYPE_VIDEO)
            val af = selectedFormat(tracks, C.TRACK_TYPE_AUDIO)
            val vfW = vf?.width ?: 0
            val vfH = vf?.height ?: 0
            val w = if (vfW > 0) vfW else videoW
            val h = if (vfH > 0) vfH else videoH
            val par = vf?.pixelWidthHeightRatio ?: 1f
            return MediaTrackInfo(
                videoCodec = videoCodecLabel(vf?.sampleMimeType, vf?.codecs, text),
                resolution = resolutionLabel(w, h, par, text),
                frameRate = frameRateLabel(vf?.frameRate ?: -1f),
                videoBitrate = bitrateLabel(vf?.bitrate ?: -1),
                dynamicRange = dynamicRangeLabel(
                    vf?.sampleMimeType,
                    vf?.colorInfo?.colorTransfer ?: C.COLOR_TRANSFER_SDR,
                    vf?.codecs,
                    text,
                ),
                audioCodec = audioCodecLabel(af?.sampleMimeType),
                channels = channelsLabel(af?.channelCount ?: -1, text),
                sampleRate = sampleRateLabel(af?.sampleRate ?: -1),
                audioBitrate = bitrateLabel(af?.bitrate ?: -1),
                audioTrackCount = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
                textTrackCount = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT },
                dynamicRangeNote = dolbyVisionNote(
                    codecString = vf?.codecs,
                    deviceSupportsDolbyVision = deviceSupportsDolbyVision(),
                    text = text,
                ),
            )
        }

        /** 视频编码：mime → 通用名（H.264/H.265/AV1/VP9/Dolby Vision…），未知 mime 原样大写；DV 展示名经 [text] 注入 */
        internal fun videoCodecLabel(mime: String?, codecs: String? = null, text: (Int) -> String): String {
            mime ?: return "--"
            return when {
                mime == "video/dolby-vision" -> {
                    val p = dolbyVisionProfile(codecs)
                    if (p != null) text(R.string.player_dv_profile_fmt).format(p)
                    else text(R.string.player_dv_label)
                }
                mime.endsWith("/hevc") || mime.endsWith("/h265") -> "H.265 (HEVC)"
                mime.endsWith("/avc") || mime.endsWith("/h264") -> "H.264 (AVC)"
                mime.endsWith("/av01") || mime.endsWith("/av1") -> "AV1"
                mime.endsWith("/vp9") -> "VP9"
                mime.endsWith("/vp8") -> "VP8"
                mime.endsWith("/mpeg2video") -> "MPEG-2"
                mime.endsWith("/mpeg4") -> "MPEG-4"
                mime.endsWith("/h263") -> "H.263"
                else -> mime.substringAfter('/').uppercase()
            }
        }

        /** 音频编码：mime → 通用名 */
        internal fun audioCodecLabel(mime: String?): String {
            mime ?: return "--"
            return when {
                mime.endsWith("/aac") || mime.endsWith("/mp4a.latm") || mime.endsWith("/mp4a") -> "AAC"
                mime.endsWith("/ac3") -> "AC-3 (Dolby Digital)"
                mime.endsWith("/eac3") -> "E-AC-3 (Dolby Digital Plus)"
                mime.endsWith("/ac4") -> "AC-4"
                mime.endsWith("/truehd") -> "Dolby TrueHD"
                mime.endsWith("/opus") -> "Opus"
                mime.endsWith("/flac") -> "FLAC"
                mime.endsWith("/vorbis") -> "Vorbis"
                mime.endsWith("/dts") -> "DTS"
                mime.endsWith("/dtshd") -> "DTS-HD"
                mime.endsWith("/vnd.dts.hd") -> "DTS-HD"
                mime.endsWith("/alac") -> "ALAC"
                mime.endsWith("/mp3") || mime.endsWith("/mpeg") || mime.endsWith("/mpeg1audio") ||
                    mime.endsWith("/mpeg-L2") -> "MP3"
                else -> mime.substringAfter('/').uppercase()
            }
        }

        /**
         * 动态范围：杜比视界 mime → 带 Profile 号；PQ(ST2084) → HDR10；HLG → HLG；其余 SDR。
         * [codecs] 形如 "dvhe.05.06" / "dvh1.08.07" / "hev1.2.4.L120.90"，DV 时取 profile 号显示。
         */
        internal fun dynamicRangeLabel(mime: String?, colorTransfer: Int, codecs: String? = null, text: (Int) -> String): String {
            val isDv = mime == "video/dolby-vision" || dolbyVisionProfile(codecs) != null
            if (isDv) {
                val p = dolbyVisionProfile(codecs)
                return if (p != null) text(R.string.player_dv_profile_fmt).format(p)
                else text(R.string.player_dv_label)
            }
            return when (colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
        }

        /**
         * 从 codec string 解析杜比视界 Profile 号：
         * - "dvhe"/"dvh1"（含 dovi/dav1 变体）→ 第二段的两位数字，如 dvhe.05.06 → 5、dvh1.08.07 → 8；
         * - 形如 "dvh1.05" / "dolby-vision.05" 的短写法 → 同样取到 profile；
         * - 非 DV（hev1/hvc1/avc1…）→ null。
         */
        internal fun dolbyVisionProfile(codecString: String?): Int? {
            val c = codecString?.trim()?.lowercase() ?: return null
            if (c.isEmpty()) return null
            if (!(c.startsWith("dvhe") || c.startsWith("dvh1") ||
                    c.startsWith("dovi") || c.startsWith("dav1") || c.startsWith("dolby"))
            ) {
                return null
            }
            // 第一段（编码名）之后的第一个纯数字段即 profile（05 → 5，08 → 8）
            return c.split('.', '-', '_')
                .drop(1)
                .firstOrNull { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
                ?.toIntOrNull()
        }

        /**
         * 本机是否存在杜比视界视频解码器（MediaCodecList 查 video/dolby-vision）。
         * 用于诚实地告诉用户「这片子这台机器放不出画面」到底是不是能力问题，
         * 而不是让用户对着黑屏猜（用户实测：DV 测试片有声音无画面）。
         */
        internal fun deviceSupportsDolbyVision(): Boolean = runCatching {
            val mimes = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { it.isEncoder.not() && it.supportedTypes.isNotEmpty() }
                .flatMap { it.supportedTypes.asList() }
            mimes.any { it.equals("video/dolby-vision", ignoreCase = true) }
        }.getOrDefault(false)

        /**
         * 杜比视界的可播性归因（纯函数，JVM 可测）：
         * - Profile 5 使用 IPTPQc2 + 无 HDR10 回退层，设备没有 DV 解码器时必然黑屏（业界公论，
         *   厂商播放器同样如此）→ 如实给出原因；
         * - Profile 8（含 8.1）带 HDR10 兼容基底，无 DV 解码器的机器应回退 HDR10 正常出画；
         *   若这种片源也黑屏，属播放器缺陷 → 提示可用「视频信息」取证；
         * - Profile 4/7（蓝光双轨）同样依赖 DV 解码器；
         * - 其余/未知 profile：无把握时不下结论（不给用户错误归因）。
         */
        internal fun dolbyVisionNote(
            codecString: String?,
            deviceSupportsDolbyVision: Boolean,
            text: (Int) -> String,
        ): String? {
            val profile = dolbyVisionProfile(codecString) ?: return null
            if (deviceSupportsDolbyVision) return null
            return when (profile) {
                5 -> text(R.string.player_dv_note_p5)
                4, 7 -> text(R.string.player_dv_note_p47_fmt).format(profile)
                8 -> text(R.string.player_dv_note_p8)
                else -> null
            }
        }

        /** 帧率：>0 取整到 0.1 显示，末尾 .0 去掉；未提供 → 「--」 */
        internal fun frameRateLabel(fps: Float): String {
            if (fps <= 0f) return "--"
            val rounded = (fps * 10).roundToInt() / 10.0
            return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()} fps" else "$rounded fps"
        }

        /** 码率：bit/s → 自适应 Mbps/Kbps；未提供（≤0）→ 「--」 */
        internal fun bitrateLabel(bps: Int): String = when {
            bps <= 0 -> "--"
            bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
            else -> "${(bps / 1000.0).roundToInt()} Kbps"
        }

        /** 分辨率：宽高 + 非方像素比修正（如 DVD anamorphic，像素比标签经 [text] 注入）；未知 → 「--」 */
        internal fun resolutionLabel(w: Int, h: Int, pixelAspect: Float, text: (Int) -> String): String {
            if (w <= 0 || h <= 0) return "--"
            return if (abs(pixelAspect - 1f) > 0.01f) {
                text(R.string.player_info_pixel_aspect_fmt).format(w, h, "%.2f".format(pixelAspect))
            } else {
                "$w×$h"
            }
        }

        /** 声道：1.0/2.0/5.1/7.1 常见制式，其余 nch；未提供 → 「--」；标签经 [text] 注入 */
        internal fun channelsLabel(count: Int, text: (Int) -> String): String = when (count) {
            -1 -> "--"
            1 -> text(R.string.player_ch_mono)
            2 -> text(R.string.player_ch_stereo)
            6 -> text(R.string.player_ch_surround51)
            8 -> text(R.string.player_ch_surround71)
            else -> text(R.string.player_ch_count_fmt).format(count)
        }

        /** 采样率：Hz → kHz；未提供 → 「--」 */
        internal fun sampleRateLabel(hz: Int): String =
            if (hz <= 0) "--" else "%.1f kHz".format(hz / 1000.0)
    }
}
