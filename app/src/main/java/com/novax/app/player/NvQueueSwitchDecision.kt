package com.novax.app.player

/**
 * 服务侧队列切换判定纯函数（三期 spec §1.2-2）：switchQueue 的入口门与挂起后陈旧裁决
 * 提炼为可 JVM 测试的判定，行为与 be6e3d8 逐条对齐。
 * 背景：评审 P1-1 证实 seekToNext 曾旁路 canSwitch(size>1) 门，单文件队列经
 * 蓝牙/AVRCP fallback 链可弹「已是最后一个」假 toast——守卫补齐后本判定仍是
 * switchQueue 的门面（双保险），丢弃语义不变（在途第二击直接丢弃，注释明示意图）。
 */
internal object NvQueueSwitchDecision {

    enum class SwitchAction { PROCEED, DROP_IN_FLIGHT, IGNORE_EMPTY_QUEUE, BOUND_FIRST, BOUND_LAST }

    /** 入口门：在途丢弃 → 空队列忽略 → 越界提示 → 放行（顺序与 be6e3d8 :731-743 等价，
     *  在途与空队列均为静默 return，先后不可观测）。 */
    fun decideEntry(inFlight: Boolean, queueSize: Int, queueIndex: Int, direction: Int): SwitchAction =
        when {
            inFlight -> SwitchAction.DROP_IN_FLIGHT
            queueSize == 0 -> SwitchAction.IGNORE_EMPTY_QUEUE
            queueIndex + direction < 0 -> SwitchAction.BOUND_FIRST
            queueIndex + direction >= queueSize -> SwitchAction.BOUND_LAST
            else -> SwitchAction.PROCEED
        }

    /** 挂起期间内容被并发替换（BUG-C-R2-05）：videoUrl 快照比对，陈旧意图作废（:923-925 等价）。 */
    fun isStaleIntent(originalVideoUrl: String?, currentVideoUrl: String?): Boolean =
        originalVideoUrl != currentVideoUrl
}
