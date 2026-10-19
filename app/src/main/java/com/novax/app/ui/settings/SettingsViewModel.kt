package com.novax.app.ui.settings

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novax.app.NvAppLanguage
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.scanner.NvScannerHolder
import com.novax.app.novaxUiPrefs
import com.novax.app.player.putPlayerPref
import com.novax.app.player.playerPrefsFlow
import com.novax.app.player.resetPlayerPrefsToDefaults
import com.novax.app.player.setLeftSlowmoEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * C10 设置页 ViewModel（WS4）。
 * 单一数据源：全部键在 novax_player_prefs（Context.playerPrefsStore，player/PlayerPrefs.kt 唯一委托）；
 * 「返回键直接退出」沿用 MainActivity 既有 novax_ui_prefs 文件同一键（C11 §5.3 注记：WS4 读写同文件同键）。
 * ui.files 的 novax_settings（battery_optimization_asked）不属于设置键，重置时不触碰。
 */

/** 「返回键直接退出」键（存 novax_ui_prefs，与 MainActivity KEY_BACK_EXIT 同名同文件） */
val KEY_UI_BACK_EXIT = booleanPreferencesKey("novax_ui_back_exit")

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app.applicationContext

    /** novax_player_prefs 全量快照；null = DataStore 未加载完成（UI 回退默认值） */
    val prefs: StateFlow<Preferences?> = appContext.playerPrefsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 「返回键直接退出」（novax_ui_prefs / novax_ui_back_exit，消费：MainActivity BackHandler） */
    val backExit: StateFlow<Boolean> = appContext.novaxUiPrefs.data
        .map { it[KEY_UI_BACK_EXIT] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 应用内语言（novax_ui_prefs / novax_app_language，auto+四语；Task 13）。
     * 行副标题/对话框选中态的显示源；写入端在 UI 直接调 NvAppLanguage.set（33+ LocaleManager /
     * 32- 写盘+缓存），recreate 须与 set 同协程保序，故不经本 vm 中转。
     */
    val language: StateFlow<String> = appContext.novaxUiPrefs.data
        .map { it[NvAppLanguage.PREF_KEY] ?: NvAppLanguage.AUTO }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NvAppLanguage.AUTO)

    /** 观看历史条数（隐私组值摘要；清除后 Room Flow 自动归零） */
    val historyCount: StateFlow<Int> = MediaDb.get(appContext).watchHistoryDao()
        .observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ---------- C12 媒体库组 ----------

    /** 全部已配源（各源「使用刮削」开关列表，写 SourceEntity.scrapeEnabled） */
    val sources: StateFlow<List<SourceEntity>> = MediaDb.get(appContext).sourceDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 任一源扫描中（「重新扫描媒体库」行内 16dp 圈；实时进度为准） */
    val scanning: StateFlow<Boolean> = NvScannerHolder.get(appContext).progress
        .map { live -> live.values.any { it.status == ScanStatus.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 自动扫描新源（novax_scan_autoscan，默认开；消费：FilesViewModel 保存源后触发扫描） */
    val libAutoScan: StateFlow<Boolean> = appContext.playerPrefsFlow()
        .map { it[KEY_LIB_AUTO_SCAN] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 刮削增强全局总开关（novax_lib_scrape_global，默认关；二期生效） */
    val libScrapeGlobal: StateFlow<Boolean> = appContext.playerPrefsFlow()
        .map { it[KEY_LIB_SCRAPE_GLOBAL] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 逐源「使用刮削」开关写入端（sources.scrapeEnabled，Room Flow 自动回显） */
    fun setSourceScrape(sourceId: Long, enabled: Boolean) = viewModelScope.launch {
        runCatching {
            val dao = MediaDb.get(appContext).sourceDao()
            dao.getById(sourceId)?.let { dao.update(it.copy(scrapeEnabled = enabled)) }
        }
    }

    /** 「重新扫描媒体库」（C12 §二.1 T2）：全部源串行重扫 */
    fun rescanLibrary() = viewModelScope.launch {
        val sources = runCatching { MediaDb.get(appContext).sourceDao().getAll() }.getOrDefault(emptyList())
        if (sources.isNotEmpty()) NvScannerHolder.get(appContext).scanAll(sources)
    }

    /** 「立即刮削全部已启用源」（2026-09-15 TMDB 刮削真实现）：手动触发，忽略总开关 */
    fun scrapeEnabledSourcesNow() = viewModelScope.launch {
        val enabledIds = runCatching {
            MediaDb.get(appContext).sourceDao().getAll().filter { it.scrapeEnabled }.map { it.id }
        }.getOrDefault(emptyList())
        if (enabledIds.isNotEmpty()) com.novax.app.data.scrape.NvScraper.get(appContext).scrapeAll(enabledIds)
    }

    /** 通用单键写入（开关/Chip 点击即写，消费方经 Flow 自动响应） */
    fun <T> set(key: Preferences.Key<T>, value: T) =
        viewModelScope.launch { appContext.putPlayerPref(key, value) }

    /** 左半屏慢放写入端（novax_left_slowmo，novax_player_prefs） */
    fun setLeftSlowmo(enabled: Boolean) =
        viewModelScope.launch { appContext.setLeftSlowmoEnabled(enabled) }

    /** 返回键直接退出写入端（novax_ui_prefs / novax_ui_back_exit，C11 R1/R2 消费） */
    fun setBackExit(enabled: Boolean) = viewModelScope.launch {
        appContext.novaxUiPrefs.edit { it[KEY_UI_BACK_EXIT] = enabled }
    }

    /** 隐私：清除观看历史（WS2 Room watch_history.clearAll；首页经 Room Flow 自动刷新） */
    fun clearHistory(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { MediaDb.get(appContext).watchHistoryDao().clearAll() }
        onDone()
    }

    /** 隐私：重置所有设置 = 清 novax_player_prefs + novax_ui_prefs 复位；历史/收藏/电池标记保留 */
    fun resetAll(onDone: () -> Unit) = viewModelScope.launch {
        appContext.resetPlayerPrefsToDefaults()
        appContext.novaxUiPrefs.edit { it[KEY_UI_BACK_EXIT] = false }
        onDone()
    }

    /** 缓存大小（应用 cache 目录递归求和；异步计算，未出值显示「计算中…」） */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { dirSize(appContext.cacheDir) }.getOrDefault(0L)
    }

    /**
     * 清除缓存：删除 cache 目录内容并返回清除字节数。
     * 2026-09-15 修复：缩略图文件在 cacheDir/thumbs，旧实现只删文件不清 DB 引用——
     * media_items.thumbnailPath 指向已删文件且 thumbState=DONE 永不重抽 → 全库封面永久失效。
     * 现删除后批量 resetThumb（清 thumbnailPath + 复位 NONE，复用 DAO 唯一写点）；
     * 封面随后按「滚动到可见项」渐进重抽（诚实语义：非立即全量，不阻塞 UI）。
     */
    fun clearCache(onDone: (Long) -> Unit) = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) {
            val size = runCatching { dirSize(appContext.cacheDir) }.getOrDefault(0L)
            runCatching { appContext.cacheDir.deleteRecursively() }
            runCatching {
                val dao = MediaDb.get(appContext).mediaItemDao()
                dao.allThumbRefs().forEach { dao.resetThumb(it.id) }
                // 海报文件在 cacheDir/scrape_posters，随目录一并删除：引用同步清空防悬空破图，
                // 下次刮削自愈重下（审核 P1-7）
                dao.clearAllScrapePosterRefs()
            }
            size
        }
        onDone(freed)
    }

    private fun dirSize(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
