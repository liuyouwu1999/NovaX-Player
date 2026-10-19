package com.novax.app.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存吃紧设备的缓冲策略单测（2026-09-15 用户实测「播放中闪退」修复的回归）。
 *
 * 真机取证（Redmi 10X / M2004J7AC / LineageOS-21，系统报物理内存实测仅 5.4GB）：
 * 播放时整机被压到 swap 83~85%，`lowmemorykiller` 以 oom_score_adj 0（前台）杀掉
 * com.novax.app（RSS 394MB；同批还杀了桌面、输入法、settings）→ 用户看到的「闪退」
 * （无 FATAL、无弹窗，App 直接消失）。
 * 根因之一：内存守卫判据 `memoryClass <= 128` 在这台机（memoryClass=256）恒为 false，
 * 守卫从未生效；且前向缓冲上限未随内存缩放（可涨到 120s ≈ 上百 MB 数据）。
 *
 * 本测试锁定三个不变量：
 * 1. 吃紧设备：targetBufferBytes 减半（24MB→12MB）、前向缓冲压到 60s 以内；
 * 2. 吃紧设备：起播门槛不被牺牲（仍为 profile.playbackMs）；
 * 3. 非吃紧设备：所有数值与修复前完全一致（红线：不许改坏大内存设备体验）。
 */
class LoadControlMemoryTest {

    private val MB = 1024 * 1024

    // ---------------- 非吃紧设备：行为必须与修复前一致 ----------------

    @Test
    fun `非吃紧设备 SMB 档保持 24MB 目标缓冲与 120s 上限`() {
        val p = computeLoadControlParams(BufferProfile.SMB, lowMem = false)
        assertEquals(24 * MB, p.targetBufferBytes)
        assertEquals(50_000, p.minBufferMs)      // profile.minBufferMs
        assertEquals(120_000, p.maxBufferMs)     // profile.maxBufferMs
        assertEquals(800, p.playbackBufferMs)    // profile.playbackMs
        assertEquals(600, p.rebufferBufferMs)
        assertEquals(45_000, p.backBufferMs)
    }

    @Test
    fun `非吃紧设备本地档不设字节预算`() {
        val p = computeLoadControlParams(BufferProfile.LOCAL, lowMem = false)
        assertEquals(C.LENGTH_UNSET, p.targetBufferBytes)
        assertEquals(0, p.backBufferMs)
    }

    // ---------------- 吃紧设备：各项必须被压缩 ----------------

    @Test
    fun `吃紧设备目标缓冲减半为 12MB`() {
        val p = computeLoadControlParams(BufferProfile.SMB, lowMem = true)
        assertEquals(12 * MB, p.targetBufferBytes)
    }

    @Test
    fun `吃紧设备前向缓冲压到 60s 以内`() {
        val p = computeLoadControlParams(BufferProfile.SMB, lowMem = true)
        assertTrue("前向缓冲应 ≤ 30s（60s×0.5），实际 ${p.minBufferMs}", p.minBufferMs <= 30_000)
        assertTrue("上限应 ≤ 60s，实际 ${p.maxBufferMs}", p.maxBufferMs <= 60_000)
        assertTrue("回看缓冲也要减半", p.backBufferMs <= 22_500)
    }

    @Test
    fun `吃紧设备起播门槛不被牺牲（压内存不该牺牲起播速度）`() {
        val p = computeLoadControlParams(BufferProfile.SMB, lowMem = true)
        assertEquals(BufferProfile.SMB.playbackMs, p.playbackBufferMs)
        assertEquals(800, p.playbackBufferMs)
        // 前向缓冲即便缩放后也绝不能低于起播门槛
        assertTrue(p.minBufferMs >= p.playbackBufferMs)
    }

    @Test
    fun `吃紧设备下三档缓冲级别都被压住`() {
        BufferLevel.entries.forEach { level ->
            val p = computeLoadControlParams(BufferProfile.SMB, lowMem = true, level = level)
            assertTrue("level=$level 上限应 ≤ 60s，实际 ${p.maxBufferMs}", p.maxBufferMs <= 60_000)
            assertEquals("level=$level 字节预算仍应减半", 12 * MB, p.targetBufferBytes)
        }
    }

    @Test
    fun `吃紧设备远程档同样被压缩`() {
        val p = computeLoadControlParams(BufferProfile.SMB_REMOTE, lowMem = true)
        assertEquals(12 * MB, p.targetBufferBytes)
        assertTrue(p.maxBufferMs <= 60_000)
        // 远程档 rebuffer 门槛（2s）不动：那是网络策略，与内存无关
        assertEquals(BufferProfile.SMB_REMOTE.rebufferMs, p.rebufferBufferMs)
    }

    @Test
    fun `吃紧设备高缓冲档也不会突破 60s 硬上限`() {
        // HIGH 档原本 forwardMs/maxMs 更大，必须被 MEMORY_TIGHT_MAX_FORWARD_MS 压住
        val p = computeLoadControlParams(BufferProfile.WEBDAV_HTTP, lowMem = true, level = BufferLevel.HIGH)
        assertTrue(p.maxBufferMs <= 60_000)
    }
}
