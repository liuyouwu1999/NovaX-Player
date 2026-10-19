@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.data.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException

/**
 * 坏索引 MKV 降级 seek 包装器（2026-09-30，mpv `--hr-seek=no` / VLC 无索引降级同款思路）。
 *
 * 背景（真机实证，见 2026-09-30 计划文档）：宝贝计划等转载压制 MKV 的 SeekHead 是空壳
 * （无 Cues 条目）→ MatroskaExtractor 在首个 Cluster 处发 [SeekMap.Unseekable] →
 * Unseekable.getSeekPoints 恒返回 SeekPoint(0,0) → 用户点进度条任何位置都落回开头。
 * 上游不修（信任索引是设计取舍），VLC/mpv 的做法是降级：按平均码率估算字节位置，
 * 落点误差以一个 Cluster（约 0.5~2s）为界。
 *
 * 本类即该降级的 ExoPlayer 原生实现：
 * - **拦截 seekMap**：delegate 发出 Unseekable 且输入总长已知、时长 >0 时，改发
 *   [ConstantBitrateSeekMap]（timeUs → 字节 = 全长线性估算），并置 fallbackActive；
 *   有索引 MKV（seekable）或长度未知时原样透传，零行为变化。
 * 2026-10-03 容器泛化：锚点规格参数化（[ResyncSpec]）。MP4 分片（无 sidx 索引的下载/转载
 * 片源，Mp4Extractor 发 Unseekable）用 'moof' box 魔数锚点，落点=box size 字段起点——
 * Mp4Extractor 分片解析必须从 box 头开始读。恢复精度=一个分片（一般 2~6s）；moof 间距
 * 超过 8MB 扫描窗时未命中 → 按既有 IOException 错误路径上报（可见、不静默错位）。
 * - **Cluster 重同步**：fallbackActive 后的每次 seek，输入会落在估算字节（通常在 Cluster
 *   中间，直接读是垃圾）。read 入口先向前扫描下一个 Cluster ID `1F 43 B6 75`
 *   （peekFully 滚动窗口 ≤8MB；命中后 read 侧 skip 掉 peeked 前置字节，落点恰为
 *   Cluster 起始），再让 delegate 从干净的 Cluster 边界继续解析。Cluster 内 Timecode
 *   是绝对时间戳，故实际恢复位置=该 Cluster 时刻（±1 簇精度）。
 * - **尾部余量**：估算 SeekMap 用 length-16MB 做可用长度，保证任何目标都有 ≥8MB
 *   扫描窗；8MB 未命中 Cluster 时回退 16MB 重试一次，再失败按 IOException 上抛
 *   （走既有错误路径，绝不让加载线程静默吞掉）。
 *
 * 线程模型：read/seek 都发生在 ExoPlayer 加载线程（与 delegate 相同），无额外同步。
 * 日志经注入出口（JVM 单测无 android.util.Log，同 [com.novax.app.data.smb.SmbRandomReadStream] 惯例）。
 */
internal class NvFallbackSeekExtractor(
    private val delegate: Extractor,
    /** 重同步锚点规格（容器感知，见 [ResyncSpec]）；默认 Matroska——既有调用点零变化 */
    private val spec: ResyncSpec = ResyncSpec.MATROSKA,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : Extractor {

    // ---------- 状态（仅加载线程触碰） ----------

    private var downstream: ExtractorOutput? = null

    /** 最近一次 read 收到的输入：seekMap 拦截发生在 delegate.read 的调用栈内，用它取总长 */
    private var lastInput: ExtractorInput? = null

    /** delegate 已被替换成估算 SeekMap，后续 seek 需要重同步 */
    private var fallbackActive = false

    /** seek() 已发生、等下一次 read() 执行重同步 */
    private var pendingResync = false

    private var pendingSeekTimeUs = C.TIME_UNSET

    /** 重同步回退重试次数（0/1） */
    private var resyncRetries = 0

    // ---------- Extractor ----------

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun release() = delegate.release()

    override fun init(output: ExtractorOutput) {
        // downstream 存真正的下游 sink；ForwardOutput 只包给 delegate（存反了会 seekMap 无限递归）
        downstream = output
        log("init: delegate=${delegate.javaClass.simpleName}")
        delegate.init(ForwardOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        lastInput = input
        if (pendingResync) {
            pendingResync = false
            val anchorPos = resyncToMarker(input)
            if (anchorPos < 0L) {
                // 8MB 未命中：回退 16MB 重扫一次（估算偏到尾部附件区等场景）；再失败如实报错
                if (resyncRetries < 1 && input.position > RETREAT_BYTES) {
                    resyncRetries++
                    pendingResync = true
                    seekPosition.position = input.position - RETREAT_BYTES
                    log("resync miss at pos=${input.position}, retreat to ${seekPosition.position}")
                    return Extractor.RESULT_SEEK
                }
                throw IOException("降级 seek：重同步未找到 Cluster 边界 (pos=${input.position})")
            }
            resyncRetries = 0
            log("resync: estimate=${input.position} -> anchor=$anchorPos marker=${spec.marker.size}B offset=${spec.landingOffset}")
            // 估算落点与实际 Cluster 起点对齐后重置 delegate 解析状态，从干净边界继续
            delegate.seek(anchorPos, pendingSeekTimeUs)
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingSeekTimeUs = timeUs
        if (fallbackActive) pendingResync = true
        delegate.seek(position, timeUs)
    }

    // ---------- seekMap 拦截与重同步 ----------

    private fun onDelegateSeekMap(seekMap: SeekMap) {
        val out = downstream ?: throw IllegalStateException("seekMap 在 init 前到达")
        val length = lastInput?.length ?: C.LENGTH_UNSET.toLong()
        val durationUs = seekMap.durationUs
        if (!seekMap.isSeekable && length != C.LENGTH_UNSET.toLong() && durationUs > 0) {
            // bits/s 用全长算（无偏估算：落点=目标时刻的真实平均位置；上游 ctor 收 Int，
            // 真实码率 ≤ 数百 Mbps 远在 Int 域内）。极小合成长度整除下溢钳 1。
            // 尾部越界（估算 > 最后一个簇）由 read 的 resync 回退路径兜住，无需预留余量
            val bitrate = (length * 8 * 1_000_000L / durationUs).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val firstActivation = !fallbackActive
            fallbackActive = true
            log("fallback seek ON: length=$length durationUs=$durationUs bitrate=$bitrate")
            if (firstActivation) {
                // 可见提示钩子（2026-10-03）：激活时给用户一句明确告知——既是 UX（无索引片源
                // 定位有 ±几秒物理精度上限，与 mpv 行为一致），也让「新包已生效」一眼可辨。
                // 提取器层不做 UI，宿主（PlayerActivity/PlaybackService）负责注入 Toast 出口。
                onActivated?.invoke("无跳转索引，已启用估算定位")
            }
            out.seekMap(ConstantBitrateSeekMap(length, 0L, bitrate, -1))
        } else {
            log(
                "passthrough seekMap: seekable=" + seekMap.isSeekable +
                    " durationUs=" + seekMap.durationUs + " length=" + length +
                    " type=" + seekMap.javaClass.simpleName,
            )
            out.seekMap(seekMap)
        }
    }

    /**
     * 从输入当前位置向前扫描下一个锚点魔数（容器规格见 [ResyncSpec]），返回其绝对字节位置；未命中返回 -1。
     * 用 peekFully 滚动 4KB 窗口；EOF 时部分窗口字节已在 peek 流（DefaultExtractorInput
     * 语义：throw 前已 append），用 peek() 重放整段 peek 流做最后一轮扫描——估算 SeekMap
     * 虽预留了 16MB 尾部余量，接近片尾的 seek 仍可能在残窗内命中最后的簇。
     * 命中后 read 侧 skipFully 消化 peeked 前置字节，落点=魔数起点 + landingOffset（钳到不小于当前位置）。
     */
    private fun resyncToMarker(input: ExtractorInput): Long {
        val start = input.position
        val window = ByteArray(RESYNC_WINDOW_BYTES)
        val carrySize = spec.marker.size - 1
        var carry = ByteArray(carrySize)
        var base = start   // carry[0] 对应的绝对位置
        while (base - start < MAX_RESYNC_BYTES) {
            try {
                input.peekFully(window, 0, window.size)
            } catch (e: EOFException) {
                // 残窗收尾：peek 流里已有 [start, peekPosition) 全部字节（本轮未消费过 read 侧）
                val partial = (input.peekPosition - start).toInt()
                if (partial <= 0) return -1L
                val all = ByteArray(partial)
                input.peek(all, 0, partial)
                return scanTail(input, all, base - start, start)
            }
            val idx = findMarker(carry, window)
            if (idx >= 0) {
                val matchAbs = base + idx - carrySize
                // MP4 落点 = 魔数前 4B 的 size 字段起点；紧邻文件头（估算落点<4）时钳到当前位置
                val anchorAbs = (matchAbs + spec.landingOffset).coerceAtLeast(start)
                input.skipFully((anchorAbs - start).toInt())
                return anchorAbs
            }
            System.arraycopy(window, window.size - carrySize, carry, 0, carrySize)
            base += window.size
        }
        return -1L
    }

    /** 残窗收尾扫描：all 为自 start 起的全部 peeked 字节；从 max(0, baseOffset-carry) 起找锚点魔数（容器规格见 [ResyncSpec]） */
    private fun scanTail(
        input: ExtractorInput,
        all: ByteArray,
        baseOffset: Long,
        start: Long,
    ): Long {
        val from = (baseOffset - (spec.marker.size - 1)).toInt().coerceAtLeast(0)
        var i = from
        while (i + spec.marker.size <= all.size) {
            var matched = true
            for (k in spec.marker.indices) {
                if (all[i + k] != spec.marker[k]) { matched = false; break }
            }
            if (matched) {
                val anchorAbs = (start + i + spec.landingOffset).coerceAtLeast(start)
                input.skipFully((anchorAbs - start).toInt())
                return anchorAbs
            }
            i++
        }
        return -1L
    }

    /** 在 carry(前3字节)+window 拼接视野内找锚点魔数（容器规格见 [ResyncSpec]）；返回以 carry[0] 为 0 的下标，未命中 -1 */
    private fun findMarker(carry: ByteArray, window: ByteArray): Int {
        val total = carry.size + window.size
        val id = spec.marker
        var i = 0
        while (i + id.size <= total) {
            var matched = true
            for (k in id.indices) {
                val b = when {
                    i + k < carry.size -> carry[i + k]
                    else -> window[i + k - carry.size]
                }
                if (b != id[k]) { matched = false; break }
            }
            if (matched) return i
            i++
        }
        return -1
    }

    /** ExtractorOutput 透传，仅拦截 seekMap（坏索引 → 估算 SeekMap） */
    private inner class ForwardOutput(private val wrapped: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = wrapped.track(id, type)
        override fun endTracks() = wrapped.endTracks()
        override fun seekMap(seekMap: SeekMap) = onDelegateSeekMap(seekMap)
    }

    /**
     * 降级 seek 的重同步锚点规格（容器感知）。
     *
     * 估算落点通常在容器单元中间，向前扫描 [marker] 魔数找下一个单元边界作为恢复点：
     * - Matroska：Cluster 元素 ID，[landingOffset]=0，落点=魔数起点；
     * - MP4 分片：'moof' box 类型字段（ASCII），[landingOffset]=-4，落点=魔数前 4 字节的
     *   box size 字段起点（ISO-BMFF box 头 = 4B size + 4B type）。
     *
     * 无 size 合法性校验（设计取舍）：'moof' ASCII 误中 mdat 垃圾的概率 ≈ 2^-32/字节位，
     * 与 MKV Cluster ID 同级（坏索引路径已接受）；误中的恢复 = 加载错误重试路径，不损坏播放状态。
     */
    internal class ResyncSpec internal constructor(
        internal val marker: ByteArray,
        /** 命中后落点相对魔数起点的偏移（MP4 为 -4：落到 box size 字段起点） */
        internal val landingOffset: Int,
    ) {
        companion object {
            internal val MATROSKA = ResyncSpec(CLUSTER_ID, 0)
            internal val MP4 = ResyncSpec(byteArrayOf(0x6D, 0x6F, 0x6F, 0x66), -4)
        }
    }

    companion object {
        private const val TAG = "NvFallbackSeek"

        /**
         * 降级 seek 激活回调（进程级，宿主注入；提取器层不做 UI）。
         * 参数为用户可读的提示文案；每个 Extractor 实例至多触发一次（激活即置位）。
         */
        @Volatile
        var onActivated: ((String) -> Unit)? = null

        /** Matroska Cluster 元素 ID */
        internal val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)

        /** 重同步滚动窗口 */
        private const val RESYNC_WINDOW_BYTES = 4 * 1024

        /** 单次重同步最大扫描量：≥ 最大簇间距（实测该批次 ~1.5MB/簇，8MB 余量充足） */
        internal const val MAX_RESYNC_BYTES = 8L * 1024 * 1024

        /** 重同步未命中的回退量 */
        private const val RETREAT_BYTES = 16L * 1024 * 1024
    }
}
