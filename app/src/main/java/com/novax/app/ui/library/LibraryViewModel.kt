package com.novax.app.ui.library

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novax.app.R
import com.novax.app.data.NvGraph
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaItemWithWatch
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.ScanStateEntity
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.privacy.NvFavoriteDirect
import com.novax.app.data.privacy.NvPrivacyListFilter
import com.novax.app.player.NvHistoryDirect
import com.novax.app.data.db.WatchKind
import com.novax.app.data.scanner.MediaScanner
import com.novax.app.data.scanner.NvScannerHolder
import com.novax.app.data.scanner.MediaNames
import com.novax.app.data.scanner.ScanProgress
import com.novax.app.data.thumb.NvThumbHolder
import com.novax.app.ui.home.NvPlaybackLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import com.novax.app.KEY_LIB_SORT
import com.novax.app.novaxUiPrefs

// ── 排序 / 筛选枚举（C12 §4.1 ⚠ 修订 C3）──

/** 排序（C12 §4.1；2026-10-16 用户裁定默认改「标题 A→Z」）：标题（默认）/ 添加时间（新→旧，保留可选档）/ 大小（大→小）；年份、评分二期刮削回填后启用 */
enum class LibrarySort(@StringRes val labelRes: Int, val sql: String) {
    ADDED(R.string.lib_sort_added, "ADDED"),
    TITLE(R.string.lib_sort_title, "NAME"),
    SIZE(R.string.lib_sort_size, "SIZE"),
}

/**
 * 2026-09-15 D4 剧集聚合组（库页「剧集」分段）：同文件夹 ≥2 个视频 → 一部剧
 * （文件夹名 = 剧名，01/02 命名自然归堆）。点击组卡 = selectFolder(该目录) 直达全集视频墙。
 * 2026-09-20：≥[MediaType.SERIES_MIN_EPISODES] 的门槛在本类 seriesGroups 聚合处过滤
 * （旧版注释声称 SQL 同口径过滤，实际 SERIES 分支是裸直通，从未强制）。
 */
data class LibrarySeriesGroup(
    val sourceId: Long,
    val dir: String,
    val name: String,
    val episodes: Int,
    val watched: Int,          // 已看完集数（wDur>0 且 wPos≥95%）
    val thumbnailPath: String?,
    // BUG-C-R7-03：组级聚合排序键（组卡按排序菜单跟随列表口径）
    val groupAddedAtMs: Long = 0L,
    val groupTotalBytes: Long = 0L,
)

/**
 * 分段 Tab（2026-09-15 用户分类模型重设计；2026-09-20 时长口径修正）：全部 / 长视频 / 短视频 / 剧集。
 * - 长视频：时长 ≥ [LONG_MIN_MS]（40 分钟，与 kind 升级阈值一致）；
 * - 短视频：时长 < 40 分钟或未知（未抽帧的暂归此段，抽帧后自动归位）；
 * - 剧集：同文件夹视频聚合视图，VM 层 seriesGroups 要求 ≥[SERIES_MIN_EPISODES] 集才算一部剧；
 * 长/短纯按时长、互斥且穷尽（DAO 已不再排除剧集文件夹），剧集条目可同时出现在对应时长分段；
 * kind 列保留仅供搜索分桶/升级逻辑使用，不参与分段。
 */
enum class MediaType(@StringRes val labelRes: Int, val sql: String) {
    ALL(R.string.lib_type_all, "ALL"),
    LONG(R.string.lib_type_long, "LONG"),
    SHORT(R.string.lib_type_short, "SHORT"),
    SERIES(R.string.lib_type_series, "SERIES");

    companion object {
        /** 长视频时长下限（毫秒）：40 分钟，与 Thumbnailer.MOVIE_MIN_MS / DAO SQL 字面量一致 */
        const val LONG_MIN_MS = 40L * 60 * 1000

        /** 剧集聚合门槛：同文件夹视频数 ≥ 2 才算一部剧（VM 层 seriesGroups 聚合处过滤） */
        const val SERIES_MIN_EPISODES = 2
    }
}

/** 已看/未看（接 watch_history，SQL :watched 参数：-1=不过滤 0=未看 1=已看，C12 §1.6） */
enum class WatchedFilter(@StringRes val labelRes: Int, val sql: Int) {
    NONE(R.string.lib_watched_state, -1), WATCHED(R.string.lib_watched, 1), UNWATCHED(R.string.lib_unwatched, 0)
}

enum class ViewMode { Grid, List }

/** 全屏阶段（C12 §八.1 状态清单的库页子集） */
enum class Phase { Loading, Ready, NoSource, NotIndexed, ScanningEmpty, ScanDoneEmpty, NoMatch }

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context = app.applicationContext
    private val dao = NvGraph.mediaItemDao(context)
    private val scanDao = NvGraph.scanStateDao(context)
    private val sourceDao = NvGraph.sourceDao(context)
    private val favoriteDao = NvGraph.favoriteDao(context)
    private val historyDao = NvGraph.historyDao(context)
    private val db = MediaDb.get(context)
    // 隐私分类门面（Task 9）：历史直写点一律经 NvHistoryDirect，不旁路 historyDao
    private val privacy = com.novax.app.data.privacy.NvPrivacyAccess.get(context)
    // 密文行解密（Task 14 读端）：与 Access 内部同一进程级单例实例
    private val cipher = com.novax.app.data.privacy.NvPrivacyCrypto.keystoreCipher()

    /** 拼音排序键缓存（性能修复）：每条目只算一次排序键，items/folders 共用；纯类可单测 */
    private val sortKeys = LibrarySortKeyCache()

    private val scanner: MediaScanner get() = NvScannerHolder.get(context)

    // ── 筛选 / 排序 / 弹层（MutableStateFlow；observeLibrary 经 flatMapLatest 组合，C12 §九C）──
    private val viewModeFlow = MutableStateFlow(ViewMode.Grid)
    private val mediaTypeFlow = MutableStateFlow(MediaType.ALL)
    private val watchedFlow = MutableStateFlow(WatchedFilter.NONE)
    // 2026-10-16 用户裁定：默认排序「添加时间」→「标题 A→Z」（同轮全局序 A→Z→0-9→# 落地）。
    // BUG-C-R7-06 三处同口径机制保留、只换基准：sortFlow 初值、UiState 缺省（下方 LibraryUiState.sort）
    // 与菜单角标（LibraryScreen 排序钮）都以 TITLE 为基准——默认态角标不亮、手选其他档（含添加时间）即亮；
    // 持久化选择仍优先（init 恢复）：之前手选过「添加时间」的用户重启不受影响
    private val sortFlow = MutableStateFlow(LibrarySort.TITLE)
    private val sourceFilterFlow = MutableStateFlow<Long?>(null)
    private val folderFilterFlow = MutableStateFlow<LibraryFolder?>(null)
    private val sortSheetVisible = MutableStateFlow(false)
    private val sourceSheetVisible = MutableStateFlow(false)
    private val itemSheet = MutableStateFlow<MediaItemWithWatch?>(null)
    private val notedScanDismissedAt = MutableStateFlow(0L)
    private val failedScanDismissedAt = MutableStateFlow(0L)

    /** 源列表内存缓存：播放地址 / 收藏键的 LOCAL 分支判定用（同步读） */
    @Volatile
    private var cachedSources: List<SourceEntity> = emptyList()

    init {
        viewModelScope.launch {
            // BUG-C-R6-16：排序选择持久化（启动恢复上次手选；2026-10-16 默认基准 ADDED→TITLE，
            // 本机制不变——手选过「添加时间」的用户重启仍是添加时间）
            runCatching {
                context.novaxUiPrefs.data
                    .map { it[KEY_LIB_SORT] }
                    .first()
                    ?.let { saved -> LibrarySort.entries.firstOrNull { it.name == saved }?.let { sortFlow.value = it } }
            }
            sourceDao.observeAll().collect { cachedSources = it }
        }
    }

    private data class Filters(
        val mediaType: MediaType,
        val watched: WatchedFilter,
        val sort: LibrarySort,
        val sourceId: Long?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val itemsFlow = combine(mediaTypeFlow, watchedFlow, sortFlow, sourceFilterFlow, ::Filters)
        .flatMapLatest { f ->
            // media_items 唯一数据源（C12 §4.1）：筛选/排序全在 SQL；已看子查询接 watch_history
            dao.observeLibrary(f.sourceId, f.mediaType.sql, f.watched.sql, f.sort.sql)
        }
        // 性能修复（发射合批）：抽帧逐张 updateThumb 会让 Room 全表重发 2846 行 → 逐张触发整页重算；
        // 300ms 防抖把连续写库合并为至多每 300ms 一次真实重算（在 Default 段执行，见 uiState.flowOn）
        .debounce(ITEMS_DEBOUNCE_MS)

    private data class Env(
        val sources: List<SourceEntity>,
        val scans: List<ScanStateEntity>,
        val progress: Map<Long, ScanProgress>,
    )

    private val envFlow = combine(
        sourceDao.observeAll(),
        scanDao.observeAll(),
        scanner.progress,
    ) { sources, scans, progress -> Env(sources, scans, progress) }

    private data class Sheets(
        val sortSheet: Boolean,
        val sourceSheet: Boolean,
        val itemSheet: MediaItemWithWatch?,
        val notedDismissedAt: Long,
        val failedDismissedAt: Long,
    )

    private data class Base(val viewMode: ViewMode, val filters: Filters, val folder: LibraryFolder?)

    /** 收藏键（与 FilesViewModel.mediaKeyOf 同构）：LOCAL 源 = (null, 本地绝对路径)。
     *  隐私口径（Task 14）：密文收藏行（hash 键）先解密还原明文键——解锁后库页星标态不漏判；
     *  解不开的密文行丢弃（fail-closed，星标宁缺不误显） */
    private val favoriteKeysFlow = favoriteDao.observeAll()
        .map { list ->
            list.mapNotNull { NvPrivacyListFilter.decryptDisplay(cipher, it) }
                .map { it.sourceId to it.path }.toSet()
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        itemsFlow,
        envFlow,
        favoriteKeysFlow,
        combine(viewModeFlow, combine(mediaTypeFlow, watchedFlow, sortFlow, sourceFilterFlow, ::Filters), folderFilterFlow, ::Base),
        combine(
            sortSheetVisible, sourceSheetVisible, itemSheet, notedScanDismissedAt, failedScanDismissedAt,
        ) { a, b, c, n, f -> Sheets(a, b, c, n, f) },
    ) { items, env, favoriteKeys, base, sheets ->
        cachedSources = env.sources
        // 隐私口径（Task 14 查询层过滤）：锁定行滤掉（正常锁定内容已随 purgeScope 出库，
        // 此为解锁→重锁窗口/规则漂移的兜底网）；sourceId==null 恒保留（T4 豁免口径）
        val privacyVisible = NvPrivacyListFilter.filter(items, privacy) { it.item.sourceId to it.item.path }
        val visible = privacyVisible.filter { base.folder?.contains(it.item.sourceId, it.item.path) ?: true }
        // 性能修复（拼音键缓存）：标题排序不再走 MediaNames.comparator 的"每次比较现转拼音+现切自然序 token"，
        // 改为每条目一次预计算键（LibrarySortKeyCache，scrapedTitle/name 或 mtime 变更自动失效），比较阶段零转换
        val titleSorted = if (base.filters.sort == LibrarySort.TITLE) visible.sortedWith(
            compareBy<MediaItemWithWatch> { sortKeys.itemKey(it.item.id, it.item.scrapedTitle ?: it.item.name, it.item.mtimeMs) }
                .thenBy { it.item.sourceId }.thenBy { it.item.path }
        ) else visible
        // 文件夹清单同走过滤后集合：锁定目录名不得出现在文件夹筛选弹层（露馅面与列表同口径）
        val folders = privacyVisible.map { LibraryFolder(it.item.sourceId, it.item.path.substringBeforeLast('/', "").ifEmpty { "/" }) }
            .distinct().sortedWith(compareBy<LibraryFolder> { sortKeys.folderKey(it.sourceId, it.path) }.thenBy { it.sourceId })
        LibraryUiState(
            loading = false,   // 首个真实发射即出骨架（骨架不复活）
            items = titleSorted,
            // 2026-09-15 D4：剧集 Tab 聚合组（仅 SERIES 分段计算，其他分段空表零开销）
            // 2026-09-20：≥2 集门槛在此过滤（SQL SERIES 分支是直通；单视频文件夹不成「1 集剧」）
            seriesGroups = if (base.filters.mediaType == MediaType.SERIES) {
                titleSorted
                    .groupBy { it.item.sourceId to it.item.path.substringBeforeLast('/', "") }
                    .filter { it.value.size >= MediaType.SERIES_MIN_EPISODES }
                    .map { (key, rows) ->
                        LibrarySeriesGroup(
                            sourceId = key.first,
                            dir = key.second,
                            // 2026-09-15：刮削命中时剧名/海报优先用 TMDB 结果（文件夹名/抽帧图为回退）
                            name = rows.firstOrNull { !it.item.scrapedTitle.isNullOrBlank() }?.item?.scrapedTitle
                                ?: key.second.substringAfterLast('/').ifBlank {
                                    key.second.ifBlank { context.getString(R.string.lib_series_fallback) }
                                },
                            episodes = rows.size,
                            watched = rows.count { LibraryViewModel.isWatched(it) },
                            thumbnailPath = rows.firstOrNull { !it.item.scrapePosterPath.isNullOrBlank() }?.item?.scrapePosterPath
                                ?: rows.firstOrNull { it.item.thumbnailPath != null }?.item?.thumbnailPath,
                            // BUG-C-R7-03：组级聚合键（组内最新添加/总大小）
                            groupAddedAtMs = rows.maxOf { it.item.addedAt },
                            groupTotalBytes = rows.sumOf { it.item.sizeBytes.takeIf { s -> s > 0 } ?: 0L },
                        )
                    }
                    // BUG-C-R7-03：组卡排序跟随排序菜单（与列表同口径）——
                    // TITLE（及兜底）=剧名 A→Z；ADDED=组内最新添加时间 新→旧；SIZE=组内总大小 大→小
                    .sortedWith(
                        when (base.filters.sort) {
                            // 与 DAO 列表口径一致：ADDED=addedAt DESC（新→旧）、SIZE=sizeBytes DESC（大→小）
                            LibrarySort.ADDED -> compareByDescending<LibrarySeriesGroup> { it.groupAddedAtMs }
                            LibrarySort.SIZE -> compareByDescending<LibrarySeriesGroup> { it.groupTotalBytes }
                            else -> compareBy<LibrarySeriesGroup, String>(MediaNames.comparator) { it.name }
                        },
                    )
            } else {
                emptyList()
            },
            folder = base.folder,
            folders = folders,
            sources = env.sources,
            scans = env.scans,
            progress = env.progress,
            favoriteKeys = favoriteKeys,
            viewMode = base.viewMode,
            mediaType = base.filters.mediaType,
            watchedFilter = base.filters.watched,
            sort = base.filters.sort,
            sourceId = base.filters.sourceId,
            sortSheetVisible = sheets.sortSheet,
            sourceSheetVisible = sheets.sourceSheet,
            itemSheet = sheets.itemSheet,
            notedScanDismissedAt = sheets.notedDismissedAt,
            failedScanDismissedAt = sheets.failedDismissedAt,
        )
    }
        // 性能修复（重活下主线程之外）：combine 变换（filter/sort/folders 映射/pinyin 转换）原先随
        // viewModelScope(Main) 在主线程执行，2846 行 + 每秒重发 = 主线程持续 R；flowOn 把上游整段
        // （含 debounce 定时与排序/映射）搬到 Default，主线程只收最终 UiState
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // ── 视图 / 分段 / 筛选 ──
    fun toggleViewMode() {
        viewModeFlow.value = if (viewModeFlow.value == ViewMode.Grid) ViewMode.List else ViewMode.Grid
    }

    fun selectMediaType(type: MediaType) {
        mediaTypeFlow.value = type
    }

    /** 已看/未看互斥 Chip：再点取消（C12 §4.1 交互沿用 C3） */
    fun toggleWatched(f: WatchedFilter) {
        watchedFlow.value = if (watchedFlow.value == f) WatchedFilter.NONE else f
    }

    fun selectSource(id: Long?) {
        folderFilterFlow.value = null
        sourceFilterFlow.value = id
        sourceSheetVisible.value = false
    }

    fun clearFilters() {
        folderFilterFlow.value = null
        mediaTypeFlow.value = MediaType.ALL
        watchedFlow.value = WatchedFilter.NONE
        sourceFilterFlow.value = null
    }

    // ── 弹层 ──
    fun selectFolder(folder: LibraryFolder?) { folderFilterFlow.value = folder }
    fun showSortSheet() { sortSheetVisible.value = true }
    fun dismissSortSheet() { sortSheetVisible.value = false }

    fun selectSort(sort: LibrarySort) {
        sortFlow.value = sort
        sortSheetVisible.value = false
        // BUG-C-R6-16：同步持久化（重启后 init 恢复，字母索引不再随默认排序丢失）
        viewModelScope.launch {
            runCatching { context.novaxUiPrefs.edit { it[KEY_LIB_SORT] = sort.name } }
        }
    }

    fun showSourceSheet() { sourceSheetVisible.value = true }
    fun dismissSourceSheet() { sourceSheetVisible.value = false }

    fun showItemSheet(item: MediaItemWithWatch) { itemSheet.value = item }
    fun dismissItemSheet() { itemSheet.value = null }

    // ── 扫描结果横幅关闭（2026-10-26：会话级，见 LibraryUiState.notedScanDismissedAt 注释）──
    fun dismissNotedScan() {
        uiState.value.notedScan?.let { notedScanDismissedAt.value = it.lastScanAt }
    }

    fun dismissFailedScan() {
        uiState.value.failedScan?.let { failedScanDismissedAt.value = it.lastScanAt }
    }

    // ── 重新扫描（C12 §二.1 T2：顶栏 Sync 图标 → 全部源串行重扫；再点取消）──
    fun rescan() {
        viewModelScope.launch {
            val sources = sourceDao.getAll()
            if (sources.isNotEmpty()) scanner.scanAll(sources)
        }
    }

    fun cancelScan() = scanner.cancelAll()

    /** 正在扫描的进度行（库页尾插 NvScanStatusRow 数据源） */
    fun runningProgress(state: LibraryUiState): List<ScanProgress> =
        state.progress.values.filter { it.status == ScanStatus.RUNNING }

    // ── 抽帧触发（C12 §3.8）：requestThumb 内 claimThumb CAS，重复调用安全去重 ──
    fun needThumb(mediaItemId: Long) {
        // 2026-09-21 审计 P3：原 4 条 Log.w 每卡全保留（快速滚动每秒数百行）→ Log.d（R8 剥除）
        viewModelScope.launch {
            runCatching { NvThumbHolder.get(context).requestThumb(mediaItemId) }
                .onFailure {
                    android.util.Log.w("NvThumb", "needThumb failed id=$mediaItemId", it)
                }
        }
    }

    // ── 播放（C12 §4.4/§4.5：一期文件级库直达播放，续播由 PlayerActivity 按历史键自理）──
    fun playUrlOf(item: MediaItemEntity): String {
        val source = cachedSources.firstOrNull { it.id == item.sourceId }
        return if (source?.type == SourceType.LOCAL) {
            NvPlaybackLauncher.localPath(source.shareOrPath, item.path)
        } else {
            NvPlaybackLauncher.novaxUrl(item.sourceId, item.path)
        }
    }

    // ── 长按菜单（C12 §4.4）──

    /** 加入/取消收藏（favorites 键与文件页同构：LOCAL=(null, 绝对路径)，其余=(sourceId, 源内路径)）。
     *  隐私口径（Task 9 修复轮）：经 NvFavoriteDirect——双键查重，锁命中内容密文行落盘 */
    fun toggleFavorite(item: MediaItemWithWatch) {
        viewModelScope.launch {
            runCatching {
                val (keySourceId, keyPath) = mediaKeyOf(item.item)
                NvFavoriteDirect.toggle(
                    db,
                    privacy,
                    FavoriteEntity(
                        sourceId = keySourceId,
                        path = keyPath,
                        title = item.item.scrapedTitle ?: item.item.name,
                        kind = WatchKind.MOVIE.name,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
            itemSheet.value = null
        }
    }

    /** 标记为已看（pos=duration）/ 取消已看（删历史行，C12 §4.4） */
    fun toggleWatchedItem(item: MediaItemWithWatch) {
        viewModelScope.launch {
            runCatching {
                val e = item.item
                // 2026-10-18 发布终审 BUG-4：历史键经 mediaKeyOf 映射——LOCAL 源播放器写
                // (null, 绝对路径)，此前库页用裸键 (本地源id, 相对路径)：标记行与真实播放行
                // 永不相遇（已看状态失联），且与播放历史产生两行不同键的重复数据。
                // 与收藏侧 favoriteKeyOf 修复（BUG-C-R7-04）同口径收口
                val (keySourceId, keyPath) = mediaKeyOf(e)
                val watched = isWatched(item)
                if (watched) {
                    // 隐私口径（Task 9）：hash+plain 双口径删——密文行在 hash 键上
                    NvHistoryDirect.markUnwatched(db, privacy, keySourceId, keyPath)
                } else {
                    val duration = e.durationMs ?: item.wDur?.takeIf { it > 0 } ?: 60_000L
                    // 隐私口径（Task 9）：经 NvHistoryDirect——无痕不写、锁定内容密文行落盘
                    NvHistoryDirect.markWatched(
                        db,
                        privacy,
                        WatchHistoryEntity(
                            sourceId = keySourceId,
                            sourceType = cachedSources.firstOrNull { it.id == e.sourceId }?.type?.name,
                            path = keyPath,
                            title = e.scrapedTitle ?: e.name,
                            kind = watchKindOf(e),
                            positionMs = duration,
                            durationMs = duration,
                            watchedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            itemSheet.value = null
        }
    }

    /** 重新抽帧（C12 §4.4）：resetThumb 清路径+复位 NONE → requestThumb 重新 CAS 入队 */
    fun reextractThumb(item: MediaItemWithWatch) {
        viewModelScope.launch {
            runCatching {
                dao.resetThumb(item.item.id)
                NvThumbHolder.get(context).requestThumb(item.item.id)
            }
            itemSheet.value = null
        }
    }

    // ── 工具 ──

    /** 收藏与历史的统一键（2026-10-18 随 BUG-4 由 favoriteKeyOf 更名扩用）：LOCAL=null+
     *  绝对路径（与 PlayerActivity.buildHistorySnapshot / FilesViewModel.mediaKeyOf 同构）；
     *  其余=sourceId+源内路径 */
    internal fun mediaKeyOf(e: MediaItemEntity): Pair<Long?, String> {
        val src = cachedSources.firstOrNull { it.id == e.sourceId }
        return if (src?.type == SourceType.LOCAL) {
            null to NvPlaybackLauncher.localPath(src.shareOrPath, e.path)
        } else {
            e.sourceId to e.path
        }
    }

    /**
     * BUG-E-T2-01（换集链路-B）：媒体库起播按同目录音视频构建播放队列（异步查库后回调）。
     * 原实现只传单 url：播放器上/下集按钮置灰、通知栏无 ▷|。队列顺序与库页剧集聚合一致
     * （videosInDir 名称排序）。
     */
    fun playWithSiblingQueue(
        row: MediaItemWithWatch,
        onReady: (String, List<Pair<String, String>>) -> Unit,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val item = row.item
            val url = playUrlOf(item)
            val dir = item.path.substringBeforeLast('/', "")
            val siblings = if (dir.isBlank()) {
                listOf(item)
            } else {
                runCatching {
                    dao.videosInDir(item.sourceId, com.novax.app.data.db.escapeLikePattern(dir))
                }.getOrDefault(emptyList())
            }.ifEmpty { listOf(item) }
            val queue = siblings.map { playUrlOf(it) to (it.scrapedTitle ?: it.name) }
            onReady(url, queue)
        }
    }

    private fun watchKindOf(e: MediaItemEntity): String = when (e.kind) {
        MediaKind.EPISODE -> WatchKind.EPISODE.name
        else -> WatchKind.MOVIE.name
    }

    companion object {
        /** 库列表流防抖（性能修复）：抽帧批量写库合并重发，至多每 300ms 全表重算一次 */
        internal const val ITEMS_DEBOUNCE_MS = 300L

        /** 完看判定（与 observeLibrary SQL 同式：pos ≥ 95% 且 duration>0） */
        fun isWatched(row: MediaItemWithWatch): Boolean =
            row.wDur != null && row.wDur > 0 && row.wPos != null && row.wPos >= row.wDur * 0.95

        /** 副标题（C12 §4.3）："{分辨率} · {时长}"，未知段隐藏 */
        fun subtitleOf(e: MediaItemEntity): String? {
            val parts = listOfNotNull(resolutionLabel(e.width), durationLabel(e.durationMs))
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /** w≥3840→4K / ≥1920→1080p / ≥1280→720p，未知→隐藏段（C12 §4.3） */
        private fun resolutionLabel(w: Int?): String? = when {
            w == null || w <= 0 -> null
            w >= 3840 -> "4K"
            w >= 1920 -> "1080p"
            w >= 1280 -> "720p"
            else -> "${w}p"
        }

        private fun durationLabel(ms: Long?): String? {
            if (ms == null || ms <= 0) return null
            val totalMin = ms / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            return when {
                h > 0 -> "${h}h${m}m"
                totalMin > 0 -> "${totalMin}m"
                else -> "${ms / 1000}s"
            }
        }
    }
}

data class LibraryUiState(
    val folder: LibraryFolder? = null,
    val seriesGroups: List<LibrarySeriesGroup> = emptyList(),   // 2026-09-15 D4：剧集 Tab 聚合组
    val folders: List<LibraryFolder> = emptyList(),
    val loading: Boolean = true,   // 仅初始为 true；首个 combine 发射置 false（S1 骨架只在首帧出现）
    val items: List<MediaItemWithWatch> = emptyList(),
    val sources: List<SourceEntity> = emptyList(),
    val scans: List<ScanStateEntity> = emptyList(),
    val progress: Map<Long, ScanProgress> = emptyMap(),
    val favoriteKeys: Set<Pair<Long?, String>> = emptySet(),
    val viewMode: ViewMode = ViewMode.Grid,
    val mediaType: MediaType = MediaType.ALL,
    val watchedFilter: WatchedFilter = WatchedFilter.NONE,
    // 2026-10-16 默认基准 ADDED→TITLE（BUG-C-R7-06 三处同口径之一）：与 sortFlow 初值、菜单角标基准一致
    val sort: LibrarySort = LibrarySort.TITLE,
    val sourceId: Long? = null,
    val sortSheetVisible: Boolean = false,
    val sourceSheetVisible: Boolean = false,
    val itemSheet: MediaItemWithWatch? = null,
    // 2026-10-26 用户反馈「扫描提示横幅关不掉」：会话级关闭基准—— dismissing 记录被关横幅的
    // lastScanAt，仅压制「这次及更早」的提示；之后新扫描产生的新失败/新提示（lastScanAt 更大）
    // 会重新出现。不落库不清 DB 诊断记录，重启 App 后未消的问题仍会提醒。
    val notedScanDismissedAt: Long = 0L,
    val failedScanDismissedAt: Long = 0L,
) {
    /** 任一源扫描中（实时进度为准；scan_state 表供冷启动完成态判定） */
    val scanning: Boolean get() = progress.values.any { it.status == ScanStatus.RUNNING }

    /** 最近一次扫描失败（S7 顶部横幅文案数据；已被用户关闭的旧失败不再回场） */
    val failedScan: ScanStateEntity?
        get() = scans.firstOrNull { it.status == ScanStatus.FAILED && it.lastScanAt > failedScanDismissedAt }

    /** 最近一次「完成但带提示」的扫描（2026-10-19：失读/失效扫描根跳过降级为 DONE+note 后的可见出口） */
    val notedScan: ScanStateEntity?
        get() = scans.firstOrNull { it.status == ScanStatus.DONE && !it.error.isNullOrBlank() && it.lastScanAt > notedScanDismissedAt }

    val hasFilters: Boolean
        get() = watchedFilter != WatchedFilter.NONE || mediaType != MediaType.ALL || sourceId != null || folder != null

    /** 筛选组合命中 0（S12） */
    val noMatch: Boolean get() = items.isEmpty() && hasFilters

    /**
     * 字母侧条 initial→index 映射（本任务新增）：仅"标题 A→Z"排序时有值。
     * 从当前排序后的 items 构建（拼音首字母，未知归 #），供侧条松手 scrollToItem 直跳。
     */
    val initialIndexMap: Map<String, Int> by lazy {
        if (sort == LibrarySort.TITLE) {
            buildInitialIndexMap(items.map { it.item.scrapedTitle ?: it.item.name })
        } else {
            emptyMap()
        }
    }

    /**
     * 剧集组卡网格的字母索引（BUG-C-R6-16：组卡按剧名 A→Z 排列却一直没有侧条）。
     * 组名首字母 → 组在网格中的下标；仅"标题 A→Z"排序时有值。
     */
    val seriesInitialIndexMap: Map<String, Int> by lazy {
        if (sort == LibrarySort.TITLE) {
            buildInitialIndexMap(seriesGroups.map { it.name })
        } else {
            emptyMap()
        }
    }
}
