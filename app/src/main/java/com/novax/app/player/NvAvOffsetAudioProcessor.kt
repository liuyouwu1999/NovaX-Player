@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 音画偏移补偿处理器（2026-09-23 长按倍速/大跳后音画不同步的兜底修法）。
 *
 * 背景（真机 Fold3 实测）：大跳 seek 后视频按关键帧追赶、追平后仍残留 ~0.3-0.5s 的
 * 「嘴巴先动、声音后到」——音频输出链路（三星杜比/SoundAlive/MySound 音效栈）的固有
 * 延迟，AudioFlinger 报 ~513ms，而 app 能拿到的 AudioTrack 时间戳不含音效级延迟，
 * 无 API 可查询 → 只能由用户按耳朵微调。
 *
 * 语义（offsetMs，单位毫秒）：
 * - offset < 0：**音频提前**。每个流起点（configure/flush，即起播与每次 seek/变速冲刷）
 *   丢弃 |offset| 毫秒的音频内容 → 可闻音频相对画面提前，修「声音后到」。
 * - offset > 0：**音频延后**。维护一个 |offset| 毫秒的静音/内容延迟队列，流内持续延后
 *   → 修「声音先响」；队列在 configure/flush 时以静音预填，输出连续不 underrun。
 *
 * 生命周期：flush()（seek/变速冲刷）清空队列并重新武装丢料/延迟；流内保持连续。
 * 注意：变速切换 media3 会整链 drain+flush → 负偏移会重新丢一次 |offset|——这是补偿
 * 的正确语义（管线排空后重建立延迟），非内容丢失（isEnded 已含输出取干条件，尾料先于
 * flush 放行）。
 * 支持且仅支持 PCM 16-bit（本 app 的 sink 已钉死 16-bit 输出，见 NvRenderersFactory）。
 * 链路位置：NvRenderersFactory 把本处理器放进 DefaultAudioProcessorChain 首位
 * （Sonic 在其后，变速重采样不受影响）。
 *
 * **变速缩放（2026-09-25 倍速音画不同步根治）**：要抵消的音效栈延迟是**墙钟**恒量 L，
 * 而本处理器丢的是**内容**毫秒——内容在 speed 倍速下以 speed×墙钟流过，丢 N 毫秒内容
 * 只等于 N/speed 毫秒墙钟。1x 时 offset=L 恰好抵消；2x 时补偿只剩一半（音频落后
 * L/2）、3x 剩三分之一——即用户报的「1x 对齐、倍速不同步」。故每次武装（configure/
 * flush）按 currentSpeed 缩放：有效偏移 = offset × speed。currentSpeed 由
 * PlaybackEngine 的 onPlaybackParametersChanged 回写（覆盖长按/菜单/通知栏全部变速路径）。
 */
class NvAvOffsetAudioProcessor(
    offsetMs: Int,
    private val speedProvider: () -> Float = { currentSpeed },
) : AudioProcessor {

    /** 补偿量（毫秒）；负 = 音频提前（丢料），正 = 音频延后（延迟队列） */
    private var offsetMs: Int = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    private var sampleRate = 0
    private var bytesPerFrame = 0
    private var configured = false
    private var ended = false

    /** 正偏移的延迟队列：数据占 [pendingHead, pendingLen) */
    private var pending = ByteArray(0)
    private var pendingHead = 0
    private var pendingLen = 0

    /** 负偏移：本次流起点还需丢弃的字节数 */
    private var dropRemaining = 0L

    private var delayBytes = 0L

    /** 已处理完、等待 sink 取走的输出：数据占 [outPos, outLen) */
    private var out = ByteArray(0)
    private var outPos = 0
    private var outLen = 0

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (offsetMs == 0) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        armForStream()
        configured = true
        ended = false
        return inputAudioFormat
    }

    override fun isActive(): Boolean = configured && offsetMs != 0

    override fun queueInput(input: ByteBuffer) {
        if (offsetMs > 0) {
            appendToPending(input)
            emitFromPending()
        } else {
            dropThenEmit(input)
        }
        input.position(input.limit())
    }

    override fun queueEndOfStream() {
        // 正偏移：流结束时把延迟队列里剩的尾巴全部放行（isEnded 以排空为准）
        if (offsetMs > 0) emitFromPending(all = true)
        ended = true
    }

    override fun getOutput(): ByteBuffer {
        if (outLen - outPos <= 0) return AudioProcessor.EMPTY_BUFFER
        val buffer = ByteBuffer.wrap(out, outPos, outLen - outPos)
        // sink 契约保证返回的缓冲会被完整取干；出空后回收数组复用（防长播无限增长）
        outPos = outLen
        return buffer
    }

    override fun isEnded(): Boolean =
        // 三条件缺一不可：ended 置位 + 延迟尾巴排空 + 输出缓冲已交付。
        // media3 1.8.0 的 AudioProcessingPipeline.isEnded() = inputEnded && 链尾 isEnded &&
        // 输出取干，而 DefaultAudioSink.processBuffers 顶部见 isEnded 即短路不再调
        // getOutput——若 out 里还压着正偏移的尾料就提前报 ended，EOS 与每次变速冲刷
        // （drainToEndOfStream → flush）都会静默丢 |offset| 毫秒音频（审核 S1）。
        ended && pendingLen - pendingHead <= 0 && outLen - outPos <= 0

    /** flush = seek/变速冲刷：清空队列、重新武装本流的偏移（保持补偿跨 seek 生效） */
    override fun flush() {
        pendingHead = 0
        pendingLen = 0
        outPos = 0
        outLen = 0
        armForStream()
    }

    override fun reset() {
        flush()
        configured = false
        sampleRate = 0
        bytesPerFrame = 0
        delayBytes = 0
    }

    /**
     * 流武装（configure 与 flush 共用）：按「offset × 当前倍速」计算有效偏移并重建
     * 丢料/延迟状态。倍速变化必经 flush（media3 变速整链冲刷），此处读到的是最新速度。
     */
    private fun armForStream() {
        val eff = effectiveOffsetMs()
        delayBytes = eff.toLong() * bytesPerFrame * sampleRate / 1_000L
        dropRemaining = if (eff < 0) -delayBytes else 0L
        pendingHead = 0
        pendingLen = 0
        outPos = 0
        outLen = 0
        ended = false
        // 负偏移（丢料）冲刷后重新武装 = 补偿随每次 seek/变速恢复；
        // 正偏移（延迟）冲刷后预填静音，输出连续不 underrun
        primeSilenceIfDelayed()
    }

    /** 有效偏移（毫秒·内容时基）= offset × 倍速：墙钟恒量 L 的内容时基换算，见类注释 */
    private fun effectiveOffsetMs(): Int =
        (offsetMs * speedProvider().coerceIn(0.25f, 4f)).roundToInt()
            .coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    // ---- 内部：pending / out 均为「数组 + 区间」的朴素字节队列，量级 ≤ 数百 KB ----

    /** 正偏移：流起点/冲刷后预填 |offset| 毫秒静音，输出保持连续（否则 AudioTrack underrun） */
    private fun primeSilenceIfDelayed() {
        if (offsetMs <= 0 || delayBytes <= 0L) return
        val n = delayBytes.toInt()
        compactAndGrowPending(n)
        java.util.Arrays.fill(pending, pendingLen, pendingLen + n, 0)
        pendingLen += n
    }

    private fun appendToPending(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        compactAndGrowPending(n)
        input.get(pending, pendingLen, n)
        pendingLen += n
    }

    /** 从 pending 头部发射；默认发射量 = 持有 [delayBytes]，[all]=true 时全部发射 */
    private fun emitFromPending(all: Boolean = false) {
        val available = pendingLen - pendingHead
        val emit = if (all) available else (available - delayBytes).coerceAtLeast(0L).toInt()
        if (emit <= 0) return
        ensureOutCapacity(emit)
        System.arraycopy(pending, pendingHead, out, outLen, emit)
        outLen += emit
        pendingHead += emit
    }

    private fun dropThenEmit(input: ByteBuffer) {
        if (dropRemaining > 0) {
            val d = minOf(dropRemaining, input.remaining().toLong()).toInt()
            input.position(input.position() + d)
            dropRemaining -= d
        }
        val n = input.remaining()
        if (n > 0) {
            ensureOutCapacity(n)
            input.get(out, outLen, n)
            outLen += n
        }
    }

    /** 把 pending 的有效区间压实到头部，并保证还能再放 [extra] 字节 */
    private fun compactAndGrowPending(extra: Int) {
        val available = pendingLen - pendingHead
        if (pendingHead > 0) {
            System.arraycopy(pending, pendingHead, pending, 0, available)
            pendingHead = 0
            pendingLen = available
        }
        if (pending.size < pendingLen + extra) {
            pending = pending.copyOf(maxOf(pendingLen + extra, pending.size * 2, 4_096))
        }
    }

    private fun ensureOutCapacity(extra: Int) {
        // sink 按契约会把上一次 getOutput 的缓冲取干；取干后回收区间复用
        if (outPos == outLen && outLen > 0) {
            outPos = 0
            outLen = 0
        }
        if (out.size < outLen + extra) {
            out = out.copyOf(maxOf(outLen + extra, out.size * 2, 4_096))
        }
    }

    companion object {
        const val MAX_OFFSET_MS = 2_000

        /**
         * 当前播放倍速（内容/墙钟比）。由 PlaybackEngine 的 onPlaybackParametersChanged
         * 回写——长按倍速、倍速菜单、通知栏、活跃引擎兜底全部路径最终都汇到
         * player.playbackParameters，此处是唯一真相源。@Volatile：处理器运行在
         * playback 线程，回写在主线程回调（ExoPlayer 应用线程）。
         */
        @Volatile
        @JvmStatic
        var currentSpeed: Float = 1f
    }
}
