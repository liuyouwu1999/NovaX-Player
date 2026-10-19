package com.novax.app.player

import androidx.room.withTransaction
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.WatchHistoryEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 观看历史写入器（C11 §5.2，播放器唯一写入口）。
 *
 * **upsert 语义**：按唯一键 (sourceId, path) 事务内先查后写——
 * 查有 → 带 id REPLACE 覆盖更新（幂等）；查无 → 插入新行（**首播即入库**）。
 * 绝不允许"查无行即 return"：那会让任何观看历史永远写进不了表（阻断性缺陷）。
 *
 * 线程约定：[record] 必须在主线程调用（ExoPlayer 位置/时长只能在其所属线程读取），
 * 位置快照取到后经内部 IO scope 落库——该 scope 独立于 Activity lifecycleScope，
 * onDestroy 触发的兜底写入不会被协程取消截断；写入失败静默（不影响退出速度）。
 *
 * **有效性闸门**（C11 §2.2）：本次内容位置 >5s 或登记时的起播位置 >5s（续播场景）才写；
 * <5s 视为误触不入库。心跳仅在 isPlaying 时调度，等价于"墙钟 >5s"分支。
 */
class NvHistoryWriter(
    private val db: MediaDb,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.watchHistoryDao()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val latest = MutableStateFlow<Snapshot?>(null)

    data class Snapshot(
        val sourceId: Long?,
        val sourceType: String?,
        val path: String,
        val title: String,
        val kind: String,                       // WatchKind.name
        val positionMs: Long,
        val durationMs: Long,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    )

    /** 起播时登记目标（含续播起始位置，供闸门判断"曾看到 >5s"） */
    fun attach(s: Snapshot) {
        latest.value = s
    }

    /** sourceType 后置覆盖（2026-09-18 RMVB 兜底页：起播时类型未知，拿到 SourceEntity 后修正） */
    fun retagSourceType(type: String) {
        latest.value = latest.value?.copy(sourceType = type)
    }

    /** 主线程调用：读播放器快照 → 异步落库（fire-and-forget，失败静默） */
    fun record(engine: PlaybackEngine) {
        val player = engine.player
        record(player.currentPosition.coerceAtLeast(0L), player.duration.takeIf { it > 0L } ?: 0L)
    }

    /**
     * 位置直填版（2026-09-18 双内核）：供 libVLC 兜底播放页使用——它不持有 PlaybackEngine，
     * 直接给 media.time/length。有效性闸门与 [record] 同口径，另拦「本次 0 进度」：
     * 起播失败/未开播的 flush（mp.time=0）不得覆写已存的续播位置
     * （BUG-C-R3-02：续播显式位 >10s 时旧闸门放行 0，几分钟旧进度被清成 0）。
     */
    fun record(positionMs: Long, durationMs: Long) {
        val s = latest.value ?: return
        if (positionMs <= 0L) return                                  // 0 进度绝不落库
        if (positionMs <= 5_000L && s.positionMs <= 5_000L) return   // 有效性闸门：>5s 才写
        write(s.copy(positionMs = positionMs, durationMs = durationMs))
    }

    /** 心跳入口：仅在播放中写（等价 C11 §2.2 "墙钟 >5s"由调度保证） */
    fun recordIfPlaying(engine: PlaybackEngine) {
        if (engine.player.isPlaying) record(engine)
    }

    private fun write(s: Snapshot) {
        scope.launch {
            runCatching { upsert(s) }
        }
    }

    /** 事务内 find→insert/REPLACE：防 NULL 唯一索引并发插重（C11 §2.1 红线） */
    private suspend fun upsert(s: Snapshot) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val old = dao.findByKey(s.sourceId, s.path)
            if (old != null) {
                // 查有：同键覆盖更新（幂等），duration 探测失败保留旧值
                dao.insert(
                    old.copy(
                        positionMs = s.positionMs,
                        durationMs = s.durationMs.takeIf { it > 0L } ?: old.durationMs,
                        watchedAt = now,
                    ),
                )
            } else {
                // 查无：插入新行（首播即入库），id 交 autoGenerate
                dao.insert(
                    WatchHistoryEntity(
                        sourceId = s.sourceId,
                        sourceType = s.sourceType,
                        path = s.path,
                        title = s.title,
                        kind = s.kind,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs,
                        watchedAt = now,
                        seasonNumber = s.seasonNumber,
                        episodeNumber = s.episodeNumber,
                    ),
                )
            }
        }
    }
}
