package com.novax.app.data.privacy

/**
 * 隐私动作状态机（隐私计划 Task 13）：文件页长按菜单与源长按菜单的「加/移除隐私锁、
 * 开/关无痕、挂载到此处」全部动作的纯逻辑分流。零 Android 依赖，单测
 * NvPrivacyActionPolicyTest 锁定；调用方（FilesViewModel/Compose）只把动作翻译成副作用。
 *
 * 口径来源：
 * - spec §4.1 入口与动作、§6 边界表（锁需要 PIN；**无痕不需要 PIN**——无 PIN 开无痕
 *   不许死锁在「要 PIN 而 PIN 不存在」）；
 * - spec §4.1「开启无痕时若该范围已有历史 → 确认同时清除」；
 * - Task 16 设定的 pendingRepoint → 本任务菜单首项「挂载到此处」完成回传。
 */
object NvPrivacyActionPolicy {

    /** 动作种类：隐私锁 / 无痕（同一规则行两旗标独立共存，见 [flipped]） */
    enum class Kind { LOCK, INCOGNITO }

    /** PIN 闸分流 */
    sealed interface PinGate {
        /** 无 PIN：先挂 PinSetupSheet，完成后续跑原动作 */
        object NeedPinSetup : PinGate

        /** 直接执行 */
        object Ready : PinGate
    }

    fun pinGate(kind: Kind, hasPin: Boolean): PinGate =
        if (kind == Kind.LOCK && !hasPin) PinGate.NeedPinSetup else PinGate.Ready

    /** 关分支（移除锁/关无痕）PIN 闸分流（终审 C1） */
    sealed interface OffGate {
        /** 削弱保护方向：先经 NvPinVerifySheet 逐次验 PIN，验过才执行 */
        object VerifyPin : OffGate

        /** 直接执行 */
        object Proceed : OffGate
    }

    /**
     * 规则增删的方向闸（终审 C1）：**关分支（移除隐私锁/关闭无痕）必须先验 PIN**——
     * 否则整源锁/ORPHANED/BROKEN 锁可被无 PIN 解除并自动重扫；开分支（加锁/开无痕）
     * 不要求（规格 §6：加锁才建 PIN，无 PIN 开无痕不得死锁）。
     */
    fun offGate(turnOn: Boolean): OffGate = if (turnOn) OffGate.Proceed else OffGate.VerifyPin

    /** 重挂同源校验（终审 I1）：跨源重挂会让规则脱离原源（原源静默失锁、目标源被误锁），fail-closed 拒绝 */
    fun repointSameSource(ruleSourceId: Long, browseSourceId: Long): Boolean = ruleSourceId == browseSourceId

    /** 无痕开启分流：范围内已有 N 条历史 → 先确认「同时清除」；0 条直接开 */
    sealed interface IncognitoGate {
        data class ConfirmClear(val count: Int) : IncognitoGate
        object Proceed : IncognitoGate
    }

    fun incognitoGate(existingRecords: Int): IncognitoGate =
        if (existingRecords > 0) IncognitoGate.ConfirmClear(existingRecords) else IncognitoGate.Proceed

    /** 规则行的两旗标（PrivacyRuleEntity.lockEnabled/incognitoEnabled 的值态投影） */
    data class Flags(val lockEnabled: Boolean, val incognitoEnabled: Boolean)

    /**
     * 翻转后的旗标；返回 null = 两旗标全 false，规则行不再需要（调用方删行）。
     * 锁与无痕独立翻转、同一行共存（嵌套/并存规则的逐条判定由 NvPrivacyIndex 承担）。
     */
    fun flipped(current: Flags, flip: Kind, on: Boolean): Flags? = when (flip) {
        Kind.LOCK -> Flags(on, current.incognitoEnabled)
        Kind.INCOGNITO -> Flags(current.lockEnabled, on)
    }.takeIf { it.lockEnabled || it.incognitoEnabled }

    /** 长按菜单/源菜单的呈现态（文案二选一与「挂载到此处」首项） */
    data class MenuState(
        val showRepoint: Boolean,      // 存在 pendingRepoint → 首项「挂载到此处」
        val lockLabelOn: Boolean,      // true = 菜单显示「移除隐私锁」
        val incognitoLabelOn: Boolean, // true = 菜单显示「关闭无痕」
    )

    fun menuState(hasPendingRepoint: Boolean, ownRule: NvPrivacyIndex.RuleView?): MenuState = MenuState(
        showRepoint = hasPendingRepoint,
        lockLabelOn = ownRule?.lockEnabled == true,
        incognitoLabelOn = ownRule?.incognitoEnabled == true,
    )

    /**
     * 重挂完成时规则行的 fingerprint 取值：目标目录指纹优先（用户明确指向的新目录）；
     * 目标列举失败回退旧指纹（保持对账能力，不因一次列举失败丢判据）。
     */
    fun repointFingerprint(old: String?, fresh: String?): String? = fresh ?: old

    /** 目标目录指纹与规则旧指纹是否一致（疑似同一目录改名/移动）；仅供日志区分，两种情况都完成挂载 */
    fun repointMatched(old: String?, fresh: String?): Boolean =
        old != null && fresh != null && NvFingerprint.matches(old, fresh)
}
