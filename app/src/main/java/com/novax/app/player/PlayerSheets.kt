@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.novax.app.data.cast.CastMediaResolver
import com.novax.app.data.cast.CastUiState
import com.novax.app.data.cast.CastSessionHolder
import com.novax.app.ui.cast.NvCastDeviceSheet
import com.novax.app.ui.cast.NvCastMeteredDialogHost
import com.novax.app.ui.cast.rememberNvCastGateState
import com.novax.app.ui.components.NvPlayerPopup
import com.novax.app.ui.components.NvSlider
import com.novax.app.ui.components.NvBannerKind
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvInlineBanner
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.components.NvLoader
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvSecondaryButton
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// ============================================================
// 从 PlayerScreen.kt 机械切出（2026-09-28 审计 A：巨型文件拆分，零逻辑改动，private→internal）
// ============================================================

@Composable
internal fun SubtitleSheet(
    player: Player,
    engine: PlayerHandle,
    candidates: List<SubtitleCandidate>,
    trackRefresh: Int,
    // 2026-09-17 功能项：外挂字幕编码播放器内快捷切换（设置键 KEY_SUB_ENCODING，切换即重挂）
    encodingPref: String,
    onEncodingPick: (String) -> Unit,
    onPickFile: (SubtitleCandidate) -> Unit,
    onClose: () -> Unit,
) {
    // 内嵌轨（side-load 中的同目录字幕单独归入「同目录字幕文件」分组，不重复展示）
    val embedded = remember(trackRefresh, engine.activeSubtitleId) {
        textGroups(player).filter { (group, trackIndex) ->
            group.getTrackFormat(trackIndex).id != engine.activeSubtitleId
        }
    }
    val disabled = remember(trackRefresh) {
        player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    }
    val selectedIdx = remember(trackRefresh) { selectedTrackIndex(player, C.TRACK_TYPE_TEXT) }
    NvPlayerPopup(title = "字幕", onDismiss = onClose) {
        NvSheetListItem("关闭字幕", selected = disabled || selectedIdx == null) {
            setTextTrack(player, null)
        }
        if (candidates.isNotEmpty() && embedded.isNotEmpty()) {
            SheetSectionLabel("内嵌字幕")
        }
        if (candidates.isEmpty() && embedded.isEmpty()) {
            Text(
                "未找到字幕",
                style = NvTypography.LabelMedium,
                color = NvColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        embedded.forEach { (group, trackIndex) ->
            val format = group.getTrackFormat(trackIndex)
            NvSheetListItem(
                text = format.language?.let { langName(it) } ?: "字幕轨 ${format.label ?: ""}",
                selected = selectedIdx == trackIndex && group.isSelected,
                onClick = { setTextTrack(player, group) },
            )
        }
        if (candidates.isNotEmpty()) {
            SheetSectionLabel("同目录字幕文件")
            candidates.forEach { candidate ->
                SubtitleFileRow(
                    candidate = candidate,
                    selected = engine.activeSubtitleId == PlaybackEngine.sideLoadIdFor(candidate.path),
                    onClick = { onPickFile(candidate) },
                )
            }
        }
        // 2026-10-12 用户验收:弹窗字太多看不懂——编码区只在真挂着外挂字幕时才有意义,
        // 未挂时整区(含解释长句)消失;全局默认编码仍在 设置→播放器
        val activeSideLoad = candidates.firstOrNull {
            engine.activeSubtitleId == PlaybackEngine.sideLoadIdFor(it.path)
        }
        if (activeSideLoad != null) {
            SheetSectionLabel("外挂字幕编码")
            // 2026-09-17 功能项:GBK/BIG5 乱码时的播放器内快捷修正——写入设置键后
            // 按新编码重新挂载当前外挂字幕(进度保留)
            listOf("auto" to "自动探测", "utf-8" to "UTF-8", "gbk" to "GBK", "big5" to "BIG5").forEach { (v, label) ->
                NvSheetListItem(label, selected = encodingPref == v, onClick = { onEncodingPick(v) })
            }
        }
        SheetSectionLabel("字幕延迟")
        SubtitleDelayRow()
    }
}

/**
 * 字幕延迟调节行（2026-09-15）：−步进 / 重置 / +步进，正数=字幕延后、负数=字幕提前。
 * 写 KEY_SUB_DELAY_MS 并同步 NvSubtitleOffset 单例；装饰 parser 对新样本即刻生效，
 * 已解码在途样本不变（2026-10-12 验收：弹窗字太多看不懂,删除行尾脚注 Text——
 * 生效时机说明保留在 NvSubtitleOffset.kt 文档,行为不变）。步进读 KEY_SUB_OFFSET_STEP（设置-播放器）。
 */
@Composable
internal fun SubtitleDelayRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var delayMs by remember { mutableStateOf(NvSubtitleOffset.delayMs) }
    val stepMs = remember {
        ((runCatching { playerPrefsSnapshot()?.get(KEY_SUB_OFFSET_STEP) }.getOrNull() ?: 0.5f) * 1000).toLong()
    }
    fun apply(new: Long) {
        delayMs = new
        NvSubtitleOffset.delayMs = new
        scope.launch { runCatching { context.putPlayerPref(KEY_SUB_DELAY_MS, new) } }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                delayMs == 0L -> "无偏移"
                delayMs > 0 -> "字幕延后 ${delayMs / 1000.0} 秒"
                else -> "字幕提前 ${-delayMs / 1000.0} 秒"
            },
            style = NvTypography.BodyMedium,
            color = NvColors.TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        listOf(
            "-${stepMs / 1000.0}s" to { apply(delayMs - stepMs) },
            "重置" to { apply(0L) },
            "+${stepMs / 1000.0}s" to { apply(delayMs + stepMs) },
        ).forEach { (label, action) ->
            // 2026-10-09 UI 审计 B6：触控目标 ≥48dp——chip 视觉不变（文字+胶囊 ripple），
            // 外层 Box 抬到最小 48dp 承载点击
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(NvShape.Badge)
                    .clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = NvTypography.LabelLarge,
                    color = NvColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 弹层分组小标题（C6 §3.1 内嵌 / 同目录字幕文件） */
@Composable
internal fun SheetSectionLabel(text: String) {
    Text(
        text,
        style = NvTypography.LabelMedium,
        color = NvColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** 同目录字幕文件行：文件名 + 语言 tag + 「同名」chip + 检查标 */
@Composable
internal fun SubtitleFileRow(
    candidate: SubtitleCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val loadable = SubtitleMatcher.mimeTypeForFile(candidate.path) != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    candidate.displayName,
                    style = NvTypography.BodyMedium,
                    color = if (selected) NvColors.Primary else NvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (candidate.isExact) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(NvShape.Badge)
                            .background(NvColors.PrimaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("同名", style = NvTypography.LabelSmall, color = NvColors.Primary)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subtitleLangLabel(candidate) + if (loadable) "" else " · 暂不支持加载",
                style = NvTypography.LabelSmall,
                color = NvColors.TextSecondary,
            )
        }
        if (selected) {
            Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = NvColors.Primary)
        }
    }
}

/** 进入播放器时多候选手选弹层（C6 §3.1），含「不加载字幕」 */
@Composable
internal fun SubtitlePickerSheet(
    candidates: List<SubtitleCandidate>,
    onPick: (SubtitleCandidate) -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
) {
    NvPlayerPopup(title = "选择字幕", onDismiss = onClose) {
        Text(
            "同目录找到 ${candidates.size} 个字幕文件，请选择要加载的字幕",
            style = NvTypography.LabelMedium,
            color = NvColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        candidates.forEach { candidate ->
            SubtitleFileRow(candidate = candidate, selected = false, onClick = { onPick(candidate) })
        }
        NvSheetListItem("不加载字幕", selected = false, onClick = onDecline)
    }
}

/** 候选语言描述：归一化 tag → 本地化名，无 tag 显示格式/评分提示 */
internal fun subtitleLangLabel(candidate: SubtitleCandidate): String {
    val tag = candidate.languageTag?.let { SubtitleMatcher.normalizeLanguageTag(it) }
    if (tag != null) return when (tag) {
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁体中文"
        "zh-HK" -> "粤语"
        "zh" -> "中文"
        "en", "en-US", "en-GB" -> "英文"
        "ja" -> "日文"
        "ko" -> "韩文"
        else -> tag.uppercase()
    }
    return if (candidate.isExact) "与视频同名" else "同目录字幕"
}

@Composable
internal fun AudioTrackSheet(player: Player, trackRefresh: Int, onClose: () -> Unit) {
    val groups = remember(trackRefresh) { audioGroups(player) }
    val selectedIdx = remember(trackRefresh) { selectedTrackIndex(player, C.TRACK_TYPE_AUDIO) }
    NvPlayerPopup(title = "音轨", onDismiss = onClose) {
        groups.forEach { (group, trackIndex) ->
            val format = group.getTrackFormat(trackIndex)
            val channels = when (format.channelCount) {
                1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"
                else -> "${format.channelCount}ch"
            }
            NvSheetListItem(
                text = "${format.language?.let { langName(it) } ?: "音轨"} · $channels${if (format.sampleMimeType != null) " · ${format.sampleMimeType?.substringAfter('/')?.uppercase()}" else ""}",
                selected = selectedIdx == trackIndex && group.isSelected,
                onClick = { setAudioTrack(player, group) },
            )
        }
        if (groups.isEmpty()) {
            Text("无音轨信息", style = NvTypography.LabelMedium, color = NvColors.TextSecondary,
                modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
internal fun SpeedSheet(
    current: Float,
    muteHighSpeed: Boolean,
    onDismiss: () -> Unit,
    onPick: (Float) -> Unit,
) {
    // BUG-C-R1-01：关闭面板走 onDismiss——原实现复用 onPick(current)，会回写菜单记忆速度
    // 并清掉本次新增的下滑倍速锁定（锁定静默丢失）
    NvPlayerPopup(title = "播放速度", onDismiss = onDismiss) {
        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { s ->
            NvSheetListItem(
                // 3x「静音」角标随设置（novax_mute_above_2x，阈值 2026-09-18 由 >2x 提至 ≥3x）
                text = formatSpeedLabel(s) + if (s >= 3f && muteHighSpeed) "（自动静音）" else "",
                selected = abs(current - s) < 0.01f,
                onClick = { onPick(s) },
            )
        }
        // 2026-10-08 用户需求：0.1~5.0 连续细调（0.1 步进吸附）——显示与提交同口径走
        // snapPlaybackSpeed；松手才 onPick（拖动中不写引擎，防每帧变速冲刷）
        SheetSectionLabel("细调（0.1x 步进，0.1~5.0）")
        var fine by rememberSaveable { mutableFloatStateOf(current.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)) }
        Text(
            String.format(java.util.Locale.US, "%.1f", snapPlaybackSpeed(fine)) + "x",
            style = NvTypography.TitleMedium,
            color = NvColors.Primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        NvSlider(
            value = fine,
            onValueChange = { fine = it },
            onValueChangeFinished = { onPick(snapPlaybackSpeed(fine)) },
            valueRange = PLAYBACK_SPEED_MIN..PLAYBACK_SPEED_MAX,
            steps = 48,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * M3（2026-10-07）清晰度面板：档位列表、当前档高亮；AUTO 档带「起播原画，卡顿自动降档」
 * 说明文案（与自动档策略 NvQualityAutoPolicy 的用户可感语义一致）。
 * 2026-10-08：[sourceBitrateBps] > 0 时隐藏「档位上限 ≥ 源码率」的转码档（该形态下服务器
 * 本就不转码、原画即该档最佳，视频 App 同款「无此清晰度不显示」）；原画行标注源码率。
 * 非 Jellyfin 源只显示提示（顶栏入口已按源隐藏，此分支为防御性兜底，正常不可达）。
 */
@Composable
internal fun QualitySheet(
    current: NvQualityTier,
    isJellyfinSource: Boolean,
    effectiveTier: NvQualityTier? = null,
    sourceBitrateBps: Long = 0,
    onDismiss: () -> Unit,
    onPick: (NvQualityTier) -> Unit,
) {
    NvPlayerPopup(title = "清晰度", onDismiss = onDismiss) {
        if (!isJellyfinSource) {
            Text(
                "仅 Jellyfin 源支持清晰度切换",
                style = NvTypography.BodyMedium,
                color = NvColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return@NvPlayerPopup
        }
        NvQualityTier.availableTiers(sourceBitrateBps).forEach { t ->
            NvSheetListItem(
                text = when {
                    t == NvQualityTier.ORIGINAL && sourceBitrateBps > 0 -> {
                        // 原画行标注源码率：用户可直接看出「原画 = X M」，被隐藏档位的语义自明
                        val mbps = String.format(java.util.Locale.US, "%.1f", sourceBitrateBps / 1_000_000.0)
                        "原画（源码率约 ${mbps}M）"
                    }
                    // M4：自动档副标展示当前生效档（AUTO 运行中始终可见真实画质；自动档降档后面板重开即见）
                    t == NvQualityTier.AUTO && effectiveTier != null ->
                        "自动（当前生效：${effectiveTier.label}）"
                    t == NvQualityTier.AUTO -> "自动（起播原画，卡顿自动降档）"
                    else -> t.label
                },
                selected = current == t,
                onClick = { onPick(t) },
            )
        }
    }
}

@Composable
internal fun AspectRatioSheet(
    current: AspectMode,
    onPick: (AspectMode) -> Unit,
) {
    NvPlayerPopup(title = "画面比例", onDismiss = { onPick(current) }) {
        AspectMode.entries.forEach { mode ->
            NvSheetListItem(mode.label, selected = mode == current, onClick = { onPick(mode) })
        }
    }
}

/**
 * 「更多」面板（2026-09-17 功能项）：AB 循环 + 睡眠定时。
 * AB 循环走心跳判定（越过 B 点跳回 A），换集/重建引擎自动清空；
 * 睡眠定时跨重建有效，到点暂停。
 */
@Composable
internal fun MoreActionsSheet(
    loopAMs: Long,
    loopBMs: Long,
    sleepMinutes: Int,
    sleepRemainingMs: Long?,
    onSetLoopA: () -> Unit,
    onSetLoopB: () -> Unit,
    onClearLoop: () -> Unit,
    onSleepPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    NvPlayerPopup(title = "更多", onDismiss = onClose) {
        SheetSectionLabel("AB 循环")
        val loopActive = loopAMs >= 0 && loopBMs > loopAMs
        NvSheetListItem("把当前进度设为循环起点 A", selected = false, onClick = onSetLoopA)
        NvSheetListItem("把当前进度设为循环终点 B", selected = false, onClick = onSetLoopB)
        if (loopActive) {
            NvSheetListItem(
                "清除循环（${formatTime(loopAMs)} → ${formatTime(loopBMs)} 循环播放中）",
                selected = true,
                onClick = onClearLoop,
            )
        }
        SheetSectionLabel("睡眠定时")
        listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟").forEach { (min, label) ->
            val selected = if (min == 0) sleepMinutes == 0 else sleepMinutes == min
            val text = if (min > 0 && selected && sleepRemainingMs != null) {
                val remainMin = ((sleepRemainingMs + 59_999) / 60_000).toInt()
                "$label · 剩余 $remainMin 分钟"
            } else {
                label
            }
            NvSheetListItem(text, selected = selected, onClick = { onSleepPick(min) })
        }
    }
}

/** 信息面板键值行 */
@Composable
internal fun InfoRow(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = NvTypography.BodyMedium, color = NvColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = NvTypography.BodyMedium,
            color = NvColors.TextPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 视频信息「文件」行取值（返工⑤ 2026-10-15 用户第四项反馈）：完整文件名（含扩展名）——
 * playUrl（novax:// 编码路径 / 本地绝对路径 / 远程直连）末段 URL 解码；空白/不可得回退库内标题。
 * 同 MainActivity.filePlayTitle 口径但**不去扩展名**（用户要的是完整文件名）。
 */
internal fun videoInfoFileName(playUrl: String?, title: String): String {
    if (!playUrl.isNullOrBlank()) {
        val seg = playUrl.substringBefore('?').substringAfterLast('/')
        if (seg.isNotBlank()) {
            val decoded = runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
            if (decoded.isNotBlank()) return decoded
        }
    }
    return title
}

/**
 * 视频信息面板（2026-09-15 用户需求）：当前播放的视频编码/分辨率/帧率/码率/动态范围 +
 * 音频编码/声道/采样率 + 轨道统计。数据取自 player 当前选中的 video/audio Format（onTracksChanged
 * 后就绪；trackRefresh 变化时重算），未就绪显示「--」。
 * 返工⑤（2026-10-15 用户第四项反馈）：「视频」段首行前加「文件」= 完整文件名（含扩展名）。
 */
@Composable
internal fun VideoInfoSheet(
    player: Player,
    videoW: Int,
    videoH: Int,
    trackRefresh: Int,
    fileName: String,
    onClose: () -> Unit,
) {
    val info = remember(trackRefresh) { MediaTrackInfo.of(player.currentTracks, videoW, videoH) }
    NvPlayerPopup(title = "视频信息", onDismiss = onClose) {
        SheetSectionLabel("视频")
        InfoRow("文件", fileName)
        InfoRow("编码", info.videoCodec)
        InfoRow("分辨率", info.resolution)
        InfoRow("帧率", info.frameRate)
        InfoRow("码率", info.videoBitrate)
        InfoRow("动态范围", info.dynamicRange)
        // 2026-09-15 用户实测（杜比视界测试片有声音无画面）：面板如实给出归因，
        // 不让用户对着黑屏猜（Profile 5 无 DV 解码器必然黑屏 / Profile 8 本应 HDR10 回退）
        info.dynamicRangeNote?.let { note ->
            Spacer(Modifier.height(8.dp))
            NvInlineBanner(
                text = note,
                kind = NvBannerKind.Error,
                modifier = Modifier.padding(horizontal = NvSpacing.L),
            )
        }
        SheetSectionLabel("音频")
        InfoRow("编码", info.audioCodec)
        InfoRow("声道", info.channels)
        InfoRow("采样率", info.sampleRate)
        InfoRow("码率", info.audioBitrate)
        SheetSectionLabel("轨道")
        InfoRow("音轨数", info.audioTrackCount.toString())
        InfoRow("字幕轨数", info.textTrackCount.toString())
        Spacer(Modifier.height(10.dp))
    }
}

// ---------------- Track 选择（ExoPlayer track API） ----------------

internal fun textGroups(player: Player): List<Pair<Tracks.Group, Int>> =
    player.currentTracks.groups.mapIndexedNotNull { _, g ->
        if (g.type == C.TRACK_TYPE_TEXT) firstTrack(g)?.let { g to it } else null
    }

internal fun audioGroups(player: Player): List<Pair<Tracks.Group, Int>> =
    player.currentTracks.groups.mapIndexedNotNull { _, g ->
        if (g.type == C.TRACK_TYPE_AUDIO) firstTrack(g)?.let { g to it } else null
    }

internal fun firstTrack(group: Tracks.Group): Int? =
    (0 until group.length).firstOrNull { group.isTrackSupported(it) }

internal fun selectedTrackIndex(player: Player, type: Int): Int? =
    when (type) {
        C.TRACK_TYPE_TEXT -> textGroups(player).firstOrNull { it.first.isSelected }?.second
        else -> audioGroups(player).firstOrNull { it.first.isSelected }?.second
    }

internal fun setTextTrack(player: Player, group: Tracks.Group?) {
    val builder = player.trackSelectionParameters.buildUpon()
    if (group == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val trackIndex = firstTrack(group) ?: return
        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
    }
    player.trackSelectionParameters = builder.build()
}

internal fun setAudioTrack(player: Player, group: Tracks.Group) {
    val trackIndex = firstTrack(group) ?: return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
}

internal fun langName(code: String): String = when (code.lowercase()) {
    "zh" -> "中文"; "en" -> "英文"; "ja" -> "日文"; "ko" -> "韩文"
    else -> code.uppercase()
}

// ---------------- 工具 ----------------

/** C10「变速不变调」（novax_audio_preserve_pitch，默认 true）：开 = pitch 1f；关 = pitch 随 speed（变调） */
internal fun pitchFor(speed: Float, preservePitch: Boolean): Float = if (preservePitch) 1f else speed

internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
