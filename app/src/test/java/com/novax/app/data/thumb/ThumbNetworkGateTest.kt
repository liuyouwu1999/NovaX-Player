package com.novax.app.data.thumb

import com.novax.app.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面抽帧计量网络闸门（2026-09-28 后台偷跑流量事故）：
 * 远程源在蜂窝等计量网络上一律推迟抽帧（标 FAILED 走既有网络恢复通道，到 Wi-Fi 自动补抽）；
 * 本地文件不耗流量不设闸；网络可用（Wi-Fi/不限量）照常抽。
 */
class ThumbNetworkGateTest {

    @Test
    fun `远程源在受限网络推迟抽帧`() {
        assertEquals(true, Thumbnailer.shouldDeferForNetwork(SourceType.WEBDAV, networkAllowed = false))
        assertEquals(true, Thumbnailer.shouldDeferForNetwork(SourceType.SMB, networkAllowed = false))
        assertEquals(true, Thumbnailer.shouldDeferForNetwork(SourceType.SFTP, networkAllowed = false))
    }

    @Test
    fun `本地源不受网络闸门`() {
        assertEquals(false, Thumbnailer.shouldDeferForNetwork(SourceType.LOCAL, networkAllowed = false))
    }

    @Test
    fun `网络可用时任何源都不推迟`() {
        assertEquals(false, Thumbnailer.shouldDeferForNetwork(SourceType.WEBDAV, networkAllowed = true))
        assertEquals(false, Thumbnailer.shouldDeferForNetwork(SourceType.LOCAL, networkAllowed = true))
    }

    /**
     * 预算常量锁（2026-09-28 T1，照 MediaScannerBfsTest.sniffBlockContract_is64KB 模式）：
     * 三根预算线任何一根被删/改小立即红。配合 NvMediaDataSource.maxBytes 必填的编译期锁，
     * 「删一行、全部测试照样绿」的 32MB 接线回归形态双保险。
     */
    @Test
    fun `读预算契约锁_32MB与256KB块与64MB转码预算`() {
        assertEquals(32L * 1024 * 1024, Thumbnailer.THUMB_MAX_READ_BYTES)
        assertEquals(256 * 1024, Thumbnailer.THUMB_STREAM_BLOCK_BYTES)
        assertEquals(64L * 1024 * 1024, Thumbnailer.THUMB_MAX_LIBVLC_READ_BYTES)
    }
}
