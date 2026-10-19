package com.novax.app.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * seek 目标夹取单测（2026-09-15 用户实测回归：拖动到片尾导致永久黑屏死锁）。
 *
 * 真机现场：seek 目标 == duration → DataSource 以「文件长 - 10KB」打开 →
 * demuxer 取不到样本 → 永久 BUFFERING / 黑屏 / 零读盘 / 无错误无 EOS（复现 2/2）。
 * 本测试锁定「任何 seek 目标都不得进入片尾 [SEEK_END_SAFETY_MS] 窗口」这一不变量。
 */
class SeekClampTest {

    private val DURATION = 263_083L   // 真机取证的片长（4:23.083）

    @Test
    fun `片尾目标被夹到安全窗口内（死锁现场回归）`() {
        // 真机拖动产生的目标：263083（= duration）与 263090（略超）
        assertEquals(DURATION - SEEK_END_SAFETY_MS, clampSeekTarget(DURATION, DURATION))
        assertEquals(DURATION - SEEK_END_SAFETY_MS, clampSeekTarget(DURATION + 7, DURATION))
        assertEquals(DURATION - SEEK_END_SAFETY_MS, clampSeekTarget(Long.MAX_VALUE, DURATION))
    }

    @Test
    fun `安全窗口边界：严格小于上界才通过`() {
        val upper = DURATION - SEEK_END_SAFETY_MS
        assertEquals(upper - 1, clampSeekTarget(upper - 1, DURATION))
        assertEquals(upper, clampSeekTarget(upper, DURATION))
        // 再往后一点就被压回上界
        assertEquals(upper, clampSeekTarget(upper + 1, DURATION))
    }

    @Test
    fun `片中 seek 不受影响`() {
        assertEquals(108_837L, clampSeekTarget(108_837L, DURATION))
        assertEquals(0L, clampSeekTarget(0L, DURATION))
    }

    @Test
    fun `负目标夹到 0`() {
        assertEquals(0L, clampSeekTarget(-1L, DURATION))
        assertEquals(0L, clampSeekTarget(-999_999L, DURATION))
    }

    @Test
    fun `时长未知（直播）不做上界，仅保证非负`() {
        assertEquals(123_456L, clampSeekTarget(123_456L, C.TIME_UNSET))
        assertEquals(0L, clampSeekTarget(-5L, C.TIME_UNSET))
        assertEquals(9_999_999L, clampSeekTarget(9_999_999L, 0L))
    }

    @Test
    fun `超短片不产生负数上界`() {
        // duration < 安全窗口：上界与下界冲突 → 兜底 0，绝不能给负数
        assertEquals(0L, clampSeekTarget(500L, 500L))
        assertEquals(0L, clampSeekTarget(0L, 200L))
        assertEquals(0L, clampSeekTarget(100L, 1L))
    }
}
