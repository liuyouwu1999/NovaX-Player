package com.novax.app.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
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
