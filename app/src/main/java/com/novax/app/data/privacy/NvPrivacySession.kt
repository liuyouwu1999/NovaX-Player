package com.novax.app.data.privacy

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 解锁会话（隐私计划 Task 11，spec §3.4）：进程级纯内存单例——解锁规则集 + 补扫标记 +
 * 补扫世代号。解锁接线：NvPrivacyAccess.get 构造处回填 unlockedIds=[unlockedRuleIds]，
 * 播放/扫描/缩略图闸据此放行；重锁（[NvPrivacyRelockOrchestrator]）经 [relockAll] 一次性
 * 收割待清理集并使在途补扫失效（世代号 +1，补扫完成方按世代丢弃结果）。
 * 会话态绝不落盘——落盘的只有重锁待清理标记（NvPinStore.pendingWipeIds，进程被杀冷启动补跑）。
 */
object NvPrivacySession {

    /**
     * 重锁完成事件（UI 回前台再闸/列表刷新消费）。replay=0：订阅者只关心订阅后的重锁；
     * 缓冲 + DROP_OLDEST 保证 emit 在主线程永不挂起、慢订阅只丢旧不堵新。
     */
    val relockEvents = MutableSharedFlow<RelockEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class RelockEvent(val wipedRuleIds: List<Long>)

    private val lock = Any()
    private var gen = 0L
    private val unlocked = LinkedHashSet<Long>()   // 保序：待清理集/事件内容确定可断言
    private val rescanned = HashSet<Long>()

    /** 会话补扫世代号：每次重锁 +1（spec §5.3 按它取消在途补扫） */
    val generation: Long get() = synchronized(lock) { gen }

    /** 解锁闸的放行清单（NvPrivacyAccess 构造回填的唯一取用点） */
    fun unlockedRuleIds(): Set<Long> = synchronized(lock) { unlocked.toSet() }

    /** 解锁成功（Task 12 解锁面板回调）登记规则 id；重复解锁幂等 */
    fun unlock(ruleId: Long) {
        synchronized(lock) { unlocked.add(ruleId) }
    }

    /** 解锁补扫完成（Task 12：触发该范围扫描成功后调）；未解锁的 id 忽略 */
    fun markRescanned(ruleId: Long) {
        synchronized(lock) { if (ruleId in unlocked) rescanned.add(ruleId) }
    }

    /**
     * 重锁：世代号 +1（使在途补扫失效）→ 返回待清理集并清空会话。
     * 待清理集 = **全部**解锁规则（补扫未完成的也在内——解锁期间缩略图/入库行可能已在，
     * 只清 rescanned 会漏，fail-closed 取超集；purgeScope 幂等，多清无代价）。
     */
    fun relockAll(): List<Long> = synchronized(lock) {
        gen += 1
        val wiped = unlocked.toList()
        unlocked.clear()
        rescanned.clear()
        wiped
    }

    @VisibleForTesting
    fun rescannedRuleIdsForTest(): Set<Long> = synchronized(lock) { rescanned.toSet() }

    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            gen = 0
            unlocked.clear()
            rescanned.clear()
        }
    }
}
