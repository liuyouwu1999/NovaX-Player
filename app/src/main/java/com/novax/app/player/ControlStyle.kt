package com.novax.app.player

/** 控制层样式（spec 2026-11-15 §3）：CLASSIC=经典（默认） MINIMAL=极简 STREAMING=长视频风。 */
enum class NvControlStyle { CLASSIC, MINIMAL, STREAMING }

/** DataStore 原始值解析；未知/null/空一律回退 CLASSIC（fail-safe，spec §6）。 */
fun parseControlStyle(raw: String?): NvControlStyle = when (raw) {
    "minimal" -> NvControlStyle.MINIMAL
    "streaming" -> NvControlStyle.STREAMING
    else -> NvControlStyle.CLASSIC
}

/** 写入端原始值（与 parseControlStyle 互逆；spec §6 classic/minimal/streaming 字面值）。
 *  播放中「控制样式」组（T8）与设置页共用本映射直写 KEY_CONTROL_STYLE。 */
val NvControlStyle.dataStoreValue: String
    get() = when (this) {
        NvControlStyle.CLASSIC -> "classic"
        NvControlStyle.MINIMAL -> "minimal"
        NvControlStyle.STREAMING -> "streaming"
    }

/**
 * 「更多」面板「播放控制」组行键（spec 2026-11-15 §5.5，T8；2026-11-19 起按样式去重）：
 * 16 入口与 §3.4 功能对等总表一一对应（暂停/播放与手势重复，不入组）。
 */
enum class QuickControlId { LOCK, PREV, NEXT, REW, FWD, SPEED, SUBTITLE, AUDIO, ASPECT, ORIENTATION, MIRROR, ROTATE, PIP, QUALITY, CAST, INFO }

/** 极简档已被外层直接按钮覆盖的入口（2026-11-19 用户拍板方案 A 去重账目，出处=spec §3.4
 *  功能对等总表+主会话逐项核对）：锁=底栏+⋯菜单、上下集=底栏+⋯菜单、快退/快进=底栏、
 *  CC 字幕=左列、旋转=左列、PIP/投屏=⋯菜单。**SPEED 不在集内=有意保留**（见
 *  [quickControlsFor] kdoc）；ASPECT 窄幅维度单列（宽幅覆盖/窄幅保留）。 */
private val MINIMAL_STYLE_COVERED: Set<QuickControlId> = setOf(
    QuickControlId.LOCK,
    QuickControlId.PREV, QuickControlId.NEXT,
    QuickControlId.REW, QuickControlId.FWD,
    QuickControlId.SUBTITLE,
    QuickControlId.ROTATE,
    QuickControlId.PIP,
    QuickControlId.CAST,
)

/** 长视频风已被外层直接按钮覆盖的入口：上集/下集/快退/快进=底栏单行、
 *  字幕/音轨/投屏=顶栏图标簇（[StreamingTopBar]/[StreamingBottomBar]）。
 *  SPEED 不在集内=窄幅维度单列（宽幅底栏有倍速钮覆盖/窄幅 [streamingRowSlots] 无倍速
 *  槽保留），见 [quickControlsFor]。 */
private val STREAMING_STYLE_COVERED: Set<QuickControlId> = setOf(
    QuickControlId.PREV, QuickControlId.NEXT,
    QuickControlId.REW, QuickControlId.FWD,
    QuickControlId.SUBTITLE, QuickControlId.AUDIO, QuickControlId.CAST,
)

/** 「更多」面板窄幅名义值判定（复审 P1-1 阈值对齐）：底栏三处（MinimalBottomBar/
 *  StreamingBottomBar/NvPlayerBottomBar）的 BoxWithConstraints 都在**自身水平内移
 *  16dp×2 之后**判 maxWidth < 480.dp → 底栏实测窄幅阈值=**窗口宽 < 512dp**。面板
 *  BottomSheet 全宽取 LocalConfiguration.screenWidthDp 名义值时必须对齐 512——若取 480，
 *  窗口宽 ∈ [480,512) 档内「底栏窄幅（无比例/倍速钮）而面板按宽幅剔行」= MINIMAL×ASPECT /
 *  STREAMING×SPEED 的面板入口整体消失（比例/倍速面板无任何入口）。 */
internal fun nvPanelNarrowOf(screenWidthDp: Int): Boolean = screenWidthDp < 512

/** 「播放控制」组键集（spec §5.5 T8 + 2026-11-19 用户拍板方案 A：按当前样式智能去重）。
 *  两层过滤叠加：
 *  ①RMVB 键集剔除先例保持（spec §5.3）：PIP/清晰度/投屏恒剔，QUALITY 另受 hasQuality；
 *  ②样式覆盖集：当前样式外层已有直接按钮的入口从组中移除（用户点名「和外面按钮重复」），
 *    未覆盖入口保留=功能对等兜底。三样式账目（spec §3.4 功能对等表核对）：
 *  - CLASSIC：顶栏动作区+底栏覆盖全部 16 项 → 恒空（[QuickControlsSection] 组空整组隐藏）；
 *  - MINIMAL：移除 [MINIMAL_STYLE_COVERED] + ASPECT（仅宽幅——窄幅底栏无比例钮，
 *    [minimalBottomSlots] 同口径，窄幅保留）。**SPEED 必须保留**：极简右侧倍速三件套
 *    （−/数值/+）只能 ±0.1 步进/重置，八档+连续细调的任意倍速面板唯一入口就在这组；
 *  - STREAMING：移除 [STREAMING_STYLE_COVERED] + SPEED（仅宽幅——宽幅底栏有倍速钮，
 *    窄幅 [streamingRowSlots] 去倍速槽→窄幅保留：任意倍速档面板唯一入口，与 MINIMAL
 *    的 ASPECT 窄幅保留镜像对称；[ControlStyleStreaming]「倍速经顶栏更多可达」承诺由本
 *    保留兑现）。LOCK/比例/方向/镜像/旋转/PIP/信息顶底栏均无直接按钮，留组兜底。
 *
 *  [narrow]=面板宿主屏宽名义值经 [nvPanelNarrowOf]（<512，对齐底栏内移后实测阈值——
 *  面板 BottomSheet 全宽）。回调分派 [dispatchQuickControl] 与本过滤解耦：行少了自然
 *  少派，无行级硬编码。 */
internal fun quickControlsFor(isRmvb: Boolean, hasQuality: Boolean, style: NvControlStyle, narrow: Boolean): List<QuickControlId> =
    QuickControlId.entries.filter { id ->
        val rmvbExcluded = isRmvb && (id == QuickControlId.PIP || id == QuickControlId.QUALITY || id == QuickControlId.CAST)
        val qualityHidden = id == QuickControlId.QUALITY && !hasQuality
        val styleCovered = when (style) {
            NvControlStyle.CLASSIC -> true
            NvControlStyle.MINIMAL -> id in MINIMAL_STYLE_COVERED || (id == QuickControlId.ASPECT && !narrow)
            NvControlStyle.STREAMING -> id in STREAMING_STYLE_COVERED || (id == QuickControlId.SPEED && !narrow)
        }
        !rmvbExcluded && !qualityHidden && !styleCovered
    }

/** QuickControlId → 契约回调分派（T8）：与顶/底栏按钮完全同源同语义——
 *  REW/FWD 走 onSkipBy(∓skipSec×1000)（StreamingBottomBar 同式）；面板型入口
 *  （倍速/字幕/音轨/清晰度/信息）在两侧契约里本就指向各自面板开关。 */
internal fun NvControlContract.dispatchQuickControl(id: QuickControlId) {
    when (id) {
        QuickControlId.LOCK -> onLock()
        QuickControlId.PREV -> onPrev()
        QuickControlId.NEXT -> onNext()
        QuickControlId.REW -> onSkipBy(-skipSec * 1000L)
        QuickControlId.FWD -> onSkipBy(skipSec * 1000L)
        QuickControlId.SPEED -> onSpeed()
        QuickControlId.SUBTITLE -> onSubtitle()
        QuickControlId.AUDIO -> onAudio()
        QuickControlId.ASPECT -> onAspect()
        QuickControlId.ORIENTATION -> onOrientation()
        QuickControlId.MIRROR -> onVideoMirror()
        QuickControlId.ROTATE -> onVideoRotate()
        QuickControlId.PIP -> onPip()
        QuickControlId.QUALITY -> onQuality()
        QuickControlId.CAST -> onCast()
        QuickControlId.INFO -> onInfo()
    }
}
