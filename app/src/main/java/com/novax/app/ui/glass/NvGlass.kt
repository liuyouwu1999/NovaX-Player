package com.novax.app.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 毛玻璃能力分档（spec §6）：FULL=折射+模糊+高光(33+) / BLUR=真实模糊(31-32) / SCRIM=半透明降级(26-30)。
 * REFRACTION_AVAILABLE 由 Stage 0 判定写入（分支 A=true，分支 B=false，false 时 FULL 折叠为 BLUR 效果）。
 */
const val REFRACTION_AVAILABLE = true   // ← Task 0 判定后如走分支 B 改 false（连同本行注释更新）

enum class NvGlassCapability { FULL, BLUR, SCRIM }

fun resolveGlassCapability(sdkInt: Int): NvGlassCapability = when {
    sdkInt >= 33 -> NvGlassCapability.FULL
    sdkInt >= 31 -> NvGlassCapability.BLUR
    else -> NvGlassCapability.SCRIM
}

fun effectiveCapability(cap: NvGlassCapability): NvGlassCapability =
    if (cap == NvGlassCapability.FULL && !REFRACTION_AVAILABLE) NvGlassCapability.BLUR else cap

/** 域级玻璃环境：HazeState 仅 FULL/BLUR 档非空（SCRIM 档零 Haze 依赖路径）。 */
class NvGlassEnv(
    val capability: NvGlassCapability,
    val hazeState: Any?,   // dev.chrisbanes.haze.HazeState，以 Any? 承载避免 SCRIM 档运行时触碰 haze 类
) {
    val blurCapable: Boolean get() = hazeState != null
}

val LocalNvGlassEnv = staticCompositionLocalOf<NvGlassEnv?> { null }
