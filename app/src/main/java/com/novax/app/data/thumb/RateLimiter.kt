package com.novax.app.data.thumb

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 最小间隔限速器（本任务新增：扫描完成后后台渐进抽帧 ≤1 个/秒，防 NAS 过载）。
 * 时钟与等待均可注入（[nowMs]/[waitFn]），单测零真实睡眠、确定性断言。
 */
class RateLimiter(
    /** 相邻两次 [acquire] 的最小间隔毫秒 */
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val waitFn: suspend (Long) -> Unit = { delay(it) },
) {

    private val mutex = Mutex()

    // 初值取远期过去（避免 Long.MIN_VALUE 与 minIntervalMs 相加溢出）
    private var lastAt: Long = Long.MIN_VALUE / 2

    /** 阻塞至距上次 [acquire] ≥ minIntervalMs（并发调用经 mutex 串行） */
    suspend fun acquire() {
        mutex.withLock {
            val waitMs = lastAt + minIntervalMs - nowMs()
            if (waitMs > 0) waitFn(waitMs)
            lastAt = nowMs()
        }
    }
}
