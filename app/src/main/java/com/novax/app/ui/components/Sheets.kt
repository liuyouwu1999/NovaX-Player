package com.novax.app.ui.components

import com.novax.app.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novax.app.ui.glass.NvGlassLensTier
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.theme.NvColors

// DESIGN-SYSTEM.md §2.10 确认对话框（含 danger 变体）
@Composable
fun NvConfirmDialog(
    title: String,
    message: String,
    confirmText: String = stringResource(R.string.misc_confirm),
    dismissText: String = stringResource(R.string.misc_cancel),
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NvColors.GlassFillStrong,   // 原 NvColors.SurfaceElevated——独立窗口填充式玻璃（spec §8，M3 单参限制见 decisions.md）
        shape = NvShape.Dialog,
        title = { Text(title, style = NvTypography.TitleMedium) },
        text = { Text(message, style = NvTypography.BodyMedium, color = NvColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (danger) NvColors.Error else NvColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText, color = NvColors.TextSecondary) }
        },
    )
}

// DESIGN-SYSTEM.md §2.11 底部弹层（模态，带 Scrim）
// 返工⑮（2026-10-16）：heavyGlass 档摘除——真机实证 ModalBottomSheet 独立窗口取不到主界面
// 内容的模糊（跨窗口无玻璃，heavyGlass 静默退化为 GlassFillStrong 实底=用户截图「实心白板」），
// 需要真玻璃的弹层一律改原地覆盖层（遥控面板 NvCastRemotePanel 已迁，decisions §25）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NvBottomSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NvColors.GlassFillStrong,   // 独立窗口填充式玻璃（spec §8，M3 单参限制见 decisions.md）
        shape = NvShape.BottomSheet,
        dragHandle = {
            Box(
                Modifier
                    .padding(8.dp)
                    .size(32.dp, 4.dp)
                    .clip(NvShape.DividerHandle)
                    .background(NvColors.Divider),
            )
        },
    ) {
        // 2026-09-17 修复：内容列加纵向滚动——字幕面板加入编码切换后行数超屏，
        // 原实现超出部分被 ModalBottomSheet 直接裁剪（编码行不可达）
        // 2026-10-10 S5:右缘滚动条与居中弹窗同款(内容超高时常驻可见,见 [nvVerticalScrollbar])
        val scroll = rememberScrollState()
        Column(
            Modifier
                .padding(bottom = NvSpacing.XXL)
                // 2026-10-10 终审 I-1:滚动条必须在 verticalScroll 之外(先挂先画),
                // drawBehind 才能测到视口高度且不随内容平移——顺序反了 thumb 退化为全高静止线
                .nvVerticalScrollbar(scroll)
                .verticalScroll(scroll),
            content = content,
        )
    }
}

/**
 * 播放器专用居中弹窗（2026-10-08 用户需求「弹出的窗口铺满整个屏幕…做成弹窗式的，有垂直滚动条」）：
 * ModalBottomSheet 横屏下内容一多就撑满整屏高度（挡画面+难操作）。in-place 覆盖层实现
 * （在播放器根 Box 内调用）——不另开 Dialog 窗口，沉浸式 flag/刘海 inset 沿用播放页现状：
 * 半透明遮罩（点击空白关闭）+ 居中面板（竖屏 92% 宽、横屏封顶 460dp；高 ≤85% 屏）+
 * 标题行关闭钮 + 内容垂直滚动（超长内容如字幕编码行可达底，2026-09-17 裁剪 bug 不复发）。
 * 返回键先关弹窗（ModalBottomSheet 同语义）；RMVB 页组合序更靠后的 BackHandler 会接管并
 * 优先关面板（各调用点保证），此处为主播放器路径兜底。
 */
@Composable
fun NvPlayerPopup(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        )
        Column(
            Modifier
                .align(Alignment.Center)
                // widthIn 必须放在 fillMaxWidth 之前（2026-10-08 e2e 实证：fillMaxWidth 在前会把
                // 宽度定死为父宽 92%，后置 widthIn 收到固定约束无法收窄，横屏 460dp 封顶失效）
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = maxHeight * 0.85f)
                .clip(NvShape.Dialog)
                .then(popupGlassBackground())
                // 面板本体吃掉点击（无 ripple），不漏到遮罩误关
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = NvTypography.TitleMedium,
                    color = NvColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.misc_close), tint = NvColors.TextSecondary)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = NvColors.Divider)
            // 2026-10-10 S5:右缘滚动条——倍速/清晰度等面板内容超高时可发现性(用户点名弹窗滚动)
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .padding(bottom = NvSpacing.XXL)
                    // 2026-10-10 终审 I-1:同上——滚动条在 verticalScroll 之外(先挂先画)
                    .nvVerticalScrollbar(scroll)
                    .verticalScroll(scroll),
                content = content,
            )
        }
    }
}

/**
 * NvPlayerPopup 面板背景（Task 10，spec §4；返工⑤ 2026-10-15 用户第三项反馈重磨砂化）：
 * 用户真机实证「清玻璃面板文字与画面混作一团」——播放页弹窗承载的是**文字清单**（视频信息/
 * 倍速/清晰度/字幕/选集/音轨/投屏），可读性第一，全量换重磨砂配方：
 * blur 回高档 Fixed 28dp + GlassFillStrong 强填充（70% 暗面板底），保玻璃形状/圆角与边缘质感
 * （nvGlassSurface：clip 收口 + GlassSheen/GlassInnerShadow 不对称光效 + 1dp hairline 描边）；
 * 边缘透镜关档（NvGlassLensTier.None——透镜边带会搅动面板边缘下滚动文字行）。
 * env=null 未接线域回退 GlassFillStrong 实底不变（与 NvGlassPanel 填充常量一致）。
 * 主页面组件不经过本弹窗不受影响；主播放器与 RMVB 页 8 个弹窗一次全部生效，零调用方改动。
 * 返工⑭开放 [shape] 参数并改 internal；返工⑮（2026-10-16）起主消费点从 NvBottomSheet
 * heavyGlass 档（已摘除——独立窗口跨窗无玻璃，decisions §25）改为原地覆盖层面板
 * NvCastRemotePanel（NvShape.BottomSheet 形状），配方本体逐参数不变。
 */
@Composable
internal fun popupGlassBackground(shape: androidx.compose.ui.graphics.Shape = NvShape.Dialog): Modifier {
    val env = com.novax.app.ui.glass.LocalNvGlassEnv.current
    return if (env != null) {
        Modifier.nvGlassSurface(
            NvColors.GlassFillStrong,
            shape,
            NvGlassLensTier.None,
            blurRadius = 28.dp,
            fillOverlay = true,
        )
    } else {
        Modifier.background(NvColors.GlassFillStrong, shape)
    }
}

// DESIGN-SYSTEM.md §2.11 标准弹层列表项（选集/音轨/倍速共用）
// 2026-09-28 审计 C8：可选 onLongClick——投屏设备项长按显示控制地址供用户辨识
// 返工⑮（2026-10-16）：可选 enabled——投屏设备面板连接中整体禁点（防连点），既有调用
// 缺省 true 逐像素不变
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NvSheetListItem(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    // onClick 必须是末参：既有调用全部用尾随 lambda（会绑定最后一个参数）
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NvSpacing.L),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = NvTypography.BodyMedium,
            color = when {
                selected -> NvColors.Primary
                !enabled -> NvColors.TextDisabled
                else -> NvColors.TextPrimary
            },
        )
        if (selected) {
            Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = NvColors.Primary)
        }
    }
}
