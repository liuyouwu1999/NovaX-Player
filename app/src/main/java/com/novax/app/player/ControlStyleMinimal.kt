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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.glass.NvGlassLensTier
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.components.popupGlassBackground
import com.novax.app.ui.glass.NvGlassChip
import com.novax.app.ui.theme.NvColors

/**
 * 极简样式包（spec 2026-11-15 §3.2 **rev5**，用户第三次拍板 2026-11-18）：nPlayer 1:1 复刻。
 * - **顶栏=进度条**（[MinimalSeekTopBar] 48dp 通栏）：主题色填充=已播比例，整栏可拖 scrub/单点
 *   定跳；两端主题色块——✕=退出播放器、⋯（横三点）=精简「播放管理」菜单
 *   （[MinimalQuickMenu]：锁定/上下集/画中画/投屏/退出播放器）；无缓冲段、无章节刻度、
 *   无拖动气泡（三条禁令）。
 * - 左列（[MinimalSideRail]）：画面旋转（首钮）→ CC 字幕 → 齿轮更多面板；右列
 *   （[MinimalSpeedRail]）：倍速三件套 ⊕/档位/⊖。
 * - 底栏（[MinimalBottomBar]）：七钮纯按钮黑条（窄幅五钮），**之下无任何进度条**（rev5
 *   唯一进度显示=顶栏）；窄幅去锁/比例的入口兜底=⋯菜单恒有锁行、比例收更多面板。
 * - **无常驻层**：控制层隐藏后画面完全干净（rev2 常驻层/rev4 贴底细线均作废）。
 * - 状态与回调全部来自 [NvControlContract]（样式包不自带业务状态；quickMenuOpen 归编排层）。
 */

/**
 * rev5 极简顶栏通栏高/两端色块见方边长（spec §3.2 48dp 口径；2026-11-19 用户两轮反馈
 * 48→40→36dp「再细一点」）；[MinimalQuickMenu] 下拉 offset 同源引用（单一口径，防漂移）。
 */
internal val MinimalSeekTopBarHeight = 36.dp

/** 极简顶栏/底栏左右内移量（2026-11-19 三轮「还是太长」：动态 4.5% 屏宽 [nvMinimalBarInsetOf]，
 *  横屏≈40dp 对齐 nPlayer、竖屏≈16dp；[nvPanelNarrowOf] 阈值随本公式动态自洽）。
 *  Composable 壳：调用点组合期取 screenWidthDp 传入纯函数（helper 不持组合上下文）。 */
@Composable
internal fun minimalBarInset(): androidx.compose.ui.unit.Dp =
    nvMinimalBarInsetOf(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)

/** 极简左右侧钮列水平内移量（2026-11-19 用户拍板「靠边被摄像头挡住」：避开挖孔/刘海带） */
internal val MinimalRailEdgeInset = 32.dp

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
 * 顶栏已播填充宽（rev5 §3.2 顶栏=进度条，T1；2026-11-19 用户拍板改线性映射）：比例宽
 * [progress]×[barWidthPx] 直接映射，coerce 0~栏宽（progress 超界脏值不溢出）。
 * **弃原 nPlayer「最小填充宽（lead）max 规则」**（原实现=max(✕块+时间文字宽, 比例宽)，
 * 照 nPlayer 低进度截图做的）——用户真机实测两个 bug：①progress=0 时✕块右侧仍盖一条
 * 主题色（✕块左右不对称）；②低进度拖动时填充前缘钉在 lead 宽不动（零视觉反馈），
 * 拖回 00:00 感知「拖不到开头」。改版后语义：0%=无填充（✕块自身主题色即左端，
 * 右缘=填充前缘，左右对称）、100%=填满与右⋯块贴合、中间全程前缘跟手。
 */
internal fun minimalFillWidthPx(progress: Float, barWidthPx: Float): Float =
    (progress * barWidthPx).coerceIn(0f, barWidthPx)

/**
 * 是否画顶栏填充（rev5 §3.2 / plan 审核 P2-2，T1）：durationMs<=0（直播/无时长）完全不画
 * 填充——整栏仅半透明深底、无最小宽色段（无进度可言），拖动/点跳不可用，✕/⋯/文字照常。
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
 * 极简顶栏=进度条（rev5 §3.2，nPlayer 1:1）：
 * 48dp 通栏（[MinimalSeekTopBarHeight]），**自绘进度**（不复用 NvChapterSlider：全宽填充+
 * 文字叠加 vs 底部轨道+时间行，结构差异过大）。
 * - 视觉：整栏玻璃底 `nvGlassSurface(NvColors.Scrim, RectangleShape)`（2026-11-19 用户拍板
 *   「风格和 app 玻璃统一」——替换原 45% 纯黑打底；主播放器/RMVB 页视频层均已挂 nvGlassSource，
 *   玻璃即挂即生效，API≤30 降级近实底；两端口块为满高直角，故保矩形直角玻璃）；已播段主题色
 *   （NvColors.Primary，2026-11-19 用户拍板弃 nPlayer 蓝）从 x=0 起铺，宽=
 *   [minimalFillWidthPx] 线性映射（2026-11-19 用户拍板弃 nPlayer「lead 最小填充宽」规则：
 *   0%=无填充、✕块右缘即前缘左右对称、拖动全程跟手，见该函数 kdoc）；[minimalDrawsFill]==false
 *   （durationMs<=0 直播/无时长）不画任何主题色填充（整栏仅玻璃底）。**三条禁令**（spec §3.2）：
 *   无缓冲段显示、无章节刻度、无拖动气泡（当前时间标签本身即预览）。
 * - 元素（Row 叠在 Canvas 上）：主题色✕块（48dp 见方圆角方块白图标=退出播放器）→ 当前时间
 *   （[formatTime]，拖动中=拖动目标值）→ 标题（居中弹性占位/省略号）→ 剩余时间
 *   （[remainingLabel]；**拖动中显示拖动目标的剩余**——与 nPlayer 同源口径，备选「拖动中
 *   隐藏剩余」二选一未采用，此处注明）→ 主题色⋯块（横三点 `Icons.Filled.MoreHoriz`，点击
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
 *   分支不叠加（渐变叠在未播段半透明深底上会成非均匀色，破坏 1:1「画面透出」观感）——
 *   两侧编排接线（T3/T4）已落地：主播放器/RMVB 兜底页 MINIMAL 宿主渐变恒不挂载。
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

    val timeStyle = NvTypography.LabelMedium

    // 显示口径：拖动中=拖动目标位置（当前时间与剩余时间一并跟随，kdoc 注明）
    val displayPos = dragPositionMs ?: c.positionMs
    val timeText = formatTime(displayPos)

    Box(
        modifier
            .fillMaxWidth()
            // 2026-11-19 用户反馈「进度条贴顶，拖动易拉下状态栏」：整体下移屏高 7%
            // （[nvMinimalTopOffsetOf]，用户拍板「大概的固定比例」——statusBarsPadding 在
            // vivo 沉浸式下实测不生效[hide 后 insets 上报不可靠]，改静态比例让位）；拖动/tap
            // 热区随条下移，手势起点离开状态栏下拉冲突区；组件内改，主/RMVB 两挂载点自动生效
            .padding(top = nvMinimalTopOffsetOf(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp))
            // 2026-11-19 用户拍板「进度条不贴左右两边/靠近边边很难点到」：顶栏整体内移（悬浮条，
            // nPlayer 同款），左右各让 16dp——边缘区留给根手势层；pointerInput 挂在 padding 之后
            // =手势区=内移后的条（tap/drag 坐标系自洽）
            .padding(horizontal = minimalBarInset())
            .height(MinimalSeekTopBarHeight)
            // 2026-11-19 用户拍板玻璃风格统一：45% 纯黑打底→Scrim 玻璃面（直角，nvGlassSource
            // 已在两播放器页挂好即挂即生效）；nvGlassSurface 纯绘制，手势链不受影响
            .nvGlassSurface(NvColors.Scrim, RectangleShape)
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
        // 底层 Canvas：玻璃底由 Box 的 nvGlassSurface 承载（2026-11-19 玻璃化，原 45% 纯黑
        // 打底删除——玻璃 tint 已含 Scrim 60% 黑）；Canvas 只画已播段主题色（Primary 橙黄，
        // 2026-11-19 用户拍板弃 nPlayer 蓝）从 x=0 起铺（无时长不画）。DrawScope 非组合
        // 上下文：token 先提升局部值
        val accent = NvColors.Primary
        Canvas(Modifier.matchParentSize()) {
            if (drawsFill) {
                val progress = (displayPos.toFloat() / c.durationMs).coerceIn(0f, 1f)
                val fillPx = minimalFillWidthPx(progress, size.width)
                drawRect(accent, size = Size(fillPx, size.height))
            }
        }
        Row(
            Modifier.matchParentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 主题色✕块：36dp 见方直角正方形=退出播放器（2026-11-19 用户拍板「正方形不要圆角」，nPlayer 同款直角）
            val blockShape = RectangleShape
            Box(
                Modifier
                    .size(MinimalSeekTopBarHeight)
                    .background(accent, blockShape)
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
            // 标题：居中弹性占位/省略号
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
            // 主题色⋯块：横三点=精简「播放管理」菜单入口（rev4 竖⋮ 改横⋯，nPlayer 1:1）；
            // 打开态白描边高亮（打开/关闭态区分，kdoc 注明）
            Box(
                Modifier
                    .size(MinimalSeekTopBarHeight)
                    .background(accent, blockShape)
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
 * 极简⋯精简「播放管理」菜单（rev5 §3.2 MinimalQuickMenu）：右上下拉深色半透明圆角卡。
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
                // 与顶栏同步让位（顶栏下移屏高 7% 后，菜单 offset 参考仍从屏顶算起——
                // 先加同款顶部 padding 再偏移顶栏高，菜单顶仍贴顶栏下缘）
                .padding(top = nvMinimalTopOffsetOf(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp))
                .offset(y = MinimalSeekTopBarHeight)
                .width(220.dp)
                .clip(NvShape.Dialog)
                // 「更多」面板（NvPlayerPopup panelFill=Scrim）同款中透玻璃配方（2026-11-19
                // 用户拍板玻璃风格统一：GlassFillStrong 95% 重磨砂→Scrim 60% 玻璃——比清玻璃
                // 可读、比重磨砂透亮，popupGlassBackground fill 参数首用）
                .then(popupGlassBackground(NvShape.Dialog, NvColors.Scrim))
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
 * 极简倍速三件套步进（rev4 §3.2 右列 ⊕/⊖）：加 delta 后吸附 0.1 网格并 clamp 0.1~5.0
 * （复用 [snapPlaybackSpeed]，防裸浮点累加漂移透出到引擎与显示）。重置不走本函数
 * （组件层直接 onSetSpeed(1f)，契约语义=SpeedSheet.onPick 同款五动作）。
 */
fun nvSpeedStep(current: Float, delta: Float): Float = snapPlaybackSpeed(current + delta)

/**
 * 侧钮列共用圆钮（rev4 §3.2）：46dp 磨砂玻璃圆底（2026-11-19 用户拍板玻璃风格统一——
 * 替换原 Black 55% 纯色圆；配方=暂停钮 NvGlassChip 同款 GlassFillStrong+Chip 档+fillOverlay，
 * 压画面小圆钮清玻璃会隐形的返工⑤先例口径）+ 24dp 白 icon。与顶栏色块不同——侧钮列是
 * 「圆」不是「圆角方块」，1:1 对照用户归档截图。
 */
@Composable
private fun MinimalRailButton(onClick: () -> Unit, contentDescription: String, icon: ImageVector) {
    Box(
        Modifier
            .size(46.dp)
            .nvGlassSurface(NvColors.GlassFillStrong, CircleShape, NvGlassLensTier.Chip, fillOverlay = true)
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
    // 2026-11-19 用户拍板「靠边被摄像头挡住」：水平内移 [MinimalRailEdgeInset] 避开挖孔/刘海带
    Column(modifier.padding(horizontal = MinimalRailEdgeInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
 * - 中间恒显 [formatSpeedLabel] 当前档（"1x"/"1.5x"），非 1x 时主题色高亮，
 *   点击重置 c.onSetSpeed(1f)（1x 时点击无效果，幂等）。
 * **垂直定位由调用侧负责**（R4 接线：align(CenterEnd)+offset(y=屏高×6%)）——组件只交付纯 Column。
 */
@Composable
internal fun MinimalSpeedRail(c: NvControlContract, modifier: Modifier = Modifier) {
    // i18n：semantics 块非组合上下文，资源解析提升到组合期（streaming SPEED 槽同款）
    val resetCd = stringResource(R.string.player_speed_reset)
    // 水平内移与左列对称（MinimalRailEdgeInset，2026-11-19 避挖孔/刘海带）
    Column(modifier.padding(horizontal = MinimalRailEdgeInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MinimalRailButton(
            onClick = { c.onSetSpeed(nvSpeedStep(c.speed, +0.1f)) },
            contentDescription = stringResource(R.string.player_speed_plus),
            icon = Icons.Outlined.Add,
        )
        Box(
            Modifier
                .size(46.dp)
                // 磨砂玻璃圆底（MinimalRailButton 同配方，2026-11-19 玻璃风格统一）
                .nvGlassSurface(NvColors.GlassFillStrong, CircleShape, NvGlassLensTier.Chip, fillOverlay = true)
                .clickable(onClick = { c.onSetSpeed(1f) })
                // Text 无 cd 参数：重置语义挂 Box semantics（streaming SPEED 槽同款姿势）
                .semantics { contentDescription = resetCd },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatSpeedLabel(c.speed),
                style = NvTypography.LabelLarge,
                color = if (c.speed != 1f) NvColors.Primary else Color.White,
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
 * 锁 │ 上集 │ 快退(skipSec) │ 暂停 │ 快进(skipSec) │ 下集 │ 画面比例（**纯图标无文字**，
 * 2026-11-19 用户拍板，整条变薄）。
 * - **底部无进度条（rev5）**：线不在本栏——唯一进度显示=顶栏 [MinimalSeekTopBar]（rev4
 *   「宿主 Column { 底栏; NvChapterSlider(LINE) }」的贴底线已随 T3/T4 拆除，rev5 作废）。
 *   **背景组件自带**（根 `background(NvColors.Scrim)`，R5-T4 收回完成）：黑条背景向下延伸
 *   覆盖触摸区=连续黑块不悬空（2026-11-15 三星真机缺陷根修语义由组件延续），主/RMVB 两侧
 *   宿主裸调用恰好一层；「宿主 Box/Column 统一承载」旧口径退役（83ef321 接缝教训=背景收回
 *   与宿主拆除必须同任务原子完成，T4 已两侧原子落地）。
 * - **2026-11-19 用户视觉拍板**：①说明文字全部去除（上集/下集/15s/比例 caption=null 与
 *   showLabel=false，整条变薄——底栏只剩图标行）；②「每个按键大小对齐」——暂停钮不再放大
 *   （58→48dp 与行内其它钮一致，窄幅同档 btn），全行统一 btn/rowIcon；语义全部由 cd 与
 *   既有面板兜底（步长仍由 skipSec 设置驱动，视觉不标数）。
 * - 暂停 = 经典 PauseSlot 同款 NvGlassChip + GlassFillStrong 磨砂圆钮（尺寸与行内其它钮一致）。
 * - 上/下集**灰置不隐藏**（NvClusterButton enabled，nPlayer 一致口径）；锁=三态图标
 *   （LockSlot 同口径无 caption；rev5 左列锁移除后本钮+⋯菜单锁行=锁入口全集）；比例=开
 *   比例面板（onAspect）。
 */
@Composable
internal fun MinimalBottomBar(c: NvControlContract, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // 2026-11-19 用户拍板「下面的按键条也不要贴边，视觉对称」：背景在 padding
            // 之前=条内移（horizontal [minimalBarInset] 动态 4.5% 屏宽）悬浮条 nPlayer 同款，与顶栏对称；
            // 两侧边缘区留给根手势层
            .padding(horizontal = minimalBarInset())
            // 2026-11-19 用户拍板玻璃风格统一：纯色 Scrim 底→Scrim 玻璃面（直角横条，与顶栏同配方；
            // 背景组件自带语义不变——玻璃面向下覆盖触摸区不悬空，主/RMVB 两侧宿主裸调用恰一层）
            .nvGlassSurface(NvColors.Scrim, RectangleShape)
            .padding(top = 2.dp, bottom = 2.dp),
    ) {
        val narrow = maxWidth < 480.dp
        val slots = minimalBottomSlots(narrow)
        // 2026-11-19 用户拍板「每个按键大小对齐」+「再细一点」：暂停钮不再放大（与其它钮一致），
        // 全行统一 btn 尺寸+rowIcon 图标；说明文字全部去除（caption=null/showLabel=false，整条变薄）；
        // btn 48→44dp（整条 48dp）
        val btn = if (narrow) nvNarrowControlMetrics(maxWidth).second else 44.dp
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
                        caption = null,
                        enabled = c.hasPrev,
                        contentDescription = if (c.hasPrev) stringResource(R.string.player_cd_prev)
                        else stringResource(R.string.player_cd_prev_none),
                        size = btn,
                        iconSize = rowIcon,
                        onClick = c.onPrev,
                    )
                    MinimalSlot.REW -> NvSkipStepButton(rewind = true, sec = c.skipSec, size = btn, iconSize = rowIcon, showLabel = false) {
                        c.onSkipBy(-c.skipSec * 1000L)
                    }
                    // 暂停圆钮：经典 PauseSlot 同款 NvGlassChip + GlassFillStrong 磨砂强填充（返工⑤口径）；
                    // 2026-11-19 对齐：48dp 与行内其它钮一致
                    MinimalSlot.PAUSE -> NvGlassChip(
                        modifier = Modifier.size(btn),
                        shape = RoundedCornerShape(btn / 2),
                        fill = NvColors.GlassFillStrong,
                        fillOverlay = true,
                        onClick = c.onTogglePlay,
                    ) {
                        Icon(
                            if (c.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (c.isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                            Modifier.size(rowIcon + 4.dp).align(Alignment.Center),
                            tint = NvColors.TextPrimary,
                        )
                    }
                    MinimalSlot.FWD -> NvSkipStepButton(rewind = false, sec = c.skipSec, size = btn, iconSize = rowIcon, showLabel = false) {
                        c.onSkipBy(c.skipSec * 1000L)
                    }
                    MinimalSlot.NEXT -> NvClusterButton(
                        icon = Icons.Outlined.SkipNext,
                        caption = null,
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
                        caption = null,
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
