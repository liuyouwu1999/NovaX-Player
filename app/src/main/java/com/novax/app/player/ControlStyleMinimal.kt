package com.novax.app.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.theme.NvPlayerBlue

/**
 * 极简样式包（spec 2026-11-15 §3.2，T6）：nPlayer Simple Line 式。
 * - 常驻层（不受控制层显隐门控）：贴屏幕最底缘细线（已播 Primary/未播半透明白）+ 线右上方
 *   常驻半透明小字当前时间；**纯展示零热区**（不挂任何 pointerInput——拖动进度需先 tap 唤起，
 *   避免底缘带「单击唤起 vs 横滑全屏 seek vs tap-seek」三方冲突，评审 P0-3）。
 * - 唤起态：rev4 §3.2 蓝块顶栏（蓝✕/位置时间/标题/剩余时间/蓝⋯——⋯=极简档次要功能
 *   唯一入口族）+ 左右侧钮列（左=锁三态/CC 字幕/齿轮更多，右=倍速三件套 ⊕/档位标签/⊖——
 *   垂直定位由调用侧 R4 负责）；中央与底部无任何按钮行，底栏唯一内容 = NvChapterSlider
 *   line 模式（由宿主接线）。
 * - 状态与回调全部来自 [NvControlContract]（样式包不自带业务状态）。
 */

/**
 * 常驻层可见性门控（spec §3.2 门控矩阵，评审 P0-3.3）：
 * 未锁/半锁 → 显示（半锁允许看进度；半锁不唤起控制层，controlsShown 恒 false）；全锁 → 隐藏
 * （锁态=纯净画面）；错误态 → 隐藏；唤起态 → 隐藏，由门控内 line 进度条**原位顶替**
 * （二轮评审 P1-2 裁决：防双线——常驻线与唤起细线逐像素重合，视觉上「细线还在、变为可拖」）。
 */
internal fun persistentLayerVisible(fullLocked: Boolean, hasError: Boolean, controlsShown: Boolean): Boolean =
    !fullLocked && !hasError && !controlsShown

/**
 * 极简常驻层（spec §3.2）：细线贴最底缘 + 时间小字在线右上方（BottomEnd）。
 * 宿主置于门控外（AnimatedVisibility 之外）并以 align(Bottom) 锚定；本组件零热区——
 * Canvas/Text 均不消费触摸，事件自然穿到根 Box 的手势状态机。
 */
@Composable
internal fun MinimalPersistentLayer(c: NvControlContract, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(24.dp)) {
        // DrawScope 非组合上下文：主题 token 先提升为局部值（仓内既定姿势）
        val cPrimary = NvColors.Primary
        // 细线（约 3dp）：未播段半透明白整条打底，已播段 Primary 覆盖；时长未知（直播类）只画底线
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.BottomStart),
        ) {
            val w = size.width
            val y = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), 3.dp.toPx())
            if (c.durationMs > 0) {
                val played = (c.positionMs.toFloat() / c.durationMs).coerceIn(0f, 1f)
                if (played > 0f) drawLine(cPrimary, Offset(0f, y), Offset(w * played, y), 3.dp.toPx())
            }
        }
        Text(
            formatTime(c.positionMs),
            style = NvTypography.LabelSmall,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 6.dp),
        )
    }
}

/**
 * 剩余时间标签（rev4 §3.2 顶栏右半）：`-` + [formatTime]（durationMs−positionMs）。
 * 隐藏口径（返 null）：durationMs<=0（直播/无时长，rev2 总时长隐藏先例）；position 越界
 * （负值或超出时长=数据未就绪/竞态脏值，宁缺勿错）；diff<=0（恰播完）。
 */
internal fun remainingLabel(durationMs: Long, positionMs: Long): String? {
    if (durationMs <= 0) return null
    if (positionMs < 0 || positionMs > durationMs) return null
    val diff = durationMs - positionMs
    if (diff <= 0) return null
    return "-" + formatTime(diff)
}

/**
 * 极简唤起态顶栏（rev4 §3.2，nPlayer 截图 1:1 复刻）：深色半透明横条 44dp，左起——
 * 蓝✕（[NvPlayerBlue] 圆角方块=退出播放器）→ 位置时间（positionMs，复用 [formatTime]）
 * → 标题（居中弹性占位/省略号截断）→ 剩余时间（负号格式，[remainingLabel] 隐藏口径）
 * → 蓝⋯（更多面板入口，极简档次要功能唯一入口族）。
 * 签名（契约 + modifier）与 rev2 一致，两侧调用点零改动。
 */
@Composable
internal fun MinimalTopBar(c: NvControlContract, modifier: Modifier = Modifier) {
    Row(
        // scrim 同款半透明深底（宿主外层另有 ScrimStrong→透明渐变，两层叠加出 nPlayer 深色横条观感）
        modifier.fillMaxWidth().height(44.dp).background(NvColors.Scrim).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(NvPlayerBlue, RoundedCornerShape(10.dp))
                .clickable(onClick = c.onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, stringResource(R.string.player_cd_close), Modifier.size(24.dp), tint = Color.White)
        }
        Text(
            formatTime(c.positionMs),
            color = Color.White,
            style = NvTypography.LabelMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Text(
            c.title,
            color = Color.White,
            style = NvTypography.LabelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        remainingLabel(c.durationMs, c.positionMs)?.let {
            Text(
                it,
                color = Color.White.copy(alpha = 0.85f),
                style = NvTypography.LabelMedium,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        } ?: Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(44.dp)
                .background(NvPlayerBlue, RoundedCornerShape(10.dp))
                .clickable(onClick = c.onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.player_sheet_more), Modifier.size(24.dp), tint = Color.White)
        }
    }
}

/**
 * 极简倍速三件套步进（rev4 §3.2 右列 ⊕/⊖）：加 delta 后吸附 0.1 网格并 clamp 0.1~5.0
 * （复用 [snapPlaybackSpeed]，防裸浮点累加漂移透出到引擎与显示）。重置不走本函数
 * （组件层直接 onSetSpeed(1f)，契约语义=SpeedSheet.onPick 同款五动作）。
 */
fun nvSpeedStep(current: Float, delta: Float): Float = snapPlaybackSpeed(current + delta)

/**
 * 侧钮列共用圆钮（rev4 §3.2）：46dp 黑色半透明圆底（Black 55%，nPlayer Simple Line 实测）
 * + 24dp 白 icon。与顶栏蓝块不同——侧钮列是「圆」不是「圆角方块」，1:1 对照用户归档截图。
 */
@Composable
private fun MinimalRailButton(onClick: () -> Unit, contentDescription: String, icon: ImageVector) {
    Box(
        Modifier
            .size(46.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(24.dp), tint = Color.White)
    }
}

/**
 * 极简唤起态左列（rev4 §3.2）：锁（三态图标与 NvPlayerControlRow.LockSlot 同口径——
 * 全锁 Filled.Lock/半锁 Filled.LockOpen/未锁 Outlined.LockOpen，cd 复用 player_cd_lock）
 * → CC 字幕（Outlined.Subtitles，cd 复用 player_sheet_subtitles）→ 齿轮更多
 * （Outlined.Settings，cd 复用 player_sheet_more）。
 * **垂直定位由调用侧负责**（R4 接线：align(CenterStart)+offset(y=屏高×6%)）——组件只交付纯 Column。
 */
@Composable
internal fun MinimalSideRail(c: NvControlContract, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MinimalRailButton(
            onClick = c.onLock,
            contentDescription = stringResource(R.string.player_cd_lock),
            icon = when (c.lockMode) {
                2 -> Icons.Filled.Lock
                1 -> Icons.Filled.LockOpen     // 半锁：实心开锁（与未锁的空心开锁区分，LockSlot 同款）
                else -> Icons.Outlined.LockOpen
            },
        )
        MinimalRailButton(
            onClick = c.onSubtitle,
            contentDescription = stringResource(R.string.player_sheet_subtitles),
            icon = Icons.Outlined.Subtitles,
        )
        MinimalRailButton(
            onClick = c.onMore,
            contentDescription = stringResource(R.string.player_sheet_more),
            icon = Icons.Outlined.Settings,
        )
    }
}

/**
 * 极简唤起态右列（rev4 §3.2）：倍速三件套 ⊕/当前档标签/⊖。
 * - ⊕/⊖ = [nvSpeedStep] ±0.1 步进（吸附防漂移）经 c.onSetSpeed 提交；
 * - 中间恒显 [formatSpeedLabel] 当前档（"1x"/"1.5x"），非 1x 时 [NvPlayerBlue] 高亮，
 *   点击重置 c.onSetSpeed(1f)（1x 时点击无效果，幂等）。
 * **垂直定位由调用侧负责**（R4 接线：align(CenterEnd)+offset(y=屏高×6%)）——组件只交付纯 Column。
 */
@Composable
internal fun MinimalSpeedRail(c: NvControlContract, modifier: Modifier = Modifier) {
    // i18n：semantics 块非组合上下文，资源解析提升到组合期（streaming SPEED 槽同款）
    val resetCd = stringResource(R.string.player_speed_reset)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MinimalRailButton(
            onClick = { c.onSetSpeed(nvSpeedStep(c.speed, +0.1f)) },
            contentDescription = stringResource(R.string.player_speed_plus),
            icon = Icons.Outlined.Add,
        )
        Box(
            Modifier
                .size(46.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .clickable(onClick = { c.onSetSpeed(1f) })
                // Text 无 cd 参数：重置语义挂 Box semantics（streaming SPEED 槽同款姿势）
                .semantics { contentDescription = resetCd },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatSpeedLabel(c.speed),
                style = NvTypography.LabelLarge,
                color = if (c.speed != 1f) NvPlayerBlue else Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }
        MinimalRailButton(
            onClick = { c.onSetSpeed(nvSpeedStep(c.speed, -0.1f)) },
            contentDescription = stringResource(R.string.player_speed_minus),
            icon = Icons.Outlined.Remove,
        )
    }
}

/** 极简底栏槽位（rev4 §3.2 布局图底栏行顺序：锁/上集/快退/暂停/快进/下集/比例）。 */
enum class MinimalSlot { LOCK, PREV, REW, PAUSE, FWD, NEXT, ASPECT }

/** 窄幅（<480dp 竖屏/折叠栏）去锁钮与比例钮——锁经左圆钮、比例经更多面板仍可达（spec §3.2）。 */
internal fun minimalBottomSlots(narrow: Boolean): List<MinimalSlot> =
    if (narrow) listOf(MinimalSlot.PREV, MinimalSlot.REW, MinimalSlot.PAUSE, MinimalSlot.FWD, MinimalSlot.NEXT)
    else MinimalSlot.entries.toList()

/**
 * 极简唤起态底栏（rev4 §3.2）：黑色半透明横条内七钮一行**等距**（Arrangement.SpaceEvenly，
 * 实测用户截图等距分布）——锁 │ 上集 │ 快退(skipSec) │ 暂停 │ 快进(skipSec) │ 下集 │ 画面比例。
 * - **背景由宿主 Column 统一承载**（`Modifier.background(NvColors.Scrim)` 接在两侧 MINIMAL
 *   分支的 `Column { MinimalBottomBar; NvChapterSlider(LINE) }` 上）——黑条背景向下延伸覆盖
 *   进度条 48dp 触摸区，按钮+线连成一个连续黑块、线贴块底缘=贴屏幕底缘（2026-11-15 三星
 *   真机缺陷根修：原「黑条+透明触摸区」在黑背景视频上呈死黑、按钮行视觉悬空；组件自带背景
 *   会与宿主背景双层叠加出 84%≠60% 接缝，故统一上移宿主，本组件不再自画底）。
 * - 暂停 = 经典 PauseSlot 同款 NvGlassChip 58dp/30dp 磨砂圆钮（宽幅）；窄幅尺寸走
 *   [nvNarrowControlMetrics]/[nvRowIconFor]（streaming 底栏同源档位，五钮才放得下）。
 * - 上/下集**灰置不隐藏**（NvClusterButton enabled，nPlayer 一致口径）；锁=三态图标
 *   （LockSlot 同口径无 caption）；比例=开比例面板（onAspect）。
 * - 细线进度条不在本组件——宿主按 §3.2 布局图接线 `Column { MinimalBottomBar; NvChapterSlider(LINE) }`
 *   （黑条在上、line 贴底缘，与常驻线原位顶替语义）。
 */
@Composable
internal fun MinimalBottomBar(c: NvControlContract, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
    ) {
        val narrow = maxWidth < 480.dp
        val slots = minimalBottomSlots(narrow)
        val (pauseSize, btn) = if (narrow) nvNarrowControlMetrics(maxWidth) else 58.dp to 48.dp
        val pauseIcon = if (narrow) pauseSize * 0.48f else 30.dp
        val rowIcon = if (narrow) nvRowIconFor(btn) else 24.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEach { slot ->
                when (slot) {
                    MinimalSlot.LOCK -> IconButton(onClick = c.onLock, modifier = Modifier.size(btn)) {
                        Icon(
                            when (c.lockMode) {
                                2 -> Icons.Filled.Lock
                                1 -> Icons.Filled.LockOpen     // 半锁：实心开锁（与未锁的空心开锁区分，LockSlot 同款）
                                else -> Icons.Outlined.LockOpen
                            },
                            stringResource(R.string.player_cd_lock),
                            Modifier.size(rowIcon),
                            tint = Color.White,
                        )
                    }
                    MinimalSlot.PREV -> NvClusterButton(
                        icon = Icons.Outlined.SkipPrevious,
                        caption = stringResource(R.string.player_prev_episode),
                        enabled = c.hasPrev,
                        contentDescription = if (c.hasPrev) stringResource(R.string.player_cd_prev)
                        else stringResource(R.string.player_cd_prev_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onPrev,
                    )
                    MinimalSlot.REW -> NvSkipStepButton(rewind = true, sec = c.skipSec, size = btn, iconSize = rowIcon) {
                        c.onSkipBy(-c.skipSec * 1000L)
                    }
                    // 暂停圆钮：经典 PauseSlot 同款 NvGlassChip + GlassFillStrong 磨砂强填充（返工⑤口径）
                    MinimalSlot.PAUSE -> NvGlassChip(
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
                    MinimalSlot.FWD -> NvSkipStepButton(rewind = false, sec = c.skipSec, size = btn, iconSize = rowIcon) {
                        c.onSkipBy(c.skipSec * 1000L)
                    }
                    MinimalSlot.NEXT -> NvClusterButton(
                        icon = Icons.Outlined.SkipNext,
                        caption = stringResource(R.string.player_next_episode),
                        enabled = c.hasNext,
                        contentDescription = if (c.hasNext) stringResource(R.string.player_cd_next)
                        else stringResource(R.string.player_cd_next_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onNext,
                    )
                    // 比例钮：开比例面板（窄幅收更多面板兜底，spec §3.2 窄幅口径）
                    MinimalSlot.ASPECT -> NvClusterButton(
                        icon = Icons.Outlined.AspectRatio,
                        caption = stringResource(R.string.player_aspect_short),
                        enabled = true,
                        contentDescription = stringResource(R.string.player_sheet_aspect),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onAspect,
                    )
                }
            }
        }
    }
}
