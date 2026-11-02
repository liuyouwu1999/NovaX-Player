package com.novax.app.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * durationHint 世代守卫（三期 spec §1.2-1）：异步查库回填进度条时长提示前，用世代号裁决
 * 「查询发起时的内容」与「当前内容」是否一致，晚到的旧查询一律丢弃。
 * 修复：慢查询（IO 派发/DB 锁繁忙，如 WAL checkpoint 并发）晚于 applySwitch/resync 清零
 * 返回，把旧集时长写回新集（进度条分母错、拖动比例错，直到引擎报真值）。
 * 线程模型：clear()/capture() 必须在主线程调用；refresh 的 check+apply 段固定
 * withContext(main) 执行——与 clear 同线程串行化，杜绝「check 过、写晚到」复活窗口
 * （评审 P1-2）。纯 Kotlin 无 Android 依赖，JVM 可测（测试注入 test dispatcher 作 main）。
 */
class NvDurationHintGate(
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    private var seq: Int = 0

    /** 发起查询前捕获世代号（主线程）。 */
    fun capture(): Int = seq

    /** 内容切换/重同步：世代号递增，在途旧查询全部作废（主线程）。 */
    fun clear() {
        seq++
    }

    /**
     * 执行查询并按世代裁决回填：query 在调用方上下文执行（IO），check+apply 在主线程。
     * query 抛异常按 0L 处理（不回填、不影响 gate 状态）；d<=0 不回填（无库行 ≠ 清零）。
     */
    suspend fun refresh(
        captured: Int,
        query: suspend () -> Long,
        apply: (Long) -> Unit,
    ) {
        val d = runCatching { query() }.getOrDefault(0L)
        withContext(main) {
            if (captured == seq && d > 0L) apply(d)
        }
    }
}
