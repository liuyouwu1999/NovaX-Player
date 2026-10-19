package com.novax.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.data.update.NvChangelogExtractor
import com.novax.app.data.update.UpdateCheckResult
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors

/**
 * 更新弹窗组（spec §2.4「发现新版弹窗」：手动/自动共用一组件；宿主 = MainActivity 的 DialogHost）。
 * 视觉沿用 NvConfirmDialog 既有约定（GlassFillStrong 容器 + NvShape.Dialog 圆角）。
 */

@Composable
internal fun NvUpdateAvailableDialog(
    check: UpdateCheckResult.Available,
    currentVersion: String,
    onInstall: () -> Unit,
    onBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onViewNotes: () -> Unit,
    onLater: () -> Unit,
) {
    // changelog 逐行展示（repo 已按契约折叠为前 12 行）；「有后续被折叠」用 limit+1 探测——
    // 恰好整 12 行时不得误标「已折叠」
    val lines = check.changelog
    val hasMore = NvChangelogExtractor.extract(check.release.body, limit = lines.size + 1).size > lines.size
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = NvColors.GlassFillStrong,
        shape = NvShape.Dialog,
        title = {
            Text(
                stringResource(R.string.update_dialog_new_version, check.release.tagName),
                style = NvTypography.TitleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.update_row_current, currentVersion),
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                )
                lines.forEach { line ->
                    Text(line, style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                }
                if (hasMore) {
                    Text(
                        stringResource(R.string.update_changelog_truncated),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                    )
                }
                // spec §2.5 防线②（Review Focus 1）：canInstall=false（资产缺位/SUMS 不可得）
                // 不渲染「立即下载安装」，正文下加灰字说明——宁可不装，不做未校验安装
                if (!check.canInstall) {
                    Text(
                        stringResource(R.string.update_cannot_verify),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                    )
                }
                TextButton(onClick = onViewNotes, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        stringResource(R.string.update_view_full_notes),
                        style = NvTypography.LabelMedium,
                        color = NvColors.Primary,
                    )
                }
                TextButton(onClick = onCopyLink, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        stringResource(R.string.update_btn_copy_link),
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            if (check.canInstall) {
                TextButton(onClick = onInstall) {
                    Text(
                        stringResource(R.string.update_btn_install),
                        color = NvColors.Primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                TextButton(onClick = onBrowser) { Text(stringResource(R.string.update_btn_browser), color = NvColors.Primary) }
            }
        },
        dismissButton = {
            Row {
                if (check.canInstall) {
                    TextButton(onClick = onBrowser) {
                        Text(stringResource(R.string.update_btn_browser), color = NvColors.TextSecondary)
                    }
                }
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.update_btn_later), color = NvColors.TextSecondary)
                }
            }
        },
    )
}

/** 「安装未知应用」授权说明弹窗（spec §2.5：去授权 → 返回 App onResume 复查 → 就绪即续装）。 */
@Composable
internal fun NvUpdatePermissionDialog(
    onGrant: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = NvColors.GlassFillStrong,
        shape = NvShape.Dialog,
        title = {
            Text(stringResource(R.string.update_need_perm_title), style = NvTypography.TitleMedium)
        },
        text = {
            Text(
                stringResource(R.string.update_need_perm_body),
                style = NvTypography.BodyMedium,
                color = NvColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(
                    stringResource(R.string.update_btn_grant),
                    color = NvColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_btn_later), color = NvColors.TextSecondary)
            }
        },
    )
}
