pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // E4-3（发版审查）：移除 jitpack——sardine-android 已于 2026-09-28 删除，纯残留供应链面
    }
}
rootProject.name = "NovaX"
include(":app")
include(":media3-ffmpeg-ext")
