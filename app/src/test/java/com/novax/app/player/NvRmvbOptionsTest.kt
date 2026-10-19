package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RMVB 兜底播放页 libVLC 参数与缩放口径映射单测（2026-09-20 流畅度专项）。
 *
 * 判据参照官方 VLC App VLCOptions.getDeblocking（vlc-android VLCOptions.kt:267-291）：
 * armv6 无 v7 / MIPS → 4；freq≥1200 且 cores>2 → 1（bogoMIPS 兜底同判）；否则 → 3。
 * skip-frame/-idct 仅性能模式开 = 2（官方 KEY_ENABLE_FRAME_SKIP 同值），默认不带。
 */
class NvRmvbOptionsTest {

    // ---------- buildLibVlcOptions：参数集与官方自适应判据 ----------

    private fun build(
        perfFrameSkip: Boolean = false,
        mipsLike: Boolean = false,
        freqMhz: Int = 0,
        bogoMips: Int = 0,
        processors: Int = 1,
    ): List<String> = buildLibVlcOptions(
        networkCachingMs = 3_000,
        perfFrameSkip = perfFrameSkip,
        mipsLike = mipsLike,
        freqMhz = freqMhz,
        bogoMips = bogoMips,
        processors = processors,
    )

    private fun skipLoopFilter(opts: List<String>): String =
        opts[opts.indexOf("--avcodec-skiploopfilter") + 1]

    @Test
    fun `基线参数恒含时间拉伸 soxr audiotrack 与网络缓存`() {
        val opts = build()
        assertTrue(opts.contains("--audio-time-stretch"))
        assertTrue(opts.contains("--audio-resampler=soxr"))
        // 强制 audiotrack：VLC4 默认 aaudio 无缓冲上限，变速旧速率残留 600ms+（1~3s 延迟
        // 主体）；audiotrack 残留 ≈ min*2 几十 ms，接近 VLC3 即时体感
        assertTrue(opts.contains("--aout=audiotrack"))
        assertTrue(opts.contains("--network-caching=3000"))
        // 官方同款结构：skiploopfilter 以「选项名 + 值」两个条目存在
        assertTrue(opts.contains("--avcodec-skiploopfilter"))
    }

    @Test
    fun `好设备走非参考帧环路滤波档 1`() {
        assertEquals("1", skipLoopFilter(build(freqMhz = 2_000, processors = 8)))
    }

    @Test
    fun `frequency 缺失时 bogoMIPS 兜底分支生效`() {
        assertEquals("1", skipLoopFilter(build(freqMhz = 0, bogoMips = 1_500, processors = 4)))
    }

    @Test
    fun `弱机或单核走保守档 3`() {
        assertEquals("3", skipLoopFilter(build(freqMhz = 800, bogoMips = 0, processors = 4)))
        // 单核即便高频也不满足 cores>2：绝不误跳
        assertEquals("3", skipLoopFilter(build(freqMhz = 2_600, bogoMips = 2_600, processors = 1)))
    }

    @Test
    fun `armv6无v7或mips走跳全部档 4`() {
        assertEquals("4", skipLoopFilter(build(mipsLike = true, freqMhz = 2_000, processors = 8)))
    }

    @Test
    fun `性能模式默认不带跳帧 开启时为非参考帧档 2`() {
        val off = build(freqMhz = 2_000, processors = 8)
        assertFalse(off.contains("--avcodec-skip-frame"))
        assertFalse(off.contains("--avcodec-skip-idct"))
        val on = build(perfFrameSkip = true, freqMhz = 2_000, processors = 8)
        assertEquals("2", on[on.indexOf("--avcodec-skip-frame") + 1])
        assertEquals("2", on[on.indexOf("--avcodec-skip-idct") + 1])
    }

    // ---------- aspectIndexToZoomMode：缩放夹紧口径映射 ----------

    @Test
    fun `比例档映射到正确的夹紧口径`() {
        assertEquals(AspectMode.FIT, aspectIndexToZoomMode(0))   // SURFACE_BEST_FIT：保比例 letterbox
        assertEquals(AspectMode.FILL, aspectIndexToZoomMode(1))  // SURFACE_FIT_SCREEN：铺满语义近似
        assertEquals(AspectMode.FILL, aspectIndexToZoomMode(2))  // SURFACE_FILL
        assertEquals(AspectMode.R16_9, aspectIndexToZoomMode(3))
        assertEquals(AspectMode.R4_3, aspectIndexToZoomMode(4))
    }

    // ---------------- 2026-10-12 片尾假终治理（spec §4.1/§6） ----------------

    @Test
    fun `静态裁决_len未知不裁决`() =
        org.junit.Assert.assertFalse(isRmvbTailZoneResume(startPositionMs = 100_000, reportedLenMs = 0L))

    @Test
    fun `静态裁决_无续播位不裁决`() =
        org.junit.Assert.assertFalse(isRmvbTailZoneResume(startPositionMs = 0L, reportedLenMs = 600_000))

    @Test
    fun `静态裁决_负位置不裁决`() =
        org.junit.Assert.assertFalse(isRmvbTailZoneResume(startPositionMs = -1L, reportedLenMs = 600_000))

    @Test
    fun `静态裁决_距尾9_999秒命中`() =
        org.junit.Assert.assertTrue(isRmvbTailZoneResume(startPositionMs = 590_001, reportedLenMs = 600_000))

    @Test
    fun `静态裁决_距尾恰10_000命中_小于等于边界`() =
        org.junit.Assert.assertTrue(isRmvbTailZoneResume(startPositionMs = 590_000, reportedLenMs = 600_000))

    @Test
    fun `静态裁决_距尾10_001不命中`() =
        org.junit.Assert.assertFalse(isRmvbTailZoneResume(startPositionMs = 589_999, reportedLenMs = 600_000))

    @Test
    fun `假终_静态复核距真尾9_999放行为真终`() = org.junit.Assert.assertFalse(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = false, wallSinceSeekMs = 1_000, maxTimeSeenMs = 100_000,
            seekTargetMs = 100_000, lenAtEndMs = 109_999,
        )
    )

    @Test
    fun `假终_静态复核距真尾恰10_000放行_小于等于边界`() = org.junit.Assert.assertFalse(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = false, wallSinceSeekMs = 1_000, maxTimeSeenMs = 100_000,
            seekTargetMs = 100_000, lenAtEndMs = 110_000,
        )
    )

    @Test
    fun `假终_距尾10_001且无推进判假终`() = org.junit.Assert.assertTrue(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = false, wallSinceSeekMs = 1_000, maxTimeSeenMs = 100_000,
            seekTargetMs = 100_000, lenAtEndMs = 110_001,
        )
    )

    @Test
    fun `假终_len未知跳过复核强形态仍命中`() = org.junit.Assert.assertTrue(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = false, wallSinceSeekMs = 1_000, maxTimeSeenMs = 100_000,
            seekTargetMs = 100_000, lenAtEndMs = 0L,
        )
    )

    @Test
    fun `假终_复核放行优先于强形态`() = org.junit.Assert.assertFalse(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = false, wallSinceSeekMs = 0L, maxTimeSeenMs = 100_000,
            seekTargetMs = 100_000, lenAtEndMs = 103_000,
        )
    )

    @Test
    fun `假终_强形态_无推进无论墙钟多久都命中`() {
        org.junit.Assert.assertTrue(
            isRmvbFakeTailEnd(
                advancedBeyondTarget = false, wallSinceSeekMs = 60_000, maxTimeSeenMs = 100_000,
                seekTargetMs = 100_000, lenAtEndMs = 130_000,
            )
        )
    }

    @Test
    fun `假终_强形态_墙钟0无推进且复核不放行命中`() = org.junit.Assert.assertTrue(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = false, wallSinceSeekMs = 0L, maxTimeSeenMs = 100_000,
            seekTargetMs = 100_000, lenAtEndMs = 130_000,
        )
    )

    @Test
    fun `假终_弱形态_窗内推进不足命中`() = org.junit.Assert.assertTrue(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = true, wallSinceSeekMs = 7_999, maxTimeSeenMs = 103_000,
            seekTargetMs = 100_000, lenAtEndMs = 130_000,
        )
    )

    @Test
    fun `假终_弱形态_墙钟恰8_000出窗不命中`() = org.junit.Assert.assertFalse(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = true, wallSinceSeekMs = 8_000, maxTimeSeenMs = 103_000,
            seekTargetMs = 100_000, lenAtEndMs = 130_000,
        )
    )

    @Test
    fun `假终_弱形态_推进恰5_000推够不命中`() = org.junit.Assert.assertFalse(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = true, wallSinceSeekMs = 1_000, maxTimeSeenMs = 105_000,
            seekTargetMs = 100_000, lenAtEndMs = 130_000,
        )
    )

    @Test
    fun `假终_弱形态_窗外不命中`() = org.junit.Assert.assertFalse(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = true, wallSinceSeekMs = 8_001, maxTimeSeenMs = 103_000,
            seekTargetMs = 100_000, lenAtEndMs = 130_000,
        )
    )

    @Test
    fun `假终_缺省参锁定_弱形态边界内命中`() = org.junit.Assert.assertTrue(
        isRmvbFakeTailEnd(
            advancedBeyondTarget = true, wallSinceSeekMs = 4_000, maxTimeSeenMs = 102_000,
            seekTargetMs = 100_000, lenAtEndMs = 130_000,
        )
    )
}
