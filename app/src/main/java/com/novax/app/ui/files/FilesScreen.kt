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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.material.icons.outlined.Lan
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
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.R
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
import com.novax.app.ui.components.nvBarCollapse
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
import com.novax.app.ui.privacy.NvPinSetupSheet
import com.novax.app.ui.privacy.NvPrivacyUnlockSheet
import com.novax.app.ui.viewers.NvFileRouter
import com.novax.app.ui.viewers.ViewerActivity
import com.novax.app.ui.viewers.ViewerShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

// C5 文件/NAS 源页（暗色沉浸，底部 Tab 第 3 个「文件」）
// 数据链路已真实化：源列表 = Room(SourceDao) Flow；目录 = NvSourceRepository.list；
// 添加源向导 = 真实 connect 测试；发现 = NsdManager(_smb/_webdav)。
@Composable
fun FilesScreen(
    onPlay: (String) -> Unit,
    // 2026-09-15 用户需求：文件夹内播放支持上/下切换 —— 播放时把当前目录（当前排序/过滤后的
    // 可见列表）里的视频按序传给播放器。(url, 同目录视频 url 列表, 本次点击的下标)
    onPlayInFolder: (String, List<String>, Int) -> Unit = { url, _, _ -> onPlay(url) },
) {
    val context = LocalContext.current
    val vm: FilesViewModel = viewModel(factory = FilesViewModel.factory(context.applicationContext))
    // 首页「添加源」一次性事件：进入文件页时消费并自动打开向导（NvUiEvents 消费即复位）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (NvUiEvents.consumeAddSourceWizard()) vm.openWizard()
        // 隐私锁（Task 13）：重读 pendingRepoint（T16 可能刚从设置页设定并跳转过来）
        vm.refreshPendingRepoint()
        // E3-3（发版审查）：深链与浏览恢复互斥——openSourceAt 首帧几乎必挂 pendingOpenJob
        // （源列表未就绪、_browser 仍空），紧随的 restoreBrowserIfSaved 会 cancel 掉它，
        // 「打开所在文件夹」被上次浏览位置抢占。有深链消费即跳过恢复。
        val deepLink = NvFilesDeepLink.consume()
        if (deepLink != null) {
            vm.openSourceAt(deepLink.sourceId, deepLink.breadcrumb)
        } else {
            vm.restoreBrowserIfSaved()
        }
    }
    val sources by vm.sources.collectAsState()
    val sourcesLoading by vm.sourcesLoading.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val devices by vm.devices.collectAsState()
    val wizard by vm.wizard.collectAsState()
    val wizardBrowser by vm.wizardBrowser.collectAsState()
    val browser by vm.browser.collectAsState()
    val batteryPrompt by vm.batteryPrompt.collectAsState()

    var showDeleteTarget by remember { mutableStateOf<NvSource?>(null) }
    var sheetSource by remember { mutableStateOf<NvSource?>(null) }

    // ── 隐私锁（Task 13）：整源锁的源行遮蔽/点击拦截 + 解锁流 + 动作步骤（PinSetup/清历史确认）──
    // 规则 Flow + 会话 tick 双信号驱动重组；判定本体走 privacy.index 内存快照
    val privacyRules by vm.privacyRules.collectAsState()
    val privacyTick by vm.privacyTick.collectAsState()
    val privacyStep by vm.privacyStep.collectAsState()
    var unlockSourceTarget by remember { mutableStateOf<NvSource?>(null) }

    // C11 §1.3(b) 返回链（C5 §5.4 对齐）：多选（先清勾选→再退出多选）→ 搜索 → 回上一级目录；
    // 源根 = closeBrowser 回源列表（R5）——系统返回键专用，语义是「文件上一级」。
    // 2026-10-19 用户裁定：左上角箭头与返回键分家——箭头 = 关浏览器回源列表（上一级页面，
    // 深层目录一键回源汇总页，不再逐级点穿面包屑），见 FileBrowserScreen 的 onBack 接线。
    // BUG-C-R6-15：Tab 内下钻返回动作收敛为单 lambda——系统返回键正常路径由下方
    // BackHandler 直接执行；调度优先级被框架重挂打乱时（播放器前台返回有实测案例，
    // App 级 handler 反超）由 NvTabBackBus 兜底路由到同一动作，语义等价不跨 Tab 误跳。
    val filesInTabBack: () -> Unit = {
        val b = browser
        when {
            b == null -> Unit
            b.selectionMode ->
                if (b.selectedPaths.isNotEmpty()) vm.clearSelection() else vm.exitSelectionMode()
            b.searchActive -> vm.exitSearch()
            else -> vm.onBrowserBack()
        }
    }
    // BUG-C-R6-06：向导优先——原实现浏览器 BackHandler 先注册且路由 browser 优先，
    // 「首页→添加源」事件把向导置 open 却被浏览器遮蔽、返回键也先被浏览器截走「失灵」一次
    BackHandler(enabled = wizard.open) { vm.onWizardBack() }
    BackHandler(enabled = browser != null && !wizard.open) { filesInTabBack() }
    LaunchedEffect(Unit) {
        com.novax.app.ui.components.NvTabBackBus.inTabBack.collect { route ->
            if (route == com.novax.app.ui.components.NvTabBackBus.ROUTE_FILES) filesInTabBack()
        }
    }
    androidx.compose.runtime.SideEffect {
        com.novax.app.ui.components.NvTabBackBus.filesDrilled = wizard.open || browser != null
    }

    // 顶层路由：向导 / 浏览器 / 源列表（向导优先，见 BUG-C-R6-06）
    if (wizard.open) {
        AddSourceWizardScreen(vm, wizard, wizardBrowser, devices, scanning)
    } else if (browser != null) {
        FileBrowserScreen(
            state = browser!!,
            vm = vm,
            onPlay = onPlay,
            onPlayInFolder = onPlayInFolder,
            // 2026-10-19 用户裁定：左上角箭头 = 上一级页面（关浏览器回源列表），不是上一级文件夹；
            // 文件上一级由系统返回键（filesInTabBack → onBrowserBack）承接
            onBack = vm::closeBrowser,
        )
    } else {
        SourceListScreen(
            sources = sources,
            loading = sourcesLoading,
            scanning = scanning,
            devices = devices,
            onSourceClick = { src ->
                // 隐私锁（Task 13）：整源锁的源点击拦截——先出解锁面板，成功才进入
                if (vm.isSourceLocked(src.id)) unlockSourceTarget = src else vm.openSource(src)
            },
            onSourceLongPress = { src ->
                // 终审 C1（fail-closed，与目录行 T13 挡板同口径）：整源锁未解锁的源卡不弹
                // 删除/编辑/规则 sheet——否则锁规则可被无 PIN 增删、锁定源可被无 PIN 删除
                if (!vm.isSourceLocked(src.id)) sheetSource = src
            },
            onAddSource = { vm.openWizard() },
            onDeviceClick = vm::onDeviceClick,
            onRescan = { vm.rescan() },
            sourceLocked = { src -> vm.isSourceLocked(src.id) },
        )
    }

    // 源长按菜单（删除源走确认对话框，真删 DB）
    // 隐私锁（Task 13）：新增「整源隐私锁/整源无痕」两项（dirPath="" 整源规则，spec §4.1）
    sheetSource?.let { src ->
        val menuState = com.novax.app.data.privacy.NvPrivacyActionPolicy.menuState(
            hasPendingRepoint = false,
            ownRule = vm.ownRuleViewOf(src.id, ""),
        )
        NvBottomSheet(onDismiss = { sheetSource = null }) {
            NvSheetListItem(stringResource(R.string.files_sheet_edit_source), onClick = { vm.editSource(src.id); sheetSource = null })
            NvSheetListItem(stringResource(R.string.files_sheet_duplicate_source), onClick = { vm.editSource(src.id, duplicate = true); sheetSource = null })
            NvSheetListItem(
                stringResource(
                    if (menuState.lockLabelOn) R.string.privacy_source_lock_remove else R.string.privacy_source_lock_add,
                ),
                onClick = {
                    sheetSource = null
                    vm.toggleLock(src.id, "", src.name)
                },
            )
            NvSheetListItem(
                stringResource(
                    if (menuState.incognitoLabelOn) R.string.privacy_source_incognito_off else R.string.privacy_source_incognito_on,
                ),
                onClick = {
                    sheetSource = null
                    vm.toggleIncognito(src.id, "", src.name)
                },
            )
            NvSheetListItem(stringResource(R.string.files_sheet_delete_source), onClick = {
                showDeleteTarget = src
                sheetSource = null
            })
        }
    }

    // 隐私锁（Task 13）：整源锁源行的解锁面板（T12 产出）；解锁成功重置整源占位缩略图（必做 B）
    // + 补扫并进入；FLAG_SECURE 由面板自带，**离组必须还原**——源列表底层无敏感内容，
    // 默认不清除会把 SECURE 永久留在 MainActivity 窗口（2026-10-25 真机：之后截屏/最近任务全黑）
    unlockSourceTarget?.let { src ->
        NvPrivacyUnlockSheet(
            ruleIds = vm.blockingRuleIdsFor(src.id, NvPaths.SEPARATOR),
            onUnlocked = { ids ->
                unlockSourceTarget = null
                vm.onSourceUnlocked(ids, src.id)
            },
            onReset = {
                unlockSourceTarget = null
                vm.onPrivacyReset()
            },
            onDismiss = { unlockSourceTarget = null },
            clearFlagSecureOnDispose = true,
        )
    }

    // 隐私锁（Task 13）：无 PIN 加锁先引导创建（T12 PinSetupSheet），完成后原动作续跑
    if (privacyStep is com.novax.app.ui.files.FilesViewModel.PrivacyStep.NeedPinSetup) {
        NvPinSetupSheet(
            onDismiss = { vm.onPinSetupDismissed() },
            onCompleted = { vm.onPinSetupCompleted() },
        )
    }

    // 终审 C1：关分支（移除隐私锁/关闭无痕）逐次验 PIN（T16 NvPinVerifySheet 纯验证形态，
    // 不碰解锁会话）；验过由 VM 执行暂存的关动作。源菜单与目录菜单共用本步骤机
    if (privacyStep is com.novax.app.ui.files.FilesViewModel.PrivacyStep.NeedPinVerify) {
        com.novax.app.ui.privacy.NvPinVerifySheet(
            onVerified = { vm.onPinVerifyPassed() },
            onDismiss = { vm.onPinVerifyDismissed() },
        )
    }

    // 隐私锁（Task 13）：开启无痕的「同时清除 N 条记录？」确认（确认=真抹除清历史；dismiss=仅开启）
    (privacyStep as? com.novax.app.ui.files.FilesViewModel.PrivacyStep.ConfirmIncognito)?.let { step ->
        NvConfirmDialog(
            title = stringResource(R.string.privacy_menu_incognito_on),
            message = stringResource(R.string.privacy_incognito_clear_confirm, step.recordCount),
            confirmText = stringResource(R.string.privacy_incognito_clear_and_enable),
            dismissText = stringResource(R.string.privacy_incognito_keep_records),
            danger = true,
            onConfirm = { vm.onIncognitoClearConfirmed() },
            onDismiss = { vm.onIncognitoKeepRecords() },
        )
    }

    // 2.9 删除源确认
    showDeleteTarget?.let { src ->
        NvConfirmDialog(
            title = stringResource(R.string.files_delete_source_confirm, src.name),
            message = stringResource(R.string.files_delete_source_msg),
            confirmText = stringResource(R.string.files_delete),
            danger = true,
            onConfirm = {
                vm.deleteSource(src.id)
                showDeleteTarget = null
            },
            onDismiss = { showDeleteTarget = null },
        )
    }

    // 电池优化豁免（三星 MARs 会掐 SMB 连接）：SMB 源测试成功后询问一次（DataStore 防重复）
    if (batteryPrompt) {
        NvConfirmDialog(
            title = stringResource(R.string.files_battery_title),
            message = stringResource(R.string.files_battery_msg),
            confirmText = stringResource(R.string.files_go_settings),
            onConfirm = { vm.onBatteryPromptResult(true) },
            onDismiss = { vm.onBatteryPromptResult(false) },
        )
    }
}

// ---------- 1.1 源列表页 ----------
@Composable
private fun SourceListScreen(
    sources: List<NvSource>,
    loading: Boolean,
    scanning: Boolean,
    devices: List<NvDiscoveredDevice>,
    onSourceClick: (NvSource) -> Unit,
    onSourceLongPress: (NvSource) -> Unit,
    onAddSource: () -> Unit,
    onDeviceClick: (NvDiscoveredDevice) -> Unit,
    onRescan: () -> Unit,
    sourceLocked: (NvSource) -> Boolean = { false },   // 隐私锁（Task 13）：整源锁源行遮蔽判定
) {
    // Liquid Glass（spec §9）：源列表底 padding 追加玻璃底栏高度（IME 打开→0dp）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // Liquid Glass（decisions.md §4.5 页面翻转配方）：source 挂源列表滚动容器，
    // NvTopBar 悬浮做兄弟（Box 末尾声明），列表经 topInset 避让悬浮栏
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：源列表滚动态上提 + 顶栏渐显接线（静止态隐形）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    // rework3：enterAlways 顶栏收起（仅「文件」Tab 首页；FileBrowser 为二级推入页不收）
    val barCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(listState)
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        when {
            loading -> Column(
                Modifier.fillMaxSize().padding(top = topInset, start = NvSpacing.L, end = NvSpacing.L),
            ) {
                repeat(3) { NvSkeletonListRow() }
            }
            sources.isEmpty() -> Column(Modifier.fillMaxSize().padding(top = topInset)) {
                // 三.3 空态仍保留自动发现
                NvDiscoverySection(devices, scanning, onDeviceClick = onDeviceClick)
                NvEmptyState(
                    Icons.Outlined.Devices, stringResource(R.string.files_empty_sources_title),
                    stringResource(R.string.files_empty_sources_msg),
                    stringResource(R.string.files_add_manually), onAddSource,
                )
            }
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = NvSpacing.L,
                    top = topInset,
                    end = NvSpacing.L,
                    bottom = NvSpacing.S + glassBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(NvSpacing.M),
                // fillMaxSize 撑满整屏（Box 内等价原 weight(1f)）：animateContentSize 会把节点收到
                // 内容高，短列表时栏后无源像素致底栏玻璃失效（§4.5a）；source 在前、background 在后
                //（录制含背景）。内容滚入悬浮顶栏下方透出模糊
                modifier = Modifier.fillMaxSize().animateContentSize()
                    .nvGlassSource(glassEnv).background(NvColors.Background),
            ) {
                items(sources, key = { it.id }) { src ->
                    NvSourceCard(
                        src,
                        locked = sourceLocked(src),
                        onClick = { onSourceClick(src) },
                        onLongClick = { onSourceLongPress(src) },
                    )
                }
                item { NvAddSourceTile(onAddSource) }
            }
        }
        // 2026-09-16 UI 审计 P0：原自建头部缺状态栏 inset（标题比其他 Tab 高 ~24dp）
        // 且字号多一套 TitleLarge——统一走 NvTopBar（与首页/媒体库/设置完全一致）
        NvTopBar(
            title = stringResource(R.string.files_title),
            modifier = Modifier.nvBarCollapse(barCollapsed),   // rework3：下滑收起/上滑出现
            scrolled = scrolled,   // 观感返工①：静止态隐形，滚过首项渐显
            actions = listOf {
                IconButton(onClick = onRescan, Modifier.size(48.dp)) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = NvColors.Primary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Dns, stringResource(R.string.files_cd_scan_lan), Modifier.size(24.dp), tint = NvColors.TextPrimary)
                    }
                }
                IconButton(onClick = onAddSource, Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.files_add_source), Modifier.size(24.dp), tint = NvColors.TextPrimary)
                }
            },
        )
    }
}

@Composable
private fun NvSourceCard(
    src: NvSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    locked: Boolean = false,   // 隐私锁（Task 13）：整源锁通用锁样式遮蔽真名/地址（spec §4.3）
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(NvShape.Input)
            .background(NvColors.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(NvSpacing.L)
            .heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(NvShape.Input).background(NvColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (locked) Icons.Outlined.Lock else src.protocol.protocolIcon(),
                if (locked) stringResource(R.string.privacy_locked_folder) else null,
                Modifier.size(24.dp),
                tint = if (locked) NvColors.Primary else NvColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(NvSpacing.M))
        Column(Modifier.weight(1f)) {
            if (locked) {
                Text(
                    stringResource(R.string.privacy_locked_folder),
                    style = NvTypography.BodyMedium, color = NvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 地址/真名一并遮蔽：只露协议类型（不涉私密内容）
                    stringResource(src.protocol.labelRes),
                    style = NvTypography.LabelMedium, color = NvColors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    src.name,
                    style = NvTypography.BodyMedium, color = NvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,   // 2026-10-22 i18n：长源名截断出省略号
                )
                Text(
                    // 未选共享的 SMB 源：诚实标注（浏览时自动列出共享并选择）
                    when {
                        src.entity.type == SourceType.SMB && src.entity.shareOrPath.isBlank() ->
                            stringResource(R.string.files_no_share_selected)
                        else -> src.address.ifBlank { stringResource(src.protocol.labelRes) }
                    },
                    style = NvTypography.LabelMedium, color = NvColors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,   // 2026-10-22 i18n：长地址截断出省略号
                )
            }
        }
        Spacer(Modifier.width(NvSpacing.S))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(6.dp).clip(CircleShape).background(
                    when (src.state) {
                        NvSourceState.Connected -> NvColors.Success
                        NvSourceState.Failed -> NvColors.Error
                        NvSourceState.Offline -> NvColors.TextDisabled
                    },
                ),
            )
            Spacer(Modifier.width(NvSpacing.XS))
            Text(stringResource(src.state.labelRes), style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
        }
    }
}

// 虚线描边「添加源」入口卡
@Composable
private fun NvAddSourceTile(onClick: () -> Unit) {
    // Compose 无原生虚线边框：用边框 + Outline 色 + 60% 视觉近似虚线描边
    Column(
        Modifier
            .fillMaxWidth()
            .clip(NvShape.Input)
            .border(BorderStroke(1.dp, NvColors.Outline), NvShape.Input)
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(NvSpacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp), tint = NvColors.TextSecondary)
            Spacer(Modifier.width(NvSpacing.S))
            Text(stringResource(R.string.files_add_source), style = NvTypography.BodyMedium, color = NvColors.TextSecondary)
        }
        // 2026-09-16 UI 审计 P2-13：单源时下半屏大面积空板——入口卡补次级说明降空旷感
        Spacer(Modifier.height(NvSpacing.XS))
        Text(
            stringResource(R.string.files_add_source_hint),
            style = NvTypography.LabelSmall,
            color = NvColors.TextDisabled,
        )
    }
}

// SMB/WebDAV 自动发现区（NsdManager 真实发现，2.1 扫描骨架 → 设备行 → 2.2 无设备）
@Composable
internal fun NvDiscoverySection(
    devices: List<NvDiscoveredDevice>,
    scanning: Boolean,
    onDeviceClick: (NvDiscoveredDevice) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.files_discovery_title), Modifier.weight(1f), style = NvTypography.TitleMedium)
            if (devices.isNotEmpty()) {
                Text(
                    "${devices.size}",
                    Modifier.clip(NvShape.Badge).background(NvColors.PrimaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = NvTypography.LabelSmall, color = NvColors.Primary,
                )
            }
            Spacer(Modifier.width(NvSpacing.S))
            if (scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), color = NvColors.Primary, strokeWidth = 2.dp)
            }
        }
        when {
            scanning -> Column(Modifier.padding(horizontal = NvSpacing.L)) {
                Text(stringResource(R.string.files_scanning_lan), style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                repeat(2) { NvSkeletonListRow(leadingSize = 40.dp) }
            }
            devices.isEmpty() -> Text(
                stringResource(R.string.files_no_devices),
                Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                style = NvTypography.LabelMedium, color = NvColors.TextDisabled,
            )
            else -> Column(
                Modifier.padding(horizontal = NvSpacing.L).clip(NvShape.Input).background(NvColors.Surface),
            ) {
                devices.forEachIndexed { i, d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .combinedClickable(onClick = { onDeviceClick(d) }, onLongClick = {})
                            .padding(horizontal = NvSpacing.L),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(d.protocol.protocolIcon(), null, Modifier.size(20.dp), tint = NvColors.TextSecondary)
                        Spacer(Modifier.width(NvSpacing.M))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                            Text(
                                "${d.ip}:${d.port} · ${stringResource(d.protocol.labelRes)}",
                                style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
                            )
                        }
                        Icon(
                            Icons.Outlined.ChevronRight, null,
                            Modifier.size(16.dp), tint = NvColors.TextDisabled,
                        )
                    }
                    if (i < devices.lastIndex) HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
                }
            }
        }
    }
}

// ---------- 1.2 添加源向导 ----------
// 键盘黑区修复（P0-1/P0-4）：根 Column 只负责背景铺满（背景延伸到 IME 区不留黑带），
// imePadding 下放到各步内容——可滚动步 = imePadding().verticalScroll()（视口先收、内容再滚），
// 聚焦字段经 NvTextField 内建 BringIntoViewRequester 滚到键盘上方可见。
internal fun NvProtocol.protocolIcon(): ImageVector = when (this) {
    // 2026-09-16 UI 审计 P2-11：Storage 字形近乎实心、比 Cloud/Smartphone 重 → Dns 线性同族
    NvProtocol.SMB -> Icons.Outlined.Dns
    NvProtocol.WebDav -> Icons.Outlined.Cloud
    // 2026-09-29 SFTP 接入：Lan 与 Dns/Cloud 同为线性网络意象（Terminal 字形偏异族）
    NvProtocol.SFTP -> Icons.Outlined.Lan
    NvProtocol.Local -> Icons.Outlined.Smartphone
    // 2026-10-07 Jellyfin：视频库字形（与 Dns/Cloud/Lan 同为 Outlined 线性族）
    NvProtocol.Jellyfin -> Icons.Outlined.VideoLibrary
}

/** 字节大小 → 人类可读（1024 进制）；未知占位标签由调用方解析注入（i18n） */
internal fun fmtSize(bytes: Long, unknownLabel: String): String {
    if (bytes <= 0L) return unknownLabel
    val tb = bytes / 1_099_511_627_776.0
    val gb = bytes / 1_073_741_824.0
    val mb = bytes / 1_048_576.0
    val kb = bytes / 1024.0
    return when {
        tb >= 1 -> String.format("%.1f TB", tb)
        gb >= 1 -> String.format("%.1f GB", gb)
        mb >= 1 -> String.format("%.1f MB", mb)
        kb >= 1 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
