@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.novax.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.Preferences
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
    Toast.makeText(context, "该功能将在后续版本提供", Toast.LENGTH_SHORT).show()
}

/** 设备直通能力如实探测（AudioCapabilities.supportsEncoding 逐编码枚举） */
internal fun passthroughCapabilityLine(context: Context): String = runCatching {
    val names = passthroughSupportedEncodings(context)
    if (names.isEmpty()) "本机不支持位流直通（将 PCM 解码输出）" else "本机支持：${names.joinToString("、")}"
}.getOrDefault("探测失败（默认跟随系统）")

/**
 * 设备位流直通支持列表（空 = 本机完全不支持）。
 * 2026-09-15 用户反馈修复：直通开关的 enabled 与文案共用本探测——不支持就不许开，
 * 不能出现「显示不支持却仍可打开」的自相矛盾 UI（用户截图证据）。
 */
internal fun passthroughSupportedEncodings(context: Context): List<String> = runCatching {
    val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
    listOf(
        androidx.media3.common.C.ENCODING_AC3 to "AC-3",
        androidx.media3.common.C.ENCODING_E_AC3 to "E-AC-3（杜比数字+）",
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
        SettingsGroup.UI -> UiGroupPage(vm, prefs, backExit, onBack)
        SettingsGroup.PRIVACY -> PrivacyGroupPage(vm, historyCount, onBack, onOpenHistory)
        SettingsGroup.ABOUT -> AboutGroupPage(onBack, onOpenLicenses, onOpenPolicyDoc)
    }
}

// ---------------- 1. 播放器（C10 §5.5） ----------------

@Composable
private fun PlaybackGroupPage(vm: SettingsViewModel, prefs: Preferences?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SettingsGroupPage("播放器", onBack) {
        item {
            NvSettingsCard(null) {
                NvSegmentedRow(
                    title = "默认倍速",
                    options = listOf("fixed" to "固定 1x", "remember" to "记忆上次"),
                    selected = listOf(prefs.pref(KEY_SPEED_MODE, "fixed")),
                    onSelect = { vm.set(KEY_SPEED_MODE, it) },
                )
                NvSwitchRow(
                    "长按快进",
                    checked = prefs.pref(KEY_LONG_PRESS_ON, true),
                    subtitle = "按住屏幕右半屏倍速播放，松开恢复",
                    onChecked = { vm.set(KEY_LONG_PRESS_ON, it) },
                )
                // 2026-09-18 用户需求：长按倍速值改滑块选档——预设六档 0.5/1/1.5/2/2.5/3，
                // 滑到哪吸附哪档（steps 刻度 + 松手取整双吸附）；沿用 NvSliderRow 防每帧写盘
                val lpSelected = prefs.pref(KEY_LONG_PRESS_SPEED, 2f)
                val lpIdx = LONG_PRESS_SPEED_PRESETS.indexOfFirst { it == lpSelected }.takeIf { it >= 0 } ?: 3
                NvSliderRow(
                    title = "长按倍速值",
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
                    "左半屏慢放",
                    checked = prefs.pref(KEY_LEFT_SLOWMO, false),
                    subtitle = "按住屏幕左半屏 0.5x 慢放",
                    onChecked = { vm.setLeftSlowmo(it) },
                )
                // 2026-10-08 用户需求：半锁两级锁定，默认开；关 = 锁按钮一下直进全锁（旧行为）
                NvSwitchRow(
                    "半锁模式",
                    checked = prefs.pref(KEY_HALF_LOCK_ENABLED, true),
                    subtitle = "点一下=半锁（手势仍可用），再点=全锁",
                    onChecked = { vm.set(KEY_HALF_LOCK_ENABLED, it) },
                )
                NvSegmentedRow(
                    // 2026-09-18 用户需求：四档 10/15/25/30（新增 25s），默认 15s；
                    // 同一步长驱动双击三区手势与底栏 ±Ns 按钮（按钮小字随档位）
                    title = "快进快退秒数",
                    options = DOUBLE_TAP_SEC_CHOICES.map { it to "${it}s" },
                    selected = listOf(prefs.pref(KEY_DOUBLE_TAP_SEC, DEFAULT_DOUBLE_TAP_SEC)),
                    onSelect = { vm.set(KEY_DOUBLE_TAP_SEC, it) },
                    subtitle = "双击画面两侧与底栏按钮的步长",
                )
                // 2026-10-08 用户需求：整屏步长 = 片长百分比滑杆自由设定（1~50%，默认 10%），
                // 替换三档灵敏度——百分比是唯一口径，无隐藏倍率；松手才写盘（NvSliderRow 既有约定）
                // （NvSliderRow 无 subtitle 参数，说明并入标题）
                NvSliderRow(
                    title = "滑动快进步长（整屏%，默认10）",
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
                    "精确跳转",
                    checked = prefs.pref(KEY_PRECISE_SEEK, false),
                    subtitle = "逐帧对齐字幕用，起播略慢",
                    onChecked = { vm.set(KEY_PRECISE_SEEK, it) },
                )
                // 2026-09-27 细节统一：换分段控件与同页其余选项一致（标签已足够短，6 格可容纳）
                NvSegmentedRow(
                    "音画偏移（下次播放生效）",
                    options = AV_OFFSET_MS_CHOICES.map { (v, label) -> v to label },
                    selected = listOf(prefs.pref(KEY_AV_OFFSET_MS, 0)),
                    subtitle = "声音比画面晚选负值；声音先响选正值",
                    onSelect = { vm.set(KEY_AV_OFFSET_MS, it) },
                )
                NvSegmentedRow(
                    "网络预读缓冲（下次播放生效）",
                    // 2026-09-27 换分段控件：档位秒数说明挪进副标题（分段标签须短）
                    options = listOf("low" to "低", "medium" to "中", "high" to "高"),
                    selected = listOf(prefs.pref(KEY_BUFFER_LEVEL, "medium")),
                    subtitle = "预读时长：低 15s · 中 默认 · 高 120s",
                    onSelect = { vm.set(KEY_BUFFER_LEVEL, it) },
                )
                NvSwitchRow(
                    "3x 及以上自动静音",
                    checked = prefs.pref(KEY_MUTE_ABOVE_2X, DEFAULT_MUTE_ABOVE_2X),
                    subtitle = "长按 3x 与倍速菜单 3x 档期间静音",
                    onChecked = { vm.set(KEY_MUTE_ABOVE_2X, it) },
                )
            }
        }
        item {
            NvSettingsCard(null) {
                // 2026-09-15 假开关真化（评审 C3-C4）：三档接线后无运行时差异（引擎已是
                // 硬解优先+自动回退，且无软解扩展模块），假开关改为诚实信息行
                NvSettingsRow(
                    title = "解码器策略",
                    subtitle = "硬解优先，失败自动回退",
                )
                // P2 已真实生效：PlayerActivity 起播链路读取，关闭则忽略历史进度从 0 起
                NvSwitchRow(
                    "打开时续播记忆位置",
                    checked = prefs.pref(KEY_RESUME_ON_OPEN, true),
                    onChecked = { vm.set(KEY_RESUME_ON_OPEN, it) },
                )
                // 2026-09-15 D1：播完自动连播下一集（文件夹队列；PlaybackService/PlayerScreen 消费）
                NvSwitchRow(
                    "自动连播下一集",
                    checked = prefs.pref(KEY_AUTO_NEXT, true),
                    subtitle = "文件夹内播完自动播下一集",
                    onChecked = { vm.set(KEY_AUTO_NEXT, it) },
                    showDivider = true,
                )
                // 2026-09-15 D4：软字幕字号（SubtitleView setFixedTextSize）
                NvSegmentedRow(
                    "字幕字号",
                    options = listOf("0.85" to "小", "1.0" to "标准", "1.25" to "大"),
                    selected = listOf(prefs.pref(KEY_SUBTITLE_SIZE, "1.0")),
                    subtitle = "软字幕（内嵌/外挂）显示字号",
                    onSelect = { vm.set(KEY_SUBTITLE_SIZE, it) },
                )
                // 2026-09-15 D5：退后台自动进画中画
                NvSwitchRow(
                    "退后台自动小窗",
                    checked = prefs.pref(KEY_PIP_AUTO, false),
                    subtitle = "播放中回到桌面自动缩为画中画小窗",
                    onChecked = { vm.set(KEY_PIP_AUTO, it) },
                )
                // 2026-09-15 D6：音量增益（小声片源；>1.0 有削波风险，提示诚实标注）
                NvSegmentedRow(
                    "音量增益",
                    options = listOf("1.0" to "100%", "1.5" to "150%", "2.0" to "200%"),
                    selected = listOf(prefs.pref(KEY_VOLUME_BOOST, "1.0")),
                    subtitle = "软件放大音量；200% 大音源可能破音",
                    onSelect = { vm.set(KEY_VOLUME_BOOST, it) },
                )
                // 2026-09-15：AudioTrack 编码直通真开关（NvRenderersFactory 注入 AudioCapabilities）
                // 2026-09-22 停用：直通位流无法应用内变速（Sonic 只处理 PCM），直通+倍速 =
                // 音频 1x / 时钟 2x 的渐进音画不同步（androidx/media #2283，Open 未修）。
                // 为根治「长按倍速音画不同步」，音频固定 PCM 解码（NvRenderersFactory），
                // 开关保留记忆值但置灰不再生效；设备缺 AC3 解码器仍走「自动转软解」兜底。
                val passthroughEncodings = remember { passthroughSupportedEncodings(ctx) }
                NvSwitchRow(
                    "杜比/AC3 直通输出",
                    checked = prefs.pref(KEY_AUDIO_PASSTHROUGH, true),
                    subtitle = "已固定 PCM 解码（倍速音画同步保障）；位流直通暂停开放" +
                        if (passthroughEncodings.isEmpty()) "（本机亦不支持直通）" else "",
                    enabled = false,
                    onChecked = { vm.set(KEY_AUDIO_PASSTHROUGH, it) },
                )
                NvSettingsRow(
                    title = "设备直通能力",
                    subtitle = remember { passthroughCapabilityLine(ctx) },
                    showDivider = false,
                )
                // P2 已真实生效：novax_background_play=false 时 onStop 暂停、回前台不自动恢复
                // 2026-10-11 流量播放提醒三档化（原开关；弹窗勾「流量下不再提醒」= 不提醒档，
                // 此处三档可改回）。显示值与闸门共用同一解析（含旧键迁移），防显示/行为错位
                // （spec §4.6；勿用 pref(key, 默认值) 占位读——default 非 null 会让迁移分支不可达）
                NvSegmentedRow(
                    "流量播放提醒",
                    options = listOf(
                        METERED_REMIND_EVERY to "每次",
                        METERED_REMIND_SESSION_ONCE to "仅一次",
                        METERED_REMIND_NEVER to "不提醒",
                    ),
                    selected = listOf(
                        meteredRemindMode(
                            prefs?.get(KEY_METERED_REMIND_MODE),
                            // 旧键迁移读取（字面量，与 MeteredGate.remindMode 注释互指）
                            prefs?.get(booleanPreferencesKey("novax_metered_play_remind")),
                        ).toStoredValue(),
                    ),
                    subtitle = "流量下起播/切集/自动连播/通知切集/投屏远程片源前确认；" +
                        "仅一次 = 每次打开应用后确认一次，本会话内不再询问",
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
                    "后台播放",
                    checked = prefs.pref(KEY_BACKGROUND_PLAY, false),
                    subtitle = "开 = 退后台/息屏继续播放，通知栏与锁屏可控；关 = 退后台自动暂停",
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
                    "RMVB 性能模式（跳帧提速）",
                    checked = prefs.pref(KEY_RMVBFramesSkip, false),
                    subtitle = "RM/RMVB 卡顿时开启换流畅，轻微画质代价；下次进 RMVB 播放页生效",
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
    SettingsGroupPage("字幕", onBack) {
        item {
            NvSettingsCard(null) {
                NvSwitchRow(
                    "自动加载同名字幕",
                    checked = prefs.pref(KEY_SUB_AUTO_LOAD, true),
                    subtitle = "播放时自动挂载同目录同名外挂字幕",
                    onChecked = { vm.set(KEY_SUB_AUTO_LOAD, it) },
                )
                NvSegmentedRow(
                    title = "首选语言",
                    // 2026-09-20 用户需求：改单选且只留中文/English；旧值（可能 "zh,en,ja" 多选残留）
                    // 迁移取第一个合法项，无合法项回退 zh
                    options = listOf("zh" to "中文", "en" to "English"),
                    selected = listOf(
                        prefs.pref(KEY_SUB_LANGUAGES, "zh")
                            .split(',')
                            .firstOrNull { it == "zh" || it == "en" } ?: "zh",
                    ),
                    onSelect = { lang -> vm.set(KEY_SUB_LANGUAGES, lang) },
                )
                NvSegmentedRow(
                    "默认编码",
                    options = listOf("auto" to "自动", "utf-8" to "UTF-8", "gbk" to "GBK", "big5" to "BIG5"),
                    selected = listOf(prefs.pref(KEY_SUB_ENCODING, "auto")),
                    // P2 已真实生效：side-load 挂载前按此键重编码为 UTF-8 临时文件（SubtitleCharset）
                    subtitle = "外挂字幕乱码时切换",
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
                    title = "字幕大小 / 延迟",
                    subtitle = "字号在「设置 → 播放器」；延迟在播放器「字幕」菜单",
                )
                // 2026-09-15：时间轴偏移步进转正（播放器字幕菜单 ± 调节的步长）
                NvSegmentedRow(
                    "字幕延迟步进",
                    options = listOf(0.1f to "0.1s", 0.25f to "0.25s", 0.5f to "0.5s", 1f to "1.0s"),
                    selected = listOf(prefs.pref(KEY_SUB_OFFSET_STEP, 0.5f)),
                    subtitle = "播放器「字幕 → 字幕延迟」单次 ± 的步长",
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
    SettingsGroupPage("音频", onBack) {
        item {
            NvSettingsCard(null) {
                // 2026-09-15 用户反馈②修复：4 个互斥选项改用单行等宽分段控件
                //（原 NvChipRow 的 FlowRow 在竖屏窄宽度下折成 3+1，末行只挂一个胶囊＝破版）
                NvSegmentedRow(
                    title = "首选音轨语言",
                    options = listOf("auto" to "跟随文件", "zh" to "中文", "en" to "English", "ja" to "日本語"),
                    selected = listOf(prefs.pref(KEY_AUDIO_LANG, "auto")),
                    subtitle = "多音轨影片优先选中语言",
                    onSelect = { vm.set(KEY_AUDIO_LANG, it) },
                )
                // 与播放器 SpeedSheet 同键（C6 §3.3 共享）：播放器实时读取 pitch
                NvSwitchRow(
                    "变速不变调",
                    checked = prefs.pref(KEY_PRESERVE_PITCH, true),
                    subtitle = "倍速播放时保持原音调",
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
    SettingsGroupPage("网络 / NAS", onBack) {
        item {
            NvSettingsCard(null) {
                // 真实生效：PlaybackEngine.buildDataSourceFactory → DefaultHttpDataSource 超时
                NvSegmentedRow(
                    "连接超时",
                    options = listOf(5 to "5s", 8 to "8s", 15 to "15s"),
                    selected = listOf(prefs.pref(KEY_NET_TIMEOUT_SEC, 8)),
                    subtitle = "网络流连接/读取超时，慢 NAS 调大",
                    onSelect = { vm.set(KEY_NET_TIMEOUT_SEC, it) },
                )
                // 2026-09-15 假开关真化：两项均接入 NvNetTuning 运行时槽（MainActivity 收集，
                // SmbClient 开流时消费）。默认 4MB 与旧行为一致；显式改小才覆盖（contains() 门控）
                NvSegmentedRow(
                    "SMB 读取块大小",
                    // 2026-10-12 用户验收:选项按大小升序(与上方连接超时 5s/8s/15s 同风格);值集不变,
                    // NvSegmentedRow 按 value 判选中(Settings.kt:366-374),顺序仅影响视觉
                    options = listOf("512KB" to "512KB", "1MB" to "1MB", "4MB" to "4MB"),
                    selected = listOf(prefs.pref(KEY_SMB_BLOCK, "4MB")),
                    subtitle = "单块越大 NAS 往返越少；老 NAS/高丢包环境可调小；默认 4MB",
                    onSelect = { vm.set(KEY_SMB_BLOCK, it) },
                )
                NvSwitchRow(
                    "SMB 并发预读",
                    checked = prefs.pref(KEY_SMB_PREFETCH, true),
                    subtitle = "提前读取下一块，播放更顺滑；极慢 NAS 可关",
                    onChecked = { vm.set(KEY_SMB_PREFETCH, it) },
                )
                NvSwitchRow(
                    "流量网络下也生成封面",
                    checked = prefs.pref(KEY_THUMB_METERED, false),
                    subtitle = "开 = 蜂窝/计量网络也自动抽视频封面(每张有 32MB 读取上限,消耗流量)",
                    onChecked = { vm.set(KEY_THUMB_METERED, it) },
                )
                NvSwitchRow(
                    "局域网发现",
                    checked = prefs.pref(KEY_LAN_DISCOVERY, true),
                    subtitle = "文件页发现区自动扫描（关闭省电）",
                    onChecked = { vm.set(KEY_LAN_DISCOVERY, it) },
                    showDivider = false,
                )
            }
        }
    }
}

// ---------------- 5. 界面 ----------------

@Composable
private fun UiGroupPage(vm: SettingsViewModel, prefs: Preferences?, backExit: Boolean, onBack: () -> Unit) {
    SettingsGroupPage("界面", onBack) {
        item {
            NvSettingsCard(null) {
                NvSegmentedRow(
                    "主题",
                    options = listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统"),
                    selected = listOf(prefs.pref(KEY_UI_THEME, "dark")),
                    onSelect = { vm.set(KEY_UI_THEME, it) },
                    // 2026-09-20 浅色模式落地：chips 激活（MainActivity NovaXTheme 消费同一键）
                    // rework2（2026-10-15 用户反馈「画蛇添足」）：subtitle「播放界面始终为深色」删除
                )
                // 键先落位：C3 媒体库网格列数（Library 端接入留后续 WS）
                NvSegmentedRow(
                    "海报墙列数",
                    options = listOf("auto" to "自动", "2" to "2", "3" to "3", "4" to "4"),
                    selected = listOf(prefs.pref(KEY_UI_POSTER_COLUMNS, "auto")),
                    subtitle = "媒体库网格列数",
                    onSelect = { vm.set(KEY_UI_POSTER_COLUMNS, it) },
                )
                NvSwitchRow(
                    "列表显示文件大小",
                    checked = prefs.pref(KEY_UI_SHOW_FILE_SIZE, true),
                    subtitle = "文件列表副标题显示大小段",
                    onChecked = { vm.set(KEY_UI_SHOW_FILE_SIZE, it) },
                )
                // 真实生效：写 novax_ui_prefs 同一键，MainActivity BackHandler 消费（C11 R1/R2）
                NvSwitchRow(
                    "返回键直接退出",
                    checked = backExit,
                    subtitle = "关 = Tab 根按返回需 2s 内再按一次确认退出",
                    onChecked = { vm.setBackExit(it) },
                )
                NvSegmentedRow(
                    "启动页",
                    options = listOf("home" to "首页", "library" to "媒体库", "files" to "文件"),
                    selected = listOf(prefs.pref(KEY_UI_START_TAB, "home")),
                    onSelect = { vm.set(KEY_UI_START_TAB, it) },
                    showDivider = false,
                )
            }
        }
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
    val cacheLabel = cacheBytes?.let { "%.1f MB".format(it / 1_048_576.0) } ?: "计算中…"

    SettingsGroupPage("隐私与数据", onBack) {
        item {
            NvSettingsCard(null) {
                // 2026-09-27 用户需求：升级为历史管理入口（条目可见、可续播、可跳文件夹），
                // 清除/删除操作移入历史页内
                NvEntryRow("观看历史", value = "$historyCount 条记录",
                    onClick = onOpenHistory)
                NvEntryRow("清除缓存", value = cacheLabel,
                    onClick = { askCache = true })
                NvEntryRow("重置所有设置", danger = true, showDivider = false,
                    onClick = { askReset = true })
            }
        }
    }

    if (askCache) {
        NvConfirmDialog(
            title = "清除缓存",
            message = "清除 $cacheLabel 缓存？不影响观看历史与已收藏内容。",
            confirmText = "清除",
            onConfirm = {
                askCache = false
                vm.clearCache { freed ->
                    cacheRefreshTick++
                    Toast.makeText(ctx, "已清除 %.1f MB".format(freed / 1_048_576.0), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { askCache = false },
        )
    }
    if (askReset) {
        NvConfirmDialog(
            title = "重置所有设置",
            message = "将把全部设置还原为默认值；观看历史与收藏保留。",
            confirmText = "重置",
            danger = true,
            onConfirm = {
                askReset = false
                vm.resetAll { Toast.makeText(ctx, "已重置", Toast.LENGTH_SHORT).show() }
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
            ?: throw IllegalStateException("MediaStore 写入失败")
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IllegalStateException("输出流打开失败")
    } else {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "novax_donate_qr.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    }
}

// 2026-10-19 用户需求：打赏入口上移至设置首页，弹窗随迁并提为公共组件
@Composable
fun NvDonateDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { saveDonateQr(ctx) }
                .onSuccess { Toast.makeText(ctx, "已保存到相册", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(ctx, "未授权存储权限，无法保存", Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(NvShape.Dialog)
                .background(NvColors.SurfaceElevated)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("打赏作者", style = NvTypography.TitleMedium, color = NvColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            // 2026-10-19 用户需求：标题下加副题
            Text("请作者喝杯咖啡", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
            Spacer(Modifier.height(14.dp))
            Image(
                painter = painterResource(R.drawable.donate_wechat_qr),
                contentDescription = "微信打赏收款码",
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(2f / 3f)
                    .clip(NvShape.Dialog),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                // 2026-10-19 用户定稿文案
                "软件免费，自愿打赏，感谢支持",
                style = NvTypography.LabelMedium,
                color = NvColors.TextSecondary,
            )
            Spacer(Modifier.height(14.dp))
            Row {
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = NvColors.TextSecondary)
                }
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching { saveDonateQr(ctx) }
                            .onSuccess { Toast.makeText(ctx, "已保存到相册", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
                    } else {
                        permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) {
                    Text(
                        "保存到相册",
                        color = NvColors.Primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
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
    SettingsGroupPage("关于", onBack) {
        item {
            NvSettingsCard(null) {
                NvSettingsRow(
                    title = "版本",
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text(version, style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 2026-10-09 用户需求：关于页作者署名
                NvSettingsRow(
                    title = "作者",
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text("YouwuLiu", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 2026-10-16 用户需求：作者联系方式
                NvSettingsRow(
                    title = "邮箱",
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text("liuyouwu1999@163.com", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 2026-10-19 用户需求：关于页直达 GitHub 开源地址
                NvEntryRow("开源地址", onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setData(android.net.Uri.parse("https://github.com/liuyouwu1999/NovaX-Player")),
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(ctx, "未找到可打开链接的应用", android.widget.Toast.LENGTH_SHORT).show()
                    }
                })
                // 2026-10-19 用户需求：完全免费声明（反诈口径；禁止倒卖条款见用户协议二）
                NvSettingsRow(
                    title = "费用",
                    subtitle = null,
                    onClick = null,
                    showDivider = true,
                ) {
                    Text("完全免费", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                }
                // 2026-10-19 打赏入口上移至设置首页（原关于页行删除）
                // 2026-10-16 v1.0 公开发布（spec §1.4）：合规文档入口（首页→设置→关于 = 3 次点击可达）
                NvEntryRow("隐私政策", onClick = { onOpenPolicyDoc(PolicyDoc.PRIVACY) })
                NvEntryRow("用户协议", onClick = { onOpenPolicyDoc(PolicyDoc.AGREEMENT) })
                // 2026-10-19 用户需求：免责声明直达（即用户协议六）
                NvEntryRow("免责声明", onClick = { onOpenPolicyDoc(PolicyDoc.AGREEMENT) })
                // 2026-09-15 二期占位清理：个人分发无更新源，「检查更新」假入口删除
                NvEntryRow("开源致谢", onClick = onOpenLicenses)
                // 2026-09-15 D2：本地崩溃日志查看/分享（纯本地；无记录时点按提示）
                NvEntryRow("崩溃日志", onClick = {
                    val latest = com.novax.app.data.NvCrashLog.latest(ctx)
                    if (latest == null) {
                        android.widget.Toast.makeText(ctx, "暂无崩溃记录", android.widget.Toast.LENGTH_SHORT).show()
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
                            ctx.startActivity(android.content.Intent.createChooser(intent, "分享崩溃日志"))
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, "日志：${latest.name}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                })
                // 「反馈渠道」假入口同步删除（2026-09-15 二期占位清理）
            }
        }
    }
}
