package com.novax.app.player

import com.novax.app.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 流量播放确认判定（2026-09-28 用户需求：仅 Wi-Fi 免确认播放） */
class NvMeteredGuardTest {

    @Before
    fun setUp() {
        // object 从此有状态（2026-10-11 会话标志），每例复位防串扰
        NvMeteredGuard.resetSession()
    }

    @Test
    fun `显式远程源走网络`() {
        assertTrue(NvMeteredGuard.isRemoteTarget("novax://3/x.mp4", SourceType.WEBDAV))
        assertTrue(NvMeteredGuard.isRemoteTarget("novax://3/x.rmvb", SourceType.SMB))
    }

    @Test
    fun `显式本地源与本机 URI 不走网络`() {
        assertFalse(NvMeteredGuard.isRemoteTarget("novax://1/a.mp4", SourceType.LOCAL))
        assertFalse(NvMeteredGuard.isRemoteTarget("content://media/external/video/media/42", null))
        assertFalse(NvMeteredGuard.isRemoteTarget("file:///sdcard/a.mp4", null))
        assertFalse(NvMeteredGuard.isRemoteTarget("/storage/emulated/0/a.mp4", null))
    }

    @Test
    fun `http 直链恒走网络`() {
        assertTrue(NvMeteredGuard.isRemoteTarget("http://x/a.mp4", null))
        assertTrue(NvMeteredGuard.isRemoteTarget("https://x/a.mp4", null))
    }

    @Test
    fun `计量网络且走网络才需要确认`() {
        assertTrue(NvMeteredGuard.needsDataConfirmation("novax://3/x.mp4", SourceType.WEBDAV, isMetered = true))
        assertFalse(NvMeteredGuard.needsDataConfirmation("novax://3/x.mp4", SourceType.WEBDAV, isMetered = false))
        assertFalse(NvMeteredGuard.needsDataConfirmation("content://media/x", null, isMetered = true))
        assertFalse(NvMeteredGuard.needsDataConfirmation("/sdcard/a.mp4", null, isMetered = true))
    }

    /** 2026-09-28 审计 P2-5：外部 VIEW 可注入任意大小写 scheme——HTTP:// 曾落 else 分支被
     * 当本地文件免确认起播（计量绕过）。表驱动锁全大小写组合与未知 scheme 的归类。 */
    @Test
    fun `scheme大小写不敏感_大小写直链仍判远程`() {
        val remoteCases = listOf(
            "http://x/a.mp4", "HTTP://x/a.mp4", "Http://x/a.mp4", "hTTpS://x/a.mkv",
            "HTTPS://x/a.mkv", "ftp://x/a.mp4", "FTP://x/a.mp4",
        )
        remoteCases.forEach { url ->
            assertTrue("应判远程：$url", NvMeteredGuard.isRemoteTarget(url, null))
        }
    }

    @Test
    fun `scheme大小写不敏感_本机URI与路径仍判本地`() {
        val localCases = listOf(
            "FILE:///sdcard/a.mp4", "File:///sdcard/a.mp4", "CONTENT://media/external/42",
            "Content://media/external/42", "/storage/emulated/0/a.mp4", "novax://3/x.mp4",
        )
        localCases.forEach { url ->
            assertFalse("应判本地：$url", NvMeteredGuard.isRemoteTarget(url, null))
        }
    }

    @Test
    fun `计量确认大小写绕过已封堵`() {
        // 修复前：HTTP://（sourceType=null）落 else 分支 → needsDataConfirmation=false
        assertTrue(NvMeteredGuard.needsDataConfirmation("HTTP://host/x.mkv", null, isMetered = true))
        assertTrue(NvMeteredGuard.needsDataConfirmation("FTP://host/x.mkv", null, isMetered = true))
    }

    // ---------------- 2026-10-11 三档：档位解析表 ----------------

    @Test
    fun `档位解析_新键三值各归位`() {
        assertEquals(MeteredRemindMode.EVERY, meteredRemindMode(METERED_REMIND_EVERY, null))
        assertEquals(MeteredRemindMode.SESSION_ONCE, meteredRemindMode(METERED_REMIND_SESSION_ONCE, null))
        assertEquals(MeteredRemindMode.NEVER, meteredRemindMode(METERED_REMIND_NEVER, null))
    }

    @Test
    fun `档位解析_未知值回落默认`() {
        assertEquals(MeteredRemindMode.SESSION_ONCE, meteredRemindMode("garbage", null))
        assertEquals(MeteredRemindMode.SESSION_ONCE, meteredRemindMode("", null))
    }

    @Test
    fun `档位解析_旧键迁移_false为NEVER_true与双缺为默认`() {
        assertEquals(MeteredRemindMode.NEVER, meteredRemindMode(null, false))
        assertEquals(MeteredRemindMode.SESSION_ONCE, meteredRemindMode(null, true))
        assertEquals(MeteredRemindMode.SESSION_ONCE, meteredRemindMode(null, null))
    }

    @Test
    fun `档位解析_新键永久遮蔽旧键`() {
        // 用户勾过「不再提醒」（旧键 false）之后又把设置改回「仅一次」：新键在场即生效，
        // 不得回读旧键
        assertEquals(MeteredRemindMode.SESSION_ONCE, meteredRemindMode(METERED_REMIND_SESSION_ONCE, false))
        assertEquals(MeteredRemindMode.NEVER, meteredRemindMode(METERED_REMIND_NEVER, true))
    }

    @Test
    fun `落盘值往返`() {
        MeteredRemindMode.entries.forEach { mode ->
            assertEquals(mode, meteredRemindMode(mode.toStoredValue(), null))
        }
    }

    // ---------------- 2026-10-11 三档：会话标志三态 ----------------

    @Test
    fun `会话标志_初始未确认_mark置位_reset清除`() {
        assertFalse(NvMeteredGuard.sessionConfirmed)
        NvMeteredGuard.markSessionConfirmed()
        assertTrue(NvMeteredGuard.sessionConfirmed)
        NvMeteredGuard.resetSession()
        assertFalse(NvMeteredGuard.sessionConfirmed)
    }

    // ---------------- 2026-10-11 三档：档位裁决表 ----------------

    @Test
    fun `档位裁决_EVERY恒需确认`() {
        assertTrue(NvMeteredGuard.needsConfirmation(MeteredRemindMode.EVERY, sessionConfirmed = false))
        assertTrue(NvMeteredGuard.needsConfirmation(MeteredRemindMode.EVERY, sessionConfirmed = true))
    }

    @Test
    fun `档位裁决_NEVER恒免`() {
        assertFalse(NvMeteredGuard.needsConfirmation(MeteredRemindMode.NEVER, sessionConfirmed = false))
        assertFalse(NvMeteredGuard.needsConfirmation(MeteredRemindMode.NEVER, sessionConfirmed = true))
    }

    @Test
    fun `档位裁决_SESSION_ONCE随标志`() {
        assertTrue(NvMeteredGuard.needsConfirmation(MeteredRemindMode.SESSION_ONCE, sessionConfirmed = false))
        assertFalse(NvMeteredGuard.needsConfirmation(MeteredRemindMode.SESSION_ONCE, sessionConfirmed = true))
    }
}
