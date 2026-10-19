plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

import java.util.Properties

// ---- Release 签名（E4-1）：debug 证书公开发布=签名锁死单机+商店拒收+任何人可签同签名升级包。
// keystore.properties 不入库；缺失时 release 打包任务 fail-fast（配置期不拦，debug/单测不受影响）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// ---- 发版参数（E4-4）：发布新版本时只改前两个值。versionCode = 日期×100 + 同日序号×10 + ABI偏移 ----
// 同日第 n 版（n=0 为当日首版，最多 10 版/日）；ABI 偏移 arm64-v8a=1 / armeabi-v7a=2 / x86_64=3。
// 方案数学性质由 ReleaseVersionSchemeTest 锁定（跨日严格单调、2147 年内不溢出）。
// 注意：AGP 9 新 Variant API 已无 per-output 重命名能力（javap 实证），产物名维持 AGP 默认，
// 对外分发命名在归档时手工完成（docs/RELEASE.md §3）。
// v1.0.0.11 发包：nvReleaseDate=20261030、nvReleaseIndex=1（当日第 2 版，基础码 2026103010）。
// 内容 = 修复：划掉最近任务后通知栏播放条残留——此前「后台播放」开启时 onTaskRemoved
// 放行 media3 默认（在播中保持前台服务+通知），应用退出后通知残留。现对齐主流视频应用
// 口径（YouTube/MX Player）：划卡 = 暂停并停服务撤通知，无视后台播放开关；后台播放
// 设置继续只负责 HOME/锁屏/退后台继续播；返回键显式退出语义不变（exitUiHost 恒停）。
// 历史：v1.0.0.10=202610300x（大图库目录点图闪退修复）；v1.0.0.9=202610264x（锁角标热修）。
val nvVersionName = "1.0.0.11"
val nvReleaseDate = 20261030
val nvReleaseIndex = 1
val nvVersionBase: Int = run {
    // T11 评审移交：双守卫 + long 运算。Int 直乘在日期误改（多打一位/改超 21474836 年）时
    // 会静默回绕，require 拦不住；先升 long 再校验，配置期即炸而非产出错版号。
    require(nvReleaseIndex in 0..9) { "nvReleaseIndex 须在 0..9（同日最多 10 版，防与次日基础码相撞）" }
    val b = nvReleaseDate * 100L + nvReleaseIndex * 10L
    require(b + 3 <= Int.MAX_VALUE) { "versionCode 溢出：检查 nvReleaseDate/nvReleaseIndex" }
    b.toInt()
}
val nvAbiCodeOffsets = mapOf("arm64-v8a" to 1, "armeabi-v7a" to 2, "x86_64" to 3)

android {
    namespace = "com.novax.app"
    // Liquid Glass Stage0（Task0）：haze-glass 2.0.1 AAR 元数据要求 minCompileSdk=37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.novax.app"
        minSdk = 26
        targetSdk = 36
        versionCode = nvVersionBase
        versionName = nvVersionName
        // 2026-09-20 真机多点触控注入（androidTest/PinchZoomInjectTest）：
        // 无此行 AGP 不生成 <instrumentation> 声明，am instrument 找不到 runner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // C13：cast 单测跑 JVM，android.util.Log 等桩方法返回默认值（Log.w 诊断日志不再炸测试）
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // 2026-09-15 C7：开 R8 + 资源收缩（旧版全量依赖进 dex：APK 体积/冷启动/内存全劣化）。
            isMinifyEnabled = true
            isShrinkResources = true
            // E4-1：正式发布签名（keystore.properties，见文件头注释）；debug 签名发布自此作废
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // 2026-10-18 真机测试变体（真机装机验证）：debug 的可调试属性 + release 正式签名
        // ——已有设备装着 release 签名的 1.0.0.0（versionCode 2026101601），debug 签名无法覆盖
        // （签名不匹配只能卸载重装=清数据）；本变体同签名 + 版本码更高（nvReleaseIndex=1 →
        // arm64 2026101611），可直接覆盖安装且用户数据（源配置/观看历史）全保留。
        // 正式发版不受影响：发包按 RELEASE.md §2 改 nvReleaseDate，本变体不参与发布。
        create("dev") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.findByName("release")
            matchingFallbacks += listOf("debug")
        }
    }
    // 2026-09-18 双内核：libVLC 按 ABI 带 native 库（单包全 ABI ≈200MB 不可接受）——
    // 拆分为单 ABI APK：arm64-v8a（现代手机）/ armeabi-v7a（老设备）/ x86_64（模拟器）
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    // Kotlin 2.3 起 kotlinOptions DSL 移除（Task0 工具链前移）：迁移到 compilerOptions DSL
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // E4-3（发版审查）：恢复 lintVital 发布门禁（原因 AGP 配置缓存序列化 bug 整体关闭）。
        // baseline 吸收存量、新增 fatal 仍会阻断打包；若本机 AGP 复现该 bug（构建崩在
        // lint 任务序列化），回退 checkReleaseBuilds=false 并在 docs/RELEASE.md 固化手工门禁。
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }
    // 2026-10-21 四语国际化收尾（i18n 计划 Task 12）：APK 只打包 en/zh-rCN/ko/ja，
    // 剔除依赖库携带的其余语言资源（体积↓，且避免系统语言落到未翻译语言时的混排观感）。
    // DSL 选择依据（本机 javap 实证，AGP gradle-api-9.1.0）：
    //   javap -classpath gradle-api-9.1.0.jar com.android.build.api.dsl.ApplicationAndroidResources | grep -i localeFilters
    //   → public abstract java.util.Set<java.lang.String> getLocaleFilters();
    // 命中 → 用 androidResources.localeFilters（defaultConfig.resourceConfigurations 在 9.1 已弃用）。
    // 记法红线：此处必须用资源限定符记法 zh-rCN（locales_config.xml 里才是 BCP47 的 zh-CN），
    // 写成 zh-CN 会让全部中文资源静默消失且构建不报错（存活核验见 task-12 Step 3）。
    androidResources {
        localeFilters.addAll(listOf("en", "zh-rCN", "zh-rTW", "ko", "ja"))   // 2026-10-22 增繁中（记法同红线：资源限定符 zh-rTW）
    }
}

// E4-4：release 按 ABI 拆分 versionCode（defaultConfig 只有基础码，ABI 偏移在此叠加）。
// 配置缓存安全的 androidComponents API（勿用已移除的 applicationVariants；
// defaultConfig.versionName 在此作用域不可解析，故用文件顶部 nvVersionName——本任务无重命名逻辑）。
androidComponents {
    onVariants { variant ->
        // release/dev 两个签名变体都按 ABI 叠加版本码（dev 覆盖安装 release 需更高版本码）
        if (variant.name != "release" && variant.name != "dev") return@onVariants
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull()?.identifier
            // abi 可空：Map.get 不接受可空键，let 包一层
            output.versionCode.set(nvVersionBase + (abi?.let { nvAbiCodeOffsets[it] } ?: 0))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // xpp3（smbj/jcifs 传递依赖）与 android 平台 org.xmlpull 冲突，R8 拒绝；平台自带实现，直接排除
    configurations.all {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xmlpull", module = "xmlpull")
    }
    implementation("com.belerweb:pinyin4j:2.5.1")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    // 隐私锁（Task 12）：BiometricPrompt 指纹解锁 + SDK30+ 设备凭据忘码流。
    // 传递依赖 androidx.fragment 提供 FragmentActivity（BiometricPrompt 硬性宿主要求）
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // 图片加载（NvPosterCard）
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")

    // C8: 播放内核
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // 2026-09-29 FFmpeg 音频软解扩展：AC3/E-AC3(JOC)/DTS(DTS-HD)/TrueHD 在主播放器出声，
    // 不再触发无声横幅/软解记忆链（NvRenderersFactory 显式注册 FfmpegAudioRenderer）
    implementation(project(":media3-ffmpeg-ext"))

    // 2026-09-20 双内核兜底 + 升 VLC4：RM/RMVB 等 ExoPlayer 不支持的私有容器走 libVLC 兜底
    //（路由见 PlayerActivity.startPlayback / RmvbPlayerActivity）。升级 4.0.0-eap29 的动因：
    // VLC4 时钟子系统（vlc_clock）重写，变速（setRate）过渡不再触发 aout 冲刷/插静音/
    // 时钟重置（VLC3 已知问题 videolan/vlc-android#5254）。轨道 API 有破坏性变更，
    // 适配见 RmvbPlayerActivity（getTracks/selectTrack 体系）。
    implementation("org.videolan.android:libvlc-all:4.0.0-eap29")

    // 媒体库本地索引（KSP 注解处理生成 DAO 实现）
    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")

    // 键值设置存储（C 后续设置页）
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // 文件层（WebDAV/网络流）
    implementation("com.squareup.okhttp3:okhttp:5.1.0")

    // C8 §3.1 NAS 源：SMB（smbj）；WebDAV 走自研 OkHttp 实现（WebDavClient）。
    // 2026-09-28 删除 sardine-android：死依赖（全树零 import，审计 P2-6）；Digest 认证
    // 2026-09-29 已由自研 DigestAuthInterceptor 落地（RFC2617 MD5/qop=auth），无需再加回
    implementation("com.hierynomus:smbj:0.14.0")
    // smbj 依赖 slf4j：Android 上必须绑定 slf4j-android，否则 NoClassDefFoundError
    implementation("org.slf4j:slf4j-android:1.7.36")

    // 2026-09-29 SFTP 源（sshj，与 smbj 同作者 hierynomus；0.38.0 为该库最终稳定线）：
    // 读路径复用 SmbRandomReadStream 管线（SftpReadHandle 适配 RandomReadHandle）。
    // minSdk 26 自带 java.time/API26+，sshj 无需 coreLibraryDesugaring；传递依赖
    // bouncycastle/eddsa 与现有树无冲突（编译/打包核实，见 SftpClient kdoc）
    implementation("com.hierynomus:sshj:0.38.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // SMB 共享枚举（SmbClient.listShares 向导「获取共享列表」）：jcifs-ng 独立短连接，与 smbj 播放池互补
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // C13 投屏：jUPnP（Cling 继任者）只负责 SSDP 发现与设备注册表；控制走自研 SOAP。
    implementation("org.jupnp:org.jupnp.android:3.0.5")
    // jUPnP 官方要求自带 Jetty 9（POM 里 optional 不传递，缺了启动即 ClassNotFoundException）：
    // stream 传输/GENA 都跑在它上面。2026-09-28 9.4.53 → 9.4.58.v20250814（同 9.4.x 分支，
    // 修 CVE-2024-13009 / CVE-2025-5115，审计 P3）
    implementation("org.eclipse.jetty:jetty-server:9.4.58.v20250814")
    implementation("org.eclipse.jetty:jetty-servlet:9.4.58.v20250814")
    implementation("org.eclipse.jetty:jetty-client:9.4.58.v20250814")

    // C6 §3.1 单测：SubtitleMatcher
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")   // JVM 单测提供真实 XmlPullParserFactory（android.jar 为桩）
    // JVM 单测里 android.jar 的 org.json 是桩（isReturnDefaultValues 静默返回默认值），
    // 补真实实现——与 kxml2 对 XmlPullParser 的处理同型（仅测试期，零运行时影响）
    testImplementation("org.json:json:20240303")
    testImplementation("org.slf4j:slf4j-simple:1.7.36")

    // 2026-09-20 真机多点触控注入（RMVB 缩放验证专用，见 androidTest/PinchZoomInjectTest）：
    // UiAutomation 多指 MotionEvent 通道；测试 APK 独立安装，不动宿主数据
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Liquid Glass Stage0（Task0）：Haze 2.0.1（haze 核心 + blur 后端 + glass 折射样式）
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")
}

// E4-1 fail-fast：release 打包任务执行期校验签名配置在位（CC 安全；taskGraph.whenReady 在
// 配置缓存命中时不会重执行，不可用作守卫）。缺 keystore.properties 时 AGP 会无声产出
// 未签名包——必须在打包前拦下，禁止任何形式的回落。
tasks.matching { it.name.startsWith("package") && it.name.contains("Release") }.configureEach {
    // 实证（Gradle 9.3.1）：doFirst 闭包若引用脚本顶层属性，会连脚本对象一起捕获，
    // CC 报 "cannot serialize Gradle script object references"。故在 configureEach 体内
    // （仅配置期执行，不参与序列化）把探测结果拷进局部 val，doFirst 只捕获这个 Boolean。
    val signingConfigured = keystorePropsFile.exists()
    doFirst("verifyReleaseSigning") {
        // 主保障是 Gradle 配置缓存对配置期文件探测的失效跟踪（keystore.properties 增删会使
        // CC 失效并重新执行配置）；捕获的 Boolean 是旧版 Gradle 下的 fail-loud 兜底
        // （见 docs/RELEASE.md §1）
        check(signingConfigured) {
            "缺少 NovaX/keystore.properties（release 签名）。见 docs/RELEASE.md「签名」节——不得以 debug 证书公开发布。"
        }
    }
}
