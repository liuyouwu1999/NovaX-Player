package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远程链路判定与档位映射（2026-09-15 远程模式自适应） */
class NvRemoteProfileDetectorTest {

    @Test
    fun threshold_600ms_boundary() {
        assertFalse(NvRemoteProfileDetector.isRemoteLink(600))    // 恰在阈值 → LAN
        assertTrue(NvRemoteProfileDetector.isRemoteLink(601))     // 超过 → 远程
        assertFalse(NvRemoteProfileDetector.isRemoteLink(80))     // LAN 实测量级
    }

    @Test
    fun remoteProfile_onlyForNetworkProfiles() {
        assertEquals(BufferProfile.SMB_REMOTE, NvRemoteProfileDetector.remoteProfileOf(BufferProfile.SMB))
        assertEquals(
            BufferProfile.WEBDAV_HTTP_REMOTE,
            NvRemoteProfileDetector.remoteProfileOf(BufferProfile.WEBDAV_HTTP),
        )
        assertNull(NvRemoteProfileDetector.remoteProfileOf(BufferProfile.LOCAL))   // 本地无远程概念
        assertNull(NvRemoteProfileDetector.remoteProfileOf(BufferProfile.SFTP))
    }

    @Test
    fun remoteProfile_keepsFastStart_raisesRebufferGate() {
        // 起播门槛不变（外网也要快点出画面），rebuffer 恢复门槛抬到 2s（防抖动停-起-停）
        assertEquals(BufferProfile.SMB.playbackMs, BufferProfile.SMB_REMOTE.playbackMs)
        assertEquals(2_000, BufferProfile.SMB_REMOTE.rebufferMs)
        assertEquals(90_000, BufferProfile.SMB_REMOTE.minBufferMs)
    }
}
