package com.novax.app.ui.library

import com.novax.app.data.db.ScanStateEntity
import com.novax.app.data.db.ScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-10-26 扫描结果横幅可关闭（会话级）单测：LibraryUiState.notedScan/failedScan 的
 * dismissedAt 过滤语义——关闭只压制「这次及更早」（lastScanAt ≤ 基准）的提示，之后新扫描
 * 产生的新提示（lastScanAt 更大）必须重新出现；关闭动作本身不清 DB 诊断记录。
 */
class LibraryScanBannerTest {

    private fun scan(
        sourceId: Long,
        status: ScanStatus,
        error: String? = null,
        lastScanAt: Long = 1000L,
    ) = ScanStateEntity(
        sourceId = sourceId,
        status = status,
        scannedDirs = 1,
        foundItems = 0,
        lastScanAt = lastScanAt,
        error = error,
    )

    @Test
    fun `noted banner shows for DONE with note`() {
        val s = LibraryUiState(scans = listOf(scan(1, ScanStatus.DONE, error = "根目录不存在")))
        assertNotNull(s.notedScan)
        assertNull(s.failedScan)
    }

    @Test
    fun `noted banner hidden for DONE clean and FAILED`() {
        val s = LibraryUiState(
            scans = listOf(
                scan(1, ScanStatus.DONE, error = null),
                scan(2, ScanStatus.FAILED, error = "连接失败"),
            ),
        )
        assertNull(s.notedScan)
        assertNotNull(s.failedScan)
    }

    @Test
    fun `dismiss suppresses current noted banner`() {
        // 关闭时记录该次 lastScanAt=1000 → 同条（lastScanAt == 基准）不再显示
        val s = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.DONE, error = "根目录不存在", lastScanAt = 1000L)),
            notedScanDismissedAt = 1000L,
        )
        assertNull(s.notedScan)
    }

    @Test
    fun `dismiss is session scope - newer note reappears`() {
        // 关掉旧提示（基准 1000）后，新扫描又带新提示（lastScanAt 2000）→ 必须重新出现
        val s = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.DONE, error = "新提示", lastScanAt = 2000L)),
            notedScanDismissedAt = 1000L,
        )
        assertNotNull(s.notedScan)
        assertEquals("新提示", s.notedScan?.error)
    }

    @Test
    fun `dismiss suppresses older notes too and picks newest visible`() {
        // 双源各一条 note：基准 1500 → 旧(1000)压制、新(2000)可见
        val s = LibraryUiState(
            scans = listOf(
                scan(1, ScanStatus.DONE, error = "旧提示", lastScanAt = 1000L),
                scan(2, ScanStatus.DONE, error = "新提示", lastScanAt = 2000L),
            ),
            notedScanDismissedAt = 1500L,
        )
        assertEquals("新提示", s.notedScan?.error)
    }

    @Test
    fun `failed banner same dismissal semantics`() {
        val base = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "连接失败", lastScanAt = 1000L)),
        )
        assertNotNull(base.failedScan)
        val dismissed = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "连接失败", lastScanAt = 1000L)),
            failedScanDismissedAt = 1000L,
        )
        assertNull(dismissed.failedScan)
        val newer = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "再次失败", lastScanAt = 2000L)),
            failedScanDismissedAt = 1000L,
        )
        assertEquals("再次失败", newer.failedScan?.error)
    }
}
