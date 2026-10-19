package com.novax.app.ui.policy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novax.app.ui.components.NvTypography // NvTypography 在 ui.components（Motion.kt）
import com.novax.app.ui.theme.NvColors

/**
 * 首启隐私弹窗（spec §1.2）：双按钮（同意/不同意）均必须存在且可用；不可点外部或返回键关闭
 * （onDismissRequest 空）；正文摘要 + 全文入口（认定方法「首次运行弹窗提示阅读」）。
 */
@Composable
fun NvPolicyConsentDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onViewPolicy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("隐私政策与用户协议", style = NvTypography.TitleMedium, color = NvColors.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "欢迎使用 NovaX。开发者不通过本应用收集任何个人信息：无统计/广告/上报组件，观看历史与 NAS 凭据等全部数据仅保存在您的设备上。" +
                        "使用刮削或网络源功能时，请求由您的设备直接发往您配置的服务器（详见全文）。",
                    style = NvTypography.BodyMedium, color = NvColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                // 2026-10-19 用户需求：首启弹窗重点突出「完全免费」（反诈口径）
                Text(
                    "NovaX 完全免费——任何形式的付费下载或销售均属诈骗。",
                    style = NvTypography.LabelLarge.copy(fontWeight = FontWeight.Bold),
                    color = NvColors.Primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "查看《隐私政策》与《用户协议》全文",
                    style = NvTypography.LabelMedium, color = NvColors.Primary,
                    modifier = Modifier.clickable { onViewPolicy() },
                )
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意并继续") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("不同意") } },
    )
}
