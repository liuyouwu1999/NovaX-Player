package com.novax.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

// DESIGN-SYSTEM.md §2.1 主要按钮
@Composable
fun NvPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(NvMotion.DurationFast, easing = NvMotion.CurveDecelerate),
        label = "primaryPressScale",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled && !loading,
        shape = NvShape.Button,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = NvColors.Primary,
            contentColor = Color.White,
            disabledContainerColor = NvColors.SurfaceElevated,
            disabledContentColor = NvColors.TextDisabled,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text, style = NvTypography.BodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// DESIGN-SYSTEM.md §2.2 次要按钮
// 返工⑮（2026-10-16 用户真机「断开投屏裸红字 vs 拉回本机描边胶囊不一致」）：可选 danger 档——
// 红字红描边的描边胶囊（同 48dp/药丸形状/字号），缺省 false 全工程既有调用逐像素不变
@Composable
fun NvSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !loading,
        shape = NvShape.Button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = if (danger) NvColors.Error else NvColors.TextPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = NvColors.TextDisabled,
        ),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> NvColors.TextDisabled.copy(alpha = 0.3f)
                danger -> NvColors.Error
                else -> NvColors.Outline
            },
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text, style = NvTypography.BodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// DESIGN-SYSTEM.md §2.3 文字按钮
@Composable
fun NvTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = NvColors.Primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = NvShape.Button,
        colors = ButtonDefaults.textButtonColors(
            contentColor = tint,
            disabledContentColor = NvColors.TextDisabled,
        ),
    ) {
        Text(text, style = NvTypography.BodyMedium)
    }
}
