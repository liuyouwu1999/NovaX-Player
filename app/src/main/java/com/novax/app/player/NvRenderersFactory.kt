@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.novax.media3.ffmpeg.FfmpegAudioRenderer
import com.novax.media3.ffmpeg.FfmpegLibrary

/**
 * 音频输出策略固定的 RenderersFactory（2026-09-22 长按倍速音画不同步根治）。
 *
 * 用户实测（2026-09-22）：长按倍速（2x/3x）播放一会音画渐进不同步。根因在 Media3 1.8.0
 * 的两条变速通路都不适合我们（字节码 + 上游 issue 双重核证）：
 *
 * 1. **平台变速（AudioTrack.setPlaybackParams）**：DefaultRenderersFactory 在 API 23+ 默认
 *    把 `enableAudioTrackPlaybackParams=true` 传进来，速度交给 AudioFlinger，媒体时钟从
 *    `AudioTrack.getTimestamp()` 外推——OEM HAL 在 speed!=1 时时间戳漂移（渐进 desync 的
 *    直接机制，ExoPlayer #1203 原话「device is reporting an audio track position that's
 *    slowly drifting」；Media3 1.8.0 release note 也在给「position drift from the audio
 *    output device」打补丁）。官方文档自述：平台变速「lower latency, but **less reliable**」。
 *    → 关掉：`setEnableAudioTrackPlaybackParams(false)`，速度改由应用内 SonicAudioProcessor
 *    重采样，时钟 = 已写入帧数 + 系统时钟，**结构上不可能漂移**。
 *
 * 2. **位流直通（AC3/E-AC3 passthrough）**：Sonic 不处理编码位流（1.8.0
 *    `shouldApplyAudioProcessorPlaybackParameters` 要求 OUTPUT_MODE_PCM），直通 + 变速
 *    = 音频 1x、媒体时钟 2x，音频越落越远（androidx/media #2283，Open 未修）。
 *    → 停用：强制 [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES]（PCM-only），
 *    AC3/E-AC3 一律解码为 PCM 再进 Sonic。设备缺 AC3 解码器的场景由既有的
 *    「音轨缺失自动转 libVLC 软解」链路兜底，音频不会消失；副作用是音量增益
 *    （KEY_VOLUME_BOOST）从此全格式有效（直通位流绕过 PCM 混音链的旧限制消失）。
 *
 * 3. **float 输出**：Sonic 1.9.0 才支持 float PCM 输入（1.8.0 release note）；1.8.0 里
 *    float 输入会让 `shouldApplyAudioProcessorPlaybackParameters` 返回 false → 变速静默
 *    失效。→ `setEnableFloatOutput(false)` 钉死 16-bit PCM，保证 Sonic 全场景可用。
 *
 * 代价（已知且接受）：Sonic 变速生效有 ~100-300ms 排空延迟（#7982「apply late but stay
 * consistent」）、变速瞬间一次轻微音损（#602）；对长按倍速交互无感。
 * 2026-10-25 外部审查整改（spec §3）：直通已停用（2026-09-22），音频路径固定 PCM-only，
 * 旧 audioPassthrough 构造参数随死管道清理移除——偏好键 novax_audio_passthrough 仅作为
 * 设置页置灰开关的展示存储保留，不再进入本类与引擎。
 *
 * 诊断：PlaybackEngine 里的 NvAvSync 日志会打印实际 AudioTrack 编码/offload 与
 * playbackParameters 接受情况，真机复核以它为准。
 */
class NvRenderersFactory(
    context: Context,
    private val avOffsetMs: Int = 0,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        android.util.Log.w(
            "NvAvSync",
            "audioSink build: sonic-speed (platform params off) pcm-only " +
                "avOffsetMs=$avOffsetMs " +
                "(factory float=$enableFloatOutput trackParams=$enableAudioTrackPlaybackParams ignored)",
        )
        val builder = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
        // 音画偏移补偿（用户可调）：放进默认处理器链首位（SilenceSkipping/Sonic 在后，
        // 变速不变调不受影响）；0 = 不加处理器，行为与无补偿完全一致
        if (avOffsetMs != 0) {
            builder.setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(NvAvOffsetAudioProcessor(avOffsetMs)),
            )
        }
        return builder.build()
    }

    /**
     * FFmpeg 音频软解 renderer（2026-09-29，spec §5.3）：追加在 MediaCodec 之后，选择完全靠
     * ExoPlayer 能力评级——MediaCodec 有解码器（AAC/MP3…）时 FORMAT_HANDLED 且列前获胜；
     * 无解码器（AC3/EAC3/DTS/TrueHD）时 EXCEEDS_CAPABILITIES，FFmpeg 的 FORMAT_HANDLED 胜出。
     * 不走 DefaultRenderersFactory 的 EXTENSION_RENDERER_MODE 反射路径（R8 需 keep 规则，
     * 反射名脆弱），显式注册。AudioSink 用独立实例（AudioSink 有状态不可两 renderer 共享；
     * 每实例都带本类的 Sonic/AvOffset 配置）。
     */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
        if (!FfmpegLibrary.isAvailable()) {
            // libffmpegext.so 加载失败（老 ROM/怪 ABI）：不注册，退回无声横幅兜底链（Review Focus #5）
            android.util.Log.w("NvAvSync", "libffmpegext unavailable → no soft audio renderer")
            return
        }
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, buildAudioSink(context, false, false)))
        android.util.Log.w("NvAvSync", "ffmpeg audio renderer registered: ac3/eac3/dca/truehd → 16bit PCM (sonic)")
    }
}
