package com.novax.app.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * C6 §2.3（2026-09-14 修订）+ C10 设置页：播放器偏好统一 DataStore 文件。
 * 独立文件（novax_player_prefs）：与 ui.files 的 novax_settings（电池标记）分离，
 * 避免同名文件多 DataStore 实例冲突。C10 修正节：设置页全部键落本文件（扩展），
 * 读写复用同一 [playerPrefsStore] 委托，禁止在他处二次声明同名 DataStore。
 *
 * 键名与默认值与 docs/design/C10-settings.md §3 总表一一对应；
 * 「读取端默认值」= 键缺省时的行为，重置即 clear() 回到缺省。
 */

// ---------------- C6 既有键 ----------------

/** 左手模式慢放开关（长按左半屏 = 0.5x 慢放），默认 false */
val KEY_LEFT_SLOWMO = booleanPreferencesKey("novax_left_slowmo")
val KEY_BUFFER_LEVEL = stringPreferencesKey("novax_buffer_level")

/** 画面比例记忆（PlayerScreen AspectMode.name：FIT/FILL/R16_9/R4_3），默认 FIT */
val KEY_ASPECT_MODE = stringPreferencesKey("novax_aspect_mode")

// ---------------- C10 · 播放器 ----------------

/** 默认倍速模式："fixed"（固定 1x）| "remember"（记忆上次） */
val KEY_SPEED_MODE = stringPreferencesKey("novax_playback_speed_mode")

/** 上次倍速（非 UI 键：SpeedSheet 改倍速 / 退出播放器时写入） */
val KEY_LAST_SPEED = floatPreferencesKey("novax_playback_last_speed")

/** Jellyfin 清晰度档位记忆（NvQualityTier.name：ORIGINAL/P20M/P10M/P4M/P2M/AUTO），默认 AUTO。
 *  非 UI 键：PlayerScreen QualitySheet 写、PlayerActivity 起播读（对齐 KEY_LAST_SPEED 先例） */
val KEY_PLAYBACK_QUALITY = stringPreferencesKey("novax_playback_quality")

/** 长按快进总开关，默认 true；关 = 长按倍速手势禁用（左半屏慢放不受影响） */
val KEY_LONG_PRESS_ON = booleanPreferencesKey("novax_long_press_speed_enabled")

/** 长按倍速值（滑块六档 0.5/1/1.5/2/2.5/3，见 [LONG_PRESS_SPEED_PRESETS]），默认 2.0f；运行时覆盖 LONG_PRESS_SPEED 常量 */
val KEY_LONG_PRESS_SPEED = floatPreferencesKey("novax_long_press_speed")

/** 长按倍速值预设档（2026-09-18 用户需求：设置页滑块选档，滑到哪吸附哪档；索引即滑块刻度） */
val LONG_PRESS_SPEED_PRESETS = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)

/** 3x 及以上自动静音，默认 [DEFAULT_MUTE_ABOVE_2X]（2026-10-09 起 false，用户需求「3x倍速自动静音这个默认关闭」；
 *  键名沿用 novax_mute_above_2x，2026-09-18 阈值由 >2x 提至 ≥3x）：长按倍速 / SpeedSheet 3x 档期间静音。
 *  显式开启过的用户保持其选择（DataStore 有值优先）。 */
val KEY_MUTE_ABOVE_2X = booleanPreferencesKey("novax_mute_above_2x")

/** [KEY_MUTE_ABOVE_2X] 唯一默认值出口；全部读点/初值点一律引本常量，禁止散落布尔字面量 */
const val DEFAULT_MUTE_ABOVE_2X = false

/**
 * 双击快进/快退秒数（2026-09-18 用户需求：四档 10/15/25/30，默认 15；档位见 [DOUBLE_TAP_SEC_CHOICES]）。
 * 同一步长同时驱动：双击三区手势（左退/右进）与底栏 ±Ns 按钮（按钮文字随档位）。
 */
val KEY_DOUBLE_TAP_SEC = intPreferencesKey("novax_double_tap_seek_seconds")

/** 快进/快退步长档位 */
val DOUBLE_TAP_SEC_CHOICES = listOf(10, 15, 25, 30)

/** 快进/快退步长默认档（15s） */
const val DEFAULT_DOUBLE_TAP_SEC = 15

/** 滑动快进灵敏度："low" | "normal" | "high"，默认 "normal"（消费：C6 §2.6，键先落位）
 *  2026-10-08 退役：由 [KEY_SWIPE_FULL_PERCENT] 百分比口径替代，键留声明不删（历史值休眠） */
val KEY_SWIPE_SENS = stringPreferencesKey("novax_seek_swipe_sensitivity")

/**
 * 滑动快进步长：整屏滑动 = 片长的百分比（1~50），默认 10。
 * 2026-10-08 用户需求「选到多少就是百分之多少」滑杆自由设定；替换三档灵敏度
 * [KEY_SWIPE_SENS]（倍率口径删除——隐藏系数与「选多少是多少」矛盾，旧键留声明供历史值休眠）。
 * 消费：PlayerGesturePolicy.swipeFullMsFor（主播放器经 GestureEvents.swipeFullPercent，
 * RMVB 页经 uiSwipeFullPercent，同键同口径）。
 */
val KEY_SWIPE_FULL_PERCENT = intPreferencesKey("novax_swipe_full_percent")

/** 精确跳转（SeekParameters.EXACT），默认 false */
val KEY_PRECISE_SEEK = booleanPreferencesKey("novax_precise_seek")

/** 打开时续播记忆位置，默认 true（消费：起播链路，键先落位） */
val KEY_RESUME_ON_OPEN = booleanPreferencesKey("novax_resume_on_open")

/** 后台播放（二期，需 MediaSessionService），默认 false */
val KEY_BACKGROUND_PLAY = booleanPreferencesKey("novax_background_play")

/**
 * 半锁模式（2026-10-08 用户需求），默认 true：开 = 底栏锁按钮两级切换（半锁→全锁）；
 * 关 = 一下直进全锁（2026-09-19 既有行为）。半锁下手势全部可用（亮度/滑动进度/快进快退/
 * 双击暂停/长按倍速/双指缩放），唯独单击屏幕不弹控制层；两级解锁统一左下角锁图标双击。
 * 消费：PlayerScreen onLock/角标覆盖层；RmvbPlayerActivity 同语义（uiHalfLockOn）。
 */
val KEY_HALF_LOCK_ENABLED = booleanPreferencesKey("novax_half_lock_enabled")

// ---------------- 流量播放提醒三档（2026-10-11，spec §4.1） ----------------

/** 档位落盘值常量（字符串键，惯例同 [KEY_DECODER]） */
const val METERED_REMIND_EVERY = "every"

/** 默认档：每次打开应用（前台会话）确认一次，会话内不再弹（spec §3） */
const val METERED_REMIND_SESSION_ONCE = "session_once"

/** 永久豁免：弹窗勾「流量下不再提醒」或设置页「不提醒」档（原 KEY_METERED_PLAY_REMIND=false 语义） */
const val METERED_REMIND_NEVER = "never"

/**
 * 流量播放提醒档位（2026-10-11 三档化，默认仅一次）：
 * every = 每次起播/切集/投屏远程片源前都弹（2026-09-28 原行为）；
 * session_once = 每次打开应用确认一次，会话内全部入口不再弹；
 * never = 从不弹。会话口径与终结钩子见 spec §3/§4.5
 * （2026-10-11 计费提醒会话设计）。
 */
val KEY_METERED_REMIND_MODE = stringPreferencesKey("novax_metered_remind_mode")

/** 档位语义枚举（解析与裁决用；落盘一律走上面的字符串值常量） */
enum class MeteredRemindMode { EVERY, SESSION_ONCE, NEVER }

/** [MeteredRemindMode] → 落盘字符串（设置页三档段显示值回写用） */
fun MeteredRemindMode.toStoredValue(): String = when (this) {
    MeteredRemindMode.EVERY -> METERED_REMIND_EVERY
    MeteredRemindMode.SESSION_ONCE -> METERED_REMIND_SESSION_ONCE
    MeteredRemindMode.NEVER -> METERED_REMIND_NEVER
}

/**
 * 档位解析（纯函数，JVM 可测）：新键优先，未知值回落默认档；
 * 旧键 novax_metered_play_remind 仅在无新键时读——false = 用户曾显式永久豁免 → NEVER，
 * true/缺省 → 默认 SESSION_ONCE。读时兼容不写回、不删盘上旧条目（spec §4.1）。
 */
fun meteredRemindMode(stored: String?, legacyRemind: Boolean?): MeteredRemindMode = when {
    stored != null -> when (stored) {
        METERED_REMIND_EVERY -> MeteredRemindMode.EVERY
        METERED_REMIND_NEVER -> MeteredRemindMode.NEVER
        else -> MeteredRemindMode.SESSION_ONCE
    }
    legacyRemind == false -> MeteredRemindMode.NEVER
    else -> MeteredRemindMode.SESSION_ONCE
}

/** 解码器优先："auto" | "hw_first" | "sw_first"（下次播放生效，键先落位） */
val KEY_DECODER = stringPreferencesKey("novax_decoder_priority")

// ---------------- C10 · 字幕 ----------------

/** 自动加载同名字幕，默认 true */
val KEY_SUB_AUTO_LOAD = booleanPreferencesKey("novax_subtitle_auto_load")

/** 首选字幕语言，逗号分隔（"zh" / "zh,en,ja" 子集），默认 "zh" */
val KEY_SUB_LANGUAGES = stringPreferencesKey("novax_subtitle_languages")

/** 外挂字幕默认编码："auto" | "utf-8" | "gbk" | "big5"，默认 "auto" */
val KEY_SUB_ENCODING = stringPreferencesKey("novax_subtitle_encoding")

/** 字幕大小缩放（0.8–1.4）——2026-09-15 删除：消费点是 KEY_SUBTITLE_SIZE 档位，本键从未接通（死键） */

/** 字幕时间轴偏移步进（0.1 / 0.25 / 0.5 / 1.0），默认 0.5f（消费：SubtitleSheet 字幕延迟 ± 步进） */
val KEY_SUB_OFFSET_STEP = floatPreferencesKey("novax_subtitle_offset_step")

/** 2026-09-15：字幕延迟毫秒数（可负=提前；消费：NvSubtitleOffset，SubtitleSheet 调节，从下一句字幕生效） */
val KEY_SUB_DELAY_MS = longPreferencesKey("novax_subtitle_delay_ms")

// ---------------- C10 · 音频 ----------------

/** 首选音轨语言："auto" | "zh" | "en" | "ja"，默认 "auto" */
val KEY_AUDIO_LANG = stringPreferencesKey("novax_audio_language")

/** 变速不变调，默认 true；关 = pitch 随 speed（变调） */
val KEY_PRESERVE_PITCH = booleanPreferencesKey("novax_audio_preserve_pitch")

/** 2026-09-15 D1：播完自动连播下一集（文件夹队列；默认开） */
val KEY_AUTO_NEXT = booleanPreferencesKey("novax_auto_next")

/** 2026-09-15 D4：字幕字号档位（"0.85"小 / "1.0"标准 / "1.25"大，乘 18sp） */
val KEY_SUBTITLE_SIZE = stringPreferencesKey("novax_subtitle_size")

/** 2026-09-15 D5：退后台自动进画中画（默认关） */
val KEY_PIP_AUTO = booleanPreferencesKey("novax_pip_auto")

/** 2026-09-15 D6：音量增益档位（"1.0"/"1.5"/"2.0"；LoudnessEnhancer +0/+3.5/+6dB） */
val KEY_VOLUME_BOOST = stringPreferencesKey("novax_volume_boost")

/**
 * 2026-09-15：AudioTrack 编码直通（AC3/E-AC3/AC4 等位流直送 AudioTrack，默认开）。
 * 2026-09-22 停用（长按倍速音画不同步根治，见 NvRenderersFactory 类注释）：直通位流无法
 * 应用内变速（Sonic 只处理 PCM），直通+倍速 = 渐进 desync（androidx/media #2283）。
 * 键保留只为设置页开关记忆值；引擎侧已无条件 PCM 解码，本键不再参与决策。
 */
val KEY_AUDIO_PASSTHROUGH = booleanPreferencesKey("novax_audio_passthrough")

/**
 * 2026-09-20 RMVB 性能模式（官方 VLC「性能模式」KEY_ENABLE_FRAME_SKIP 同语义，默认关）：
 * 开 = libVLC 以 --avcodec-skip-frame 2 --avcodec-skip-idct 2 起播（跳非参考帧/IDCT，
 * RMVB 软解倍速卡顿的逃生门；轻微画质代价）。LibVLC 实例为 Activity 生命周期级，
 * 设置变更在下次进入 RMVB 播放页时生效。
 */
val KEY_RMVBFramesSkip = booleanPreferencesKey("novax_rmvb_frame_skip")

/**
 * 2026-09-19 软解路由记忆（per-file）：KEY_SOFT_ROUTE_KEY_PREFIX + 播放地址 → 原因
 * （"音轨 DTS" / "杜比视界画面"）：该文件在这台设备上已确认硬解不了，再次打开直接路由
 * libVLC，零等待。
 * 2026-09-29 FFmpeg 音频扩展落地：per-mime 集合（原 KEY_SOFT_UNSUPPORTED_AUDIO_MIMES）
 * 删除——AC3/EAC3/DTS/TrueHD 主播放器已可软解出声，mime 级记忆自此必然错误；
 * per-file 记忆保留（视频类原因仍有效），其中「音轨」类历史记录由
 * [migrateSoftRouteMemoriesOnce] 一次性清除。
 */
const val SOFT_ROUTE_KEY_PREFIX = "novax_softroute_"

/** 2026-09-29 迁移标记：novax_softroute_* 的「音轨」类记录 + 已删 mime 集合只清一次 */
val KEY_SOFT_ROUTE_MIGRATED_FFAUDIO = booleanPreferencesKey("novax_softroute_migrated_ffaudio")

/** 纯函数（JVM 单测锁）：输入 name→value 快照，输出应删除的键名集合 */
fun planSoftRouteMigration(entries: Map<String, String>): Set<String> {
    val drop = mutableSetOf("novax_soft_unsupported_audio_mimes")
    for ((key, value) in entries) {
        if (key.startsWith(SOFT_ROUTE_KEY_PREFIX) && value.contains("音轨")) drop.add(key)
    }
    return drop
}

/** 幂等迁移（PlayerActivity.onCreate 调）：清「音轨」类旧路由记忆 + 标记防重跑 */
suspend fun Context.migrateSoftRouteMemoriesOnce() {
    val prefs = playerPrefsStore.data.first()
    if (prefs.get(KEY_SOFT_ROUTE_MIGRATED_FFAUDIO) == true) return
    playerPrefsStore.edit { m ->
        val snapshot = m.asMap()
        val drop = planSoftRouteMigration(snapshot.entries.associate { (k, v) -> k.name to v.toString() })
        for ((k, _) in snapshot) if (k.name in drop) m.remove(k)
        m[KEY_SOFT_ROUTE_MIGRATED_FFAUDIO] = true
    }
}

/**
 * 迁移后台触发（PlayerActivity.onCreate 调，非挂起）：必须走 [playerPrefsIoScope] 而非
 * lifecycleScope——软解记忆直连路由在 onCreate 后 ~190ms 内 finish()，lifecycleScope 取消
 * 会连带取消未提交的迁移编辑，旧记忆永不清除（真机实证：DTS 旧记忆→路由→libVLC
 * 必崩的死循环）。App scope 写不被 Activity 销毁取消：首启即使读侧撞上竞态路由+崩溃，
 * 迁移仍落盘，第二次打开必然直连主播放器。
 */
fun Context.migrateSoftRouteMemoriesInBackground() {
    playerPrefsIoScope.launch { runCatching { migrateSoftRouteMemoriesOnce() } }
}

/** 2026-09-15：TMDB v3 API Key（用户在设置-媒体库自填；空 = 刮削不发起，UI 如实提示） */
val KEY_TMDB_API_KEY = stringPreferencesKey("novax_tmdb_api_key")

// ---------------- C10 · 网络 / NAS ----------------

/** HTTP 连接/读取超时秒数（5 / 8 / 15），默认 8；消费：PlaybackEngine DefaultHttpDataSource */
val KEY_NET_TIMEOUT_SEC = intPreferencesKey("novax_net_connect_timeout_seconds")

/**
 * 音画偏移补偿（2026-09-23 长按倍速/大跳后音画不同步的兜底修法，单位毫秒，默认 0）。
 * 背景：部分机型（真机实测）音频输出链路（杜比/音效栈）有 ~0.3-0.5s 的固有延迟，
 * app 侧拿不到该值（时间戳不含音效级延迟），表现为「嘴巴先动、声音后到」。
 * 负值 = 音频提前（丢料）；正值 = 音频延后（延迟队列）。消费：NvAvOffsetAudioProcessor
 * （起播与每次 seek/变速冲刷时重新武装）；设置-播放器「音画偏移」行调节，按设备记忆。
 */
val KEY_AV_OFFSET_MS = intPreferencesKey("novax_av_offset_ms")

/** 音画偏移档位（值=毫秒，负=音频提前）：消费：设置-播放器「音画偏移」NvChipRow */
val AV_OFFSET_MS_CHOICES = listOf(
    -1000 to "-1s",
    -500 to "-0.5s",
    -250 to "-0.25s",
    0 to "0",
    250 to "+0.25s",
    500 to "+0.5s",
)

/** SMB 读取块大小："256KB" | "512KB" | "1MB" | "4MB"，默认 "4MB"（消费：C8 §3.1，NvNetTuning 默认同源） */
val KEY_SMB_BLOCK = stringPreferencesKey("novax_smb_read_block")

/** SMB 并发预读，默认 true（消费：C8 §2，键先落位） */
val KEY_SMB_PREFETCH = booleanPreferencesKey("novax_smb_prefetch")

/** 局域网发现总闸，默认 true（消费：C5 发现区，键先落位） */
val KEY_LAN_DISCOVERY = booleanPreferencesKey("novax_lan_discovery")

/** 2026-10-12 §1a:流量(计量)网络下也允许远程封面抽帧;默认 false=维持 Wi-Fi only。
 *  消费:NvThumbHolder 闸门(封面抽帧专用;扫描嗅探闸门 NvScannerHolder 不消费) */
val KEY_THUMB_METERED = booleanPreferencesKey("novax_thumb_metered")

// ---------------- C10 · 界面 ----------------

/** 主题，仅 "dark"（夜间专用；浅色二期不改键） */
val KEY_UI_THEME = stringPreferencesKey("novax_ui_theme")

/** 海报墙列数："auto" | "2" | "3" | "4"，默认 "auto"（消费：C3 网格，键先落位） */
val KEY_UI_POSTER_COLUMNS = stringPreferencesKey("novax_ui_poster_columns")

/** 列表显示文件大小，默认 true（消费：C5 NvFileRow，键先落位） */
val KEY_UI_SHOW_FILE_SIZE = booleanPreferencesKey("novax_ui_show_file_size")

/** 启动页："home" | "library" | "files"，默认 "home"（消费：C11 初始 Tab，键先落位） */
val KEY_UI_START_TAB = stringPreferencesKey("novax_ui_start_tab")

// ---------------- DataStore 委托（全 app 唯一） ----------------

/**
 * C10 设置页与播放器共用的唯一 novax_player_prefs 委托。
 * 「返回键直接退出」（novax_ui_back_exit）按 MainActivity 既有实现存 novax_ui_prefs，不在本文件。
 */
internal val Context.playerPrefsStore by preferencesDataStore(name = "novax_player_prefs")

/** 网络超时合法档位与默认值（C10 §3.4） */
val NET_TIMEOUT_CHOICES_SEC = listOf(5, 8, 15)
const val DEFAULT_NET_TIMEOUT_SEC = 8

/** 读取端 Flow（设置页 / 播放器 collect 用） */
fun Context.playerPrefsFlow(): Flow<Preferences> = playerPrefsStore.data

/** 播放器内实时收集全部偏好；null = DataStore 尚未加载完成（读侧回退默认值） */
@Composable
fun rememberPlayerPrefs(): State<Preferences?> {
    val context = LocalContext.current
    return remember(context) { context.playerPrefsStore.data }.collectAsState(initial = null)
}

/** 左手慢放开关实时读取（默认 false）；设置页提供写入端 [setLeftSlowmoEnabled] */
@Composable
fun rememberLeftSlowmoEnabled(): State<Boolean> {
    val prefs by rememberPlayerPrefs()
    return remember { derivedStateOf { prefs?.get(KEY_LEFT_SLOWMO) ?: false } }
}

// ---------------- 写入端（C10 设置页） ----------------

/** 通用单键写入（C10 §4.3：开关/Chip 点击即写）；写入成功同步刷新内存缓存快照 */
suspend fun <T> Context.putPlayerPref(key: Preferences.Key<T>, value: T) {
    playerPrefsStore.edit { it[key] = value }.let { prefsCache = it }
}

/** C6 §7.1 左半屏慢放写入端（复用同一委托，禁止二次声明 DataStore） */
suspend fun Context.setLeftSlowmoEnabled(enabled: Boolean) = putPlayerPref(KEY_LEFT_SLOWMO, enabled)

/** C10 隐私「重置所有设置」：键清空回默认（不含观看历史/收藏，不动 battery_optimization_asked）；
 *  与 putPlayerPref 同口径同步刷新内存缓存（BUG-C-R1-06：原实现缓存不失效，
 *  httpTimeoutMs 等快照消费者读到旧值直至下次任意写） */
suspend fun Context.resetPlayerPrefsToDefaults() {
    playerPrefsStore.edit { it.clear() }.let { prefsCache = it }
}

/** 独立 IO scope：退出播放器（Composition 销毁）后的兜底写不会被取消 */
private val playerPrefsIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 内存缓存快照（P2 修复主线程 runBlocking）：首次异步加载后非阻塞可读；putPlayerPref 写后同步 */
@Volatile
private var prefsCache: Preferences? = null

/** 内存缓存快照读取（非阻塞，无 IO）：null = 缓存尚未加载完成，读侧回退默认值 */
fun playerPrefsSnapshot(): Preferences? = prefsCache

/**
 * 预热内存缓存（PlayerActivity.onCreate 调用）：异步读一次 DataStore，
 * 之后 [playerPrefsSnapshot] 毫秒级返回，杜绝主线程同步等待。
 */
fun Context.warmPlayerPrefsCache() {
    if (prefsCache != null) return
    playerPrefsIoScope.launch {
        runCatching { playerPrefsStore.data.first() }.onSuccess { prefsCache = it }
    }
}

/**
 * 记录「上次倍速」（novax_playback_last_speed，C6 §7.3 / C10 §3.1）：
 * SpeedSheet 改倍速与退出播放器时调用；mode=fixed 也照写，切回「记忆上次」立即有值。
 */
fun Context.recordLastSpeed(speed: Float) {
    playerPrefsIoScope.launch {
        runCatching { putPlayerPref(KEY_LAST_SPEED, speed) }
    }
}

/**
 * HTTP 连接/读取超时（novax_net_connect_timeout_seconds，5/8/15s，默认 8s）。
 * P2 修复：只读内存缓存快照（非阻塞，≤50ms 红线内），不再 runBlocking 同步等 DataStore；
 * 起播链路由 PlayerActivity 在 IO 协程预读后显式传入 [buildDataSourceFactory]。
 */
fun Context.httpTimeoutMs(): Int {
    val sec = prefsCache?.get(KEY_NET_TIMEOUT_SEC)
    return (if (sec != null && sec in NET_TIMEOUT_CHOICES_SEC) sec else DEFAULT_NET_TIMEOUT_SEC) * 1_000
}

/**
 * 音画偏移毫秒数（novax_av_offset_ms，默认 0）：非阻塞读内存快照，消费在引擎构建期
 * （PlaybackEngine → NvRenderersFactory → NvAvOffsetAudioProcessor）。
 */
fun Context.avOffsetMs(): Int {
    val v = prefsCache?.get(KEY_AV_OFFSET_MS) ?: 0
    return v.coerceIn(-NvAvOffsetAudioProcessor.MAX_OFFSET_MS, NvAvOffsetAudioProcessor.MAX_OFFSET_MS)
}
