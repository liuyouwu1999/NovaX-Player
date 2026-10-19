package com.novax.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

// DESIGN-SYSTEM.md §2.8 空态
@Composable
fun NvEmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(NvSpacing.XXL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = NvColors.TextDisabled)
        Spacer(Modifier.height(NvSpacing.L))
        Text(title, style = NvTypography.TitleMedium)
        description?.let {
            Spacer(Modifier.height(NvSpacing.S))
            Text(
                it,
                style = NvTypography.BodyMedium,
                color = NvColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(NvSpacing.XL))
            NvPrimaryButton(actionText, onAction, Modifier.height(40.dp))
        }
    }
}

// DESIGN-SYSTEM.md §2.9 错误态 + 重试
// 2026-09-19：onRetry 可空——确定性失败（如设备无杜比视界解码器）重试没有意义，
// 调用方改用自己的行动按钮（如「软解内核播放」），此时传 null 不渲染重试按钮
@Composable
fun NvErrorState(
    title: String = "加载失败",
    message: String,
    retryText: String = "重试",
    retrying: Boolean = false,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        NvEmptyState(
            icon = Icons.Outlined.CloudOff,
            title = title,
            description = message,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(NvSpacing.M))
            NvPrimaryButton(
                text = retryText,
                onClick = onRetry,
                loading = retrying,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(40.dp),
            )
        }
    }
}

// DESIGN-SYSTEM.md §3 登记组件：内联提示条（info=蓝 / success=绿 / error=红）
// 2026-10-19 用户反馈：常驻提示必须可关——[onClose] 非空时右侧渲染 × 关闭钮
@Composable
fun NvInlineBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: NvBannerKind = NvBannerKind.Info,
    onClose: (() -> Unit)? = null,
) {
    val accent = when (kind) {
        NvBannerKind.Info -> NvColors.Secondary
        NvBannerKind.Success -> NvColors.Success
        NvBannerKind.Error -> NvColors.Error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(NvShape.Input)
            .background(NvColors.SurfaceElevated)
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(4.dp, 16.dp)
                .clip(NvShape.DividerHandle)
                .background(accent),
        )
        Text(
            text,
            style = NvTypography.LabelMedium,
            color = NvColors.TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = NvSpacing.S),
        )
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭提示",
                    modifier = Modifier.size(16.dp),
                    tint = NvColors.TextSecondary,
                )
            }
        }
    }
}

enum class NvBannerKind { Info, Success, Error }

// DESIGN-SYSTEM.md §3 登记组件：统一加载指示器（24dp 橙色）
@Composable
fun NvLoader(modifier: Modifier = Modifier) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier.size(24.dp),
        color = NvColors.Primary,
        strokeWidth = 2.dp,
    )
}
