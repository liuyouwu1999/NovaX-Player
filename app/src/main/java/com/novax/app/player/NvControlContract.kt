// app/src/main/java/com/novax/app/player/NvControlContract.kt
package com.novax.app.player

/**
 * 控制层统一契约（spec §5.1）：三样式包接收同一份状态+回调，各画各的。
 * 从 NvPlayerBottomBar 参数表扩展；字段即"控制层全部输入"，样式包不得自带业务状态。
 */
data class NvControlContract(
    // —— 状态 ——
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    /** 0=未锁 1=半锁 2=全锁（PlayerLockPolicy 口径） */
    val lockMode: Int = 0,
    val skipSec: Int = 15,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val title: String = "",
    /** Jellyfin 有质量档时为 true（streaming 顶栏/更多面板据此显隐清晰度入口） */
    val hasQuality: Boolean = false,
    // —— 回调（全部默认 no-op，样式包按需接线）——
    val onBack: () -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onSkipBy: (Long) -> Unit = {},
    val onPrev: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onSpeed: () -> Unit = {},
    val onLock: () -> Unit = {},
    val onSubtitle: () -> Unit = {},
    val onAudio: () -> Unit = {},
    val onAspect: () -> Unit = {},
    val onOrientation: () -> Unit = {},
    val onVideoMirror: () -> Unit = {},
    val onVideoRotate: () -> Unit = {},
    val onPip: () -> Unit = {},
    val onQuality: () -> Unit = {},
    val onInfo: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onMore: () -> Unit = {},
    /** 直设倍速（R1 极简档倍速三件套 ⊕/⊖ 经 nvSpeedStep 步进后提交）：提交语义=SpeedSheet.onPick
     *  同款五动作（UI 速度态镜像 + 清长按倍速双态 + 引擎设速 + ≥3x 静音应用 + 跨会话记忆），
     *  不关任何面板（面板关闭归 onSpeed 打开的 SpeedSheet 自身 onPick） */
    val onSetSpeed: (Float) -> Unit = {},
    /** 拖动松手 seek（line/classic 进度条共用） */
    val onSeek: (Long) -> Unit = {},
    /** ⋯精简菜单打开态（rev5 §3.2 MinimalQuickMenu / §5.1）：编排层持有（样式包不得自带
     *  影响编排层行为的业务状态）；编排层自动隐藏计时在 true 期间挂起（主侧 LaunchedEffect
     *  key 门控、RMVB 侧 hide job 挂起——对「显隐超时零改动」的唯一显式豁免）。复位规则=
     *  任何控制层隐藏出口（锁定收层/全锁/错误态）与样式热切换一律复位 false（spec §5.1），
     *  防旗标残留→自动隐藏永久挂起/跨样式残留 */
    val quickMenuOpen: Boolean = false,
    /** ⋯块点击切换（rev5 §5.1）：开↔关互切；菜单非 LOCK 行点击后的统一关菜单也走本回调 */
    val onToggleQuickMenu: () -> Unit = {},
    /** ⋯菜单投屏行渲染门控（rev5 §5.1）：主侧=playUrl 非 null 时 true，RMVB 恒 false
     *  （hasQuality 同型先例；行集剔除由 [quickMenuRows] 消费） */
    val hasCast: Boolean = true,
)
