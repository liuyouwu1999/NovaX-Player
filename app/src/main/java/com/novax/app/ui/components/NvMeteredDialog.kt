package com.novax.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.novax.app.ui.theme.NvColors

/**
 * 流量播放确认框（2026-09-28 用户需求：仅 Wi-Fi 免确认播放；2026-10-11 三档化）。
 * 主播放器、RMVB 兜底页与投屏共用；继续 = 起播（本次消耗移动流量），取消 = 关闭播放页/放弃切集。
 * 继续 → 置位会话标志（「仅提醒一次」档本会话不再弹）；勾选「流量下不再提醒」并继续 →
 * 写「不提醒」档（KEY_METERED_REMIND_MODE=never，文档见
 * 2026-10-11 计费提醒会话设计），
 * 恢复入口在 设置→播放器→「流量播放提醒」三档段（业界惯例：弹窗勾选必须能在设置页找回，B站/咪咕同款）。
 */
@Composable
fun NvMeteredConfirmDialog(
    remindDisabledChecked: Boolean,
    onRemindDisabledCheckedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = NvColors.GlassFillStrong,   // 原 NvColors.SurfaceElevated——独立窗口填充式玻璃（spec §8，M3 单参限制见 decisions.md）
        shape = NvShape.Dialog,
        title = { Text("即将使用移动流量播放", style = NvTypography.TitleMedium) },
        text = {
            Column {
                Text("当前使用移动流量，继续播放将消耗数据流量；仅 Wi-Fi 下可免确认播放。")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = remindDisabledChecked,
                        onCheckedChange = onRemindDisabledCheckedChange,
                    )
                    Text("流量下不再提醒")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("继续播放", color = NvColors.Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消", color = NvColors.TextSecondary) }
        },
    )
}
