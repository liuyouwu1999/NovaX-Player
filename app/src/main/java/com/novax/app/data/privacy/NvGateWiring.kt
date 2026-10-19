package com.novax.app.data.privacy

/**
 * 隐私闸结果→动作映射纯逻辑核（隐私计划 Task 12）。三个闸位（Activity 起播闸、应用内切集闸、
 * 服务后台切集闸）的「判定结果落成哪个动作」全部收敛于此，单测 NvGateWiringTest 守护；
 * 各调用方只负责把动作翻译成自己的副作用（面板/Toast/通知），映射本身不掺 Android 依赖。
 *
 * 无源目标豁免（spec §3.1）：sourceId==null（外部 content://「打开方式」、本地裸路径直连）
 * 不属于任何源规则，恒放行——调用方必须先把 novax 目标解析成 (sourceId, path) 再进本核，
 * hasSourceTarget=false 即代表豁免分支。
 */
object NvGateWiring {

    /** 闸动作全集（每个闸位只可能落其中两个：PROCEED + 各自的拦截动作） */
    enum class GateAction {
        /** 放行，继续起播/切集流 */
        PROCEED,
        /** 起播闸拦截：展示解锁面板覆盖层，解锁成功才继续起播流 */
        SHOW_UNLOCK_SHEET,
        /** 应用内切集闸拦截：停连播（放弃本次切换，保留当前集；手动点击与自动连播同语义） */
        STOP_AUTO_ADVANCE,
        /** 服务切集闸拦截：停连播 + 发「内容已上锁」通知拉回 PlayerActivity 出面板 */
        STOP_AND_NOTIFY_LOCKED,
    }

    /** 起播闸（PlayerActivity.onCreate 解析 novaxTarget 后 + onResume 再闸） */
    fun startGate(hasSourceTarget: Boolean, allowed: Boolean): GateAction =
        if (!hasSourceTarget || allowed) GateAction.PROCEED else GateAction.SHOW_UNLOCK_SHEET

    /** 应用内切集闸（PlayerActivity.switchQueueVideo，MeteredGate 之前） */
    fun activitySwitchGate(hasSourceTarget: Boolean, allowed: Boolean): GateAction =
        if (!hasSourceTarget || allowed) GateAction.PROCEED else GateAction.STOP_AUTO_ADVANCE

    /** 服务切集闸（PlaybackService.switchQueue，prepareEpisodeMedia 之前） */
    fun serviceSwitchGate(hasSourceTarget: Boolean, allowed: Boolean): GateAction =
        if (!hasSourceTarget || allowed) GateAction.PROCEED else GateAction.STOP_AND_NOTIFY_LOCKED

    /** 解锁验证结果 → 解锁面板 UI 效果 */
    sealed class UnlockEffect {
        /** 通过：登记会话解锁、触发补扫、继续放行流 */
        object Passed : UnlockEffect()

        /** 未过且未触发冷却：清空输入留在键盘，提示错误 */
        data class Stay(val showWrong: Boolean) : UnlockEffect()

        /** 冷却中：倒计时禁盘（Keyboard 在 remainingSec 归零前不可用） */
        data class Cooldown(val remainingSec: Int) : UnlockEffect()

        /** PIN 密文损坏（Keystore 密钥丢失）：直接引导忘码流，不进错误计数循环 */
        object BrokenGuide : UnlockEffect()
    }

    fun unlockEffect(result: PinResult): UnlockEffect = when (result) {
        is PinResult.Ok -> UnlockEffect.Passed
        is PinResult.Wrong -> wrongEffect(result.cooldownSec)
        is PinResult.Cooldown -> UnlockEffect.Cooldown(result.remainingSec)
        is PinResult.Broken -> UnlockEffect.BrokenGuide
    }

    /** 错误分支单拆：触发冷却档落倒计时禁盘，未达阈值清输入留在键盘 */
    private fun wrongEffect(cooldownSec: Int): UnlockEffect =
        if (cooldownSec > 0) UnlockEffect.Cooldown(cooldownSec) else UnlockEffect.Stay(showWrong = true)

    /**
     * 解锁补扫世代守卫（T11 交接红线）：解锁成功先快照 generation → 扫描 → **完成前**校验
     * 世代未变且规则仍在解锁集才 markRescanned。markRescanned 只查 unlocked 不查 generation，
     * 重锁后（generation 已 +1）的迟到补扫必须靠本判定拦截，不得继续写入库。
     */
    fun rescanShouldCommit(generationAtStart: Long, generationNow: Long, stillUnlocked: Boolean): Boolean =
        stillUnlocked && generationAtStart == generationNow

    /** 忘码：设备凭据认证路径（「忘记密码？」入口按 SDK 与锁屏状态分流） */
    enum class CredentialPath {
        /** 设备未设锁屏凭据：先警告「无法防止他人清除应用数据」，用户坚持才可继续（继续后无可验凭据，直接重置） */
        WARN_NO_KEYGUARD,
        /** SDK≥30：BiometricPrompt 的 BIOMETRIC_WEAK or DEVICE_CREDENTIAL 组合（不带 negativeButtonText） */
        BIOMETRIC_PROMPT_SDK30,
        /** SDK 26~29：KeyguardManager.createConfirmDeviceCredentialIntent（BiometricPrompt 不支持凭据组合） */
        KEYGUARD_INTENT_SDK26_29,
    }

    fun credentialPath(sdkInt: Int, deviceSecure: Boolean): CredentialPath = when {
        !deviceSecure -> CredentialPath.WARN_NO_KEYGUARD
        sdkInt >= 30 -> CredentialPath.BIOMETRIC_PROMPT_SDK30
        else -> CredentialPath.KEYGUARD_INTENT_SDK26_29
    }

    /** 指纹自动弹判定：用户已启用指纹解锁 且 设备当前可用（BIOMETRIC_SUCCESS）才打开即自动弹 */
    fun autoBiometricPrompt(enabled: Boolean, canAuthenticate: Boolean): Boolean = enabled && canAuthenticate

    /** PIN 设置两遍输入裁决：一致才允许提交写库 */
    enum class SetupPass { MATCH, MISMATCH }

    fun setupPass(first: String, second: String): SetupPass =
        if (first == second) SetupPass.MATCH else SetupPass.MISMATCH

    /**
     * Jellyfin 服务器进度上报单点判定（隐私计划 Task 17）：无痕命中 → 不放行（不创建
     * reporter）。PlaybackService 的 5 个上报形态（start / 暂停恢复单发 / 换片 stopped /
     * 心跳 / 收口兜底 stopped）全部经 jfReporter 实例消费，创建点一处拦截即全覆盖。
     * sourceId 判定不了（null）按不上报处理——服务器痕迹方向 fail-closed；isIncognito
     * 判定本身的失败兜底（按无痕处理）由调用方注入的 lambda 负责（与 covers 同款 getOrDefault(true)）。
     */
    fun jfReportAllowed(sourceId: Long?, path: String, isIncognito: (Long?, String) -> Boolean): Boolean =
        sourceId != null && !isIncognito(sourceId, path)
}
