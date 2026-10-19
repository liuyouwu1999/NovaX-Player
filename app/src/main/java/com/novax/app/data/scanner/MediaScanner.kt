package com.novax.app.data.scanner

import com.novax.app.R
import com.novax.app.data.NvFileEntry
import com.novax.app.data.NvPaths
import com.novax.app.data.db.MediaItemDao
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.ScanStateDao
import com.novax.app.data.db.ScanStateEntity
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.ThumbState
import com.novax.app.data.smb.SmbShareRoot
import com.novax.app.data.smb.SmbStatusText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 实时扫描进度（内存广播；持久化最终态走 scan_state 表，C12 §二.5） */
data class ScanProgress(
    val sourceId: Long,
    val status: ScanStatus,
    val scannedDirs: Int,
    val currentDir: String,
    val foundItems: Int,
)

/**
 * 媒体索引扫描器（C12 §二）：逐源 BFS 列目录 → 视频文件入库 media_items。
 *
 * 规格：
 * - BFS 深度 ≤ [ScanRules.MAX_DEPTH]（相对源根）；单源 [ScanRules.MAX_ITEMS_PER_SOURCE] 上限截断；
 * - 串行列目录（SMB 单会话顺序列最稳），多源之间也串行（[scanAll]）；
 * - [ScanRules.BATCH_SIZE]/批 upsert，合并保护已有 thumbnailPath/addedAt 不覆盖（[ScanMerge]）；
 * - 入库跳过规则（仅 VIDEO 类，IMAGE 不受影响；文件浏览器不受影响，文件页仍可见可播）：
 *   文件名命中相机/监控命名（[ScanRules.CAMERA_OR_SEGMENT_REGEX]：IMG_/VID_/MOV_/DJI_/GOPR/GXdd/SXdd/ddM_
 *   前缀，或含 "_segment"）或 size < [ScanRules.MIN_LIBRARY_VIDEO_BYTES]（50MB，阈值可调）不入库；
 *   被跳过项不进对账 seen 集 → 扫描正常跑完后随对账一并清掉存量垃圾行（用户重扫一次即净）；
 * - 扫描正常跑完（DONE 且未截断、无失读跳过）才对账清扫已消失条目；取消/失败/截断/失读不删
 *   （数据不全对账会误删）；
 * - 非根目录列举失败（重试耗尽）→ 跳过该子树继续扫并计数（D1，2026-10-09 审计 P1：API 30+
 *   的 /Android/data 系统受限是必然而非异常），终态 DONE 带 skipped note，不再整源 FAILED；
 *   扫描根本身不可读维持整源 FAILED；唯一例外是「路径不存在」类根失败（2026-10-19 用户裁定：
 *   NAS 侧把扫描根改名/移动不该瘫痪整个媒体库）→ 降级跳过该根，DONE 带 note 提示重选；
 *   失效根的存量行由 incomplete 闸保护不被对账误删。
 * - 可取消（[cancel]/[cancelAll] 协作式停止，中断源写 CANCELLED，已入库批次保留）；
 * - 进度：[progress] StateFlow 实时广播 + scan_state 表持久化；
 * - "列目录 / 列共享"抽象为构造函数函数参数（[lister]/[shareLister]），单测注入 fake 不真连 SMB。
 *
 * 无 DI 框架，单例访问点见 [NvScannerHolder]（懒创建，避免全局 constructor 副作用）。
 */
class MediaScanner(
    private val mediaDao: MediaItemDao,
    private val scanDao: ScanStateDao,
    /** 列目录（源内统一路径 → 条目），默认走 NvSourceRepository.list；测试注入 fake */
    private val lister: suspend (SourceEntity, String) -> Result<List<NvFileEntry>>,
    /** 列 SMB 共享名（共享名留空源先枚举共享再逐个扫），默认走 listSmbShares；测试注入 fake */
    private val shareLister: suspend (SourceEntity) -> Result<List<String>>,
    /**
     * 计量网络嗅探闸门（2026-09-28 偷跑流量修复 P1-1）：返回 true = 允许对远程源做
     * headerReader 嗅探。生产注入 ConnectivityManager.isActiveNetworkMetered 取反
     * （NvScannerHolder）；网络服务不可得按受限保守处理（宁可不嗅探）；JVM 测试/缺省恒 true。
     * 本地文件不耗流量不受限（判定见 [shouldDeferSniffForNetwork]）。
     */
    private val allowNetworkSniff: () -> Boolean = { true },
    /**
     * 读文件头（2026-09-15 非媒体嗅探用）：返回 ≤512 字节；null = 读取失败（调用方 fail-open，
     * 宁可入库不误杀真媒体）。默认走 NvSourceRepository.openStreamBlocking（thumb 通道，与播放
     * 隔离）；测试注入 fake。
     *
     * maxBlockBytes = 64KB（2026-09-21 审计 P1 修复）：SMB 块流是「整块填满才返回字节」语义，
     * 嗅探只需 512B；漏传时块= min(协商 maxReadSize, 4MB) + 预读块，实测每文件从 NAS 拉 2×4MB
     * （模拟器+真 NAS 实测 2 文件 = 16.6MB），首扫 5000 文件 ≈ 40GB 读放大。64KB 覆盖
     * 512B 需求且兼容老 NAS 的最小协商块。仅 SMB 路径受此参数影响（WebDAV/LOCAL 本就精确读）。
     */
    private val headerReader: suspend (SourceEntity, String) -> ByteArray? = { source, path ->
        withContext(Dispatchers.IO) {
            runCatching {
                com.novax.app.data.NvGraph.repository()
                    .openStreamBlocking(
                        source,
                        path,
                        0L,
                        maxBlockBytes = HEADER_SNIFF_BLOCK_BYTES,
                        lane = com.novax.app.data.smb.SmbClient.LANE_THUMB,
                    ).use { ins ->
                        val buf = ByteArray(HEADER_SNIFF_BYTES)
                        var off = 0
                        while (off < buf.size) {
                            val n = ins.read(buf, off, buf.size - off)
                            if (n <= 0) break // n==0 按流结束处理：防自旋死循环
                            off += n
                        }
                        buf.copyOf(off)
                    }
            }.getOrNull()
        }
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * 扫描横幅 note/失败文案的字符串供给（i18n Task 8）：res → 当前语言串。生产 =
     * appContext::getString（NvScannerHolder 注入）；JVM 单测注入 zh 原句映射（断言维持中文）。
     * 默认恒返回 resId 占位——生产构造必须注入。
     */
    private val text: (Int) -> String = { it.toString() },
    /**
     * 隐私判定供给（隐私功能计划 Task 10）：返回 null = 无隐私层（JVM 单测缺省，全放行零改行为）。
     * 生产 = NvPrivacyAccess::get（NvScannerHolder 注入）——扫描期两处消费：
     * ①目录条目剪枝（命中锁规则且未解锁 → 不入队不产缩略图）；②收尾改名跟随对账。
     */
    private val privacyAccess: () -> com.novax.app.data.privacy.NvPrivacyAccess? = { null },
) {

    private val _progress = MutableStateFlow<Map<Long, ScanProgress>>(emptyMap())

    /** sourceId → 实时进度；扫描结束保留最终态（冷启动 UI 读 scan_state 表） */
    val progress: StateFlow<Map<Long, ScanProgress>> = _progress.asStateFlow()

    private val jobs = mutableMapOf<Long, Job>()

    /** 单飞互斥：同一源并发触发只允许一个扫描在跑 */
    private val taskLock = Any()
    private val executionMutex = Mutex()
    private var batchJob: Job? = null
    private var batchSourceId: Long? = null
    private var batchSourceIds: Set<Long> = emptySet()

    // ---------- 入口 ----------

    /**
     * 扫描单源（异步启动），返回该源的进度 Flow（Room InvalidationTracker 负责库页刷新，
     * 本 Flow 只负责进度条文案，C12 §二.5；禁止手动刷新回调/EventBus）。
     */
    fun scanSource(source: SourceEntity): Flow<ScanProgress> {
        startScan(source)
        return progress.map { it[source.id] ?: ScanProgress(source.id, ScanStatus.IDLE, 0, "", 0) }
            .distinctUntilChanged()
    }

    /** 异步启动（已在跑的源忽略重复触发；「仅作文件源」禁用源见 scanSourceNow 守卫） */
    fun startScan(source: SourceEntity) {
        val job = synchronized(taskLock) {
            if (jobs[source.id]?.isCompleted == false || batchSourceId == source.id) return
            scope.launch(start = CoroutineStart.LAZY) {
                try { scanSourceNow(source) }
                finally {
                    val self = currentCoroutineContext()[Job]
                    synchronized(taskLock) { if (jobs[source.id] === self) jobs.remove(source.id) }
                }
            }.also { jobs[source.id] = it }
        }
        job.start()
    }

    /** 全部源串行重扫（C12 §二.1 T2/T3：进度清晰、SMB 连接按源复用） */
    fun scanAll(sources: List<SourceEntity>) {
        val job = synchronized(taskLock) {
            if (batchJob?.isCompleted == false) return
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    for (s in sources) {
                        currentCoroutineContext().ensureActive()
                        synchronized(taskLock) { batchSourceId = s.id }
                        scanSourceNow(s)
                    }
                } finally {
                    val self = currentCoroutineContext()[Job]
                    synchronized(taskLock) {
                        if (batchJob === self) { batchJob = null; batchSourceId = null; batchSourceIds = emptySet() }
                    }
                }
            }.also { batchJob = it; batchSourceIds = sources.map { source -> source.id }.toSet() }
        }
        job.start()
    }

    /** 取消单源扫描（协作式；中断源写 CANCELLED，已入库批次保留） */
    fun cancel(sourceId: Long) {
        synchronized(taskLock) {
            jobs.remove(sourceId)?.cancel()
            if (sourceId in batchSourceIds) batchJob?.cancel()
        }
    }

    /** 取消全部（T2 扫描中再点 = 取消；X1 取消只取消当前 + 清队列） */
    fun cancelAll() {
        synchronized(taskLock) {
            batchJob?.cancel()
            batchJob = null
            batchSourceId = null
            batchSourceIds = emptySet()
            jobs.values.toList().forEach { it.cancel() }
            jobs.clear()
        }
    }

    suspend fun cancelAndJoin(sourceId: Long) {
        val pending = synchronized(taskLock) {
            listOfNotNull(jobs[sourceId], batchJob?.takeIf { sourceId in batchSourceIds })
                .also { tasks -> tasks.forEach { it.cancel() } }
        }
        pending.forEach { it.join() }
    }

    // ---------- 单源扫描（挂起直到终态；scanAll 与单测复用） ----------

    suspend fun scanSourceNow(source: SourceEntity) = executionMutex.withLock {
        // 2026-10-26「仅作文件源」：scanRoots=DISABLED 的源所有扫描路径（startScan/scanAll
        // 串行重扫/编辑保存触发/单测直调）一律跳过——不写 RUNNING 也不落终态，源对库不可见
        if (ScanRoots.isDisabled(source.scanRoots)) return@withLock
        currentCoroutineContext().ensureActive()
        val startedAt = System.currentTimeMillis()
        publish(source.id, ScanStatus.RUNNING, 0, "", 0)
        runCatching { scanDao.upsert(ScanStateEntity(source.id, ScanStatus.RUNNING, 0, 0, startedAt)) }
        try {
            val privacy = privacyAccess()   // 单次取用（进程级单例；null = 无隐私层全放行）
            val roots = rootDirsOf(source, privacy)
            val outcome = walk(source, roots, privacy)
            currentCoroutineContext().ensureActive()
            // D1（2026-10-09 审计 P1）：失读跳过不再是 FAILED，终态为带 note 的完成态
            // （note 写 scan_state.error 列，与截断提示同法；失败截断优先级更高，两者都不对账）。
            // 2026-10-19：缺路径跳过（降级的失效扫描根）单列一段，给出可行动的补救入口
            val note = when {
                outcome.truncated -> text(R.string.data_scan_note_truncated).format(ScanRules.MAX_ITEMS_PER_SOURCE)
                else -> {
                    val segments = mutableListOf<String>()
                    if (outcome.missingDirs > 0) {
                        segments += text(R.string.data_scan_note_missing_dirs)
                            .format(outcome.missingDirs, outcome.firstMissingDir?.take(120))
                    }
                    if (outcome.skippedDirs > 0) {
                        segments += text(R.string.data_scan_note_skipped_dirs)
                            .format(outcome.skippedDirs, outcome.firstSkippedDir?.take(120))
                    }
                    segments.joinToString("；").ifEmpty { null }
                }
            }
            scanDao.upsert(
                ScanStateEntity(
                    source.id, ScanStatus.DONE, outcome.scannedDirs, outcome.foundItems,
                    System.currentTimeMillis(), note,
                ),
            )
            publish(source.id, ScanStatus.DONE, outcome.scannedDirs, "", outcome.foundItems)
            // 对账清扫：仅正常跑完且未截断/未失读跳过（取消/失败/截断/失读时数据不全，
            // 对账会误删，C12 §二.4 + D1 2026-10-09 审计）
            if (!outcome.incomplete) {
                reconcile(source, outcome.seenPaths)
                purgeSuspectRows(source, outcome.seenPaths)   // 2026-09-15：存量非媒体行复验清理
                followPrivacyRules(source, outcome, privacy)  // Task 10：改名跟随对账（仅完整扫描）
            }
        } catch (e: CancellationException) {
            // 取消：终态写库要在 NonCancellable 下做（已取消的协程里挂起调用会被立即拒绝）；
            // 已入库批次保留（增量语义），进度保留中断时的已知值
            withContext(NonCancellable) {
                val last = _progress.value[source.id]
                runCatching {
                    scanDao.upsert(
                        ScanStateEntity(
                            source.id, ScanStatus.CANCELLED,
                            last?.scannedDirs ?: 0, last?.foundItems ?: 0,
                            System.currentTimeMillis(), null,
                        ),
                    )
                }
                publish(source.id, ScanStatus.CANCELLED, last?.scannedDirs ?: 0, "", last?.foundItems ?: 0)
            }
            throw e
        } catch (e: Exception) {
            // 2026-10-19：SMB/WebDAV 状态码类错误落库前译成人话（原样 NTSTATUS 用户读不懂、
            // 也不知道该动哪个目录）；原始异常仍在 cause 链与 logcat 可取证
            val msg = friendlyScanError(e)
            runCatching {
                scanDao.upsert(
                    ScanStateEntity(source.id, ScanStatus.FAILED, 0, 0, System.currentTimeMillis(), msg),
                )
            }
            publish(source.id, ScanStatus.FAILED, 0, "", 0)
        }
    }

    /**
     * 扫描失败落库文案（2026-10-19）：「路径不存在」类状态码错误译成可读原因 + 具体目录
     * （横幅读者需要知道去 NAS 上找什么；i18n Task 8 文案改走 [text] 资源供给），
     * 其余原样截 160 字符（含未知底层信息便于排查）。
     */
    private fun friendlyScanError(e: Exception): String {
        val raw = e.message ?: return e.javaClass.simpleName
        if (!SmbStatusText.missingPath(raw)) return raw.take(160)
        val path = SmbStatusText.uncPathOf(raw)
        return (if (path != null) {
            text(R.string.data_scan_error_path_missing_with_dir).format(path)
        } else {
            text(R.string.data_scan_error_path_missing)
        }).take(160)
    }

    // ---------- 遍历 ----------

    /**
     * 扫描根目录集：
     * 1) 源已存 scanRoots（向导第三步目录多选结果）→ 解析 + 嵌套去重后逐根 BFS；
     * 2) 为空回退现状：共享名留空的 SMB 源先枚举共享再逐个扫（复用 listShares，
     *    虚拟根 /@share/<名>/ 由 SmbClient.list 兜底直列）；其余源从根 "/" 起扫。
     */
    private suspend fun rootDirsOf(source: SourceEntity, privacy: com.novax.app.data.privacy.NvPrivacyAccess?): List<String> {
        val excluded = excludedDirsOf(source)
        val lockedRoot: (String) -> Boolean = { dir ->
            // 隐私剪枝（Task 10）：扫描根命中锁规则且未解锁 → 该根整棵不入扫（文件不被列举入库），
            // 与排除目录同语义；存量行随完整扫描的对账清除
            privacy != null && !privacy.startAllowed(source.id, dir)
        }
        val stored = ScanRoots.dedup(ScanRoots.parse(source.scanRoots))
            .map(NvPaths::asDirectory)   // 目录形式（尾 "/"），与子目录入队表示一致
        if (stored.isNotEmpty()) {
            // 排除目录恰为某扫描根（或其祖先）→ 该根整体不扫；全部根被排除 → 空列表
            //（walk 队列为空立即结束，对账清空该源索引——语义：用户明确排除了全部扫描范围）
            return stored.filterNot { ScanRules.isUnderExcludedDir(it, excluded) || lockedRoot(it) }
        }
        if (source.type == SourceType.SMB && source.shareOrPath.isBlank()) {
            val shares = shareLister(source).getOrThrow()
            return shares.map { SmbShareRoot.entryPath(it) }
                .filterNot { ScanRules.isUnderExcludedDir(it, excluded) || lockedRoot(it) }
        }
        return listOf(NvPaths.SEPARATOR).filterNot { lockedRoot(it) }
    }

    /** sources.excludedDirs → 规范化排除目录集（每次 walk 解析一次；null/损坏 → 空集） */
    private fun excludedDirsOf(source: SourceEntity): Set<String> =
        ScanRoots.parse(source.excludedDirs).map { it.trimEnd('/').let { p -> if (p.isEmpty()) "/" else p } }.toSet()

    internal data class WalkOutcome(
        val seenPaths: List<String>,
        val truncated: Boolean,
        val scannedDirs: Int,
        val foundItems: Int,
        // D1（2026-10-09 审计 P1）：重试耗尽仍失读而跳过的非根目录数 + 首个失读目录。
        // 发生过失读 → 本次对账/复验跳过（incomplete），防把失读子树存量行当"已消失"误删
        val skippedDirs: Int = 0,
        val firstSkippedDir: String? = null,
        // 2026-10-19 用户裁定：扫描根「路径不存在」类失败降级跳过的根数 + 首个失效根。
        // 同样进 incomplete 闸——失效根子树不进 seen，对账会误删其存量行（目录可能只是
        // 暂时改名/迁移，用户随时会编辑源重新选择）
        val missingDirs: Int = 0,
        val firstMissingDir: String? = null,
        // Task 10 改名跟随：queuedDirs = BFS 实际列举的目录；prunedDirs = 目击存在但未入队的
        // 目录（隐私剪枝/用户排除/深度截断边界）。两者并集 = 「确认存活目录集」交给
        // NvOrphanTracker——缺 prunedDirs 会让锁定未解锁目录每次扫描被误判丢失 → 误 ORPHANED
        val queuedDirs: Set<String> = emptySet(),
        val prunedDirs: Set<String> = emptySet(),
    ) {
        /** 数据不完整（截断/失读/缺路径跳过）→ 对账/复验跳过（C12 §二.4 + D1 共用此闸） */
        val incomplete: Boolean get() = truncated || skippedDirs > 0 || missingDirs > 0
    }

    /**
     * BFS 遍历：串行列目录、深度/数量上限、500/批提交。
     * 失败语义（D1，2026-10-09 审计 P1）：扫描根列举失败维持上抛（整源 FAILED，原行为）；
     * 非根目录重试耗尽仍失读（API 30+ 的 /Android/data 系统受限是必然而非异常、SD 卡热拔）
     * → 跳过该子树继续扫并计入 [WalkOutcome.skippedDirs]，整源不再致命化。
     */
    private suspend fun walk(source: SourceEntity, roots: List<String>, privacy: com.novax.app.data.privacy.NvPrivacyAccess?): WalkOutcome {
        val queue = ArrayDeque<Pair<String, Int>>()   // dirPath to depth（根为 0）
        // 2026-09-21 审计 P3：已访问目录去重（归一化路径）——junction/symlink 目录环下
        // 原实现仅受深度 6 封顶，环内子树被重复列目录 K^5 量级次；入队时查重即剪枝
        val visitedDirs = HashSet<String>()
        roots.forEach {
            visitedDirs.add(NvPaths.normalize(it))
            queue.add(it to 0)
        }
        // Task 10：剪枝边界目击集——目录条目在父列举中出现了却没入队（隐私锁/排除/深度截断），
        // 「确认存在但未列举」；与 visitedDirs 并集构成改名跟随的存活集
        val prunedDirs = HashSet<String>()
        val excluded = excludedDirsOf(source)        // 用户排除目录：BFS 剪枝，子树整体不扫
        val seen = ArrayList<String>()
        val batch = ArrayList<MediaItemEntity>()
        var scannedDirs = 0
        var found = 0
        var truncated = false
        var skippedDirs = 0
        var firstSkippedDir: String? = null
        var missingDirs = 0
        var firstMissingDir: String? = null
        // 2026-09-15 用户反馈（评审 P4）：目录稀疏时 batch 迟迟不满 100 个，已发现内容
        // 长时间不可见——距上次提交超 FLUSH_INTERVAL_MS 就地 flush（clockMs 可注入可测）
        var lastCommitAt = clockMs()
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val entries = try {
                currentCoroutineContext().ensureActive()   // 每目录循环间协作式取消
                // 2026-09-28：目录列举瞬时故障单次重试（300ms 退避）——简易测试服务器/
                // 劣质 NAT 下偶发「unexpected end of stream」类响应截断，重试即愈；
                // 仅对 IOException（网络/流类）重试，取消与程序错误照常上抛
                try {
                    lister(source, dir).getOrThrow()
                } catch (e: java.io.IOException) {
                    // 缺路径类（服务器已明确「没有此路径」）重试无意义：早退交给外层分类
                    // （降级跳过/整源 FAILED），不烧 300ms 退避与第二次服务器往返
                    if (SmbStatusText.missingPath(e.message.orEmpty())) throw e
                    android.util.Log.d("NvScan", "listDir transient failure, retry once: $dir (${e.message})")
                    kotlinx.coroutines.delay(300)
                    lister(source, dir).getOrThrow()
                }
            } catch (e: CancellationException) {
                // 取消也要保留未满批（增量语义）：终态写入需 NonCancellable，否则已取消协程内的挂起调用被拒
                withContext(NonCancellable) { if (batch.isNotEmpty()) commitBatch(source, batch) }
                throw e
            } catch (e: java.io.IOException) {
                // D1（2026-10-09 审计 P1）：重试耗尽仍失读——扫描根不可读维持整源 FAILED
                // （整卷源的根可读，命中此分支的是 /Android/data 等系统受限子目录）；非根目录
                // 跳过该子树继续扫。失读子树不进 seen → 本次对账/复验由 incomplete 闸整体跳过，
                // 防把子树存量行当"已消失"误删（S1-F8 同机理共用此闸）。
                // 2026-10-19 用户裁定：唯一例外是「路径不存在」类（STATUS_OBJECT_PATH/NAME_
                // NOT_FOUND、WebDAV 404）——NAS 侧把扫描根改名/移动（NAS 自管共享常态）
                // 不该瘫痪整个媒体库，降级为跳过该根继续扫（missingDirs 计数，DONE 带 note
                // 提示编辑源重选扫描目录）；权限/连接类根失败仍 FAILED，那是配置坏了必须显性报错
                val missingPath = SmbStatusText.missingPath(e.message.orEmpty())
                if (depth == 0 && !missingPath) throw e
                if (depth == 0) {
                    missingDirs++
                    if (firstMissingDir == null) firstMissingDir = dir
                } else {
                    skippedDirs++
                    if (firstSkippedDir == null) firstSkippedDir = dir
                }
                android.util.Log.w(
                    "NvScan",
                    "listDir failed after retry, skip subtree ($skippedDirs missing=$missingDirs): $dir (${e.message})",
                )
                continue
            }
            scannedDirs++
            // 2026-09-16 审计 P2-3：目录级批量预查存量行——原来每个视频文件各打一次
            // findByKey（数千文件 = 数千次 DB round-trip），现按目录一次 IN 查询建映射
            val existingByPath: Map<String, MediaItemEntity> = entries
                .filter { !it.isDir && !ScanRules.isHiddenEntry(it.name) &&
                    ScanRules.isVideoFile(it.name) && !ScanRules.isImageFile(it.name) &&
                    ScanRules.shouldIndexVideo(it.name, it.size) }
                .map { NvPaths.normalize(it.path) }
                .chunked(500)
                .flatMap { runCatching { mediaDao.findByPaths(source.id, it) }.getOrDefault(emptyList()) }
                .associateBy { it.path }
            for (e in entries) {
                if (ScanRules.isHiddenEntry(e.name)) continue
                if (e.isDir) {
                    val norm = NvPaths.normalize(e.path)
                    // 隐私剪枝（Task 10）：命中锁规则且未解锁 → 不入队不产缩略图（锁定内容不进媒体库）；
                    // 目录条目已在父列举中目击 → 记入 prunedDirs（确认存活，改名跟随不误判丢失）
                    if (privacy != null && !privacy.startAllowed(source.id, e.path)) {
                        prunedDirs.add(norm)
                    } else if (ScanRules.isUnderExcludedDir(e.path, excluded)) {
                        // 排除目录剪枝：不入队即整棵子树不可达；被跳过目录的存量行由对账清除
                        prunedDirs.add(norm)
                    } else if (depth + 1 < ScanRules.MAX_DEPTH) {
                        // 目录环剪枝：同一归一化目录只入队一次
                        if (visitedDirs.add(norm)) {
                            queue.add(e.path to depth + 1)
                        }
                    } else {
                        prunedDirs.add(norm)   // 深度截断边界：目录存在但不再下探
                    }
                } else if (ScanRules.isVideoFile(e.name)) {
                    // 入库跳过规则（仅媒体库索引，文件浏览器不受影响）：
                    // 1) 相机/监控命名碎片与 <50MB 小文件（ScanRules.shouldIndexVideo）；
                    // 2) 图片不再入媒体库（2026-09-15 用户需求：图片只在文件页浏览/查看器打开），
                    //    存量 IMAGE 行不进 seen → 扫描跑完随对账自动清除；
                    // 3) 非媒体文件（sam3_output/哈希名等「有视频扩展名但不是视频」）：读文件头
                    //    魔数校验（ScanRules.looksLikeMediaContainer），仅对新增/变化的候选读一次
                    //    （存量未变行免读，NAS 零额外负担）；读失败 fail-open 不误杀真媒体。
                    // 跳过项不进 seen → 成功对账时与"源中已消失"同路径清掉存量行，一次重扫即净。
                    if (ScanRules.isImageFile(e.name)) continue
                    if (!ScanRules.shouldIndexVideo(e.name, e.size)) continue
                    if (found >= ScanRules.MAX_ITEMS_PER_SOURCE) {
                        truncated = true   // 达上限：停止入库；截断跳过对账（未列到的文件不能当"已消失"删）
                        continue
                    }
                    val normalized = NvPaths.normalize(e.path)
                    val existing = existingByPath[normalized]
                    val unchanged = existing != null &&
                        existing.sizeBytes == e.size && existing.mtimeMs == e.lastModified
                    if (!unchanged) {
                        // 2026-09-28 偷跑流量修复（P1-1）：计量网络（蜂窝）下远程源跳过嗅探——
                        // 只列目录建索引。跳过 = 不读头，走既有「读头失败 fail-open」语义
                        // （宁可入库不误杀真媒体）；本地源不耗流量不受限
                        if (shouldDeferSniffForNetwork(source.type, allowNetworkSniff())) {
                            android.util.Log.d("NvScan", "sniff skipped (metered network): ${e.path}")
                        } else {
                            val header = headerReader(source, e.path)
                            if (header != null && !ScanRules.looksLikeMediaContainer(header, header.size)) continue
                        }
                    }
                    found++
                    seen.add(e.path)
                    batch.add(itemOf(source, e))
                    if (batch.size >= ScanRules.BATCH_SIZE) {
                        commitBatch(source, batch)
                        batch.clear()
                    }
                }
            }
            // 时间 flush（放 publish 前：横幅计数与库内已可见数据保持一致）
            if (batch.isNotEmpty() && clockMs() - lastCommitAt >= FLUSH_INTERVAL_MS) {
                commitBatch(source, batch)
                batch.clear()
                lastCommitAt = clockMs()
            }
            publish(source.id, ScanStatus.RUNNING, scannedDirs, dir, found)
        }
        if (batch.isNotEmpty()) commitBatch(source, batch)
        return WalkOutcome(
            seen, truncated, scannedDirs, found, skippedDirs, firstSkippedDir, missingDirs, firstMissingDir,
            queuedDirs = visitedDirs.toSet(),
            prunedDirs = prunedDirs.toSet(),
        )
    }

    /**
     * 条目 → 实体：size/mtime 取自 NvFileEntry（零额外 IO），addedAt=now（合并时旧行保护）。
     * 2026-09-15：仅视频条目会走到这里（图片已退出媒体库索引，见 walk）。
     */
    internal fun itemOf(source: SourceEntity, e: NvFileEntry): MediaItemEntity {
        val path = NvPaths.normalize(e.path)
        val baseName = NvPaths.fileNameOf(path)
        val nameKeys = MediaNames.keys(ScanRules.displayNameOf(baseName))
        val displayName = ScanRules.displayNameOf(baseName)
        return MediaItemEntity(
            sourceId = source.id,
            path = path,
            name = displayName,
            nameLower = displayName.lowercase(),
            pinyin = nameKeys.first,
            initials = nameKeys.second,
            sizeBytes = e.size,
            mtimeMs = e.lastModified,
            kind = ScanRules.kindOf(baseName, path),
            durationMs = null,
            thumbnailPath = null,
            thumbState = ThumbState.NONE.name,
            addedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 存量可疑行清理（2026-09-15 非媒体治理的存量面）：扫描跑完后，对「文件仍在（seen 中）、
     * kind=VIDEO、从未成功回填时长（durationMs IS NULL——真视频抽帧必回填，垃圾文件必然失败）」
     * 的行读文件头复验，非媒体容器 → 删行。上限 [PURGE_SUSPECT_LIMIT] 行/次 + [PURGE_INTERVAL_MS]
     * 间隔，读失败 fail-open 跳过不删。是「入库前嗅探」的补漏：老版本已入库的垃圾行一次重扫即净。
     */
    private suspend fun purgeSuspectRows(source: SourceEntity, seen: Collection<String>) {
        // 2026-09-28 偷跑流量修复（P1-1）：计量网络（蜂窝）下远程源整段跳过复验——每条
        // 可疑行都要嗅探读头；跳过与「读失败 fail-open」同语义保留全部行（零网络字节），
        // 下次 Wi-Fi 扫描再净
        if (shouldDeferSniffForNetwork(source.type, allowNetworkSniff())) {
            android.util.Log.d("NvScan", "purge suspects skipped (metered network): source=${source.id}")
            return
        }
        val seenSet = seen.toHashSet()
        val suspects = runCatching { mediaDao.suspectVideoRows(source.id) }.getOrDefault(emptyList())
        var purged = 0
        for (row in suspects) {
            currentCoroutineContext().ensureActive()
            if (purged >= PURGE_SUSPECT_LIMIT) break
            if (row.path !in seenSet) continue   // 不在 seen 的由对账负责，避免误删已消失文件之外的行
            val header = headerReader(source, row.path) ?: continue   // 读失败 fail-open
            if (ScanRules.looksLikeMediaContainer(header, header.size)) continue
            runCatching { mediaDao.deleteById(row.id) }
            purged++
            delay(PURGE_INTERVAL_MS)
        }
    }

    /** 500/批 upsert：先查旧行合并（保护 thumbnailPath/addedAt/thumbState 等，C12 §二.3） */
    private suspend fun commitBatch(source: SourceEntity, batch: List<MediaItemEntity>) {
        mediaDao.mergeScanBatch(source.id, batch)
    }

    /**
     * 改名跟随对账（隐私功能计划 Task 10，spec §3.3「外部改名/移动」）：完整扫描收尾把
     * 「确认存活目录集」（BFS 入队 + 剪枝边界目击，见 WalkOutcome.queuedDirs/prunedDirs）
     * 交给 [NvOrphanTracker]——规则路径仍在 → 不动；确认丢失（父目录本轮被列举）→ 候选池
     * 指纹唯一匹配 → migrate（keyHash/dirCipher/displayCipher/fingerprint 更新、state=ACTIVE）；
     * 零/多匹配或无指纹 → ORPHANED（fail-closed 整源临时锁）。
     *
     * jf: 规则不在此对账（JELLYFIN 指纹=子 item id 集哈希，Task 17 接管，否则会被当成
     * 文件系统目录误判丢失）。fingerprintOf 惰性列目录拍指纹（tracker 内部记忆化），
     * 无丢失规则时零额外 IO。任何异常只放弃本次跟随（W 级留痕），绝不打断扫描收尾。
     */
    private suspend fun followPrivacyRules(
        source: SourceEntity,
        outcome: WalkOutcome,
        privacy: com.novax.app.data.privacy.NvPrivacyAccess?,
    ) {
        privacy ?: return
        runCatching {
            val cipher = com.novax.app.data.privacy.NvPrivacyCrypto.keystoreCipher()
            val dao = privacy.db.privacyRuleDao()
            com.novax.app.data.privacy.NvOrphanTracker(
                rulesOf = { sid ->
                    privacy.index.views()
                        .filter {
                            it.sourceId == sid && it.dirPath.isNotBlank() &&
                                !it.dirPath.startsWith(com.novax.app.data.privacy.NvPrivacyIndex.JF_PREFIX)
                        }
                        .map { v ->
                            // 父目录本轮被实际列举过 → 丢失才可信；父被剪枝/截断 = 状态未知，不动
                            val parent = NvPaths.normalize(NvPaths.parentOf(v.dirPath))
                            com.novax.app.data.privacy.NvOrphanTracker.RuleSnap(
                                id = v.id,
                                dirPath = v.dirPath,
                                fingerprint = v.fingerprint,
                                parentListed = parent in outcome.queuedDirs,
                            )
                        }
                },
                hashOf = cipher::hmac,
            ).onScanFinished(
                sourceId = source.id,
                visitedDirs = outcome.queuedDirs + outcome.prunedDirs,
                fingerprintOf = { dir ->
                    val entries = lister(source, dir).getOrNull() ?: return@onScanFinished null
                    com.novax.app.data.privacy.NvFingerprint.of(entries.map { it.name to it.size }, cipher::hmac)
                },
                migrate = { ruleId, newDir, newHash, fp ->
                    val old = dao.all().firstOrNull { it.id == ruleId } ?: return@onScanFinished
                    val dirCipher = cipher.encrypt(newDir) ?: return@onScanFinished   // fail-closed：加密失败不动规则，下次扫描再试
                    // 展示名沿用原明文重新加密（displayCipher 空串 = 无展示名，保持口径）
                    val display = if (old.displayCipher.isEmpty()) "" else cipher.decrypt(old.displayCipher)
                    val displayCipher = if (display.isNullOrEmpty()) "" else cipher.encrypt(display) ?: old.displayCipher
                    dao.upsert(
                        old.copy(
                            keyHash = newHash,
                            dirCipher = dirCipher,
                            displayCipher = displayCipher,
                            fingerprint = fp,
                            state = com.novax.app.data.privacy.NvPrivacyIndex.STATE_ACTIVE,
                        ),
                    )
                    // 日志只留 id（spec §5.7：路径/目录名不出口）
                    android.util.Log.w("NvPrivacy", "rule migrated by scan follow: rule=$ruleId source=${source.id}")
                },
                orphan = { ruleId ->
                    dao.all().firstOrNull { it.id == ruleId }?.let { old ->
                        dao.upsert(old.copy(state = com.novax.app.data.privacy.NvPrivacyIndex.STATE_ORPHANED))
                        android.util.Log.w("NvPrivacy", "rule orphaned by scan follow: rule=$ruleId source=${source.id}")
                    }
                },
            )
        }.onFailure {
            android.util.Log.w("NvPrivacy", "scan follow skipped: source=${source.id} (${it.javaClass.simpleName})")
        }
    }

    /**
     * 对账清扫（C12 §二.4）：删除本次扫描未见的文件行——"源中已消失" + "命中入库跳过规则
     * （[ScanRules.shouldIndexVideo]）本次不再入库的存量行"（被跳过项不进 seenPaths，此处一并删除）。
     * 路径数 ≤900 时直接 deleteNotIn（SQL 变量上限内）；更大集合走内存差集 + 分批删除。
     * 被删行的缩略图文件随后由 ThumbCache.enforce 的无主文件清理回收（无行引用即删）。
     */
    private suspend fun reconcile(source: SourceEntity, seenPaths: List<String>) {
        if (seenPaths.isEmpty()) {
            mediaDao.deleteBySource(source.id)
            return
        }
        if (seenPaths.size <= SQL_VAR_LIMIT) {
            mediaDao.deleteNotIn(source.id, seenPaths)
            return
        }
        val seen = seenPaths.toHashSet()
        val existing = mediaDao.allPaths(source.id)
        val gone = existing.filterNot { it in seen }
        gone.chunked(SQL_VAR_LIMIT).forEach { chunk ->
            mediaDao.deleteByPaths(source.id, chunk)
        }
    }

    /**
     * 进度发布（2026-09-15 节流修复）：RUNNING 进度 ≥300ms 才发一次（终态 DONE/CANCELLED/FAILED
     * 恒直通）。旧行为每目录一发 → 库页 envFlow 无 debounce 直接重算 1245 行 + 网格全量重组 +
     * 字母索引图主线程重建，扫描期主线程持续满载（发热源之一）。
     * 原子性顺手修复：_progress 改 update{}（旧「读-改-写」在多源并行扫描可丢更新）。
     */
    private fun publish(sourceId: Long, status: ScanStatus, scannedDirs: Int, currentDir: String, foundItems: Int) {
        if (status == ScanStatus.RUNNING) {
            val now = clockMs()
            val last = lastPublishAt[sourceId] ?: 0L
            if (now - last < PROGRESS_MIN_INTERVAL_MS) return
            lastPublishAt[sourceId] = now
        } else {
            lastPublishAt.remove(sourceId)
        }
        _progress.update { cur -> cur + (sourceId to ScanProgress(sourceId, status, scannedDirs, currentDir, foundItems)) }
    }

    companion object {
        /** SQLite 默认主机变量上限 999：留余量给 (sourceId, path IN) 的其他绑定参数 */
        internal const val SQL_VAR_LIMIT = 900

        /** RUNNING 进度最小发布间隔（终态不节流）：库页重算/重组的频率上限 ≈3 次/秒 */
        internal const val PROGRESS_MIN_INTERVAL_MS = 300L

        /** 非媒体嗅探读取的文件头长度（覆盖 MPEG-TS 0x47@0/188/376 三点同步校验） */
        internal const val HEADER_SNIFF_BYTES = 512

        /**
         * 嗅探 SMB 块上限（2026-09-21 审计 P1：见 headerReader 注释——漏传时每文件
         * 2×4MB 读放大，实测首扫 5000 文件 ≈ 40GB 无谓 NAS 读取）
         */
        internal const val HEADER_SNIFF_BLOCK_BYTES = 64 * 1024

        /**
         * 嗅探计量网络闸门（纯函数可单测，2026-09-28 P1-1）：远程源在受限网络上推迟
         * headerReader 嗅探（仅列目录建索引）；本地文件不耗流量不受限。
         * 与 [com.novax.app.data.thumb.Thumbnailer.shouldDeferForNetwork] 同构（同一语义线）。
         */
        internal fun shouldDeferSniffForNetwork(sourceType: SourceType, sniffAllowed: Boolean): Boolean =
            sourceType != SourceType.LOCAL && !sniffAllowed

        /** 目录稀疏时的批提交间隔（clockMs 计时；目录结束仍未满 BATCH_SIZE 就地落库让用户看见） */
        internal const val FLUSH_INTERVAL_MS = 3000L

        /** 扫描后存量可疑行清理的单次上限（防一次扫描对 NAS 发起过多小读；剩余下次扫描继续） */
        internal const val PURGE_SUSPECT_LIMIT = 300

        /** 存量清理逐条间隔（毫秒）：小读也讲究温柔，别把 NAS 盘打热 */
        internal const val PURGE_INTERVAL_MS = 20L

        // 扫描横幅第 1 行文案原在此共享拼串（bannerTitle）——i18n Task 8 评审移交②抽取为
        // 资源 data_scan_banner_title，改由 UI 层直接解析（LibraryScreen stringResource /
        // HomeViewModel appContext.getString），纯函数拼串随之删除。
    }

    /** 各源上次 RUNNING 进度发布时刻（epoch ms；终态移除防泄漏） */
    private val lastPublishAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 时钟可注入（单测用假时钟；300ms 节流用 epoch ms 足够） */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }
}
