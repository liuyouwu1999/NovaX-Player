package com.novax.app.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.MediaItemDao
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.ScanStateEntity
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.ThumbState
import com.novax.app.data.db.escapeLikePattern
import java.io.File
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 帧封面管线（C12 §三，修正版）：可见项 → claimThumb CAS（NONE→RUNNING 唯一写入点）→
 * 优先级队列（[ThumbWorkQueue]：MOVIE/SERIES/EPISODE > IMAGE > VIDEO，同级 FIFO，后台降级）→
 * 固定 [CONCURRENCY] 个 worker + 全局 [Semaphore](3) → NvMediaDataSource 定位读 →
 * MediaMetadataRetriever 两段式（先取元数据回填 duration/width/height，再以 duration 定帧位）→
 * JPEG(宽 480, 质量 85) 落 cacheDir/thumbs/{md5(sourceId:path)}.jpg → updateThumb(DONE)。
 *
 * 本任务新增：
 * - 并发 2 → [CONCURRENCY]=3（可见项触发优先，摄像头切片大批量场景不再堵死前台）；
 * - 队列改优先级排序（原实现按 launch 顺序隐式 FIFO，VIDEO 切片与影片同权）；
 * - 扫描 DONE 后自动后台渐进抽帧（[startScanDoneWatcher]）：按库 NAME 排序逐个 requestThumb，
 *   低优先级（+BACKGROUND_PENALTY 排在全部前台请求之后），[RateLimiter] 限速 ≤1 个/秒防 NAS 过载；
 *   性能修复：VIDEO 一律跳过后台抽帧（2846 条摄像头切片的 SMB 读+解码是发热主力），
 *   仅可见项触发时抽——该项若已在后台队列（已 CAS 成 RUNNING）则 claim 落败自动跳过。
 *
 * 状态机写入点（C12 §三.5，除此之外无任何写入路径）：
 * - NONE→RUNNING：仅 [requestThumb] 内 [MediaItemDao.claimThumb] CAS；
 * - RUNNING→DONE/FAILED：仅 [extractOne] 收尾 [MediaItemDao.updateThumb]；
 * - 冷启动对账：[recoverStale]（上一会话中断遗留的 RUNNING 复位 NONE）。
 *
 * API 等级：`MediaMetadataRetriever.setDataSource(MediaDataSource)` 需 API 29+（minSdk=26）。
 * SDK<29 走本地缓存文件中转兜底（2026-09-29 phase2 落地）：文件头（≤min(size,16MB)，256KB 块
 * 顺序读）经 openStreamBlocking 拷到 cacheDir/thumb_relay_<hash>.<ext>（扩展名辅助 MMR 识别
 * 容器），setDataSource(临时文件路径) 取帧，finally 即删；中转后仍解不出（moov 在尾部等）
 * 落既有 libVLC 兜底/FAILED，与 SDK≥29 失败路径同流。
 *
 * 无 DI 框架，单例访问点见 [NvThumbHolder]。
 */
class Thumbnailer(
    private val context: Context,
    private val repo: NvSourceRepository,
    private val dao: MediaItemDao,
    /** sourceId → 源配置（media_items 不冗余源连接信息；默认走 SourceDao.getById） */
    private val sourceProvider: suspend (Long) -> SourceEntity?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 后台渐进抽帧限速器（≤1 个/秒；注入便于单测） */
    private val backgroundRateLimiter: RateLimiter = RateLimiter(BACKGROUND_MIN_INTERVAL_MS),
    /** 计量网络闸门（2026-09-28 偷跑流量事故）：返回 true = 允许抽帧。生产注入
     * ConnectivityManager.isActiveNetworkMetered 取反；JVM 测试/缺省恒 true */
    private val isThumbNetworkAllowed: () -> Boolean = { true },
    /**
     * 隐私判定供给（隐私功能计划 Task 10）：返回 null = 无隐私层（JVM 测试缺省，零改行为）。
     * 生产 = NvPrivacyAccess::get（NvThumbHolder 注入）。requestThumb 入口纵深防御：
     * 扫描剪枝生效后锁定内容本无行可抽，此处拦存量行（加锁与对账清行之间的窗口/竞态）。
     */
    private val privacyAccess: () -> com.novax.app.data.privacy.NvPrivacyAccess? = { null },
) {

    /** 全局并发 3（原 C12 §三.5 为 2；2846 条摄像头切片场景提速 50%，SMB 随机读仍可承受） */
    private val permits = Semaphore(CONCURRENCY)

    private val gate = ThumbClaimGate(
        claim = { id -> dao.claimThumb(id) },
        claimRetry = { id -> dao.claimThumbForRetry(id) },
    )

    private val recovered = AtomicBoolean(false)

    /** 优先级队列 + 常驻 worker（[ensureWorkers] 懒启动） */
    private val queue = ThumbWorkQueue()
    private val workersStarted = AtomicBoolean(false)

    /** sourceId → 扫描 DONE 后的后台渐进抽帧 Job（重扫 RUNNING 时取消，DONE 重启） */
    private val backgroundPasses = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    // ---------- 对外 API ----------

    /** 卡片状态机订阅自己的行（NONE 骨架 / RUNNING shimmer / DONE 真帧 / FAILED fallback） */
    fun observeThumb(mediaItemId: Long): Flow<MediaItemEntity?> = dao.observeById(mediaItemId)

    /**
     * 请求抽帧（可见项触发 / 后台渐进共用）：CAS 认领成功才入队，失败（他人已认领/非 NONE，
     * 含"已在后台队列"）静默返回。认领与抽帧解耦——本方法不等待抽帧完成，卡片经 [observeThumb]
     * 的 Room Flow 自动刷新。首次调用先做冷启动对账（复位上一会话遗留 RUNNING）。
     *
     * @param background true=扫描完成后的后台渐进抽帧：低优先级（排在全部前台请求之后）
     */
    suspend fun requestThumb(mediaItemId: Long, background: Boolean = false, priority: Int? = null) {
        if (recovered.compareAndSet(false, true)) dao.resetStaleRunning()
        // 先读状态再认领：FAILED 的兜底扩展名走 claimThumbForRetry 通道
        val item = dao.findById(mediaItemId)
        // 2026-09-21 审计 P3：原 Log.w 每卡 4-5 条（release 全保留，且打印完整 NAS 路径）——
        // 降为 Log.d（R8 剥除），claim 失败的静默路径不再产生任何 release 日志
        android.util.Log.d("NvThumb", "requestThumb: id=$mediaItemId state=${item?.thumbState}")
        if (item == null) return
        // 隐私拦截（Task 10，纵深防御）：锁定且未解锁内容绝不抽帧（不读 NAS、不产明文缩略图文件）。
        // 置终态占位（DONE+无文件 → 卡片落 fallback）直接返回，绝不入队；绝不留 NONE（会被
        // 可见项触发/后台渐进反复认领），也不用 FAILED（会进网络恢复重试通道空转）
        val privacy = privacyAccess()
        if (privacy != null && !privacy.startAllowed(item.sourceId, item.path)) {
            if (item.thumbState != ThumbState.DONE.name || item.thumbnailPath != null) {
                runCatching { dao.markThumbBlocked(item.id) }
            }
            // 日志只留 id（spec §5.7：路径/文件名不出口）
            android.util.Log.w("NvPrivacy", "thumb blocked by privacy: id=$mediaItemId source=${item.sourceId}")
            return
        }
        // BUG-C-R6-17：FAILED 重试从「仅 RM/RMVB/WMV/ASF」放宽到全部扩展名——
        // 历史 FAILED 的 mp4/mkv（老 DivX 等 MediaCodec 解不出）也值得一轮 libVLC 兜底；
        // 仍由 libVlcRetryTried 保证每文件每次会话只重试一次，不产生循环开销
        val isFailedRetryable = item.thumbState == ThumbState.FAILED.name
        val claimed = if (isFailedRetryable) {
            if (!libVlcRetryTried.add(item.id)) {
                android.util.Log.d("NvThumb", "libvlc retry skip (tried this session): id=$mediaItemId")
                return
            }
            android.util.Log.d("NvThumb", "libvlc retry allowed: id=$mediaItemId")
            gate.tryClaimRetry(mediaItemId)
        } else {
            gate.tryClaim(mediaItemId)
        }
        if (!claimed) return
        enqueueClaimed(item, background, priority)
    }

    /**
     * 已 CAS 认领成功的实体直接入队（库页 needThumb 回调路径复用；background 语义同 [requestThumb]）。
     * 2026-09-27：[priority] 非 null 时覆盖 kind 自动档位（文件夹批量置顶走 RANK_FOLDER_BOOST）。
     */
    fun enqueueClaimed(item: MediaItemEntity, background: Boolean = false, priority: Int? = null) {
        ensureWorkers()
        val p = priority ?: ThumbPriority.of(item.kind.name, background)
        scope.launch { queue.offer(item, p) }
    }

    /** 冷启动对账：把上一会话中断遗留的 RUNNING 复位 NONE（防状态机卡死 shimmer，C12 §三.5） */
    suspend fun recoverStale() {
        dao.resetStaleRunning()
    }

    /**
     * 扫描 DONE 后自动后台渐进抽帧（本任务新增；NvThumbHolder 装配时注入 scan_state Flow）：
     * 任一源进入 DONE → 启动该源的渐进循环（库 NAME 排序逐个 requestThumb，低优先级，
     * [backgroundRateLimiter] 限速 ≤1 个/秒）；源重新进入 RUNNING/IDLE（重扫）→ 取消旧循环。
     * 冷启动表里已有 DONE 行也会启动（上次会话未抽完的 NONE 项继续补抽，已 DONE 项零开销跳过）。
     */
    fun startScanDoneWatcher(scanStates: Flow<List<ScanStateEntity>>) {
        scope.launch {
            scanStates.collect { states ->
                for (state in states) {
                    val existing = backgroundPasses[state.sourceId]
                    when (state.status) {
                        ScanStatus.DONE -> if (existing == null || !existing.isActive) {
                            backgroundPasses[state.sourceId] = launch { progressivePass(state.sourceId) }
                        }
                        ScanStatus.RUNNING, ScanStatus.IDLE ->
                            backgroundPasses.remove(state.sourceId)?.cancel()
                        ScanStatus.FAILED, ScanStatus.CANCELLED -> Unit   // 数据不全，不自动补抽
                    }
                }
            }
        }
    }

    /** 渐进循环：库排序顺序（NAME）快照 → 逐个跳过非 NONE → 限速后低优先级入队 */
    private suspend fun progressivePass(sourceId: Long) {
        val rows = dao.observeLibrary(
            sourceId,
            "ALL",   // 2026-09-15 分段重构：后台渐进抽帧覆盖全部视频，不分段
            watched = -1,
            sort = "NAME",
        ).first()
        for (row in rows) {
            val item = row.item
            // 性能修复（热源截断）：VIDEO（摄像头切片主力，2846 条）绝不后台全量渐进抽帧——
            // SMB 随机读 + 视频解码按 1 条/秒持续 30-60 分钟 = 手机发热第二热源。
            // VIDEO 只在用户滚到可见项时经 requestThumb 前台路径抽取（不占限速预算）。
            if (item.kind == MediaKind.VIDEO) continue
            // 已 DONE/RUNNING/FAILED 跳过（不占限速预算，避免 FAILED 重试风暴）；
            // RUNNING 含"已在前台/后台队列"——可见项触发保持，绝不重复抽帧
            if (item.thumbState != ThumbState.NONE.name) continue
            backgroundRateLimiter.acquire()
            requestThumb(item.id, background = true)
        }
    }

    /**
     * 2026-09-21 审计 P2：FAILED 墓碑批量复位 + 重启渐进 pass（网络恢复回调驱动）。
     * 背景：NAS 离线时 progressivePass 会把 NONE 全部烧成 FAILED（重扫对未变化行不重置），
     * mp4/mkv 从此无自动恢复通道。本方法给全部 FAILED 行第二次机会——代价是真坏文件每次
     * 网络恢复事件多试一轮（触发方 ≥2min 防抖 + 事件边沿触发，风暴风险有界）。
     * @return 复位的行数（0 = 无 FAILED 行，调用方可跳过 pass 重启）
     */
    suspend fun retryFailedThumbs(): Int {
        val reset = runCatching { dao.resetFailedThumbs() }.getOrDefault(0)
        if (reset > 0) {
            android.util.Log.w("NvThumb", "retryFailedThumbs: reset $reset FAILED rows -> NONE")
        }
        return reset
    }

    /** 对给定扫描态中的 DONE 源重启后台渐进 pass（retryFailedThumbs 复位后调用） */
    fun restartPasses(states: List<ScanStateEntity>) {
        for (state in states) {
            if (state.status != ScanStatus.DONE) continue
            backgroundPasses.remove(state.sourceId)?.cancel()
            backgroundPasses[state.sourceId] = scope.launch { progressivePass(state.sourceId) }
        }
    }

    /** 启动维护（NvThumbHolder 装配用）：延迟后在管线自己的 scope 执行一次（LRU 清理），失败不打断 */
    fun scheduleMaintenance(delayMs: Long, block: suspend () -> Unit) {
        scope.launch {
            delay(delayMs)
            runCatching { block() }
        }
    }

    // ---------- 队列与执行 ----------

    /** 懒启动 [CONCURRENCY] 个常驻 worker：逐个 take → Semaphore 限并发 → 抽帧 */
    private fun ensureWorkers() {
        if (!workersStarted.compareAndSet(false, true)) return
        repeat(CONCURRENCY) {
            scope.launch {
                while (true) {
                    val request = queue.take()
                    try {
                        permits.withPermit {
                            withContext(io) { extractOne(request.item) }
                        }
                    } catch (ce: CancellationException) {
                        throw ce   // scope 取消（App 退出）：随协程终止，不算单条失败
                    } catch (t: Throwable) {
                        // worker 绝不因单条异常死亡（BUG-C-R5-08：死一条=并发永久递减、该行卡 RUNNING）
                        android.util.Log.w("NvThumb", "worker survived: ${t.javaClass.simpleName} ${t.message}")
                    }
                }
            }
        }
    }

    /** 单条抽帧：两段式（先元数据后取帧），终态经 updateThumb 落库；CancellationException 透传（退后台取消≠FAILED） */
    internal suspend fun extractOne(item: MediaItemEntity) {
        extractCore(item, persist = true, outFile = thumbFile(item))
        gate.release(item.id)
    }

    /**
     * 文件页旁路抽帧（BUG-C-R6-17）：媒体库没有记录的视频（未扫描的文件夹）也能长封面。
     * 不写库（无行可写），成帧落 [browserThumbFileFor]；命中磁盘缓存直接返回，他人在抽
     * 返回 null（调用方下次进入可见行自然重试）。独立 Semaphore(2) 限流，不占库内
     * 抽帧 worker 预算；抽帧核心与 [extractOne] 共用 [extractCore]。
     */
    private val browserInFlight: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val browserPermits = Semaphore(2)

    fun browserThumbFileFor(sourceId: Long, path: String, sizeBytes: Long): File =
        ThumbCache.browserThumbFile(context, sourceId, path, sizeBytes)

    suspend fun extractBrowserThumb(sourceId: Long, path: String, sizeBytes: Long): File? {
        // 隐私拦截（Task 13 补口，与 requestThumb :127 同口径）：锁定且未解锁内容绝不经旁路
        // 通道抽帧落盘——requestThumb 只闸库内行，文件页旁路（未入库视频）绕过它
        privacyAccess()?.let { p ->
            if (!p.startAllowed(sourceId, path)) {
                android.util.Log.w("NvPrivacy", "browser thumb blocked by privacy: source=$sourceId")
                return null
            }
        }
        val key = "$sourceId:$path:$sizeBytes"
        if (!browserInFlight.add(key)) return null
        try {
            return browserPermits.withPermit {
                val transient = MediaItemEntity(
                    id = 0L,
                    sourceId = sourceId,
                    path = path,
                    name = path.substringAfterLast('/'),
                    nameLower = "",
                    sizeBytes = sizeBytes,
                    mtimeMs = 0L,
                    kind = MediaKind.VIDEO,
                    addedAt = 0L,
                )
                extractCore(transient, persist = false, outFile = browserThumbFileFor(sourceId, path, sizeBytes))
            }
        } finally {
            browserInFlight.remove(key)
        }
    }

    /**
     * 文件页图片旁路缩略图（远程源）：拉全量字节 → 降采样解码 → 240 宽 JPEG q80
     * （行内 40×60dp 够用，比视频帧 480 更省空间）。本地图片不经此路径——UI 直接
     * Coil 降采样读原文件，零磁盘。失败返回 null，调用方留字母占位。
     */
    suspend fun extractBrowserImageThumb(sourceId: Long, path: String, sizeBytes: Long): File? {
        // 隐私拦截（Task 13 补口，同 extractBrowserThumb）
        privacyAccess()?.let { p ->
            if (!p.startAllowed(sourceId, path)) {
                android.util.Log.w("NvPrivacy", "browser image thumb blocked by privacy: source=$sourceId")
                return null
            }
        }
        val key = "img:$sourceId:$path:$sizeBytes"
        if (!browserInFlight.add(key)) return null
        try {
            return browserPermits.withPermit {
                val out = browserThumbFileFor(sourceId, "img:$path", sizeBytes)
                // 磁盘命中先行：已缓存缩略图零网络，蜂窝下照常显示
                if (out.exists()) return@withPermit out
                val source = sourceProvider(sourceId) ?: return@withPermit null
                // 2026-09-28 偷跑流量修复（P0-3）：远程源在计量网络（蜂窝）上推迟图片缩略图——
                // 本路径原样全量下载原图（相机原图 3-10MB/张），是 F1a 闸门只护 extractCore
                // 漏掉的孪生旁路。与 extractCore 推迟同语义：不开任何流、返回 null（调用方
                // 只跳过 UI 回填，留字母占位）；无库行可标 FAILED，靠用户下次滚动重试自然
                // 在 Wi-Fi 下补图
                if (shouldDeferForNetwork(source.type, isThumbNetworkAllowed())) {
                    android.util.Log.w("NvThumb", "image thumb deferred (metered network): source=$sourceId type=${source.type}")
                    return@withPermit null
                }
                val tmp = File(ThumbCache.browserThumbsDir(context), "tmp_${System.nanoTime()}.bin")
                // 2026-10-22 真机修复（vivo V2509A 无线日志实证）：系统可随时清空 cacheDir，
                // browser 子目录随之消失——此处直接写 tmp 会 ENOENT 且未捕获，协程炸掉整个
                // 进程（用户表现为「播放/浏览几秒应用直接退出」）。写入前确保目录存在
                tmp.parentFile?.mkdirs()
                try {
                    withContext(io) {
                        var offset = 0L
                        tmp.outputStream().use { outs ->
                            while (offset < sizeBytes) {
                                val len = minOf(THUMB_STREAM_BLOCK_BYTES.toLong(), sizeBytes - offset).toInt()
                                repo.openStreamBlocking(
                                    source, path, offset, len,
                                    lane = com.novax.app.data.smb.SmbClient.LANE_THUMB,
                                ).use { ins ->
                                    val buf = ByteArray(64 * 1024)
                                    var done = 0
                                    while (done < len) {
                                        val n = ins.read(buf, 0, minOf(buf.size, len - done))
                                        if (n <= 0) break
                                        outs.write(buf, 0, n)
                                        done += n
                                    }
                                }
                                offset += len
                            }
                        }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmp.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
                    val bmp = BitmapFactory.decodeFile(
                        tmp.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    ) ?: return@withPermit null
                    val scaled = scaleToWidth(bmp, 240)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { it.writeJpeg(scaled) }
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                    out
                } catch (e: Exception) {
                    // 2026-10-22 真机修复：抽取失败（源不可达/目录被清/解码失败等）只应丢封面，
                    // 不允许异常穿透协程炸掉进程（vivo 无线日志实证 ENOENT 曾致整机闪退）
                    android.util.Log.w("NvThumb", "image thumb extract failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                } finally {
                    runCatching { tmp.delete() }
                }
            }
        } finally {
            browserInFlight.remove(key)
        }
    }

    private fun java.io.OutputStream.writeJpeg(bmp: Bitmap) {
        java.io.BufferedOutputStream(this).use { buffered ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, buffered)
        }
    }

    /**
     * 进文件夹重置本目录 FAILED 墓碑(§1a):每文件夹每会话一轮;封面闸门未放行时跳过
     * 且**不占去重名额**(蜂窝下先浏览、后开开关的场景拨开关后仍能触发)。RUNNING 不碰。
     * 返回是否实际执行了重置(仅日志/测试观察用)。
     */
    suspend fun resetFolderFailedOnce(sourceId: Long, dir: String): Boolean {
        if (!isThumbNetworkAllowed()) return false
        val key = "$sourceId:$dir"
        if (!folderResetDone.once(key)) return false
        val n = runCatching { dao.resetFailedThumbsInDir(sourceId, escapeLikePattern(dir)) }.getOrDefault(0)
        if (n > 0) android.util.Log.d("NvThumb", "folder reset FAILED->NONE: n=$n dir=$dir")
        return n > 0
    }

    /**
     * 抽帧核心：[persist]=false 时（旁路）不写库、不做 kind 升级、不动认领门，
     * 成帧返回落盘文件，失败返回 null。
     */
    private suspend fun extractCore(item: MediaItemEntity, persist: Boolean, outFile: File): File? {
        // 2026-09-18 双内核抽帧：RM/RMVB/WMV/ASF 的抽帧走 libVLC 转码兜底，
        // 不适用 MMR 的三个前置限制（size/SDK/source）
        val ext = item.path.substringAfterLast('.', "").lowercase()
        val isLibVlcCandidate = ext in LIBVLC_THUMB_EXTS
        var result: File? = null
        try {
            if (!isLibVlcCandidate && item.sizeBytes <= 0) {
                // size<0 不取帧：-1 是 MediaDataSource 的"未知"哨兵，绝不带进 DataSource（C12 §三.2）；
                // 中转路径同受此守卫（relayCopyLimit 按 size 定上限，size≤0 拷不出有效头）
                if (persist) dao.updateThumb(item.id, null, ThumbState.FAILED.name, item.durationMs, item.width, item.height, item.sizeBytes, item.mtimeMs)
                return null
            }
            // 2026-09-29 phase2 兜底：setDataSource(MediaDataSource) 为 API 29+，26–28 无此重载
            // （直接调=NoSuchMethodError）。SDK<29 且非 libVLC 候选改为「本地缓存文件中转」：
            // 文件头（≤min(size,16MB)）落盘 cacheDir/thumb_relay_<hash>.<ext> → setDataSource(路径)
            // 取帧 → finally 删临时文件；中转后仍解不出（moov 在尾部等）落下方既有
            // libVLC 兜底/FAILED。libVLC 候选（rm/rmvb/wmv/asf）不走中转——MMR 本就解不出，
            // 保持原样直接进兜底，避免白拷 16MB。
            val useLocalRelay = !isLibVlcCandidate && Build.VERSION.SDK_INT < 29
            val source = sourceProvider(item.sourceId)
            if (source == null) {
                // source 拿不到：远程无法读流、本地无法定位根目录——候选也不可抽
                android.util.Log.w("NvThumb", "source null: id=${item.id} ext=$ext")
                if (persist) dao.updateThumb(item.id, null, ThumbState.FAILED.name, item.durationMs, item.width, item.height, item.sizeBytes, item.mtimeMs)
                return null
            }
            // 2026-09-28 偷跑流量修复（F1a）：远程源在计量网络（蜂窝）上推迟抽帧——标 FAILED
            // 入既有网络恢复通道（onAvailable → resetFailedThumbs → 渐进 pass 重启），到 Wi-Fi
            // 自动补抽。闸门覆盖库内抽帧与文件页视频旁路（两者都经 extractCore）；图片旁路
            // extractBrowserImageThumb 不经此处，函数开头另有同款早退（2026-09-28 P0-3）。
            // 本地文件不耗流量不设闸；远程一律推迟——蜂窝下连用户浏览触发的旁路也不外发字节
            if (shouldDeferForNetwork(source.type, isThumbNetworkAllowed())) {
                android.util.Log.w("NvThumb", "deferred (metered network): id=${item.id} type=${source.type}")
                if (persist) dao.updateThumb(item.id, null, ThumbState.FAILED.name, item.durationMs, item.width, item.height, item.sizeBytes, item.mtimeMs)
                return null
            }
            // 2026-09-28 回撤「远程封面 libVLC-first」（0a1ef05）：每张封面=起代理+LibVLC 实例
            // +x264 转码前5秒（5~20s/张、并发3），把可见行触发+RANK_FOLDER_BOOST 置顶的效果
            // 吃掉了——用户浏览的文件夹从秒出图退化成十几秒一张，且慢与优先级无关。
            // 远程回到与本地同一条 MMR 路径：流量由上文蜂窝闸门（F1a，计量推迟=0 字节）+
            // 本路径 32MB 读预算（F1b，超限熔断落 FAILED）双保险，读放大有界不再 85MB；
            // 元数据（时长/宽高）还能回填 DB。rm/rmvb/wmv/asf 由下方 MMR 失败→libVLC 兜底承接
            var durationMs: Long? = null
            var width: Int? = null
            var height: Int? = null
            runCatching {
                if (useLocalRelay) {
                    // 2026-09-29 phase2：SDK<29 中转——文件头落盘后按路径接入 MMR（API 等级见
                    // useLocalRelay 注释）。临时文件生命周期严格随本块：成功/失败 finally 即删
                    val relay = relayFileFor(item)
                    try {
                        val retriever = MediaMetadataRetriever()
                        try {
                            copyHeaderToRelay(source, item, relay)
                            retriever.setDataSource(relay.absolutePath)
                            val meta = twoPhaseExtract(retriever, item, persist, outFile)
                            durationMs = meta.first; width = meta.second; height = meta.third
                        } finally {
                            retriever.release()
                        }
                    } finally {
                        runCatching { relay.delete() }
                    }
                } else {
                    val retriever = MediaMetadataRetriever()
                    val ds = NvMediaDataSource(item.sizeBytes, maxBytes = THUMB_MAX_READ_BYTES) { offset ->
                        repo.openStreamBlocking(
                            source,
                            item.path,
                            offset,
                            THUMB_STREAM_BLOCK_BYTES,
                            lane = com.novax.app.data.smb.SmbClient.LANE_THUMB,
                        )
                    }
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    try {
                        retriever.setDataSource(ds)
                        val meta = twoPhaseExtract(retriever, item, persist, outFile)
                        durationMs = meta.first; width = meta.second; height = meta.third
                    } finally {
                        // §1b 验收观测:单条抽帧耗时 + 管道跳过字节(此前无任何计时/跳过口径,提速无法验收)
                        android.util.Log.d(
                            "NvThumb",
                            "mmr extract: id=${item.id} costMs=${android.os.SystemClock.elapsedRealtime() - startedAt} skipped=${ds.pipeSkippedBytes}",
                        )
                        runCatching { ds.close() }
                        retriever.release()
                    }
                }
                result = outFile
            }.onFailure { e ->
                if (e is CancellationException) throw e
                android.util.Log.w("NvThumb", "mmr failed: id=${item.id} ext=$ext err=${e.message}")
                var handled = false
                // BUG-C-R6-17：libVLC 兜底从「仅 RM/RMVB/WMV/ASF」放宽到所有 MMR 解不出的
                // 格式；非候选扩展名一次性（会话级），解不出最多损失一次超时、不循环重试
                val allowLibVlcFallback = ext in LIBVLC_THUMB_EXTS ||
                    mmrFallbackTried.add("${item.sourceId}:${item.path}")
                if (allowLibVlcFallback) {
                    runCatching {
                        withTimeout(LIBVLC_EXTRACT_TIMEOUT_MS) {
                            extractWithLibVlc(source, item, durationMs)
                        }
                    }.getOrNull()?.let { bmp ->
                        android.util.Log.w("NvThumb", "libvlc thumb OK: id=${item.id}")
                        val file = outFile
                        file.parentFile?.mkdirs()
                        val scaled = scaleToWidth(bmp, THUMB_WIDTH)
                        file.outputStream().use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                        if (persist) {
                            dao.updateThumb(item.id, file.absolutePath, ThumbState.DONE.name, durationMs, width, height, item.sizeBytes, item.mtimeMs)
                            maybeUpgradeToMovie(item.id, durationMs)
                        }
                        handled = true
                        result = file
                    }
                }
                if (!handled) {
                    android.util.Log.w("NvThumb", "libvlc thumb FAILED: id=${item.id}")
                    if (persist) {
                        dao.updateThumb(item.id, null, ThumbState.FAILED.name, durationMs, width, height, item.sizeBytes, item.mtimeMs)
                        maybeUpgradeToMovie(item.id, durationMs)
                    }
                }
            }
        } finally {
            if (persist) gate.release(item.id)
        }
        return result
    }

    /**
     * MMR 两段式公共主体（2026-09-29 从 extractCore 抽出，供 SDK≥29 DataSource 与 SDK<29
     * 本地中转两条接入路径复用）：先回填元数据（duration/width/height），再以片长百分比
     * （10%/25%/40%，避开厂标卡/渐显黑场）候选位取帧（OPTION_CLOSEST_SYNC，全黑/纯色帧
     * 退而求其次），JPEG(宽 480) 落盘并 DONE 入库（[persist]=false 旁路只落盘）。
     *
     * @return 元数据三元组 (durationMs, width, height)——取帧失败抛异常走调用方 onFailure
     *         （libVLC 兜底/FAILED 收尾仍需要这些半程结果）
     */
    private suspend fun twoPhaseExtract(
        retriever: MediaMetadataRetriever,
        item: MediaItemEntity,
        persist: Boolean,
        outFile: File,
    ): Triple<Long?, Int?, Int?> {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        // 2026-10-23 用户真机「横屏视频被当竖屏播放」：METADATA_KEY_VIDEO_WIDTH/HEIGHT 是
        // 编码尺寸（不含旋转）——竖持横拍的视频编码 1080x1920 + rot90 实际横屏显示，按编码
        // 值入库会让切集方向预判整条判反。90/270 归一化为显示方向入库；getFrameAtTime /
        // getScaledFrameAtTime 返回帧已应用旋转，dstW:dstH 亦须按显示比例给，否则 scaled
        // 请求把竖内容塞进横容器（拉伸/裁剪）
        val width: Int?
        val height: Int?
        if ((rotation == 90 || rotation == 270) && rawW != null && rawH != null) {
            width = rawH
            height = rawW
        } else {
            width = rawW
            height = rawH
        }

        // 帧位候选取片长百分比（10%/25%/40%），避开厂标卡/渐显黑场
        val duration = durationMs ?: 40_000L
        val candidates = longArrayOf(
            (duration * 0.10f).toLong().coerceAtLeast(1_000L),
            (duration * 0.25f).toLong().coerceAtLeast(1_000L),
            (duration * 0.40f).toLong().coerceAtLeast(1_000L),
        )
        var frame: Bitmap? = null
        var fallback: Bitmap? = null
        // H-5（2026-10-18 发布终审）：API 27+ 用 getScaledFrameAtTime 原地降采样——旧路径
        // getFrameAtTime 解码**原始分辨率**整帧（4K ≈33MB）后才 scaleToWidth，候选循环同持
        // frame+fallback 两帧、CONCURRENCY=3 并发下峰值位图内存 ~200MB（低端机 OOM/被杀）。
        // 元数据 width/height 已先取到（比例已知，dstW:dstH 按比例给、无拉伸）；缺元数据或
        // API 26 保持旧路径（26 无 scaled API，本页 minSdk=26）
        val dstW: Int
        val dstH: Int
        if (width != null && width > 0 && height != null && height > 0 && width > THUMB_WIDTH) {
            dstW = THUMB_WIDTH
            dstH = (THUMB_WIDTH.toLong() * height / width).toInt().coerceAtLeast(1)
        } else {
            dstW = 0; dstH = 0   // 0 = 走原分辨率路径（小图本就无需降采样）
        }
        for (posMs in candidates) {
            val f = if (dstW > 0 && android.os.Build.VERSION.SDK_INT >= 27) {
                runCatching {
                    retriever.getScaledFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
                }.getOrNull()
                    // 个别厂商实现对 scaled 版返回 null/抛异常：退回原分辨率路径保功能
                    ?: retriever.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: continue
            if (isUsableFrame(f)) {
                fallback?.recycle()
                frame = f
                break
            }
            if (fallback == null) fallback = f else f.recycle()
        }
        val chosen = frame ?: fallback ?: error("未取得视频帧")

        // 2026-10-24 审查回退（P1-2）：DONE 行宽高恢复为**元数据宽高（含 rotation 归一化 =
        // 显示方向）**——上一版改存「取到帧的尺寸」，但旁路路径 getScaledFrameAtTime 降采样
        // 到 THUMB_WIDTH 宽，4K 源入库变 ~480×270，首页/详情的分辨率徽章（≥1600 才出
        // 1080P/2K/4K）全灭。归一化元数据同为显示方向口径（retriever 报编码尺寸 + 独立
        // rotation 交换，模拟器实证），徽章与历史语义不受影响
        outFile.parentFile?.mkdirs()
        val scaled = scaleToWidth(chosen, THUMB_WIDTH)
        outFile.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (scaled !== chosen) scaled.recycle()
        chosen.recycle()
        if (persist) {
            dao.updateThumb(item.id, outFile.absolutePath, ThumbState.DONE.name, durationMs, width, height, item.sizeBytes, item.mtimeMs)
            maybeUpgradeToMovie(item.id, durationMs)
        } else if (item.sourceId > 0 && (width ?: 0) > 0 && (height ?: 0) > 0) {
            // 2026-10-23 旁路封面顺带入库；2026-10-24 审查收敛（P1-3）：**只更新已存在的行**
            //（此前无行时插轻行——observeLibrary/search/刮削查询无 kind 过滤，轻行（name 带
            // 扩展名、addedAt=0、mtime=0）会污染媒体库页与刮削候选，已回退）。切集方向判定
            // 已不查库（改「保持当前方向等待+真实尺寸一步到位」），DB 宽高仅服务分辨率徽章，
            // 无行文件不值得为其建行
            runCatching {
                dao.updateDimensions(item.sourceId, item.path, durationMs, width, height)
            }
        }
        return Triple(durationMs, width, height)
    }

    /**
     * 2026-09-29 phase2（SDK<29 中转）：把文件头顺序拷贝到 [relay]，上限
     * [relayCopyLimit]（min(size,16MB)）。经 [NvSourceRepository.openStreamBlocking] 开
     * 单条常驻流顺序读（maxBlockBytes=256KB 与抽帧通道同口径防读放大，lane=LANE_THUMB
     * 与播放连接隔离），64KB 缓冲循环写到预算或 EOF——提前 EOF（读失败/文件缩水）不抛，
     * 拷多少算多少交 MMR 裁决（解不出即落调用方既有失败路径）；真正的 IO 异常向上抛，
     * 临时文件由调用方 finally 删除。
     */
    private suspend fun copyHeaderToRelay(
        source: com.novax.app.data.db.SourceEntity,
        item: MediaItemEntity,
        relay: File,
    ) {
        withContext(io) {
            val limit = relayCopyLimit(item.sizeBytes)
            repo.openStreamBlocking(
                source,
                item.path,
                offset = 0L,
                maxBlockBytes = THUMB_STREAM_BLOCK_BYTES,
                lane = com.novax.app.data.smb.SmbClient.LANE_THUMB,
            ).use { ins ->
                relay.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (copied < limit) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), limit - copied).toInt())
                        if (n <= 0) break   // n==0 防自旋（2026-09-16 审计同型位点）
                        outs.write(buf, 0, n)
                        copied += n
                    }
                }
            }
        }
    }

    /**
     * SDK<29 中转临时文件：cacheDir/thumb_relay_<md5(sourceId:path)>.<原扩展名>。
     * 扩展名保留（小写化）：MediaMetadataRetriever 按路径扩展名辅助嗅探容器，无后缀的
     * mp4/mkv 裸文件名会显著降低解容器成功率。命名规则见 [relayFileName]。
     */
    internal fun relayFileFor(item: MediaItemEntity): File {
        val ext = item.path.substringAfterLast('.', "").lowercase()
        return File(context.cacheDir, relayFileName(ThumbCache.hashKey(item.sourceId, item.path), ext))
    }

    /**
     * libVLC 抽帧兜底（2026-09-18 双内核）：MediaMetadataRetriever 解不出的 RM/RMVB/WMV/ASF，
     * 用 libVLC 把**前 5 秒转码成临时 mkv（h264）**，再用现有 MMR 两段式从临时文件抽帧。
     * 为什么转码而不是直接截帧：libvlc-android 3.6.2 绑定没有 takeSnapshot API；
     * mkv 是簇结构，转码中断的截断文件 MMR 仍可读（mp4 的 moov 在尾部则不行）。
     * - 静音（--aout=dummy）：后台抽帧绝不发声；
     * - 远程源经 NvRmvbProxy（127.0.0.1 Range 代理）走与播放相同的 SMB 读通道；
     * - 全程硬超时 [LIBVLC_EXTRACT_TIMEOUT_MS]，任何失败返回 null（调用方保持 FAILED）；
     * - 生命周期：LibVLC/MediaPlayer/代理/临时文件严格随本函数创建销毁。
     */
    private suspend fun extractWithLibVlc(
        source: com.novax.app.data.db.SourceEntity,
        item: MediaItemEntity,
        durationMs: Long?,
    ): Bitmap? = withContext(io) {
        var proxy: com.novax.app.player.NvRmvbProxy? = null
        var libVlc: LibVLC? = null
        var mp: MediaPlayer? = null
            val tmpDir = File(context.cacheDir, "thumbs/rmvb")
            // 旁路抽帧的临时实体 id 恒 0——拼 nanoTime 防并发覆盖（库内路径 id 唯一不受影响）
            val dst = File(tmpDir, "${item.id}_${System.nanoTime()}.mkv")
        try {
            tmpDir.mkdirs()
            dst.delete()
            val uri = if (source.type.name == "LOCAL") {
                android.net.Uri.fromFile(File(repo.localAbsolutePath(source, item.path)))
            } else {
                // 2026-09-28（F4）：代理会话读预算 64MB 熔断——20s 墙钟之外的字节保险丝，
                // 高码率片远程抽帧不再无上界拉流
                proxy = com.novax.app.player.NvRmvbProxy.startFor(
                    repo, source, item.path, item.sizeBytes,
                    maxTotalBytes = THUMB_MAX_LIBVLC_READ_BYTES,
                )
                    ?: return@withContext null
                android.net.Uri.parse(proxy.url)
            }

            libVlc = LibVLC(context, arrayListOf("--aout=dummy"))
            mp = MediaPlayer(libVlc)
            val media = Media(libVlc, uri).apply {
                setHWDecoderEnabled(true, false)
                // 只转码前 5 秒 → h264 + mkv（簇结构：截断文件仍可读）；音频不转（省 CPU，抽帧不需要）
                addOption(":sout=#transcode{vcodec=h264,vb=300}:std{access=file,mux=mkv,dst='${dst.absolutePath.replace("'", "\'")}'}")
                addOption(":sout-keep")
                addOption(":start-time=0")
                addOption(":stop-time=5")
            }
            mp.media = media
            mp.play()

            // 等转码产出。注意：mkv muxer 有写缓冲，数据在 mp.stop() 时才完整落盘——
            // 所以轮询只判断「编码器已产出」（x264 摘要日志/文件出现均可），真正的大小
            // 校验放在 stop 之后（首版在这里超时放弃，实际文件是好的——真机教训）
            var waited = 0
            while (waited < 12_000) {
                delay(250)
                waited += 250
                if (dst.exists() && dst.length() > 250_000L) break   // 产出充足提前收工
            }
            runCatching { mp.stop() }   // 触发 mux 落盘收尾
            delay(300)                  // 给文件系统一点落盘时间
            if (dst.exists().not() || dst.length() < 8_000L) return@withContext null

            // 用现有 MMR 两段式从临时 mkv 抽帧（候选位 50%/90%）
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(dst.absolutePath)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: (dst.length() * 8 / 300).coerceAtLeast(1_000L)   // 300kbps 反推兜底
                // 帧位统一毫秒域（BUG-C-R5-03：原实现 coerceIn(100_000L, dur-100_000L) 把
                // 微秒量级下限与毫秒片长混用，转码片仅 5s 时 min>max 直接抛异常被吞 →
                // libVLC 兜底抽帧对目标扩展名从不产出）
                val hiMs = (dur - 1_000L).coerceAtLeast(1_000L)
                for (frac in listOf(0.5f, 0.9f)) {
                    val at = (dur * frac).toLong().coerceIn(1_000L, hiMs)
                    val frame = retriever.getFrameAtTime(
                        at * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: continue
                    // 质检：转码帧即真实内容帧，但全黑片源仍可能拿到黑帧——不合格试下一候选
                    if (isUsableFrame(frame)) return@withContext frame
                    frame.recycle()
                }
                retriever.getFrameAtTime(
                    (dur / 2).coerceIn(1_000L, hiMs) * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            } finally {
                runCatching { retriever.release() }
            }
        } finally {
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
            runCatching { libVlc?.release() }
            proxy?.stop()
            runCatching { dst.delete() }
        }
    }

    /**
     * kind 升级（2026-09-15 接线修复 C12 §1.3 死链）：duration ≥ 40min 且当前仍 VIDEO → MOVIE。
     * 旧行为：DAO upgradeKindToMovie 全项目零调用 + ScanMerge 重扫把 kind 打回，影片 Tab 大面积空。
     * SQL 自带 AND kind='VIDEO' 守卫，EPISODE/IMAGE 不受影响；重复调用幂等。
     */
    private suspend fun maybeUpgradeToMovie(id: Long, durationMs: Long?) {
        if (isMovieLongEnough(durationMs)) {
            runCatching { dao.upgradeKindToMovie(id) }
        }
    }

    /**
     * 帧质检（2026-09-18 抽帧改进）：32×32 采样算亮度均值与标准差，
     * 拒绝全黑（<20，片头黑场/渐显）与近纯色（标准差 <10，厂标卡/白屏）帧。
     * 阈值刻意保守——只挡极端帧，正常暗场景/雪景不受影响。
     */
    private fun isUsableFrame(bmp: Bitmap): Boolean {
        val w = 32
        val h = 32
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        var sum = 0L
        val lumas = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val luma = (77 * (p shr 16 and 0xFF) + 150 * (p shr 8 and 0xFF) + 29 * (p and 0xFF)) shr 8
            lumas[i] = luma
            sum += luma
        }
        val mean = sum / lumas.size
        if (mean < FRAME_MIN_MEAN_LUMA || mean > FRAME_MAX_MEAN_LUMA) return false
        var sq = 0L
        for (l in lumas) {
            val d = l - mean
            sq += d * d
        }
        val std = kotlin.math.sqrt(sq / lumas.size.toDouble())
        return std >= FRAME_MIN_STDDEV
    }

    /** cacheDir/thumbs/{md5(sourceId:path)}.jpg */
    internal fun thumbFile(item: MediaItemEntity): File =
        File(ThumbCache.thumbsDir(context), ThumbCache.hashKey(item.sourceId, "${item.path}:${item.sizeBytes}:${item.mtimeMs}") + ".jpg")

    companion object {
        /** 短边缩放到宽 480（等比），单图 30–80KB，千部库 ≈ 30–80MB（C12 §三.4） */
        internal const val THUMB_WIDTH = 480

        /**
         * 单次抽帧的读字节预算（2026-09-28 偷跑流量事故，F1b）：MMR 对 moov 在尾部的
         * mp4 有 1.5×+ 顺序读放大（实测 55MB 片抽一张 20KB 封面拉 85MB）——烧断即落
         * FAILED 占位图。32MB ≈ 覆盖 1080p 常见码率影片的容器解析 + 定帧所需，
         * 4K 蓝光原盘最坏也只花 32MB。本地源同样受限（省磁盘/CPU，语义统一）。
         */
        internal const val THUMB_MAX_READ_BYTES: Long = 32L * 1024 * 1024

        /**
         * libVLC 远程抽帧会话读预算（2026-09-28 F4）：远程源经 NvRmvbProxy 拉流转码前 5 秒，
         * 原本只有 [LIBVLC_EXTRACT_TIMEOUT_MS] 20s 墙钟——Wi-Fi 下高码率片 5 秒转码可拉
         * 数十 MB。64MB 会话熔断 = 本地 MMR 预算（[THUMB_MAX_READ_BYTES]，32MB）的 2 倍
         * （转码含音频/容器开销更重）。超限表现为代理读侧 EOF，libVLC 转码超时收场，
         * [extractWithLibVlc] 现有超时 + finally 清理兜底。
         */
        internal const val THUMB_MAX_LIBVLC_READ_BYTES: Long = 64L * 1024 * 1024

        /** 计量网络闸门（纯函数可单测）：远程源在受限网络推迟抽帧；本地文件不耗流量不受限 */
        internal fun shouldDeferForNetwork(sourceType: SourceType, networkAllowed: Boolean): Boolean =
            sourceType != SourceType.LOCAL && !networkAllowed

        internal const val JPEG_QUALITY = 85

        /** 全局并发（本任务 2→3：并发 worker 数 = 信号量许可数） */
        internal const val CONCURRENCY = 3

        /** 后台渐进抽帧限速：≤1 个/秒，防 NAS 过载（本任务新增） */
        internal const val BACKGROUND_MIN_INTERVAL_MS = 1_000L

        /** MOVIE 升级阈值：durationMs ≥ 40min（C12 §1.3） */
        internal const val MOVIE_MIN_MS = 40L * 60 * 1000

        /** 帧质检阈值：亮度均值下限（全黑/渐显拒绝）、上限（白屏厂标卡拒绝）、标准差下限（近纯色拒绝） */
        internal const val FRAME_MIN_MEAN_LUMA = 20
        internal const val FRAME_MAX_MEAN_LUMA = 235
        internal const val FRAME_MIN_STDDEV = 10.0

        /**
         * libVLC 抽帧兜底适用的扩展名：视频容器（MMR 抽不了）——RmvbPlayerActivity
         * .FALLBACK_EXTENSIONS 的视频子集；2026-10-26 兜底表补的 wma/ape/aiff 为纯音频，
         * 永不入库抽帧，不在此列。
         */
        internal val LIBVLC_THUMB_EXTS = setOf("rm", "rmvb", "wmv", "asf")

        /** libVLC 抽帧硬超时（含代理握手/容器探测/软解起播/截帧） */
        internal const val LIBVLC_EXTRACT_TIMEOUT_MS = 20_000L

        /** FAILED 兜底扩展名的会话级重试去重（每条每会话只经 libVLC 重试一次，防风暴） */
        private val libVlcRetryTried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

        /** §1a 进文件夹 FAILED 重置的会话级去重(每文件夹每会话一轮):重置为 NONE 后走
         *  claimThumb 普通通道,不受 libVlcRetryTried 拦截——无去重则反复进出文件夹对解不出的
         *  文件反复烧满 32MB 预算 */
        private val folderResetDone = SessionOnceSet()

        /**
         * MMR 解不出时 libVLC 兜底的非候选扩展名一次性记录（会话级；key=sourceId:path）。
         * 兜底放宽到全部格式后（BUG-C-R6-17，dvp/DivX 老编码 MediaCodec 不认 ffmpeg 认），
         * 用它防止真解不出的文件每次浏览都反复烧 12s 超时。
         */
        private val mmrFallbackTried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        /**
         * 抽帧专用 SMB 块上限 256KB（2026-09-15 读放大修复）：retriever 容器读为 KB 级顺序读，
         * 4MB 块 = 最高 64× 读放大伤 NAS 盘；256KB 使放大收敛到 ≤4×，配合常驻流顺序读实际 ≈1×。
         * 播放路径仍为 4MB（SmbReadMath.MAX_STREAM_BLOCK_BYTES），互不影响。
         */
        internal const val THUMB_STREAM_BLOCK_BYTES = 256 * 1024

        /**
         * SDK<29 中转拷贝上限（2026-09-29 phase2）：16MB。mp4/mkv 的 moov 在头部时容器解析
         * +定帧数据都在首部，16MB 覆盖绝大多数场景；moov 在尾部的大文件中转后 MMR 仍解不出
         * → 既有 libVLC 兜底承接，不再加码（单条封面读预算有界优先）。
         */
        internal const val RELAY_MAX_COPY_BYTES: Long = 16L * 1024 * 1024

        /** 中转拷贝上限决策（纯函数，单测锁）：min(文件大小, 16MB)；size≤0 钳到 0（空文件→MMR 失败→既有失败路径） */
        internal fun relayCopyLimit(sizeBytes: Long): Long =
            sizeBytes.coerceIn(0L, RELAY_MAX_COPY_BYTES)

        /** 中转临时文件名（纯函数，单测锁）：thumb_relay_<hash>[.<扩展名>]，扩展名辅助 MMR 识别容器 */
        internal fun relayFileName(hash: String, ext: String): String =
            if (ext.isEmpty()) "thumb_relay_$hash" else "thumb_relay_$hash.$ext"

        /** kind 升级判定（纯函数，单测锁边界 2399999/2400000ms） */
        internal fun isMovieLongEnough(durationMs: Long?): Boolean =
            durationMs != null && durationMs >= MOVIE_MIN_MS

        /** 等比缩放到目标宽（已 ≤ 目标宽则原样返回，不放大小图） */
        internal fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
            if (src.width <= targetWidth || src.width <= 0) return src
            val h = (src.height.toLong() * targetWidth / src.width).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, targetWidth, h, true)
        }
    }
}
