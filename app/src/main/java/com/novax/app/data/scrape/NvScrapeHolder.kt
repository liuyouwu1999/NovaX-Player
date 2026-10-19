package com.novax.app.data.scrape

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 单源刮削进度（设置-媒体库组进度行消费；running=false 即终态） */
data class NvScrapeProgress(
    val sourceId: Long,
    val done: Int,
    val total: Int,
    val running: Boolean,
    val message: String? = null,
)

/**
 * 刮削进度进程级单例（照 NvScannerHolder 范式）：StateFlow 供设置页与触发方订阅。
 * 状态即真相：无条目 = 该源未在刮。
 */
object NvScrapeHolder {

    private val _progress = MutableStateFlow<Map<Long, NvScrapeProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, NvScrapeProgress>> = _progress.asStateFlow()

    fun publish(p: NvScrapeProgress) = _progress.update { it + (p.sourceId to p) }

    fun clear(sourceId: Long) = _progress.update { it - sourceId }
}
