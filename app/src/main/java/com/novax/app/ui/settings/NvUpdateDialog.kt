package com.novax.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.data.update.NvChangelogExtractor
import com.novax.app.data.update.UpdateCheckResult
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvSecondaryButton
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors

/**
 * 更新弹窗组（spec §2.4「发现新版弹窗」：手动/自动共用一组件；宿主 = MainActivity 的 DialogHost）。
 * 视觉沿用 NvConfirmDialog 既有约定（GlassFillStrong 容器 + NvShape.Dialog 圆角）。
 *
 * 2026-10-26 用户反馈返工（四项）：
 * - changelog 逐行 1、2、3 编号（此前无分点难读）；
 * - 按钮全部可辨识——主动作 NvPrimaryButton 填充、次动作 NvSecondaryButton 描边胶囊、
 *   链接类下划线小字（此前正文里三个无边框 TextButton 看不出可点）；
 * - 新增「忽略此版本」（方案 1）：记录 tag 后自动检查对同版本静默，新版本出现恢复提醒；
 * - 文字清理见 NvChangelogExtractor（去 H1/H2/加粗星号，弹窗不再透出 Markdown 残迹）。
 * 动作全部置于 text 列尾部（内容型弹窗惯例），AlertDialog 按钮槽传空防双动作区。
 */
@Composable
internal fun NvUpdateAvailableDialog(
    check: UpdateCheckResult.Available,
    currentVersion: String,
    onInstall: () -> Unit,
    onBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onViewNotes: () -> Unit,
    onIgnore: () -> Unit,
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
                // 2026-10-26：逐条 1、2、3 编号（用户反馈无分点不好读）
                lines.forEachIndexed { i, line ->
                    Text(
                        "${i + 1}. $line",
                        style = NvTypography.BodyMedium,
                        color = NvColors.TextPrimary,
                    )
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
                // 链接类出口：下划线小字（明显可点但不与动作钮抢层级）；浏览器下载为
                // spec §2.3 国内可达性出口——canInstall=false 时已提升为主按钮，不重复出
                LinkLine(stringResource(R.string.update_view_full_notes), onViewNotes)
                LinkLine(stringResource(R.string.update_btn_copy_link), onCopyLink)
                if (check.canInstall) {
                    LinkLine(stringResource(R.string.update_btn_browser), onBrowser)
                }
                Spacer(Modifier.height(4.dp))
                // 主动作：可校验安装 → 应用内下载安装；否则浏览器下载提升为主出口
                if (check.canInstall) {
                    NvPrimaryButton(
                        text = stringResource(R.string.update_btn_install),
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    NvPrimaryButton(
                        text = stringResource(R.string.update_btn_browser),
                        onClick = onBrowser,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 次动作：忽略此版本 / 以后再说（描边胶囊，边界清晰）
                Row(horizontalArrangement = Arrangement.spacedBy(NvSpacing.S)) {
                    NvSecondaryButton(
                        text = stringResource(R.string.update_btn_ignore_version),
                        onClick = onIgnore,
                        modifier = Modifier.weight(1f),
                    )
                    NvSecondaryButton(
                        text = stringResource(R.string.update_btn_later),
                        onClick = onLater,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/** 链接行：下划线 + 主色小字（2026-10-26 用户反馈「按钮没边界看不出可点」——链接类用
 *  网页惯例下划线标识，动作类走描边/填充按钮，两类都可辨识） */
@Composable
private fun LinkLine(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Text(
            text,
            style = NvTypography.LabelMedium.copy(textDecoration = TextDecoration.Underline),
            color = NvColors.Primary,
        )
    }
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
