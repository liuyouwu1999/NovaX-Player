package com.novax.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.novax.app.R
import com.novax.app.data.scrape.NvScrapeHolder
import com.novax.app.player.KEY_TMDB_API_KEY
import com.novax.app.ui.components.NvEntryRow
import com.novax.app.ui.components.NvLoader
import com.novax.app.ui.components.NvSettingsCard
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvSwitchRow
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.files.NvTextField
import com.novax.app.ui.theme.NvColors

/**
 * C12 §六 · 设置「媒体库」组（插在「网络 / NAS」组之后，全部复用 C10 已登记组件）。
 *
 * 2026-09-15 起刮削为真实现（data/scrape/NvScraper）：
 * - 「启用刮削（TMDB）」= KEY_LIB_SCRAPE_GLOBAL 真开关：扫描完成后对新条目自动匹配；
 * - 「TMDB API Key」用户自填（TMDB 免费注册即得 v3 key；App 不内置密钥，缺 Key 如实提示）；
 * - 各源「使用刮削」写 sources.scrapeEnabled（总开关关闭时源级开关无效果，总开关优先）；
 * - 「立即刮削」手动对全部已启用源触发（忽略总开关，但 Key 缺失仍不发起）。
 */

/** 添加源成功后自动扫描建立索引（novax_player_prefs，默认开；消费：FilesViewModel.confirmAddSource） */
val KEY_LIB_AUTO_SCAN = booleanPreferencesKey("novax_scan_autoscan")

/** 刮削增强全局总开关（novax_player_prefs，默认关；消费：MainActivity 扫描完成触发 NvScraper） */
val KEY_LIB_SCRAPE_GLOBAL = booleanPreferencesKey("novax_lib_scrape_global")

@Composable
internal fun MediaLibraryGroupPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val autoScan by vm.libAutoScan.collectAsState()
    val scrapeGlobal by vm.libScrapeGlobal.collectAsState()
    val sources by vm.sources.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val scrapeProgress by NvScrapeHolder.progress.collectAsState()

    val apiKey = prefs?.get(KEY_TMDB_API_KEY).orEmpty()
    var showKeyDialog by remember { mutableStateOf(false) }

    val scrapeRunning = scrapeProgress.values.any { it.running }
    // map 为 inline，可在组合上下文内取词；joinToString 非 inline 不能内嵌 @Composable 调用
    val scrapeText = scrapeProgress.values.let { entries ->
        when {
            entries.isEmpty() -> null
            entries.any { it.running } ->
                entries.filter { it.running }
                    .map { stringResource(R.string.settings_scrape_progress, it.done, it.total) }
                    .joinToString(" · ")
            else -> entries.firstOrNull { !it.message.isNullOrEmpty() }?.message
                ?: entries.filter { it.total > 0 }
                    .map { stringResource(R.string.settings_scrape_done, it.total) }
                    .joinToString(" · ")
                    .ifEmpty { null }
        }
    }

    SettingsGroupPage(stringResource(R.string.settings_group_library), onBack) {
        item {
            NvSettingsCard(null) {
                NvSwitchRow(
                    stringResource(R.string.settings_lib_auto_scan),
                    checked = autoScan,
                    subtitle = stringResource(R.string.settings_lib_auto_scan_desc),
                    onChecked = { vm.set(KEY_LIB_AUTO_SCAN, it) },
                )
                NvEntryRow(
                    stringResource(R.string.settings_rescan_library),
                    value = if (scanning) stringResource(R.string.settings_scanning) else null,
                    onClick = { vm.rescanLibrary() },
                    trailing = { if (scanning) NvLoader(modifier = Modifier.padding(end = NvSpacing.S)) },
                )
            }
        }
        item {
            NvSettingsCard(stringResource(R.string.settings_tmdb_scrape)) {
                NvSwitchRow(
                    stringResource(R.string.settings_lib_scrape_global),
                    checked = scrapeGlobal,
                    subtitle = stringResource(R.string.settings_lib_scrape_global_desc),
                    onChecked = { vm.set(KEY_LIB_SCRAPE_GLOBAL, it) },
                )
                NvEntryRow(
                    "TMDB API Key",
                    // 2026-09-18 用户反馈：原 value 把「未配置（themoviedb.org 免费注册）」整串塞进行尾，
                    // 和标题/副标题挤作一团——注册指引移入弹窗，行内只留状态
                    value = if (apiKey.isBlank()) stringResource(R.string.settings_key_not_set) else apiKey.take(4) + "…",
                    subtitle = stringResource(R.string.settings_tmdb_key_desc),
                    onClick = { showKeyDialog = true },
                )
                NvEntryRow(
                    stringResource(R.string.settings_scrape_now),
                    value = scrapeText,
                    onClick = { vm.scrapeEnabledSourcesNow() },
                    trailing = { if (scrapeRunning) NvLoader(modifier = Modifier.padding(end = NvSpacing.S)) },
                    showDivider = false,
                )
            }
        }
        item {
            NvSettingsCard(stringResource(R.string.settings_per_source_scrape)) {
                if (sources.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_sources),
                        Modifier.padding(NvSpacing.L),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                    )
                } else {
                    sources.forEachIndexed { i, s ->
                        NvSwitchRow(
                            stringResource(R.string.settings_source_scrape, s.nickname),
                            checked = s.scrapeEnabled,
                            subtitle = stringResource(R.string.settings_source_scrape_desc),
                            onChecked = { vm.setSourceScrape(s.id, it) },
                            showDivider = i != sources.lastIndex,
                        )
                    }
                }
            }
        }
    }

    if (showKeyDialog) {
        var text by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            // Liquid Glass（spec §8）：独立窗口弹层填充式玻璃，与 NvConfirmDialog 等三处同款（Task 4 口径）
            containerColor = NvColors.GlassFillStrong,
            shape = NvShape.Dialog,
            title = { Text("TMDB API Key (v3)", style = NvTypography.TitleMedium) },
            text = {
                Column {
                    NvTextField(
                        label = "API Key",
                        value = text,
                        onValueChange = { text = it },
                        placeholder = stringResource(R.string.settings_key_placeholder),
                    )
                    Text(
                        stringResource(R.string.settings_key_help),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                        modifier = Modifier.padding(top = NvSpacing.S),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.set(KEY_TMDB_API_KEY, text.trim())
                    showKeyDialog = false
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}
