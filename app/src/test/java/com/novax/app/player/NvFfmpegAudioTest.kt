package com.novax.app.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/** NvFfmpegAudio 契约锁：mime 集与 native 构建参数（build_ffmpeg.sh 调用参数）一一对应 */
class NvFfmpegAudioTest {
    @Test
    fun `covered mimes match native decoder set`() {
        assertEquals(
            setOf(
                "audio/ac3", "audio/eac3", "audio/eac3-joc",
                "audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd",
            ),
            NvFfmpegAudio.COVERED_MIMES,
        )
        assertEquals(listOf("ac3", "eac3", "dca", "truehd"), NvFfmpegAudio.BUILT_DECODER_NAMES)
        // mime 常量真值防手写漂移（对照 media3 MimeTypes 常量而非字面量重复两遍）
        assertEquals(
            setOf(
                MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD,
            ),
            NvFfmpegAudio.COVERED_MIMES,
        )
    }
}
