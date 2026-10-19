@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.novax.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.data.update.UpdateCheckResult
import com.novax.app.NvAppLanguage
import com.novax.app.player.KEY_AUDIO_LANG
import com.novax.app.player.KEY_BACKGROUND_PLAY
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.novax.app.player.KEY_METERED_REMIND_MODE
import com.novax.app.player.METERED_REMIND_EVERY
import com.novax.app.player.METERED_REMIND_NEVER
import com.novax.app.player.METERED_REMIND_SESSION_ONCE
import com.novax.app.player.meteredRemindMode
import com.novax.app.player.toStoredValue
import com.novax.app.player.KEY_AUTO_NEXT
import com.novax.app.player.KEY_AUTO_ORIENT_ON_SWITCH
import com.novax.app.player.KEY_DECODER
import com.novax.app.player.KEY_DOUBLE_TAP_SEC
import com.novax.app.player.DEFAULT_DOUBLE_TAP_SEC
import com.novax.app.player.DOUBLE_TAP_SEC_CHOICES
import com.novax.app.player.LONG_PRESS_SPEED_PRESETS
import com.novax.app.player.KEY_LAN_DISCOVERY
import com.novax.app.player.KEY_THUMB_METERED
import com.novax.app.player.KEY_PIP_AUTO
import com.novax.app.player.KEY_VOLUME_BOOST
import com.novax.app.player.KEY_SUBTITLE_SIZE
import com.novax.app.player.KEY_RMVBFramesSkip
import com.novax.app.player.KEY_LEFT_SLOWMO
import com.novax.app.player.KEY_LONG_PRESS_ON
import com.novax.app.player.KEY_LONG_PRESS_SPEED
import com.novax.app.player.KEY_HALF_LOCK_ENABLED
import com.novax.app.player.KEY_MUTE_ABOVE_2X
import com.novax.app.player.DEFAULT_MUTE_ABOVE_2X
import com.novax.app.player.KEY_NET_TIMEOUT_SEC
import com.novax.app.player.KEY_PRESERVE_PITCH
import com.novax.app.player.KEY_PRECISE_SEEK
import com.novax.app.player.KEY_RESUME_ON_OPEN
import com.novax.app.player.KEY_SMB_BLOCK
import com.novax.app.player.KEY_SMB_PREFETCH
import com.novax.app.player.KEY_SPEED_MODE
import com.novax.app.player.KEY_SUB_AUTO_LOAD
import com.novax.app.player.KEY_SUB_ENCODING
import com.novax.app.player.KEY_SUB_LANGUAGES
import com.novax.app.player.KEY_SUB_OFFSET_STEP
import com.novax.app.player.KEY_AUDIO_PASSTHROUGH
import com.novax.app.player.AV_OFFSET_MS_CHOICES
import com.novax.app.player.KEY_AV_OFFSET_MS
import com.novax.app.player.KEY_BUFFER_LEVEL
import com.novax.app.player.KEY_SWIPE_FULL_PERCENT
import com.novax.app.player.KEY_UI_POSTER_COLUMNS
import com.novax.app.player.KEY_UI_SHOW_FILE_SIZE
import com.novax.app.player.KEY_UI_START_TAB
import com.novax.app.player.KEY_UI_THEME
import com.novax.app.R
import com.novax.app.ui.components.NvChipRow
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.NvEntryRow
import com.novax.app.ui.components.NvPhase2Badge
import com.novax.app.ui.components.NvSettingsCard
import com.novax.app.ui.components.NvSettingsRow
import com.novax.app.ui.components.NvSegmentedRow
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSliderRow
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvSwitchRow
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.policy.PolicyDoc
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * C10 · 组内页通用模板 + 七个分组页（§4.2 / §5.4–§5.7）。
 * 真实生效项（WS4 最小集合）：
 *  播放器——默认倍速/长按快进/长按倍速值/左半屏慢放/双击秒数/>2x 静音/精确跳转/变速不变调；
 *  网络——连接超时（PlaybackEngine DefaultHttpDataSource）；
 *  界面——海报墙列数（键先落位）/返回键直接退出（novax_ui_prefs，MainActivity 消费）；
 *  隐私——清除观看历史/清除缓存/重置所有设置；关于——版本/开源致谢。
 * 其余项按 C10 键表落位存值（消费方在 C3/C5/C6/C8 相应部件接入），二期行 Toast + NvPhase2Badge。
 */

// ---------------- 组内页通用模板（C10 §5.4） ----------------

@Composable
internal fun SettingsGroupPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（组内页为页内路由，底栏仍显示）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // Liquid Glass（decisions.md §4.5）：source 挂列表滚动容器，NvTopBar 悬浮做兄弟（页面翻转配方）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：组内页顶栏静止态隐形（同设置首页接线）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
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
            verticalArrangement = Arrangement.spacedBy(NvSpacing.XL),
            content = content,
        )
        NvTopBar(title, onBack = onBack, scrolled = scrolled)
    }
}

/** 二期行统一 Toast（C10 §0.3 + P2 假开关诚实化：不做假开关，可点击且解释） */
internal fun phase2Toast(context: Context) {
    Toast.makeText(context, context.getString(R.string.settings_phase2_toast), Toast.LENGTH_SHORT).show()
}

/** 设备直通能力如实探测（AudioCapabilities.supportsEncoding 逐编码枚举；非组合层走 context.getString） */
internal fun passthroughCapabilityLine(context: Context): String = runCatching {
    val names = passthroughSupportedEncodings(context)
    if (names.isEmpty()) context.getString(R.string.settings_passthrough_unsupported)
    else context.getString(R.string.settings_passthrough_supported, names.joinToString("、"))
}.getOrDefault(context.getString(R.string.settings_passthrough_probe_failed))

/**
 * 设备位流直通支持列表（空 = 本机完全不支持）。
 * 2026-09-15 用户反馈修复：直通开关的 enabled 与文案共用本探测——不支持就不许开，
 * 不能出现「显示不支持却仍可打开」的自相矛盾 UI（用户截图证据）。
 */
internal fun passthroughSupportedEncodings(context: Context): List<String> = runCatching {
    val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
    listOf(
        androidx.media3.common.C.ENCODING_AC3 to "AC-3",
        androidx.media3.common.C.ENCODING_E_AC3 to context.getString(R.string.settings_passthrough_eac3),
        androidx.media3.common.C.ENCODING_AC4 to "AC-4",
        androidx.media3.common.C.ENCODING_DTS_HD to "DTS-HD",
    ).filter { caps.supportsEncoding(it.first) }.map { it.second }
}.getOrDefault(emptyList())

// ---------------- 分组路由 ----------------

@Composable
internal fun SettingsGroupPageRouter(
    group: SettingsGroup,
    vm: SettingsViewModel,
    prefs: Preferences?,
    backExit: Boolean,
    language: String,
    historyCount: Int,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenLicenses: () -> Unit,
    onOpenPolicyDoc: (PolicyDoc) -> Unit,
) {
    when (group) {
        SettingsGroup.PLAYBACK -> PlaybackGroupPage(vm, prefs, onBack)
        SettingsGroup.SUBTITLE -> SubtitleGroupPage(vm, prefs, onBack)
        SettingsGroup.AUDIO -> AudioGroupPage(vm, prefs, onBack)
        SettingsGroup.NETWORK -> NetworkGroupPage(vm, prefs, onBack)
        SettingsGroup.LIBRARY -> MediaLibraryGroupPage(vm, onBack)
        SettingsGroup.UI -> UiGroupPage(vm, prefs, backExit, language, onBack)
        SettingsGroup.PRIVACY -> PrivacyGroupPage(vm, historyCount, onBack, onOpenHistory)
        SettingsGroup.ABOUT -> AboutGroupPage(onBack, onOpenLicenses, onOpenPolicyDoc)
    }
}

// ---------------- 1. 播放器（C10 §5.5） ----------------

@Composable
private fun PlaybackGroupPage(vm: SettingsViewModel, prefs: Preferences?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SettingsGroupPage(stringResource(R.string.settings_group_playback), onBack) {
        item {
            NvSettingsCard(null) {
                NvSegmentedRow(
                    title = stringResource(R.string.settings_default_speed),
                    options = listOf(
                        "fixed" to stringResource(R.string.settings_speed_fixed),
                        "remember" to stringResource(R.string.settings_speed_remember),
                    ),
                    selected = listOf(prefs.pref(KEY_SPEED_MODE, "fixed")),
                    onSelect = { vm.set(KEY_SPEED_MODE, it) },
                )
                NvSwitchRow(
                    stringResource(R.string.settings_long_press_seek),
                    checked = prefs.pref(KEY_LONG_PRESS_ON, true),
                    subtitle = stringResource(R.string.settings_long_press_seek_desc),
                    onChecked = { vm.set(KEY_LONG_PRESS_ON, it) },
                )
                // 2026-09-18 用户需求：长按倍速值改滑块选档——预设六档 0.5/1/1.5/2/2.5/3，
                // 滑到哪吸附哪档（steps 刻度 + 松手取整双吸附）；沿用 NvSliderRow 防每帧写盘
                val lpSelected = prefs.pref(KEY_LONG_PRESS_SPEED, 2f)
                val lpIdx = LONG_PRESS_SPEED_PRESETS.indexOfFirst { it == lpSelected }.takeIf { it >= 0 } ?: 3
                NvSliderRow(
                    title = stringResource(R.string.settings_long_press_speed),
                    value = lpIdx.toFloat(),
                    onValueChangeFinished = { idx ->
                        vm.set(KEY_LONG_PRESS_SPEED, LONG_PRESS_SPEED_PRESETS[idx.roundToInt().coerceIn(0, LONG_PRESS_SPEED_PRESETS.lastIndex)])
                    },
                    valueRange = 0f..LONG_PRESS_SPEED_PRESETS.lastIndex.toFloat(),
                    steps = LONG_PRESS_SPEED_PRESETS.size - 2,
                    display = { idx ->
                        val p = LONG_PRESS_SPEED_PRESETS[idx.roundToInt().coerceIn(0, LONG_PRESS_SPEED_PRESETS.lastIndex)]
                        if (p % 1f == 0f) "${p.toInt()}x" else "${p}x"
                    },
                    tickLabels = listOf("0.5", "1", "1.5", "2", "2.5", "3"),
                    enabled = prefs.pref(KEY_LONG_PRESS_ON, true),   // 开关联动禁用
                    showDivider = false,
                )
                // novax_left_slowmo 在同文件同键；写入端 setLeftSlowmo，PlayerScreen collect 实时生效
                NvSwitchRow(
                    stringResource(R.string.settings_left_slowmo),
                    checked = prefs.pref(KEY_LEFT_SLOWMO, false),
                    subtitle = stringResource(R.string.settings_left_slowmo_desc),
                    onChecked = { vm.setLeftSlowmo(it) },
                )
                // 2026-10-08 用户需求：半锁两级锁定，默认开；关 = 锁按钮一下直进全锁（旧行为）
                NvSwitchRow(
                    stringResource(R.string.settings_half_lock),
                    checked = prefs.pref(KEY_HALF_LOCK_ENABLED, true),
                    subtitle = stringResource(R.string.settings_half_lock_desc),
                    onChecked = { vm.set(KEY_HALF_LOCK_ENABLED, it) },
                )
                NvSegmentedRow(
                    // 2026-09-18 用户需求：四档 10/15/25/30（新增 25s），默认 15s；
                    // 同一步长驱动双击三区手势与底栏 ±Ns 按钮（按钮小字随档位）
                    title = stringResource(R.string.settings_double_tap_seconds),
                    options = DOUBLE_TAP_SEC_CHOICES.map { it to "${it}s" },
                    selected = listOf(prefs.pref(KEY_DOUBLE_TAP_SEC, DEFAULT_DOUBLE_TAP_SEC)),
                    onSelect = { vm.set(KEY_DOUBLE_TAP_SEC, it) },
                    subtitle = stringResource(R.string.settings_double_tap_seconds_desc),
                )
                // 2026-10-08 用户需求：整屏步长 = 片长百分比滑杆自由设定（1~50%，默认 10%），
                // 替换三档灵敏度——百分比是唯一口径，无隐藏倍率；松手才写盘（NvSliderRow 既有约定）
                // （NvSliderRow 无 subtitle 参数，说明并入标题）
                NvSliderRow(
                    title = stringResource(R.string.settings_swipe_seek_step),
                    value = prefs.pref(KEY_SWIPE_FULL_PERCENT, 10).toFloat(),
                    valueRange = 1f..50f,
                    steps = 48,
                    display = { "${it.roundToInt()}%" },
                    onValueChangeFinished = { vm.set(KEY_SWIPE_FULL_PERCENT, it.roundToInt().coerceIn(1, 50)) },
                )
            }
        }
        item {
            NvSettingsCard(null) {
                NvSwitchRow(
                    stringResource(R.string.settings_precise_seek),
                    checked = prefs.pref(KEY_PRECISE_SEEK, false),
                    subtitle = stringResource(R.string.settings_precise_seek_desc),
                    onChecked = { vm.set(KEY_PRECISE_SEEK, it) },
                )
                // 2026-09-27 细节统一：换分段控件与同页其余选项一致（标签已足够短，6 格可容纳）
                NvSegmentedRow(
                    stringResource(R.string.settings_av_offset),
                    options = AV_OFFSET_MS_CHOICES.map { (v, label) -> v to label },
                    selected = listOf(prefs.pref(KEY_AV_OFFSET_MS, 0)),
                    subtitle = stringResource(R.string.settings_av_offset_desc),
                    onSelect = { vm.set(KEY_AV_OFFSET_MS, it) },
                )
                NvSegmentedRow(
                    stringResource(R.string.settings_buffer_level),
                    // 2026-09-27 换分段控件：档位秒数说明挪进副标题（分段标签须短）
                    options = listOf(
                        "low" to stringResource(R.string.settings_buffer_low),
                        "medium" to stringResource(R.string.settings_buffer_medium),
                        "high" to stringResource(R.string.settings_buffer_high),
                    ),
                    selected = listOf(prefs.pref(KEY_BUFFER_LEVEL, "medium")),
                    subtitle = stringResource(R.string.settings_buffer_level_desc),
                    onSelect = { vm.set(KEY_BUFFER_LEVEL, it) },
                )
                NvSwitchRow(
                    stringResource(R.string.settings_mute_above_2x),
                    checked = prefs.pref(KEY_MUTE_ABOVE_2X, DEFAULT_MUTE_ABOVE_2X),
                    subtitle = stringResource(R.string.settings_mute_above_2x_desc),
                    onChecked = { vm.set(KEY_MUTE_ABOVE_2X, it) },
                )
            }
        }
        item {
            NvSettingsCard(null) {
                // 2026-09-15 假开关真化（评审 C3-C4）：三档接线后无运行时差异（引擎已是
                // 硬解优先+自动回退，且无软解扩展模块），假开关改为诚实信息行
                NvSettingsRow(
                    title = stringResource(R.string.settings_decoder_policy),
                    subtitle = stringResource(R.string.settings_decoder_policy_desc),
                )
                // P2 已真实生效：PlayerActivity 起播链路读取，关闭则忽略历史进度从 0 起
                NvSwitchRow(
                    stringResource(R.string.settings_resume_on_open),
                    checked = prefs.pref(KEY_RESUME_ON_OPEN, true),
                    onChecked = { vm.set(KEY_RESUME_ON_OPEN, it) },
                )
                // 2026-09-15 D1：播完自动连播下一集（文件夹队列；PlaybackService/PlayerScreen 消费）
                NvSwitchRow(
                    stringResource(R.string.settings_auto_next),
                    checked = prefs.pref(KEY_AUTO_NEXT, true),
                    subtitle = stringResource(R.string.settings_auto_next_desc),
                    onChecked = { vm.set(KEY_AUTO_NEXT, it) },
                    showDivider = true,
                )
                // 2026-10-22 切集自动定向开关（PlayerScreen 消费；关 = 切集不改变方向）
                NvSwitchRow(
                    stringResource(R.string.settings_auto_orient_switch),
                    checked = prefs.pref(KEY_AUTO_ORIENT_ON_SWITCH, true),
                    subtitle = stringResource(R.string.settings_auto_orient_switch_desc),
                    onChecked = { vm.set(KEY_AUTO_ORIENT_ON_SWITCH, it) },
                )
                // 2026-09-15 D4：软字幕字号（SubtitleView setFixedTextSize）
                NvSegmentedRow(
                    stringResource(R.string.settings_subtitle_size),
                    options = listOf(
                        "0.85" to stringResource(R.string.settings_size_small),
                        "1.0" to stringResource(R.string.settings_size_standard),
                        "1.25" to stringResource(R.string.settings_size_large),
                    ),
                    selected = listOf(prefs.pref(KEY_SUBTITLE_SIZE, "1.0")),
                    subtitle = stringResource(R.string.settings_subtitle_size_desc),
                    onSelect = { vm.set(KEY_SUBTITLE_SIZE, it) },
                )
                // 2026-09-15 D5：退后台自动进画中画
                NvSwitchRow(
                    stringResource(R.string.settings_pip_auto),
                    checked = prefs.pref(KEY_PIP_AUTO, false),
                    subtitle = stringResource(R.string.settings_pip_auto_desc),
                    onChecked = { vm.set(KEY_PIP_AUTO, it) },
                )
                // 2026-09-15 D6：音量增益（小声片源；>1.0 有削波风险，提示诚实标注）
                NvSegmentedRow(
                    stringResource(R.string.settings_volume_boost),
                    options = listOf("1.0" to "100%", "1.5" to "150%", "2.0" to "200%"),
                    selected = listOf(prefs.pref(KEY_VOLUME_BOOST, "1.0")),
                    subtitle = stringResource(R.string.settings_volume_boost_desc),
                    onSelect = { vm.set(KEY_VOLUME_BOOST, it) },
                )
                // 2026-09-15：AudioTrack 编码直通真开关（NvRenderersFactory 注入 AudioCapabilities）
                // 2026-09-22 停用：直通位流无法应用内变速（Sonic 只处理 PCM），直通+倍速 =
                // 音频 1x / 时钟 2x 的渐进音画不同步（androidx/media #2283，Open 未修）。
                // 为根治「长按倍速音画不同步」，音频固定 PCM 解码（NvRenderersFactory），
                // 开关保留记忆值但置灰不再生效；设备缺 AC3 解码器仍走「自动转软解」兜底。
                val passthroughEncodings = remember { passthroughSupportedEncodings(ctx) }
                NvSwitchRow(
                    stringResource(R.string.settings_passthrough_toggle),
                    checked = prefs.pref(KEY_AUDIO_PASSTHROUGH, true),
                    subtitle = stringResource(R.string.settings_passthrough_disabled_desc) +
                        if (passthroughEncodings.isEmpty()) stringResource(R.string.settings_passthrough_unsupported_short) else "",
                    enabled = false,
                    onChecked = { vm.set(KEY_AUDIO_PASSTHROUGH, it) },
                )
                NvSettingsRow(
                    title = stringResource(R.string.settings_passthrough_capability),
                    subtitle = remember { passthroughCapabilityLine(ctx) },
                    showDivider = false,
                )
                // P2 已真实生效：novax_background_play=false 时 onStop 暂停、回前台不自动恢复
                // 2026-10-11 流量播放提醒三档化（原开关；弹窗勾「流量下不再提醒」= 不提醒档，
                // 此处三档可改回）。显示值与闸门共用同一解析（含旧键迁移），防显示/行为错位
                // （spec §4.6；勿用 pref(key, 默认值) 占位读——default 非 null 会让迁移分支不可达）
                NvSegmentedRow(
                    stringResource(R.string.settings_metered_remind),
                    options = listOf(
                        METERED_REMIND_EVERY to stringResource(R.string.settings_metered_every),
                        METERED_REMIND_SESSION_ONCE to stringResource(R.string.settings_metered_once),
                        METERED_REMIND_NEVER to stringResource(R.string.settings_metered_never),
                    ),
                    selected = listOf(
                        meteredRemindMode(
                            prefs?.get(KEY_METERED_REMIND_MODE),
                            // 旧键迁移读取（字面量，与 MeteredGate.remindMode 注释互指）
                            prefs?.get(booleanPreferencesKey("novax_metered_play_remind")),
                        ).toStoredValue(),
                    ),
                    subtitle = stringResource(R.string.settings_metered_remind_desc),
                    onSelect = { vm.set(KEY_METERED_REMIND_MODE, it) },
                )
                // 2026-09-28 后台播放开关联动通知权限（审查 B9）：API 33+ 开启且未授权时
                // 请求 POST_NOTIFICATIONS——否则 PlaybackService 前台服务的通知栏/锁屏控制
                // 不展示。拒绝不阻断开关（服务仍可用，只是没有通知面板；与 MainActivity
                // 首启一次性请求互不冲突，系统两次拒绝后会静默忽略再次请求）。
                val notifPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* 结果不处理：拒绝不改开关状态，播放功能不受影响 */ }
                NvSwitchRow(
                    stringResource(R.string.settings_background_play),
                    checked = prefs.pref(KEY_BACKGROUND_PLAY, false),
                    subtitle = stringResource(R.string.settings_background_play_desc),
                    onChecked = { on ->
                        vm.set(KEY_BACKGROUND_PLAY, on)
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                // 2026-09-20 RMVB 软解性能逃生门（官方 VLC「性能模式」同语义；RMVB 无硬解，
                // 倍速/高码率卡顿时开启，跳非参考帧换流畅，轻微画质代价；下次进 RMVB 页生效）
                NvSwitchRow(
                    stringResource(R.string.settings_rmvb_perf),
                    checked = prefs.pref(KEY_RMVBFramesSkip, false),
                    subtitle = stringResource(R.string.settings_rmvb_perf_desc),
                    onChecked = { vm.set(KEY_RMVBFramesSkip, it) },
                    showDivider = false,
                )
            }
        }
    }
}

// ---------------- 2. 字幕（C10 §5.6） ----------------

@Composable
private fun SubtitleGroupPage(vm: SettingsViewModel, prefs: Preferences?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SettingsGroupPage(stringResource(R.string.settings_group_subtitle), onBack) {
        item {
            NvSettingsCard(null) {
                NvSwitchRow(
                    stringResource(R.string.settings_sub_auto_load),
                    checked = prefs.pref(KEY_SUB_AUTO_LOAD, true),
                    subtitle = stringResource(R.string.settings_sub_auto_load_desc),
                    onChecked = { vm.set(KEY_SUB_AUTO_LOAD, it) },
                )
                NvSegmentedRow(
                    title = stringResource(R.string.settings_sub_language),
                    // 2026-09-20 用户需求：改单选且只留中文/English；旧值（可能 "zh,en,ja" 多选残留）
                    // 迁移取第一个合法项，无合法项回退 zh（语言自名标签为白名单专名，不资源化）
                    options = listOf("zh" to "中文", "en" to "English"),
                    selected = listOf(
                        prefs.pref(KEY_SUB_LANGUAGES, "zh")
                            .split(',')
                            .firstOrNull { it == "zh" || it == "en" } ?: "zh",
                    ),
                    onSelect = { lang -> vm.set(KEY_SUB_LANGUAGES, lang) },
                )
                NvSegmentedRow(
                    stringResource(R.string.settings_sub_encoding),
                    options = listOf(
                        "auto" to stringResource(R.string.settings_encoding_auto),
                        "utf-8" to "UTF-8", "gbk" to "GBK", "big5" to "BIG5",
                    ),
                    selected = listOf(prefs.pref(KEY_SUB_ENCODING, "auto")),
                    // P2 已真实生效：side-load 挂载前按此键重编码为 UTF-8 临时文件（SubtitleCharset）
                    subtitle = stringResource(R.string.settings_sub_encoding_desc),
                    onSelect = { vm.set(KEY_SUB_ENCODING, it) },
                )
            }
        }
        item {
            NvSettingsCard(null) {
                // 2026-09-15：字号真实现（播放器组 KEY_SUBTITLE_SIZE）；字幕延迟真实现
                // （播放器字幕菜单 NvShiftedSubtitleParser，从下一句字幕生效）。
                // 纯信息行，不可点击（诚实化：不留无动作的假入口）
                NvSettingsRow(
                    title = stringResource(R.string.settings_subtitle_size_delay),
                    subtitle = stringResource(R.string.settings_subtitle_size_delay_desc),
                )
                // 2026-09-15：时间轴偏移步进转正（播放器字幕菜单 ± 调节的步长）
                NvSegmentedRow(
                    stringResource(R.string.settings_sub_offset_step),
                    options = listOf(0.1f to "0.1s", 0.25f to "0.25s", 0.5f to "0.5s", 1f to "1.0s"),
                    selected = listOf(prefs.pref(KEY_SUB_OFFSET_STEP, 0.5f)),
                    subtitle = stringResource(R.string.settings_sub_offset_step_desc),
                    onSelect = { vm.set(KEY_SUB_OFFSET_STEP, it) },
                    showDivider = false,
                )
            }
        }
    }
}

// ---------------- 3. 音频 ----------------

@Composable
private fun AudioGroupPage(vm: SettingsViewModel, prefs: Preferences?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SettingsGroupPage(stringResource(R.string.settings_group_audio), onBack) {
        item {
            NvSettingsCard(null) {
                // 2026-09-15 用户反馈②修复：4 个互斥选项改用单行等宽分段控件
                //（原 NvChipRow 的 FlowRow 在竖屏窄宽度下折成 3+1，末行只挂一个胶囊＝破版）
                NvSegmentedRow(
                    title = stringResource(R.string.settings_audio_language),
                    // 语言自名标签（中文/English/日本語）为白名单专名，不资源化
                    options = listOf(
                        "auto" to stringResource(R.string.settings_follow_file),
                        "zh" to "中文", "en" to "English", "ja" to "日本語",
                    ),
                    selected = listOf(prefs.pref(KEY_AUDIO_LANG, "auto")),
                    subtitle = stringResource(R.string.settings_audio_language_desc),
                    onSelect = { vm.set(KEY_AUDIO_LANG, it) },
                )
                // 与播放器 SpeedSheet 同键（C6 §3.3 共享）：播放器实时读取 pitch
                NvSwitchRow(
                    stringResource(R.string.settings_pitch_preserve),
                    checked = prefs.pref(KEY_PRESERVE_PITCH, true),
                    subtitle = stringResource(R.string.settings_preserve_pitch_desc),
                    onChecked = { vm.set(KEY_PRESERVE_PITCH, it) },
                )
                // 2026-09-15：直通开关已真实现，入口在设置-播放器（杜比/AC3 直通输出）
            }
        }
    }
}

// ---------------- 4. 网络 / NAS ----------------

@Composable
private fun NetworkGroupPage(vm: SettingsViewModel, prefs: Preferences?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SettingsGroupPage(stringResource(R.string.settings_group_network), onBack) {
        item {
            NvSettingsCard(null) {
                // 真实生效：PlaybackEngine.buildDataSourceFactory → DefaultHttpDataSource 超时
                NvSegmentedRow(
                    stringResource(R.string.settings_connect_timeout),
                    options = listOf(5 to "5s", 8 to "8s", 15 to "15s"),
                    selected = listOf(prefs.pref(KEY_NET_TIMEOUT_SEC, 8)),
                    subtitle = stringResource(R.string.settings_connect_timeout_desc),
                    onSelect = { vm.set(KEY_NET_TIMEOUT_SEC, it) },
                )
                // 2026-09-15 假开关真化：两项均接入 NvNetTuning 运行时槽（MainActivity 收集，
                // SmbClient 开流时消费）。默认 4MB 与旧行为一致；显式改小才覆盖（contains() 门控）
                NvSegmentedRow(
                    stringResource(R.string.settings_smb_block),
                    // 2026-10-12 用户验收:选项按大小升序(与上方连接超时 5s/8s/15s 同风格);值集不变,
                    // NvSegmentedRow 按 value 判选中(Settings.kt:366-374),顺序仅影响视觉
                    options = listOf("512KB" to "512KB", "1MB" to "1MB", "4MB" to "4MB"),
                    selected = listOf(prefs.pref(KEY_SMB_BLOCK, "4MB")),
                    subtitle = stringResource(R.string.settings_smb_block_desc),
                    onSelect = { vm.set(KEY_SMB_BLOCK, it) },
                )
                NvSwitchRow(
                    stringResource(R.string.settings_smb_prefetch),
                    checked = prefs.pref(KEY_SMB_PREFETCH, true),
                    subtitle = stringResource(R.string.settings_smb_prefetch_desc),
                    onChecked = { vm.set(KEY_SMB_PREFETCH, it) },
                )
                NvSwitchRow(
                    stringResource(R.string.settings_thumb_metered),
                    checked = prefs.pref(KEY_THUMB_METERED, false),
                    subtitle = stringResource(R.string.settings_thumb_metered_desc),
                    onChecked = { vm.set(KEY_THUMB_METERED, it) },
                )
                NvSwitchRow(
                    stringResource(R.string.settings_lan_discovery),
                    checked = prefs.pref(KEY_LAN_DISCOVERY, true),
                    subtitle = stringResource(R.string.settings_lan_discovery_desc),
                    onChecked = { vm.set(KEY_LAN_DISCOVERY, it) },
                    showDivider = false,
                )
            }
        }
    }
}

// ---------------- 5. 界面 ----------------

/**
 * Task 13：应用内语言行副标题 / 对话框选项标签——五态。
 * 语言自名标签复用 subLangLabel（白名单专名不翻译，SettingsScreen.kt 同源）；
 * 「跟随系统」态用资源键 settings_ui_language_auto。
 */
@Composable
private fun languageLabel(option: String): String = when (option) {
    NvAppLanguage.AUTO -> stringResource(R.string.settings_ui_language_auto)
    else -> subLangLabel(option)
}

/** 应用内语言单选对话框（沿用本文件打赏弹窗 Dialog+Column 模式；2026-10-22 起六项含繁中，选中 ✓，点击即生效关闭） */
@Composable
private fun NvLanguageDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(NvShape.Dialog)
                .background(NvColors.SurfaceElevated)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.settings_ui_language),
                style = NvTypography.TitleMedium,
                color = NvColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            NvAppLanguage.OPTIONS.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        languageLabel(option),
                        Modifier.weight(1f),
                        style = NvTypography.BodyMedium,
                        color = if (option == current) NvColors.Primary else NvColors.TextPrimary,
                        fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (option == current) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            Modifier.size(18.dp),
                            tint = NvColors.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UiGroupPage(
    vm: SettingsViewModel,
    prefs: Preferences?,
    backExit: Boolean,
    language: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLanguage by remember { mutableStateOf(false) }
    SettingsGroupPage(stringResource(R.string.settings_group_ui), onBack) {
        item {
            NvSettingsCard(null) {
                NvSegmentedRow(
                    stringResource(R.string.settings_theme),
                    options = listOf(
                        "dark" to stringResource(R.string.settings_theme_dark),
                        "light" to stringResource(R.string.settings_theme_light),
                        "system" to stringResource(R.string.settings_theme_system),
                    ),
                    selected = listOf(prefs.pref(KEY_UI_THEME, "dark")),
                    onSelect = { vm.set(KEY_UI_THEME, it) },
                    // 2026-09-20 浅色模式落地：chips 激活（MainActivity NovaXTheme 消费同一键）
                    // rework2（2026-10-15 用户反馈「画蛇添足」）：subtitle「播放界面始终为深色」删除
                )
                // Task 13：应用内语言开关——副标题 = 当前五态；33+ 走系统 per-app locale 接口
                // （系统自动重建），32- 走自实现包装（NvAppLanguage.set 后手动 recreate）
                NvEntryRow(
                    stringResource(R.string.settings_ui_language),
                    subtitle = languageLabel(language),
                    onClick = { showLanguage = true },
                )
                // 键先落位：C3 媒体库网格列数（Library 端接入留后续 WS）
                NvSegmentedRow(
                    stringResource(R.string.settings_poster_columns),
                    options = listOf(
                        "auto" to stringResource(R.string.settings_auto),
                        "2" to "2", "3" to "3", "4" to "4",
                    ),
                    selected = listOf(prefs.pref(KEY_UI_POSTER_COLUMNS, "auto")),
                    subtitle = stringResource(R.string.settings_poster_columns_desc),
                    onSelect = { vm.set(KEY_UI_POSTER_COLUMNS, it) },
                )
                NvSwitchRow(
                    stringResource(R.string.settings_show_file_size),
                    checked = prefs.pref(KEY_UI_SHOW_FILE_SIZE, true),
                    subtitle = stringResource(R.string.settings_show_file_size_desc),
                    onChecked = { vm.set(KEY_UI_SHOW_FILE_SIZE, it) },
                )
                // 真实生效：写 novax_ui_prefs 同一键，MainActivity BackHandler 消费（C11 R1/R2）
                NvSwitchRow(
                    stringResource(R.string.settings_back_exit),
                    checked = backExit,
                    subtitle = stringResource(R.string.settings_back_exit_desc),
                    onChecked = { vm.setBackExit(it) },
                )
                NvSegmentedRow(
                    stringResource(R.string.settings_start_tab),
                    options = listOf(
                        "home" to stringResource(R.string.settings_tab_home),
                        "library" to stringResource(R.string.settings_tab_library),
                        "files" to stringResource(R.string.settings_tab_files),
                    ),
                    selected = listOf(prefs.pref(KEY_UI_START_TAB, "home")),
                    onSelect = { vm.set(KEY_UI_START_TAB, it) },
                    showDivider = false,
                )
            }
        }
    }

    if (showLanguage) {
        NvLanguageDialog(
            current = language,
            onSelect = { option ->
                showLanguage = false
                scope.launch {
                    // set 返回时缓存/写盘已同步完成（32-）；33+ 系统自动重建，32- 手动 recreate
                    NvAppLanguage.set(ctx, option)
                    if (Build.VERSION.SDK_INT < 33) (ctx as? Activity)?.recreate()
                }
            },
            onDismiss = { showLanguage = false },
        )
    }
}

// ---------------- 6. 隐私与数据（C10 §5.7 危险操作三连） ----------------

@Composable
private fun PrivacyGroupPage(
    vm: SettingsViewModel,
    historyCount: Int,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val ctx = LocalContext.current
    var askCache by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    // P3 修复：清除缓存后刷新大小标签（refreshTick 变化触发重算，produceState(key) 重启）
    var cacheRefreshTick by remember { mutableIntStateOf(0) }
    val cacheBytes by produceState<Long?>(initialValue = null, key1 = cacheRefreshTick) {
        value = runCatching { vm.cacheSizeBytes() }.getOrNull()
    }
    val cacheLabel = cacheBytes?.let { "%.1f MB".format(it / 1_048_576.0) }
        ?: stringResource(R.string.settings_cache_calculating)

    SettingsGroupPage(stringResource(R.string.settings_group_privacy), onBack) {
        item {
            NvSettingsCard(null) {
                // 2026-09-27 用户需求：升级为历史管理入口（条目可见、可续播、可跳文件夹），
                // 清除/删除操作移入历史页内
                NvEntryRow(stringResource(R.string.settings_watch_history),
                    value = stringResource(R.string.settings_record_count, historyCount),
                    onClick = onOpenHistory)
                NvEntryRow(stringResource(R.string.settings_clear_cache), value = cacheLabel,
                    onClick = { askCache = true })
                NvEntryRow(stringResource(R.string.settings_reset_all), danger = true, showDivider = false,
                    onClick = { askReset = true })
            }
        }
    }

    if (askCache) {
        NvConfirmDialog(
            title = stringResource(R.string.settings_clear_cache),
            message = stringResource(R.string.settings_clear_cache_confirm_message, cacheLabel),
            confirmText = stringResource(R.string.settings_clear_action),
            onConfirm = {
                askCache = false
                vm.clearCache { freed ->
                    cacheRefreshTick++
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.settings_cache_cleared, "%.1f".format(freed / 1_048_576.0)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { askCache = false },
        )
    }
    if (askReset) {
        NvConfirmDialog(
            title = stringResource(R.string.settings_reset_all),
            message = stringResource(R.string.settings_reset_all_message),
            confirmText = stringResource(R.string.settings_reset_action),
            danger = true,
            onConfirm = {
                askReset = false
                vm.resetAll {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_reset_done), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { askReset = false },
        )
    }
}

// ---------------- 7. 关于 ----------------

// 2026-10-16 v1.0.0.0：打赏二维码保存（29+ MediaStore 免权限；26-28 写公共 Pictures，需存储权限）
private fun saveDonateQr(ctx: Context) {
    val bitmap = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.donate_wechat_qr)
    if (Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "novax_donate_qr.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException(ctx.getString(R.string.settings_error_media_store))
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IllegalStateException(ctx.getString(R.string.settings_error_output_stream))
    } else {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "novax_donate_qr.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    }
}

@Composable
private fun AboutGroupPage(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenPolicyDoc: (PolicyDoc) -> Unit,
) {
    val ctx = LocalContext.current
    val version = remember { versionLabel(ctx) }
    var showDonate by remember { mutableStateOf(false) }
    // 检查更新 VM（spec §2.6 独立轻量 + 单实例裁决/blocker 防线）：显式指定 Activity 为
    // ViewModelStoreOwner——裸 viewModel() 会作用域到 settings 路由 NavBackStackEntry，与
    // MainActivity else 分支的 viewModel() 得到两个互不相通的实例，「设置行点击检查 →
    // Activity 层弹窗」链路即断；指定 owner 后与 MainActivity 同一 ViewModelStore = 同一实例
    val updateVm: UpdateViewModel =
        viewModel(viewModelStoreOwner = LocalContext.current as ViewModelStoreOwner)
    val updateState by updateVm.state.collectAsState()
    val autoCheckOn by updateVm.autoCheckEnabled.collectAsState()
    // spec §2.5：清理时机 = MainActivity 启动与进入关于页；downloadId 在场且 Idle 时恢复进度轮询
    LaunchedEffect(Unit) { updateVm.onAboutEnter() }
    // 「已是最新/检查失败」副标题 3 秒后复位为当前版本行；Downloading/DownloadFailed/VerifyFailed
    // 驻留不复位；fromAuto=true 的 UpToDate/CheckFailed 本就走默认分支，无须计时
    LaunchedEffect(updateState) {
        val s = updateState
        if ((s is UpdateUiState.UpToDate && !s.fromAuto) ||
            (s is UpdateUiState.CheckFailed && !s.fromAuto)
        ) {
            delay(3_000)
            updateVm.dismissTransient()
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { saveDonateQr(ctx) }
                .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.settings_qr_saved), Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_save_failed, it.message ?: ""), Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.settings_qr_no_permission), Toast.LENGTH_SHORT).show()
        }
    }
    SettingsGroupPage(stringResource(R.string.settings_group_about), onBack) {
        item {
            NvSettingsCard(null) {
                NvSettingsRow(
                    title = stringResource(R.string.settings_version),
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text(version, style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 检查更新行（spec §2.4）：副标题状态机映射；点击状态感知——Downloading 态 = 取消下载
                // （spec §2.5「取消→DownloadManager.remove」UI 落位，fixwave2），其余态照旧手动检查
                //（不受/不产生 24h 节流）
                val updateFallback = stringResource(R.string.update_row_current, version)
                val rowSubtitle = when (val s = updateState) {
                    UpdateUiState.Checking -> stringResource(R.string.settings_loading)
                    is UpdateUiState.UpToDate -> if (s.fromAuto) updateFallback else stringResource(R.string.update_row_up_to_date)
                    is UpdateUiState.Downloading -> stringResource(R.string.update_dl_progress, s.percent)
                    // 失败态驻留（不 3s 复位），用户再点行 = 重新检查
                    is UpdateUiState.DownloadFailed -> stringResource(R.string.update_download_failed)
                    is UpdateUiState.VerifyFailed -> stringResource(R.string.update_verify_failed)
                    is UpdateUiState.CheckFailed -> when {
                        s.fromAuto -> updateFallback                       // 自动检查静默（spec §2.4）
                        s.reason == UpdateCheckResult.Failed.Reason.RATE_LIMITED ->
                            stringResource(R.string.update_rate_limited)
                        else -> stringResource(R.string.update_check_failed)
                    }
                    else -> updateFallback
                }
                NvSettingsRow(
                    title = stringResource(R.string.update_check_row_title),
                    subtitle = rowSubtitle,
                    onClick = {
                        if (updateState is UpdateUiState.Downloading) updateVm.cancelDownload()
                        else updateVm.manualCheck()
                    },
                    showDivider = true,
                ) {
                    val s = updateState
                    // spec §2.4「打开项目主页」出口（可达性落位）：手动检查的网络类失败才显示
                    if (s is UpdateUiState.CheckFailed && !s.fromAuto && s.canOpenProjectPage) {
                        TextButton(onClick = { updateVm.openProjectPage(ctx) }) {
                            Text(
                                stringResource(R.string.update_open_project_page),
                                style = NvTypography.LabelMedium,
                            )
                        }
                    }
                    // spec §2.5 取消的可见入口：下载中行尾「取消」按钮（与行点击同效，对齐本槽
                    // CheckFailed TextButton 现有用法）
                    if (s is UpdateUiState.Downloading) {
                        TextButton(onClick = { updateVm.cancelDownload() }) {
                            Text(
                                stringResource(R.string.update_btn_cancel),
                                style = NvTypography.LabelMedium,
                            )
                        }
                    }
                }
                // 自动检查开关行（novax_update_auto_check，默认开；gate 消费端在 MainActivity 钩子）
                NvSwitchRow(
                    stringResource(R.string.update_auto_check_row_title),
                    checked = autoCheckOn,
                    onChecked = { updateVm.setAutoCheck(it) },
                    showDivider = true,
                )
                // 2026-10-09 用户需求：关于页作者署名
                NvSettingsRow(
                    title = stringResource(R.string.settings_author),
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text("YouwuLiu", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 2026-10-16 用户需求：作者联系方式
                NvSettingsRow(
                    title = stringResource(R.string.settings_email),
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text("liuyouwu1999@163.com", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 2026-10-15 v1.0.0.0 用户需求：打赏作者（微信收款码）
                NvEntryRow(stringResource(R.string.settings_donate), onClick = { showDonate = true })
                // 2026-10-16 v1.0 公开发布（spec §1.4）：合规文档入口（首页→设置→关于 = 3 次点击可达）
                NvEntryRow(stringResource(R.string.settings_privacy_policy), onClick = { onOpenPolicyDoc(PolicyDoc.PRIVACY) })
                NvEntryRow(stringResource(R.string.settings_user_agreement), onClick = { onOpenPolicyDoc(PolicyDoc.AGREEMENT) })
                // 2026-09-15 二期占位清理：个人分发无更新源，「检查更新」假入口删除
                NvEntryRow(stringResource(R.string.settings_open_licenses), onClick = onOpenLicenses)
                // 2026-09-15 D2：本地崩溃日志查看/分享（纯本地；无记录时点按提示）
                NvEntryRow(stringResource(R.string.settings_crash_log), onClick = {
                    val latest = com.novax.app.data.NvCrashLog.latest(ctx)
                    if (latest == null) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_crash_none), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                ctx.packageName + ".fileprovider",
                                latest,
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(android.content.Intent.createChooser(intent, ctx.getString(R.string.settings_crash_share)))
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_crash_file, latest.name), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                })
                // 「反馈渠道」假入口同步删除（2026-09-15 二期占位清理）
            }
            if (showDonate) {
                // 2026-10-16 用户裁定：bottom sheet 居中不可控，改居中 Dialog + 保存到相册
                Dialog(onDismissRequest = { showDonate = false }) {
                    Column(
                        Modifier
                            .clip(NvShape.Dialog)
                            .background(NvColors.SurfaceElevated)
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.settings_donate), style = NvTypography.TitleMedium, color = NvColors.TextPrimary)
                        Spacer(Modifier.height(14.dp))
                        Image(
                            painter = painterResource(R.drawable.donate_wechat_qr),
                            contentDescription = stringResource(R.string.settings_donate_qr_cd),
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(2f / 3f)
                                .clip(NvShape.Dialog),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.settings_donate_caption),
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row {
                            TextButton(onClick = { showDonate = false }) {
                                Text(stringResource(R.string.settings_close), color = NvColors.TextSecondary)
                            }
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 29) {
                                    runCatching { saveDonateQr(ctx) }
                                        .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.settings_qr_saved), Toast.LENGTH_SHORT).show() }
                                        .onFailure {
                                            Toast.makeText(ctx, ctx.getString(R.string.settings_save_failed, it.message ?: ""), Toast.LENGTH_SHORT).show()
                                        }
                                } else {
                                    permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }) {
                                Text(
                                    stringResource(R.string.settings_save_to_gallery),
                                    color = NvColors.Primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
