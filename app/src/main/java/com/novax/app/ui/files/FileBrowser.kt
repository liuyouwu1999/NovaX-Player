@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.novax.app.ui.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.data.NvPaths
import com.novax.app.data.db.SourceType
import com.novax.app.data.smb.SmbShareRoot
import com.novax.app.data.scanner.ScanRoots
import com.novax.app.data.scanner.ScanRules
import com.novax.app.data.cast.CastMedia
import com.novax.app.data.cast.CastSessionHolder
import com.novax.app.ui.cast.NvCastDeviceSheet
import com.novax.app.ui.cast.NvCastMeteredDialogHost
import com.novax.app.ui.cast.rememberNvCastGateState
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.components.NvCheckRow
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvFilterChipRow
import com.novax.app.ui.components.NvHighlightText
import com.novax.app.ui.components.NvInlineBanner
import com.novax.app.ui.components.NvBannerKind
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvSecondaryButton
import com.novax.app.ui.components.NvSearchBar
import com.novax.app.ui.components.NvSelectionAction
import com.novax.app.ui.components.NvSelectionBar
import com.novax.app.ui.components.NvSelectionCircle
import com.novax.app.ui.components.NvSelectionTopBar
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvSkeletonListRow
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvSwitchRow
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.viewers.NvFileRouter
import com.novax.app.ui.viewers.ViewerActivity
import com.novax.app.ui.viewers.ViewerShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

// ============================================================
// 从 FilesScreen.kt 机械切出（2026-09-28 审计 A：巨型文件拆分，零逻辑改动，private→internal）
// ============================================================

@Composable
internal fun FileBrowserScreen(
    state: NvBrowserUiState,
    vm: FilesViewModel,
    onPlay: (String) -> Unit,
    onPlayInFolder: (String, List<String>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val favoriteKeys by vm.favoriteKeys.collectAsState()
    val historyByKey by vm.historyByKey.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val dirsFirst by vm.dirsFirst.collectAsState()
    // 排除目录横幅数据源：Room sources Flow，dao.update 后自动刷新
    val sourcesForExclusion by vm.sources.collectAsState()
    var actionEntry by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    var castEntry by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    var infoEntry by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    var renameEntry by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    var deleteEntry by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    // C9 §六：未知类型弹层（文件信息 + 用其他应用打开 + 取消）；外发先拷 cache，行内进度 + 再点取消
    var unknownEntry by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    var confirmBigShare by remember(state.sourceId) { mutableStateOf<NvFileEntry?>(null) }
    // 媒体库排除目录（2026-09-15）：多选目录「排除出媒体库」待确认集；Room Flow 驱动横幅回显
    val excludedDirs = remember(sourcesForExclusion, state.sourceId) {
        sourcesForExclusion.firstOrNull { it.id == state.sourceId }
            ?.entity?.excludedDirs
            ?.let { ScanRoots.parse(it).map { p -> p.trimEnd('/') } }
            ?: emptyList()
    }
    var confirmExcludeDirs by remember(state.sourceId) { mutableStateOf<List<String>?>(null) }
    // C5 二期："..."过滤/排序弹层、多选批量删除确认
    var showToolsSheet by remember(state.sourceId) { mutableStateOf(false) }
    var confirmDeleteSelection by remember(state.sourceId) { mutableStateOf(false) }
    val shareScope = rememberCoroutineScope()
    var shareJob by remember { mutableStateOf<Job?>(null) }
    var shareProgress by remember { mutableStateOf<Float?>(null) }   // null = 未在下载

    /** 「用其他应用打开」：novax 先拷 cache（进度可取消），再 FileProvider + ACTION_VIEW */
    fun launchShare(entry: NvFileEntry, uri: String) {
        if (shareJob?.isActive == true) return
        shareJob = shareScope.launch {
            shareProgress = null
            try {
                val file = ViewerShare.materialize(
                    context, uri, entry.name, "share",
                    knownTotal = if (entry.size > 0) entry.size else -1L,
                ) { p -> shareProgress = p }
                shareProgress = null
                if (!ViewerShare.share(context, file, ViewerShare.mimeTypeOf(entry.name))) {
                    Toast.makeText(context, "未找到可处理此类型的应用", Toast.LENGTH_SHORT).show()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                shareProgress = null
                Toast.makeText(context, "打开失败：${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 本地源无权限 → 「去授权」触发运行时权限请求
    val storagePermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    // 观感返工①（2026-10-15）：目录列表滚动态上提（原 LazyColumn 无 state），仅常规态 NvTopBar
    // 渐显接线（多选/搜索态顶栏维持现状）；首项滚离后顶栏渐显，静止态隐形
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.retryListing() }
    // BUG-C-R6-07：R+ 的「去授权」必须走「所有文件访问」系统页——READ_MEDIA_VIDEO 只授媒体读，
    // 非媒体文件（RMVB/外挂字幕）会被 FUSE 静默隐藏
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) vm.retryListing()
    }

    val isLocalSource = state.source?.protocol == NvProtocol.Local
    val currentDirPath = state.breadcrumb.fold("") { acc, seg -> NvPaths.join(acc, seg) }

    // C5 二期组合管线：search（并入子目录命中）→ type filter（文件夹恒显示）→ sort（纯函数单测锁定）
    val activeQuery = NvListQuery(
        enabledTypes = state.enabledTypes,
        sortMode = sortMode,
        dirsFirst = dirsFirst,
        searchQuery = if (state.searchActive) state.searchQuery else "",
        searchActive = state.searchActive,
    )
    // H-8（2026-10-18 发布终审）：管线结果 remember 化——applyListPipeline 是 filter+
    // O(n log n) 排序，此前每次重组（搜索框每敲一个字符、多选每点一次、收藏/历史 Flow 每次
    // 发射）都对整目录重算；NvListQuery 为 data class，仅其值/entries 实例/searchSubResults
    // 变化才失效。派生列表同理随源记忆，数千条目录的搜索输入卡顿由此消除
    val visible = remember(activeQuery, state.entries, state.searchSubResults) {
        applyListPipeline(state.entries, activeQuery, state.searchSubResults)
    }
    // BUG-E-T2-02：「列表显示文件大小」开关的消费端（设置写入 KEY_UI_SHOW_FILE_SIZE，
    // 原实现文件行从未读取，关闭后仍显示大小）
    val playerPrefs by com.novax.app.player.rememberPlayerPrefs()
    val showFileSize = playerPrefs?.get(com.novax.app.player.KEY_UI_SHOW_FILE_SIZE) ?: true
    val visiblePaths = remember(visible) { visible.map { it.path } }
    val selectedEntries = remember(state.entries, state.searchSubResults, state.selectedPaths) {
        (state.entries + state.searchSubResults)
            .filter { it.path in state.selectedPaths }
            // BUG-C-R6-08：子目录搜索命中会与当前目录条目重复进两个列表，不去重删除确认框计数翻倍
            .distinctBy { it.path }
    }
    val selectedVideos = remember(visible, state.selectedPaths) {
        visible.filter {
            !it.isDir && NvFileRouter.kindOf(it.name) == NvFileRouter.Kind.VIDEO && it.path in state.selectedPaths
        }
    }
    // 「用其他应用打开」可用条件：恰选中 1 个本地非视频文件（C5 §5.4）
    val openWithEnabled = selectedEntries.size == 1 && isLocalSource &&
        !selectedEntries[0].isDir && NvFileRouter.kindOf(selectedEntries[0].name) != NvFileRouter.Kind.VIDEO

    // Liquid Glass（spec §9 悬浮配方）：顶栏三态整体 as 悬浮玻璃层放 Box 末尾，
    // 内容经 topInset 避让；多选态列表直通屏顶、行内容从多选栏下滚过透出模糊
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            if (!state.selectionMode) {
                // 面包屑等页头随悬浮顶栏下移避让（topInset = 状态栏 + 56dp 栏高）
                Column(Modifier.padding(top = topInset)) {
                    // 面包屑：「根」(depth=0) 回源根目录（listDir，不关浏览器）；其余段回对应层级
                    NvBreadcrumb(listOf("根") + state.breadcrumb, onSelect = { depth ->
                        if (depth <= 0) vm.listDir(state.sourceId, emptyList())
                        else vm.onBreadcrumbNavigate(depth - 1)
                    })
                    // C5 §5.1 激活过滤态回显：「已过滤 · 视频+图像」Chip，点 X 重置
                    state.enabledTypes
                        .takeIf { !it.containsAll(allFileTypes) }
                        ?.let { enabled ->
                            NvFilterChipRow(
                                chips = listOf("已过滤 · ${filterSummary(enabled)}" to { vm.resetTypeFilter() }),
                            )
                        }
                    // 媒体库排除目录回显：当前目录在排除集内 → 一键恢复收录（重扫后重新入索引）
                    if (ScanRules.isUnderExcludedDir(currentDirPath, excludedDirs)) {
                        NvFilterChipRow(
                            chips = listOf("此目录已从媒体库排除 · 点按恢复收录" to {
                                vm.setDirsExcluded(state.sourceId, listOf(currentDirPath), excluded = false)
                                Toast.makeText(context, "已恢复收录，正在更新媒体索引…", Toast.LENGTH_SHORT).show()
                            }),
                        )
                    }
                    // 5.3 「含子目录」开关 + 搜索进度
                    if (state.searchActive) {
                        NvSwitchRow(
                            title = "含子目录",
                            subtitle = if (state.searchIncludeSubs) "深度 ≤$SEARCH_MAX_DEPTH · 最多 $SEARCH_RESULT_LIMIT 条" else null,
                            checked = state.searchIncludeSubs,
                            onChecked = vm::onSearchIncludeSubsChange,
                            showDivider = false,
                        )
                        if (state.searching) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = NvColors.Primary, strokeWidth = 2.dp)
                                Spacer(Modifier.width(NvSpacing.S))
                                Text("正在搜索子目录…", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                            }
                        }
                    }
                }
            }
            // 列表主体（过滤/搜索后为空各有专属空态）
            Box(Modifier.weight(1f)) {
                val searchNeedle = if (state.searchActive) state.searchQuery.trim() else ""
                // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（IME 打开→0dp）
                val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
                // Liquid Glass（decisions.md §4.5）：source 挂列表滚动容器，三态顶栏/面包屑在 Column 上方做兄弟
                val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
                when {
                    state.errorMessage != null -> NvErrorState( // 2.7 断连/错误
                        title = "无法读取目录",
                        message = state.errorMessage,
                        retrying = state.loading,
                        onRetry = vm::retryListing,
                    )
                    state.noPermission -> NvEmptyState( // 2.8 无权限
                        Icons.Outlined.Lock, "需要存储权限", "本地存储未授权，无法读取文件",
                        "去授权",
                        {
                            if (Build.VERSION.SDK_INT >= 30) {
                                // BUG-C-R6-07：范围存储设备走「所有文件访问」系统页
                                allFilesLauncher.launch(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.parse("package:" + context.packageName),
                                    ),
                                )
                            } else {
                                permLauncher.launch(storagePermission)
                            }
                        },
                    )
                    state.loading -> Column { repeat(8) { NvSkeletonListRow(leadingSize = 40.dp) } }
                    visible.isEmpty() && searchNeedle.isNotEmpty() -> NvEmptyState( // 5.3 无结果
                        Icons.Outlined.SearchOff, "未找到 \"$searchNeedle\"", "检查关键词或开启含子目录",
                    )
                    visible.isEmpty() && state.hasTypeFilter -> NvEmptyState( // 过滤后无文件（文件夹恒显示）
                        Icons.Outlined.FolderOff, "没有符合过滤条件的文件", "点上方「已过滤」Chip 可重置过滤",
                    )
                    state.entries.isEmpty() -> NvEmptyState( // 2.5 空目录
                        Icons.Outlined.FolderOff, "此文件夹为空", "返回上一级或选择其他目录",
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.nvGlassSource(glassEnv).background(NvColors.Background),
                        // 多选态页头整块隐藏，列表直通屏顶——top 追加 topInset 让首行起于多选栏下方；
                        // 常规/搜索态页头已避让，维持原 S
                        contentPadding = PaddingValues(
                            top = if (state.selectionMode) topInset + NvSpacing.S else NvSpacing.S,
                            bottom = NvSpacing.S + glassBottom,
                        ),
                    ) {
                    items(visible, key = { it.path }) { raw ->
                        // P2-8 行回填：订阅 watch_history 按键匹配 → watched✓ + 续播进度
                        val e = vm.decorated(state.sourceId, raw, historyByKey[vm.mediaKeyOf(state.sourceId, raw)])
                        // C12 §3.8 + 2026-09-15 D3：单次查询同时取缩略图与时长/分辨率徽标
                        // （审计 P2-4：原两处 produceState 各打一次 findByKey，滚动大目录 DB 调用减半）
                        // BUG-C-R6-13：改为订阅行级 DB Flow——抽帧完成入库后行内即时刷新
                        // BUG-C-R6-17：无封面的视频行按需触发抽帧——库内行走 requestThumb
                        //（claim CAS 去重），未入库的走旁路抽取（browserThumbs 回流），不再只有字母占位
                        // 2026-09-26：图片行也出缩略图——本地直读原图（Coil 按显示尺寸降采样，
                        // 零磁盘），远程走旁路拉取+降采样 240px（browserThumbs 回流）
                        val rowInfo = produceState<Pair<String?, String?>>(
                            initialValue = null to null,
                            state.sourceId, raw.path,
                        ) {
                            val kind = NvFileRouter.kindOf(raw.name)
                            if (raw.isDir) return@produceState
                            when (kind) {
                                NvFileRouter.Kind.VIDEO -> {
                                    // H-9（2026-10-18 发布终审）：值流与副作用流分离——此前
                                    // ensureBrowserThumb/requestThumbFor 写在 combine 变换 lambda 里，
                                    // browserThumbs 是整 Map StateFlow（任一张封面落盘都会发射新值），
                                    // 批量抽帧期间每张完成都会让全部可见行重入变换并重复发起
                                    // 请求（协程 launch + dao 查询量 = 可见行×文件数白烧）。
                                    // 现副作用只挂 observeMediaMeta（单行 DB 流，仅本行变化才
                                    // 重发）；值合并保持纯函数
                                    kotlinx.coroutines.coroutineScope {
                                        launch {
                                            // 值流（纯函数，无副作用）
                                            combine(
                                                vm.observeMediaMeta(state.sourceId, raw.path),
                                                vm.browserThumbs,
                                            ) { m, browserThumbs ->
                                                when {
                                                    m == null ->
                                                        browserThumbs[raw.path]?.let { it to null } ?: (null to null)
                                                    else -> {
                                                        val thumb = m.takeIf {
                                                            it.thumbnailPath != null && it.thumbState == com.novax.app.data.db.ThumbState.DONE.name
                                                        }?.thumbnailPath
                                                        thumb to mediaMetaBadge(m.durationMs, m.width, m.height)
                                                    }
                                                }
                                            }.collect { value = it }
                                        }
                                        launch {
                                            // 副作用流：仅随本行元数据变化触发（claim CAS /
                                            // in-flight 去重照旧兜底重复）
                                            vm.observeMediaMeta(state.sourceId, raw.path).collect { m ->
                                                when {
                                                    m == null -> vm.ensureBrowserThumb(state.sourceId, raw.path, raw.size)
                                                    m.thumbnailPath == null || m.thumbState != com.novax.app.data.db.ThumbState.DONE.name ->
                                                        vm.requestThumbFor(m.id)
                                                }
                                            }
                                        }
                                    }
                                }

                                NvFileRouter.Kind.IMAGE -> {
                                    val local = vm.localImageFileOf(state.sourceId, raw.path)
                                    if (local != null) {
                                        value = local.absolutePath to null
                                    } else {
                                        vm.ensureBrowserImageThumb(state.sourceId, raw.path, raw.size)
                                        vm.browserThumbs.collect { bt ->
                                            value = bt[raw.path]?.let { it to null } ?: (null to null)
                                        }
                                    }
                                }

                                else -> return@produceState
                            }
                        }
                        val thumbPath = rowInfo.value.first
                        val metaBadge = rowInfo.value.second
                        // 5.3 子目录命中：次行显示相对路径（当前目录自身条目 = 名称本身，不显示）
                        val relDir = relativePathUnder(raw.path, currentDirPath)?.takeIf { it != raw.name }
                        NvFileRow(
                            e = e,
                            thumbnailPath = thumbPath,
                            selected = if (state.selectionMode) raw.path in state.selectedPaths else null,
                            highlightQuery = searchNeedle.takeIf { it.isNotEmpty() },
                            relativeDir = relDir,
                            metaBadge = metaBadge,
                            showSize = showFileSize,
                            onClick = {
                                if (state.selectionMode) {
                                    vm.toggleSelected(raw.path)
                                } else if (e.isDir) {
                                    // 导航仅更新目录，不改源身份；虚拟共享前缀随路径保留。
                                    vm.listDir(state.sourceId, e.path.trim('/').split('/').filter { it.isNotEmpty() })
                                } else {
                                    // 2026-09-18 双内核：RM/RMVB/WMV 已由 libVLC 兜底承接
                                    //（PlayerActivity 按扩展名转 RmvbPlayerActivity），不再弹「可能无法播放」。
                                    // C9 §一：NvFileRouter 分发（视频行为不变；音频走播放器音频模式）
                                        when (NvFileRouter.kindOf(e.name)) {
                                            NvFileRouter.Kind.VIDEO, NvFileRouter.Kind.AUDIO -> {
                                                // 2026-09-15 用户需求：文件夹内上/下切换 —— 同目录
                                                //（当前排序/过滤后的可见列表）音视频按序入队
                                                val queue = visible.filter {
                                                    !it.isDir &&
                                                        NvFileRouter.kindOf(it.name).let { k ->
                                                            k == NvFileRouter.Kind.VIDEO || k == NvFileRouter.Kind.AUDIO
                                                        }
                                                }
                                                val urls = queue.map { vm.playUrl(state.sourceId, it) }
                                                val idx = queue.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
                                                onPlayInFolder(vm.playUrl(state.sourceId, e), urls, idx)
                                            }
                                        NvFileRouter.Kind.IMAGE -> {
                                            // 同目录图片按名称排序构成 Pager 列表（含自身）
                                            val images = visible
                                                .filter {
                                                    !it.isDir &&
                                                        NvFileRouter.kindOf(it.name) == NvFileRouter.Kind.IMAGE
                                                }
                                                .sortedBy { it.name.lowercase() }
                                            val uris = ArrayList(images.map { vm.playUrl(state.sourceId, it) })
                                            val idx = images.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
                                            ViewerActivity.start(
                                                context, NvFileRouter.Kind.IMAGE,
                                                vm.playUrl(state.sourceId, e), e.name, e.size,
                                                uris, idx,
                                            )
                                        }
                                        NvFileRouter.Kind.PDF -> ViewerActivity.start(
                                            context, NvFileRouter.Kind.PDF,
                                            vm.playUrl(state.sourceId, e), e.name, e.size,
                                        )
                                        NvFileRouter.Kind.TEXT -> ViewerActivity.start(
                                            context, NvFileRouter.Kind.TEXT,
                                            vm.playUrl(state.sourceId, e), e.name, e.size,
                                        )
                                        NvFileRouter.Kind.UNKNOWN -> unknownEntry = e
                                    }
                                }
                            },
                            onLongClick = {
                                // 5.4 长按进入多选（多选态下长按 = 勾选/取消）
                                if (state.selectionMode) {
                                    vm.toggleSelected(raw.path)
                                } else {
                                    // BUG-C-R6-02（P1）：非多选态长按打开操作菜单——actionEntry
                                    // 此前全文件无非空赋值，加入/取消收藏、标记已看、LOCAL 重命名/
                                    // 删除、显示信息五条链路整体不可达
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    actionEntry = raw
                                }
                            },
                        )
                        HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
                    }
                }
            }
        }
        // 5.4 多选底栏
        if (state.selectionMode) {
            NvSelectionBar(
                actions = listOf(
                    NvSelectionAction("播放选中", Icons.Outlined.PlayCircle) {
                        if (selectedVideos.isEmpty()) {
                            Toast.makeText(context, "选中项中没有可播放的视频", Toast.LENGTH_SHORT).show()
                        } else {
                            // 2026-09-15：按列表顺序建播放队列连播（原二期 TODO 落地）
                            val urls = selectedVideos.map { vm.playUrl(state.sourceId, it) }
                            onPlayInFolder(urls.first(), urls, 0)
                        }
                    },
                    NvSelectionAction("加入收藏", Icons.Outlined.Favorite) {
                        vm.batchAddFavorites(state.sourceId, selectedEntries)
                    },
                    NvSelectionAction(
                        "排除出媒体库",
                        Icons.Outlined.VisibilityOff,
                        enabled = selectedEntries.isNotEmpty() && selectedEntries.all { it.isDir },
                    ) {
                        val dirs = selectedEntries.filter { it.isDir }
                            .map { it.path.trimEnd('/') }.filter { it.isNotEmpty() && it != "/" }
                        if (dirs.isEmpty()) {
                            Toast.makeText(context, "请先勾选要排除的文件夹", Toast.LENGTH_SHORT).show()
                        } else {
                            confirmExcludeDirs = dirs
                        }
                    },
                    NvSelectionAction("删除", Icons.Outlined.Delete, enabled = isLocalSource) {
                        if (!isLocalSource) {
                            Toast.makeText(context, "NAS 源删除将在后续版本支持", Toast.LENGTH_SHORT).show()
                        } else if (selectedEntries.none { !it.isDir }) {
                            Toast.makeText(context, "请先勾选要删除的文件", Toast.LENGTH_SHORT).show()
                        } else {
                            confirmDeleteSelection = true
                        }
                    },
                    NvSelectionAction("其他应用打开", Icons.Outlined.OpenInNew, enabled = openWithEnabled) {
                        if (openWithEnabled) {
                            val e = selectedEntries[0]
                            if (e.size > NvFileRouter.SHARE_CONFIRM_BYTES) confirmBigShare = e
                            else launchShare(e, vm.playUrl(state.sourceId, e))
                        } else {
                            Toast.makeText(context, "仅支持单个本地非视频文件", Toast.LENGTH_SHORT).show()
                        }
                    },
                    NvSelectionAction("全不选", Icons.Outlined.Deselect) { vm.clearSelection() },
                ),
            )
        }
        }
        // 顶栏三态（悬浮玻璃层，Box 末尾声明做内容兄弟）：多选 NvSelectionTopBar（面包屑隐藏）
        // → 搜索 NvSearchBar → 常规 NvTopBar；三态自身玻璃/实色背景在 Selection.kt 内按
        // Task 5 NvTopBar 同款链序前置（盖状态栏条带，§4.5a / Task 5 检查点①）
        when {
            state.selectionMode -> NvSelectionTopBar(
                selectedCount = state.selectedPaths.size,
                totalCount = visiblePaths.size,
                onSelectAll = { vm.selectAll(visiblePaths) },
                onInvert = { vm.invertSelection(visiblePaths) },
                onClose = {
                    if (state.selectedPaths.isEmpty()) vm.exitSelectionMode() else vm.clearSelection()
                },
            )
            state.searchActive -> NvSearchBar(
                query = state.searchQuery,
                onQueryChange = vm::onSearchQueryChange,
                onClose = vm::exitSearch,
            )
            else -> NvTopBar(
                title = state.breadcrumb.lastOrNull() ?: state.source?.name ?: "文件",
                scrolled = scrolled,   // 观感返工①：仅常规态接线（多选/搜索态维持常驻）
                onBack = onBack,
                actions = listOf(
                    {
                        IconButton(onClick = { showToolsSheet = true }, Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreVert, "过滤与排序", Modifier.size(24.dp), tint = NvColors.TextPrimary)
                        }
                        IconButton(onClick = { vm.enterSearch() }, Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Search, "搜索", Modifier.size(24.dp), tint = NvColors.TextPrimary)
                        }
                    },
                ),
            )
        }
    }

    // 5.1/5.2 "..."弹层：类型过滤器（NvCheckRow 六类 + 显示 N 项/重置）+ 排序方式（单选 + 文件夹恒置顶）
    if (showToolsSheet) {
        NvFilterSortSheet(
            state = state,
            sortMode = sortMode,
            dirsFirst = dirsFirst,
            onConfirmFilter = { enabled ->
                vm.onTypeFilterConfirm(enabled)
                showToolsSheet = false
            },
            onSortMode = vm::onSortModeChange,
            onDirsFirst = vm::onDirsFirstChange,
            onDismiss = { showToolsSheet = false },
        )
    }

    // 5.4 批量删除确认（仅本地）
    if (confirmDeleteSelection) {
        val files = selectedEntries.filter { !it.isDir }
        NvConfirmDialog(
            title = "删除 ${files.size} 个文件？",
            message = "将从本地存储中删除所选文件，不可恢复",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDeleteSelection = false
                vm.deleteSelectedLocal(state.sourceId, files)
            },
            onDismiss = { confirmDeleteSelection = false },
        )
    }

    // 三.2 长按菜单（修订）：① 加入/取消收藏 ② 标记已看/取消已看 ③ 加入媒体库 ④ LOCAL 重命名/删除 ⑤ 显示信息 ⑥ 多选
    actionEntry?.let { e ->
        val isFavorite = vm.mediaKeyOf(state.sourceId, e) in favoriteKeys
        val isLocal = state.source?.protocol == NvProtocol.Local
        val history = historyByKey[vm.mediaKeyOf(state.sourceId, e)]
        val isWatched = history != null && history.durationMs > 0L &&
            history.positionMs >= history.durationMs * 0.95
        NvBottomSheet(onDismiss = { actionEntry = null }) {
            NvSheetListItem(
                if (isFavorite) "取消收藏" else "加入收藏",
                selected = isFavorite,
                onClick = {
                    vm.toggleFavorite(state.sourceId, e)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    Toast.makeText(
                        context,
                        if (isFavorite) "已取消收藏" else "已加入收藏",
                        Toast.LENGTH_SHORT,
                    ).show()
                    actionEntry = null
                },
            )
            // C13 §5 入口二：文件长按「投屏」（视频文件）
            val isVideoFile = com.novax.app.ui.viewers.NvFileRouter.VIDEO_EXTS.any {
                e.name.endsWith(".$it", ignoreCase = true)
            }
            if (isVideoFile) {
                NvSheetListItem(
                    "投屏",
                    selected = false,
                    onClick = {
                        actionEntry = null
                        castEntry = e
                    },
                )
            }
            NvSheetListItem(
                if (isWatched) "取消已看" else "标记已看",
                selected = isWatched,
                onClick = {
                    if (isWatched) vm.markUnwatched(state.sourceId, e) else vm.markWatched(state.sourceId, e)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    Toast.makeText(
                        context,
                        if (isWatched) "已标记未看" else "已标记已看",
                        Toast.LENGTH_SHORT,
                    ).show()
                    actionEntry = null
                },
            )
            // 2026-09-15：删「加入媒体库」假菜单项——媒体索引已全自动（加源即扫描入库），假动作只误导
            if (isLocal) {
                NvSheetListItem("重命名", onClick = { renameEntry = e; actionEntry = null })
                NvSheetListItem("删除", onClick = { deleteEntry = e; actionEntry = null })
            }
            // 2026-10-09 批次二评审（T4）：5.4 选择模式全链（enterSelectionMode/勾选/批量收藏/
            // 批量删除）此前零 UI 入口——长按语义已让位操作菜单（BUG-C-R6-02），补「多选」项接线
            NvSheetListItem(
                "多选",
                selected = false,
                onClick = {
                    actionEntry = null
                    vm.enterSelectionMode(e)   // 签名 enterSelectionMode(first: NvFileEntry)，传整对象
                },
            )
            NvSheetListItem("显示信息", onClick = { infoEntry = e; actionEntry = null })
        }
    }

    // ── 弹层：投屏设备选择（C13 §5 入口二：文件长按）──
    val castGate = rememberNvCastGateState()   // 2026-09-28 审计 P0-1：计量网络投屏确认
    castEntry?.let { e ->
        val scope = rememberCoroutineScope()
        NvCastDeviceSheet(
            onDismiss = { castEntry = null },
            onCast = { device ->
                scope.launch {
                    val media = CastMedia(
                        sourceId = state.sourceId,
                        path = e.path,
                        title = e.name.removeSuffix("." + e.name.substringAfterLast('.', "")),
                        sizeBytes = e.size,
                        durationMs = 0L,
                        playUrl = vm.playUrl(state.sourceId, e),
                        subtitlePath = null,
                    )
                    // 2026-09-28 审计 P0-1：投屏 = 经手机中继整片拉流，计量网络先确认
                    if (!castGate.awaitConsent(media)) {
                        castEntry = null
                        return@launch
                    }
                    CastSessionHolder.cast(device, media)
                    castEntry = null
                }
            },
        )
    }
    NvCastMeteredDialogHost(castGate)

    // LOCAL 重命名对话框
    renameEntry?.let { e ->
        var newName by remember(e.path) { mutableStateOf(e.name) }
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            // Liquid Glass（spec §8）：独立窗口弹层填充式玻璃（Task 4 评审裁定顺带项，三处同款）
            containerColor = NvColors.GlassFillStrong,
            shape = NvShape.Dialog,
            title = { Text("重命名", style = NvTypography.TitleMedium, color = NvColors.TextPrimary) },
            text = { NvTextField("新文件名", newName, { newName = it }, Modifier.fillMaxWidth()) },
            confirmButton = {
                NvTextButton(
                    text = "重命名",
                    onClick = {
                        vm.renameLocalFile(state.sourceId, e, newName.trim())
                        renameEntry = null
                    },
                    enabled = newName.isNotBlank() && newName != e.name,
                )
            },
            dismissButton = { NvTextButton(text = "取消", onClick = { renameEntry = null }) },
        )
    }

    // LOCAL 删除确认
    deleteEntry?.let { e ->
        NvConfirmDialog(
            title = "删除文件 \"${e.name}\"？",
            message = "将从本地存储中删除该文件，不可恢复",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteLocalFile(state.sourceId, e)
                deleteEntry = null
            },
            onDismiss = { deleteEntry = null },
        )
    }

    // 显示信息（路径 / 大小）
    infoEntry?.let { e ->
        NvBottomSheet(onDismiss = { infoEntry = null }) {
            Row(
                Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = NvColors.TextSecondary)
                Spacer(Modifier.width(NvSpacing.S))
                Text("文件信息", style = NvTypography.TitleMedium)
            }
            listOf(
                "文件名" to e.name,
                "路径" to e.path,
                "大小" to fmtSize(e.size),
            ).forEach { (k, v) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = NvSpacing.L, vertical = NvSpacing.XS)) {
                    Text(k, style = NvTypography.LabelSmall, color = NvColors.TextSecondary)
                    Text(
                        v, style = NvTypography.BodyMedium, color = NvColors.TextPrimary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // C9 §六 NvUnknownFileSheet：图标 + 文件名/类型行 + 「用其他应用打开」（下载中行内进度、再点取消）+ 取消
    unknownEntry?.let { e ->
        val running = shareJob?.isActive == true
        NvBottomSheet(onDismiss = {
            shareJob?.cancel()
            shareProgress = null
            unknownEntry = null
        }) {
            Row(
                Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(NvShape.Input).background(NvColors.SurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(24.dp), tint = NvColors.TextSecondary)
                }
                Spacer(Modifier.width(NvSpacing.M))
                Column {
                    Text(
                        e.name, style = NvTypography.BodyMedium, color = NvColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${e.name.substringAfterLast('.', "文件").uppercase()} 文件 · ${fmtSize(e.size)}",
                        style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
                    )
                }
            }
            HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable {
                        when {
                            running -> {   // 再点变「取消」：中断下载
                                shareJob?.cancel()
                                shareJob = null
                                shareProgress = null
                            }
                            e.size > NvFileRouter.SHARE_CONFIRM_BYTES -> confirmBigShare = e
                            else -> launchShare(e, vm.playUrl(state.sourceId, e))
                        }
                    }
                    .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            running && shareProgress != null ->
                                "正在下载… ${(shareProgress!! * 100).toInt()}%"
                            running -> "正在下载…"
                            else -> "用其他应用打开"
                        },
                        style = NvTypography.BodyMedium,
                        color = if (running) NvColors.Secondary else NvColors.TextPrimary,
                    )
                    if (running && shareProgress != null) {
                        Spacer(Modifier.height(NvSpacing.XS))
                        LinearProgressIndicator(
                            progress = { shareProgress!! },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = NvColors.Primary,
                            trackColor = NvColors.SurfaceElevated,
                        )
                    }
                }
            }
            NvSheetListItem("取消", onClick = {
                shareJob?.cancel()
                shareProgress = null
                unknownEntry = null
            })
        }
    }

    // §6.1 文件 >512MB：先确认「先下载到本机缓存再打开」
    confirmBigShare?.let { e ->
        NvConfirmDialog(
            title = "文件较大",
            message = "该文件 ${fmtSize(e.size)}，将先下载到本机缓存再打开",
            confirmText = "继续",
            onConfirm = {
                val entry = e
                confirmBigShare = null
                unknownEntry = entry
                launchShare(entry, vm.playUrl(state.sourceId, entry))
            },
            onDismiss = { confirmBigShare = null },
        )
    }

    // 媒体库排除目录确认（2026-09-15）：只影响媒体库索引，文件不动；排除后立即重扫清索引
    confirmExcludeDirs?.let { dirs ->
        NvConfirmDialog(
            title = if (dirs.size == 1) "从媒体库排除？" else "排除 ${dirs.size} 个文件夹？",
            message = "排除后这些目录（含子目录）不再被索引，现有条目清除；进入目录可随时恢复收录。",
            confirmText = "排除",
            onConfirm = {
                confirmExcludeDirs = null
                vm.setDirsExcluded(state.sourceId, dirs, excluded = true)
                vm.clearSelection()
                Toast.makeText(context, "已排除，正在更新媒体索引…", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmExcludeDirs = null },
        )
    }
}

// 5.1/5.2 过滤 + 排序弹层（"..."入口）：六类 NvCheckRow（默认全勾 = 无过滤，确定后生效）
// + 排序单选（即时生效，DataStore 持久化）+「文件夹恒置顶」NvSwitchRow
@Composable
internal fun NvFilterSortSheet(
    state: NvBrowserUiState,
    sortMode: NvSortMode,
    dirsFirst: Boolean,
    onConfirmFilter: (Set<NvFileType>) -> Unit,
    onSortMode: (NvSortMode) -> Unit,
    onDirsFirst: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 草稿态：打开时取当前过滤，确定才写入（弹层内勾选即时回显命中数）
    var draftTypes by remember { mutableStateOf(state.enabledTypes) }
    val typeCounts = remember(state.entries) {
        NvFileType.entries.associateWith { type ->
            state.entries.count { !it.isDir && fileTypeOf(it.name) == type }
        }
    }
    NvBottomSheet(onDismiss = onDismiss) {
        Text(
            "类型过滤器",
            Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            style = NvTypography.TitleMedium,
            color = NvColors.TextPrimary,
        )
        NvFileType.entries.forEach { type ->
            NvCheckRow(
                title = type.label,
                checked = type in draftTypes,
                onChecked = { checked ->
                    draftTypes = if (checked) draftTypes + type else draftTypes - type
                },
                icon = type.icon(),
                count = typeCounts[type] ?: 0,
            )
        }
        val hitCount = state.entries.count { it.isDir || fileTypeOf(it.name) in draftTypes }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NvTextButton("重置", onClick = { draftTypes = allFileTypes })
            Spacer(Modifier.weight(1f))
            NvPrimaryButton("显示 $hitCount 项", onClick = { onConfirmFilter(draftTypes) })
        }
        HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
        Text(
            "排序方式",
            Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            style = NvTypography.TitleMedium,
            color = NvColors.TextPrimary,
        )
        NvSortMode.entries.forEach { mode ->
            NvSheetListItem(
                text = mode.label,
                selected = sortMode == mode,
                onClick = { onSortMode(mode) },
            )
        }
        NvSwitchRow(
            title = "文件夹恒置顶",
            checked = dirsFirst,
            onChecked = onDirsFirst,
            showDivider = false,
        )
    }
}

/** 六类文件类型图标（C5 §5.1 NvCheckRow 行首） */
internal fun NvFileType.icon(): ImageVector = when (this) {
    NvFileType.VIDEO -> Icons.Outlined.Movie
    NvFileType.AUDIO -> Icons.Outlined.MusicNote
    NvFileType.IMAGE -> Icons.Outlined.Image
    NvFileType.SUBTITLE -> Icons.Outlined.Subtitles
    NvFileType.DOC -> Icons.Outlined.Description
    NvFileType.OTHER -> Icons.Outlined.InsertDriveFile
}

// 面包屑路径行（高 32dp，横向滚动，当前段 Primary）
@Composable
internal fun NvBreadcrumb(path: List<String>, onSelect: (Int) -> Unit) {
    // E3-5（发版审查）：深层导航后新增段落在可视区外——段数变化时滚到当前段（最后一段）
    val listState = rememberLazyListState()
    LaunchedEffect(path.size) {
        if (path.isNotEmpty()) listState.animateScrollToItem(path.size - 1)
    }
    LazyRow(
        Modifier.fillMaxWidth().height(32.dp),
        state = listState,
        contentPadding = PaddingValues(horizontal = NvSpacing.L),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(path.size) { i ->
            val seg = path[i]
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                    Modifier.size(12.dp), tint = NvColors.TextDisabled,
                )
            }
            Text(
                seg,
                Modifier
                    .heightIn(min = 32.dp)
                    .combinedClickable(onClick = { onSelect(i) }, onLongClick = {})
                    .padding(horizontal = NvSpacing.XS, vertical = 6.dp),
                style = NvTypography.LabelMedium,
                color = if (i == path.lastIndex) NvColors.Primary else NvColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

// 文件行：文件夹行 / 视频文件行（名称 + 大小 + 类型 badge；缩略图命中 media_items 时显示真帧，C12 §3.8）
// C5 二期：多选态行首勾选圈 + 选中底色 Primary 8%；搜索命中词高亮；子目录命中次行相对路径
@Composable
internal fun NvFileRow(
    e: NvFileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    thumbnailPath: String? = null,
    selected: Boolean? = null,        // null = 非多选模式；true/false = 多选勾选态
    highlightQuery: String? = null,   // 搜索命中词高亮（空串 = 不高亮）
    relativeDir: String? = null,      // 子目录命中时的相对路径（次行）
    metaBadge: String? = null,        // 2026-09-15 D3：时长·分辨率（media_items 已回填）
    showSize: Boolean = true,         // BUG-E-T2-02：「列表显示文件大小」开关消费端（原实现无人生效）
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected == true) NvColors.Primary.copy(alpha = 0.08f) else Color.Transparent)
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            NvSelectionCircle(selected = selected)
            Spacer(Modifier.width(NvSpacing.M))
        }
        if (e.isDir) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(24.dp), tint = NvColors.TextSecondary)
            Spacer(Modifier.width(NvSpacing.M))
            Column(Modifier.weight(1f)) {
                NvHighlightText(
                    text = e.name,
                    query = highlightQuery.orEmpty(),
                    style = NvTypography.BodyMedium,
                    color = NvColors.TextPrimary,
                )
                relativeDir?.let {
                    Text(it, style = NvTypography.LabelSmall, color = NvColors.TextDisabled, maxLines = 1)
                }
            }
            Icon(
                Icons.Outlined.ChevronRight, null,
                Modifier.size(16.dp), tint = NvColors.TextDisabled,
            )
        } else {
            // 2:3 缩略图：抽帧完成的真帧优先（仅显示不触发抽帧），否则文件名首字渐变兜底
            Box(
                Modifier.size(40.dp, 60.dp).clip(NvShape.Poster).background(NvColors.Skeleton),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnailPath != null) {
                    coil3.compose.AsyncImage(
                        // 显式解码口径：本地原图/大帧不再全量解码（12MP 照片全解一次 ≈ 48MB 位图）
                        model = coil3.request.ImageRequest.Builder(LocalContext.current)
                            .data(java.io.File(thumbnailPath))
                            .size(160, 240)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    // 2026-09-27 去 AI 味：橙蓝渐变换中性底（与 NvPosterFallback 同口径）
                    Box(
                        Modifier.fillMaxSize().background(NvColors.Skeleton),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            e.name.take(1).uppercase(),
                            style = NvTypography.TitleMedium, color = NvColors.TextPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(NvSpacing.M))
            Column(Modifier.weight(1f)) {
                NvHighlightText(
                    text = e.name,
                    query = highlightQuery.orEmpty(),
                    style = NvTypography.BodyMedium,
                    color = NvColors.TextPrimary,
                    maxLines = 2,
                )
                relativeDir?.let {
                    Text(
                        it,
                        style = NvTypography.LabelSmall, color = NvColors.TextDisabled,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(NvSpacing.XS))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 类型 badge（后缀大写）
                    Text(
                        e.name.substringAfterLast('.', "视频").uppercase(),
                        Modifier.clip(NvShape.Badge).background(NvColors.PrimaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = NvTypography.LabelSmall, color = NvColors.Primary,
                    )
                    if (showSize) {
                        Spacer(Modifier.width(NvSpacing.S))
                        Text(
                            fmtSize(e.size),
                            style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
                        )
                    }
                    metaBadge?.let {
                        Spacer(Modifier.width(NvSpacing.S))
                        Text(
                            it,
                            style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    if (e.watched) {
                        Spacer(Modifier.width(NvSpacing.S))
                        Icon(Icons.Outlined.CheckCircle, "已看", Modifier.size(16.dp), tint = NvColors.Success)
                    }
                }
                // 续播进度（watch_history 按路径匹配回填）
                if (e.resumeProgress != null) {
                    Spacer(Modifier.height(NvSpacing.XS))
                    LinearProgressIndicator(
                        progress = { e.resumeProgress!! },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = NvColors.Primary,
                        trackColor = NvColors.SurfaceElevated,
                    )
                }
                // 时长/分辨率徽标已于 2026-09-15 接入 media_items 回填数据（metaBadge）
            }
        }
    }
}

/** 2026-09-15 D3：媒体元数据徽标 "45:32 · 4K"（无数据段自动省略；全空 → null） */
internal fun mediaMetaBadge(durationMs: Long?, width: Int?, height: Int?): String? {
    val parts = mutableListOf<String>()
    if (durationMs != null && durationMs > 0) {
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        parts.add(if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec))
    }
    com.novax.app.ui.home.resolutionBadge(width, height)?.let { parts.add(it) }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

// 登记新组件：NvTextField（圆角 12dp、Outline 边框、聚焦 Primary）
