@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.novax.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors
import kotlin.math.roundToInt

// ============================================================
// C10 · 设置组件（DESIGN-SYSTEM §3 本次登记，规格见 docs/design/C10-settings.md §1/§5.2）
// 复用件（NvTopBar / NvFilterChip / NvBottomSheet / NvConfirmDialog）不在此重复定义。
// ============================================================

/** 设置分组卡容器（入口页与组内页通用）：Surface 底、圆角 NvShape.Poster(12dp) */
@Composable
fun NvSettingsCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it,
                Modifier.padding(start = NvSpacing.L, bottom = NvSpacing.S),
                style = NvTypography.LabelMedium,
                color = NvColors.TextSecondary,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(NvShape.Poster).background(NvColors.Surface),
            content = content,
        )
        // 行间 1dp Divider 由 NvSettingsRow 自带（start 内缩 16dp）
    }
}

/**
 * 设置行基座：标题 + 副标题 + trailing 插槽。
 * 行高 min 52dp（热区 ≥48dp），水平内边距 16dp；副标题 LabelMedium 60%；
 * danger=true 标题 NvColors.Error（不可逆动作）；enabled=false 标题置灰 38%。
 */
@Composable
fun NvSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    showDivider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val titleColor = when {
        danger -> NvColors.Error
        !enabled -> NvColors.TextDisabled
        else -> NvColors.TextPrimary
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = NvSpacing.L, vertical = 10.dp)
                .defaultMinSize(minHeight = 48.dp),   // 热区红线
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let {
                it()
                androidx.compose.foundation.layout.Spacer(Modifier.padding(end = NvSpacing.M))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = NvTypography.BodyMedium, color = titleColor)
                subtitle?.let { Text(it, style = NvTypography.LabelMedium, color = NvColors.TextSecondary) }
            }
            trailing()
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = NvSpacing.L),
                thickness = 1.dp,
                color = NvColors.Divider,
            )
        }
    }
}

/** 开关（Material3 Switch 的 Nv 配色：选中轨道 Primary + 白 thumb；disabled 走 38% 灰） */
@Composable
fun NvSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = NvColors.Primary,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = NvColors.Track,
            uncheckedThumbColor = NvColors.TextSecondary,
            uncheckedBorderColor = NvColors.Outline,
            // 2026-09-15 用户反馈③：不可用的能力开关必须看得出来是灰的（设备不支持位流直通时）
            disabledCheckedTrackColor = NvColors.Primary.copy(alpha = 0.38f),
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = NvColors.Track.copy(alpha = 0.5f),
            disabledUncheckedThumbColor = NvColors.TextDisabled,
            disabledUncheckedBorderColor = NvColors.Outline.copy(alpha = 0.4f),
        ),
    )
}

/** 开关行：整行可点切换（热区整行）；trailing 可附「二期」徽标等 */
@Composable
fun NvSwitchRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    NvSettingsRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        showDivider = showDivider,
        onClick = { if (enabled) onChecked(!checked) },
    ) {
        trailing()
        NvSwitch(checked = checked, onCheckedChange = { onChecked(it) }, enabled = enabled)
    }
}

/** 入口行：值摘要 60% + ChevronRight 16dp（进组内页/确认框/致谢页） */
@Composable
fun NvEntryRow(
    title: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    danger: Boolean = false,
    showDivider: Boolean = true,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    NvSettingsRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        danger = danger,
        showDivider = showDivider,
        onClick = onClick,
    ) {
        // 2026-09-18：value 限单行省略——长值（如「未配置（themoviedb.org 免费注册）」）曾把标题/副标题挤成乱团
        value?.let {
            Text(
                it,
                style = NvTypography.LabelMedium,
                color = NvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            Modifier.padding(start = NvSpacing.XS).size(16.dp),
            tint = NvColors.TextDisabled,
        )
    }
}

/** 行内 Chip 组：上行标题、下行 FlowRow(NvFilterChip)；multiSelect 由调用方管理 selected；
 *  enabled=false 整组禁用 38%（开关联动，如「长按倍速值」随「长按快进」）；
 *  trailing 可附「二期」徽标（P2 假开关诚实化：未消费的键统一标注） */
@Composable
fun <T> NvChipRow(
    title: String,
    options: List<Pair<T, String>>,        // 值 to 标签
    selected: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false,
    enabled: Boolean = true,
    subtitle: String? = null,
    showDivider: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        NvSettingsRow(title, subtitle = subtitle, showDivider = false, trailing = trailing)   // 标题行占位
        FlowRow(
            Modifier.padding(start = NvSpacing.L, end = NvSpacing.L, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
        ) {
            options.forEach { (value, label) ->
                NvFilterChip(
                    label = label,
                    selected = value in selected,
                    enabled = enabled,
                    // 2026-09-27 去 AI 味：设置选项选中不挂 ✓，橙色填充表达
                    showSelectionIcon = false,
                    onClick = { onSelect(value) },
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = NvSpacing.L),
                thickness = 1.dp,
                color = NvColors.Divider,
            )
        }
    }
}

/** 滑杆行：拖动中本地态、onValueChangeFinished 松手写盘（C10 §4.3 防每帧写 DataStore）；
 *  steps>0 时配合 tickLabels 在滑杆下方两端对齐显示刻度档位（2026-09-18 长按倍速值滑块）；
 *  enabled=false 整行 38%（与 NvChipRow 禁用观感一致，开关联动）；
 *  trailing 可附「二期」徽标（P2 假开关诚实化） */
@Composable
fun NvSliderRow(
    title: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    display: (Float) -> String = { "%.0f%%".format(it * 100) },
    showDivider: Boolean = true,
    enabled: Boolean = true,
    tickLabels: List<String>? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    // BUG-C-R8-07：外部回显只在非拖动期同步——原 remember(value) 在异步回显到达时换 key
    // 重建 local，拖动被打断、滑块回跳且回跳值被提交
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
    ) {
        NvSettingsRow(title, showDivider = false) {
            trailing()
            Text(display(local), style = NvTypography.LabelMedium, color = NvColors.Primary)
        }
        NvSlider(
            value = local,
            onValueChange = {
                dragging = true
                local = it
            },
            onValueChangeFinished = {
                dragging = false
                onValueChangeFinished(local)
            },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(horizontal = NvSpacing.L),
        )
        // 刻度档位行：与滑杆两端对齐（首末标签中心≈轨道两端刻度），LabelSmall 次要色，选中档主色
        if (tickLabels != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NvSpacing.L),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                tickLabels.forEachIndexed { i, label ->
                    val selectedIdx = local.roundToInt().coerceIn(0, tickLabels.lastIndex)
                    Text(
                        label,
                        style = NvTypography.LabelSmall,
                        color = if (i == selectedIdx) NvColors.Primary else NvColors.TextSecondary,
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = NvSpacing.L),
                thickness = 1.dp,
                color = NvColors.Divider,
            )
        }
    }
}

/**
 * 单行等宽分段控件（2026-09-15 用户反馈②：修复「首选音轨语言」等少量互斥选项被 FlowRow
 * 折成 2 行、末行只挂 1-2 个胶囊的破版观感）。
 *
 * 与 [NvChipRow] 的分工：
 * - 选项 ≤4 且语义互斥（语言 / 档位 / 模式）→ 本组件：N 格平分整行，标签永不折行；
 * - 选项更多或多选（如字幕语言可多选）→ 仍用 FlowRow 版式。
 *
 * 视觉：外层 SurfaceElevated 胶囊轨道 + 3dp 内缩，选中格 Primary 实底黑字，未选透明次级文字。
 */
@Composable
fun <T> NvSegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        NvSettingsRow(title, subtitle = subtitle, showDivider = false)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = NvSpacing.L, end = NvSpacing.L, bottom = 12.dp)
                .clip(NvShape.Button)
                .background(NvColors.SurfaceElevated)
                // 返工⑥（2026-10-15 用户真机截图）：同 Chips.kt NvSegmentedControl——实心 SurfaceElevated
                // 容器挂 1dp hairline（GlassBorderHairline），浅色白压白时容器圆弧可见；仅描边，
                // 填充/文字色/选中态不动
                .border(1.dp, NvColors.GlassBorderHairline, NvShape.Button)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value in selected
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp)
                        .clip(NvShape.Button)
                        // rework2：实心橙→Primary 15% 淡橙（选中态全局统一以底栏为准，grep 复核追加项）
                        .background(if (isSelected) NvColors.Primary.copy(alpha = 0.15f) else Color.Transparent)
                        .then(if (enabled) Modifier.clickable { onSelect(value) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = NvTypography.LabelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else null,
                        color = when {
                            !enabled -> NvColors.TextDisabled
                            // rework2：黑字→Primary 橙字（底栏选中标准；SemiBold 保留）
                            isSelected -> NvColors.Primary
                            else -> NvColors.TextSecondary
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = NvSpacing.L),
                thickness = 1.dp,
                color = NvColors.Divider,
            )
        }
    }
}

/** 「二期」徽标：NvShape.Badge(8dp)，底 Skeleton，字 LabelSmall TextDisabled */
@Composable
fun NvPhase2Badge() {    Text(
        "二期",
        Modifier
            .background(NvColors.Skeleton, NvShape.Badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = NvTypography.LabelSmall,
        color = NvColors.TextDisabled,
    )
}
