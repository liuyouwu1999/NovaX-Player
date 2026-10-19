package com.novax.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

// DESIGN-SYSTEM.md §2.12 Chip 筛选（单选/多选由父级管理 selected）
@Composable
fun NvFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // 2026-09-27 用户反馈去 AI 味：选中不再挂 ✓（AI 生成 UI 的招牌套路），选中态由
    // 橙色填充+描边变化表达；确需勾选语义的调用点显式传 true
    showSelectionIcon: Boolean = false,
    filledSelected: Boolean = false,
    compact: Boolean = false,          // 2026-09-15：次级修饰筛选小样式（28dp/LabelSmall/弱描边）
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 28.dp else 32.dp),
        shape = NvShape.Button,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = NvColors.TextSecondary,
            disabledContainerColor = NvColors.Surface,
            disabledLabelColor = NvColors.TextDisabled,
            // rework2（2026-10-15 选中态全局统一，以底栏为准）：filledSelected 实心橙+黑字废止，
            // 与 NvSegmentedControl/底栏统一为淡橙底(Primary 15%)+橙字；非 filledSelected 路径原本一致不动
            selectedContainerColor = if (filledSelected) NvColors.Primary.copy(alpha = 0.15f) else NvColors.PrimaryContainer,
            selectedLabelColor = NvColors.Primary,
        ),
        border = if (!selected) {
            BorderStroke(1.dp, if (compact) NvColors.Outline.copy(alpha = 0.5f) else NvColors.Outline)
        } else null,
        leadingIcon = when {
            leadingIcon != null -> {
                { Icon(leadingIcon, null, Modifier.size(if (compact) 14.dp else 16.dp)) }
            }
            selected && showSelectionIcon -> {
                { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
            }
            else -> null
        },
        label = {
            // 单行省略：目录名等长文本 chip 限宽后截断，不再把同行「清除」挤出屏幕
            Text(
                label,
                style = if (compact) NvTypography.LabelSmall else NvTypography.LabelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * 主分类分段控件（2026-09-16 UI 审计 P2-14：原 NvSegmentChip 死代码 + 库页内联重复实现
 * 二合一——分段控件语义，各段等宽填充可用宽度）。
 * 选中=淡橙底+橙字（rework2 2026-10-15 选中态全局统一以底栏为准，废止旧「橙底黑字」），
 * 未选=透明底次级文字；3dp 内衬 + 3dp 段间距。
 * rework3 观感统一（2026-10-15，用户真机反馈「筛选也做成玻璃效果」）：container=false 时
 * 不画实心 SurfaceElevated 容器面，由调用方自备容器（库页=玻璃面）；默认 true 保持实心，
 * 既有调用点零改动。3dp 内衬属组件几何，两档一致。
 */
@Composable
fun NvSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    container: Boolean = true,
) {
    Row(
        modifier
            .clip(NvShape.Button)
            .then(
                if (container) Modifier
                    .background(NvColors.SurfaceElevated)
                    // 返工⑥（2026-10-15 用户真机截图）：实心容器挂 1dp hairline 描边（GlassBorderHairline
                    // token，玻璃面返工⑤已挂同款）——浅色下 SurfaceElevated≈白≈卡片色，无描边则容器圆弧
                    // 消失（白压白）；深色下色阶差本可见，12% 白 hairline 弱一档不劣化。
                    // container=false（玻璃化路径）不叠加——玻璃面自带 hairline，避免双描边。
                    .border(1.dp, NvColors.GlassBorderHairline, NvShape.Button)
                else Modifier,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(NvShape.Button)
                    // rework2：实心橙→Primary 15% 淡橙（底栏选中标准）
                    .background(if (selected) NvColors.Primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = NvTypography.LabelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    // rework2：黑字→Primary 橙字（底栏选中标准；SemiBold 保留）
                    color = if (selected) NvColors.Primary else NvColors.TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
