package com.novax.app.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页规则管理行映射纯逻辑测试（隐私计划 Task 16）：RuleView + 落库 state →
 * 徽标 / 标题取值来源 / 可用动作（重挂到…、解除）。
 */
class NvPrivacyManagePolicyTest {

    private fun view(
        dirPath: String = "/media/movies",
        display: String = "movies",
        lock: Boolean = false,
        incognito: Boolean = false,
    ) = NvPrivacyIndex.RuleView(
        id = 7L, sourceId = 1L, dirPath = dirPath, display = display,
        lockEnabled = lock, incognitoEnabled = incognito, wholeSource = false,
    )

    // ── 徽标映射（未知 state fail-closed 按 BROKEN） ──

    @Test
    fun `ACTIVE状态映射ACTIVE徽标`() {
        assertEquals(
            NvPrivacyManagePolicy.Badge.ACTIVE,
            NvPrivacyManagePolicy.badgeOf(NvPrivacyIndex.STATE_ACTIVE),
        )
    }

    @Test
    fun `ORPHANED状态映射ORPHANED徽标`() {
        assertEquals(
            NvPrivacyManagePolicy.Badge.ORPHANED,
            NvPrivacyManagePolicy.badgeOf(NvPrivacyIndex.STATE_ORPHANED),
        )
    }

    @Test
    fun `BROKEN与未知状态一律映射BROKEN徽标`() {
        assertEquals(
            NvPrivacyManagePolicy.Badge.BROKEN,
            NvPrivacyManagePolicy.badgeOf(NvPrivacyIndex.STATE_BROKEN),
        )
        assertEquals(
            NvPrivacyManagePolicy.Badge.BROKEN,
            NvPrivacyManagePolicy.badgeOf("WEIRD"),
        )
    }

    // ── ACTIVE 行：只呈现，不给重挂 ──

    @Test
    fun `ACTIVE行不可重挂可解除`() {
        val row = NvPrivacyManagePolicy.rowOf(view(), NvPrivacyIndex.STATE_ACTIVE)
        assertEquals(NvPrivacyManagePolicy.Badge.ACTIVE, row.badge)
        assertFalse(row.canRepoint)
        assertTrue(row.canRemove)
    }

    @Test
    fun `两开关状态原样透传`() {
        val row = NvPrivacyManagePolicy.rowOf(view(lock = true, incognito = true), NvPrivacyIndex.STATE_ACTIVE)
        assertTrue(row.lockEnabled)
        assertTrue(row.incognitoEnabled)
    }

    // ── ORPHANED / BROKEN 行：重挂 + 解除 ──

    @Test
    fun `ORPHANED行提供重挂与解除`() {
        val row = NvPrivacyManagePolicy.rowOf(view(), NvPrivacyIndex.STATE_ORPHANED)
        assertEquals(NvPrivacyManagePolicy.Badge.ORPHANED, row.badge)
        assertTrue(row.canRepoint)
        assertTrue(row.canRemove)
    }

    @Test
    fun `BROKEN行提供重挂与解除`() {
        // 路径解不开 → dirPath 空（jf 与否不可知），canRepoint 交 true，UI 按源类型兜底摘除
        val row = NvPrivacyManagePolicy.rowOf(view(dirPath = "", display = ""), NvPrivacyIndex.STATE_BROKEN)
        assertEquals(NvPrivacyManagePolicy.Badge.BROKEN, row.badge)
        assertTrue(row.canRepoint)
        assertTrue(row.canRemove)
    }

    @Test
    fun `jf规则不给重挂仍可解除`() {
        val row = NvPrivacyManagePolicy.rowOf(
            view(dirPath = NvPrivacyIndex.JF_PREFIX + "abc123", display = "剧集"),
            NvPrivacyIndex.STATE_ORPHANED,
        )
        assertFalse(row.canRepoint)
        assertTrue(row.canRemove)
    }

    // ── 标题取值来源：display 优先 → 解密路径 → 整源/未知占位 ──

    @Test
    fun `display非空优先作标题`() {
        assertEquals(
            NvPrivacyManagePolicy.TitleSource.DISPLAY,
            NvPrivacyManagePolicy.rowOf(view(), NvPrivacyIndex.STATE_ACTIVE).titleSource,
        )
    }

    @Test
    fun `display空回退解密路径`() {
        assertEquals(
            NvPrivacyManagePolicy.TitleSource.DIR_PATH,
            NvPrivacyManagePolicy.rowOf(view(display = ""), NvPrivacyIndex.STATE_ACTIVE).titleSource,
        )
    }

    @Test
    fun `两者皆空映射整源或未知占位`() {
        assertEquals(
            NvPrivacyManagePolicy.TitleSource.WHOLE_SOURCE,
            NvPrivacyManagePolicy.rowOf(view(dirPath = "", display = ""), NvPrivacyIndex.STATE_ACTIVE).titleSource,
        )
        assertEquals(
            NvPrivacyManagePolicy.TitleSource.WHOLE_SOURCE,
            NvPrivacyManagePolicy.rowOf(view(dirPath = "", display = ""), NvPrivacyIndex.STATE_BROKEN).titleSource,
        )
    }
}
