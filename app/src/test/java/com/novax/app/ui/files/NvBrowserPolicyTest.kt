package com.novax.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件浏览器策略纯函数单测：
 * - [browserBackTarget]：返回键目标状态（系统返回键与左上角箭头共用），锁定
 *   「子目录返回重列当前目录 = 返回键失效」的回归；
 * - [lastDirPrefsKey] / [restoreBreadcrumbFrom]：每源「上次浏览目录」持久化键与
 *   恢复决策（2026-09-29：novax_ui_prefs 持久化，进源自动恢复，失效/无记录回落根）。
 */
class NvBrowserPolicyTest {

    // ---------- browserBackTarget：给定面包屑 → 返回后的目标状态 ----------

    @Test
    fun back_inSubFolder_goesUpOneLevel() {
        assertEquals(listOf("a"), browserBackTarget(listOf("a", "b")))
        assertEquals(listOf("a", "b"), browserBackTarget(listOf("a", "b", "c")))
    }

    @Test
    fun back_atFirstLevel_returnsSourceRoot() {
        assertEquals(emptyList<String>(), browserBackTarget(listOf("a")))
    }

    @Test
    fun back_atSourceRoot_closesBrowser() {
        assertNull(browserBackTarget(emptyList()))
    }

    @Test
    fun back_neverStaysOnSameDirectory_regression() {
        // 回归锁定：旧实现把 size-1 当绝对层级（take(depth+1)），子目录返回 = 重列当前目录
        for (depth in 1..4) {
            val breadcrumb = (1..depth).map { "dir$it" }
            val target = browserBackTarget(breadcrumb)!!
            assertTrue("返回后必须更浅", target.size < breadcrumb.size)
            assertEquals("返回 = 恰好回上一级", breadcrumb.dropLast(1), target)
        }
    }

    // ---------- lastDirPrefsKey / restoreBreadcrumbFrom：每源上次目录恢复决策 ----------

    @Test
    fun lastDirKey_isPerSource() {
        assertEquals("files_last_dir_7", lastDirPrefsKey(7L))
        assertEquals("files_last_dir_0", lastDirPrefsKey(0L))
        // 不同源键必须不同（按 sourceId 区分，互不串位）
        assertTrue(lastDirPrefsKey(1L) != lastDirPrefsKey(2L))
    }

    @Test
    fun restore_noRecordOrExplicitRoot_fallsBackToRoot() {
        // 无记录（null）与显式根记录（joinToPath(emptyList()) == ""）都回落根目录（空面包屑）
        assertEquals(emptyList<String>(), restoreBreadcrumbFrom(null))
        assertEquals(emptyList<String>(), restoreBreadcrumbFrom(""))
        assertEquals(emptyList<String>(), restoreBreadcrumbFrom("   "))
        assertEquals(emptyList<String>(), restoreBreadcrumbFrom("///"))
    }

    @Test
    fun restore_storedPathToBreadcrumb_matchesNavigationPaths() {
        // 与 joinToPath（写入口径）/ FileBrowser 条目点击（读出口径）互逆
        assertEquals(listOf("a"), restoreBreadcrumbFrom("/a"))
        assertEquals(listOf("a", "b"), restoreBreadcrumbFrom("/a/b"))
        assertEquals(listOf("a", "b"), restoreBreadcrumbFrom("a/b"))   // 无前导分隔符容错
    }

    @Test
    fun restore_smbVirtualShareBreadcrumb() {
        // SMB 未选共享源的虚拟共享层：/@share/<名>（SmbClient 支持直列，可按原样恢复）
        assertEquals(listOf("@share", "Media"), restoreBreadcrumbFrom("/@share/Media"))
    }

    @Test
    fun restore_joinToPath_roundTrip() {
        // 写入值（面包屑 → 路径）恢复回面包屑必须恒等（根除外：双方都是空）
        val crumbs = listOf(
            listOf("a"),
            listOf("a", "b"),
            listOf("电影", "连续剧"),
            listOf("@share", "Media"),
        )
        for (crumb in crumbs) {
            val path = crumb.fold("") { acc, seg -> com.novax.app.data.NvPaths.join(acc, seg) }
            assertEquals("往返必须恒等：$crumb", crumb, restoreBreadcrumbFrom(path))
        }
    }
}
