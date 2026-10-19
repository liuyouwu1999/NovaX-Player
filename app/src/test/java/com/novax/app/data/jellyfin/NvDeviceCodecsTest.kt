package com.novax.app.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-10 S3(spec §3.4 最小矩阵):设备能力→DeviceProfile 编解码声明的纯逻辑部分 */
class NvDeviceCodecsTest {

    @Test fun transcodeVideoCodecs_withHevcDecoder_appendsHevc() {
        assertEquals(listOf("h264", "hevc"), NvDeviceCodecs.transcodeVideoCodecs(listOf("video/hevc", "video/avc")))
    }

    @Test fun transcodeVideoCodecs_withoutHevc_h264Only() {
        assertEquals(listOf("h264"), NvDeviceCodecs.transcodeVideoCodecs(listOf("video/avc", "video/x-vnd.on2.vp9")))
    }

    @Test fun transcodeVideoCodecs_emptyFallback_h264Only() {
        // queryDecoderMimes 异常回退空集 → 保守只声明 h264
        assertEquals(listOf("h264"), NvDeviceCodecs.transcodeVideoCodecs(emptySet()))
    }

    @Test fun transcodeVideoCodecs_mimeCaseInsensitive() {
        assertEquals(listOf("h264", "hevc"), NvDeviceCodecs.transcodeVideoCodecs(listOf("VIDEO/HEVC")))
    }

    @Test fun audioConstants_aacFirstAndFormatsPresent() {
        assertTrue(NvDeviceCodecs.TRANSCODE_AUDIO_CODECS.startsWith("aac,"))
        listOf("ac3", "eac3", "dts", "mp3").forEach {
            assertTrue("TRANSCODE 缺 $it", NvDeviceCodecs.TRANSCODE_AUDIO_CODECS.contains(it))
        }
        assertTrue(NvDeviceCodecs.DIRECT_PLAY_AUDIO_CODECS.startsWith("aac,"))
        listOf("ac3", "eac3", "dts", "truehd").forEach {
            assertTrue("DIRECT_PLAY 缺 $it", NvDeviceCodecs.DIRECT_PLAY_AUDIO_CODECS.contains(it))
        }
    }

    @Test fun audioConstants_flac_directPlayOnly() {
        // F4(2026-10-10,spec §3):TsExtractor 无 FlacReader,flac copy 进 ts 整片无声——
        // 转码串不含 flac,DirectPlay 串含 flac(mkv 直连走 Matroska 提取器)
        assertTrue("TRANSCODE 不应含 flac", !NvDeviceCodecs.TRANSCODE_AUDIO_CODECS.contains("flac"))
        assertTrue("DIRECT_PLAY 缺 flac", NvDeviceCodecs.DIRECT_PLAY_AUDIO_CODECS.contains("flac"))
    }
}
