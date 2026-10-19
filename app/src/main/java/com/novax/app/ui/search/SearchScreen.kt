package com.novax.app.ui.search

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.R
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.ThumbState
import com.novax.app.player.PlayerActivity
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.nvBarCollapse
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvFilterChip
import com.novax.app.ui.components.NvInlineBanner
import com.novax.app.ui.components.NvMediaPosterCard
import com.novax.app.ui.components.NvPosterCard
import com.novax.app.ui.components.NvSectionHeader
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSkeletonListRow
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.theme.NvColors

// C12 搜索页（真实化版，对齐 C7 交互 + C12 §五修订）：
// 结果 = MediaItemDao.search 按 kind 分桶（影片/剧集/视频），命中直达播放；
// 「最近添加」= observeRecentlyAdded 真数据 + 抽帧状态机；索引建立中横幅接真扫描进度。
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,   // 兼容宿主导航签名；一期命中直达播放，详情路由不再使用
    onPlay: (String) -> Unit,         // 同上（mock playById 已由真实播放取代）
    onGoToFiles: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // R12：列表滚动态提升到屏级（rememberSaveable 内核），返回详情页后结果列表位置恢复
    val resultListState = rememberLazyListState()
    // rework3：enterAlways 收起（本页"顶栏"=玻璃搜索框块，随结果列表下滑整条滑出/上滑滑回）
    val barCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(resultListState)

    // Liquid Glass（spec Stage 3 显式改造点，页面翻转）：
    // - 状态栏避让移到搜索框自身（statusBarsPadding），根 Box 保留 imePadding（键盘上推整页）；
    // - 结果容器全高挂 nvGlassSource、搜索框玻璃做兄弟（decisions.md §4.5）——搜索结果滚动钻入
    //   搜索框后透进玻璃；背景在 source 之后（§4.5a：栏后纯背景区域也有源像素）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // 搜索框块高：框 48dp + 上下 S 间距 + 状态栏（内容起点，实测不符以实测钉定）
    val statusBars = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fieldBlock = statusBars + 48.dp + NvSpacing.S * 2
    Box(
        Modifier
            .fillMaxSize()
            .background(NvColors.Background)
            .imePadding(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .nvGlassSource(glassEnv)
                .background(NvColors.Background),
        ) {
            when {
                state.query.isBlank() -> EmptyContent(state, viewModel, contentTop = fieldBlock)

                // 状态 4：无结果 + 建议操作（C12 §5.3 修订：删刮削句——搜不到就是真没有）
                state.results.isEmpty && !state.searching -> NvEmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_no_results_for, state.committedQuery),
                    description = stringResource(R.string.search_no_results_hint),
                    actionText = stringResource(R.string.search_go_files),
                    onAction = onGoToFiles,   // 真实切到「文件」Tab（宿主导航处理）
                    modifier = Modifier.fillMaxSize().padding(top = fieldBlock),
                )

                else -> ResultContent(
                    state = state,
                    listState = resultListState,
                    viewModel = viewModel,
                    onFilterChange = viewModel::onFilterChange,
                    contentTop = fieldBlock,
                    contentBottom = glassBottom,
                )
            }
        }
        NvSearchField(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onCancel = viewModel::onCancel,
            modifier = Modifier
                .nvBarCollapse(barCollapsed)   // rework3：下滑收起/上滑出现（整块含状态栏避让一起滑出）
                .fillMaxWidth()
                .statusBarsPadding()   // 状态栏避让回到搜索框自身（评审 MAJOR 整改：不能丢，丢了会画进状态栏）
                .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S)
                .clip(NvShape.Input)
                .nvGlassSurface(NvColors.GlassFill, NvShape.Input),   // 描边由 nvGlassSurface 自带
        )
    }
}

// ============ NvSearchField（C7 §五） ============
// Liquid Glass（spec Stage 3）：modifier 形参转嫁外层 Row——玻璃底（nvGlassSurface）由调用方
// 在翻转后的页面上以兄弟位挂上（decisions §4.5），内层不再自绘 background/border
@Composable
private fun NvSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,   // 新增形参：外层 Row 布局与玻璃底均由此传入
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .clip(NvShape.Input)
                .padding(horizontal = NvSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search, null,
                Modifier.size(24.dp), tint = NvColors.TextSecondary,
            )
            Spacer(Modifier.width(NvSpacing.S))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = NvTypography.BodyMedium.copy(color = NvColors.TextPrimary),
                cursorBrush = SolidColor(NvColors.Primary),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            // C12 §5.2：演员组一期移除，占位文案同步
                            Text(
                                stringResource(R.string.search_hint),
                                style = NvTypography.BodyMedium,
                                color = NvColors.TextDisabled,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                // 点击清除 X：只清文字，保持焦点与键盘
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Cancel,
                        stringResource(R.string.search_cd_clear),
                        Modifier.size(20.dp),
                        tint = NvColors.TextSecondary,
                    )
                }
            }
        }
        // 点击取消：清空输入、收键盘
        if (query.isEmpty()) {
            NvTextButton(stringResource(R.string.search_cancel), {
                onQueryChange("")
                onCancel()
                keyboard?.hide()
            }, tint = NvColors.TextSecondary)
        }
    }
}

/** 命中直达播放（C12 §5.2）：novax:// 或本地绝对路径，续播由 PlayerActivity 按历史键自理 */
private fun playHit(context: android.content.Context, viewModel: SearchViewModel, item: MediaItemEntity) {
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_VIDEO_URL, viewModel.playUrlOf(item))
            .putExtra(PlayerActivity.EXTRA_TITLE, item.scrapedTitle ?: item.name),
    )
}

// ============ 空输入态（历史 + 最近添加真数据） ============
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyContent(state: SearchViewModel.UiState, viewModel: SearchViewModel, contentTop: Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    // 2026-09-20 间距调整后空态内容变高（标题上间距 +8dp×2），历史 Chip 多时可能超一屏——加滚动兜底。
    // 搜索框悬浮（翻转后）：内容经顶部占位避让，滚动时钻入搜索框后透进玻璃（§4.5 配方）
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(contentTop))
        state.indexingText?.let {
            NvInlineBanner(
                text = it,
                modifier = Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            )
        }
        if (state.history.isNotEmpty()) {
            NvSectionHeader(stringResource(R.string.search_history), onMoreClick = { showClearDialog = true })
            FlowRow(
                Modifier.padding(horizontal = NvSpacing.L),
                horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
            ) {
                state.history.forEach { h ->
                    HistoryChip(
                        text = h,
                        onPick = { viewModel.onHistoryPick(h) },
                        onRemove = { viewModel.onRemoveHistory(h) },
                    )
                }
            }
        }
        // 「最近添加」真数据（C12 §5.1）：海报走与库页同一抽帧状态机
        NvSectionHeader(stringResource(R.string.search_recent))
        LazyRow(
            contentPadding = PaddingValues(horizontal = NvSpacing.L),
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
        ) {
            items(state.recentlyAdded, key = { it.id }) { item ->
                NvMediaPosterCard(
                    modifier = Modifier.width(110.dp),
                    title = item.scrapedTitle ?: item.name,
                    thumbnailPath = item.thumbnailPath,
                    thumbState = runCatching { ThumbState.valueOf(item.thumbState) }.getOrDefault(ThumbState.NONE),
                    onNeedThumb = { viewModel.needThumb(item.id) },
                    onClick = { playHit(context, viewModel, item) },
                )
            }
        }
        if (state.history.isEmpty()) {
            Text(
                stringResource(R.string.search_empty_footnote),
                Modifier.padding(NvSpacing.L),
                style = NvTypography.LabelSmall,
                color = NvColors.TextDisabled,
            )
        }
    }

    if (showClearDialog) {
        NvConfirmDialog(
            title = stringResource(R.string.search_clear_history_title),
            message = pluralStringResource(
                R.plurals.search_clear_history_msg, state.history.size, state.history.size,
            ),
            confirmText = stringResource(R.string.search_clear),
            danger = true,
            onConfirm = {
                viewModel.onClearHistory()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

// 历史 Chip：透明底 + Outline 描边，尾部 X 删除（热区 48dp），点击回填并立即搜索
@Composable
private fun HistoryChip(text: String, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(NvShape.Button)
            .border(1.dp, NvColors.Outline, NvShape.Button)
            .combinedClickable(onClick = onPick, onLongClick = onRemove),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.padding(start = NvSpacing.M, end = NvSpacing.XS, top = NvSpacing.XS, bottom = NvSpacing.XS),
            style = NvTypography.LabelMedium,
            color = NvColors.TextSecondary,
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, stringResource(R.string.search_cd_remove_history), Modifier.size(14.dp), tint = NvColors.TextDisabled)
        }
    }
}

// ============ 结果页（过滤 Chip + 三分组：影片/剧集/视频，C12 §5.2） ============
// 搜索框悬浮（翻转后）：结果列表全高，contentPadding top 避让搜索框块——滚动时结果钻入
// 搜索框后透进玻璃（§4.5 配方）；bottom 追加玻璃底栏高（Task 3 Step 3 各页同款，IME 打开归零）
@Composable
private fun ResultContent(
    state: SearchViewModel.UiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: SearchViewModel,
    onFilterChange: (SearchFilter) -> Unit,
    contentTop: Dp,
    contentBottom: Dp,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expandedGroups by rememberSaveable { mutableStateOf(setOf<SearchFilter>()) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentTop, bottom = NvSpacing.XL + contentBottom),
    ) {
        item {
            Row(
                Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
            ) {
                SearchFilter.entries.forEach { f ->
                    NvFilterChip(
                        label = stringResource(f.labelRes),
                        selected = state.filter == f,
                        onClick = { onFilterChange(f) },
                    )
                }
            }
        }

        // 状态 3 兜底：查询窗口内且无结果，先出骨架行
        if (state.searching && state.results.isEmpty) {
            items(3) {
                NvSkeletonListRow(Modifier.padding(horizontal = NvSpacing.L))
            }
            return@LazyColumn
        }

        val showMovies = state.filter in listOf(SearchFilter.ALL, SearchFilter.MOVIE) && state.results.movies.isNotEmpty()
        val showSeries = state.filter in listOf(SearchFilter.ALL, SearchFilter.SERIES) && state.results.series.isNotEmpty()
        val showVideos = state.filter in listOf(SearchFilter.ALL, SearchFilter.VIDEO) && state.results.videos.isNotEmpty()

        if (showMovies) {
            item { GroupHeader(stringResource(R.string.search_group_movies), state.results.movies.size) }
            item {
                if (SearchFilter.MOVIE in expandedGroups) {
                    Column {
                        state.results.movies.forEach { item ->
                            MediaListRow(
                                title = item.scrapedTitle ?: item.name,
                                meta = LibraryMeta.of(item, state.sourceNames),
                                onClick = { playHit(context, viewModel, item) },
                            )
                        }
                    }
                } else {
                    MediaPosterStrip(
                        items = state.results.movies.take(12),
                        onMore = if (state.results.movies.size > 12) {
                            { expandedGroups = expandedGroups + SearchFilter.MOVIE }
                        } else null,
                        onNeedThumb = viewModel::needThumb,
                        onItemClick = { playHit(context, viewModel, it) },
                    )
                }
            }
        }

        if (showSeries) {
            item { GroupHeader(stringResource(R.string.search_group_series), state.results.series.size) }
            item {
                if (SearchFilter.SERIES in expandedGroups) {
                    Column {
                        state.results.series.forEach { group ->
                            MediaListRow(
                                title = group.name,
                                meta = pluralStringResource(
                                    R.plurals.search_series_meta,
                                    group.episodes, group.episodes, state.sourceNames[group.sourceId] ?: "",
                                ),
                                onClick = { playHit(context, viewModel, group.first) },   // 组内首集直达播放
                            )
                        }
                    }
                } else {
                    // 剧集组海报：取组内首个抽帧成功的封面
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NvSpacing.L),
                        horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                    ) {
                        items(state.results.series.take(12), key = { it.sourceId.toString() + "_" + it.dir }) { group ->
                            NvPosterCard(
                                title = group.name,
                                subtitle = pluralStringResource(
                                    R.plurals.search_episode_count, group.episodes, group.episodes,
                                ),
                                posterUrl = group.thumbnailPath,
                                onClick = { playHit(context, viewModel, group.first) },
                            )
                        }
                    }
                }
            }
        }

        if (showVideos) {
            item { GroupHeader(stringResource(R.string.search_group_videos), state.results.videos.size) }
            items(state.results.videos, key = { it.id }) { item ->
                FileResultRow(item, state.committedQuery, state.sourceNames[item.sourceId] ?: "") {
                    playHit(context, viewModel, item)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    NvSectionHeader("$title · $count")
}

/** 折叠态横向海报条（可见项触发抽帧，与库页同一状态机） */
@Composable
private fun MediaPosterStrip(
    items: List<MediaItemEntity>,
    onMore: (() -> Unit)?,
    onNeedThumb: (Long) -> Unit,
    onItemClick: (MediaItemEntity) -> Unit,
) {
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = NvSpacing.L),
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
        ) {
            items(items, key = { it.id }) { item ->
                NvMediaPosterCard(
                    title = item.scrapedTitle ?: item.name,
                    thumbnailPath = item.thumbnailPath,
                    thumbState = runCatching { ThumbState.valueOf(item.thumbState) }.getOrDefault(ThumbState.NONE),
                    onNeedThumb = { onNeedThumb(item.id) },
                    onClick = { onItemClick(item) },
                )
            }
        }
        if (onMore != null) {
            NvTextButton(stringResource(R.string.search_more), onMore, modifier = Modifier.padding(horizontal = NvSpacing.L))
        }
    }
}

// 组内展开列表模式：72dp 行（首字母占位 + 片名 + 元信息）
@Composable
private fun MediaListRow(
    title: String,
    meta: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = NvSpacing.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(60.dp)
                .clip(NvShape.Poster)
                .background(NvColors.Skeleton),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "?",
                style = NvTypography.TitleMedium,
                color = NvColors.TextSecondary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = NvTypography.BodyMedium, color = NvColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = NvTypography.LabelSmall, color = NvColors.TextSecondary)
        }
    }
}

// 文件结果条目：扩展名着色图标 + 高亮文件名 + 源/路径，点击直达播放器（C12 §5.2）
@Composable
private fun FileResultRow(item: MediaItemEntity, query: String, sourceName: String, onClick: () -> Unit) {
    val dir = item.path.substringBeforeLast('/', "")
    val ext = item.path.substringAfterLast('.', "").lowercase()
    val iconTint = if (ext in listOf("mkv", "mp4")) NvColors.Secondary else NvColors.TextSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
    ) {
        Icon(Icons.Outlined.Description, null, Modifier.size(24.dp), tint = iconTint)
        Column(Modifier.weight(1f)) {
            highlight(item.scrapedTitle ?: item.name, query).let { annotated ->
                Text(annotated, style = NvTypography.BodyMedium, color = NvColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOf(sourceName, dir).filter { it.isNotBlank() }.joinToString(" · "),
                style = NvTypography.LabelSmall,
                color = NvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 命中高亮：命中子串 Primary（C7 §1.4）。（浅色模式改造：NvColors 变主题感知，函数挂 @Composable）
@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    val idx = text.indexOf(query, ignoreCase = true)
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = NvColors.Primary), idx, idx + query.length)
    }
}

private object LibraryMeta {
    fun of(item: MediaItemEntity, sourceNames: Map<Long, String>): String =
        listOfNotNull(
            sourceNames[item.sourceId],
            item.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}
