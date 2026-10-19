package com.novax.app.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闸结果→动作映射纯逻辑测试（隐私计划 Task 12）：三闸位（起播闸/应用内切集闸/服务切集闸）、
 * 解锁结果→UI 效果、补扫世代守卫、忘码设备凭据路径、指纹自动弹判定。
 * 全部纯 Kotlin，无 Android 框架依赖（PinResult 同包纯 sealed class）。
 */
class NvGateWiringTest {

    // ---------- 起播闸（PlayerActivity.onCreate / onResume 再闸共用） ----------

    @Test
    fun `起播闸-已锁目标展示解锁面板`() {
        assertEquals(
            NvGateWiring.GateAction.SHOW_UNLOCK_SHEET,
            NvGateWiring.startGate(hasSourceTarget = true, allowed = false),
        )
    }

    @Test
    fun `起播闸-未锁放行`() {
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.startGate(hasSourceTarget = true, allowed = true),
        )
    }

    @Test
    fun `起播闸-无源目标豁免（content外部打开与本地直连恒放行）`() {
        // sourceId==null 按 spec 设计豁免：即使 allowed 误传 false 也不得拦
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.startGate(hasSourceTarget = false, allowed = false),
        )
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.startGate(hasSourceTarget = false, allowed = true),
        )
    }

    // ---------- 应用内切集闸（PlayerActivity.switchQueueVideo：手动点击与 ENDED 自动连播同径） ----------

    @Test
    fun `应用内切集闸-目标已锁停连播`() {
        assertEquals(
            NvGateWiring.GateAction.STOP_AUTO_ADVANCE,
            NvGateWiring.activitySwitchGate(hasSourceTarget = true, allowed = false),
        )
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.activitySwitchGate(hasSourceTarget = true, allowed = true),
        )
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.activitySwitchGate(hasSourceTarget = false, allowed = false),
        )
    }

    // ---------- 服务切集闸（PlaybackService.switchQueue：停连播+通知拉回） ----------

    @Test
    fun `服务切集闸-目标已锁停连播并发通知`() {
        assertEquals(
            NvGateWiring.GateAction.STOP_AND_NOTIFY_LOCKED,
            NvGateWiring.serviceSwitchGate(hasSourceTarget = true, allowed = false),
        )
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.serviceSwitchGate(hasSourceTarget = true, allowed = true),
        )
        assertEquals(
            NvGateWiring.GateAction.PROCEED,
            NvGateWiring.serviceSwitchGate(hasSourceTarget = false, allowed = false),
        )
    }

    // ---------- 解锁验证结果 → UI 效果 ----------

    @Test
    fun `验证通过放行`() {
        assertEquals(NvGateWiring.UnlockEffect.Passed, NvGateWiring.unlockEffect(PinResult.Ok))
    }

    @Test
    fun `错而未达冷却-清空输入留在键盘`() {
        val effect = NvGateWiring.unlockEffect(PinResult.Wrong(0))
        assertTrue(effect is NvGateWiring.UnlockEffect.Stay)
        assertTrue((effect as NvGateWiring.UnlockEffect.Stay).showWrong)
    }

    @Test
    fun `错误触发冷却-进冷却倒计时`() {
        val effect = NvGateWiring.unlockEffect(PinResult.Wrong(30))
        assertEquals(NvGateWiring.UnlockEffect.Cooldown(30), effect)
    }

    @Test
    fun `冷却中恒进冷却倒计时（正确 PIN 也等）`() {
        assertEquals(NvGateWiring.UnlockEffect.Cooldown(60), NvGateWiring.unlockEffect(PinResult.Cooldown(60)))
    }

    @Test
    fun `密文损坏直接引导忘码流（不进错误计数循环）`() {
        assertEquals(NvGateWiring.UnlockEffect.BrokenGuide, NvGateWiring.unlockEffect(PinResult.Broken))
    }

    // ---------- 解锁补扫世代守卫（T11 交接：重锁后补扫不得继续写入库） ----------

    @Test
    fun `补扫守卫-世代未变且仍在解锁集才提交`() {
        assertTrue(NvGateWiring.rescanShouldCommit(generationAtStart = 3, generationNow = 3, stillUnlocked = true))
    }

    @Test
    fun `补扫守卫-重锁后世代已变不得提交`() {
        assertFalse(NvGateWiring.rescanShouldCommit(generationAtStart = 3, generationNow = 4, stillUnlocked = true))
    }

    @Test
    fun `补扫守卫-规则不在解锁集不得提交`() {
        assertFalse(NvGateWiring.rescanShouldCommit(generationAtStart = 3, generationNow = 3, stillUnlocked = false))
    }

    // ---------- 忘码：设备凭据路径（双 SDK 分支 + 未设锁屏先警告） ----------

    @Test
    fun `忘码-未设锁屏先警告`() {
        assertEquals(
            NvGateWiring.CredentialPath.WARN_NO_KEYGUARD,
            NvGateWiring.credentialPath(sdkInt = 33, deviceSecure = false),
        )
        assertEquals(
            NvGateWiring.CredentialPath.WARN_NO_KEYGUARD,
            NvGateWiring.credentialPath(sdkInt = 27, deviceSecure = false),
        )
    }

    @Test
    fun `忘码-SDK30+走BiometricPrompt设备凭据组合`() {
        assertEquals(
            NvGateWiring.CredentialPath.BIOMETRIC_PROMPT_SDK30,
            NvGateWiring.credentialPath(sdkInt = 30, deviceSecure = true),
        )
        assertEquals(
            NvGateWiring.CredentialPath.BIOMETRIC_PROMPT_SDK30,
            NvGateWiring.credentialPath(sdkInt = 34, deviceSecure = true),
        )
    }

    @Test
    fun `忘码-SDK26到29走KeyguardManager意图`() {
        assertEquals(
            NvGateWiring.CredentialPath.KEYGUARD_INTENT_SDK26_29,
            NvGateWiring.credentialPath(sdkInt = 26, deviceSecure = true),
        )
        assertEquals(
            NvGateWiring.CredentialPath.KEYGUARD_INTENT_SDK26_29,
            NvGateWiring.credentialPath(sdkInt = 29, deviceSecure = true),
        )
    }

    // ---------- 指纹自动弹判定（已启用且设备可用才自动弹） ----------

    @Test
    fun `指纹自动弹-启用且可用才弹`() {
        assertTrue(NvGateWiring.autoBiometricPrompt(enabled = true, canAuthenticate = true))
        assertFalse(NvGateWiring.autoBiometricPrompt(enabled = false, canAuthenticate = true))
        assertFalse(NvGateWiring.autoBiometricPrompt(enabled = true, canAuthenticate = false))
        assertFalse(NvGateWiring.autoBiometricPrompt(enabled = false, canAuthenticate = false))
    }

    // ---------- PIN 设置两遍输入状态机（PinSetupSheet 消费） ----------

    @Test
    fun `PIN设置-两遍一致才提交`() {
        assertEquals(NvGateWiring.SetupPass.MISMATCH, NvGateWiring.setupPass("1234", "5678"))
        assertEquals(NvGateWiring.SetupPass.MATCH, NvGateWiring.setupPass("1234", "1234"))
    }

    // ---------- Jellyfin 服务器上报单点判定（隐私计划 Task 17：无痕→不建 reporter） ----------

    @Test
    fun `jf上报-无痕命中不放行`() {
        var asked: Pair<Long?, String>? = null
        val allowed = NvGateWiring.jfReportAllowed(7L, "/电影/片单A/ep.mkv") { sid, p ->
            asked = sid to p
            true   // fake：命中无痕规则
        }
        assertFalse(allowed)
        assertEquals(7L to "/电影/片单A/ep.mkv", asked)   // 判定输入确为当前播放项 (sourceId, path)
    }

    @Test
    fun `jf上报-未命中无痕放行`() {
        assertTrue(NvGateWiring.jfReportAllowed(7L, "/电影/ep.mkv") { _, _ -> false })
    }

    @Test
    fun `jf上报-sourceId判定不了按不上报处理（fail-closed）`() {
        // sourceId 为 null = 无法判定是否无痕：宁不上报（服务器痕迹方向 fail-closed）
        assertFalse(NvGateWiring.jfReportAllowed(null, "/x") { _, _ -> false })
    }
}
