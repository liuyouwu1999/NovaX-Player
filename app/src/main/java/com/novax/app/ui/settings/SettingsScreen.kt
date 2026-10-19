package com.novax.app.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.player.KEY_AUDIO_LANG
import com.novax.app.player.DEFAULT_DOUBLE_TAP_SEC
import com.novax.app.player.KEY_DOUBLE_TAP_SEC
import com.novax.app.player.KEY_LONG_PRESS_SPEED
import com.novax.app.player.KEY_NET_TIMEOUT_SEC
import com.novax.app.player.KEY_PRESERVE_PITCH
import com.novax.app.player.KEY_SMB_BLOCK
import com.novax.app.player.KEY_SPEED_MODE
import com.novax.app.player.KEY_SUB_ENCODING
import com.novax.app.player.KEY_SUB_LANGUAGES
import com.novax.app.player.KEY_UI_POSTER_COLUMNS
import com.novax.app.player.KEY_UI_START_TAB
import com.novax.app.ui.components.NvSettingsCard
import com.novax.app.ui.components.nvBarCollapse
import com.novax.app.ui.components.NvSettingsRow
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.policy.NvPolicyScreen
import com.novax.app.ui.policy.PolicyDoc
import com.novax.app.ui.theme.NvColors

/**
 * C10 · 设置入口页（底部 Tab 5）+ 组内页路由（WS4）。
 * 返回语义（C10 §2）：组内页/致谢页/政策文档页返回 = 上一级设置页（BackHandler + NvTopBar onBack）；
 * 设置 Tab 本身无返回键。子页面为页内状态，不经全局 NavHost。
 */

/** C10 §2 七个设置分组 + C12 §六 新增「媒体库」组 */
enum class SettingsGroup { PLAYBACK, SUBTITLE, AUDIO, NETWORK, LIBRARY, UI, PRIVACY, ABOUT }

/** Preferences 空安全读取：null（DataStore 未加载）回退默认值 */
internal fun <T> Preferences?.pref(key: Preferences.Key<T>, default: T): T = this?.get(key) ?: default

@Composable
fun SettingsScreen(onOpenHistory: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel()
    val prefs by vm.prefs.collectAsState()
    val backExit by vm.backExit.collectAsState()
    val historyCount by vm.historyCount.collectAsState()

    // 组内页 / 致谢页 / 政策文档页为页内状态；rememberSaveable 保证旋转后不丢层级
    var group by rememberSaveable { mutableStateOf<SettingsGroup?>(null) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    // 2026-10-16 v1.0 公开发布（spec §1.4）：合规文档页（隐私政策/用户协议）为页内状态
    var showPolicyDoc by rememberSaveable { mutableStateOf<PolicyDoc?>(null) }

    BackHandler(enabled = group != null || showLicenses || showPolicyDoc != null) {
        when {
            showPolicyDoc != null -> showPolicyDoc = null
            showLicenses -> showLicenses = false
            else -> group = null
        }
    }

    when {
        showLicenses -> LicensesScreen(onBack = { showLicenses = false })
        showPolicyDoc != null -> NvPolicyScreen(showPolicyDoc!!) { showPolicyDoc = null }
        group != null -> SettingsGroupPageRouter(
            group = group!!,
            vm = vm,
            prefs = prefs,
            backExit = backExit,
            historyCount = historyCount,
            onBack = { group = null },
            onOpenHistory = onOpenHistory,
            onOpenLicenses = { showLicenses = true },
            onOpenPolicyDoc = { showPolicyDoc = it },
        )
        else -> SettingsHome(
            prefs = prefs,
            historyCount = historyCount,
            onOpenGroup = { group = it },
        )
    }
}

// ---------------- 值摘要标签（入口页 + 组内页共用） ----------------

internal fun subLangLabel(code: String): String = when (code) {
    "zh" -> "中文"; "en" -> "English"; "ja" -> "日本語"; else -> code
}

internal fun encodingLabel(v: String): String = when (v) {
    "utf-8" -> "UTF-8"; "gbk" -> "GBK"; "big5" -> "BIG5"; else -> "自动"
}

internal fun audioLangLabel(v: String): String = when (v) {
    "zh" -> "中文"; "en" -> "English"; "ja" -> "日本語"; else -> "跟随文件"
}

internal fun posterColumnsLabel(v: String): String = when (v) {
    "2" -> "2 列"; "3" -> "3 列"; "4" -> "4 列"; else -> "自适应"
}

internal fun startTabLabel(v: String): String = when (v) {
    "library" -> "媒体库"; "files" -> "文件"; else -> "首页"
}

/** 版本号（PackageManager：buildConfig 已启用(2026-10-13 §2 取证注入门控)，版本号仍走 PackageManager、不依赖 BuildConfig 字段） */
internal fun versionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrDefault("?")

// ---------------- 入口页（C10 §4.1 分组卡列表） ----------------

private data class GroupEntry(
    val group: SettingsGroup,
    val icon: ImageVector,
    val title: String,
)

private val groupEntries = listOf(
    GroupEntry(SettingsGroup.PLAYBACK, Icons.Outlined.PlayCircle, "播放器"),
    GroupEntry(SettingsGroup.SUBTITLE, Icons.Outlined.Subtitles, "字幕"),
    GroupEntry(SettingsGroup.AUDIO, Icons.Outlined.VolumeUp, "音频"),
    GroupEntry(SettingsGroup.NETWORK, Icons.Outlined.Lan, "网络 / NAS"),
    GroupEntry(SettingsGroup.LIBRARY, Icons.Outlined.VideoLibrary, "媒体库"),
    GroupEntry(SettingsGroup.UI, Icons.Outlined.Palette, "界面"),
    GroupEntry(SettingsGroup.PRIVACY, Icons.Outlined.Shield, "隐私与数据"),
    GroupEntry(SettingsGroup.ABOUT, Icons.Outlined.Info, "关于"),
)

/** 入口页分组（2026-09-16 UI 审计 P2-10）：每张组卡包含的行 */
private val settingCardGroups: List<List<GroupEntry>> = listOf(
    groupEntries.subList(0, 3),   // 播放器 / 字幕 / 音频
    groupEntries.subList(3, 6),   // 网络 / 媒体库 / 界面
    groupEntries.subList(6, 8),   // 隐私与数据 / 关于
)

/** 各分组值摘要（nPlayer 式动态摘要：随设置实时变，C10 §4.1） */
private fun summaryOf(group: SettingsGroup, prefs: Preferences?, historyCount: Int): String =
    when (group) {
        SettingsGroup.PLAYBACK -> {
            val mode = prefs.pref(KEY_SPEED_MODE, "fixed")
            "默认 ${if (mode == "remember") "记忆" else "1x"}" +
                " · 长按 ${prefs.pref(KEY_LONG_PRESS_SPEED, 2f).toInt()}x" +
                " · 双击 ${prefs.pref(KEY_DOUBLE_TAP_SEC, DEFAULT_DOUBLE_TAP_SEC)}s"
        }
        SettingsGroup.SUBTITLE -> {
            val langs = prefs.pref(KEY_SUB_LANGUAGES, "zh").split(',')
                .filter { it.isNotBlank() }.joinToString("/") { subLangLabel(it) }
            "$langs · ${encodingLabel(prefs.pref(KEY_SUB_ENCODING, "auto"))}编码"
        }
        SettingsGroup.AUDIO ->
            "${audioLangLabel(prefs.pref(KEY_AUDIO_LANG, "auto"))} · " +
                if (prefs.pref(KEY_PRESERVE_PITCH, true)) "变速不变调" else "变速变调"
        SettingsGroup.NETWORK ->
            "超时 ${prefs.pref(KEY_NET_TIMEOUT_SEC, 8)}s · 块 ${prefs.pref(KEY_SMB_BLOCK, "4MB")}"
        SettingsGroup.LIBRARY ->
            "自动索引 ${if (prefs.pref(KEY_LIB_AUTO_SCAN, true)) "开" else "关"} · " +
                "刮削 ${if (prefs.pref(KEY_LIB_SCRAPE_GLOBAL, false)) "开" else "关"}"
        SettingsGroup.UI ->
            "${posterColumnsLabel(prefs.pref(KEY_UI_POSTER_COLUMNS, "auto"))} · " +
                "启动进${startTabLabel(prefs.pref(KEY_UI_START_TAB, "home"))}"
        SettingsGroup.PRIVACY -> "$historyCount 条观看记录"
        SettingsGroup.ABOUT -> "NovaX"
    }

@Composable
private fun SettingsHome(
    prefs: Preferences?,
    historyCount: Int,
    onOpenGroup: (SettingsGroup) -> Unit,
) {
    val context = LocalContext.current
    val version = remember { versionLabel(context) }
    // 2026-10-19 用户需求：打赏入口自关于页上移至设置首页
    var showDonate by remember { mutableStateOf(false) }
    // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（IME 打开→0dp 不留背景空带）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // Liquid Glass（decisions.md §4.5）：source 挂列表滚动容器，NvTopBar 悬浮做兄弟（页面翻转配方）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：设置首页顶栏静止态隐形——亮板「白卡压灰底」的实色缝随背景渐隐消除
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    // rework3：enterAlways 顶栏收起（仅设置首页；组内页为二级推入页不收）
    val barCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(listState)
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        LazyColumn(
            Modifier.fillMaxSize().nvGlassSource(glassEnv).background(NvColors.Background),

            state = listState,
            contentPadding = PaddingValues(
                start = NvSpacing.L,
                top = topInset,
                end = NvSpacing.L,
                bottom = NvSpacing.XL + glassBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(NvSpacing.L),
        ) {
            // 2026-09-16 UI 审计 P2-10：8 张单行卡 → 3 张分组卡（组内 divider），密度对齐
            // Android 设置惯例；间距 XL→L
            items(settingCardGroups, key = { it.hashCode() }) { groupEntriesInCard ->
                NvSettingsCard(title = null) {
                    groupEntriesInCard.forEachIndexed { index, entry ->
                        NvSettingsRow(
                            title = entry.title,
                            onClick = { onOpenGroup(entry.group) },
                            showDivider = index < groupEntriesInCard.lastIndex,
                            leading = {
                                Icon(entry.icon, null, Modifier.size(24.dp), tint = NvColors.TextSecondary)
                            },
                        ) {
                            Text(
                                if (entry.group == SettingsGroup.ABOUT) version
                                else summaryOf(entry.group, prefs, historyCount),
                                style = NvTypography.LabelMedium,
                                color = NvColors.TextSecondary,
                            )
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                Modifier.padding(start = NvSpacing.XS).size(16.dp),
                                tint = NvColors.TextDisabled,
                            )
                        }
                    }
                }
            }
            // 2026-10-19 用户需求：打赏作者独立卡（自关于页上移，完全免费口径下自愿支持）
            item {
                NvSettingsCard(title = null) {
                    NvSettingsRow(
                        title = "打赏作者",
                        onClick = { showDonate = true },
                        showDivider = false,
                        leading = {
                            Icon(Icons.Outlined.Favorite, null, Modifier.size(24.dp), tint = NvColors.TextSecondary)
                        },
                    ) {
                        Text(
                            // 2026-10-19 用户定稿文案
                            "软件免费，自愿打赏",
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                        )
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            Modifier.padding(start = NvSpacing.XS).size(16.dp),
                            tint = NvColors.TextDisabled,
                        )
                    }
                }
            }
        }
        // 2026-10-19 用户需求：打赏弹窗宿主随入口上移
        if (showDonate) {
            NvDonateDialog(onDismiss = { showDonate = false })
        }
        NvTopBar(
            "设置",
            modifier = Modifier.nvBarCollapse(barCollapsed),   // rework3：下滑收起/上滑出现
            scrolled = scrolled,
        )
    }
}
