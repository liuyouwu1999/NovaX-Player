package com.novax.app.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私动作状态机纯逻辑测试（隐私计划 Task 13）：长按菜单/源菜单的 PIN 闸分流、
 * 无痕清历史确认、规则旗标翻转、菜单呈现态与重挂指纹核验。
 */
class NvPrivacyActionPolicyTest {

    private fun rule(
        lock: Boolean = false,
        incognito: Boolean = false,
        dirPath: String = "/a",
    ) = NvPrivacyIndex.RuleView(
        id = 1L, sourceId = 1L, dirPath = dirPath, display = "a",
        lockEnabled = lock, incognitoEnabled = incognito, wholeSource = false,
    )

    // ── PIN 闸分流（spec §6 边界表：锁要 PIN；无痕不要 PIN） ──

    @Test
    fun `锁动作未设PIN先引导创建`() {
        assertTrue(NvPrivacyActionPolicy.pinGate(NvPrivacyActionPolicy.Kind.LOCK, hasPin = false)
            is NvPrivacyActionPolicy.PinGate.NeedPinSetup)
    }

    @Test
    fun `锁动作已设PIN直接执行`() {
        assertTrue(NvPrivacyActionPolicy.pinGate(NvPrivacyActionPolicy.Kind.LOCK, hasPin = true)
            is NvPrivacyActionPolicy.PinGate.Ready)
    }

    @Test
    fun `无痕动作不要求PIN`() {
        assertTrue(NvPrivacyActionPolicy.pinGate(NvPrivacyActionPolicy.Kind.INCOGNITO, hasPin = false)
            is NvPrivacyActionPolicy.PinGate.Ready)
        assertTrue(NvPrivacyActionPolicy.pinGate(NvPrivacyActionPolicy.Kind.INCOGNITO, hasPin = true)
            is NvPrivacyActionPolicy.PinGate.Ready)
    }

    // ── 关分支 PIN 闸（终审 C1：移除锁/关无痕必须先验 PIN） ──

    @Test
    fun `关分支_移除隐私锁先验PIN`() {
        assertTrue(NvPrivacyActionPolicy.offGate(turnOn = false) is NvPrivacyActionPolicy.OffGate.VerifyPin)
    }

    @Test
    fun `关分支_关闭无痕先验PIN`() {
        // 关分支与 Kind 无关（削弱保护方向一律 VerifyPin），由调用方按 kind 翻对应旗标
        assertTrue(NvPrivacyActionPolicy.offGate(turnOn = false) is NvPrivacyActionPolicy.OffGate.VerifyPin)
    }

    @Test
    fun `开分支_加锁与开无痕不要求验PIN`() {
        // 规格 §6：加锁才建 PIN，无 PIN 开无痕不得死锁——开分支一律直接执行
        assertTrue(NvPrivacyActionPolicy.offGate(turnOn = true) is NvPrivacyActionPolicy.OffGate.Proceed)
    }

    // ── 重挂同源校验（终审 I1：跨源重挂 fail-closed 拒绝） ──

    @Test
    fun `重挂同源放行`() {
        assertTrue(NvPrivacyActionPolicy.repointSameSource(ruleSourceId = 3L, browseSourceId = 3L))
    }

    @Test
    fun `重挂跨源拒绝`() {
        assertFalse(NvPrivacyActionPolicy.repointSameSource(ruleSourceId = 3L, browseSourceId = 4L))
    }

    // ── 无痕开启的清历史确认 ──

    @Test
    fun `无痕开启范围内已有历史先确认`() {
        assertEquals(NvPrivacyActionPolicy.IncognitoGate.ConfirmClear(7), NvPrivacyActionPolicy.incognitoGate(7))
    }

    @Test
    fun `无痕开启零条历史直接开`() {
        assertTrue(NvPrivacyActionPolicy.incognitoGate(0) is NvPrivacyActionPolicy.IncognitoGate.Proceed)
    }

    // ── 规则旗标翻转 ──

    @Test
    fun `翻转开锁`() {
        assertEquals(
            NvPrivacyActionPolicy.Flags(true, false),
            NvPrivacyActionPolicy.flipped(NvPrivacyActionPolicy.Flags(false, false), NvPrivacyActionPolicy.Kind.LOCK, on = true),
        )
    }

    @Test
    fun `翻转关锁_无痕仍在则规则保留`() {
        assertEquals(
            NvPrivacyActionPolicy.Flags(false, true),
            NvPrivacyActionPolicy.flipped(NvPrivacyActionPolicy.Flags(true, true), NvPrivacyActionPolicy.Kind.LOCK, on = false),
        )
    }

    @Test
    fun `翻转后两旗标全false则规则删除`() {
        assertNull(NvPrivacyActionPolicy.flipped(NvPrivacyActionPolicy.Flags(true, false), NvPrivacyActionPolicy.Kind.LOCK, on = false))
        assertNull(NvPrivacyActionPolicy.flipped(NvPrivacyActionPolicy.Flags(false, true), NvPrivacyActionPolicy.Kind.INCOGNITO, on = false))
    }

    @Test
    fun `翻转关无痕_锁仍在则规则保留`() {
        assertEquals(
            NvPrivacyActionPolicy.Flags(true, false),
            NvPrivacyActionPolicy.flipped(NvPrivacyActionPolicy.Flags(true, true), NvPrivacyActionPolicy.Kind.INCOGNITO, on = false),
        )
    }

    // ── 菜单呈现态 ──

    @Test
    fun `菜单态_存在重挂待办时首项挂载到此处`() {
        val m = NvPrivacyActionPolicy.menuState(hasPendingRepoint = true, ownRule = rule(lock = true))
        assertTrue(m.showRepoint)
        assertTrue(m.lockLabelOn)
        assertFalse(m.incognitoLabelOn)
    }

    @Test
    fun `菜单态_无规则显示加锁与开无痕`() {
        val m = NvPrivacyActionPolicy.menuState(hasPendingRepoint = false, ownRule = null)
        assertFalse(m.showRepoint)
        assertFalse(m.lockLabelOn)
        assertFalse(m.incognitoLabelOn)
    }

    @Test
    fun `菜单态_无痕已开的标签态`() {
        val m = NvPrivacyActionPolicy.menuState(hasPendingRepoint = false, ownRule = rule(incognito = true))
        assertFalse(m.lockLabelOn)
        assertTrue(m.incognitoLabelOn)
    }

    // ── 重挂指纹核验（仅日志/提示区分，两种情况都完成挂载） ──

    @Test
    fun `重挂指纹_采纳目标目录新指纹`() {
        assertEquals("fp2", NvPrivacyActionPolicy.repointFingerprint("fp1", "fp2"))
    }

    @Test
    fun `重挂指纹_目标列举失败回退旧指纹`() {
        assertEquals("fp1", NvPrivacyActionPolicy.repointFingerprint("fp1", null))
        assertNull(NvPrivacyActionPolicy.repointFingerprint(null, null))
    }

    @Test
    fun `重挂指纹匹配判定_旧空视作不匹配`() {
        assertTrue(NvPrivacyActionPolicy.repointMatched("fp", "fp"))
        assertFalse(NvPrivacyActionPolicy.repointMatched("fp", "other"))
        assertFalse(NvPrivacyActionPolicy.repointMatched(null, "fp"))
        assertFalse(NvPrivacyActionPolicy.repointMatched("fp", null))
    }
}
