package com.novax.app.data.thumb

import java.util.concurrent.ConcurrentHashMap

/**
 * 抽帧认领闸门（C12 §三.5 队列去重）：DB 侧 [claim]（claimThumb CAS，NONE→RUNNING 的
 * 唯一写入点，受影响行数=1 才算认领成功）+ 进程内 inFlight 双保险，同一 id 并发触发仅
 * 成功一次，防并发重复抽帧。纯逻辑类，单测注入 fake claim 模拟 DB CAS。
 */
class ThumbClaimGate(
    private val claim: suspend (Long) -> Int,
    /** 2026-09-18 双内核抽帧：FAILED→RUNNING 的重试认领（libVLC 兜底扩展名专用） */
    private val claimRetry: (suspend (Long) -> Int)? = null,
) {

    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** @return true = 认领成功可入队；false = 已在队 / DB CAS 落败（他人已认领） */
    suspend fun tryClaim(id: Long): Boolean = tryClaimInternal(id, claim)

    /** FAILED 项的 libVLC 重试认领（NONE/FAILED 均可 CAS 成功） */
    suspend fun tryClaimRetry(id: Long): Boolean =
        claimRetry?.let { tryClaimInternal(id, it) } ?: tryClaimInternal(id, claim)

    private suspend fun tryClaimInternal(id: Long, claimFn: suspend (Long) -> Int): Boolean {
        if (!inFlight.add(id)) return false
        val dbOk = try {
            claimFn(id) == 1
        } catch (e: Throwable) {
            inFlight.remove(id)
            throw e
        }
        if (!dbOk) {
            inFlight.remove(id)
            return false
        }
        return true
    }

    /** 任务收尾释放（DONE/FAILED/取消之后），允许下次「重新抽帧」重新认领 */
    fun release(id: Long) {
        inFlight.remove(id)
    }

    fun inFlightCount(): Int = inFlight.size

    /** 测试辅助：挂起等待在途清零（生产路径不经此方法） */
    internal suspend fun awaitIdle(timeoutMs: Long = 5_000) {
        val start = System.currentTimeMillis()
        while (inFlight.isNotEmpty() && System.currentTimeMillis() - start < timeoutMs) {
            kotlinx.coroutines.delay(10)
        }
    }
}
