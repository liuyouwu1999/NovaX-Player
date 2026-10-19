package com.novax.app.player

/**
 * Jellyfin 自动档决策（spec §6）：消费播放状态流，对 AUTO 档给出降/升清晰度决策，
 * 由 PlaybackService 据此重建引擎切流。防抖规则移植 NvAdaptivePolicy 的真机教训：
 * - 长缓冲（>30s）或 180s 窗内 3 次短 rebuffer → 降档；
 * - 连续 180s 无 rebuffer 且已到 READY → 升档；
 * - 引擎重建宽限窗（15s）内的缓冲不算网络证据（防自激）；
 * - 动作间冷却 60s；手动指定档位（activeTier != AUTO）时不决策。
 *
 * 档位约定（ORIGINAL 与 AUTO 的 maxBitrateBps 同为 Int.MAX_VALUE——必须按枚举身份
 * 判别，绝不用码率比较，否则手动「原画」会被误判成自动档）：[tick] 的 activeTier
 * 传「用户所选档位」——AUTO 表示自动档运行中，阶梯步进基于内部跟踪的生效档
 * [currentTier]（AUTO 起播原画，随降/升档决策逐档步进）；其余任何值视为手动档。
 * 纯逻辑，无 Android 依赖，JVM 可测。
 */
class NvQualityAutoPolicy(
    private val nowMs: () -> Long,
    private val cfg: Config = Config(),
) {
    data class Config(
        val longBufferMs: Long = 30_000L,
        val burstWindowMs: Long = 180_000L,
        val burstCount: Int = 3,
        val upgradeAfterStableMs: Long = 180_000L,
        val rebuildGraceMs: Long = 15_000L,
        val actionCooldownMs: Long = 60_000L,
    )

    enum class Action { NONE, DOWNGRADE, UPGRADE }

    data class Decision(val action: Action, val target: NvQualityTier?, val reason: String)

    /** 自动档阶梯的当前生效档：AUTO 起播原画，随决策逐档步进（手动档不决策，不受影响） */
    private var current = NvQualityTier.ORIGINAL

    private val rebuffers = ArrayDeque<Long>()
    private var bufferingSince = NO_TIME
    private var lastActionAt = -cfg.actionCooldownMs
    private var graceUntil = -1L
    private var stableSince = NO_TIME
    private var frozen = false

    /** 自动档阶梯当前生效档（AUTO 起播原画；降/升档后步进；UI 展示与测试断言用） */
    fun currentTier(): NvQualityTier = current

    /**
     * 引擎实例更换（决策后的重建/自动连播都会换）：清空缓冲观测并重开宽限窗。
     * 阶梯生效档 [current] **不**重置——决策触发的引擎重建若重置阶梯，
     * 下一次缓冲会再次从原画降档，形成「原画↔20M」自激循环。
     */
    fun onEngineSwapped() {
        rebuffers.clear(); bufferingSince = NO_TIME
        graceUntil = nowMs() + cfg.rebuildGraceMs
        stableSince = NO_TIME
    }

    /** 用户手动切档：重置观测并进入冷却（自动档不与手动抢方向盘） */
    fun onUserAction() {
        rebuffers.clear(); bufferingSince = NO_TIME; stableSince = NO_TIME
        lastActionAt = nowMs()
    }

    fun onStateChanged(stateBuffering: Boolean, playing: Boolean, frozen: Boolean) {
        this.frozen = frozen
        if (frozen) { bufferingSince = NO_TIME; rebuffers.clear(); return }
        val now = nowMs()
        if (stateBuffering) {
            if (bufferingSince == NO_TIME) bufferingSince = now
            stableSince = NO_TIME
            return
        }
        if (bufferingSince != NO_TIME) {
            val endedAt = now
            if (endedAt - bufferingSince >= MIN_REBUFFER_MS && endedAt > graceUntil) {
                rebuffers.addLast(endedAt)
            }
            bufferingSince = NO_TIME
        }
        if (playing) {
            if (stableSince == NO_TIME) stableSince = now
        } else {
            stableSince = NO_TIME
        }
    }

    fun tick(activeTier: NvQualityTier): Decision {
        // 身份判别（Task 1 review）：ORIGINAL/AUTO 码率相同，按码率比较会把手动原画
        // 误判为自动档——只认 activeTier != AUTO
        if (frozen || activeTier != NvQualityTier.AUTO) return Decision(Action.NONE, null, "not-auto")
        val now = nowMs()
        if (now < graceUntil) return Decision(Action.NONE, null, "grace")
        val inCooldown = now - lastActionAt < cfg.actionCooldownMs
        // 降档：长缓冲（且缓冲开始于宽限窗后——窗内的缓冲是重建自身的代价，不算网络证据）
        //       或 窗内累计 3 次短 rebuffer
        val bufferingNow = bufferingSince != NO_TIME
        val longBuffer = bufferingNow && bufferingSince > graceUntil &&
            now - bufferingSince > cfg.longBufferMs
        while (rebuffers.isNotEmpty() && now - rebuffers.first() > cfg.burstWindowMs) rebuffers.removeFirst()
        val burst = rebuffers.size >= cfg.burstCount
        if ((longBuffer || burst) && !inCooldown) {
            lastActionAt = now
            val target = current.lower()
            rebuffers.clear(); bufferingSince = NO_TIME
            return if (target == null) Decision(Action.NONE, null, "floor")
            else {
                current = target
                Decision(Action.DOWNGRADE, target, if (longBuffer) "长缓冲 ${cfg.longBufferMs / 1000}s" else "burst")
            }
        }
        // 升档：稳定播放足够久（非冷却）
        if (!bufferingNow && stableSince != NO_TIME && now - stableSince >= cfg.upgradeAfterStableMs && !inCooldown) {
            lastActionAt = now
            val target = current.higher()
            stableSince = now   // 升档后重新计时
            return if (target == null) Decision(Action.NONE, null, "ceiling")
            else {
                current = target
                Decision(Action.UPGRADE, target, "稳定 ${cfg.upgradeAfterStableMs / 1000}s")
            }
        }
        return Decision(Action.NONE, null, "")
    }

    internal fun debugSnapshot(): String =
        "buffering=$bufferingSince rebuffers=${rebuffers.size} grace=$graceUntil stable=$stableSince last=$lastActionAt"

    private companion object {
        const val MIN_REBUFFER_MS = 200L
        const val NO_TIME = -1L
    }
}
