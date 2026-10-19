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
import androidx.compose.ui.unit.dp
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

internal data class LicenseEntry(val name: String, val license: String, val contribution: String)

internal val licenseEntries = listOf(
    LicenseEntry("pinyin4j 2.5.1", "BSD", "离线中文拼音索引与标题自然排序。项目：https://github.com/belerweb/pinyin4j"),
    LicenseEntry(
        "Nova Video Player / AOS-FP", "Apache License 2.0",
        "NovaX 的播放底座与文件核心源自 Nova Video Player 开源项目，感谢原项目及社区的长期贡献。",
    ),
    LicenseEntry(
        "Media3 / ExoPlayer", "Apache License 2.0",
        "android.media3 提供解码、轨道选择、字幕渲染与媒体会话能力，是本应用播放内核的基石。",
    ),
    LicenseEntry(
        "libVLC 4.0.0-eap29", "LGPL 2.1",
        "org.videolan.android:libvlc-all —— RM/RMVB 软解与转码抽帧播放内核，随应用分发。" +
            "按 LGPL 2.1 要求完整提供协议文本与源码获取方式。项目：https://code.videolan.org/videolan/vlc-android",
    ),
    LicenseEntry(
        "FFmpeg n4.4.4", "LGPL 2.1",
        "FFmpeg（libavcodec/libavutil/swresample，n4.4.4 静态编入 libffmpegext.so）—— " +
            "AC3/E-AC3/DTS/TrueHD 音频软解，LGPL 2.1 配置构建（未启用任何 GPL 组件），" +
            "按 LGPL 2.1 要求完整提供协议文本与源码获取方式。项目：https://ffmpeg.org",
    ),
    LicenseEntry(
        "smbj 0.14.0", "Apache License 2.0",
        "com.hierynomus:smbj —— SMB2/3 客户端协议库，为 NAS 直读与快速拖动提供连接复用与偏移读取能力。",
    ),
    LicenseEntry(
        "sshj 0.38.0", "Apache License 2.0",
        "com.hierynomus:sshj —— SFTP 客户端协议库，承载 SFTP 源的目录浏览与随机读播放" +
            "（读路径复用 SMB 管线）。项目：https://github.com/hierynomus/sshj",
    ),
    LicenseEntry(
        "eddsa 0.3.0", "ISC",
        "net.i2p.crypto:eddsa —— sshj 的 Ed25519 签名算法传递依赖。",
    ),
    LicenseEntry(
        "BouncyCastle 1.79", "MIT",
        "org.bouncycastle:bcprov-jdk18on —— sshj 传递依赖的加密算法底座（SFTP 握手与密钥交换）。" +
            "项目：https://www.bouncycastle.org",
    ),
    LicenseEntry(
        "jcifs-ng 2.1.10", "LGPL 2.1",
        "eu.agno3.jcifs:jcifs-ng —— SMB 客户端实现，用于共享枚举（向导「获取共享列表」独立短连接），随应用分发。" +
            "按 LGPL 2.1 要求完整提供协议文本与源码获取方式。项目：https://github.com/AgNO3/jcifs-ng",
    ),
    LicenseEntry(
        "SLF4J Android 1.7.36", "MIT",
        "org.slf4j:slf4j-android —— 日志门面（SMB 协议栈依赖）。项目：https://www.slf4j.org",
    ),
    LicenseEntry(
        "OkHttp 5.1.0", "Apache License 2.0",
        "com.squareup.okhttp3 —— HTTP 客户端，承载 WebDAV 与网络流访问。项目：https://github.com/square/okhttp",
    ),
    LicenseEntry(
        "jUPnP 3.0.5", "CDDL 1.0",
        "org.jupnp:org.jupnp.android —— DLNA/UPnP 设备发现与控制。项目：https://github.com/jupnp/jupnp",
    ),
    LicenseEntry(
        "Eclipse Jetty 9.4.58", "Apache License 2.0",
        "org.eclipse.jetty —— 投屏本地流服务器（jUPnP 流传输/GENA 底层）。项目：https://github.com/jetty/jetty.project",
    ),
    LicenseEntry(
        "Haze 2.0.1", "Apache License 2.0",
        "dev.chrisbanes.haze（haze / haze-blur / haze-glass）—— 液态玻璃材质渲染库" +
            "（模糊、边缘折射与高光，2026-10 界面玻璃化全程使用）。项目：https://github.com/chrisbanes/haze",
    ),
    LicenseEntry(
        "Kotlin 2.2.20 / Coroutines 1.10.2 / AndroidX（Core 1.17.0 · Lifecycle 2.9.4 · " +
            "Activity Compose 1.11.0 · Navigation Compose 2.9.5） / Jetpack Compose（BOM 2026.09.00，含 Material Icons）" +
            " / DataStore 1.1.7 / Room 2.8.2 / Coil 3.3.0",
        "Apache License 2.0",
        "现代安卓开发全栈：语言与协程、AndroidX 基础组件（应用首页/生命周期/导航）、声明式 UI 与图标" +
            "（material-icons-extended）、键值与关系型持久化、图片加载。",
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
                    "NovaX 基于以下优秀的开源项目构建，谨此致谢——",
                    style = NvTypography.BodyMedium,
                    color = NvColors.TextSecondary,
                    modifier = Modifier.padding(bottom = NvSpacing.XL),
                )
            }
            licenseEntries.forEach { entry ->
                item(key = entry.name) {
                    NvSettingsCard(title = null) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(entry.name, style = NvTypography.BodyMedium, color = NvColors.TextPrimary)
                            Text(
                                entry.license,
                                style = NvTypography.LabelMedium,
                                color = NvColors.Primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                entry.contribution,
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
                    "各项目完整协议文本见 https://www.apache.org/licenses/LICENSE-2.0 等官方源。\n" +
                        "NovaX 站在这些巨人的肩膀上 —— 感谢开源社区。",
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                )
            }
        }
        NvTopBar("开源致谢", onBack = onBack, scrolled = scrolled)
    }
}
