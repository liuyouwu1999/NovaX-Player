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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvTypography // NvTypography 在 ui.components（Motion.kt）
import com.novax.app.ui.theme.NvColors

/**
 * 首启隐私弹窗（spec §1.2）：双按钮（同意/不同意）均必须存在且可用；不可点外部或返回键关闭
 * （onDismissRequest 空）；正文摘要 + 全文入口（认定方法「首次运行弹窗提示阅读」）。
 * 用户可见文案全部为四语字符串资源（policy_consent_*）。
 */
@Composable
fun NvPolicyConsentDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onViewPolicy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.policy_consent_title), style = NvTypography.TitleMedium, color = NvColors.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.policy_consent_body),
                    style = NvTypography.BodyMedium, color = NvColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.policy_consent_view_full),
                    style = NvTypography.LabelMedium, color = NvColors.Primary,
                    modifier = Modifier.clickable { onViewPolicy() },
                )
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text(stringResource(R.string.policy_consent_agree)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.policy_consent_decline)) } },
    )
}
