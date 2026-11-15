package com.novax.app.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.theme.NvColors

/**
 * 长视频样式包（spec 2026-11-15 §3.3，T7）：优酷式。
 * - 顶栏：返回 + 标题 + 右侧图标簇（投屏/字幕/音轨/更多），**平铺不可滚动**（与经典档的
 *   可滚动动作抽屉区分）；其余入口（锁/比例/方向/镜像/旋转/PIP/清晰度/信息）经「更多」面板（§5.5）。
 * - 底部**单行**：上集/快退/暂停/快进/下集 + 内联时间 + 倍速钮；窄幅（<480dp）去倍速钮
 *   （倍速经顶栏更多与长按手势仍可达，Review Focus #6），时间保留。
 * - 贴底进度线 = 宿主接 NvChapterSlider line 模式 + thickenOnDrag=true（拖动加粗+预览气泡）。
 * - 状态与回调全部来自 [NvControlContract]（样式包不自带业务状态）。
 */

/** streaming 单行底栏槽位（spec §3.3 顺序：上集/快退/暂停/快进/下集/内联时间/倍速）。 */
enum class StreamingSlot { PREV, REW, PAUSE, FWD, NEXT, TIME, SPEED }

/** 窄幅（<480dp）去倍速钮——倍速经顶栏更多与长按手势仍可达（spec §3.3）。 */
internal fun streamingRowSlots(narrow: Boolean): List<StreamingSlot> =
    if (narrow) listOf(StreamingSlot.PREV, StreamingSlot.REW, StreamingSlot.PAUSE, StreamingSlot.FWD, StreamingSlot.NEXT, StreamingSlot.TIME)
    else StreamingSlot.entries.toList()

/**
 * 长视频风顶栏（spec §3.3）：48dp 一行平铺——返回 + 标题（ellipsis）+ 图标簇
 * （投屏/字幕/音轨/更多），**不可滚动**（不用 NvScrollableActionRow，与经典档动作抽屉的形态区分）。
 * [hasCast]=false 时自动减投屏键（RMVB 兜底页无投屏，spec §5.3 键集参数化思想）。
 */
@Composable
internal fun StreamingTopBar(c: NvControlContract, hasCast: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = c.onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.player_back), Modifier.size(26.dp), tint = Color.White)
        }
        Text(
            c.title,
            color = Color.White,
            style = NvTypography.TitleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        // 图标簇（平铺）：清晰度/信息/比例等收「更多」面板（§3.4 功能对等表）
        if (hasCast) {
            IconButton(onClick = c.onCast, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Cast, stringResource(R.string.player_cd_cast), Modifier.size(26.dp), tint = Color.White)
            }
        }
        IconButton(onClick = c.onSubtitle, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Subtitles, stringResource(R.string.player_sheet_subtitles), Modifier.size(26.dp), tint = Color.White)
        }
        IconButton(onClick = c.onAudio, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Audiotrack, stringResource(R.string.player_sheet_audio), Modifier.size(26.dp), tint = Color.White)
        }
        IconButton(onClick = c.onMore, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.player_sheet_more), Modifier.size(26.dp), tint = Color.White)
        }
    }
}

/**
 * 长视频风单行底栏（spec §3.3）：按 [streamingRowSlots] 渲染，整簇水平居中。
 * - 宽幅：暂停 58dp/30dp 图标（经典 PauseSlot 同款 NvGlassChip 磨砂圆钮）+ 侧键 48dp/24dp。
 * - 窄幅（<480dp）：尺寸走 [nvNarrowControlMetrics]/[nvRowIconFor]（经典窄行同源档位，
 *   4 侧键+内联时间才放得下）+ 间隔 12→6dp。
 * - 倍速槽与经典 SpeedSlot 同口径（非 1x 显倍速文本主色，1x 显 Speed 图标）。
 * - 时间行与 NvChapterSlider classic 时间行同源格式化（含直播类时长未知口径）。
 */
@Composable
internal fun StreamingBottomBar(c: NvControlContract, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 9.dp),
    ) {
        val narrow = maxWidth < 480.dp
        val slots = streamingRowSlots(narrow)
        val (pauseSize, btn) = if (narrow) nvNarrowControlMetrics(maxWidth) else 58.dp to 48.dp
        val pauseIcon = if (narrow) pauseSize * 0.48f else 30.dp
        val rowIcon = if (narrow) nvRowIconFor(btn) else 24.dp
        val gap = if (narrow) 6.dp else 12.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEach { slot ->
                when (slot) {
                    StreamingSlot.PREV -> NvClusterButton(
                        icon = Icons.Outlined.SkipPrevious,
                        caption = stringResource(R.string.player_prev_episode),
                        enabled = c.hasPrev,
                        contentDescription = if (c.hasPrev) stringResource(R.string.player_cd_prev)
                        else stringResource(R.string.player_cd_prev_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onPrev,
                    )
                    StreamingSlot.REW -> NvSkipStepButton(rewind = true, sec = c.skipSec, size = btn, iconSize = rowIcon) {
                        c.onSkipBy(-c.skipSec * 1000L)
                    }
                    // 暂停圆钮：经典 PauseSlot 同款 NvGlassChip + GlassFillStrong 磨砂强填充（返工⑤口径）
                    StreamingSlot.PAUSE -> NvGlassChip(
                        modifier = Modifier.size(pauseSize),
                        shape = RoundedCornerShape(pauseSize / 2),
                        fill = NvColors.GlassFillStrong,
                        fillOverlay = true,
                        onClick = c.onTogglePlay,
                    ) {
                        Icon(
                            if (c.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (c.isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                            Modifier.size(pauseIcon).align(Alignment.Center),
                            tint = NvColors.TextPrimary,
                        )
                    }
                    StreamingSlot.FWD -> NvSkipStepButton(rewind = false, sec = c.skipSec, size = btn, iconSize = rowIcon) {
                        c.onSkipBy(c.skipSec * 1000L)
                    }
                    StreamingSlot.NEXT -> NvClusterButton(
                        icon = Icons.Outlined.SkipNext,
                        caption = stringResource(R.string.player_next_episode),
                        enabled = c.hasNext,
                        contentDescription = if (c.hasNext) stringResource(R.string.player_cd_next)
                        else stringResource(R.string.player_cd_next_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onNext,
                    )
                    // 内联时间（优酷式）：与 NvChapterSlider classic 时间行同源格式化（直播口径同款）
                    StreamingSlot.TIME -> Text(
                        if (c.durationMs > 0) "${formatTime(c.positionMs)} / ${formatTime(c.durationMs)}"
                        else stringResource(R.string.player_live, formatTime(c.positionMs)),
                        color = Color.White,
                        style = NvTypography.LabelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                    // 倍速槽：经典 SpeedSlot 同口径（非 1x 文本融进按钮主色激活态；cd 带当前倍速）
                    // i18n：semantics 块非组合上下文，资源解析提升到组合期
                    StreamingSlot.SPEED -> {
                        val speedCd = if (c.speed != 1f) stringResource(R.string.player_speed_current, formatSpeedLabel(c.speed))
                        else stringResource(R.string.player_speed)
                        Box(
                            Modifier
                                .size(btn)
                                .clickable(onClick = c.onSpeed)
                                .semantics {
                                    contentDescription = speedCd
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c.speed != 1f) {
                                Text(
                                    formatSpeedLabel(c.speed),
                                    style = NvTypography.LabelLarge,
                                    color = NvColors.Primary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            } else {
                                Icon(Icons.Outlined.Speed, null, Modifier.size(rowIcon), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
