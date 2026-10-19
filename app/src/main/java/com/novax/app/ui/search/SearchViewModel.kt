package com.novax.app.ui.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.ThumbState
import com.novax.app.data.scanner.NvScannerHolder
import com.novax.app.data.thumb.NvThumbHolder
import com.novax.app.ui.home.NvPlaybackLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

// 2026-09-29 建库写入已打通（pinyin4j，非 TinyPinyin）：新行由扫描入库 MediaScanner.itemOf 经
// MediaNames.keys 写 pinyin/initials；一期存量空键行由 PinyinBackfill 在本类 init 触发一次性回填。
// 键格式跟随 DAO 查询约定：pinyin 全拼小写无分隔（LIKE '%q%'），initials 首字母大写（LIKE 'Q%' 前缀）。

/**
 * 剧集组（C12 §5.2）：EPISODE 按 (sourceId, 父目录) 查询期聚合，SERIES 不落行。
 * [first] 为组内首集（一期文件级库无聚合详情，点击直达播放）。
 */
data class SeriesGroup(
    val sourceId: Long,
    val dir: String,
    val name: String,
    val episodes: Int,
    val thumbnailPath: String?,
    val first: MediaItemEntity,
)

data class SearchResults(
    val movies: List<MediaItemEntity> = emptyList(),
    val series: List<SeriesGroup> = emptyList(),
    val videos: List<MediaItemEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty() && videos.isEmpty()
}

/**
 * C12 搜索（真实化版）：MediaItemDao.search 唯一数据源（一次查询按 kind 分桶，
 * 对齐 C12 审核建议 5）；150ms 防抖 / IME / 历史 / 过滤 Chip 交互保留 C7 现状。
 * 命中点击直达播放（novax:// 或本地绝对路径，与 NvPlaybackLauncher 同构）。
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val query: String = "",              // 输入框实时文字（未防抖）
        val committedQuery: String = "",     // 防抖后生效的查询
        val results: SearchResults = SearchResults(),
        val history: List<String> = emptyList(),
        val filter: String = "全部",         // 全部 / 影片 / 剧集 / 视频
        val searching: Boolean = false,      // 防抖窗口内为 true（骨架兜底）
        val recentlyAdded: List<MediaItemEntity> = emptyList(),  // 空输入态「最近添加」真数据
        val sourceNames: Map<Long, String> = emptyMap(),
        val indexingText: String? = null,    // 索引建立中横幅（C12 §5.3）
    )

    private val context: Context = app.applicationContext
    private val dao = NvGraph.mediaItemDao(context)

    init {
        // 2026-09-29 拼音检索建库收尾：搜索页首开时对存量空键行做一次回填（pinyin4j 纯本地
        // 转换 + 零 NAS IO，见 PinyinBackfill）。幂等：回填过的行不再命中候选查询；IO 上跑，
        // 失败静默（下次进搜索页再试），不阻塞首帧
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.novax.app.data.scanner.PinyinBackfill.run(dao) }
        }
    }

    /** 源列表内存缓存：播放地址 LOCAL 分支判定 + 文件结果行源名回显 */
    @Volatile
    private var cachedSources: List<SourceEntity> = emptyList()

    private val queryFlow = MutableStateFlow("")
    private val historyFlow = MutableStateFlow(emptyList<String>())
    private val filterFlow = MutableStateFlow("全部")

    /** 防抖窗口内 true；mapLatest 出结果后置 false（无结果空态由此可达） */
    private val searchingFlow = MutableStateFlow(false)

    private val recentlyAddedFlow = dao.observeRecentlyAdded()

    private val sourcesFlow = NvGraph.sourceDao(context).observeAll().onEach { cachedSources = it }

    /** 任一源扫描中 → 横幅「媒体库索引建立中，结果可能不全（已索引 N 个）」，近似值即可（C12 §5.3） */
    private val indexingFlow = NvScannerHolder.get(context).progress.mapLatest { live ->
        val running = live.values.filter { it.status == ScanStatus.RUNNING }
        running.takeIf { it.isNotEmpty() }?.let { "媒体库索引建立中，结果可能不全（已发现 ${it.sumOf { p -> p.foundItems }} 个）" }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val debouncedResults = queryFlow
        .debounce(DEBOUNCE_MS)                       // 状态 2：防抖窗口内不查询
        .mapLatest { q ->
            if (q.isBlank()) SearchResults() to "" else searchLocal(q.trim()) to q.trim()
        }
        // 2026-09-15 性能修复（评审 P1）：searchLocal 内拼音排序 + Regex 切分下放 Default 线程，
        // 旧版在 viewModelScope(Main) 收集 → 每次按键主线程对全部命中现转拼音排序
        .flowOn(Dispatchers.Default)
        .onEach { (results, q) ->
            searchingFlow.value = false              // 出结果 → 骨架退场，无结果空态可达
            if (q.isNotEmpty()) {                    // P3：搜索词写入历史（去重置顶）
                historyFlow.update { h ->
                    (listOf(q) + h.filterNot { it == q }).take(HISTORY_LIMIT)
                }
            }
        }

    private data class Inputs(
        val query: String,
        val pair: Pair<SearchResults, String>,
        val history: List<String>,
        val filter: String,
        val searching: Boolean,
    )

    private data class Env(
        val recent: List<MediaItemEntity>,
        val sources: List<SourceEntity>,
        val indexing: String?,
    )

    private val inputsFlow = combine(
        queryFlow, debouncedResults, historyFlow, filterFlow, searchingFlow, ::Inputs,
    )

    private val envFlow = combine(recentlyAddedFlow, sourcesFlow, indexingFlow, ::Env)

    val uiState: StateFlow<UiState> = combine(inputsFlow, envFlow) { inputs, env ->
        cachedSources = env.sources
        UiState(
            query = inputs.query,
            committedQuery = inputs.query.trim(),
            results = inputs.pair.first,
            history = inputs.history,
            filter = inputs.filter,
            searching = inputs.query.isNotBlank() && inputs.searching, // 防抖窗口内 true（骨架兜底）
            recentlyAdded = env.recent,
            sourceNames = env.sources.associate { it.id to it.nickname },
            indexingText = env.indexing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onQueryChange(q: String) {
        queryFlow.value = q
        searchingFlow.value = q.isNotBlank()         // 输入即进入「搜索中」，防抖结束出结果
    }

    fun onFilterChange(f: String) {
        filterFlow.value = f
    }

    // 历史 Chip 点击：回填 + 立即执行搜索（无防抖，直查）
    fun onHistoryPick(text: String) {
        queryFlow.value = text
        searchingFlow.value = text.isNotBlank()
        historyFlow.update { h ->
            (listOf(text) + h.filterNot { it == text }).take(HISTORY_LIMIT)
        }
    }

    fun onRemoveHistory(text: String) {
        historyFlow.update { h -> h - text }
    }

    fun onClearHistory() {
        historyFlow.value = emptyList()
    }

    fun onCancel() {
        queryFlow.value = ""
        searchingFlow.value = false
    }

    /** 最近添加/结果卡片可见项触发抽帧（与库页同一 requestThumb 状态机，CAS 去重） */
    fun needThumb(mediaItemId: Long) {
        viewModelScope.launch {
            runCatching { NvThumbHolder.get(context).requestThumb(mediaItemId) }
        }
    }

    /** 命中点击 → 播放地址：LOCAL 源 = 本地绝对路径，其余 = novax://<id><encoded path>
     *  （与 NvPlaybackLauncher 同构，保证历史/续播键一致，C12 §5.2 文件命中直达播放） */
    fun playUrlOf(item: MediaItemEntity): String {
        val source = cachedSources.firstOrNull { it.id == item.sourceId }
        return if (source?.type == SourceType.LOCAL) {
            NvPlaybackLauncher.localPath(source.shareOrPath, item.path)
        } else {
            NvPlaybackLauncher.novaxUrl(item.sourceId, item.path)
        }
    }

    // Room search（C12 §1.6）：nameLower LIKE（前缀命中优先）+ pinyin/initials 列
    // （2026-09-29 建库写入+存量回填已生效，格式约定见 MediaNames kdoc）
    private suspend fun searchLocal(q: String): SearchResults {
        val hits = runCatching { dao.search(q) }.getOrDefault(emptyList()).sortedWith(
            compareBy<MediaItemEntity, String>(com.novax.app.data.scanner.MediaNames.comparator) { it.scrapedTitle ?: it.name }
                .thenBy { it.sourceId }.thenBy { it.path }
        )
        val movies = hits.filter { it.kind == MediaKind.MOVIE }
        val videos = hits.filter { it.kind == MediaKind.VIDEO }
        val series = groupEpisodes(hits.filter { it.kind == MediaKind.EPISODE })
        return SearchResults(movies, series, videos)
    }

    /** EPISODE 按 (sourceId, 父目录) 聚合成剧集组（C12 §1.3/§5.2） */
    private fun groupEpisodes(episodes: List<MediaItemEntity>): List<SeriesGroup> =
        episodes
            .groupBy { it.sourceId to it.path.substringBeforeLast('/', "") }
            .map { (key, eps) ->
                val dir = key.second
                SeriesGroup(
                    sourceId = key.first,
                    dir = dir,
                    name = dir.substringAfterLast('/').ifBlank { dir.ifBlank { "剧集" } },
                    episodes = eps.size,
                    thumbnailPath = eps.firstOrNull { it.thumbState == ThumbState.DONE.name }?.thumbnailPath,
                    first = eps.first(),
                )
            }
            .sortedWith(compareBy<SeriesGroup, String>(com.novax.app.data.scanner.MediaNames.comparator) { it.name })

    companion object {
        const val DEBOUNCE_MS = 150L
        const val HISTORY_LIMIT = 20
    }
}
