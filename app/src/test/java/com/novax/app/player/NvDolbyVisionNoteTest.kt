package com.novax.app.player

import com.novax.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 杜比视界归因 + 编码标签单测（2026-09-15 用户实测反馈回归）。
 *
 * 背景：用户用两个杜比视界测试片实测「有声音无画面」（Profile5_SolLevante / Profile81_SolLevante）。
 * 本测试锁定：
 * - codec string → profile 号解析（dvhe.05.06 = 5、dvh1.08.07 = 8，非 DV 一律 null）；
 * - 无 DV 解码器的设备上，Profile 5/4/7 必须给出「必然黑屏」归因，Profile 8 必须给出
 *   「本应 HDR10 回退」提示（不能把播放器缺陷说成设备不支持）；
 * - 有 DV 解码器的设备不打扰用户（note = null）；
 * - Profile 5 不能被误判成 HDR10/SDR（用户看到的黑屏根源就是它没有回退层）。
 */
class NvDolbyVisionNoteTest {

    /**
     * i18n Task 9：label 函数改经 text 缝注入资源（生产 = context::getString）。
     * 本测注入 zh 原句假映射，断言维持中文原句（与 values-zh-rCN 锁同文）。
     */
    private val zhText: (Int) -> String = { res ->
        when (res) {
            R.string.player_dv_label -> "Dolby Vision（杜比视界）"
            R.string.player_dv_profile_fmt -> "Dolby Vision（杜比视界 Profile %1\$d）"
            R.string.player_info_pixel_aspect_fmt -> "%1\$d×%2\$d（像素比 %3\$s）"
            R.string.player_ch_mono -> "单声道 1.0"
            R.string.player_ch_stereo -> "立体声 2.0"
            R.string.player_ch_surround51 -> "5.1 环绕声"
            R.string.player_ch_surround71 -> "7.1 环绕声"
            R.string.player_ch_count_fmt -> "%1\$d 声道"
            R.string.player_dv_note_p5 -> "本机无杜比视界解码器，Profile 5 没有 HDR10 兼容层，画面必然无法正确显示（有声音无图像属预期）——请改用 Profile 8 片源或 SDR/HDR10 片源"
            R.string.player_dv_note_p47_fmt -> "本机无杜比视界解码器，Profile %1\$d 为双层杜比视界，需要蓝光机/电视端解码——手机端播放会出现有声音无画面"
            R.string.player_dv_note_p8 -> "本机无杜比视界解码器，Profile 8 带 HDR10 兼容层，本应回退到 HDR10 正常出画；若此片仍黑屏请把本条信息反馈（属播放器需修的问题）"
            else -> res.toString()
        }
    }

    // ---------------- codec string → profile ----------------

    @Test
    fun `dvhe 05 06 解析为 Profile 5`() {
        assertEquals(5, MediaTrackInfo.dolbyVisionProfile("dvhe.05.06"))
    }

    @Test
    fun `dvh1 08 07 解析为 Profile 8`() {
        assertEquals(8, MediaTrackInfo.dolbyVisionProfile("dvh1.08.07"))
    }

    @Test
    fun `大小写与单段短写法均可解析`() {
        assertEquals(5, MediaTrackInfo.dolbyVisionProfile("DVHE.05"))
        assertEquals(8, MediaTrackInfo.dolbyVisionProfile("dvh1.08"))
    }

    @Test
    fun `非杜比视界编码一律返回 null`() {
        assertNull(MediaTrackInfo.dolbyVisionProfile("hev1.2.4.L120.90"))
        assertNull(MediaTrackInfo.dolbyVisionProfile("hvc1.2.4.L153.B0"))
        assertNull(MediaTrackInfo.dolbyVisionProfile("avc1.640028"))
        assertNull(MediaTrackInfo.dolbyVisionProfile("av01.0.08M.08"))
        assertNull(MediaTrackInfo.dolbyVisionProfile(null))
        assertNull(MediaTrackInfo.dolbyVisionProfile(""))
    }

    @Test
    fun `无数字段的杜比视界编码不抛异常且返回 null`() {
        assertNull(MediaTrackInfo.dolbyVisionProfile("dvhe"))
        assertNull(MediaTrackInfo.dolbyVisionProfile("dvh1."))
    }

    // ---------------- 可播性归因 ----------------

    @Test
    fun `无DV解码器时 Profile 5 给出必然黑屏归因`() {
        val note = MediaTrackInfo.dolbyVisionNote("dvhe.05.06", deviceSupportsDolbyVision = false, text = zhText)
        assertTrue("Profile 5 必须归因", note != null)
        assertTrue("应点明没有 HDR10 兼容层", note!!.contains("HDR10"))
    }

    @Test
    fun `无DV解码器时 Profile 8 提示本应回退而非设备不支持`() {
        val note = MediaTrackInfo.dolbyVisionNote("dvh1.08.07", deviceSupportsDolbyVision = false, text = zhText)
        assertTrue("Profile 8 需提示取证", note != null)
        assertTrue("必须说明本应回退 HDR10", note!!.contains("回退"))
        assertFalse("不得把播放器问题说成设备不支持", note.contains("必然无法正确显示"))
    }

    @Test
    fun `无DV解码器时 Profile 7 归因双层杜比`() {
        val note = MediaTrackInfo.dolbyVisionNote("dvhe.07.06", deviceSupportsDolbyVision = false, text = zhText)
        assertTrue(note != null)
        assertTrue(note!!.contains("双层"))
    }

    @Test
    fun `有DV解码器时不打扰用户`() {
        assertNull(MediaTrackInfo.dolbyVisionNote("dvhe.05.06", deviceSupportsDolbyVision = true, text = zhText))
        assertNull(MediaTrackInfo.dolbyVisionNote("dvh1.08.07", deviceSupportsDolbyVision = true, text = zhText))
    }

    @Test
    fun `非DV片源不产生任何归因`() {
        assertNull(MediaTrackInfo.dolbyVisionNote("hev1.2.4.L120.90", deviceSupportsDolbyVision = false, text = zhText))
        assertNull(MediaTrackInfo.dolbyVisionNote(null, deviceSupportsDolbyVision = false, text = zhText))
    }

    // ---------------- 动态范围 / 编码标签 ----------------

    @Test
    fun `DV mime 的动态范围标签带 Profile 号`() {
        val label = MediaTrackInfo.dynamicRangeLabel(
            mime = "video/dolby-vision",
            colorTransfer = androidx.media3.common.C.COLOR_TRANSFER_ST2084,
            codecs = "dvhe.05.06",
            text = zhText,
        )
        assertEquals("Dolby Vision（杜比视界 Profile 5）", label)
    }

    @Test
    fun `HDR10 与 HLG 标签不被 DV 改动影响`() {
        assertEquals(
            "HDR10 (PQ)",
            MediaTrackInfo.dynamicRangeLabel(
                mime = "video/hevc",
                colorTransfer = androidx.media3.common.C.COLOR_TRANSFER_ST2084,
                codecs = "hev1.2.4.L120.90",
                text = zhText,
            ),
        )
        assertEquals(
            "HLG",
            MediaTrackInfo.dynamicRangeLabel(
                mime = "video/hevc",
                colorTransfer = androidx.media3.common.C.COLOR_TRANSFER_HLG,
                codecs = null,
                text = zhText,
            ),
        )
        assertEquals(
            "SDR",
            MediaTrackInfo.dynamicRangeLabel(
                mime = "video/avc",
                colorTransfer = androidx.media3.common.C.COLOR_TRANSFER_SDR,
                codecs = "avc1.640028",
                text = zhText,
            ),
        )
    }

    @Test
    fun `编码标签对 DV 与 HEVC 分别正确`() {
        assertEquals(
            "Dolby Vision（杜比视界 Profile 8）",
            MediaTrackInfo.videoCodecLabel("video/dolby-vision", "dvhe.08.06", zhText),
        )
        assertEquals("H.265 (HEVC)", MediaTrackInfo.videoCodecLabel("video/hevc", "hev1.2.4.L120.90", zhText))
        assertEquals("H.264 (AVC)", MediaTrackInfo.videoCodecLabel("video/avc", null, zhText))
    }
}
