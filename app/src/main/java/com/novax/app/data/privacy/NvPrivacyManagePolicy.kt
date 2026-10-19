package com.novax.app.data.privacy

/**
 * 设置页规则管理行的呈现/动作映射纯逻辑核（隐私计划 Task 16）。零 Android 依赖，
 * 单测 NvPrivacyManagePolicyTest 锁定；UI 只把结果翻译成控件，不掺判定。
 *
 * 口径来源（spec §4.5）：ACTIVE 行只呈现（源 + 解密路径/展示名 + 两开关状态 + 徽标），
 * 规则的加锁/开无痕走文件页长按菜单（Task 13）；ORPHANED/BROKEN 行提供「重挂到…/解除」，
 * 每次变更操作逐次验 PIN（防「设置页旁路拆锁」）。
 */
object NvPrivacyManagePolicy {

    /** 规则健康徽标（privacy_rules.state 投影；未知值 fail-closed 按 BROKEN 呈现） */
    enum class Badge { ACTIVE, ORPHANED, BROKEN }

    /** 行主标题取值来源：display 优先 → 解密路径 → 整源/未知占位 */
    enum class TitleSource { DISPLAY, DIR_PATH, WHOLE_SOURCE }

    /** 规则行呈现 + 可用动作 */
    data class RuleRow(
        val badge: Badge,
        val titleSource: TitleSource,
        val lockEnabled: Boolean,
        val incognitoEnabled: Boolean,
        val canRepoint: Boolean,
        val canRemove: Boolean,
    )

    fun badgeOf(state: String): Badge = when (state) {
        NvPrivacyIndex.STATE_ACTIVE -> Badge.ACTIVE
        NvPrivacyIndex.STATE_ORPHANED -> Badge.ORPHANED
        else -> Badge.BROKEN
    }

    /**
     * RuleView + 落库 state → 行呈现。
     * jf 规则（dirPath "jf:" 前缀）无文件页目录语义——重挂会被 T13 completeRepointAt
     * fail-closed 拒绝，此处直接不给「重挂到…」（解除仍可用，设置页是 jf 规则的解除出口）。
     * BROKEN 路径解不开（dirPath 空）时 jf 与否不可知，canRepoint 恒 true，
     * UI 层按源类型（JELLYFIN）再摘一道。
     */
    fun rowOf(view: NvPrivacyIndex.RuleView, state: String): RuleRow {
        val badge = badgeOf(state)
        return RuleRow(
            badge = badge,
            titleSource = when {
                view.display.isNotBlank() -> TitleSource.DISPLAY
                view.dirPath.isNotBlank() -> TitleSource.DIR_PATH
                else -> TitleSource.WHOLE_SOURCE
            },
            lockEnabled = view.lockEnabled,
            incognitoEnabled = view.incognitoEnabled,
            canRepoint = (badge == Badge.ORPHANED || badge == Badge.BROKEN) &&
                !view.dirPath.startsWith(NvPrivacyIndex.JF_PREFIX),
            canRemove = true,
        )
    }
}
