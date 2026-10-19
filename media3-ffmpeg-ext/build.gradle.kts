plugins {
    id("com.android.library")
}

android {
    namespace = "com.novax.media3.ffmpeg"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        // E4-5：JNI 类显式 keep，随库消费方传递（见 consumer-rules.pro 头注释）
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // E4-3（发版审查）：与 app 同口径恢复 lintVital 发布门禁（模块自身 media3 unstable API 用法已标 @UnstableApi）。
    // baseline 吸收存量、新增 fatal 仍会阻断打包；若本机 AGP 复现该 bug（构建崩在
    // lint 任务序列化），回退 checkReleaseBuilds=false 并在 手工 lint 门禁兜底。
    lint {
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // api：本模块类签名直接暴露 media3 类型（FfmpegAudioRenderer 等）
    api("androidx.media3:media3-exoplayer:1.8.0")
    api("androidx.media3:media3-common:1.8.0")
    // 上游 r2.19.1 Java 源使用 androidx.annotation.Nullable；media3 1.8.0 将
    // androidx.annotation:annotation 声明为 runtime scope（不进编译类路径），模块自身
    // 编译需显式提供（仅编译期需要，运行时由 media3 的 runtime 依赖带入）。
    compileOnly("androidx.annotation:annotation:1.3.0")
}
