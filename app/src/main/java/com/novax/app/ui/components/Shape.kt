package com.novax.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// DESIGN-SYSTEM.md §1.4 圆角 Shape tokens
object NvShape {
    val Poster = RoundedCornerShape(12.dp)
    val Button = RoundedCornerShape(24.dp)          // 药丸
    val Dialog = RoundedCornerShape(20.dp)
    val Input = RoundedCornerShape(12.dp)
    val Badge = RoundedCornerShape(8.dp)
    val Pill = RoundedCornerShape(50)                // 2026-10-09 底部导航整胶囊(图标+文字)
    val BottomSheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val DividerHandle = RoundedCornerShape(2.dp)
}

// DESIGN-SYSTEM.md §1.3 间距 Spacing tokens（8pt 网格）
object NvSpacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
    // 2026-09-16 UI 审计 P2-16 补档：微内衬（3dp/6dp 散点归拢用；3dp 微内衬建议直接归 XS）
    val XS2 = 6.dp
}
