package com.novax.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// DESIGN-SYSTEM.md §1.5 动效 Motion tokens
object NvMotion {
    const val DurationFast = 150
    const val DurationNormal = 200
    const val DurationSlow = 250
    val CurveDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val CurveAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val PressScale = 0.96f
    const val ShimmerDuration = 1200
}

// DESIGN-SYSTEM.md §1.2 字体 Typography tokens
object NvTypography {
    val TitleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    )
    val TitleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
    )
    val BodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val BodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val LabelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val LabelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val LabelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}
