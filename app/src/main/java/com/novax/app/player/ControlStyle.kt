package com.novax.app.player

/** 控制层样式（spec 2026-11-15 §3）：CLASSIC=经典（默认） MINIMAL=极简 STREAMING=长视频风。 */
enum class NvControlStyle { CLASSIC, MINIMAL, STREAMING }

/** DataStore 原始值解析；未知/null/空一律回退 CLASSIC（fail-safe，spec §6）。 */
fun parseControlStyle(raw: String?): NvControlStyle = when (raw) {
    "minimal" -> NvControlStyle.MINIMAL
    "streaming" -> NvControlStyle.STREAMING
    else -> NvControlStyle.CLASSIC
}
