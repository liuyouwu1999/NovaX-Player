package com.novax.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Task 13 · 应用内语言开关（跟随系统 + 应用内覆盖；2026-10-22 起五语：简中/繁中/英/韩/日）。
 *
 * 双路径（spec 评审要点 1）：
 * - **API 33+**：系统 per-app locale 官方接口。[set] 走 [LocaleManager.setApplicationLocales]
 *   （auto = 空列表 = 清覆盖回归系统语言），系统自动重建界面；[wrap] 必须**原样返回**——
 *   系统已把 per-app locale 编入 base context 配置，二次 createConfigurationContext 会与系统打架。
 * - **API 32-**：自实现兼容。[wrap] 用 createConfigurationContext 覆写 locale；
 *   [set] 写 DataStore + 同步更新缓存（重建后 wrap 读缓存零阻塞，缓存一致性要点 4），
 *   activity.recreate() 由调用方触发。
 *
 * 偏好存 [novaxUiPrefs]（与返回键退出/通知权限标记同一 DataStore 文件），键 [KEY]，默认 auto。
 *
 * 语言自名标签（中文/English/한국어/日本語）为白名单专名不翻译——UI 侧字面量，与本对象无关。
 */
object NvAppLanguage {

    /** 选项全集（auto 恒首）；顺序即设置对话框展示顺序。2026-10-22 增繁体中文 zh-TW */
    val OPTIONS = listOf(AUTO, "zh", "zh-TW", "en", "ko", "ja")

    const val AUTO = "auto"

    /** DataStore 键名（存 novax_ui_prefs 文件，复用 MainActivity 的 novaxUiPrefs 委托） */
    const val KEY = "novax_app_language"

    /** DataStore 键对象（设置页 vm.language StateFlow 读同一键） */
    val PREF_KEY: Preferences.Key<String> = stringPreferencesKey(KEY)

    /**
     * 偏好 → 语言标签。null = 跟随系统（不包装）。
     * "zh" 规格化为 zh-CN（命中 values-zh-rCN 目录）；"zh-TW" 命中 values-zh-rTW；
     * "en"/"ko"/"ja" 同名；auto 与一切未知值（安全侧）→ null。
     */
    fun resolveTag(preference: String): String? = when (preference) {
        "zh" -> "zh-CN"
        "zh-TW" -> "zh-TW"
        "en" -> "en"
        "ko" -> "ko"
        "ja" -> "ja"
        else -> null
    }

    /** 进程内偏好缓存：attachBaseContext（主线程、无协程基建）与 wrap 热路径直读，不反复走 DataStore */
    @Volatile
    private var cached: String? = null

    /**
     * 当前偏好（auto/zh/en/ko/ja）。
     *
     * 动机：32- 路径在 attachBaseContext 里取偏好——该回调在主线程且早于任何协程基础设施，
     * 无法挂起读 DataStore，只能 runBlocking 读一次。量级：novax_ui_prefs 是 DataStore 单键小
     * XML 文件（几行文本），首帧一次性磁盘读为毫秒级；读毕入 [cached]，进程内不再二次 IO。
     * 损坏（IO 异常等）runCatching 回 auto（安全侧 = 跟随系统）。
     */
    fun preference(context: Context): String {
        cached?.let { return it }
        val read = runCatching {
            runBlocking { context.novaxUiPrefs.data.first()[PREF_KEY] }
        }.getOrNull() ?: AUTO
        cached = read
        return read
    }

    /**
     * attachBaseContext 用：按应用内语言包装 base context。
     * 33+ 原样返回（系统已应用 per-app locale，评审要点 1）；
     * 32- 且非 auto → createConfigurationContext(Locale(tag))；auto → 原样。
     */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = resolveTag(preference(context)) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    /**
     * 用户选择入口（选中即生效）。
     * 33+ → LocaleManager.setApplicationLocales（auto → 空列表 = 清覆盖），系统自动重建；
     * 32- → 写 DataStore + 更新缓存，activity.recreate() 由调用方在同一协程内、本函数返回后触发
     * （缓存已先行更新，recreate 后 attachBaseContext 读缓存立即生效）。
     * 未知值按 auto 落盘（与 resolveTag 安全侧同口径）。
     */
    suspend fun set(context: Context, preference: String) {
        val safe = if (preference in OPTIONS) preference else AUTO
        cached = safe   // 缓存一致性（要点 4）：写盘前先更缓存，32- 重建零阻塞、服务侧取词即时
        context.novaxUiPrefs.edit { it[PREF_KEY] = safe }
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            // LocaleListCompat 在 API 24+ 恒包装平台 LocaleList（minSdk 26），unwrap 强转安全；
            // auto（resolveTag=null）→ 空列表 = 清除覆盖、回归系统语言
            val locales = resolveTag(safe)
                ?.let { LocaleListCompat.forLanguageTags(it).unwrap() as android.os.LocaleList }
                ?: android.os.LocaleList.getEmptyLocaleList()
            manager.setApplicationLocales(locales)
        }
    }
}
