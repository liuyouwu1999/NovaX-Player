package com.novax.app.ui.components

import com.novax.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.theme.NvColors

/** 数字组标签：条上显示"数"（气泡显示"数字"），涵盖全部 0-9 开头的条目 */
const val SECTION_LABEL_DIGIT = "数"

/** 符号/未知组标签（非字母非数字开头） */
const val SECTION_LABEL_HASH = "#"

/** 侧条分组顺序与列表全局序一致（2026-10-16 用户裁定 A→Z→0-9→#）：A-Z 最上（字母开头排最前，
 *  中文经拼音归入），数字组（0-9 开头）其后，符号/未知 # 在最后 */
val NV_ALPHABET_SIDEBAR_LETTERS: List<String> =
    ('A'..'Z').map { it.toString() } + listOf(SECTION_LABEL_DIGIT, SECTION_LABEL_HASH)

// ── 返工⑩-2 侧条重做（2026-10-16 用户裁定，参照 AOSP 通讯录式 fast-scroller 观感）──
// 用户不满：旧版 fillMaxHeight 撑满整屏（"侧边太长了不好看"）；选中仅 12→13sp 放大不可感。

/** 每字母行高（= 触摸命中区）：22dp——收拢观感与 ≥20dp 命中下限的折中（主流 18-24dp 带内） */
val NV_SIDEBAR_ROW_HEIGHT = 22.dp

/** 条体上下呼吸边（各 16dp）：收拢后不顶筛选栏、不压底栏 */
val NV_SIDEBAR_VERTICAL_MARGIN = 16.dp

/** 未选中字号：9sp 小字（主流 8-10sp 带内），克制的次要色 */
val NV_SIDEBAR_FONT_SIZE = 9.sp

/** 选中放大倍数：2.0x（9sp→18sp 有效字号，主流 1.8-2.2x 带内），配主色高亮；松手回弹 */
const val NV_SIDEBAR_ACTIVE_SCALE = 2f

/**
 * 媒体库字母快速滚动侧条（DESIGN-SYSTEM 组件：NvAlphabetSidebar）。
 *
 * - A-Z + 数 + # 细条 28dp 宽、半透明底；**总高收拢垂直居中**（返工⑩-2）：
 *   `min(字母数×22dp 行高, 可用高−上下 16dp 呼吸边)`——高屏恰好收拢（914dp 屏 28 行 616dp，
 *   上下各余 ~150dp），矮屏被可用高裁剪时 weight 槽位均分收缩、仍居中不溢出
 *   （回归教训：SpaceEvenly + 28 组在横屏矮屏上超出可用高度，W 之后的组被推出屏外；
 *   weight 均分 + letterAt 按实测条高换算，任何高度下触摸映射恒精确）；
 * - 默认 9sp 小字；**触摸/拖动中当前字母 2.0x 放大 + 主色高亮**（graphicsLayer 变换层，
 *   不改布局零跳动），其余保持小字，松手 150ms 回弹；
 * - 按下/拖动同时显示中央大气泡；**拖动全程实时跳转**（分组一变即回调，MX Player 式），
 *   松手兜底跳最近有效分组；气泡松手 300ms 淡出；
 * - 有内容字母次要色、无内容降透明（TextDisabled）——两组可辨（主流同款）；
 * - 整段手势在侧条内消费，与网格嵌套滚动零冲突；条外触摸穿透到网格。
 *
 * @param indexMap 分组（"A".."Z"/"数"/"#"）→ 列表首项下标（LibraryUiState.initialIndexMap）
 * @param onLetterSelected 拖动实时回调，调用方 scrollToItem 到该分组第一项
 */
@Composable
fun NvAlphabetSidebar(
    indexMap: Map<String, Int>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    letters: List<String> = NV_ALPHABET_SIDEBAR_LETTERS,
) {
    var activeSection by remember { mutableStateOf<String?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val bubbleAlpha = remember { Animatable(0f) }

    // BUG-C-R11-01（2026-10-16 真机拖动中卡死返工）：indexMap/onLetterSelected 经
    // rememberUpdatedState 读取最新值，pointerInput 只以 letters 为 key——旧实现把
    // onLetterSelected（ReadyContent 每次重组都新建实例，LibraryUiState 含 List 字段
    // 非稳定 → ReadyContent 不可跳过）直接当 key，拖动中任何重组（收起阈值翻转/缩略图
    // 装载/扫描进度）都会重启 pointerInput 取消手势协程；配合下述 finally 统一复位，
    // 「气泡不消失 + 字母保持放大 + 列表停跳」的真机截图态被双保险封死。
    val indexMapState by rememberUpdatedState(indexMap)
    val onSelectedState by rememberUpdatedState(onLetterSelected)

    // 按下即时显示气泡；松手 300ms 淡出
    LaunchedEffect(activeSection, pressed) {
        when {
            activeSection != null && pressed -> bubbleAlpha.snapTo(1f)
            else -> bubbleAlpha.animateTo(0f, tween(durationMillis = NvMotion.DurationNormal + 100))
        }
    }

    BoxWithConstraints(modifier) {   // 覆盖层本体无 pointerInput：条外触摸穿透网格
        // 收拢总高：min(28×22dp, 可用高−32dp)；负数兜底（极端矮屏）钳 0，letterAt 另有 ≤0 守卫
        val barHeight = (NV_SIDEBAR_ROW_HEIGHT * letters.size)
            .coerceAtMost(maxHeight - NV_SIDEBAR_VERTICAL_MARGIN * 2)
            .coerceAtLeast(0.dp)

        // ── 中央大气泡 ──
        activeSection?.let { section ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = bubbleAlpha.value }
                    .size(96.dp)
                    .clip(NvShape.Dialog)
                    .background(NvColors.SurfaceElevated.copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // i18n Task 9：「数」为内部分组键（协议值，保留）；气泡展示名资源化
                    if (section == SECTION_LABEL_DIGIT) stringResource(R.string.misc_section_digit) else section,
                    color = NvColors.Primary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ── 收拢细条：整条高度触摸区，整段手势消费；CenterEnd 对齐即列表区内垂直居中 ──
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(barHeight)
                .width(28.dp)
                .background(NvColors.Background.copy(alpha = 0.35f))
                // BUG-C-R8-03：手势内经 indexMapState/onSelectedState 读最新值——常量 key 下
                // 列表原地刷新后跳转仍用新映射（旧语义保留，告别 stale 闭包）
                .pointerInput(letters) {
                    awaitEachGesture {
                        // BUG-C-R11-01：try/finally 统一复位——up/cancel/异常/协程被重组取消
                        // 任何退出路径都必然走到，pressed 悬挂 true 的「气泡长存+选中不回弹」
                        // 根除（协程取消时 finally 仍执行，且赋值非挂起操作不会被再次打断）
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            pressed = true
                            var lastJumped: String? = null
                            val indexMap = indexMapState
                            // 拖动全程实时跳转：分组一变就 scrollToItem（lastJumped 去重防抖）
                            fun jump(section: String) {
                                if (section != lastJumped && indexMap.containsKey(section)) {
                                    lastJumped = section
                                    onSelectedState(section)
                                }
                            }
                            activeSection = letterAt(down.position.y, size.height.toFloat(), letters)
                                .also(::jump)
                            while (true) {
                                val event = awaitPointerEvent()
                                // 注：Compose ui 1.9 无 PointerEventType.Cancel——窗焦点丢失/
                                // 父层拦截派发的 ACTION_CANCEL 以「changes.pressed=false」形态
                                // 走下方 up 分支退出（模拟器 HOME/通知帘压测实证复位）。
                                // 只认按下时的那根指针：它从事件流消失（系统侧移除/流断裂，
                                // 事件里查无此 id）即 break 收口，绝不悬挂 await——旧 fallback
                                // `?: firstOrNull()` 会认领陌生指针（多点触控下把别处按下的
                                // 手指当拖动指针继续消费，表现为列表吞事件冻住）
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) {
                                    change.consume()   // up 也消费，整段手势闭环
                                    break
                                }
                                val current = letterAt(change.position.y, size.height.toFloat(), letters)
                                activeSection = current
                                jump(current)   // 委托属性无法 smart cast，经局部变量传递
                                change.consume()   // 拖动全程消费：网格不联动滚动
                            }
                            // 松手兜底：全程按在无内容分组上时，跳到最近的有效分组
                            val section = activeSection
                            if (lastJumped == null && section != null && letters.isNotEmpty()) {
                                nearestSection(section, letters, indexMap)?.let(::jump)
                            }
                        } finally {
                            pressed = false   // 气泡经 LaunchedEffect 300ms 淡出，回弹经 150ms scale
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { section ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val selectable = indexMap.containsKey(section)
                    val isActive = pressed && section == activeSection
                    // 选中放大走 graphicsLayer 变换层（不改布局零跳动），150ms 平滑跟进/回弹
                    val scale by animateFloatAsState(
                        targetValue = if (isActive) NV_SIDEBAR_ACTIVE_SCALE else 1f,
                        animationSpec = tween(NvMotion.DurationFast),
                        label = "sidebarLetterScale",
                    )
                    Text(
                        // i18n Task 9 fix：「数」为分组键，但侧条本体是显示点——
                        // 与气泡同款判断，回退本地化名（en/ko/ja 不再见汉字）
                        if (section == SECTION_LABEL_DIGIT) stringResource(R.string.misc_section_digit) else section,
                        color = when {
                            isActive -> NvColors.Primary
                            selectable -> NvColors.TextSecondary
                            else -> NvColors.TextDisabled
                        },
                        fontSize = NV_SIDEBAR_FONT_SIZE,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

/** 触摸 y（组件内坐标）→ 分组：整条高度均分 |letters| 格，越界夹取（条高实收，收拢/裁剪通用） */
private fun letterAt(y: Float, barHeightPx: Float, letters: List<String>): String {
    if (barHeightPx <= 0f) return letters.first()
    val index = ((y / barHeightPx) * letters.size).toInt().coerceIn(0, letters.size - 1)
    return letters[index]
}

/** 距 [section] 最近（按条上顺序距离）且有内容的分组；全空返回 null */
private fun nearestSection(section: String, letters: List<String>, indexMap: Map<String, Int>): String? {
    val origin = letters.indexOf(section).takeIf { it >= 0 } ?: return null
    var best: String? = null
    var bestDist = Int.MAX_VALUE
    letters.forEachIndexed { i, l ->
        if (indexMap.containsKey(l)) {
            val d = kotlin.math.abs(i - origin)
            if (d < bestDist) {
                bestDist = d
                best = l
            }
        }
    }
    return best
}
