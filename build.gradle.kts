plugins {
    // Liquid Glass Stage0（Task0）：compose 1.12.1 AAR 元数据要求 AGP >= 9.1.0；compileSdk 37 同步前移。
    // AGP 9 起内置 Kotlin 支持（builtInKotlin），不再应用 org.jetbrains.kotlin.android（KGP 显式插件
    // 会直接报错拒装）；Kotlin 选项经模块内 kotlin { compilerOptions {} } 配置
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
