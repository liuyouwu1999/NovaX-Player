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
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
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
internal fun AddSourceWizardScreen(
    vm: FilesViewModel,
    wizard: NvWizardUiState,
    wizardBrowser: FilesViewModel.WizardBrowse?,
    devices: List<NvDiscoveredDevice>,
    scanning: Boolean,
) {
    // Liquid Glass（spec §9 页面翻转配方）：NvTopBar 悬浮做玻璃层（Box 末尾声明），
    // source 挂表单流内容容器（全高 + background 在 source 后，§4.5a），内容经 topInset 避让
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        Column(
            Modifier.fillMaxSize().nvGlassSource(glassEnv).background(NvColors.Background)
                .padding(top = topInset),
        ) {
            NvStepProgress(wizard.step)
            when (wizard.step) {
                0 -> StepProtocol(vm, devices, scanning)
                1 -> StepCredential(vm, wizard)
                else -> StepConfirmDirs(vm, wizardBrowser)
            }
        }
        NvTopBar(
            title = when (wizard.step) {
                0 -> "添加源"
                1 -> if (wizard.original != null) "编辑源 · ${wizard.protocol.label}" else "${wizard.protocol.label} · 地址与凭据"
                else -> "选择媒体目录（可多选）"
            },
            onBack = vm::onWizardBack,
            actions = when (wizard.step) {
                // 2026-10-19 用户反馈：表单步右上角常驻「下一步」——长表单（键盘再一开）
                // 免划到最底才能进下一步；与底部按钮同调 goNext（测试中置灰，进度横幅同源）
                1 -> listOf(
                    {
                        NvTextButton(
                            "下一步",
                            onClick = vm::goNext,
                            enabled = !wizard.connecting,
                        )
                    },
                )
                2 -> listOf(
                    {
                        NvTextButton(
                            "全选",
                            onClick = vm::selectAllWizardDirs,
                            enabled = wizardBrowser?.loading != true,
                        )
                    },
                )
                else -> emptyList()
            },
        )
    }
}

@Composable
internal fun NvStepProgress(step: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        horizontalArrangement = Arrangement.spacedBy(NvSpacing.XS),
    ) {
        repeat(3) { i ->
            Box(
                Modifier.weight(1f).height(3.dp).clip(NvShape.DividerHandle)
                    .background(if (i <= step) NvColors.Primary else NvColors.SurfaceElevated),
            )
        }
    }
}

@Composable
internal fun StepProtocol(vm: FilesViewModel, devices: List<NvDiscoveredDevice>, scanning: Boolean) {
    val context = LocalContext.current
    // 本地源需要媒体读取权限（按系统版本选权限），授权后再进表单
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    // 助手须先于 launcher 声明：Kotlin 局部函数不可被前向引用，下方回调闭包要捕获它
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.onProtocolSelect(NvProtocol.Local)
        // E3-10（发版审查）：拒绝授权不再静默进目录选择步（深处「下一步」才报目录不可读）——
        // 留在协议步并给一行可读说明。
        else vm.onLocalPermissionDenied()
    }
    // 2026-09-18：R+ 额外请求「所有文件访问」——范围存储会把 RMVB/外挂字幕等非媒体文件
    // 对 File API 隐藏，本地播放器必须全量可见；授完返回自动进本地源流程
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // E3-10（发版审查）：设置页返回时复查真实授权态，未授予同样留在协议步提示
        if (hasAllFilesAccess()) vm.onProtocolSelect(NvProtocol.Local) else vm.onLocalPermissionDenied()
    }

    // Liquid Glass（§5.5 内容避让，2026-10-16 排查补齐）：协议步无吸底 CTA，但小屏上内容可滚，
    // 末尾「本地存储」行同样会被玻璃底栏压住——与凭据步同配方（bottom 追加 glassBottom）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(
                top = NvSpacing.L,
                start = NvSpacing.L,
                end = NvSpacing.L,
                bottom = NvSpacing.L + glassBottom,
            ),
    ) {
        // E3-10（发版审查）：协议步补 error 渲染位——权限被拒留在本步时给一行可读说明
        //（此前 error 只在凭据步/目录步渲染，协议步拒绝授权后界面毫无反应）
        val wizard by vm.wizard.collectAsState()
        wizard.error?.let { err ->
            Row(
                Modifier.fillMaxWidth().clip(NvShape.Input)
                    .background(NvColors.Error.copy(alpha = 0.12f)).padding(NvSpacing.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = NvColors.Error)
                Spacer(Modifier.width(NvSpacing.S))
                Column(Modifier.weight(1f)) {
                    Text(err.title, style = NvTypography.BodyMedium, color = NvColors.Error, fontWeight = FontWeight.SemiBold)
                    Text(err.message, style = NvTypography.LabelMedium, color = NvColors.Error)
                }
            }
            Spacer(Modifier.height(NvSpacing.M))
        }
        NvDiscoverySection(devices, scanning, onDeviceClick = vm::onDeviceClick)
        Spacer(Modifier.height(NvSpacing.XL))
        Text("选择协议", style = NvTypography.TitleMedium)
        Spacer(Modifier.height(NvSpacing.M))
        // 2026-09-29：SFTP 接入（sshj）；2026-10-07 Jellyfin 加入——四协议 2×2 宫格
        //（Local 保留下方独立行走存储权限）
        listOf(NvProtocol.SMB, NvProtocol.WebDav, NvProtocol.SFTP, NvProtocol.Jellyfin).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(NvSpacing.M)) {
                row.forEach { p ->
                    Column(
                        Modifier.weight(1f).height(88.dp).clip(NvShape.Input).background(NvColors.Surface)
                            .combinedClickable(onClick = { vm.onProtocolSelect(p) }, onLongClick = {})
                            .padding(NvSpacing.M),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(p.protocolIcon(), null, Modifier.size(32.dp), tint = NvColors.Primary)
                        Spacer(Modifier.height(NvSpacing.S))
                        Text(p.label, style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(NvSpacing.M))
        }
        // 本地存储单独一行（存储权限）
        Row(
            Modifier.fillMaxWidth().clip(NvShape.Input).background(NvColors.Surface)
                .combinedClickable(
                    onClick = {
                        if (hasAllFilesAccess()) {
                            vm.onProtocolSelect(NvProtocol.Local)
                        } else if (Build.VERSION.SDK_INT >= 30) {
                            allFilesLauncher.launch(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } else {
                            permLauncher.launch(permission)
                        }
                    },
                    onLongClick = {},
                )
                .padding(NvSpacing.M).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Smartphone, null, Modifier.size(24.dp), tint = NvColors.Primary)
            Spacer(Modifier.width(NvSpacing.M))
            Column(Modifier.weight(1f)) {
                Text("本地存储", style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                Text(
                    "读取本机目录中的视频",
                    style = NvTypography.LabelSmall, color = NvColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
internal fun StepCredential(vm: FilesViewModel, wizard: NvWizardUiState) {
    // Liquid Glass（§5.5 吸底 CTA 避让，2026-10-16 用户截图实证）：凭据/编辑源步「下一步」是
    // 页内流尾部 CTA——向导内嵌 files Tab、玻璃底栏在场，padding 在 verticalScroll 之后=随内容
    // 滚动，bottom 追加 glassBottom（IME 打开→0dp）让滚到底时按钮浮出玻璃胶囊上方。
    // StepConfirmDirs 已修（4167d52），本步此前漏改（DESIGN-SYSTEM.md §5.5）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(
                top = NvSpacing.L,
                start = NvSpacing.L,
                end = NvSpacing.L,
                bottom = NvSpacing.L + glassBottom,
            ),
    ) {
        wizard.error?.let { err ->
            Row(
                Modifier.fillMaxWidth().clip(NvShape.Input)
                    .background(NvColors.Error.copy(alpha = 0.12f)).padding(NvSpacing.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = NvColors.Error)
                Spacer(Modifier.width(NvSpacing.S))
                Column(Modifier.weight(1f)) {
                    Text(err.title, style = NvTypography.BodyMedium, color = NvColors.Error, fontWeight = FontWeight.SemiBold)
                    Text(err.message, style = NvTypography.LabelMedium, color = NvColors.Error)
                }
            }
            Spacer(Modifier.height(NvSpacing.M))
        }
        when (wizard.protocol) {
            NvProtocol.SMB -> {
                NvTextField("主机（IP 或主机名）", wizard.host, vm::onHost, placeholder = "192.168.1.10")
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("端口", wizard.port, vm::onPort, placeholder = vm.defaultPortLabel(wizard.protocol, wizard.https))
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("域（可选）", wizard.domain, vm::onDomain, placeholder = "WORKGROUP")
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("共享名", wizard.shareOrPath, vm::onShareOrPath, placeholder = "留空 = 进入时自动列出共享")
                NvSharePicker(vm, wizard)
            }
            // N3 源地址全裸 host（2026-10-09 用户需求：协议头不该让用户填）：只填 IP/域名，
            // 加密用 HTTPS 开关承载；粘贴带 http(s):// 前缀由 onHost 自动剥离+端口拆分
            NvProtocol.WebDav -> {
                NvTextField("主机（IP 或域名）", wizard.host, vm::onHost, placeholder = "192.168.1.10 或 nas.example.com")
                Spacer(Modifier.height(NvSpacing.M))
                NvSwitchRow(
                    title = "HTTPS",
                    checked = wizard.https,
                    onChecked = vm::onHttps,
                    subtitle = "加密连接，端口默认随开关 80↔443",
                    showDivider = false,
                )
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("端口", wizard.port, vm::onPort, placeholder = vm.defaultPortLabel(wizard.protocol, wizard.https))
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("根路径（可选）", wizard.shareOrPath, vm::onShareOrPath, placeholder = "/dav")
            }
            // 2026-09-29 SFTP 表单（sshj 接入）：host 必填，端口默认 22，根路径空 = 登录家目录
            NvProtocol.SFTP -> {
                NvTextField("主机（IP 或主机名）", wizard.host, vm::onHost, placeholder = "192.168.1.10")
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("端口", wizard.port, vm::onPort, placeholder = vm.defaultPortLabel(wizard.protocol, wizard.https))
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("根路径（可选）", wizard.shareOrPath, vm::onShareOrPath, placeholder = "留空 = 登录家目录，如 /volume1/videos")
            }
            NvProtocol.Local -> NvLocalFolderPicker(
                initialPath = wizard.shareOrPath.ifBlank { LOCAL_BROWSER_ROOT },
                selectedPath = wizard.shareOrPath,
                // 2026-09-18 用户反馈：本地源默认名应取所选文件夹名（昵称仍可手改）；
                // 最终兜底「我的手机」见 wizardEntity
                onPick = { path ->
                    vm.onShareOrPath(path)
                    if (wizard.nickname.isBlank()) {
                        vm.onNickname(path.trimEnd('/').substringAfterLast('/'))
                    }
                },
            )
            // 2026-10-07 Jellyfin 表单；N3（2026-10-09）：地址只填 IP/域名，HTTPS 开关承载加密
            //（裸存时 client 侧 normalizeBaseUrl 补 http），粘贴带 scheme 自动剥离；端口留空 = 8096；
            // 账号/密码在下方通用凭据区恒必填（无匿名开关、无共享名/根路径）
            NvProtocol.Jellyfin -> {
                NvTextField("服务器地址", wizard.host, vm::onHost, placeholder = "192.168.1.100")
                Spacer(Modifier.height(NvSpacing.M))
                NvSwitchRow(
                    title = "HTTPS",
                    checked = wizard.https,
                    onChecked = vm::onHttps,
                    subtitle = "加密连接，端口默认随开关 8096↔443",
                    showDivider = false,
                )
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("端口", wizard.port, vm::onPort, placeholder = vm.defaultPortLabel(wizard.protocol, wizard.https))
            }
        }
        Spacer(Modifier.height(NvSpacing.M))
        NvTextField(
            "昵称（可选）",
            wizard.nickname,
            vm::onNickname,
            // 本地源不叫「我的 NAS」：占位符提示默认取文件夹名，兜底「我的手机」
            placeholder = if (wizard.protocol == NvProtocol.Local) "选文件夹后自动填，兜底「我的手机」" else "我的 NAS",
        )
        if (wizard.protocol != NvProtocol.Local) {
            // 2026-10-07 Jellyfin：Media Server 必须真认证，无「匿名」开关
            if (wizard.protocol != NvProtocol.Jellyfin) {
                Spacer(Modifier.height(NvSpacing.M))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("匿名访问", Modifier.weight(1f), style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                    Switch(
                        checked = wizard.anonymous,
                        onCheckedChange = vm::onAnonymous,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = NvColors.Primary,
                            uncheckedTrackColor = NvColors.SurfaceElevated,
                        ),
                    )
                }
            }
            if (!wizard.anonymous || wizard.protocol == NvProtocol.Jellyfin) {
                Spacer(Modifier.height(NvSpacing.M))
                NvTextField("账号", wizard.user, vm::onUser)
                Spacer(Modifier.height(NvSpacing.M))
                NvPasswordField("密码", wizard.pass, vm::onPass)
            }
        }
        Spacer(Modifier.height(NvSpacing.L))
        // 2026-09-18 用户反馈：取消独立「测试连接」按钮——下一步即测试，成功进目录步、
        // 失败在本步顶部错误横幅给出原因（去掉了旧的绿色「连接成功」横幅与双按钮行）
        Text(
            if (wizard.connecting) "正在测试连接 ${wizard.host.ifBlank { wizard.shareOrPath }}…" else "",
            style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
        )
        NvPrimaryButton(
            if (wizard.connecting) "测试中…" else "下一步",
            vm::goNext,
            Modifier.fillMaxWidth(),
            enabled = !wizard.connecting,
        )
    }
}

// 本地源目录浏览器起点：主存储根（所有可浏览目录都在它之下）
internal const val LOCAL_BROWSER_ROOT = "/storage/emulated/0"

// 本地源目录浏览器（2026-09-18 用户反馈：不该让用户手输绝对路径——改为互动式选择：
// 点目录进入、行尾圆圈选定子目录、底部「选定当前所在目录」；选定即回填向导目录值，
// 之后照常「测试连接」→「下一步」。
@Composable
internal fun NvLocalFolderPicker(initialPath: String, selectedPath: String, onPick: (String) -> Unit) {
    var current by remember { mutableStateOf(initialPath) }
    // 2026-09-18 用户反馈：浏览中按系统返回/侧滑先回上一级，到存储根才放行退出向导
    BackHandler(enabled = current != LOCAL_BROWSER_ROOT) {
        current = File(current).parentFile?.takeIf { it.path.startsWith("/storage/emulated") }?.path
            ?: LOCAL_BROWSER_ROOT
    }
    val parent = remember(current) { File(current).parentFile?.takeIf { it.path.startsWith("/storage") || it.path == "/" } }
    val dirs = remember(current) {
        runCatching {
            File(current).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedWith(
                    compareByDescending<File> { f -> f.name.lowercase() in setOf("download", "movies", "dcim") }
                        .thenBy { it.name.lowercase() },
                )
        }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(NvShape.Input)
            .background(NvColors.SurfaceElevated),
    ) {
        // 当前所在目录
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NvSpacing.M, vertical = NvSpacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = NvColors.Primary)
            Spacer(Modifier.width(NvSpacing.S))
            Text(
                current,
                style = NvTypography.LabelSmall,
                color = NvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
        // 目录列表（限高独立滚动；外层是 verticalScroll，不能用 LazyColumn）
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (parent != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { current = parent.path }
                        .padding(horizontal = NvSpacing.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ArrowUpward, "返回上级", Modifier.size(20.dp), tint = NvColors.TextSecondary)
                    Spacer(Modifier.width(NvSpacing.M))
                    Text("返回上级", style = NvTypography.BodyMedium, color = NvColors.TextSecondary)
                }
            }
            dirs?.forEach { dir ->
                val isSel = dir.absolutePath == selectedPath
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { current = dir.absolutePath }
                        .padding(horizontal = NvSpacing.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = NvColors.TextSecondary)
                    Spacer(Modifier.width(NvSpacing.M))
                    Text(
                        dir.name,
                        style = NvTypography.BodyMedium,
                        color = NvColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 行尾圆圈：选定该目录作为源目录（不进入）
                    IconButton(onClick = { onPick(dir.absolutePath) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (isSel) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            if (isSel) "已选定此目录" else "选定此目录",
                            tint = if (isSel) NvColors.Primary else NvColors.TextSecondary,
                        )
                    }
                }
            }
            if (dirs == null) {
                Text(
                    "无法读取该目录（存储权限未授予或路径无效），请点「返回上级」换一个目录",
                    style = NvTypography.LabelMedium,
                    color = NvColors.Error,
                    modifier = Modifier.padding(horizontal = NvSpacing.M, vertical = NvSpacing.M),
                )
            } else if (dirs.isEmpty() && parent != null) {
                Text(
                    "此目录下没有子文件夹",
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NvSpacing.M, vertical = NvSpacing.M),
                )
            }
        }
        HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
        // 底部：选定当前所在目录（浏览到位后一键确认）
        val curSel = current == selectedPath
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onPick(current) }
                .padding(horizontal = NvSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (curSel) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                null,
                Modifier.size(20.dp),
                tint = NvColors.Primary,
            )
            Spacer(Modifier.width(NvSpacing.M))
            Text(
                if (curSel) "已选定当前目录，可点下方「测试连接」" else "选定当前所在目录",
                style = NvTypography.BodyMedium,
                color = NvColors.Primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// 共享列表选择器（jcifs-ng 枚举）：主机+账号已填时可拉取，点击共享名回填输入框；
// 失败显示可重试的错误行，不打断手动输入。
@Composable
internal fun NvSharePicker(vm: FilesViewModel, wizard: NvWizardUiState) {
    Spacer(Modifier.height(NvSpacing.M))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "共享列表",
            Modifier.weight(1f),
            style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
        )
        if (wizard.sharesLoading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = NvColors.Primary, strokeWidth = 2.dp)
            Spacer(Modifier.width(NvSpacing.S))
        }
        NvTextButton(
            if (wizard.shares.isNotEmpty() || wizard.sharesError != null) "重新获取" else "获取共享列表",
            vm::fetchShares,
            enabled = wizard.canFetchShares && !wizard.sharesLoading,
        )
    }
    when {
        wizard.sharesError != null -> Row(
            Modifier.fillMaxWidth().clip(NvShape.Input)
                .background(NvColors.Error.copy(alpha = 0.12f)).padding(NvSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = NvColors.Error)
            Spacer(Modifier.width(NvSpacing.S))
            Text(
                wizard.sharesError,
                Modifier.weight(1f),
                style = NvTypography.LabelMedium, color = NvColors.Error,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            NvTextButton("重试", vm::fetchShares, enabled = !wizard.sharesLoading)
        }
        wizard.shares.isNotEmpty() -> Column(
            Modifier.fillMaxWidth().clip(NvShape.Input).background(NvColors.Surface),
        ) {
            val selected = wizard.shareOrPath.trim().trim('/').uppercase()
            wizard.shares.forEachIndexed { i, name ->
                val isSelected = name.uppercase() == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .combinedClickable(onClick = { vm.onShareSelect(name) }, onLongClick = {})
                        .padding(horizontal = NvSpacing.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Folder, null, Modifier.size(18.dp),
                        tint = if (isSelected) NvColors.Primary else NvColors.TextSecondary,
                    )
                    Spacer(Modifier.width(NvSpacing.M))
                    Text(
                        name,
                        Modifier.weight(1f),
                        style = NvTypography.BodyMedium,
                        color = if (isSelected) NvColors.Primary else NvColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (isSelected) {
                        Icon(Icons.Outlined.Check, "已选择", Modifier.size(16.dp), tint = NvColors.Primary)
                    }
                }
                if (i < wizard.shares.lastIndex) HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
            }
        }
    }
}

// Step 2 目录确认（真实列目录）+ 目录多选（扫描根）：点击行进入目录，行尾勾选圈标记扫描根；
// 未选任何目录 = 保存后扫整个源（回退现状），已选 = 写入 sources.scanRoots 逐根扫描
@Composable
internal fun StepConfirmDirs(
    vm: FilesViewModel,
    browse: FilesViewModel.WizardBrowse?,
) {
    val wizard by vm.wizard.collectAsState()
    // 2026-09-18 用户反馈：目录浏览中按系统返回/侧滑，应回上一级目录而不是直接退回配置源步；
    // 已在源根（无子目录层级）时才放行给向导返回
    BackHandler(enabled = !browse?.breadcrumb.isNullOrEmpty()) {
        vm.wizardBreadcrumbTo(browse!!.breadcrumb.size - 2)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        wizard.error?.let { Text("${it.title}：${it.message}", color = NvColors.Primary, modifier = Modifier.padding(NvSpacing.L)) }
        NvBreadcrumb(
            listOf("根") + (browse?.breadcrumb ?: emptyList()),
            onSelect = { depth -> vm.wizardBreadcrumbTo(depth - 1) },
        )
        // 多选说明行：未选 = 扫整个源；已选 N 个 = 只扫这些目录
        Text(
            if (wizard.selectedScanDirs.isEmpty()) {
                "未勾选目录时将扫描整个源；点行尾圆圈可只扫描指定目录"
            } else {
                "已选 ${wizard.selectedScanDirs.size} 个扫描目录（含子目录）"
            },
            Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
        )
        // 2026-09-28 安全加固（审查 P3）：WebDAV 明文 HTTP + Basic 认证组合，在保存前的
        // 确认步给一次性内联警示（与凭据步 http:// 前缀提示同款式样），只提示不阻断保存
        //（N3 2026-10-09：host 已统一裸存，明文判定改按 HTTPS 开关——提示文案/位置不动）
        if (
            wizard.protocol == NvProtocol.WebDav &&
            !wizard.https &&
            !wizard.anonymous && wizard.user.isNotBlank()
        ) {
            Text(
                "明文 HTTP + Basic 认证：口令将以 Base64 明文传输，同一网络的任何人可截获，建议使用 https://",
                Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                style = NvTypography.LabelSmall, color = NvColors.TextSecondary,
            )
        }
        when {
            browse == null || browse.loading -> Column {
                repeat(6) { NvSkeletonListRow(leadingSize = 24.dp) }
            }
            browse.errorMessage != null -> NvErrorState(
                title = "目录读取失败",
                message = browse.errorMessage,
                onRetry = { vm.wizardBreadcrumbTo(browse.breadcrumb.size - 1) },
            )
            browse.entries.isEmpty() -> NvEmptyState(
                Icons.Outlined.FolderOff, "此文件夹为空", "返回上一级，或直接保存源",
            )
            else -> LazyColumn(Modifier.weight(1f)) {
                items(browse.entries, key = { it.path }) { e ->
                    val isShareEntry = SmbShareRoot.shareNameOf(e.path) != null
                    val selected = NvPaths.normalize(e.path) in wizard.selectedScanDirs
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .combinedClickable(
                                onClick = {
                                    if (e.isDir) {
                                        // 虚拟共享条目（共享名留空源）：回填共享名并列该共享根
                                        if (isShareEntry) {
                                            vm.onWizardShareEntry(e)
                                        } else {
                                            vm.openWizardDir(e.name)
                                        }
                                    }
                                },
                                onLongClick = {},
                            )
                            .padding(horizontal = NvSpacing.L),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.CheckCircle,
                            null, Modifier.size(24.dp), tint = NvColors.TextSecondary,
                        )
                        Spacer(Modifier.width(NvSpacing.M))
                        Text(
                            e.name, Modifier.weight(1f),
                            style = NvTypography.BodyMedium, color = NvColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (e.isDir) {
                            // 勾选圈 = 扫描根多选（目录与虚拟共享条目均可勾）
                            IconButton(
                                onClick = { vm.toggleWizardDir(e) },
                            ) {
                                NvSelectionCircle(selected = selected)
                            }
                        } else {
                            Text(
                                fmtSize(e.size),
                                style = NvTypography.LabelSmall, color = NvColors.TextDisabled,
                            )
                        }
                    }
                    HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
                }
            }
        }
        // Liquid Glass（终审必修，Task 7 修复轮）：本块为吸底 CTA——向导内嵌 files Tab、玻璃底栏
        // 在场，bottom 追加 glassBottom（=72dp 底栏 + 导航栏 inset，IME 打开→0dp，与五 Tab 页同配方；
        // 单加 navigationBarsPadding 不够，玻璃底栏才是主遮挡物）。§9 翻转配方只内建 topInset，
        // 含吸底 CTA 的页内流须自行处理 bottom insets（DESIGN-SYSTEM.md §5.5）
        val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
        Column(
            Modifier.fillMaxWidth().background(NvColors.Background)
                .padding(bottom = glassBottom),
        ) {
            HorizontalDivider(color = NvColors.Divider, thickness = 1.dp)
            NvPrimaryButton(
                if (wizard.selectedScanDirs.isEmpty()) "保存源（扫描整个源）"
                else "保存源（扫描已选 ${wizard.selectedScanDirs.size} 个目录）",
                onClick = vm::confirmAddSource,
                enabled = wizard.testSuccess && browse?.loading == false,
                loading = wizard.saving,
                modifier = Modifier.fillMaxWidth().padding(NvSpacing.L),
            )
        }
    }
}

// ---------- 1.3 文件浏览器页（C5 二期四件套：类型过滤 / 排序 / 目录内搜索 / 多选） ----------
