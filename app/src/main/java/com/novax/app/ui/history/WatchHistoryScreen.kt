package com.novax.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novax.app.R
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.nvBarCollapse
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.components.NvTypography.LabelSmall
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.home.NvPlaybackLauncher
import com.novax.app.ui.theme.NvColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 观看历史页（2026-09-27 用户需求）：条目 = 标题 + 所在文件夹 + 进度 + 观看时间。
 * 点 = 从上次进度续播；长按 = 打开所在文件夹 / 删除该条；顶栏「清空」= 全清（带确认）。
 */
@Composable
fun WatchHistoryScreen(
    onBack: () -> Unit,
    onOpenFolder: (com.novax.app.ui.files.NvFilesDeepLink.Target) -> Unit,
) {
    val vm: WatchHistoryViewModel = viewModel()
    val rows by vm.rows.collectAsState()
    val context = LocalContext.current
    var askClear by remember { mutableStateOf(false) }

    // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（IME 打开→0dp）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // Liquid Glass（decisions.md §4.5）：source 挂列表滚动容器，NvTopBar 悬浮做兄弟（页面翻转配方）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：顶栏静止态隐形（列表滚过首项渐显；空态列表未滚动=隐形，标题压底）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    // rework3：enterAlways 顶栏收起（主 Tab 域列表页）
    val barCollapsed by com.novax.app.ui.components.rememberNvBarCollapseState(listState)
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        if (rows.isEmpty()) {
            // weight 是 ColumnScope 修饰符，进 Box 后改 fillMaxSize + top 避让悬浮顶栏
            NvEmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.hist_empty_title),
                description = stringResource(R.string.hist_empty_desc),
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        } else {
            LazyColumn(
                // background 在 source 之后：录制须含背景像素，否则栏后无源可采（§4.5a）
                Modifier.fillMaxSize().nvGlassSource(glassEnv).background(NvColors.Background),
                state = listState,
                contentPadding = PaddingValues(top = topInset, bottom = glassBottom),
            ) {
                items(rows, key = { it.entry.id }) { row ->
                    HistoryRow(
                        row = row,
                        onClick = { NvPlaybackLauncher.resumeFrom(context, row.entry) },
                        onOpenFolder = onOpenFolder,
                        onDelete = { vm.delete(row.entry.id) },
                    )
                    HorizontalDivider(color = NvColors.Divider)
                }
            }
        }
        NvTopBar(
            title = stringResource(R.string.hist_title),
            onBack = onBack,
            modifier = Modifier.nvBarCollapse(barCollapsed),   // rework3：下滑收起/上滑出现
            scrolled = scrolled,   // 观感返工①
            actions = listOf {
                if (rows.isNotEmpty()) {
                    NvTextButton(stringResource(R.string.hist_clear), onClick = { askClear = true }, tint = NvColors.TextSecondary)
                }
            },
        )
    }

    if (askClear) {
        NvConfirmDialog(
            title = stringResource(R.string.hist_clear_title),
            message = stringResource(R.string.hist_clear_msg),
            confirmText = stringResource(R.string.hist_clear),
            danger = true,
            onConfirm = {
                askClear = false
                vm.clearAll()
            },
            onDismiss = { askClear = false },
        )
    }
}

/** 相对时间文案（本小时内精确到分钟，当天内小时，当月内天，更早落日期）；i18n 计数走 plurals（spec §1.3） */
@Composable
private fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - epochMs) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.hist_time_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.hist_time_min_ago, minutes, minutes)
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            pluralStringResource(R.plurals.hist_time_hour_ago, hours, hours)
        }
        minutes < 60 * 24 * 30 -> {
            val days = minutes / (60 * 24)
            pluralStringResource(R.plurals.hist_time_day_ago, days, days)
        }
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    row: HistoryRowUi,
    onClick: () -> Unit,
    onOpenFolder: (com.novax.app.ui.files.NvFilesDeepLink.Target) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val e = row.entry
    val watchedFraction = if (e.durationMs > 0) {
        (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val finished = e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95f

    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.title,
                        Modifier.weight(1f, fill = false),
                        style = NvTypography.BodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = NvColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.episodeTag?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = LabelSmall, color = NvColors.Primary)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    row.folderLabel,
                    style = LabelSmall,
                    color = NvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                if (e.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = watchedFraction,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = NvColors.Primary,
                        trackColor = NvColors.Track,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    relativeTime(e.watchedAt),
                    style = LabelSmall,
                    color = NvColors.TextDisabled,
                )
                if (finished) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.hist_finished), style = LabelSmall, color = NvColors.TextSecondary)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (row.canOpenFolder) {
                row.folderTarget?.let { target ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hist_open_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                        onClick = {
                            menuOpen = false
                            onOpenFolder(target)
                        },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.hist_delete_item), color = NvColors.Error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
