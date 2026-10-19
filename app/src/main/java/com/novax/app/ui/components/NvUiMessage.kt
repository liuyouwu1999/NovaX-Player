package com.novax.app.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * ViewModel/UI 状态层文案载体（i18n spec §1.3）：四语化后状态层不再拼接中文串。
 * count 非空 = res 指向 <plurals>，用 pluralStringResource 解析（spec §1.3，勿用 stringResource）。
 */
data class UiMessage(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
    val count: Int? = null,
)

@Composable
fun UiMessage.text(): String =
    if (count == null) stringResource(res, *args.toTypedArray())
    else pluralStringResource(res, count, *args.toTypedArray())

fun UiMessage.resolve(context: Context): String =
    if (count == null) context.getString(res, *args.toTypedArray())
    else context.resources.getQuantityString(res, count, *args.toTypedArray())
