package com.novax.app.ui.settings

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.res.stringResource
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
import com.novax.app.R
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
fun SettingsScreen(onOpenHistory: () -> Unit = {}, onGoToFiles: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel()
    val prefs by vm.prefs.collectAsState()
    val backExit by vm.backExit.collectAsState()
    val language by vm.language.collectAsState()
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
            language = language,
            historyCount = historyCount,
            onBack = { group = null },
            onOpenHistory = onOpenHistory,
            onGoToFiles = onGoToFiles,
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

// ---------------- 值摘要标签（入口页 + 组内页共用；i18n 后标签函数为 @Composable，唯一调用链 Text → summaryOf） ----------------

/** 语言自名标签（中文/繁體中文/English/한국어/日本語）为白名单专名，不资源化（spec §1.2；Task 13 补 ko；2026-10-22 补 zh-TW） */
@Composable
internal fun subLangLabel(code: String): String = when (code) {
    "zh" -> "中文"; "zh-TW" -> "繁體中文"; "en" -> "English"; "ko" -> "한국어"; "ja" -> "日本語"; else -> code
}

@Composable
internal fun encodingLabel(v: String): String = when (v) {
    "utf-8" -> "UTF-8"; "gbk" -> "GBK"; "big5" -> "BIG5"; else -> stringResource(R.string.settings_encoding_auto)
}

@Composable
internal fun audioLangLabel(v: String): String = when (v) {
    "zh" -> "中文"; "en" -> "English"; "ja" -> "日本語"; else -> stringResource(R.string.settings_follow_file)
}

@Composable
internal fun posterColumnsLabel(v: String): String = when (v) {
    "2" -> stringResource(R.string.settings_columns_count, 2)
    "3" -> stringResource(R.string.settings_columns_count, 3)
    "4" -> stringResource(R.string.settings_columns_count, 4)
    else -> stringResource(R.string.settings_columns_adaptive)
}

@Composable
internal fun startTabLabel(v: String): String = when (v) {
    "library" -> stringResource(R.string.settings_tab_library)
    "files" -> stringResource(R.string.settings_tab_files)
    else -> stringResource(R.string.settings_tab_home)
}

/** 版本号（PackageManager：buildConfig 已启用(2026-10-13 §2 取证注入门控)，版本号仍走 PackageManager、不依赖 BuildConfig 字段）。
 *  longVersionCode 是 API 28+ 成员方法，minSdk 26/27 上调用抛 NoSuchMethodError 被 runCatching 吞成「?」——
 *  按 SDK_INT 分流（Task 10 端到端冒烟在 API 26 模拟器实证）：低版本用 versionCode 升 long。 */
internal fun versionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("?")

// ---------------- 入口页（C10 §4.1 分组卡列表） ----------------

private data class GroupEntry(
    val group: SettingsGroup,
    val icon: ImageVector,
    val titleRes: Int,
)

private val groupEntries = listOf(
    GroupEntry(SettingsGroup.PLAYBACK, Icons.Outlined.PlayCircle, R.string.settings_group_playback),
    GroupEntry(SettingsGroup.SUBTITLE, Icons.Outlined.Subtitles, R.string.settings_group_subtitle),
    GroupEntry(SettingsGroup.AUDIO, Icons.Outlined.VolumeUp, R.string.settings_group_audio),
    GroupEntry(SettingsGroup.NETWORK, Icons.Outlined.Lan, R.string.settings_group_network),
    GroupEntry(SettingsGroup.LIBRARY, Icons.Outlined.VideoLibrary, R.string.settings_group_library),
    GroupEntry(SettingsGroup.UI, Icons.Outlined.Palette, R.string.settings_group_ui),
    GroupEntry(SettingsGroup.PRIVACY, Icons.Outlined.Shield, R.string.settings_group_privacy),
    GroupEntry(SettingsGroup.ABOUT, Icons.Outlined.Info, R.string.settings_group_about),
)

/** 入口页分组（2026-09-16 UI 审计 P2-10）：每张组卡包含的行 */
private val settingCardGroups: List<List<GroupEntry>> = listOf(
    groupEntries.subList(0, 3),   // 播放器 / 字幕 / 音频
    groupEntries.subList(3, 6),   // 网络 / 媒体库 / 界面
    groupEntries.subList(6, 8),   // 隐私与数据 / 关于
)

/** 各分组值摘要（nPlayer 式动态摘要：随设置实时变，C10 §4.1）。i18n：唯一调用点在 Text 内，加 @Composable 取词 */
@Composable
private fun summaryOf(group: SettingsGroup, prefs: Preferences?, historyCount: Int): String =
    when (group) {
        SettingsGroup.PLAYBACK -> {
            val mode = prefs.pref(KEY_SPEED_MODE, "fixed")
            stringResource(
                R.string.settings_summary_playback,
                if (mode == "remember") stringResource(R.string.settings_speed_remember_short) else "1x",
                prefs.pref(KEY_LONG_PRESS_SPEED, 2f).toInt(),
                prefs.pref(KEY_DOUBLE_TAP_SEC, DEFAULT_DOUBLE_TAP_SEC),
            )
        }
        SettingsGroup.SUBTITLE -> {
            // map 为 inline，可在组合上下文内调用 @Composable 标签函数；joinToString 非 inline 不能直接内嵌
            val langs = prefs.pref(KEY_SUB_LANGUAGES, "zh").split(',')
                .filter { it.isNotBlank() }.map { subLangLabel(it) }.joinToString("/")
            stringResource(
                R.string.settings_summary_subtitle,
                langs,
                encodingLabel(prefs.pref(KEY_SUB_ENCODING, "auto")),
            )
        }
        SettingsGroup.AUDIO ->
            stringResource(
                R.string.settings_summary_audio,
                audioLangLabel(prefs.pref(KEY_AUDIO_LANG, "auto")),
                if (prefs.pref(KEY_PRESERVE_PITCH, true)) stringResource(R.string.settings_pitch_preserve)
                else stringResource(R.string.settings_pitch_varies),
            )
        SettingsGroup.NETWORK ->
            stringResource(
                R.string.settings_summary_network,
                prefs.pref(KEY_NET_TIMEOUT_SEC, 8),
                prefs.pref(KEY_SMB_BLOCK, "4MB"),
            )
        SettingsGroup.LIBRARY ->
            stringResource(
                R.string.settings_summary_library,
                if (prefs.pref(KEY_LIB_AUTO_SCAN, true)) stringResource(R.string.settings_state_on)
                else stringResource(R.string.settings_state_off),
                if (prefs.pref(KEY_LIB_SCRAPE_GLOBAL, false)) stringResource(R.string.settings_state_on)
                else stringResource(R.string.settings_state_off),
            )
        SettingsGroup.UI ->
            stringResource(
                R.string.settings_summary_ui,
                posterColumnsLabel(prefs.pref(KEY_UI_POSTER_COLUMNS, "auto")),
                startTabLabel(prefs.pref(KEY_UI_START_TAB, "home")),
            )
        SettingsGroup.PRIVACY -> stringResource(R.string.settings_history_count, historyCount)
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
                            title = stringResource(entry.titleRes),
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
        }
        NvTopBar(
            stringResource(R.string.settings_title),
            modifier = Modifier.nvBarCollapse(barCollapsed),   // rework3：下滑收起/上滑出现
            scrolled = scrolled,
        )
    }
}
