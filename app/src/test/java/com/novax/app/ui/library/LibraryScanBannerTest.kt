package com.novax.app.ui.library

import com.novax.app.data.db.ScanStateEntity
import com.novax.app.data.db.ScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-26 扫描结果横幅可关闭（会话级）单测：LibraryUiState.notedScan/failedScan 的
 * dismissedAt 过滤语义——关闭只压制「这次及更早」（lastScanAt ≤ 基准）的提示，之后新扫描
 * 产生的新提示（lastScanAt 更大）必须重新出现；关闭动作本身不清 DB 诊断记录。
 *
 * 2026-11-19 用户拍板修订（失败横幅专项）：failedScan 从「时间戳会话级」改**按源持久压制**
 * （见 [FailedScanSuppressions]）——× = 该源失败横幅不再出现（自动扫描重跑失败、lastScanAt
 * 更新、重启 App 均不回场），直到手动扫描（rescan）解除；notedScan 时间戳语义不变。
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
    fun `failed banner suppressed per source regardless of newer lastScanAt`() {
        // 2026-11-19 用户报的回归场景：× 关闭后自动扫描重跑又失败（lastScanAt 更新）→
        // 旧时间戳语义横幅必然回场；按源持久压制下同源不再出现
        val base = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "连接失败", lastScanAt = 1000L)),
        )
        assertNotNull(base.failedScan)
        val dismissed = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "连接失败", lastScanAt = 1000L)),
            failedScanSuppressedSourceIds = setOf("1"),
        )
        assertNull(dismissed.failedScan)
        val rerun = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "再次失败", lastScanAt = 2000L)),
            failedScanSuppressedSourceIds = setOf("1"),
        )
        assertNull(rerun.failedScan)
    }

    @Test
    fun `other source failure still shows while one source suppressed`() {
        // 压制按源隔离：源 1 被关，源 2 新失败照常报
        val s = LibraryUiState(
            scans = listOf(
                scan(1, ScanStatus.FAILED, error = "源1失败", lastScanAt = 2000L),
                scan(2, ScanStatus.FAILED, error = "源2失败", lastScanAt = 2000L),
            ),
            failedScanSuppressedSourceIds = setOf("1"),
        )
        assertEquals(2L, s.failedScan?.sourceId)
        assertEquals("源2失败", s.failedScan?.error)
    }

    @Test
    fun `manual scan clears suppression and banner can report again`() {
        // 手动扫描（顶栏刷新 / 库页「立即扫描」→ rescan）解除压制：清空后同源新一轮失败允许再报
        val cleared = FailedScanSuppressions.cleared(setOf("1", "2"))
        assertTrue(cleared.isEmpty())
        val s = LibraryUiState(
            scans = listOf(scan(1, ScanStatus.FAILED, error = "又失败", lastScanAt = 3000L)),
            failedScanSuppressedSourceIds = cleared,
        )
        assertEquals("又失败", s.failedScan?.error)
    }

    @Test
    fun `suppression set ops are idempotent and key round trips`() {
        // × 关闭：加入压制集（幂等）；DataStore stringSetPreferencesKey 原生存取——
        // 持久化往返即 Set<String> 原样恢复（sourceId 十进制串），无自定义序列化可坏
        var set = FailedScanSuppressions.suppressed(emptySet(), 5L)
        set = FailedScanSuppressions.suppressed(set, 5L)
        assertEquals(setOf("5"), set)
        assertEquals("5", FailedScanSuppressions.keyOf(5L))
        // 入选谓词：FAILED 且源不在压制集 = 可见；DONE 或已压制 = 不可见
        assertTrue(FailedScanSuppressions.visible(scan(5, ScanStatus.FAILED), setOf()))
        assertFalse(FailedScanSuppressions.visible(scan(5, ScanStatus.FAILED), set))
        assertFalse(FailedScanSuppressions.visible(scan(5, ScanStatus.DONE), setOf()))
    }
}
