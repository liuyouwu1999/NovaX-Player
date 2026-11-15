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
 * 「更多」面板「播放控制」组行键（spec 2026-11-15 §5.5，T8）：功能全集兜底——
 * 16 入口与 §3.4 功能对等总表一一对应（暂停/播放与手势重复，不入组）。
 */
enum class QuickControlId { LOCK, PREV, NEXT, REW, FWD, SPEED, SUBTITLE, AUDIO, ASPECT, ORIENTATION, MIRROR, ROTATE, PIP, QUALITY, CAST, INFO }

/** 「播放控制」组键集（spec §5.5）：RMVB 按 §5.3 键集剔除 PIP/清晰度/投屏；QUALITY 仅 Jellyfin 有质量档时。 */
internal fun quickControlsFor(isRmvb: Boolean, hasQuality: Boolean): List<QuickControlId> =
    QuickControlId.entries.filter { id ->
        val rmvbExcluded = isRmvb && (id == QuickControlId.PIP || id == QuickControlId.QUALITY || id == QuickControlId.CAST)
        val qualityHidden = id == QuickControlId.QUALITY && !hasQuality
        !rmvbExcluded && !qualityHidden
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
