package com.novax.app.ui.library

internal fun posterColumns(preference: String?, widthDp: Float): Int =
    preference?.toIntOrNull()?.takeIf { it in 2..4 } ?: when {
        widthDp < 360f -> 2
        widthDp < 600f -> 3
        widthDp < 840f -> 4
        else -> 5
    }
