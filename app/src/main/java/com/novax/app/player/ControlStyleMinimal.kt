package com.novax.app.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors

/**
 * 极简样式包（spec 2026-11-15 §3.2，T6）：nPlayer Simple Line 式。
 * - 常驻层（不受控制层显隐门控）：贴屏幕最底缘细线（已播 Primary/未播半透明白）+ 线右上方
 *   常驻半透明小字当前时间；**纯展示零热区**（不挂任何 pointerInput——拖动进度需先 tap 唤起，
 *   避免底缘带「单击唤起 vs 横滑全屏 seek vs tap-seek」三方冲突，评审 P0-3）。
 * - 唤起态：细顶栏（返回+标题+总时长+更多键——评审 P0-1：极简档全部次要功能唯一入口）；
 *   中央与底部无任何按钮行，底栏唯一内容 = NvChapterSlider line 模式（由宿主接线）。
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
 * 极简唤起态顶栏（spec §3.2）：返回 + 标题（ellipsis）+ 总时长 + 更多键（P0-1：极简档
 * 唯一功能入口，nPlayer Simple Line 同款带菜单键）。细半透明 32dp 一行，无动作键抽屉。
 * 总时长在引擎解析出真实时长前不显示（durationMs<=0 时空占位误导，时间信息由常驻层小字承载）。
 */
@Composable
internal fun MinimalTopBar(c: NvControlContract, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = c.onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.player_back), Modifier.size(20.dp), tint = Color.White)
        }
        Text(
            c.title,
            color = Color.White,
            style = NvTypography.LabelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        if (c.durationMs > 0) {
            Text(
                formatTime(c.durationMs),
                color = Color.White.copy(alpha = 0.8f),
                style = NvTypography.LabelSmall,
            )
        }
        IconButton(onClick = c.onMore, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.player_sheet_more), Modifier.size(20.dp), tint = Color.White)
        }
    }
}
