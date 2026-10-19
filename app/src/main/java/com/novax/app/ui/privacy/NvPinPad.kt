package com.novax.app.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novax.app.R
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors

/**
 * 隐私锁共享输入件（隐私计划 Task 12）：自绘四格数字键盘（4×3+删除）与四格进度点。
 * 纯 Compose 按钮实现，**不用 TextField/系统输入法**——PIN 永不进输入法缓存与剪贴板。
 * 进度点容量固定 4 位（NvPinManager 的 `^\d{4}$` 契约），两面板（设置/解锁）共用。
 *
 * 布局自适应（横屏/折叠展开修复）：键位尺寸不再写死——列内 Row 用 weight 分宽、
 * 键格 fillMaxHeight 占满行高，数字恒居中于键格；行高按可用高度收缩（上限 56dp），
 * 高度不足时整体缩小、永不裁剪出屏。
 */

/** 单行键盘键位（1~9 + 0 + 删除；左下空位保持电话键盘惯例） */
private val KEYPAD_ROWS: List<List<Int?>> = listOf(
    listOf(1, 2, 3),
    listOf(4, 5, 6),
    listOf(7, 8, 9),
    listOf(null, 0, null),
)

/** 键盘目标行高（竖屏手感口径，空间不足时按可用高度均分收缩） */
private val KeypadTargetRowHeight = 56.dp

/** 键行触控下限：可用高度不足以让四行均分 ≥ 此值时，停止压缩改滚动兜底 */
private val KeypadMinRowHeight = 40.dp

/** 键盘列宽上限：3 键等分 + 2 个间距，保持原 76dp 键宽的观感（更窄窗口按 weight 收缩） */
private val KeypadMaxWidth = 256.dp

/** 键盘行间距 */
private val KeypadRowGap = 10.dp

/** 自绘数字键盘：按键时回调数字 / 删除；[enabled]=false（冷却中）整盘禁用。
 *  高度预算（显式，第 2 轮）：可用高扣 3×10dp 行距后四行均分——上限 56dp（竖屏手感）、
 *  下限 40dp（触控口径 40×64dp+）。Fold 展开横屏 320dp：并排分支给键盘内容高 296dp
 *  → (296-30)/4 = 66.5 → 取 56 → 键盘 4×56+3×10 = 254dp ≤ 296dp，11 键完整可见不滚动；
 *  窗口 214dp~560dp 按比例收缩（如 240dp → 行高 46.5）；<214dp 时 4×40dp 也放不下，
 *  固定 40dp 行高 + 垂直滚动兜底（初始停顶行）。 */
@Composable
internal fun NvPinKeypad(
    enabled: Boolean,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteLabel = stringResource(R.string.privacy_keypad_delete)
    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = KeypadMaxWidth)
            .fillMaxWidth(),
    ) {
        val naturalRow = (maxHeight - KeypadRowGap * 3) / 4
        if (naturalRow < KeypadMinRowHeight) {
            // 滚动兜底：40dp 下限也保不住（<214dp 窗口）——宁滚不裁
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(KeypadRowGap),
            ) {
                KEYPAD_ROWS.forEach { row ->
                    NvKeypadRow(row, KeypadMinRowHeight, enabled, onDigit, onDelete, deleteLabel)
                }
            }
        } else {
            // 无界高度（Infinity）时 naturalRow=Infinity，coerce 后回落 56dp 上限——竖排常规口径
            val rowHeight = naturalRow.coerceIn(0.dp, KeypadTargetRowHeight)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(KeypadRowGap),
            ) {
                KEYPAD_ROWS.forEach { row ->
                    NvKeypadRow(row, rowHeight, enabled, onDigit, onDelete, deleteLabel)
                }
            }
        }
    }
}

/** 单行键位：宽度 weight 等分、高度占满 [rowHeight]——数字由键格自身定中心，永不出界 */
@Composable
private fun NvKeypadRow(
    row: List<Int?>,
    rowHeight: Dp,
    enabled: Boolean,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    deleteLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        row.forEachIndexed { col, key ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    key != null -> NvKeypadButton(
                        label = key.toString(),
                        enabled = enabled,
                        onClick = { onDigit(key) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    col == 2 -> NvKeypadButton(
                        icon = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = deleteLabel,
                        enabled = enabled,
                        onClick = onDelete,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Unit   // 左下空位
                }
            }
        }
    }
}

/** 单键：尺寸完全由外层键格决定（weight×行高），数字/图标恒居中；圆底随格子缩放 */
@Composable
private fun NvKeypadButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        // 清掉默认 contentPadding——尺寸归键格管，内容由内部 Box 居中
        contentPadding = PaddingValues(0.dp),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(NvColors.GlassFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (label != null) {
                Text(
                    label,
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
                    color = if (enabled) NvColors.TextPrimary else NvColors.TextDisabled,
                    maxLines = 1,
                )
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) NvColors.TextPrimary else NvColors.TextDisabled,
                )
            }
        }
    }
}

/** 四格进度点：已输入位数亮 [filled]，其余空心 */
@Composable
internal fun NvPinDots(filled: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) { index ->
            val color = if (index < filled) NvColors.Primary else NvColors.Outline
            Box(
                Modifier
                    .size(14.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

/** 面板内提示行（统一字号：输入引导 / 错误 / 冷却共用） */
@Composable
internal fun NvPinHintText(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        style = NvTypography.BodyMedium,
        color = if (isError) NvColors.Error else NvColors.TextSecondary,
        textAlign = textAlign,
    )
}

/** 竖屏单列卡片宽度上限（沿用原 360dp 口径） */
private val PanelColumnMaxWidth = 360.dp

/** 矮宽（横屏/折叠展开）并排卡片宽度上限：键盘 256 + 信息列 + 间距与内边距 */
private val PanelSideBySideMaxWidth = 600.dp

/** 低于此窗口高度视为矮宽形态，信息列与键盘改左右并排（竖排需 ~560dp 才放得下全套） */
private val TallEnoughForColumn = 560.dp

/** 同上阈值的窗口像素口径（LocalConfiguration.screenHeightDp，与 TallEnoughForColumn 同值） */
private const val ShortColumnThresholdDp = 560

/** 并排分支卡片纵向内边距（比竖排 20dp 紧，极矮横屏高度预算见 NvPinKeypad 注释） */
private val SideBySideVPadding = 12.dp

/** 并排分支标题行与状态区间距（紧凑口径） */
private val SideBySideTitleGap = 8.dp

/**
 * PIN 面板共享骨架（设置/解锁/验证/改 PIN 四面板同款视觉语言）：
 * - 常规竖屏（窗口高 ≥ [TallEnoughForColumn]）：单列卡片（宽上限 360dp），状态在键盘上方；
 * - 矮宽横屏（如折叠屏展开 320dp 高）：信息列与键盘**左右并排**（卡片宽上限 600dp），
 *   键盘 4×56dp 行高完整放下、键格触控 ≥48dp，全部内容顺序排布互不重叠、绝不裁剪出屏。
 * 键位回调/校验/冷却等业务逻辑一律在调用方的 slot 里，本骨架只管排布。
 *
 * @param status 状态区（提示文本 + 进度点等，含调用方自排间距）
 * @param keypad 键盘区（矮宽下放不下单列时进并排分支；null = 无键盘形态，如密文损坏引导/指纹追问）
 * @param footer 键盘/状态下方的可选按钮区（忘记密码 / 指纹追问按钮组）
 */
@Composable
internal fun NvPinSheetPanel(
    title: String,
    onClose: () -> Unit,
    closeEnabled: Boolean,
    status: @Composable () -> Unit,
    keypad: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B10)),
        contentAlignment = Alignment.Center,
    ) {
        // 卡片宽度：并排分支自己撑到 600dp 上限，竖排分支由内部 Column 的 widthIn(360) 收口。
        // （第 2 轮的卡片 heightIn(screenHeightDp) 封顶在第 3 轮移除：并排分支的键盘列预算
        // 已显式按 screenHeightDp−2×padding 收口（见下方 keypadBudget），卡片级封顶会在叠加
        // 安全区内边距后二次扣减内容高度。）
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = PanelSideBySideMaxWidth)
                .background(NvColors.SurfaceElevated, RoundedCornerShape(20.dp)),
        ) {
            // 形态判据：优先窗口真实高度（LocalConfiguration，宿主约束骗不了）；
            // 约束有界时再取「或」——任一判矮即并排（maxHeight 为 Infinity 时该比较恒 false）
            val shortByWindow = configuration.screenHeightDp < ShortColumnThresholdDp
            val shortByConstraints = maxHeight < TallEnoughForColumn
            if (keypad != null && (shortByWindow || shortByConstraints)) {
                // 键盘列显式预算（第 3 轮，约束无关）：面板全高(screenHeightDp，系统口径已排除
                // 状态栏/导航条) − 卡片纵向 padding×2。并排模式下标题/提示/关闭钮占左列
                // （真机 dump 佐证：标题 x378-1018、关闭钮 x1029-1134，键盘列 x1197+），
                // 键盘列额外开销为 0——预算即 keypadBudget，宿主约束再虚高也盖不住它。
                val keypadBudget = (configuration.screenHeightDp - 2 * 12).dp
                // 矮宽横屏：信息列 | 键盘 左右并排（键盘定宽 256dp，信息列吃剩余宽度）。
                // safeDrawingPadding：卡片内容避开状态栏/导航条/IME——真机 Fold 横屏第 4 排键
                // 曾被应用底部导航条压住（uiautomator 树里被遮挡的 '0' 节点遭可见性剪除的实锤）。
                // 仅并排分支加，竖排分支零回退。
                Row(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = SideBySideVPadding),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NvPanelTitleRow(title, onClose, closeEnabled)
                        Spacer(Modifier.height(SideBySideTitleGap))
                        status()
                        if (footer != null) {
                            Spacer(Modifier.height(8.dp))
                            footer()
                        }
                    }
                    Box(Modifier.heightIn(max = keypadBudget)) {
                        keypad()
                    }
                }
            } else {
                // 常规竖屏（或无键盘形态）：单列卡片，纵向节奏与旧版一致
                Column(
                    modifier = Modifier
                        .widthIn(max = PanelColumnMaxWidth)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NvPanelTitleRow(title, onClose, closeEnabled)
                    Spacer(Modifier.height(12.dp))
                    status()
                    if (keypad != null) {
                        Spacer(Modifier.height(20.dp))
                        keypad()
                    }
                    if (footer != null) {
                        Spacer(Modifier.height(8.dp))
                        footer()
                    }
                }
            }
        }
    }
}

@Composable
private fun NvPanelTitleRow(title: String, onClose: () -> Unit, closeEnabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = NvTypography.TitleMedium,
            color = NvColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, enabled = closeEnabled) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.privacy_unlock_close))
        }
    }
}
