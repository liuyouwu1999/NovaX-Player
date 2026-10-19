package com.novax.app.ui.policy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.novax.app.R
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.theme.NvColors

/** 政策文档种类（App 内展示与首启弹窗共用；标题为字符串资源，四语随系统）。 */
enum class PolicyDoc(@StringRes val titleRes: Int) {
    PRIVACY(R.string.policy_doc_privacy),
    AGREEMENT(R.string.policy_doc_agreement),
}

/**
 * 隐私政策/用户协议展示屏（纯文本滚动；设置-关于入口与首启弹窗共用），
 * 满足「政策可直接阅读、首页→设置→关于 = 3 次点击可达」（spec §1.4）。
 * 正文为四语字符串资源（NvPolicyText 持资源 ID），页脚固定展示
 * 「以简体中文版为准」脚注（policy_lang_disclaimer，四语同文）。
 * 顶栏/内边距/背景镜像 LicensesScreen 同款悬浮顶栏配方（decisions.md §4.5），
 * 未接线玻璃域（如弹窗复用）时 env 为 null，nvGlassSource 恒等降级。
 */
@Composable
fun NvPolicyScreen(doc: PolicyDoc, onBack: () -> Unit) {
    val sections = when (doc) {
        PolicyDoc.PRIVACY -> NvPolicyText.privacy
        PolicyDoc.AGREEMENT -> NvPolicyText.agreement
    }
    // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（政策页为页内路由，底栏仍显示）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // Liquid Glass（decisions.md §4.5）：source 挂列表滚动容器，NvTopBar 悬浮做兄弟（页面翻转配方）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：顶栏静止态隐形（同致谢页/组内页接线）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        LazyColumn(
            Modifier.fillMaxSize().nvGlassSource(glassEnv).background(NvColors.Background),
            state = listState,
            contentPadding = PaddingValues(
                start = NvSpacing.L,
                top = topInset,
                end = NvSpacing.L,
                bottom = NvSpacing.XL + glassBottom,
            ),
        ) {
            item(key = "meta") {
                Text(
                    stringResource(
                        R.string.policy_meta_updated_version,
                        NvPolicyText.LAST_UPDATED,
                        NvPolicyText.VERSION,
                    ),
                    style = NvTypography.LabelSmall,
                    color = NvColors.TextSecondary,
                )
                Spacer(Modifier.height(NvSpacing.L))
            }
            sections.forEach { s ->
                item(key = s.title) {
                    Text(stringResource(s.title), style = NvTypography.TitleMedium, color = NvColors.TextPrimary)
                    Spacer(Modifier.height(NvSpacing.S))
                    s.paragraphs.forEach { p ->
                        Text(stringResource(p), style = NvTypography.BodyMedium, color = NvColors.TextSecondary)
                        Spacer(Modifier.height(NvSpacing.S))
                    }
                    Spacer(Modifier.height(NvSpacing.L))
                }
            }
            item(key = "lang_disclaimer") {
                Text(
                    stringResource(R.string.policy_lang_disclaimer),
                    style = NvTypography.LabelSmall,
                    color = NvColors.TextSecondary,
                )
            }
        }
        NvTopBar(stringResource(doc.titleRes), onBack = onBack, scrolled = scrolled)
    }
}
