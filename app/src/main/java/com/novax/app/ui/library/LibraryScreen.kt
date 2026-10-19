@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.novax.app.ui.library

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TvOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import com.novax.app.ui.components.NvConfirmDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.novax.app.R
import com.novax.app.player.rememberPlayerPrefs
import com.novax.app.player.KEY_UI_POSTER_COLUMNS
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.data.db.MediaItemWithWatch
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.ThumbState
import com.novax.app.data.scanner.ScanProgress
import com.novax.app.ui.glass.LocalNvGlassEnv
import com.novax.app.ui.glass.NvGlassBar
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.glass.rememberNvTopBarInset
import com.novax.app.player.PlayerActivity
import com.novax.app.player.PlayerPlaylistHolder
import com.novax.app.ui.components.NvAlphabetSidebar
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.nvBarCollapse
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvFilterChip
import com.novax.app.ui.components.NvInlineBanner
import com.novax.app.ui.components.NvBannerKind
import com.novax.app.ui.components.NvMediaPosterCard
import com.novax.app.ui.components.NvSegmentedControl
import com.novax.app.ui.components.NvScanStatusRow
import com.novax.app.ui.components.NvSecondaryButton
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.data.cast.CastMedia
import com.novax.app.data.cast.CastSessionHolder
import com.novax.app.ui.cast.NvCastDeviceSheet
import com.novax.app.ui.cast.NvCastMeteredDialogHost
import com.novax.app.ui.cast.rememberNvCastGateState
import com.novax.app.ui.components.NvSkeletonPosterGrid
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.files.NvFilesDeepLink
import com.novax.app.ui.files.breadcrumbOf
import com.novax.app.ui.theme.NvColors

// 字母快速滚动侧条（C3 §4.3，本任务落地）：标题排序时网格右侧叠加 NvAlphabetSidebar。

// Liquid Glass（spec §9 Task 8）：顶簇悬浮后各滚动容器 contentPadding top 的簇内分量 = FilterBar
// 实测高（分段行 42dp + 筛选行底 padding 16dp = 58dp）。来源：emulator-5554（1080x2400@420dpi，
// density 2.625）实拍量取——screencap 像素测量分段行视觉高 110px（276..386，即 42dp）、底 padding
// 42px（16dp），网格首行卡顶缘 427px 与簇底预测 428px 1px 内吻合（glass_t8_library_rest.png，
// 量取后钉成常量，评审裁定勿动态）。NvTopBar 高度（状态栏 + 56dp）由 rememberNvTopBarInset()
// 单独承担，不含在此常量内；条件行（筛选回显 chips / 扫描 NvScanStatusRow）为瞬态覆盖：
// 出现时内容自其实色底下滚出，不为它们抬高常量（否则常态留永久空带）。
// 2026-10-17 用户真机反馈推翻 chips 段旧语义：筛选回显 chips 行悬浮在海报卡上「很突兀」——
// 同横幅（rework8）先例改为 onSizeChanged 动态实测避让：仅筛选生效期存在（count>0 才计入），
// 清除即归零，常态（无筛选）零空带；旧「不抬常量」论据针对的是钉常量，动态实测无此弊。
// 扫描 NvScanStatusRow 维持瞬态覆盖语义不动（进度行短暂、遮 affordance 低）。
// rework8（2026-10-15 用户裁定）：扫描失败横幅并入顶簇收起组（悬浮层内、紧贴筛选栏下方），
// 但内容首屏避让**含横幅段**——横幅高度随文案换行动态，onSizeChanged 实测（bannerBlock），
// 与 chips/扫描行的「不抬高常量」语义不同：横幅是常驻卡（在场时首屏首行卡必须自其下起），
// 而非滚过即走的瞬态行。
private val LIBRARY_CLUSTER_EXTRA = 58.dp

/**
 * C12 媒体库（真实化版）：media_items 唯一数据源（LibraryViewModel.observeLibrary），
 * 帧封面经 NvMediaPosterCard 状态机（可见项 requestThumb → Room Flow 自动回流），
 * 扫描进度 NvScanStatusRow 尾插。卡片点击一期直达播放（C12 §4.5）。
 */
@Composable
fun LibraryScreen(
    onOpenDetail: (String) -> Unit,   // 兼容宿主导航签名；真实条目一期直达播放，详情路由不再使用
    onGoToFiles: (() -> Unit)? = null, // 2026-09-15：图片分段空态引导（图片已退出媒体库）
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var confirmRefresh by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }

    // BUG-C-R6-15：合集（state.folder）是 Tab 内下钻态——系统返回先收合集回库根，
    // 不落到 App 级「回上一 Tab」（否则会跨 Tab 跳到文件页并恢复其旧文件夹）。
    // 下钻快照经 SideEffect 上报 + NvTabBackBus 兜底路由，双通道语义等价。
    val libraryInTabBack: () -> Unit = { viewModel.selectFolder(null) }
    BackHandler(enabled = state.folder != null) { libraryInTabBack() }
    LaunchedEffect(Unit) {
        com.novax.app.ui.components.NvTabBackBus.inTabBack.collect { route ->
            if (route == com.novax.app.ui.components.NvTabBackBus.ROUTE_LIBRARY) libraryInTabBack()
        }
    }
    SideEffect {
        com.novax.app.ui.components.NvTabBackBus.libraryDrilled = state.folder != null
    }
    val preferences by rememberPlayerPrefs()
    // 观感返工①（2026-10-15）：网格/列表滚动态屏级上提（原在 ReadyContent/LibraryListView 内部
    // 私有），供顶簇 NvTopBar 渐显信号共用；列表视图消费 listState，其余（两种网格）消费 gridState
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val gridScrolled by com.novax.app.ui.components.rememberNvGridScrolled(gridState)
    val listScrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    val headerScrolled = if (state.viewMode == ViewMode.List) listScrolled else gridScrolled
    // rework3：enterAlways 收起信号。初判「FilterBar/分段控件钉住不收」被用户真机截图推翻
    // （顶栏收起后筛选栏仍悬浮在内容上）——收起范围上移为整个顶簇（见下方顶簇 Column 挂
    // nvBarCollapse）。网格/剧集组=gridState、List=listState，与 headerScrolled 同口径择一
    val gridBarCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(gridState)
    val listBarCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(listState)
    val barCollapsed = if (state.viewMode == ViewMode.List) listBarCollapsed else gridBarCollapsed
    // Liquid Glass（spec §9 悬浮翻转 + decisions.md §4.5）：source 挂内容 BoxWithConstraints
    // （覆盖三分支网格+List+侧条，全高），顶簇（NvTopBar+FilterBar+扫描行）整体 as 悬浮层做兄弟
    // （§4.1 源/玻璃不同子树）——内容自簇下滚过。评审裁定：必须真正悬浮，不走 FileBrowser
    // 「页头留内容流」老路（Task 7 评审确认其常规态玻璃无源可采）
    val glassEnv = LocalNvGlassEnv.current
    // 悬浮顶簇配方的 top inset = 状态栏 + 56dp 栏高（NvTopBar 全高自绘，含状态栏条带）
    val topInset = rememberNvTopBarInset()
    // 内容首屏避让分量 = topInset + LIBRARY_CLUSTER_EXTRA（簇内 FilterBar 实测高，常量见文件头）
    val clusterTop = topInset + LIBRARY_CLUSTER_EXTRA
    // rework8（2026-10-15 用户裁定）：扫描失败横幅并入顶簇收起组（见下方顶簇 Column 内）。
    // 横幅高度随文案换行动态（FR3 长文案 2 行 vs 单行源），不能钉常量——onSizeChanged 实测
    // （挂在 padding 链后=量「上间隙 S+卡体」整段），只在横幅出现/换行时回调一次，非逐帧。
    // 内容首屏避让随之加横幅段（与旧布局内版本同几何：top S + 卡高 + bottom S），横幅不在场
    // 时为 0（常态零空带）。折算 dp 在组合期一次，回调仅写 px state 触发重组。
    val density = LocalDensity.current
    var bannerBlockPx by remember { mutableIntStateOf(0) }
    val bannerBlock =
        if (state.failedScan != null || state.notedScan != null) with(density) { bannerBlockPx.toDp() } + NvSpacing.S else 0.dp
    // 2026-10-17 用户反馈：筛选回显 chips 行不再悬浮压卡（方案 A）——同横幅先例 onSizeChanged
    // 实测「上间隙 S+chip 行」整段，仅筛选生效期（count>0）计入避让，清除即归零（常态零空带）。
    // 横幅在 chips 行之下同簇排布，二者同时在场时各自分量自然叠加。
    var chipsBlockPx by remember { mutableIntStateOf(0) }
    val chipsBlock =
        if (modifierFilterCount(state) > 0) with(density) { chipsBlockPx.toDp() } + NvSpacing.S else 0.dp
    // 各阶段内容容器统一的 top 避让 = 顶簇 + 失败横幅段 + 筛选回显 chips 段（均悬浮于内容上）
    val contentTop = clusterTop + bannerBlock + chipsBlock
    // 2026-09-15 B12：骨架列数改用与网格同一 BoxWithConstraints 宽度口径，折叠切换不再跳列
    // （旧：LocalConfiguration.screenWidthDp 与网格 maxWidth 口径不一致）
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(NvColors.Background),
    ) {
        // 内容容器全高（§4.5a：source 须覆盖栏后区域）；background 在 nvGlassSource 之后（录制含背景）
        BoxWithConstraints(Modifier.fillMaxSize().nvGlassSource(glassEnv).background(NvColors.Background)) {
            // 2026-09-15 B12：骨架列数与网格同一宽度口径（maxWidth 已扣 inset/边距）
            val skeletonColumns = posterColumns(preferences?.get(KEY_UI_POSTER_COLUMNS), maxWidth.value)
            // 2026-09-15 分段重设计：剧集=同文件夹 ≥2 视频聚合（2026-09-20 起 ≥2 门槛在 VM
            // seriesGroups 过滤）。空态诚实说明聚合规则，不再让用户误以为要重扫
            if (state.mediaType == MediaType.SERIES &&
                state.seriesGroups.isEmpty() &&
                phaseOf(state) in listOf(Phase.ScanDoneEmpty, Phase.NotIndexed, Phase.Ready)
            ) {
                NvEmptyState(
                    icon = Icons.Outlined.TvOff,
                    title = stringResource(R.string.lib_series_empty_title),
                    description = stringResource(R.string.lib_series_empty_desc),
                    modifier = Modifier.padding(top = contentTop),
                )
            } else when (phaseOf(state)) {
                Phase.Loading -> NvSkeletonPosterGrid( // S1
                    columns = skeletonColumns,
                    modifier = Modifier.padding(top = contentTop),
                )
                Phase.NoSource -> NvEmptyState( // S2 空库·无源
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.lib_empty_title),
                    description = stringResource(R.string.lib_empty_desc),
                    modifier = Modifier.padding(top = contentTop),
                )
                Phase.NotIndexed -> NvEmptyState( // S3 空库·有源未扫描
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.lib_not_indexed_title),
                    description = stringResource(R.string.lib_not_indexed_desc),
                    actionText = stringResource(R.string.lib_scan_now),
                    onAction = { confirmRefresh = true },
                    modifier = Modifier.padding(top = contentTop),
                )
                Phase.ScanningEmpty -> NvSkeletonPosterGrid( // 独立剩余内容区
                    columns = skeletonColumns,
                    modifier = Modifier.padding(top = contentTop),
                )
                Phase.ScanDoneEmpty -> NvEmptyState( // S6 扫描完成 0 文件
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.lib_scan_done_empty_title),
                    description = stringResource(R.string.lib_scan_done_empty_desc),
                    actionText = stringResource(R.string.lib_rescan),
                    onAction = { confirmRefresh = true },
                    modifier = Modifier.padding(top = contentTop),
                )
                Phase.NoMatch -> NvEmptyState( // S12 筛选无结果
                    icon = Icons.Outlined.ImageSearch,
                    title = stringResource(R.string.lib_no_match_title),
                    description = stringResource(R.string.lib_no_match_desc),
                    actionText = stringResource(R.string.lib_clear_filters),
                    onAction = viewModel::clearFilters,
                    modifier = Modifier.padding(top = contentTop),
                )
                Phase.Ready -> ReadyContent(state, viewModel, contentTop, gridState, listState)
            }
        }
        // 顶簇悬浮层（最后声明盖在内容上）：NvTopBar 玻璃（env 已接线自动启用），
        // rework3 观感统一（2026-10-15 用户真机反馈「筛选也做成玻璃效果，太突兀了」）：
        // FilterBar 的分段容器/筛选按钮实心板 → NvGlassBar/NvGlassChip 玻璃面（与顶/底栏同管线），
        // 旧「实色带遮蔽簇下穿行内容」随之摘除——内容自簇下滚过经玻璃透出即玻璃语义本身；
        // 布局上整体悬浮、不占内容高度。扫描行 NvScanStatusRow 仍自带 Surface 实卡（瞬态横幅，
        // 不在本次玻璃化范围）。
        // rework3 追平（用户真机反馈推翻「筛选栏钉住」初判）：整簇挂同一 nvBarCollapse——
        // NvTopBar+FilterBar+扫描行同一 graphicsLayer，滑出/滑回天然同帧同步（无先后错动）；
        // 命中测试随层平移=完全收起后筛选栏/动作钮不可误触。
        // rework8（2026-10-15 用户裁定）：扫描失败横幅一并入组——凡悬浮在滚动内容上方的常驻
        // 通知/横幅一律属于顶簇收起组，不允许顶簇收起后单独常驻（旧版横幅独立于收起组，
        // 下滑后顶簇消失而横幅留在原地，用户真机截图实证违和）。几何与旧版逐项不变：
        // 横向 L 边距、筛选栏下 S 间隙（收起组内自流式排布，位置仍紧贴筛选栏下方）。
        Column(Modifier.fillMaxWidth().nvBarCollapse(barCollapsed)) {
            LibraryHeader(
                state = state,
                scrolled = headerScrolled,   // 观感返工①：静止态顶栏隐形，滚过首项渐显
                onToggleView = viewModel::toggleViewMode,
                onSortClick = viewModel::showSortSheet,
                onScan = { if (state.scanning) viewModel.cancelScan() else confirmRefresh = true },
            )

            Column {
                // 2026-09-15 用户反馈④重做：主分类分段 + 右侧「筛选」入口合成同一行；
                // 目录/源/已看这些修饰条件收进弹层，选中后在下方以可点标签回显（不再和第二排
                // 小胶囊抢视觉重心，也不再被字母侧条压住）
                LibraryFilterBar(
                    state = state,
                    onSelectType = { viewModel.selectMediaType(MediaType.entries[it]) },
                    onOpenFilter = { filterSheet = true },
                    onClear = viewModel::clearFilters,
                    onClearFolder = { viewModel.selectFolder(null) },
                    onClearSource = { viewModel.selectSource(null) },
                    onClearWatched = { viewModel.toggleWatched(state.watchedFilter) },
                    // 2026-10-17 方案 A：chips 行实测高度回传，contentTop 动态避让（不再压卡）
                    onChipsHeightChanged = { chipsBlockPx = it },
                )

                // S7 扫描失败横幅 + 2026-10-19 完成带提示横幅（rework8 自 ReadyContent 移入收起组；
                // onSizeChanged 量「上间隙+卡体」供内容避让，见上方 bannerBlock 注）。两横幅同段
                // 计入避让——测量上收到包裹 Column，各横幅只带自身 padding
                if (state.failedScan != null || state.notedScan != null) {
                    Column(Modifier.onSizeChanged { bannerBlockPx = it.height }) {
                        state.failedScan?.let { failed ->
                            val name = state.sources.firstOrNull { it.id == failed.sourceId }?.nickname
                                ?: stringResource(R.string.lib_source_fallback, failed.sourceId)
                            NvInlineBanner(
                                text = stringResource(
                                    R.string.lib_scan_failed_banner,
                                    name,
                                    failed.error ?: stringResource(R.string.lib_unknown_error),
                                ),
                                kind = NvBannerKind.Error,
                                modifier = Modifier
                                    .padding(top = NvSpacing.S, start = NvSpacing.L, end = NvSpacing.L),
                            )
                        }
                        state.notedScan?.let { noted ->
                            val name = state.sources.firstOrNull { it.id == noted.sourceId }?.nickname
                                ?: stringResource(R.string.lib_source_fallback, noted.sourceId)
                            NvInlineBanner(
                                text = stringResource(R.string.lib_scan_noted_banner, name, noted.error ?: ""),
                                kind = NvBannerKind.Info,
                                modifier = Modifier
                                    .padding(top = NvSpacing.S, start = NvSpacing.L, end = NvSpacing.L),
                            )
                        }
                    }
                }

                val running = state.progress.values.filter { it.status == ScanStatus.RUNNING }
                running.firstOrNull()?.let { progress ->
                    val nickname = state.sources.firstOrNull { it.id == progress.sourceId }?.nickname
                        ?: stringResource(R.string.lib_source_fallback, progress.sourceId)
                    NvScanStatusRow(
                        // i18n Task 8 评审移交②：横幅第 1 行文案资源化（原 MediaScanner.bannerTitle 拼串）
                        title = stringResource(
                            R.string.data_scan_banner_title,
                            nickname, progress.scannedDirs, progress.foundItems,
                        ),
                        path = progress.currentDir.takeIf { it.isNotBlank() },
                        note = if (running.size > 1) {
                            pluralStringResource(R.plurals.lib_scan_other_sources, running.size - 1, running.size - 1)
                        } else null,
                        onCancel = viewModel::cancelScan,
                    )
                }
            }
        }
    }

    if (confirmRefresh) {
        NvConfirmDialog(
            title = stringResource(R.string.lib_rescan_title),
            message = pluralStringResource(R.plurals.lib_rescan_msg, state.sources.size, state.sources.size),
            confirmText = stringResource(R.string.lib_rescan_confirm),
            onConfirm = { confirmRefresh = false; viewModel.rescan() },
            onDismiss = { confirmRefresh = false },
        )
    }
    if (filterSheet) {
        LibraryFilterSheet(state = state, vm = viewModel, onClose = { filterSheet = false })
    }
    // ── 弹层：排序（§4.1）──
    if (state.sortSheetVisible) {
        NvBottomSheet(onDismiss = viewModel::dismissSortSheet) {
            LibrarySort.entries.forEach { sort ->
                NvSheetListItem(text = stringResource(sort.labelRes), selected = state.sort == sort) {
                    viewModel.selectSort(sort)
                }
            }
        }
    }
    // ── 弹层：源筛选（仅一源时入口隐藏）──
    if (state.sourceSheetVisible) {
        NvBottomSheet(onDismiss = viewModel::dismissSourceSheet) {
            NvSheetListItem(stringResource(R.string.lib_source_filter_all), selected = state.sourceId == null) {
                viewModel.selectSource(null)
            }
            state.sources.forEach { s ->
                NvSheetListItem(stringResource(R.string.lib_source_filter_named, s.nickname), selected = state.sourceId == s.id) {
                    viewModel.selectSource(s.id)
                }
            }
        }
    }
    // ── 弹层：长按操作（C12 §4.4）──
    var castMedia by remember { mutableStateOf<CastMedia?>(null) }
    val castGate = rememberNvCastGateState()   // 2026-09-28 审计 P0-1：计量网络投屏确认
    state.itemSheet?.let { item ->
        ItemActionSheet(state, item, viewModel, onOpenDetail, onGoToFiles) { castMedia = it }
    }
    // ── 弹层：投屏设备选择（C13 §5 入口一：媒体库长按）──
    castMedia?.let { media ->
        val scope = rememberCoroutineScope()
        NvCastDeviceSheet(
            onDismiss = { castMedia = null },
            onCast = { device ->
                scope.launch {
                    // 2026-09-28 审计 P0-1：投屏 = 经手机中继整片拉流，计量网络先确认
                    if (!castGate.awaitConsent(media)) {
                        castMedia = null
                        return@launch
                    }
                    CastSessionHolder.cast(device, media)
                    castMedia = null
                }
            },
        )
    }
    NvCastMeteredDialogHost(castGate)
}

/** 全屏阶段推导（C12 §八.1；筛选无结果优先于索引为空，避免误报「未发现视频」） */
private fun phaseOf(state: LibraryUiState): Phase = when {
    state.loading -> Phase.Loading
    state.sources.isEmpty() -> Phase.NoSource
    state.scanning && state.items.isEmpty() -> Phase.ScanningEmpty
    state.noMatch -> Phase.NoMatch
    state.items.isEmpty() && state.scans.any { it.status == ScanStatus.DONE } -> Phase.ScanDoneEmpty
    state.items.isEmpty() -> Phase.NotIndexed
    else -> Phase.Ready
}

// ── 顶栏：标题 + 视图切换 + 排序 + 重新扫描（C12 §4.2）──
@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    scrolled: Boolean,   // 观感返工①：滚动渐显信号（静止态顶栏隐形）
    // rework3 追平：collapsed 信号上移至顶簇外层 Column（FilterBar 并入收起组），本层不再自挂
    onToggleView: () -> Unit,
    onSortClick: () -> Unit,
    onScan: () -> Unit,
) {
    NvTopBar(
        title = stringResource(R.string.lib_title),
        scrolled = scrolled,
        actions = listOf {
            if (!state.scanning) {
                IconButton(onClick = onScan, Modifier.size(48.dp)) { // T2 重新扫描入口
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = stringResource(R.string.lib_rescan),
                        tint = NvColors.TextPrimary,
                    )
                }
            }
            IconButton(onClick = onToggleView, Modifier.size(48.dp)) {
                Icon(
                    if (state.viewMode == ViewMode.Grid) {
                        Icons.AutoMirrored.Outlined.ViewList
                    } else {
                        Icons.Outlined.GridView
                    },
                    contentDescription = stringResource(R.string.lib_cd_toggle_view),
                    tint = NvColors.TextPrimary,
                )
            }
            IconButton(onClick = onSortClick, Modifier.size(48.dp)) {
                // 2026-09-16 UI 审计 P2-12：排序是动作不是状态——图标不再整体变橙，
                // 非默认排序时在右上角挂 4dp 橙点角标（Badge 语义；默认基准 2026-10-16 ADDED→TITLE）
                Box {
                    Icon(
                        Icons.AutoMirrored.Outlined.Sort,
                        contentDescription = stringResource(R.string.lib_sort),
                        tint = NvColors.TextPrimary,
                    )
                    if (state.sort != LibrarySort.TITLE) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(5.dp)
                                .background(NvColors.Primary, androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
            }
        },
    )
}

// ── 筛选栏（2026-09-15 用户反馈④重做）──
// 旧版：两行同尺寸 chip（第一行分类分段、第二行目录/源/已看小胶囊）挤在一起，视觉层级靠
// 尺寸硬撑，且第二行被右侧字母侧条压住 → 用户反馈「不好看、分不清是两类选择」。
// 新版：一行解决——左=主分类分段（选中橙底黑字实心），右=「筛选」入口（有生效条件时橙框 +
// 数量角标）；生效条件在下一行以可点标签回显，点标签即清除该条件。

/** 生效修饰条件计数（不含主分类：分类已由分段控件自身表达） */
private fun modifierFilterCount(state: LibraryUiState): Int =
    (if (state.folder != null) 1 else 0) +
        (if (state.sourceId != null) 1 else 0) +
        (if (state.watchedFilter != WatchedFilter.NONE) 1 else 0)

@Composable
private fun LibraryFilterBar(
    state: LibraryUiState,
    onSelectType: (Int) -> Unit,
    onOpenFilter: () -> Unit,
    onClear: () -> Unit,
    onClearFolder: () -> Unit,
    onClearSource: () -> Unit,
    onClearWatched: () -> Unit,
    onChipsHeightChanged: (Int) -> Unit = {},
) {
    val count = modifierFilterCount(state)
    Column(
        // 2026-09-18 用户反馈：筛选行与海报网格贴死不美观——统一在筛选行底部留 16dp，
        // 网格/列表/骨架/空态所有阶段同时受益（网格 contentPadding 不必各自加 top）
        Modifier.fillMaxWidth().padding(bottom = NvSpacing.L),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NvSpacing.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
        ) {
            // 主分类：占满剩余宽度（四段等宽，右侧让出筛选按钮位）
            // 2026-09-16 UI 审计 P2-14：内联实现抽为 NvSegmentedControl（与死代码 NvSegmentChip 二合一）
            // rework3 观感统一（2026-10-15 用户真机反馈「筛选也做成玻璃效果，太突兀了」）：
            // 实心 SurfaceElevated 板 → NvGlassBar 玻璃面（NvShape.Button 胶囊，nvGlassSurface
            // FULL 档自动获得边缘透镜+顶缘高光，SCRIM/BLUR 档走既有降级分支）；container=false
            // 让分段控件只保留 3dp 内衬几何、容器面交由玻璃承担。选中段 Primary 15%+Primary 字
            // 与文字 token 不动（rework2 统一标准）。
            NvGlassBar(
                modifier = Modifier.weight(1f),
                shape = NvShape.Button,
            ) {
                NvSegmentedControl(
                    options = MediaType.entries.map { stringResource(it.labelRes) },
                    selectedIndex = state.mediaType.ordinal,
                    onSelect = onSelectType,
                    container = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LibraryFilterButton(count = count, onClick = onOpenFilter)
        }
        // 生效条件回显：只在有修饰条件时出现，不占常态布局。
        // 2026-10-17 方案 A（用户反馈「chip 压卡很突兀」）：不再悬浮——实测高度经
        // onChipsHeightChanged 回传，contentTop 动态让位（同横幅 rework8 先例），海报自 chip 行下起排。
        if (count > 0) {
            // E3-2（发版审查）：点单个条件 chip 只清该维度（源筛选连带目录，因目录从属于源），不再整排全清
            LazyRow(
                Modifier
                    .padding(top = NvSpacing.S)
                    .onSizeChanged { onChipsHeightChanged(it.height) },
                contentPadding = PaddingValues(horizontal = NvSpacing.L),
                horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.folder != null) {
                    item {
                        NvFilterChip(
                            label = stringResource(
                                R.string.lib_chip_folder,
                                state.folder.path.substringAfterLast('/'),
                            ),
                            selected = true,
                            // BUG：超长目录名把「清除」挤出屏幕——chip 限宽 + 单行省略
                            modifier = Modifier.widthIn(max = 220.dp),
                            onClick = { onClearFolder() },
                            showSelectionIcon = false,
                            filledSelected = true,
                            compact = true,
                            leadingIcon = Icons.Outlined.FolderOpen,
                        )
                    }
                }
                if (state.sourceId != null) {
                    item {
                        val name = state.sources.firstOrNull { it.id == state.sourceId }?.nickname
                            ?: stringResource(R.string.lib_all)
                        NvFilterChip(
                            label = stringResource(R.string.lib_chip_source, name),
                            selected = true,
                            onClick = { onClearSource() },
                            showSelectionIcon = false,
                            filledSelected = true,
                            compact = true,
                            leadingIcon = Icons.Outlined.Dns,
                        )
                    }
                }
                if (state.watchedFilter != WatchedFilter.NONE) {
                    item {
                        NvFilterChip(
                            label = stringResource(state.watchedFilter.labelRes),
                            selected = true,
                            onClick = { onClearWatched() },
                            showSelectionIcon = false,
                            filledSelected = true,
                            compact = true,
                            leadingIcon = if (state.watchedFilter == WatchedFilter.WATCHED) {
                                Icons.Outlined.CheckCircle
                            } else {
                                Icons.Outlined.RadioButtonUnchecked
                            },
                        )
                    }
                }
                item {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.heightIn(min = 28.dp),
                        contentPadding = PaddingValues(horizontal = NvSpacing.S),
                    ) { Text(stringResource(R.string.lib_clear), style = NvTypography.LabelSmall, color = NvColors.Primary) }
                }
            }
        }
    }
}

/** 「筛选」入口：漏斗 + 生效条件数量角标（0 = 中性玻璃；>0 = 玻璃面 + 主色淡橙 tint + 主色描边 + 主色角标）。
 *  rework3 观感统一（2026-10-15）：实心 SurfaceElevated/PrimaryContainer 板 → NvGlassChip 玻璃面
 *  （NvGlassBar 同款 nvGlassSurface 管线 + 可点击，即 NvGlassBar 的按钮变体）；active 态不再整块
 *  实橙，改为玻璃上叠 Primary 15% 淡橙 + 1dp Primary 描边——与分段选中 pill/底栏选中胶囊同一
 *  「Primary 15% + Primary 字」标准。 */
@Composable
private fun LibraryFilterButton(count: Int, onClick: () -> Unit) {
    val active = count > 0
    NvGlassChip(
        modifier = Modifier.heightIn(min = 42.dp),
        shape = NvShape.Button,
        onClick = onClick,
    ) {
        if (active) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(NvColors.Primary.copy(alpha = 0.15f), NvShape.Button)
                    .border(1.dp, NvColors.Primary, NvShape.Button),
            )
        }
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.FilterAlt,
                contentDescription = stringResource(R.string.lib_filter),
                Modifier.size(18.dp),
                tint = if (active) NvColors.Primary else NvColors.TextSecondary,
            )
            Text(
                if (active) "$count" else stringResource(R.string.lib_filter),
                style = NvTypography.LabelMedium,
                fontWeight = if (active) FontWeight.SemiBold else null,
                color = if (active) NvColors.Primary else NvColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * 筛选弹层（用户反馈④：修饰条件统一收口到这里）：
 * 分组小标题 + 已看状态（互斥，再点取消）+ 目录（全部/具体目录）+ 源（多源时）。
 * 分组标题统一 LabelSmall/TextDisabled，与胶囊拉开层级。
 */
@Composable
private fun LibraryFilterSheet(state: LibraryUiState, vm: LibraryViewModel, onClose: () -> Unit) {
    NvBottomSheet(onDismiss = onClose) {
        Text(
            stringResource(R.string.lib_filter),
            modifier = Modifier.padding(start = NvSpacing.L, top = NvSpacing.S, bottom = NvSpacing.S),
            style = NvTypography.TitleMedium,
            color = NvColors.TextPrimary,
        )
        Text(
            stringResource(R.string.lib_watch_status),
            modifier = Modifier.padding(start = NvSpacing.L, bottom = NvSpacing.XS),
            style = NvTypography.LabelSmall,
            color = NvColors.TextDisabled,
        )
        Row(
            Modifier.padding(start = NvSpacing.L, end = NvSpacing.L, bottom = NvSpacing.S),
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
        ) {
            NvFilterChip(
                label = stringResource(R.string.lib_watched),
                selected = state.watchedFilter == WatchedFilter.WATCHED,
                onClick = { vm.toggleWatched(WatchedFilter.WATCHED) },
            )
            NvFilterChip(
                label = stringResource(R.string.lib_unwatched),
                selected = state.watchedFilter == WatchedFilter.UNWATCHED,
                onClick = { vm.toggleWatched(WatchedFilter.UNWATCHED) },
            )
        }
        Text(
            stringResource(R.string.lib_folders),
            modifier = Modifier.padding(start = NvSpacing.L, bottom = NvSpacing.XS),
            style = NvTypography.LabelSmall,
            color = NvColors.TextDisabled,
        )
        NvSheetListItem(stringResource(R.string.lib_all_folders), selected = state.folder == null) { vm.selectFolder(null) }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            lazyItems(state.folders) { folder ->
                val sourceName = state.sources.firstOrNull { it.id == folder.sourceId }?.nickname.orEmpty()
                NvSheetListItem("$sourceName · ${folder.path}", selected = folder == state.folder) {
                    vm.selectFolder(folder)
                }
            }
        }
        if (state.sources.size > 1) {
            Text(
                stringResource(R.string.lib_sources),
                modifier = Modifier.padding(start = NvSpacing.L, top = NvSpacing.S, bottom = NvSpacing.XS),
                style = NvTypography.LabelSmall,
                color = NvColors.TextDisabled,
            )
            NvSheetListItem(stringResource(R.string.lib_all_sources), selected = state.sourceId == null) { vm.selectSource(null) }
            state.sources.forEach { s ->
                NvSheetListItem(s.nickname, selected = state.sourceId == s.id) { vm.selectSource(s.id) }
            }
        }
    }
}

// ── S5 旧版「扫描中·库还空」整页组件已删除（2026-09-15）：phaseOf(ScanningEmpty) 直接
// 渲染骨架 + 顶部横幅承载全部信息，此函数为无人调用的死代码（评审 P3 证据 2）

// ── Ready：网格 / 列表 + 字母侧条（仅标题排序，本任务新增）──
// rework8：S7 失败横幅移出本组件（并入库页顶簇收起组，见 LibraryScreen 顶簇 Column）——
// 悬浮在滚动内容上的常驻横幅不得脱离顶簇单独常驻（2026-10-15 用户裁定，decisions §15）
@Composable
private fun ReadyContent(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    contentTop: Dp,   // 内容首屏避让分量（顶簇 topInset+LIBRARY_CLUSTER_EXTRA + 失败横幅段）
    gridState: LazyGridState,     // 观感返工①：屏级上提（顶栏渐显信号共用）
    listState: LazyListState,     // 同上：List 视图滚动态
) {
    val preferences by rememberPlayerPrefs()
    val scope = rememberCoroutineScope()
    // BUG-C-R7-02（= 实操 T3-02 根因）：排序/筛选/分段/源/目录/视图变化后把网格锚点复位到
    // 顶部——带 key 的 LazyGrid 会锚定原首可见项，切换后视口从中途开始，头部条目「不可见」
    // 且跨 Tab 持久（清筛选也不恢复）
    LaunchedEffect(
        state.sort, state.mediaType, state.watchedFilter,
        state.sourceId, state.folder, state.viewMode,
    ) {
        gridState.scrollToItem(0)
        // E3-1（发版审查）：带 key 的 LazyList 同样锚定原首可见项——原修复只复位了网格，
        // List 视图排序/筛选后停在中段（与 BUG-C-R7-02 同症）。两态都滚，幂等。
        listState.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 2/3/4/5 列自适应：columns = (width / 150dp).coerceIn(2, 5)
            val widthDp = maxWidth.value
            val columns = posterColumns(preferences?.get(KEY_UI_POSTER_COLUMNS), widthDp)
            // 字母侧条可见性（网格右端避让与侧条渲染共用同一判定）
            // BUG-C-R6-16：剧集组卡网格（剧名 A→Z）同样显示侧条——原实现只认普通网格
            val seriesGroupGrid = state.mediaType == MediaType.SERIES && state.folder == null &&
                state.seriesGroups.isNotEmpty() && state.viewMode == ViewMode.Grid
            val showIndexBar = state.viewMode == ViewMode.Grid && state.sort == LibrarySort.TITLE && (
                (state.mediaType != MediaType.SERIES && state.initialIndexMap.isNotEmpty()) ||
                    (seriesGroupGrid && state.seriesInitialIndexMap.isNotEmpty())
                )
            // Liquid Glass（spec §9）：三个滚动容器底 padding 追加玻璃底栏高度（IME 打开→0dp）
            val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
            // Liquid Glass（spec §9 Task 8）：滚动容器首屏 top 分量 = 悬浮顶簇高 + 失败横幅段
            // （rework8：横幅悬浮于顶簇收起组内、不再占内容布局，避让改由此 padding 承担）
            val listTop = contentTop
            // 2026-09-15 D4：剧集分段=聚合组网格（组卡点击 → selectFolder 直达该组全集）
            // 目录筛选激活时不聚合（选中的目录本身就是一部剧）→ 平铺全集（2026-09-15 交互修复）
            if (seriesGroupGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = NvSpacing.L,
                        top = listTop,
                        end = NvSpacing.L,
                        bottom = glassBottom,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                    verticalArrangement = Arrangement.spacedBy(NvSpacing.L),
                ) {
                    items(state.seriesGroups, key = { "${it.sourceId}:${it.dir}" }) { group ->
                        SeriesGroupCard(group, viewModel)
                    }
                }
            } else if (state.viewMode == ViewMode.Grid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    // 2026-09-15：删 80dp 底部垫（NavHost 已吃 Scaffold bottom inset，双份 inset 白损一行卡）
                    // 2026-09-16 UI 审计 P1：A-Z 侧条（28dp）显示时右端避让，第三列卡片不再被压住 8dp
                    contentPadding = PaddingValues(
                        start = NvSpacing.L,
                        top = listTop,
                        end = if (showIndexBar) NvSpacing.L + 28.dp else NvSpacing.L,
                        bottom = glassBottom,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                    verticalArrangement = Arrangement.spacedBy(NvSpacing.L),
                ) {
                    items(state.items, key = { it.item.id }) { row ->
                        MediaCard(state, row, viewModel)
                    }
                }
            } else {
                LibraryListView(
                    state = state,
                    viewModel = viewModel,
                    topPadding = listTop,
                    bottomPadding = glassBottom,
                    listState = listState,
                )
            }
            // 字母排序侧条：仅"标题 A→Z"显示；普通网格按条目、剧集组卡网格按剧名
            if (showIndexBar) {
                val indexMap = if (seriesGroupGrid) state.seriesInitialIndexMap else state.initialIndexMap
                if (indexMap.isNotEmpty()) {
                    NvAlphabetSidebar(
                        indexMap = indexMap,
                        onLetterSelected = { letter ->
                            indexMap[letter]?.let { index ->
                                scope.launch { gridState.scrollToItem(index) }
                            }
                        },
                        // 上缘随簇底、下缘让玻璃底栏（返工⑩-2：侧条总高收拢后在该可视域内垂直
                        // 居中——旧版整条撑满可贴屏底；收拢后若仍延至屏底，# 段会压进玻璃底栏）
                        // rework8：失败横幅悬浮于收起组后，上缘避让随之含横幅段（contentTop 同口径）
                        modifier = Modifier.fillMaxSize().padding(top = contentTop, bottom = glassBottom),
                    )
                }
            }
        }
    }
}

/** 2026-09-15 D4 剧集聚合组卡：首集真帧 + 「N 集」徽标 + 已看集数；点击直达该组全集（目录筛选） */
@Composable
private fun SeriesGroupCard(group: LibrarySeriesGroup, viewModel: LibraryViewModel) {
    NvMediaPosterCard(
        title = group.name,
        thumbnailPath = group.thumbnailPath,
        thumbState = if (group.thumbnailPath != null) ThumbState.DONE else ThumbState.FAILED,
        badge = pluralStringResource(R.plurals.lib_episode_badge, group.episodes, group.episodes),
        subtitle = if (group.watched > 0) {
            pluralStringResource(R.plurals.lib_watched_episodes, group.watched, group.watched)
        } else null,
        onClick = { viewModel.selectFolder(LibraryFolder(group.sourceId, group.dir)) },
        onLongClick = null,
        modifier = Modifier.widthIn(min = 110.dp),
    )
}

/** 网格卡片（C12 §4.3 状态机全在 NvMediaPosterCard 内） */
@Composable
private fun MediaCard(state: LibraryUiState, row: MediaItemWithWatch, viewModel: LibraryViewModel) {
    val context = LocalContext.current
    NvMediaPosterCard(
        title = row.item.scrapedTitle ?: row.item.name,
        thumbnailPath = row.item.scrapePosterPath?.takeIf { it.isNotBlank() } ?: row.item.thumbnailPath,
        thumbState = runCatching { ThumbState.valueOf(row.item.thumbState) }.getOrDefault(ThumbState.NONE),
        subtitle = LibraryViewModel.subtitleOf(row.item),
        progress = row.wPos?.let { p ->
            row.wDur?.takeIf { d -> d > 0 }?.let { d -> (p.toFloat() / d).coerceIn(0f, 1f) }
        },
        badge = if (LibraryViewModel.isWatched(row)) stringResource(R.string.lib_watched) else null,
        onNeedThumb = { viewModel.needThumb(row.item.id) },
        onClick = { playItem(context, viewModel, row) },
        onLongClick = { viewModel.showItemSheet(row) },
    )
}

/** 一期直达播放（C12 §4.4）：novax:// 或本地绝对路径；续播位置由 PlayerActivity 按历史键自理。
 *  BUG-E-T2-01（换集链路-B）：按同目录音视频构建播放队列——原实现只传单 url，媒体库起播后
 *  播放器上/下集按钮置灰、通知栏无 ▷|。与文件页共用 PlayerPlaylistHolder 交接机制。 */
internal fun playItem(context: android.content.Context, viewModel: LibraryViewModel, row: MediaItemWithWatch) {
    viewModel.playWithSiblingQueue(row) { url, queue ->
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            .putExtra(PlayerActivity.EXTRA_TITLE, row.item.scrapedTitle ?: row.item.name)
        if (queue.size > 1) {
            intent.putExtra(PlayerActivity.EXTRA_QUEUE_ID, PlayerPlaylistHolder.put(queue))
        }
        context.startActivity(intent)
    }
}

// ── 列表视图（72dp 行，左 56×84 缩略 + 信息）──
@Composable
private fun LibraryListView(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    topPadding: Dp,     // Liquid Glass Task 8：悬浮顶簇避让分量
    bottomPadding: Dp,
    listState: LazyListState,   // 观感返工①：屏级上提（顶栏渐显信号共用）
) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),

        state = listState,
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    ) {
        lazyItems(state.items, key = { it.item.id }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { playItem(context, viewModel, row) },
                        onLongClick = { viewModel.showItemSheet(row) },
                    )
                    .background(NvColors.Background)
                    .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S)
                    .height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NvMediaPosterCard(
                    title = row.item.scrapedTitle ?: row.item.name,
                    thumbnailPath = row.item.scrapePosterPath?.takeIf { it.isNotBlank() } ?: row.item.thumbnailPath,
                    thumbState = runCatching { ThumbState.valueOf(row.item.thumbState) }.getOrDefault(ThumbState.NONE),
                    onNeedThumb = { viewModel.needThumb(row.item.id) },
                    onClick = { playItem(context, viewModel, row) },
                    onLongClick = { viewModel.showItemSheet(row) },
                    modifier = Modifier.width(56.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = NvSpacing.L),
                ) {
                    Text(
                        row.item.scrapedTitle ?: row.item.name,
                        style = NvTypography.BodyLarge,
                        color = NvColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(NvSpacing.XS))
                    Text(
                        LibraryViewModel.subtitleOf(row.item) ?: row.item.path,
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!LibraryViewModel.isWatched(row)) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(NvColors.Primary),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(NvColors.Divider),
            )
        }
    }
}

// ── 长按弹层（C12 §4.4 顺序：播放 / 收藏 / 已看 / 重新抽帧 / 显示文件路径）──
@Composable
private fun ItemActionSheet(
    state: LibraryUiState,
    item: MediaItemWithWatch,
    viewModel: LibraryViewModel,
    onOpenDetail: (String) -> Unit,
    onGoToFiles: (() -> Unit)? = null,  // 2026-10-13 长按「打开所在文件夹」穿透（原图片空态死参数转正）
    onCast: (CastMedia) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // BUG-C-R7-04：用与落库同构的 mediaKeyOf（原 favoriteKeyOf，BUG-4 扩用更名）比对——裸 (sourceId, path) 对 LOCAL 条目
    // 恒不命中，弹层恒显「收藏」而点击实为取消收藏
    val isFavorite = viewModel.mediaKeyOf(item.item) in state.favoriteKeys
    val watched = LibraryViewModel.isWatched(item)
    val copiedText = stringResource(R.string.lib_copied)
    NvBottomSheet(onDismiss = viewModel::dismissItemSheet) {
        NvSheetListItem(text = stringResource(R.string.lib_play), selected = false) {
            viewModel.dismissItemSheet()
            playItem(context, viewModel, item)
        }
        // C13 §5 入口一：媒体库长按「投屏」（置「播放」之下）
        NvSheetListItem(text = stringResource(R.string.lib_cast), selected = false) {
            viewModel.dismissItemSheet()
            onCast(
                CastMedia(
                    sourceId = item.item.sourceId,
                    path = item.item.path,
                    title = item.item.scrapedTitle ?: item.item.name,
                    sizeBytes = item.item.sizeBytes,
                    durationMs = item.item.durationMs ?: 0L,
                    playUrl = viewModel.playUrlOf(item.item),
                    subtitlePath = null,
                ),
            )
        }
        // 2026-09-15 详情路由接真：详情页展示刮削海报/简介 + 同文件夹真集数
        NvSheetListItem(text = stringResource(R.string.lib_details), selected = false) {
            viewModel.dismissItemSheet()
            onOpenDetail(item.item.id.toString())
        }
        // 2026-10-13 索引条目必带 sourceId + 源内路径，恒可跳（spec §3.2）
        NvSheetListItem(text = stringResource(R.string.lib_open_folder), selected = false) {
            NvFilesDeepLink.put(NvFilesDeepLink.Target(item.item.sourceId, breadcrumbOf(item.item.path)))
            viewModel.dismissItemSheet()
            onGoToFiles?.invoke()
        }
        NvSheetListItem(
            text = stringResource(if (isFavorite) R.string.lib_unfavorite else R.string.lib_favorite),
            selected = isFavorite,
        ) { viewModel.toggleFavorite(item) }
        NvSheetListItem(
            text = stringResource(if (watched) R.string.lib_unmark_watched else R.string.lib_mark_watched),
            selected = watched,
        ) { viewModel.toggleWatchedItem(item) }
        NvSheetListItem(text = stringResource(R.string.lib_reextract_thumb), selected = false) { viewModel.reextractThumb(item) }
        NvSheetListItem(text = stringResource(R.string.lib_show_path), selected = false) {
            clipboard.setText(AnnotatedString(item.item.path))
            android.widget.Toast.makeText(context, copiedText, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.dismissItemSheet()
        }
        Text(
            item.item.path,
            Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            style = NvTypography.LabelMedium,
            color = NvColors.TextDisabled,
        )
    }
}
