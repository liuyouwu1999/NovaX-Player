package com.novax.app.data.thumb

import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 抽帧请求优先级（本任务新增）：MOVIE/SERIES/EPISODE > IMAGE > VIDEO，
 * 数字越小越优先；后台渐进抽帧统一加 [BACKGROUND_PENALTY] 降级（可见项永远优先于扫描后台队列，
 * 防 2846 条摄像头切片堵住用户正在浏览的内容）。未知 kind 按 IMAGE 档（不进最低档）。
 */
object ThumbPriority {

    /** 影片/剧集/集（用户主动消费的内容）最高优先级 */
    const val RANK_MOVIE = 0

    /** 图片（预留档） */
    const val RANK_IMAGE = 1

    /** 普通视频（自拍/摄像头切片主力形态）最低优先级 */
    const val RANK_VIDEO = 2

    /** 2026-09-27 用户需求「进文件夹优先做整个文件夹的封面」：文件夹置顶档——用户当前
     *  所在目录的批量请求排到一切常规任务（含前台 MOVIE）之前 */
    const val RANK_FOLDER_BOOST = -10

    /** 后台渐进抽帧降级量：保证任何前台请求排在全部后台请求之前 */
    const val BACKGROUND_PENALTY = 100

    /** kind 名 → 优先级档位（字符串入参兼容枚举演进，如二期 IMAGE） */
    fun rankOf(kindName: String): Int = when (kindName) {
        MediaKind.MOVIE.name, MediaKind.SERIES.name, MediaKind.EPISODE.name -> RANK_MOVIE
        MediaKind.VIDEO.name -> RANK_VIDEO
        else -> RANK_IMAGE   // "IMAGE" 及未知类型
    }

    /** 完整优先级：kind 档位 + 后台降级 */
    fun of(kindName: String, background: Boolean): Int =
        rankOf(kindName) + if (background) BACKGROUND_PENALTY else 0
}

/**
 * 带优先级的抽帧请求：同级按 [seq] FIFO（[thumbRequestComparator] 的载体）。
 */
data class ThumbRequest(
    val priority: Int,
    val seq: Long,
    val item: MediaItemEntity,
) : Comparable<ThumbRequest> {
    override fun compareTo(other: ThumbRequest): Int = thumbRequestComparator.compare(this, other)

    companion object {
        /** 优先级比较器（单测覆盖）：先比 priority，再比 seq（同优先级 FIFO） */
        val thumbRequestComparator: Comparator<ThumbRequest> =
            compareBy<ThumbRequest> { it.priority }.thenBy { it.seq }
    }
}

/**
 * 抽帧工作队列（本任务新增）：优先级排序 + 同优先级 FIFO + 挂起式 [take]。
 * 替代"每任务直接 launch 进 Semaphore"的隐式队列——后者无法按 kind 优先级出队。
 * 纯逻辑类（无 Android 依赖），单测直接验证出队顺序。
 */
class ThumbWorkQueue {

    private val mutex = Mutex()
    private val heap = PriorityQueue<ThumbRequest>()
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val seqGen = AtomicLong(0)
    private val pending = AtomicInteger(0)

    /** 入队（分配自增序号；同优先级按调用顺序 FIFO 出队） */
    suspend fun offer(item: MediaItemEntity, priority: Int) {
        offer(ThumbRequest(priority, seqGen.incrementAndGet(), item))
    }

    suspend fun offer(request: ThumbRequest) {
        mutex.withLock { heap.add(request) }
        pending.incrementAndGet()
        wake.trySend(Unit)
    }

    /** 取最高优先级请求；队列空则挂起直到下一次 [offer] */
    suspend fun take(): ThumbRequest {
        while (true) {
            mutex.withLock { heap.poll() }?.let {
                pending.decrementAndGet()
                return it
            }
            wake.receive()
        }
    }

    /** 当前积压数（测试/观测用） */
    fun pendingCount(): Int = pending.get()

    /** 清空（测试用） */
    suspend fun clear() {
        mutex.withLock { heap.clear() }
        pending.set(0)
    }
}
