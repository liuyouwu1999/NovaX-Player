package com.novax.app.data.jellyfin

import android.media.MediaCodecList

/**
 * 设备解码能力 → Jellyfin DeviceProfile 编解码声明(2026-10-10 S3,spec §3)。
 * 背景:转码档此前只声明 h264+aac 立体声——HEVC 源被转 H.264(同码率效率远低,服务器按
 * 码率阶梯压分辨率,2M 只给 720p)、任意音轨被压 AAC 立体声(声音缩水)。本对象按设备实际
 * 解码能力生成声明,沿用「拿不准的不声明」原则:
 * - 视频转码:h264 恒在(全设备兜底);仅当设备存在 HEVC 解码器(mime video/hevc)才追加 hevc;
 * - 音频直解:对齐 NvFfmpegAudio.BUILT_DECODER_NAMES 实证(ac3/eac3/dca/truehd 均可软解);
 * - 音频转码 copy:源编码在列则服务器原样复制进 ts 不再转;truehd 在 ts 中罕见不入列
 *   (TrueHD 源+转码仍降 AAC 立体声=现状,直连场景原声直解不受影响);flac 不入列——
 *   media3 TsExtractor 无 FlacReader,copy 进 ts 播放端解不出音轨(详见常量 kdoc)。
 * android.media 依赖隔离在本对象;[queryDecoderMimes] 异常回退空集(=只声明 h264 保守回退);
 * 纯逻辑([transcodeVideoCodecs]/常量)JVM 可测,MediaCodecList 本体不做 JVM 测试。
 */
object NvDeviceCodecs {

    /** 转码视频编码声明,h264 恒首位(服务器转码兜底编码次序) */
    fun transcodeVideoCodecs(decoderMimes: Collection<String> = queryDecoderMimes()): List<String> =
        listOf("h264") + if (decoderMimes.any { it.equals(HEVC_MIME, ignoreCase = true) }) {
            listOf("hevc")
        } else {
            emptyList()
        }

    /** 设备全部软硬解码器支持的 mime(REGULAR_CODECS 为 API26+ 正式 API,minSdk 26 恒可用);异常回退空集 */
    fun queryDecoderMimes(): Set<String> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMapTo(HashSet()) { it.supportedTypes.toList() }
    } catch (t: Throwable) {
        emptySet()
    }

    /**
     * 转码时允许原样复制进 ts 的音频编码(aac 恒首=真需转码时的兜底输出编码)。
     * flac 不入列(2026-10-10 F4,spec §3):已打包 media3-extractor 的 TsExtractor 无
     * FlacReader,flac 源被 copy 进 ts 后播放端解不出音轨,转码会话整片无声——flac 源
     * +转码档回落 AAC 立体声;DirectPlay 列表保留 flac(mkv 直连由 Matroska 提取器
     * 处理,原声直解不受影响)。
     */
    const val TRANSCODE_AUDIO_CODECS = "aac,ac3,eac3,dts,mp3"

    /** DirectPlay 音频声明:NvFfmpegAudio BUILT_DECODER_NAMES(ac3/eac3/dca/truehd)实证可解 */
    const val DIRECT_PLAY_AUDIO_CODECS = "aac,mp3,flac,ac3,eac3,dts,truehd"

    private const val HEVC_MIME = "video/hevc"
}
