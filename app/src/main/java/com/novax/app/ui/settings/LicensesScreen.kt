package com.novax.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.ui.components.NvSettingsCard
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.theme.NvColors

/**
 * C10 §6 · 开源致谢页（静态）。
 * 法务注：smbj / ExoPlayer / Nova / OkHttp / Jetty 为 Apache 2.0（保留版权与许可声明）；
 * jcifs-ng 与 libVLC 为 LGPL 2.1、jUPnP 为 CDDL 1.0，均随应用分发——
 * 按各许可证要求完整提供协议文本与源码获取方式（见各条目与页脚说明）。
 * 2026-09-28 修订（审计 P3）：jcifs-ng 2.1.10 实际已随 APK 分发并被 SmbClient 使用，
 * 原文「未随应用分发、仅致谢」失实；补齐 libVLC / jUPnP / Jetty / OkHttp / SLF4J 条目。
 * 2026-10-09 修订（Task 11）：按 releaseRuntimeClasspath 实测增删——补 sshj 0.38.0
 * （SftpClient 实用）与 BouncyCastle 1.79（bcprov-jdk18on，sshj 传递依赖）；
 * 删 Material Symbols（应用内无字体实物，图标实由 material-icons-extended 提供，并入组条目）；
 * 组条目按 gradle 实测补版本并并入 Coroutines 1.10.2。
 * 2026-10-09 修订（批次二评审点名）：补 eddsa 0.3.0（ISC，sshj 的 Ed25519 签名算法传递依赖）。
 */

/**
 * 贡献描述/含中文限定语的组合条目名走资源（i18n）；纯专名条目名（pinyin4j 等）为白名单原文保留。
 * name 兼作 LazyColumn 稳定 key（组合条目存短专名 key，屏显名走 nameRes）。
 */
internal data class LicenseEntry(
    val name: String,
    val license: String,
    val contributionRes: Int,
    val nameRes: Int = 0,   // 非 0 = 屏显名用该资源（第 16 条组合条目，原名含中文限定语）
)

internal val licenseEntries = listOf(
    LicenseEntry("pinyin4j 2.5.1", "BSD", R.string.settings_license_pinyin4j),
    LicenseEntry(
        "Nova Video Player / AOS-FP", "Apache License 2.0",
        R.string.settings_license_nova,
    ),
    LicenseEntry(
        "Media3 / ExoPlayer", "Apache License 2.0",
        R.string.settings_license_media3,
    ),
    LicenseEntry(
        "libVLC 4.0.0-eap29", "LGPL 2.1",
        R.string.settings_license_libvlc,
    ),
    LicenseEntry(
        "FFmpeg n4.4.4", "LGPL 2.1",
        R.string.settings_license_ffmpeg,
    ),
    LicenseEntry(
        "smbj 0.14.0", "Apache License 2.0",
        R.string.settings_license_smbj,
    ),
    LicenseEntry(
        "sshj 0.38.0", "Apache License 2.0",
        R.string.settings_license_sshj,
    ),
    LicenseEntry(
        "eddsa 0.3.0", "ISC",
        R.string.settings_license_eddsa,
    ),
    LicenseEntry(
        "BouncyCastle 1.79", "MIT",
        R.string.settings_license_bouncycastle,
    ),
    LicenseEntry(
        "jcifs-ng 2.1.10", "LGPL 2.1",
        R.string.settings_license_jcifs,
    ),
    LicenseEntry(
        "SLF4J Android 1.7.36", "MIT",
        R.string.settings_license_slf4j,
    ),
    LicenseEntry(
        "OkHttp 5.1.0", "Apache License 2.0",
        R.string.settings_license_okhttp,
    ),
    LicenseEntry(
        "jUPnP 3.0.5", "CDDL 1.0",
        R.string.settings_license_jupnp,
    ),
    LicenseEntry(
        "Eclipse Jetty 9.4.58", "Apache License 2.0",
        R.string.settings_license_jetty,
    ),
    LicenseEntry(
        "Haze 2.0.1", "Apache License 2.0",
        R.string.settings_license_haze,
    ),
    LicenseEntry(
        "Kotlin / AndroidX / Compose stack", "Apache License 2.0",
        R.string.settings_license_stack,
        nameRes = R.string.settings_license_stack_name,
    ),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    // Liquid Glass（spec §9）：列表底 padding 追加玻璃底栏高度（致谢页为页内路由，底栏仍显示）
    val glassBottom = com.novax.app.ui.glass.rememberNvGlassBottomPadding()
    // Liquid Glass（decisions.md §4.5）：source 挂列表滚动容器，NvTopBar 悬浮做兄弟（页面翻转配方）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：顶栏静止态隐形（同设置组内页接线）
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
            item {
                Text(
                    stringResource(R.string.settings_licenses_header),
                    style = NvTypography.BodyMedium,
                    color = NvColors.TextSecondary,
                    modifier = Modifier.padding(bottom = NvSpacing.XL),
                )
            }
            licenseEntries.forEach { entry ->
                item(key = entry.name) {
                    NvSettingsCard(title = null) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                if (entry.nameRes != 0) stringResource(entry.nameRes) else entry.name,
                                style = NvTypography.BodyMedium,
                                color = NvColors.TextPrimary,
                            )
                            Text(
                                entry.license,
                                style = NvTypography.LabelMedium,
                                color = NvColors.Primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                stringResource(entry.contributionRes),
                                style = NvTypography.LabelMedium,
                                color = NvColors.TextSecondary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(NvSpacing.L))
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_licenses_footer),
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                )
            }
        }
        NvTopBar(stringResource(R.string.settings_open_licenses), onBack = onBack, scrolled = scrolled)
    }
}
