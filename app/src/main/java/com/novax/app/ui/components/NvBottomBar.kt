package com.novax.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novax.app.TabItem
import com.novax.app.ui.theme.NvColors

/** 底部导航(2026-10-09 用户需求):选中态整胶囊包住 图标+文字(原 M3 indicator 只包图标);
 *  胶囊 content-hugging 居中于等分格,bar 左右留边 → 边缘 tab(首页/设置)不溢出屏边。
 *  navigationBars 避让见 rework2 ①（inset 在玻璃外消费,胶囊悬于手势区上方）;
 *  无障碍语义用 selectable(role=Role.Tab, selected=) 补齐。
 *  Liquid Glass（spec §9）：Surface→NvGlassBar 玻璃化。
 *  观感返工③（2026-10-15，Apple Music 参照）：容器改**悬浮胶囊**——左右 16dp 留边、底部离屏
 *  12dp、28dp 全圆角（NvGlassBar shape 同款，nvGlassSurface 链首 clip 收口）；行高/选中胶囊逻辑不动。
 *  rework2 精修（2026-10-15，Apple 悬浮 tab bar 形态收口）：
 *  ① navigationBars inset 移出玻璃——胶囊 content-hugging 悬浮于手势区上方（离手势区 12dp），
 *    手势区不再包玻璃（旧「玻璃延伸到手势条区域」形态废止：手势导航下胶囊底部多出一条空玻璃带）；
 *  ② 标签自适应字号（TextAutoSize.StepBased 9~12sp，修「媒体库」三字标签被选中胶囊裁尾字）+
 *    选中胶囊水平 padding L→M（胶囊化后每格宽度不足）。 */
@Composable
fun NvBottomBar(
    tabs: List<TabItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.novax.app.ui.glass.NvGlassBar(
        modifier
            .fillMaxWidth()
            // inset 先消费（胶囊整体悬于手势区上方），FLOAT_GAP 后加（离手势区 12dp 悬浮）
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = com.novax.app.ui.glass.NV_BOTTOM_BAR_SIDE_MARGIN)
            .padding(bottom = com.novax.app.ui.glass.NV_BOTTOM_BAR_FLOAT_GAP),
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NvSpacing.S, vertical = NvSpacing.XS2),
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                // i18n Task 8：Tab 标签资源化（TabItem.labelRes，四语随系统语言）
                val tabLabel = androidx.compose.ui.res.stringResource(tab.labelRes)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bg by animateColorAsState(
                        if (selected) NvColors.Primary.copy(alpha = 0.15f) else Color.Transparent,
                        tween(NvMotion.DurationFast, easing = NvMotion.CurveDecelerate), label = "tabBg",
                    )
                    val fg by animateColorAsState(
                        if (selected) NvColors.Primary else NvColors.TextSecondary,
                        tween(NvMotion.DurationFast), label = "tabFg",
                    )
                    Column(
                        Modifier
                            .clip(NvShape.Pill)
                            .background(bg)
                            .selectable(selected = selected, role = Role.Tab) { if (!selected) onSelect(tab.route) }
                            .padding(horizontal = NvSpacing.M, vertical = NvSpacing.XS2),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tabLabel,
                            modifier = Modifier.size(24.dp),
                            tint = fg,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tabLabel,
                            style = NvTypography.LabelMedium,   // 目标 12sp,spec B1 钉 11~12sp(LabelSmall=10sp 不达标)
                            color = fg,
                            maxLines = 1,
                            softWrap = false,
                            // rework2：等分格宽度不足时 12sp→9sp 逐级收缩（「媒体库」三字选中态裁尾字修复）
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}
