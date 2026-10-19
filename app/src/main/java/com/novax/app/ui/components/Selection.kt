@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.ui.components

import androidx.compose.ui.res.pluralStringResource
import com.novax.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

// ============================================================
// C5 二期增强登记组件（docs/design/C5-files.md §五，DESIGN-SYSTEM 组件总表收录）：
// NvCheckRow / NvFilterChipRow / NvHighlightText / NvSearchBar /
// NvSelectionTopBar / NvSelectionBar。全部仅用既有 tokens，不新增色值/字号/圆角字面量。
// ============================================================

/**
 * 勾选行（C5 §5.1 类型过滤器）：24dp Checkbox + 类型图标 + 名称 + 数量。
 * 整行可点切换（热区 ≥48dp）。
 */
@Composable
fun NvCheckRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null,
    showDivider: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onChecked(!checked) }
            .padding(horizontal = NvSpacing.L),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            colors = CheckboxDefaults.colors(
                checkedColor = NvColors.Primary,
                checkmarkColor = Color.White,
                uncheckedColor = NvColors.TextSecondary,
            ),
        )
        icon?.let {
            Icon(it, null, Modifier.size(20.dp), tint = NvColors.TextSecondary)
            Spacer(Modifier.width(NvSpacing.M))
        }
        Text(
            title,
            Modifier.weight(1f),
            style = NvTypography.BodyMedium,
            color = NvColors.TextPrimary,
        )
        count?.let {
            Text(
                "$it",
                style = NvTypography.LabelMedium,
                color = NvColors.TextDisabled,
            )
        }
    }
    if (showDivider) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = NvSpacing.L),
            thickness = 1.dp,
            color = NvColors.Divider,
        )
    }
}

/**
 * Chip 回显行（C5 §5.1 NvFilterChipRow，高 32dp 横向滚动）：
 * 每枚 chip = PrimaryContainer 底 + Primary 文字 + 尾部 Close 14dp（点击单删该维度）；
 * ≥2 枚且 onClearAll != null 时末位追加「清除全部」。
 */
@Composable
fun NvFilterChipRow(
    chips: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    onClearAll: (() -> Unit)? = null,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = NvSpacing.L),
        horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(chips, key = { it.first }) { (label, onRemove) ->
            Row(
                Modifier
                    .clip(NvShape.Button)
                    .background(NvColors.PrimaryContainer)
                    .clickable(onClick = onRemove)
                    .padding(start = NvSpacing.M, end = NvSpacing.S)
                    .height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = NvTypography.LabelMedium, color = NvColors.Primary)
                Spacer(Modifier.width(NvSpacing.XS))
                Icon(
                    Icons.Outlined.Close, stringResource(R.string.misc_sel_remove, label),
                    Modifier.size(14.dp), tint = NvColors.Primary,
                )
            }
        }
        if (chips.size >= 2 && onClearAll != null) {
            item(key = "clearAll") {
                Row(
                    Modifier
                        .clip(NvShape.Button)
                        .border(1.dp, NvColors.Outline, NvShape.Button)
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = NvSpacing.M)
                        .height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.misc_sel_clear_all),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * 命中词高亮文本（C5 §5.3 NvHighlightText）：query 全部出现位置以 Primary 色渲染。
 * 无命中/空 query 时与普通 Text 等价。
 */
@Composable
fun NvHighlightText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    color: Color = NvColors.TextPrimary,
    highlightColor: Color = NvColors.Primary,
    style: TextStyle = NvTypography.BodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val ranges = remember(text, query) {
        com.novax.app.ui.files.highlightRanges(text, query)
    }
    val annotated: AnnotatedString = buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            // BUG-C-R8-09（防御性 clamp）：区间在 lowercase 扩长字符（如 İ→i̇）上可能越出原文本
            val start = r.first.coerceAtMost(text.length)
            val end = (r.last + 1).coerceAtMost(text.length)
            if (end > start) {
                addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold), start, end)
            }
        }
    }
    Text(annotated, modifier, style = style.copy(color = color), maxLines = maxLines, overflow = overflow)
}

/**
 * 搜索栏（C5 §5.3 NvSearchBar，高 56dp，Surface 底圆角 12dp）：
 * 前置 ArrowBack 退出搜索，尾部 Clear 清空；进入自动聚焦拉起键盘。
 * Liquid Glass（spec §9）：搜索态随 FileBrowser 顶栏三态悬浮——玻璃/实色背景按 Task 5
 * NvTopBar 同款链序前置（盖状态栏条带 + 栏全域，不留裸穿带），env 未接线域自动回退实色。
 */
@Composable
fun NvSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.misc_sel_search_hint),
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier
            .fillMaxWidth()
            .then(nvTopBarBackground())
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, Modifier.size(48.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.misc_sel_exit_search),
                tint = NvColors.TextPrimary,
            )
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(NvShape.Input)
                .background(NvColors.Surface)
                .padding(horizontal = NvSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(NvColors.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                textStyle = NvTypography.BodyMedium.copy(color = NvColors.TextPrimary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(vertical = NvSpacing.M),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                placeholder,
                                style = NvTypography.BodyMedium,
                                color = NvColors.TextDisabled,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close, stringResource(R.string.misc_sel_clear),
                        Modifier.size(18.dp), tint = NvColors.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.width(NvSpacing.S))
    }
}

/**
 * 多选顶栏（C5 §5.4 NvSelectionTopBar）：Close 退出 + 「已选 N 项」+ 全选/反选。
 * Liquid Glass（spec §9）：多选态随 FileBrowser 顶栏三态悬浮——原实色 background 在
 * statusBars padding 之后只盖 56dp 栏、不盖状态栏条带（违反 Task 5 检查点①），按
 * nvTopBarBackground 链序前置换装（盖状态栏条带 + 栏全域），env 未接线域自动回退实色。
 */
@Composable
fun NvSelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    totalCount: Int = Int.MAX_VALUE,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(nvTopBarBackground())
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, stringResource(R.string.misc_sel_exit_multi), tint = NvColors.TextPrimary)
        }
        Text(
            pluralStringResource(R.plurals.misc_sel_selected_count, selectedCount, selectedCount),
            Modifier.weight(1f),
            style = NvTypography.TitleMedium,
            color = NvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,   // 2026-10-22 i18n：长计数文案截断出省略号
        )
        NvTextButton(stringResource(R.string.misc_sel_select_all), onSelectAll, enabled = selectedCount < totalCount)
        NvTextButton(stringResource(R.string.misc_sel_invert), onInvert)
    }
}

/** 底部栏动作项（NvSelectionBar 数据）：icon + 标签；enabled 仅控视觉，
 *  点击始终回调（置灰项由调用方 Toast 说明原因，如 NAS 源删除二期）。 */
data class NvSelectionAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 多选底栏（C5 §5.4 NvSelectionBar，高 64dp，Surface 底 + 上缘 1dp Divider）。
 */
@Composable
fun NvSelectionBar(
    actions: List<NvSelectionAction>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = NvColors.Divider)
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(NvColors.Surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                SelectionActionCell(action, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.SelectionActionCell(action: NvSelectionAction, modifier: Modifier) {
    val tint = if (action.enabled) NvColors.TextPrimary else NvColors.TextDisabled
    Column(
        modifier
            .clickable(onClick = action.onClick)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, action.label, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(NvSpacing.XS))
        Text(
            action.label,
            style = NvTypography.LabelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,   // 2026-10-22 i18n：长标签截断出省略号
        )
    }
}

/** 多选勾选圈（行首 24dp）：未选 Outline 描边圈 / 选中 Primary 填充勾（C5 §5.4） */
@Composable
fun NvSelectionCircle(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) NvColors.Primary else Color.Transparent)
            .border(
                2.dp,
                if (selected) NvColors.Primary else NvColors.TextSecondary,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            // 2026-09-27 去 AI 味：文本"✓"换标准图标（与 Chips/Sheets 选中态同源）
            Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}
