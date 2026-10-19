package com.novax.app.ui.detail

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TvOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import coil3.compose.AsyncImage
import com.novax.app.data.MockMediaRepository
import com.novax.app.data.NvGraph
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.WatchKind
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvFilterChip
import com.novax.app.ui.components.NvPosterFallback
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvSectionHeader
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvSkeletonBox
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.launch

/**
 * C4 详情页（媒体详情）。
 * onPlay 参数为播放目标 id：电影为 mediaId，剧集选集为 "mediaId#SxEy"。
 */
@Composable
fun DetailScreen(
    mediaId: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(mediaId) { vm.load(mediaId) }
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        when (val s = state) {
            NvDetailUiState.Loading -> NvDetailSkeleton()
            is NvDetailUiState.Error -> NvErrorState(
                message = s.message,
                onRetry = { vm.rescrape() },
                modifier = Modifier.align(Alignment.Center).padding(NvSpacing.L),
            )
            is NvDetailUiState.Success -> NvDetailContent(s, vm, onPlay, onBack)
        }
    }
}


// ---- 主内容：单列 LazyColumn，hero 头图 + 信息折叠 ----

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NvDetailContent(
    state: NvDetailUiState.Success,
    vm: DetailViewModel,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val showTopBar by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    // C11 §3.2 详情页收藏：真实条目用与库页同构的 favoriteKey（BUG-C-R7-01：原实现对真实
    // 条目也写死 (null, mediaId)，收藏后成首页死卡、三页心形态互斥）；mock 条目沿用旧语义
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val favoriteDao = remember { NvGraph.favoriteDao(context.applicationContext) }
    val favoriteDb = remember { com.novax.app.data.db.MediaDb.get(context.applicationContext) }
    val favKey = state.favoriteKey ?: (null to state.mediaId)
    val favoriteEntry by remember(state.mediaId) {
        favoriteDao.observeByKey(favKey.first, favKey.second)
    }.collectAsState(initial = null)

    fun toggleFavorite() {
        val adding = favoriteEntry == null
        val kind = if (MockMediaRepository.byId(state.mediaId)?.kind == "series") {
            WatchKind.SERIES.name
        } else {
            WatchKind.MOVIE.name
        }
        scope.launch {
            runCatching {
                // 事务内查有删/查无插（upsert 语义），杜绝并发/连点产生重复收藏行
                favoriteDb.withTransaction {
                    if (adding) {
                        favoriteDao.insert(
                            FavoriteEntity(
                                sourceId = favKey.first,
                                path = favKey.second,
                                title = state.title,
                                kind = kind,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        favoriteDao.deleteByKey(favKey.first, favKey.second)
                    }
                }
            }
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(context, if (adding) "已加入收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }

    var showRescrapeSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var confirmEpisode by remember { mutableStateOf<EpisodeItem?>(null) }

    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        // Liquid Glass（decisions.md §4.5 Task 9 收尾）：source 挂 LazyColumn，Crossfade 悬浮 NvTopBar
        // 做兄弟——滚过 hero 后顶栏透出 hero 玻璃。页背景留在根 Box（LazyColumn 无自绘背景，§4.5a）。
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nvGlassSource(com.novax.app.ui.glass.LocalNvGlassEnv.current)) {
            // ---- 1. Hero 头图（16:9，长按 → 重新刮削菜单） ----
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showRescrapeSheet = true },
                        ),
                ) {
                    if (state.backdropUrl != null) {
                        AsyncImage(
                            state.backdropUrl, null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // S3 刮削失败降级：首字母渐变，绝不留白
                        NvPosterFallback(state.title, Modifier.fillMaxSize())
                    }
                    // 底部渐变融入背景
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.4f)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, NvColors.Background)),
                            ),
                    )
                    // 顶部轻微压暗保证状态栏可读
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.25f)
                            .background(
                                Brush.verticalGradient(listOf(NvColors.Background.copy(alpha = 0.6f), Color.Transparent)),
                            ),
                    )
                    // 悬浮返回按钮（48dp 热区）
                    // 2026-09-28 浅色模式：深底白箭头只在压真背景图时用（压图惯例）；
                    // 兜底占位上改主题色调圆底+主题色箭头——浅色下深灰圆不再突兀
                    val hasBackdrop = state.backdropUrl != null
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(NvSpacing.S)
                            .size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            modifier = Modifier
                                .background(
                                    if (hasBackdrop) NvColors.Scrim.copy(alpha = 0.4f)
                                    else NvColors.Surface.copy(alpha = 0.72f),
                                    CircleShape,
                                )
                                .padding(NvSpacing.S),
                            tint = if (hasBackdrop) Color.White.copy(alpha = 0.9f) else NvColors.TextPrimary,
                        )
                    }
                    if (state.scraping) {
                        // S9 正在重新刮削
                        CircularProgressIndicator(
                            Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                            color = NvColors.Primary,
                        )
                    }
                }
            }

            // ---- 2. 悬浮播放按钮 + 更多 ----
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = NvSpacing.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val playLabel = when {
                        state.resume != null && state.isSeries && !state.noEpisodesAtAll ->
                            "▶ 继续播放 ${state.resume.label} · 剩余 ${state.resume.remainingMin} 分钟"
                        state.resume != null -> "▶ 继续播放 · 剩余 ${state.resume.remainingMin} 分钟"
                        state.isSeries && !state.noEpisodesAtAll -> "▶ 播放 S${state.tv?.selectedSeason ?: 1}E1"
                        else -> "▶ 播放"
                    }
                    NvPrimaryButton(
                        text = playLabel,
                        onClick = {
                            val ep = state.tv?.episodes?.firstOrNull { it.progress != null }
                                ?: state.tv?.episodes?.firstOrNull()
                            onPlay(if (ep != null) "${state.mediaId}#S${ep.season}E${ep.number}" else state.mediaId)
                        },
                        modifier = Modifier.weight(1f),
                        loading = state.scraping,
                    )
                    Spacer(Modifier.width(NvSpacing.S))
                    IconButton(onClick = { showMoreSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, "更多", tint = NvColors.TextPrimary)
                    }
                }
            }

            // ---- 3. 标题 / 元信息 ----
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        state.title,
                        style = NvTypography.TitleLarge,
                        color = NvColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star, null,
                            Modifier.size(16.dp), tint = NvColors.Primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            state.metaLine,
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 剧集追更状态行
                    state.chasingLine?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = NvTypography.LabelSmall, color = NvColors.TextDisabled)
                    }
                }
            }

            // ---- 4. 简介（展开/收起） ----
            item { NvExpandableOverview(state.overview, state.fileName) }

            // ---- 5. 演职人员横滑 ----
            if (state.cast.isNotEmpty()) {
                item {
                    NvSectionHeader("演职人员")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
                    ) {
                        items(state.cast) { c -> NvCastItem(c) }
                    }
                    Spacer(Modifier.height(NvSpacing.S))
                }
            }

            // ---- 6. 剧集选集（季 Chip + 每集行） ----
            state.tv?.let { tv ->
                if (!state.noEpisodesAtAll) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
                        ) {
                            items(tv.seasons) { season ->
                                NvFilterChip(
                                    label = "第 ${season.number} 季",
                                    selected = season.number == tv.selectedSeason,
                                    onClick = { vm.selectSeason(season.number) },
                                )
                            }
                        }
                        Spacer(Modifier.height(NvSpacing.S))
                    }
                    if (tv.episodes.isEmpty()) {
                        // S6 某季无选集
                        item {
                            NvEmptyState(
                                icon = Icons.Outlined.TvOff,
                                title = "本季暂无选集",
                                modifier = Modifier.heightIn(min = 160.dp),
                            )
                        }
                    } else {
                        items(tv.episodes, key = { "${it.season}-${it.number}" }) { epItem ->
                            NvEpisodeRow(
                                epItem,
                                onClick = {
                                    // 续播点 > 2 分钟时先确认
                                    if ((epItem.resumeMs ?: 0L) > 2 * 60_000L) confirmEpisode = epItem
                                    else onPlay("${state.mediaId}#S${epItem.season}E${epItem.number}")
                                },
                            )
                        }
                    }
                } else {
                    // S7 整部无任何集：降级为电影式布局
                    item {
                        Text(
                            "未解析到分集",
                            Modifier.padding(horizontal = 16.dp),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextDisabled,
                        )
                    }
                }
            }

            // ---- 7. 底部技术信息 badge 区 ----
            item { NvTechInfoSection(state.techInfo) }
            item { Spacer(Modifier.height(32.dp)) }
        }

        // 滚过 hero 后淡入 NvTopBar
        Crossfade(targetState = showTopBar, animationSpec = tween(250), label = "topBarFade") { visible ->
            if (visible) NvTopBar(title = state.title, onBack = onBack)
        }
    }

    // 3.3 长按头图 → 重新刮削菜单（2026-09-15：重新刮削为真实现；假「手动匹配」删除）
    if (showRescrapeSheet) {
        NvBottomSheet(onDismiss = { showRescrapeSheet = false }) {
            // BUG-C-R7-11：删去与「重新刮削」行为完全相同的重复菜单项
            NvSheetListItem("重新刮削") { showRescrapeSheet = false; vm.rescrape() }
        }
    }
    // 更多菜单（C11 §5.6 修订：收藏永远第 1 项 / 重新刮削 / 分享）
    if (showMoreSheet) {
        val isFavorite = favoriteEntry != null
        NvBottomSheet(onDismiss = { showMoreSheet = false }) {
            NvSheetListItem(
                if (isFavorite) "取消收藏" else "加入收藏",
                selected = isFavorite,
                onClick = {
                    showMoreSheet = false
                    toggleFavorite()
                },
            )
            NvSheetListItem("重新刮削") { showMoreSheet = false; vm.rescrape() }
            NvSheetListItem("分享") { showMoreSheet = false; Toast.makeText(context, "分享功能开发中", Toast.LENGTH_SHORT).show() }
        }
    }
    // 3.2 选集续播确认
    confirmEpisode?.let { epItem ->
        val minutes = (epItem.resumeMs ?: 0L) / 60_000L
        // 2026-09-15 修复（假选项）：「从头播放」与「继续播放」行为完全相同 → 统一为续播确认框
        NvConfirmDialog(
            title = "从 ${epItem.resumeMs?.toPlaybackLabel()} 继续播放？",
            message = "「E${epItem.number} · ${epItem.title}」已观看到 ${minutes} 分钟。",
            confirmText = "继续播放",
            dismissText = "取消",
            onConfirm = {
                onPlay("${state.mediaId}#S${epItem.season}E${epItem.number}")
                confirmEpisode = null
            },
            onDismiss = { confirmEpisode = null },
        )
    }
}

private fun Long?.toPlaybackLabel(): String {
    val ms = this ?: 0L
    val m = ms / 60_000L
    val s = ms / 1000L % 60L
    return "%d:%02d".format(m, s)
}

// ---- 简介展开/收起（250ms 动画）；刮削失败降级文件名直出 ----

@Composable
private fun NvExpandableOverview(overview: String?, fileName: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = NvSpacing.S)) {
        if (overview != null) {
            Text(
                overview,
                style = NvTypography.BodyLarge,
                color = NvColors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize(tween(250, easing = NvMotion.CurveDecelerate)),
            )
            NvTextButton(text = if (expanded) "收起" else "展开", onClick = { expanded = !expanded })
        } else {
            // S3/S4 降级：文件名直出 + 刮削指引（2026-09-15：手动匹配假实现删除，重新刮削为真）
            Text(fileName, style = NvTypography.BodyLarge, color = NvColors.TextSecondary)
            Spacer(Modifier.height(NvSpacing.S))
            Text(
                "未能识别影片信息 · 可在设置-媒体库配置 TMDB 后「重新刮削」",
                style = NvTypography.LabelMedium,
                color = NvColors.TextDisabled,
            )
        }
    }
}


// ---- 演职人员项：圆形头像 64dp + 姓名 + 角色 ----

@Composable
private fun NvCastItem(c: CastMember) {
    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(NvColors.Skeleton)) {
            if (c.avatarUrl != null) {
                AsyncImage(c.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                NvPosterFallback(c.name, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            c.name, style = NvTypography.LabelMedium, color = NvColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            c.role, style = NvTypography.LabelSmall, color = NvColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 剧集选集行：16:9 缩略图 + 进度条 + 已看标记 ----

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NvEpisodeRow(epItem: EpisodeItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .background(Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(NvShape.Input)
                .background(NvColors.Skeleton),
        ) {
            AsyncImage(epItem.thumbUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            epItem.progress?.let { p ->
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = NvColors.Primary,
                    trackColor = NvColors.SurfaceElevated,
                )
            }
            if (epItem.watched) {
                Icon(
                    Icons.Outlined.CheckCircle, "已看",
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp),
                    tint = NvColors.Success,
                )
            }
        }
        Spacer(Modifier.width(NvSpacing.M))
        Column(Modifier.weight(1f)) {
            Text(
                "E${epItem.number} · ${epItem.title}",
                style = NvTypography.BodyMedium, color = NvColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("${epItem.durationMin} 分钟", style = NvTypography.LabelSmall, color = NvColors.TextDisabled)
            Text(
                epItem.overview,
                style = NvTypography.LabelSmall, color = NvColors.TextSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- 底部技术信息 badge 区（FlowRow 徽章 + 详细信息折叠） ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NvTechInfoSection(info: TechInfo) {
    var showDetails by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = NvSpacing.S)) {
        NvSectionHeader("媒体信息")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
            verticalArrangement = Arrangement.spacedBy(NvSpacing.S),
        ) {
            info.badges.forEach { badge ->
                Text(
                    badge,
                    Modifier
                        .clip(NvShape.Badge)
                        .background(NvColors.SurfaceElevated)
                        .padding(horizontal = NvSpacing.S, vertical = 4.dp),
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                )
            }
        }
        if (info.details.isNotEmpty()) {
            NvTextButton(text = if (showDetails) "收起详细信息" else "详细信息", onClick = { showDetails = !showDetails })
            if (showDetails) {
                info.details.forEach { (k, v) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(k, Modifier.width(88.dp), style = NvTypography.LabelMedium, color = NvColors.TextDisabled)
                        Text(v, style = NvTypography.LabelMedium, color = NvColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(color = NvColors.Divider)
        }
    }
}

// ---- 加载骨架（S2）：hero 骨架 + 文本条 + 演员圆骨架 ----

@Composable
private fun NvDetailSkeleton() {
    Column(Modifier.fillMaxSize()) {
        NvSkeletonBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 0.dp)
        Spacer(Modifier.height(16.dp))
        NvSkeletonBox(Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.6f).height(24.dp))
        Spacer(Modifier.height(NvSpacing.S))
        NvSkeletonBox(Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.4f).height(14.dp))
        Spacer(Modifier.height(16.dp))
        NvSkeletonBox(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(60.dp))
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.M),
        ) {
            repeat(6) { NvSkeletonBox(Modifier.size(64.dp), corner = 32.dp) }
        }
    }
}
