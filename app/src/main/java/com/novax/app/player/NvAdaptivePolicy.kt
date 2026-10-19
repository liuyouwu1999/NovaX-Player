package com.novax.app.player

/**
 * 播放中自适应缓冲档位决策（纯逻辑，无 Android 依赖，JVM 可测）。
 *
 * 背景：起播前的 NvRemoteProfileDetector 只探测一次；本类消费播放过程中的
 * rebuffer 事件流，给出「升级到远程重缓冲档 / 恢复原档」的决策，由
 * PlaybackService 的 watchdog 据此以当前进度重建引擎（LoadControl 构建后不可变，
 * 换档只能重建引擎）。
 *
 * ## 2026-09-15 真机取证后的修订：升档太激进，且会自己喂自己
 *
 * 真机复现（4K SMB + 用户拖动进度条）看到一条**自激链**：
 * ```
 * 用户拖动进度条 → 起播缓冲 → 第一次升级
 *   → rebuildAdaptive() 换引擎 + 换解码器（缓冲必然再抖一次）
 *     → 抖动又被当成「网络变差」记一次 rebuffer
 *       → 凑够 2 次，升第二次（日志实证：同一秒 RESTORE 后立刻又一次 startPlayback）
 * ```
 * 而收益极小：本机 `lowMem=true`，升级前后前向缓冲 **50s → 60s**
 * （`HIGH.forwardMs=120s` 会被 `MEMORY_TIGHT_MAX_FORWARD_MS=60s` 压回 60s），
 * 却要付出「重建引擎 + 销毁解码器 + Surface bufferqueue 重连」的代价——
 * 这正是「画面没了」那个 bug 的触发条件。
 *
 * 修订后的规则（对用户操作与自身重建都不再敏感）：
 * - **升档只在「稳定播放期」判定**：本次 READY 后 [SETTLE_AFTER_READY_MS] 内不判定
 *   （用户的 seek / 起播 / 换片都会先缓冲，那不是网络变差的证据）；
 * - **自身重建不算网络抖动**：`onEngineRebuilt()` 开一个
 *   [REBUILD_GRACE_MS] 宽限窗，重建导致的缓冲不计入 rebuffer 计数；
 * - **阈值加长**：单次缓冲 >[LONG_STALL_MS]（30s）或 [BURST_WINDOW_MS]（180s）内
 *   达 [BURST_COUNT]（3 次）；
 * - 恢复：升级后连续 [RESTORE_AFTER_MS] 无 rebuffer 且无进行中的 buffering；
 * - 防抖：两次动作之间最短 [ACTION_COOLDOWN_MS]；
 * - ENDED/ERROR/暂停：冻结判定（重置计时），防止播完/出错后无谓重建。
 */
class NvAdaptivePolicy(
    private val nowMs: () -> Long,
    private val cfg: Config = Config(),
) {

    /** 阈值可注入（单测用紧时序；生产用默认值） */
    data class Config(
        val burstWindowMs: Long = BURST_WINDOW_MS,
        val burstCount: Int = BURST_COUNT,
        val longStallMs: Long = LONG_STALL_MS,
        val restoreAfterMs: Long = RESTORE_AFTER_MS,
        val actionCooldownMs: Long = ACTION_COOLDOWN_MS,
        /** 本次 READY 之后多久才允许判定升档（跳过 seek/起播/换片的起播缓冲） */
        val settleAfterReadyMs: Long = SETTLE_AFTER_READY_MS,
        /** 引擎重建后的宽限窗：重建自身造成的缓冲不计入 rebuffer */
        val rebuildGraceMs: Long = REBUILD_GRACE_MS,
    )

    enum class Action { NONE, ESCALATE, RESTORE }

    data class Decision(val action: Action, val reason: String)

    private val rebufferTimes = ArrayDeque<Long>()
    private var bufferingSinceMs = NO_TIME
    private var lastRebufferAtMs = -1L
    private var lastActionAtMs = -ACTION_COOLDOWN_MS // 冷却已过（避免 Long.MIN_VALUE 参与运算溢出）
    private var upgraded = false

    /** 最近一次「READY（缓冲结束）」时刻：升档判定必须在此之后稳定一段时间才有意义 */
    private var lastReadyAtMs = -1L

    /** 引擎重建宽限窗截止时刻：窗内发生的 buffering 属自身重建副作用，不计 rebuffer */
    private var rebuildGraceUntilMs = -1L

    /** ENDED/IDLE 冻结标记：tick 在冻结期不做任何升降级动作（换引擎自动解除） */
    private var frozen = false

    /**
     * 引擎实例更换（自适应重建/自动连播都会换）：清空全部观测状态与冻结标记，保留 upgraded 记忆。
     *
     * 注意：**这是换引擎这件事本身**，会连带一段缓冲；所以同时开宽限窗，
     * 避免「重建 → 缓冲 → 判定为网络差 → 再重建」的自激（真机实证的自激链见类注释）。
     */
    fun onEngineSwapped() {
        rebufferTimes.clear()
        bufferingSinceMs = NO_TIME
        lastRebufferAtMs = -1L
        frozen = false
        rebuildGraceUntilMs = nowMs() + cfg.rebuildGraceMs
    }

    /**
     * 自适应自身重建完成（PlaybackService.rebuildAdaptive 调用）：开宽限窗。
     * 与 [onEngineSwapped] 同一件事的两个入口；分开命名便于调用点自解释。
     */
    fun onEngineRebuilt() {
        rebuildGraceUntilMs = nowMs() + cfg.rebuildGraceMs
        bufferingSinceMs = NO_TIME
    }

    /**
     * 用户主动跳转（seek）：本次缓冲是用户操作的结果，不是网络变差的证据。
     * 清 rebuffer 计数并重置「稳定起点」，让 seek 之后的起播缓冲完全不参与升档判定。
     */
    fun onUserSeek() {
        rebufferTimes.clear()
        bufferingSinceMs = NO_TIME
        lastReadyAtMs = -1L
    }

    /** 完全重置（新播放会话）。冷却视为已过——不能用 Long.MIN_VALUE，会在冷却运算里溢出为负。 */
    fun reset() {
        onEngineSwapped()
        upgraded = false
        lastActionAtMs = -ACTION_COOLDOWN_MS
        lastReadyAtMs = -1L
    }

    /**
     * 播放状态喂入。[stateBuffering] = playbackState==STATE_BUFFERING，
     * [playing] = playWhenReady 且未 ENDED/ERROR，[frozen] = ENDED/ERROR/非前台播放。
     */
    fun onStateChanged(stateBuffering: Boolean, playing: Boolean, frozen: Boolean) {
        if (frozen) {
            this.frozen = true
            bufferingSinceMs = NO_TIME
            rebufferTimes.clear()
            return
        }
        this.frozen = false
        if (stateBuffering) {
            if (bufferingSinceMs == NO_TIME) bufferingSinceMs = nowMs()
        } else {
            if (bufferingSinceMs != NO_TIME) {
                // 一次完整 rebuffer（BUFFERING→READY）；不足 200ms 的抖动不算
                val endedAt = nowMs()
                val duration = endedAt - bufferingSinceMs
                if (duration >= MIN_REBUFFER_MS) {
                    // 宽限窗内（引擎重建自身造成）或稳定期未到（seek/起播/换片造成）→ 不计入
                    val withinGrace = endedAt <= rebuildGraceUntilMs
                    if (!withinGrace) {
                        rebufferTimes.addLast(endedAt)
                        lastRebufferAtMs = endedAt
                    }
                }
                bufferingSinceMs = NO_TIME
            }
            // 已达到 READY：记录稳定起点（升档判定从这里开始计时）
            lastReadyAtMs = nowMs()
        }
        if (!playing) bufferingSinceMs = NO_TIME
    }

    fun tick(): Decision {
        if (frozen) return Decision(Action.NONE, "frozen")
        val now = nowMs()
        if (!upgraded) {
            // 两条判定路径的「防误判」条件不同（真机+单测都验证过，勿合并成一个 settled 门）：
            //  · burst（窗口内累计 N 次 rebuffer）：本身就是慢变量证据，**不需要**稳定期门。
            //    否则「一直抖、每次都抖不到 30s」的连接会因为稳定期被反复重置而**永远不升级**。
            //  · longStall（单次缓冲 30s）：起播缓冲/seek 后的起播缓冲很容易长，必须要求
            //    已经稳定播放过一段时间才认定是网络问题。
            val burst = countInWindow(now, cfg.burstWindowMs) >= cfg.burstCount
            val settled = lastReadyAtMs != NO_TIME && now - lastReadyAtMs >= cfg.settleAfterReadyMs
            val longStall = settled && bufferingSinceMs != NO_TIME &&
                now - bufferingSinceMs > cfg.longStallMs
            if ((burst || longStall) && now - lastActionAtMs >= cfg.actionCooldownMs) {
                upgraded = true
                lastActionAtMs = now
                return Decision(
                    Action.ESCALATE,
                    if (longStall) {
                        "单次缓冲超 ${cfg.longStallMs / 1000}s"
                    } else {
                        "${cfg.burstWindowMs / 1000}s 内缓冲 ${cfg.burstCount} 次"
                    },
                )
            }
        } else {            val cleanSince = maxOf(lastRebufferAtMs, lastActionAtMs)
            if (bufferingSinceMs == NO_TIME && now - cleanSince >= cfg.restoreAfterMs) {
                upgraded = false
                lastActionAtMs = now
                return Decision(Action.RESTORE, "连续 ${cfg.restoreAfterMs / 1000}s 无缓冲")
            }
        }
        return Decision(Action.NONE, "")
    }

    private fun countInWindow(now: Long, windowMs: Long): Int {
        while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > windowMs) {
            rebufferTimes.removeFirst()
        }
        return rebufferTimes.size
    }

    /** 诊断快照（包内可见，供单测/日志确认为何未触发升档） */
    internal fun debugSnapshot(): String {
        val now = nowMs()
        return "now=$now upgraded=$upgraded frozen=$frozen rebuffers=${rebufferTimes.size} " +
            "lastReady=$lastReadyAtMs bufferingSince=$bufferingSinceMs " +
            "lastRebuffer=$lastRebufferAtMs lastAction=$lastActionAtMs graceUntil=$rebuildGraceUntilMs " +
            "settled=${lastReadyAtMs != NO_TIME && now - lastReadyAtMs >= cfg.settleAfterReadyMs} " +
            "burst=${countInWindow(now, cfg.burstWindowMs)}/${cfg.burstCount} " +
            "cooldownOk=${now - lastActionAtMs >= cfg.actionCooldownMs}"
    }

    companion object {
        /**
         * 单次缓冲持续多久才算「网络真的跟不上」。
         * 原值 8s 太敏感：拖动大文件进度条后的起播缓冲经常就超过 8s，
         * 真机上一次拖动就能直接触发升档（进而重建引擎）。取 30s。
         */
        private const val LONG_STALL_MS = 30_000L

        /** 窗口内累计几次 rebuffer 才算持续劣化（原 60s/2 次 → 180s/3 次） */
        private const val BURST_WINDOW_MS = 180_000L
        private const val BURST_COUNT = 3

        private const val RESTORE_AFTER_MS = 180_000L
        private const val ACTION_COOLDOWN_MS = 60_000L

        /** 本次 READY 后稳定这么久，才允许判定升档（跳过 seek/起播/换片的起播缓冲） */
        private const val SETTLE_AFTER_READY_MS = 20_000L

        /** 引擎重建后的宽限窗：重建自身造成的缓冲不计入 rebuffer（切断自激链） */
        private const val REBUILD_GRACE_MS = 15_000L

        /** 短于该时长的缓冲视为抖动，不计 rebuffer */
        private const val MIN_REBUFFER_MS = 200L

        /** 时间哨兵：比「时钟 0 点」更安全（nowMs() 合法返回 0，0 不能当未开始标记） */
        private const val NO_TIME = -1L
    }
}
