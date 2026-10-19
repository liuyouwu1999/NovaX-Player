@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.ui.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.novax.app.R
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.ThumbState
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.player.PlayerActivity
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.nvBarCollapse
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvMediaPosterCard
import com.novax.app.ui.components.NvPosterCard
import com.novax.app.ui.components.NvPosterFallback
import com.novax.app.ui.components.NvScanStatusRow
import com.novax.app.ui.components.NvSectionHeader
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.text
import com.novax.app.ui.glass.LocalNvGlassEnv
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.files.NvFilesDeepLink
import com.novax.app.ui.files.breadcrumbOf
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.launch

// 2026-09-15 首页重设计：全部真实数据（继续观看 JOIN 真帧 / 最近添加 / 收藏真帧 / 媒体库概览），
// 零假交互（旧版假搜索 Toast / 假扫描骨架 / 假下拉刷新已删）。
@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onGoToFiles: (() -> Unit)? = null,
    onGoToSearch: (() -> Unit)? = null,
    onGoToLibrary: (() -> Unit)? = null,
    onGoToSettings: (() -> Unit)? = null,
    onOpenAddSource: (() -> Unit)? = null,   // 切文件 Tab + 一次性打开添加源向导（宿主组合实现）
    onOpenFolder: (() -> Unit)? = null,     // 2026-10-13 长按「打开所在文件夹」：切文件 Tab（宿主 switchTab）
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(context.applicationContext))
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    // 观感返工①（2026-10-15）：滚动渐显信号——首项滚离后顶栏玻璃渐显，静止态隐形（标题压内容）
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    // rework3（2026-10-15）：enterAlways 顶栏收起信号——下行越过阈值整条滑出，任一上行滑回
    val barCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(listState)
    // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（IME 打开→0dp 不留背景空带）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // 悬浮顶栏配方的 top inset = 状态栏 + 56dp 栏高（列表从顶栏下滚过）
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // Liquid Glass（decisions.md §4.5）：source 挂滚动内容容器，悬浮栏做兄弟（§4.1）
    val glassEnv = LocalNvGlassEnv.current
    var sheetContinueEntity by remember { mutableStateOf<WatchHistoryEntity?>(null) }
    var sheetItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    // 收藏条目长按（整实体：键 + kind + 标题一次拿到）
    var sheetRealFav by remember { mutableStateOf<com.novax.app.data.db.FavoriteEntity?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Liquid Glass（spec §9）页面翻转配方样板（Task 6-9 复用）：Box{ 全屏列表(top/topInset,
    // bottom/原padding+glassBottom) + NvTopBar 悬浮（最后声明盖在列表上）}
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        when {
            // S1 无源（由 sources 表真实派生）：引导去文件页添加源（空态同样避让悬浮顶栏）
            state.isEmptySource -> Box(
                Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                NvEmptyState(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.home_empty_source_title),
                    description = stringResource(R.string.home_empty_source_desc),
                    actionText = if (onOpenAddSource != null) stringResource(R.string.home_add_source) else null,
                    onAction = onOpenAddSource,
                )
            }
            else -> LazyColumn(
                state = listState,
                // background 在 source 之后：hazeSource 只录制链中位于其后的绘制——栏后仅有
                // 页背景的区域（底栏后方）也须有源像素，否则玻璃无源可采而失效（§4.5 落地实测）
                modifier = Modifier.fillMaxSize().nvGlassSource(glassEnv).background(NvColors.Background),
                contentPadding = PaddingValues(top = topInset, bottom = NvSpacing.XL + glassBottom),
                verticalArrangement = Arrangement.spacedBy(NvSpacing.S),
            ) {
                // 真实扫描进度（扫描器 RUNNING 时才出现）
                items(state.scanRunning, key = { "scan-${it.sourceId}" }) { p ->
                    NvScanStatusRow(
                        title = vm.scanTitleOf(p),
                        path = p.currentDir.takeIf { it.isNotBlank() },
                        onCancel = { vm.cancelAllScans() },
                    )
                }

                // ── 继续观看 ──
                if (state.continueRows.isNotEmpty()) {
                    item(key = "continue-header") { NvSectionHeader(stringResource(R.string.home_section_continue)) }
                    item(key = "continue-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = NvSpacing.L),
                            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                        ) {
                            items(state.continueRows, key = { it.key }) { row ->
                                NvLandscapeCard(
                                    title = row.title,
                                    chipText = row.chip?.text(),
                                    thumbPath = row.thumbPath,
                                    progress = row.progress,
                                    onPlay = { row.entity?.let { e -> NvPlaybackLauncher.resumeFrom(context, e) } },
                                    onLongClick = { row.entity?.let { sheetContinueEntity = it } },
                                    modifier = Modifier
                                        .fillParentMaxWidth(0.72f)
                                        .widthIn(max = 420.dp),
                                )
                            }
                        }
                    }
                } else {
                    item(key = "continue-guide") {
                        HomeGuideRow(
                            icon = Icons.Outlined.PlayArrow,
                            text = stringResource(R.string.home_guide_continue),
                            actionText = if (onGoToFiles != null) stringResource(R.string.home_go_files) else null,
                            onAction = onGoToFiles,
                        )
                    }
                }

                // ── 快捷入口（真实导航） ──
                item(key = "quick-tiles") {
                    QuickTilesRow(
                        mediaCount = state.kindCounts.values.sum(),
                        onGoToLibrary = onGoToLibrary,
                        onGoToFiles = onGoToFiles,
                        onGoToSearch = onGoToSearch,
                        onGoToSettings = onGoToSettings,
                    )
                }

                // ── 最近添加（真帧海报卡，可见项自动抽帧） ──
                if (state.recentRows.isNotEmpty()) {
                    item(key = "recent-header") { NvSectionHeader(stringResource(R.string.home_section_recent)) }
                    item(key = "recent-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = NvSpacing.L),
                            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                        ) {
                            items(state.recentRows, key = { "recent-${it.id}" }) { item ->
                                NvMediaPosterCard(
                                    // BUG-C-R7-10：优先刮削标题（与播放器标题/详情页同一口径）
                                    title = item.scrapedTitle?.takeIf { it.isNotBlank() } ?: item.name,
                                    // 2026-09-16 UI 审计 P2-7：与媒体库卡片同一副标题（分辨率·时长），两页卡片解剖一致
                                    subtitle = com.novax.app.ui.library.LibraryViewModel.subtitleOf(item),
                                    thumbnailPath = item.scrapePosterPath?.takeIf { it.isNotBlank() } ?: item.thumbnailPath,
                                    thumbState = runCatching {
                                        ThumbState.valueOf(item.thumbState)
                                    }.getOrDefault(ThumbState.NONE),
                                    badge = resolutionBadge(item.width, item.height),
                                    onClick = { playMediaItem(context, vm, item) },
                                    onNeedThumb = { vm.needThumb(item.id) },
                                    onLongClick = { sheetItem = item },
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                    }
                }

                // ── 收藏（真帧；空 → 整块隐藏） ──
                if (state.favoriteRows.isNotEmpty()) {
                    item(key = "fav-header") { NvSectionHeader(stringResource(R.string.home_section_favorites)) }
                    item(key = "fav-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = NvSpacing.L),
                            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                        ) {
                            items(state.favoriteRows, key = { it.key }) { row ->
                                FavoritePosterCard(
                                    row = row,
                                    onClick = { openRealFavorite(context, scope, row) },
                                    onLongClick = { row.entity?.let { sheetRealFav = it } },
                                )
                            }
                        }
                    }
                }

                // ── 媒体库概览（kind 计数 + 源数，点击进媒体库） ──
                vm.overviewLine(state.kindCounts)?.let { line ->
                    item(key = "overview") {
                        OverviewCard(
                            line = line,
                            sourceCount = state.sourceCount,
                            onClick = { onGoToLibrary?.invoke() },
                        )
                    }
                }
            }
        }

        // 悬浮层：最后声明盖在列表上（列表内容从顶栏下滚过，spec §9）
        NvTopBar(
            title = "NovaX",
            modifier = Modifier.nvBarCollapse(barCollapsed),   // rework3：下滑收起/上滑出现
            scrolled = scrolled,
            actions = listOf {
                IconButton(
                    onClick = { onGoToSearch?.invoke() },
                    Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Search, stringResource(R.string.home_cd_search), tint = NvColors.TextPrimary)
                }
            },
        )
    }

    // 继续观看卡长按菜单：移出续播窗口 / 删除记录 / 取消
    sheetContinueEntity?.let { e ->
        NvBottomSheet(onDismiss = { sheetContinueEntity = null }) {
            // 2026-10-13 首位（与历史页一致）；远程直链/mock 无文件夹概念 → 隐藏；onOpenFolder 未接线 → 隐藏（spec §3.2）
            if (onOpenFolder != null) vm.folderTargetOf(e.sourceId, e.path)?.let { target ->
                NvSheetListItem(stringResource(R.string.home_open_folder)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    NvFilesDeepLink.put(target)
                    onOpenFolder?.invoke()
                    sheetContinueEntity = null
                }
            }
            NvSheetListItem(stringResource(R.string.home_remove_from_continue)) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.removeFromContinueWatching(e)
                toast(context.getString(R.string.home_removed_from_continue))
                sheetContinueEntity = null
            }
            NvSheetListItem(stringResource(R.string.home_delete_entry)) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.deleteHistoryEntry(e)
                toast(context.getString(R.string.home_deleted_entry))
                sheetContinueEntity = null
            }
            NvSheetListItem(stringResource(R.string.home_cancel)) { sheetContinueEntity = null }
        }
    }

    // 最近添加/收藏条目长按菜单：收藏（真 kind）/ 重新抽帧 / 显示文件路径 / 取消
    sheetItem?.let { item ->
        val isFav = vm.isFavorite(item, state.realFavoriteKeys)
        val favoritedToast = stringResource(R.string.home_favorited_toast)
        val unfavoritedToast = stringResource(R.string.home_unfavorited_toast)
        NvBottomSheet(onDismiss = { sheetItem = null }) {
            NvSheetListItem(
                stringResource(if (isFav) R.string.home_unfavorite else R.string.home_favorite),
                selected = isFav,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val (keySourceId, keyPath) = vm.favoriteKeyOf(item)
                vm.toggleRealFavorite(
                    keySourceId,
                    keyPath,
                    item.name,
                    vm.watchKindOf(item),
                )
                toast(if (isFav) unfavoritedToast else favoritedToast)
                sheetItem = null
            }
            NvSheetListItem(stringResource(R.string.home_reextract_thumb)) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.reextractThumb(item)
                toast(context.getString(R.string.home_reextracting_toast))
                sheetItem = null
            }
            // 2026-10-13 索引条目必带 sourceId，恒可跳
            if (onOpenFolder != null) {
                NvSheetListItem(stringResource(R.string.home_open_folder)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    NvFilesDeepLink.put(NvFilesDeepLink.Target(item.sourceId, breadcrumbOf(item.path)))
                    onOpenFolder?.invoke()
                    sheetItem = null
                }
            }
            NvSheetListItem(stringResource(R.string.home_show_path)) {
                toast(item.path)
            }
            NvSheetListItem(stringResource(R.string.home_cancel)) { sheetItem = null }
        }
    }

    // 收藏条目长按菜单（kind 按收藏时记录的真实类型写入，不再恒 MOVIE）
    sheetRealFav?.let { fav ->
        val isFav = favoriteKey(fav.sourceId, fav.path) in state.realFavoriteKeys
        val favoritedToast = stringResource(R.string.home_favorited_toast)
        val unfavoritedToast = stringResource(R.string.home_unfavorited_toast)
        NvBottomSheet(onDismiss = { sheetRealFav = null }) {
            // 2026-10-13 首位；LOCAL 收藏存 (null, 绝对路径)，回配失败（源已删）→ 隐藏；onOpenFolder 未接线 → 隐藏（spec §3.2）
            if (onOpenFolder != null) vm.folderTargetOf(fav.sourceId, fav.path)?.let { target ->
                NvSheetListItem(stringResource(R.string.home_open_folder)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    NvFilesDeepLink.put(target)
                    onOpenFolder?.invoke()
                    sheetRealFav = null
                }
            }
            NvSheetListItem(
                stringResource(if (isFav) R.string.home_unfavorite else R.string.home_favorite),
                selected = isFav,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.toggleRealFavorite(fav.sourceId, fav.path, fav.title, fav.kind)
                toast(if (isFav) unfavoritedToast else favoritedToast)
                sheetRealFav = null
            }
        }
    }
}

// ---------- 播放/收藏点击（与库页同构：LOCAL→绝对路径，其余→novax://） ----------

private fun playMediaItem(context: Context, vm: HomeViewModel, item: MediaItemEntity) {
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_VIDEO_URL, vm.playUrlOf(item))
            // BUG-C-R7-10：标题优先刮削名，否则污染观看历史标题
            .putExtra(PlayerActivity.EXTRA_TITLE, item.scrapedTitle?.takeIf { it.isNotBlank() } ?: item.name),
    )
}

/** 收藏行点击：真文件 → 续播（有历史 seek，无则从头）；假数据影片（历史遗留收藏）忽略（mock 已退役） */
private fun openRealFavorite(context: Context, scope: kotlinx.coroutines.CoroutineScope, row: FavoriteRow) {
    val fav = row.entity ?: return
    if (NvPlaybackLauncher.isMockMediaId(fav.path)) return
    scope.launch {
        // BUG-E（死链收藏，T2-03/T3-03）：打开前校验目标可达——文件已删/已移时提示并
        // 顺手清掉死收藏，不再误导进入与目标无关的会话播放器
        val reachable = runCatching {
            if (fav.sourceId == null) {
                java.io.File(fav.path).isFile
            } else {
                NvGraph.mediaItemDao(context.applicationContext)
                    .findByKey(fav.sourceId, fav.path) != null
            }
        }.getOrDefault(true)
        if (!reachable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.home_dead_favorite_cleaned),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            runCatching { NvGraph.favoriteDao(context.applicationContext).deleteByKey(fav.sourceId, fav.path) }
            return@launch
        }
        val hist = NvGraph.historyDao(context.applicationContext)
            .getForResume(fav.sourceId, fav.path)
        val entity = hist ?: WatchHistoryEntity(
            sourceId = fav.sourceId,
            sourceType = null,
            path = fav.path,
            title = fav.title,
            kind = fav.kind,
            positionMs = 0L,
            durationMs = 0L,
            watchedAt = 0L,
        )
        NvPlaybackLauncher.resumeFrom(context, entity)
    }
}

// ---------- 小组件 ----------

/** 空态轻量引导行（替代旧全屏 NvEmptyState：首屏不再被单个空态占满） */
@Composable
private fun HomeGuideRow(
    icon: ImageVector,
    text: String,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Surface(
        color = NvColors.Surface,
        shape = NvShape.Poster,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NvSpacing.L),
    ) {
        Row(
            Modifier.padding(horizontal = NvSpacing.M, vertical = NvSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = NvColors.TextSecondary)
            Spacer(Modifier.width(NvSpacing.S))
            Text(
                text,
                Modifier.weight(1f),
                style = NvTypography.LabelMedium,
                color = NvColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionText != null && onAction != null) {
                androidx.compose.material3.TextButton(onClick = onAction) {
                    Text(actionText, style = NvTypography.LabelMedium, color = NvColors.Primary)
                }
            }
        }
    }
}

/** 快捷入口行：媒体库 / 文件 / 搜索 / 设置（真实导航，无假按钮） */
@Composable
private fun QuickTilesRow(
    mediaCount: Int,
    onGoToLibrary: (() -> Unit)?,
    onGoToFiles: (() -> Unit)?,
    onGoToSearch: (() -> Unit)?,
    onGoToSettings: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 2026-09-20 间距节奏调整：与上方内容行拉开（8dp 行距 + 16dp = 24dp，用户反馈贴太近）
            .padding(top = NvSpacing.L)
            .padding(horizontal = NvSpacing.L),
        horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
    ) {
        QuickTile(
            Modifier.weight(1f),
            icon = Icons.Outlined.VideoLibrary,
            label = stringResource(R.string.home_library),
            hint = if (mediaCount > 0) {
                pluralStringResource(R.plurals.home_media_count, mediaCount, mediaCount)
            } else {
                stringResource(R.string.home_browse_hint)
            },
            onClick = onGoToLibrary,
        )
        QuickTile(
            Modifier.weight(1f),
            icon = Icons.Outlined.Folder,
            label = stringResource(R.string.home_files),
            hint = stringResource(R.string.home_browse_sources),
            onClick = onGoToFiles,
        )
        QuickTile(
            Modifier.weight(1f),
            icon = Icons.Outlined.Search,
            label = stringResource(R.string.home_search),
            hint = stringResource(R.string.home_find_titles),
            onClick = onGoToSearch,
        )
        QuickTile(
            Modifier.weight(1f),
            icon = Icons.Outlined.Settings,
            label = stringResource(R.string.home_settings),
            hint = stringResource(R.string.home_preferences),
            onClick = onGoToSettings,
        )
    }
}

@Composable
private fun QuickTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    hint: String,
    onClick: (() -> Unit)?,
) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        color = NvColors.Surface,
        shape = NvShape.Poster,
        modifier = modifier.height(76.dp),
    ) {
        Column(
            Modifier.padding(horizontal = NvSpacing.S, vertical = NvSpacing.S),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, label, Modifier.size(22.dp), tint = NvColors.Primary)
            Spacer(Modifier.height(NvSpacing.XS))
            Text(
                label,
                style = NvTypography.LabelMedium, color = NvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,   // 2026-10-22 i18n：长标签截断出省略号
            )
            Text(
                hint,
                style = NvTypography.LabelMedium,   // 2026-09-16 UI 审计 P2：10sp 偏小，提为 12sp
                color = NvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 媒体库概览卡（kind 计数 + 源数；纯本地 SQL，点击进媒体库 Tab） */
@Composable
private fun OverviewCard(line: String, sourceCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = NvColors.Surface,
        shape = NvShape.Poster,
        modifier = Modifier
            .fillMaxWidth()
            // 2026-09-20 间距节奏调整：与上方收藏行拉开（8dp 行距 + 16dp = 24dp，与快捷磁贴同型同距）
            .padding(top = NvSpacing.L)
            .padding(horizontal = NvSpacing.L),
    ) {
        Row(
            Modifier.padding(horizontal = NvSpacing.M, vertical = NvSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.VideoLibrary,
                null,
                Modifier.size(22.dp),
                tint = NvColors.TextSecondary,
            )
            Spacer(Modifier.width(NvSpacing.M))
            Column(Modifier.weight(1f)) {
                Text(line, style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    pluralStringResource(R.plurals.home_source_count_line, sourceCount, sourceCount),
                    style = NvTypography.LabelSmall,
                    color = NvColors.TextSecondary,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                Modifier.size(20.dp),
                tint = NvColors.TextSecondary,
            )
        }
    }
}

// ---------- 卡片 ----------

/** 收藏行卡片：真帧（JOIN media_items）→ 首字母渐变兜底 */
@Composable
private fun FavoritePosterCard(
    row: FavoriteRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    NvPosterCard(
        title = row.title,
        subtitle = row.subtitle ?: "",
        posterFile = row.thumbPath?.let { java.io.File(it) },
        posterUrl = row.posterUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(110.dp),
    )
}

// §1.2 16:9 继续观看大卡（真帧/渐变兜底 + ChipPill + 底部进度条 + 40dp 播放小按钮）
@Composable
private fun NvLandscapeCard(
    title: String,
    chipText: String?,
    thumbPath: String?,
    progress: Float,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(NvShape.Poster)
            .background(NvColors.Surface)
            .combinedClickable(onClick = onPlay, onLongClick = onLongClick),
    ) {
        if (thumbPath != null) {
            // 2026-09-16 UI 审计 P2-6：加载门控——真帧 onLoad 前 crossfade 渐显，
            // 兜底字母不再裸露在加载中/失败态（与 NvPosterCard 同一方案）
            NvPosterFallback(title, Modifier.fillMaxSize())
            var loaded by remember(thumbPath) { mutableStateOf(false) }
            val imageAlpha by animateFloatAsState(
                targetValue = if (loaded) 1f else 0f,
                animationSpec = tween(NvMotion.DurationNormal),
                label = "heroImageAlpha",
            )
            AsyncImage(
                model = java.io.File(thumbPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
                onSuccess = { loaded = true },
            )
        } else {
            NvPosterFallback(title, Modifier.fillMaxSize())
        }
        // 2026-09-28 浅色模式：深色渐变遮罩只叠在真缩略图上（压图需要），
        // 兜底态不再叠——浅色下深色 scrim 压浅灰占位会发闷发脏
        if (thumbPath != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to NvColors.Scrim,
                        ),
                    ),
            )
        }
        // end=64dp 避让右下角 40dp 续播按钮：长标题不再钻到半透明按钮底下截断。
        // 2026-09-28 浅色模式：压缩略图时固定白色系+深色底（与主题无关，黑字压深色图不可读）；
        // 兜底态无遮罩，改走主题 token（浅色黑字/橙字）。
        val onImage = thumbPath != null
        Column(Modifier.align(Alignment.BottomStart).padding(start = NvSpacing.M, end = 64.dp, bottom = NvSpacing.M)) {
            Text(
                title,
                style = NvTypography.BodyMedium,
                color = if (onImage) Color.White else NvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            chipText?.let {
                Box(
                    Modifier
                        .padding(top = NvSpacing.XS)
                        .background(if (onImage) NvColors.Scrim else NvColors.PrimaryContainer, NvShape.Badge)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        it,
                        style = NvTypography.LabelSmall,
                        color = if (onImage) Color.White else NvColors.Primary,
                    )
                }
            }
        }
        Surface(
            onClick = onPlay,
            shape = CircleShape,
            // 2026-09-28 浅色模式：原硬编码深色圆底（0xFF1F1F29）是深色模式遗留，
            // 浅色下像破洞——改品牌橙圆底白标，双主题压图/压占位都和谐
            color = NvColors.Primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(NvSpacing.M)
                .size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, stringResource(R.string.home_cd_resume), tint = Color.White)
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp),
            color = NvColors.Primary,
            trackColor = NvColors.Track,
        )
    }
}
