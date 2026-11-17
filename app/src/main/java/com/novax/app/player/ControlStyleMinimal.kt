package com.novax.app.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.components.popupGlassBackground
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.theme.NvPlayerBlue

/**
 * 极简样式包（spec 2026-11-15 §3.2 **rev5**，用户第三次拍板 2026-11-18）：nPlayer 1:1 复刻。
 * - **顶栏=进度条**（[MinimalSeekTopBar] 48dp 通栏）：蓝填充=已播比例，整栏可拖 scrub/单点
 *   定跳；两端蓝块——蓝✕=退出播放器、蓝⋯（横三点）=精简「播放管理」菜单
 *   （[MinimalQuickMenu]：锁定/上下集/画中画/投屏/退出播放器）；无缓冲段、无章节刻度、
 *   无拖动气泡（三条禁令）。
 * - 左列（[MinimalSideRail]）：画面旋转（首钮）→ CC 字幕 → 齿轮更多面板；右列
 *   （[MinimalSpeedRail]）：倍速三件套 ⊕/档位/⊖。
 * - 底栏（[MinimalBottomBar]）：七钮纯按钮黑条（窄幅五钮），**之下无任何进度条**（rev5
 *   唯一进度显示=顶栏）；窄幅去锁/比例的入口兜底=⋯菜单恒有锁行、比例收更多面板。
 * - **无常驻层**：控制层隐藏后画面完全干净（rev2 常驻层/rev4 贴底细线均作废；下方
 *   [persistentLayerVisible]/[MinimalPersistentLayer]/旧 [MinimalTopBar] 为 **rev5 遗留，
 *   T5 死代码清理时删除**——T3/T4 换接线完成前旧顶栏仍被两侧调用点引用）。
 * - 状态与回调全部来自 [NvControlContract]（样式包不自带业务状态；quickMenuOpen 归编排层）。
 */

/**
 * rev5 极简顶栏通栏高/两端蓝块见方边长（spec §3.2 48dp 口径）；[MinimalQuickMenu] 下拉
 * offset 同源引用（单一口径，防两处 48dp 漂移）。
 */
internal val MinimalSeekTopBarHeight = 48.dp

/**
 * 常驻层可见性门控（spec §3.2 门控矩阵，评审 P0-3.3）——**rev5 遗留（常驻层已全删），
 * 仅存续 T3/T4 过渡期，T5 随 MinimalGateTest 整文件删除**：
 * 未锁/半锁 → 显示（半锁允许看进度；半锁不唤起控制层，controlsShown 恒 false）；全锁 → 隐藏
 * （锁态=纯净画面）；错误态 → 隐藏；唤起态 → 隐藏，由门控内 line 进度条**原位顶替**
 * （二轮评审 P1-2 裁决：防双线——常驻线与唤起细线逐像素重合，视觉上「细线还在、变为可拖」）。
 */
internal fun persistentLayerVisible(fullLocked: Boolean, hasError: Boolean, controlsShown: Boolean): Boolean =
    !fullLocked && !hasError && !controlsShown

/**
 * 极简常驻层（spec §3.2）：细线贴最底缘 + 时间小字在线右上方（BottomEnd）。
 * 宿主置于门控外（AnimatedVisibility 之外）并以 align(Bottom) 锚定；本组件零热区——
 * Canvas/Text 均不消费触摸，事件自然穿到根 Box 的手势状态机。
 * **rev5 遗留（常驻层已全删，隐藏后画面完全干净），仅存续 T3/T4 过渡期，T5 删除**。
 */
@Composable
internal fun MinimalPersistentLayer(c: NvControlContract, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(24.dp)) {
        // DrawScope 非组合上下文：主题 token 先提升为局部值（仓内既定姿势）
        val cPrimary = NvColors.Primary
        // 细线（约 3dp）：未播段半透明白整条打底，已播段 Primary 覆盖；时长未知（直播类）只画底线
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.BottomStart),
        ) {
            val w = size.width
            val y = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), 3.dp.toPx())
            if (c.durationMs > 0) {
                val played = (c.positionMs.toFloat() / c.durationMs).coerceIn(0f, 1f)
                if (played > 0f) drawLine(cPrimary, Offset(0f, y), Offset(w * played, y), 3.dp.toPx())
            }
        }
        Text(
            formatTime(c.positionMs),
            style = NvTypography.LabelSmall,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 6.dp),
        )
    }
}

/**
 * 剩余时间标签（rev5 §3.2 顶栏右半）：`-` + [formatTime]（durationMs−positionMs）。
 * 隐藏口径（返 null）：durationMs<=0（直播/无时长，rev2 总时长隐藏先例）；position 越界
 * （负值或超出时长=数据未就绪/竞态脏值，宁缺勿错）；diff<=0（恰播完）。
 */
internal fun remainingLabel(durationMs: Long, positionMs: Long): String? {
    if (durationMs <= 0) return null
    if (positionMs < 0 || positionMs > durationMs) return null
    val diff = durationMs - positionMs
    if (diff <= 0) return null
    return "-" + formatTime(diff)
}

/**
 * 顶栏已播填充宽（rev5 §3.2 顶栏=进度条，T1）：nPlayer 实证 max 规则——最小填充宽
 * （[leadWidthPx]，调用侧按 ✕块 48dp+当前时间文字实测宽传入）与比例宽（[progress]×
 * [barWidthPx]）取较大者，低进度（nPlayer 截图 00:02/45:19）时填充仍完整覆盖✕与时间文字；
 * 结果 coerce 0~栏宽（窄屏 lead 超栏宽不溢出，plan 审核 P2-3）；progress<0 等脏值经 max
 * 自然回退最小宽（宁多勿缺，填充前缘只进不退）。
 */
internal fun minimalFillWidthPx(leadWidthPx: Float, progress: Float, barWidthPx: Float): Float =
    maxOf(leadWidthPx, progress * barWidthPx).coerceIn(0f, barWidthPx)

/**
 * 是否画顶栏填充（rev5 §3.2 / plan 审核 P2-2，T1）：durationMs<=0（直播/无时长）完全不画
 * 填充——整栏仅半透明深底、无最小宽蓝段（无进度可言），拖动/点跳不可用，✕/⋯/文字照常。
 * 组件层守卫（[MinimalSeekTopBar] 的填充绘制与拖动/tap-seek 门控）以本纯函数为唯一口径，
 * 不另写第二份判定（spec §9）。
 */
internal fun minimalDrawsFill(durationMs: Long): Boolean = durationMs > 0

/**
 * ⋯精简菜单行键（rev5 §3.2 MinimalQuickMenu，T1）：行式列表渲染顺序=声明序
 * （锁定/上一集/下一集/画中画/投屏/退出播放器，spec 布局口径；与底栏锁/上下集重复属
 * §5.5 更多面板冗余同先例的有意冗余）。
 */
enum class QuickMenuRow { LOCK, PREV, NEXT, PIP, CAST, EXIT }

/**
 * ⋯精简菜单行集（rev5 §3.2；spec §5.3 键集思想，[quickControlsFor] 同型写法，T1）：
 * RMVB 剔 PIP/CAST（两引擎能力差异）；主侧 hasCast=false 剔 CAST（运行时门控——playUrl
 * 为 null 时隐藏投屏行，与经典顶栏藏投屏键同口径，hasQuality 同型先例）。两机制独立叠加
 * （RMVB 接线恒 hasCast=false，双重剔除幂等，plan 复审已核无冲突）。
 */
internal fun quickMenuRows(isRmvb: Boolean, hasCast: Boolean): List<QuickMenuRow> =
    QuickMenuRow.entries.filter { row ->
        val rmvbExcluded = isRmvb && (row == QuickMenuRow.PIP || row == QuickMenuRow.CAST)
        val castHidden = row == QuickMenuRow.CAST && !hasCast
        !rmvbExcluded && !castHidden
    }

/**
 * 极简顶栏=进度条（rev5 §3.2，nPlayer 1:1，替代旧 MinimalTopBar 的调用位）：
 * 48dp 通栏（[MinimalSeekTopBarHeight]），**自绘进度**（不复用 NvChapterSlider：全宽填充+
 * 文字叠加 vs 底部轨道+时间行，结构差异过大）。
 * - 视觉：整栏未播段 `Color.Black.copy(alpha=0.45f)` 打底（画面透出，nPlayer 实测口径）；
 *   已播段 [NvPlayerBlue] 从 x=0 起铺，宽=[minimalFillWidthPx]（lead=✕块 48dp+当前时间文字
 *   实测宽，TextMeasurer 同帧实测；lead 随显示文本变化即接受跳动——拖动跨小时格式跳变→
 *   前缘位移=nPlayer 同源口径，不做平滑，plan 审核 P2-3）；[minimalDrawsFill]==false
 *   （durationMs<=0 直播/无时长）不画任何蓝填充（整栏仅深底）。**三条禁令**（spec §3.2）：
 *   无缓冲段显示、无章节刻度、无拖动气泡（当前时间标签本身即预览）。
 * - 元素（Row 叠在 Canvas 上）：蓝✕块（48dp 见方圆角方块白图标=退出播放器）→ 当前时间
 *   （[formatTime]，拖动中=拖动目标值）→ 标题（居中弹性占位/省略号）→ 剩余时间
 *   （[remainingLabel]；**拖动中显示拖动目标的剩余**——与 nPlayer 同源口径，备选「拖动中
 *   隐藏剩余」二选一未采用，此处注明）→ 蓝⋯块（横三点 `Icons.Filled.MoreHoriz`，点击
 *   [NvControlContract.onToggleQuickMenu]；打开态（quickMenuOpen=true）加白描边高亮）。
 * - 交互（pointerInput 挂整栏，热区=栏全高）：横向拖动 scrub——本地 dragPositionMs 实时
 *   回显（时间标签+填充跟随），**拖动中不调引擎**，松手 onSeek 提交并清态（提交后回显对齐
 *   经典 slider 现状：引擎 seek 异步，允许瞬时回跳旧值再追上）；单点 tap 定跳（[timeFromX]
 *   与经典 slider 同源换算/夹取=拖到底跳片尾前 1s）。durationMs<=0 时拖动/tap-seek 全
 *   no-op（守卫消费 [minimalDrawsFill] 本体）。手势消费限于本栏（不外溢）；✕/⋯块自身
 *   clickable 在上层消费（slider 手势不抢两块点击）。durationMs 经 rememberUpdatedState 供
 *   手势协程读最新值（NvChapterSlider 2026-09-16 审计 P2-9 同款教训：时长不做 pointerInput
 *   key，防起播早期「未知→就绪」重启协程致 onDragEnd 不回调）。
 * - **宿主渐变不挂载**（spec §3.2，plan 审核）：顶栏宿主的 ScrimStrong→透明渐变对 MINIMAL
 *   分支不叠加（渐变叠在未播段半透明深底上会成非均匀色，破坏 1:1「画面透出」观感）——由
 *   两侧编排接线（T3/T4）落地，过渡期宿主渐变仍在属预期混合态，本 doc 先行注明。
 * - [onDragStart]：拖动开始钩子（可选，T3 接线收父层 seek 预览浮层用；NvChapterSlider
 *   onDragStart 同口径），默认 null 不影响编译。
 */
@Composable
internal fun MinimalSeekTopBar(
    c: NvControlContract,
    modifier: Modifier = Modifier,
    onDragStart: (() -> Unit)? = null,
) {
    // 填充门控唯一口径（plan 复审锚）：消费 minimalDrawsFill 本体，组件不另写第二份判定
    val drawsFill = minimalDrawsFill(c.durationMs)
    // 拖动 scrub 本地态：拖动中仅实时回显目标值（时间标签+填充跟随），不调引擎；
    // 松手 onSeek 提交并清 null（回显对齐经典 slider 现状，见 kdoc）
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    // 手势协程读最新值（时长不做 pointerInput key，教训见 kdoc）
    val currentDuration by rememberUpdatedState(c.durationMs)
    val currentOnSeek by rememberUpdatedState(c.onSeek)
    val currentDragStart by rememberUpdatedState(onDragStart)

    val density = LocalDensity.current
    val blockPx = with(density) { MinimalSeekTopBarHeight.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val timeStyle = NvTypography.LabelMedium

    // 显示口径：拖动中=拖动目标位置（当前时间与剩余时间一并跟随，kdoc 注明）
    val displayPos = dragPositionMs ?: c.positionMs
    val timeText = formatTime(displayPos)
    // lead 实测宽 = 蓝块 48dp + 当前时间文字实测宽（AnnotatedString 走 TextMeasurer 成员
    // measure，与显示 Text 同 style 同帧一致）
    val leadWidthPx = blockPx + textMeasurer
        .measure(AnnotatedString(timeText), style = timeStyle)
        .size.width

    Box(
        modifier
            .fillMaxWidth()
            .height(MinimalSeekTopBarHeight)
            // 单点 tap 定跳（tap 检测在 drag 之前，NvChapterSlider 同序）：✕/⋯块自身
            // clickable 在上层消费（子节点 consume up→父 tap 探测取消），不抢两块点击
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val dur = currentDuration
                    // durationMs<=0：tap-seek no-op（minimalDrawsFill 同源口径）
                    if (!minimalDrawsFill(dur)) return@detectTapGestures
                    currentOnSeek(timeFromX(offset.x, size.width.toFloat(), dur))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val dur = currentDuration
                        if (!minimalDrawsFill(dur)) return@detectDragGestures
                        currentDragStart?.invoke()
                        dragPositionMs = timeFromX(offset.x, size.width.toFloat(), dur)
                    },
                    onDragEnd = {
                        val target = dragPositionMs
                        dragPositionMs = null
                        if (target != null) currentOnSeek(target)
                    },
                    onDragCancel = { dragPositionMs = null },
                ) { change, _ ->
                    change.consume()
                    val dur = currentDuration
                    if (minimalDrawsFill(dur)) {
                        dragPositionMs = timeFromX(change.position.x, size.width.toFloat(), dur)
                    }
                }
            },
    ) {
        // 底层 Canvas：整栏未播段半透明深底打底；已播段蓝从 x=0 起铺（无时长仅深底）
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color.Black.copy(alpha = 0.45f))
            if (drawsFill) {
                val progress = (displayPos.toFloat() / c.durationMs).coerceIn(0f, 1f)
                val fillPx = minimalFillWidthPx(leadWidthPx, progress, size.width)
                drawRect(NvPlayerBlue, size = Size(fillPx, size.height))
            }
        }
        Row(
            Modifier.matchParentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 蓝✕块：48dp 见方圆角方块=退出播放器（旧顶栏同款圆角 10dp）
            val blockShape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .size(MinimalSeekTopBarHeight)
                    .background(NvPlayerBlue, blockShape)
                    .clickable(onClick = c.onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.player_cd_close), Modifier.size(24.dp), tint = Color.White)
            }
            // 当前时间（拖动中=拖动目标值）
            Text(
                timeText,
                color = Color.White,
                style = timeStyle,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            // 标题：居中弹性占位/省略号（旧顶栏同款姿势）
            Text(
                c.title,
                color = Color.White,
                style = timeStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // 剩余时间（拖动中=拖动目标的剩余，与 nPlayer 同源；隐藏口径见 remainingLabel）
            remainingLabel(c.durationMs, displayPos)?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.85f),
                    style = timeStyle,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            } ?: Spacer(Modifier.width(10.dp))
            // 蓝⋯块：横三点=精简「播放管理」菜单入口（rev4 竖⋮ 改横⋯，nPlayer 1:1）；
            // 打开态白描边高亮（打开/关闭态区分，kdoc 注明）
            Box(
                Modifier
                    .size(MinimalSeekTopBarHeight)
                    .background(NvPlayerBlue, blockShape)
                    .then(if (c.quickMenuOpen) Modifier.border(1.5.dp, Color.White, blockShape) else Modifier)
                    .clickable(onClick = c.onToggleQuickMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MoreHoriz, stringResource(R.string.player_sheet_more), Modifier.size(24.dp), tint = Color.White)
            }
        }
    }
}

/**
 * 极简蓝⋯精简「播放管理」菜单（rev5 §3.2 MinimalQuickMenu）：右上下拉深色半透明圆角卡。
 * - 容器视觉=MoreActionsSheet 同款 token（[popupGlassBackground]+[NvShape.Dialog]，宽
 *   220dp；顶部 offset=[MinimalSeekTopBarHeight] 顶栏高下拉）；行集=[quickMenuRows]
 *   （isRmvb 天然无 PIP/CAST；渲染序=声明序），纯文字行（nPlayer ⋯菜单 1:1 无图标，
 *   [NvSheetListItem] 48dp 热区同款；上/下集 hasPrev/hasNext=false 灰置不隐藏）。
 * - 行动作：LOCK→onLock（**LOCK 行不自行关菜单**——走锁定收层出口的复位链）；
 *   PREV/NEXT/PIP/CAST/EXIT→对应回调，**除 LOCK 外所有行点击后统一回调
 *   [NvControlContract.onToggleQuickMenu] 复位关菜单**（切集/投屏/退出后菜单不留、
 *   隐藏计时不再挂起，plan 审核 P2-5 拍板）。
 * - **外点消费（plan 审核 P1-1 姿势钉死）**：菜单可见时菜单外全屏透明拦截层——tap 外点=
 *   仅关菜单（detectTapGestures），**外加 drag 全消费**（detectDragGestures 逐 change
 *   consume）：裸 clickable 只消费 tap，横向拖动超 touch slop 后 move 不消费会穿透祖先
 *   手势状态机（亮度/音量/seek 穿透执行）——必须双消费。验收锚：菜单开着横滑=零手势响应、
 *   tap 外点=仅关菜单。卡本体与行自身消费点击（点卡不关菜单，外点才关）。
 * - 渲染条件由调用侧保证（quickMenuOpen && controlsShown 等价条件，T3/T4 接线）；**防自动
 *   隐藏归编排层**（契约 quickMenuOpen 门控）；组件内部不做可见性判断。组件根 Box 自带
 *   fillMaxSize（拦截层需全屏覆盖调用侧给的 Bounds）。
 */
@Composable
internal fun MinimalQuickMenu(c: NvControlContract, isRmvb: Boolean, modifier: Modifier = Modifier) {
    val toggleQuickMenu by rememberUpdatedState(c.onToggleQuickMenu)
    Box(modifier.fillMaxSize()) {
        // —— 菜单外全屏透明拦截层（先挂先画=在卡下层）——
        Box(
            Modifier
                .matchParentSize()
                // tap 外点=仅关菜单（不穿透为控制层显隐切换/手势）
                .pointerInput(Unit) {
                    detectTapGestures { toggleQuickMenu() }
                }
                // drag 全消费（P1-1）：拦「超 touch slop 后 move 不消费→祖先判横滑穿透」
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                    }
                },
        )
        // —— 右上下拉卡 ——
        val lockLabel = stringResource(R.string.player_cd_lock)
        val prevLabel = stringResource(R.string.player_prev_episode)
        val nextLabel = stringResource(R.string.player_next_episode)
        val pipLabel = stringResource(R.string.player_cd_pip)
        val castLabel = stringResource(R.string.player_cd_cast)
        val exitLabel = stringResource(R.string.player_quick_exit)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = MinimalSeekTopBarHeight)
                .width(220.dp)
                .clip(NvShape.Dialog)
                // MoreActionsSheet（NvPlayerPopup）容器同款玻璃配方（GlassFillStrong+Dialog 圆角）
                .then(popupGlassBackground(NvShape.Dialog))
                // 卡本体吃掉点击（无 ripple），点卡不关菜单（外点才关）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(vertical = 4.dp),
        ) {
            quickMenuRows(isRmvb, c.hasCast).forEach { row ->
                when (row) {
                    // LOCK 不自行关菜单（锁定收层出口复位链，见 kdoc）
                    QuickMenuRow.LOCK -> NvSheetListItem(lockLabel) { c.onLock() }
                    QuickMenuRow.PREV -> NvSheetListItem(prevLabel, enabled = c.hasPrev) {
                        c.onPrev()
                        toggleQuickMenu()
                    }
                    QuickMenuRow.NEXT -> NvSheetListItem(nextLabel, enabled = c.hasNext) {
                        c.onNext()
                        toggleQuickMenu()
                    }
                    QuickMenuRow.PIP -> NvSheetListItem(pipLabel) {
                        c.onPip()
                        toggleQuickMenu()
                    }
                    QuickMenuRow.CAST -> NvSheetListItem(castLabel) {
                        c.onCast()
                        toggleQuickMenu()
                    }
                    QuickMenuRow.EXIT -> NvSheetListItem(exitLabel) {
                        c.onBack()
                        toggleQuickMenu()
                    }
                }
            }
        }
    }
}

/**
 * 极简唤起态顶栏（rev4 §3.2）——**rev5 遗留（已被 [MinimalSeekTopBar] 替代），仅存续
 * T3/T4 换接线过渡期（两侧调用点未动，本组件仍被引用），T5 死代码清理时删除**。
 * 深色半透明横条 44dp，左起——蓝✕（[NvPlayerBlue] 圆角方块=退出播放器）→ 位置时间
 * （positionMs，复用 [formatTime]）→ 标题（居中弹性占位/省略号截断）→ 剩余时间（负号
 * 格式，[remainingLabel] 隐藏口径）→ 蓝⋯（更多面板入口）。
 * 签名（契约 + modifier）与 rev2 一致，两侧调用点零改动。
 */
@Composable
internal fun MinimalTopBar(c: NvControlContract, modifier: Modifier = Modifier) {
    Row(
        // scrim 同款半透明深底（宿主外层另有 ScrimStrong→透明渐变，两层叠加出 nPlayer 深色横条观感）
        modifier.fillMaxWidth().height(44.dp).background(NvColors.Scrim).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(NvPlayerBlue, RoundedCornerShape(10.dp))
                .clickable(onClick = c.onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, stringResource(R.string.player_cd_close), Modifier.size(24.dp), tint = Color.White)
        }
        Text(
            formatTime(c.positionMs),
            color = Color.White,
            style = NvTypography.LabelMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Text(
            c.title,
            color = Color.White,
            style = NvTypography.LabelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        remainingLabel(c.durationMs, c.positionMs)?.let {
            Text(
                it,
                color = Color.White.copy(alpha = 0.85f),
                style = NvTypography.LabelMedium,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        } ?: Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(44.dp)
                .background(NvPlayerBlue, RoundedCornerShape(10.dp))
                .clickable(onClick = c.onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.player_sheet_more), Modifier.size(24.dp), tint = Color.White)
        }
    }
}

/**
 * 极简倍速三件套步进（rev4 §3.2 右列 ⊕/⊖）：加 delta 后吸附 0.1 网格并 clamp 0.1~5.0
 * （复用 [snapPlaybackSpeed]，防裸浮点累加漂移透出到引擎与显示）。重置不走本函数
 * （组件层直接 onSetSpeed(1f)，契约语义=SpeedSheet.onPick 同款五动作）。
 */
fun nvSpeedStep(current: Float, delta: Float): Float = snapPlaybackSpeed(current + delta)

/**
 * 侧钮列共用圆钮（rev4 §3.2）：46dp 黑色半透明圆底（Black 55%，nPlayer Simple Line 实测）
 * + 24dp 白 icon。与顶栏蓝块不同——侧钮列是「圆」不是「圆角方块」，1:1 对照用户归档截图。
 */
@Composable
private fun MinimalRailButton(onClick: () -> Unit, contentDescription: String, icon: ImageVector) {
    Box(
        Modifier
            .size(46.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(24.dp), tint = Color.White)
    }
}

/**
 * 极简唤起态左列（rev5 §3.2）：**画面旋转（第一钮；点按 0°→90°→180°→270° 循环，复用既有
 * onVideoRotate 与其现有反馈机制，cd=player_cd_rotate 勿造新键）**→ CC 字幕
 * （Outlined.Subtitles，开字幕面板）→ 齿轮更多（Outlined.Settings，=「更多」面板入口）。
 * rev4 首钮=锁三态图标（Filled.Lock/Filled.LockOpen/Outlined.LockOpen）随 rev5 **移除**
 * （锁可达性不减：底栏首钮锁钮+⋯菜单恒有锁行；三态图标逻辑归 [MinimalBottomBar]，不在本组件）。
 * **垂直定位由调用侧负责**（R4 接线：align(CenterStart)+offset(y=屏高×6%)）——组件只交付纯 Column。
 */
@Composable
internal fun MinimalSideRail(c: NvControlContract, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MinimalRailButton(
            onClick = c.onVideoRotate,
            contentDescription = stringResource(R.string.player_cd_rotate),
            icon = Icons.Outlined.Rotate90DegreesCcw,
        )
        MinimalRailButton(
            onClick = c.onSubtitle,
            contentDescription = stringResource(R.string.player_sheet_subtitles),
            icon = Icons.Outlined.Subtitles,
        )
        MinimalRailButton(
            onClick = c.onMore,
            contentDescription = stringResource(R.string.player_sheet_more),
            icon = Icons.Outlined.Settings,
        )
    }
}

/**
 * 极简唤起态右列（rev4 §3.2）：倍速三件套 ⊕/当前档标签/⊖。
 * - ⊕/⊖ = [nvSpeedStep] ±0.1 步进（吸附防漂移）经 c.onSetSpeed 提交；
 * - 中间恒显 [formatSpeedLabel] 当前档（"1x"/"1.5x"），非 1x 时 [NvPlayerBlue] 高亮，
 *   点击重置 c.onSetSpeed(1f)（1x 时点击无效果，幂等）。
 * **垂直定位由调用侧负责**（R4 接线：align(CenterEnd)+offset(y=屏高×6%)）——组件只交付纯 Column。
 */
@Composable
internal fun MinimalSpeedRail(c: NvControlContract, modifier: Modifier = Modifier) {
    // i18n：semantics 块非组合上下文，资源解析提升到组合期（streaming SPEED 槽同款）
    val resetCd = stringResource(R.string.player_speed_reset)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MinimalRailButton(
            onClick = { c.onSetSpeed(nvSpeedStep(c.speed, +0.1f)) },
            contentDescription = stringResource(R.string.player_speed_plus),
            icon = Icons.Outlined.Add,
        )
        Box(
            Modifier
                .size(46.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .clickable(onClick = { c.onSetSpeed(1f) })
                // Text 无 cd 参数：重置语义挂 Box semantics（streaming SPEED 槽同款姿势）
                .semantics { contentDescription = resetCd },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatSpeedLabel(c.speed),
                style = NvTypography.LabelLarge,
                color = if (c.speed != 1f) NvPlayerBlue else Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }
        MinimalRailButton(
            onClick = { c.onSetSpeed(nvSpeedStep(c.speed, -0.1f)) },
            contentDescription = stringResource(R.string.player_speed_minus),
            icon = Icons.Outlined.Remove,
        )
    }
}

/** 极简底栏槽位（rev4 §3.2 布局图底栏行顺序：锁/上集/快退/暂停/快进/下集/比例）。 */
enum class MinimalSlot { LOCK, PREV, REW, PAUSE, FWD, NEXT, ASPECT }

/** 窄幅（<480dp 竖屏/折叠栏）去锁钮与比例钮——锁经⋯菜单（MinimalQuickMenu 恒有锁行）、
 *  比例经更多面板仍可达（spec §3.2 rev5 口径：⋯菜单恒有锁行后窄幅去锁不再损失锁入口）。 */
internal fun minimalBottomSlots(narrow: Boolean): List<MinimalSlot> =
    if (narrow) listOf(MinimalSlot.PREV, MinimalSlot.REW, MinimalSlot.PAUSE, MinimalSlot.FWD, MinimalSlot.NEXT)
    else MinimalSlot.entries.toList()

/**
 * 极简唤起态底栏（rev4 §3.2 布局沿用，rev5 口径修订）：黑色半透明横条内七钮一行**等距**
 * （Arrangement.SpaceEvenly，实测用户截图等距分布）——
 * 锁 │ 上集 │ 快退(skipSec) │ 暂停 │ 快进(skipSec) │ 下集 │ 画面比例。
 * - **底部无进度条（rev5）**：线不在本栏——唯一进度显示=顶栏 [MinimalSeekTopBar]（rev4
 *   「宿主 Column { 底栏; NvChapterSlider(LINE) }」的贴底线为过渡期形态，rev5 作废）。
 *   **背景本组件不自画**（仍由宿主 Column 统一承载 `Modifier.background(NvColors.Scrim)`，
 *   黑条背景向下延伸覆盖线触摸区=连续黑块，2026-11-15 三星真机缺陷根修）；背景收回组件
 *   自带必须与宿主 Column 拆除**同任务原子完成**（T3/T4），防组件 Scrim+宿主 Scrim 双层
 *   叠加（rev4 根修 83ef321 的接缝教训）——本任务背景代码零改动，仅 kdoc 注明 rev5 目标口径。
 * - 暂停 = 经典 PauseSlot 同款 NvGlassChip 58dp/30dp 磨砂圆钮（宽幅）；窄幅尺寸走
 *   [nvNarrowControlMetrics]/[nvRowIconFor]（streaming 底栏同源档位，五钮才放得下）。
 * - 上/下集**灰置不隐藏**（NvClusterButton enabled，nPlayer 一致口径）；锁=三态图标
 *   （LockSlot 同口径无 caption；rev5 左列锁移除后本钮+⋯菜单锁行=锁入口全集）；比例=开
 *   比例面板（onAspect）。
 */
@Composable
internal fun MinimalBottomBar(c: NvControlContract, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
    ) {
        val narrow = maxWidth < 480.dp
        val slots = minimalBottomSlots(narrow)
        val (pauseSize, btn) = if (narrow) nvNarrowControlMetrics(maxWidth) else 58.dp to 48.dp
        val pauseIcon = if (narrow) pauseSize * 0.48f else 30.dp
        val rowIcon = if (narrow) nvRowIconFor(btn) else 24.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEach { slot ->
                when (slot) {
                    MinimalSlot.LOCK -> IconButton(onClick = c.onLock, modifier = Modifier.size(btn)) {
                        Icon(
                            when (c.lockMode) {
                                2 -> Icons.Filled.Lock
                                1 -> Icons.Filled.LockOpen     // 半锁：实心开锁（与未锁的空心开锁区分，LockSlot 同款）
                                else -> Icons.Outlined.LockOpen
                            },
                            stringResource(R.string.player_cd_lock),
                            Modifier.size(rowIcon),
                            tint = Color.White,
                        )
                    }
                    MinimalSlot.PREV -> NvClusterButton(
                        icon = Icons.Outlined.SkipPrevious,
                        caption = stringResource(R.string.player_prev_episode),
                        enabled = c.hasPrev,
                        contentDescription = if (c.hasPrev) stringResource(R.string.player_cd_prev)
                        else stringResource(R.string.player_cd_prev_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onPrev,
                    )
                    MinimalSlot.REW -> NvSkipStepButton(rewind = true, sec = c.skipSec, size = btn, iconSize = rowIcon) {
                        c.onSkipBy(-c.skipSec * 1000L)
                    }
                    // 暂停圆钮：经典 PauseSlot 同款 NvGlassChip + GlassFillStrong 磨砂强填充（返工⑤口径）
                    MinimalSlot.PAUSE -> NvGlassChip(
                        modifier = Modifier.size(pauseSize),
                        shape = RoundedCornerShape(pauseSize / 2),
                        fill = NvColors.GlassFillStrong,
                        fillOverlay = true,
                        onClick = c.onTogglePlay,
                    ) {
                        Icon(
                            if (c.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (c.isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                            Modifier.size(pauseIcon).align(Alignment.Center),
                            tint = NvColors.TextPrimary,
                        )
                    }
                    MinimalSlot.FWD -> NvSkipStepButton(rewind = false, sec = c.skipSec, size = btn, iconSize = rowIcon) {
                        c.onSkipBy(c.skipSec * 1000L)
                    }
                    MinimalSlot.NEXT -> NvClusterButton(
                        icon = Icons.Outlined.SkipNext,
                        caption = stringResource(R.string.player_next_episode),
                        enabled = c.hasNext,
                        contentDescription = if (c.hasNext) stringResource(R.string.player_cd_next)
                        else stringResource(R.string.player_cd_next_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onNext,
                    )
                    // 比例钮：开比例面板（窄幅收更多面板兜底，spec §3.2 窄幅口径）
                    MinimalSlot.ASPECT -> NvClusterButton(
                        icon = Icons.Outlined.AspectRatio,
                        caption = stringResource(R.string.player_aspect_short),
                        enabled = true,
                        contentDescription = stringResource(R.string.player_sheet_aspect),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onAspect,
                    )
                }
            }
        }
    }
}
