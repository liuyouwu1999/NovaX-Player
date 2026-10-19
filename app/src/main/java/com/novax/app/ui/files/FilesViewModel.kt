package com.novax.app.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novax.app.data.NvGraph
import com.novax.app.data.NvPaths
import com.novax.app.data.NvSecrets
import com.novax.app.data.findConnectKind
import com.novax.app.data.privacy.NvFavoriteCipherRows
import com.novax.app.data.privacy.NvFavoriteDirect
import com.novax.app.data.privacy.NvFingerprint
import com.novax.app.data.privacy.NvGateWiring
import com.novax.app.data.privacy.NvHistoryCipherRows
import com.novax.app.data.privacy.NvPrivacyAccess
import com.novax.app.data.privacy.NvPrivacyActionPolicy
import com.novax.app.data.privacy.NvPrivacyCrypto
import com.novax.app.data.privacy.NvPrivacyIndex
import com.novax.app.data.privacy.NvPrivacyPrefs
import com.novax.app.data.privacy.NvPrivacyScan
import com.novax.app.data.privacy.NvPrivacySession
import com.novax.app.data.privacy.NvSecureWipe
import com.novax.app.data.db.PrivacyRuleEntity
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceStatus
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.ThumbState
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.db.WatchKind
import com.novax.app.data.jellyfin.JellyfinApi
import com.novax.app.data.scrape.NvScraper
import com.novax.app.data.scanner.NvScannerHolder
import com.novax.app.data.scanner.ScanRoots
import com.novax.app.data.smb.SmbShareRoot
import com.novax.app.data.smb.SmbStatusText
import com.novax.app.data.thumb.ThumbCache
import com.novax.app.data.webdav.WebDavPaths
import com.novax.app.R
import com.novax.app.novaxUiPrefs
import com.novax.app.ui.components.UiMessage
import com.novax.app.ui.home.NvPlaybackLauncher
import com.novax.app.ui.settings.KEY_LIB_AUTO_SCAN
import com.novax.app.ui.viewers.NvFileRouter
import com.novax.app.player.KEY_LAN_DISCOVERY
import com.novax.app.player.NvHistoryDirect
import com.novax.app.player.playerPrefsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ---------- 数据模型（C5 §四；数据链路已切真实：Room 源 + SMB/WebDAV/本地列目录） ----------

/** 向导可添加的协议（DLNA 一期未实现客户端，不提供假入口；SFTP 2026-09-29 sshj 接入；
 *  Jellyfin 2026-10-07 客户端接入，向导入口/表单/图标同步放开）；i18n：标签资源化 */
enum class NvProtocol(val labelRes: Int) {
    SMB(R.string.files_protocol_smb), WebDav(R.string.files_protocol_webdav),
    SFTP(R.string.files_protocol_sftp), Local(R.string.files_protocol_local),
    Jellyfin(R.string.files_protocol_jellyfin);

    fun toSourceType(): SourceType? = when (this) {
        SMB -> SourceType.SMB
        WebDav -> SourceType.WEBDAV
        SFTP -> SourceType.SFTP
        Local -> SourceType.LOCAL
        Jellyfin -> SourceType.JELLYFIN
    }

    companion object {
        fun of(type: SourceType): NvProtocol? = when (type) {
            SourceType.SMB -> SMB
            SourceType.WEBDAV -> WebDav
            SourceType.SFTP -> SFTP      // 2026-09-29：SFTP 客户端已落地，源列表不再隐藏
            SourceType.LOCAL -> Local
            SourceType.JELLYFIN -> Jellyfin
        }
    }
}

/** 源卡片连接状态；i18n：标签资源化（UI 层 stringResource 解析） */
enum class NvSourceState(val labelRes: Int) {
    Connected(R.string.files_state_connected), Offline(R.string.files_state_offline),
    Failed(R.string.files_state_failed),
}

/** 源卡片 UI 模型（数据源：SourceDao.observeAll → SourceEntity 映射） */
data class NvSource(
    val entity: SourceEntity,
    val name: String,
    val protocol: NvProtocol,
    val address: String,
    val state: NvSourceState,
) {
    val id: Long get() = entity.id
}

data class NvDiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val protocol: NvProtocol,
)

/** 文件/目录条目 UI 模型（数据源：NvSourceRepository.list） */
data class NvFileEntry(
    val name: String,
    val path: String,                      // 源内统一路径（NvPaths 表示）
    val isDir: Boolean,
    val size: Long = 0L,
    val mtimeMs: Long = 0L,                // C5 §5.2 修改时间排序；源不可提供时 0
    // 时长/分辨率由扫描管道（media_items）在展示层回填（2026-09-30 核实已生效，过期 TODO 删除）
    val durationMs: Long? = null,
    val resolution: String? = null,
    val watched: Boolean = false,
    val resumeProgress: Float? = null,
)

/** 向导/浏览器错误（i18n：title/message 走 UiMessage 载体，UI 层 text() 渲染） */
data class NvWizardError(
    val title: UiMessage,
    val message: UiMessage,
    val isCredential: Boolean = false,
)

/** host 字段解析结果（N3 粘贴/存量回显共用）：[https]=null 表示无 scheme 前缀（非粘贴输入） */
private data class HostParsed(val host: String, val port: String?, val https: Boolean?)

// 向导步骤状态
data class NvWizardUiState(
    val sessionId: Long = System.nanoTime(),
    val original: SourceEntity? = null,
    val saving: Boolean = false,
    val open: Boolean = false,             // 向导是否打开
    val step: Int = 0,                     // 0 协议选择 / 1 地址凭据（含测试连接）/ 2 目录确认与保存
    val protocol: NvProtocol = NvProtocol.SMB,
    // N3 源地址全裸 host（2026-10-09 用户需求：协议头不该让用户填）：表单只收裸 IP/域名，
    // 加密用 https 开关承载（存 https://host 前缀，client 层 WebDavPaths.schemeOf /
    // JellyfinApi.normalizeBaseUrl 既有逻辑识别，存储格式零改动兼容存量源）
    val host: String = "",                 // 裸 IP/域名（粘贴带 http(s):// 由 onHost 自动剥离）
    val port: String = "",                 // 空串 = 协议默认（SMB 445 / WebDAV 80 或 443）
    val https: Boolean = false,            // HTTPS 开关（WebDAV/Jellyfin）：开=存 https://host 前缀
    val domain: String = "",               // SMB 域（可空）
    val user: String = "",
    val pass: String = "",
    val anonymous: Boolean = false,
    val shareOrPath: String = "",          // SMB 共享名 / WebDAV 根路径 / 本地绝对目录
    val nickname: String = "",             // 昵称（可空，自动生成）
    val connecting: Boolean = false,       // 正在测试连接
    val testSuccess: Boolean = false,      // 测试连接通过（绿色提示 + 允许下一步/保存）
    val error: NvWizardError? = null,
    val failureCount: Int = 0,
    val sharesLoading: Boolean = false,    // 正在拉取 SMB 共享列表
    val shares: List<String> = emptyList(),// 已拉到的共享名（点击回填共享名输入框）
    val sharesError: NvWizardError? = null, // 共享列表拉取失败（可重试，不打断手动输入）
    // Step 2 目录多选结果（NvPaths 规范化绝对表示，如 "/video/movies"）：
    // 空 = 未选目录，保存后扫描器回退扫根目录；非空 = 写入 sources.scanRoots 逐根扫描。
    val selectedScanDirs: Set<String> = emptySet(),
    // 「复制源」时从原源带入的刮削开关（BUG-C-R6-12：复制场景 original 置 null 以免
    // 覆盖原行，但 scrapeEnabled 取值也跟着丢了——副本恒为关）
    val copyScrapeEnabled: Boolean? = null,
) {
    /** 「获取共享列表」入口可用性：SMB 源且主机+账号已填（匿名视为已填） */
    val canFetchShares: Boolean
        get() = protocol == NvProtocol.SMB && host.isNotBlank() && (anonymous || user.isNotBlank())
}

// 浏览器状态
data class NvBrowserUiState(
    val sourceId: Long,
    val source: NvSource? = null,
    val breadcrumb: List<String> = emptyList(),   // 相对源根，不含"根"
    val entries: List<NvFileEntry> = emptyList(),
    val loading: Boolean = true,
    val error: NvWizardError? = null,             // 2.7 断连（i18n：UiMessage 载体，UI 层渲染）
    val noPermission: Boolean = false,            // 2.8 无权限（本地目录不可读）
    // ---- C5 二期：类型过滤（5.1） ----
    val enabledTypes: Set<NvFileType> = allFileTypes,   // 六类全勾 = 无过滤
    // ---- C5 二期：目录内搜索（5.3） ----
    val searchActive: Boolean = false,            // 顶栏是否处于搜索态
    val searchQuery: String = "",
    val searchIncludeSubs: Boolean = false,       // 「含子目录」开关（默认关）
    val searching: Boolean = false,               // 子目录递归搜索进行中（可取消）
    val searchSubResults: List<NvFileEntry> = emptyList(),
    // ---- C5 二期：多选（5.4，存 ViewModel 配置变更不丢） ----
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val preSelectionQuery: NvListQuery? = null,   // 进入多选前的过滤/搜索态快照（退出恢复）
) {
    /** 是否处于过滤态（Chip 行回显条件：六类全勾 = 无过滤） */
    val hasTypeFilter: Boolean get() = !enabledTypes.containsAll(allFileTypes)
}

/** 应用级设置 KV（电池优化「已询问过」标记、文件排序偏好等） */
private val Context.novaxSettings by preferencesDataStore(name = "novax_settings")

private val KEY_BATTERY_ASKED = booleanPreferencesKey("battery_optimization_asked")

/** C5 §5.2 排序偏好持久化：跨目录、跨会话记忆 */
private val KEY_FILE_SORT_MODE = stringPreferencesKey("file_sort_mode")
private val KEY_FILE_SORT_DIRS_FIRST = booleanPreferencesKey("file_sort_dirs_first")

/** C5 §5.3 子目录搜索边界：深度 ≤3、结果限 200 条；输入防抖 150ms */
const val SEARCH_MAX_DEPTH = 3
const val SEARCH_RESULT_LIMIT = 200
const val SEARCH_DEBOUNCE_MS = 150L

fun isVideoFile(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return false
    // BUG-C-R6-10：扩展名集与 NvFileRouter.VIDEO_EXTS 统一（原 9 种缺 rm/rmvb/mpg/mpeg/3gp/ogv，
    // 这类文件在文件页永远没有缩略图/进度回填）
    return name.substring(dot + 1).lowercase() in NvFileRouter.VIDEO_EXTS
}

class FilesViewModel(
    private val appContext: Context,
    /** 2026-09-27 返回根目录修复：浏览位置（源+面包屑）存进程恢复级 SavedStateHandle */
    private val savedBrowser: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
) : ViewModel() {

    private val repo = NvGraph.repository()
    private val dao = NvGraph.sourceDao(appContext)
    private val favoriteDao = NvGraph.favoriteDao(appContext)
    private val historyDao = NvGraph.historyDao(appContext)
    private val db = MediaDb.get(appContext)
    // 隐私分类门面（Task 9）：历史直写点/续播读取一律经 NvHistoryDirect 消费，不旁路 historyDao
    private val privacy = com.novax.app.data.privacy.NvPrivacyAccess.get(appContext)

    // ---------- 隐私锁/无痕（隐私计划 Task 13）：规则快照/会话信号/动作步骤 ----------
    // 构造纪律（T8 裁定）：PinManager 一律经 NvPrivacyAccess.newPinManager 工厂
    private val privacyCipher = NvPrivacyCrypto.keystoreCipher()
    private val privacyStore by lazy { NvPrivacyPrefs(appContext, privacyCipher) }
    private val privacyPinManager by lazy { NvPrivacyAccess.newPinManager(appContext, privacyStore) }

    /** 隐私规则实体 Flow 直通 StateFlow：驱动锁定行/FLAG_SECURE 判定的重组（判定本体走 privacy.index） */
    private val _privacyRules = MutableStateFlow<List<PrivacyRuleEntity>>(emptyList())
    val privacyRules: StateFlow<List<PrivacyRuleEntity>> = _privacyRules.asStateFlow()

    /** 会话内存侧变化（解锁/忘码重置）无 Flow 可订阅——显式重组信号 */
    private val _privacyTick = MutableStateFlow(0)
    val privacyTick: StateFlow<Int> = _privacyTick.asStateFlow()

    private fun bumpPrivacyTick() {
        _privacyTick.value += 1
    }

    /** pendingRepoint（T16 设置页「重挂到…」存 ruleId → 本任务长按「挂载到此处」完成回传后清除） */
    private val _pendingRepoint = MutableStateFlow<Long?>(null)
    val pendingRepoint: StateFlow<Long?> = _pendingRepoint.asStateFlow()

    /** 待续跑的隐私动作（PIN 设置完成后原动作续跑；跨文件夹/整源共用） */
    data class PendingPrivacyAction(
        val sourceId: Long,
        // dirPath 规范口径：空串=整源；jf: 前缀+itemId=Jellyfin 目录；其余源内路径
        val dirPath: String,
        val display: String,
        val kind: NvPrivacyActionPolicy.Kind,
    )

    /**
     * 隐私动作 UI 步骤：无 PIN 先引导创建（锁）/ 无痕开启先确认清历史 /
     * 关分支（移除锁/关无痕）先验 PIN（终审 C1，NvPinVerifySheet 纯验证语义不碰解锁会话）
     */
    sealed interface PrivacyStep {
        data object Idle : PrivacyStep
        data class NeedPinSetup(val action: PendingPrivacyAction) : PrivacyStep
        data class ConfirmIncognito(val action: PendingPrivacyAction, val recordCount: Int) : PrivacyStep
        data class NeedPinVerify(val action: PendingPrivacyAction) : PrivacyStep
    }

    private val _privacyStep = MutableStateFlow<PrivacyStep>(PrivacyStep.Idle)
    val privacyStep: StateFlow<PrivacyStep> = _privacyStep.asStateFlow()

    // ---------- 源列表：Room Flow 订阅（真删库真保存） ----------
    private val _sources = MutableStateFlow<List<NvSource>>(emptyList())
    val sources: StateFlow<List<NvSource>> = _sources.asStateFlow()

    private val _sourcesLoading = MutableStateFlow(true)
    val sourcesLoading: StateFlow<Boolean> = _sourcesLoading.asStateFlow()

    /** 本轮页面生命周期内已发起后台连通性校验的源 id（避免 DB 回写触发 Flow 死循环） */
    private val statusChecked = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Long, Boolean>(),
    )

    // ---------- 局域网发现（NsdManager：_smb._tcp / _webdav._tcp，各 5s 超时） ----------
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _devices = MutableStateFlow<List<NvDiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<NvDiscoveredDevice>> = _devices.asStateFlow()

    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var scanner: NsdScanner? = null

    // ---------- 向导 ----------
    private val _wizard = MutableStateFlow(NvWizardUiState())
    val wizard: StateFlow<NvWizardUiState> = _wizard.asStateFlow()

    private val _wizardBrowser = MutableStateFlow<WizardBrowse?>(null)
    val wizardBrowser: StateFlow<WizardBrowse?> = _wizardBrowser.asStateFlow()

    // ---------- 浏览器 ----------
    private val _browser = MutableStateFlow<NvBrowserUiState?>(null)
    val browser: StateFlow<NvBrowserUiState?> = _browser.asStateFlow()

    private var browseJob: Job? = null
    private var browseGen = 0   // BUG-C-R6-04：目录浏览请求代次（过期结果丢弃）
    private var wizardBrowseJob: Job? = null
    private var searchJob: Job? = null     // C5 §5.3：150ms 防抖 + 子目录递归搜索（可取消）

    // ---------- C5 §5.2 排序偏好（DataStore 持久化，跨目录/跨会话记忆） ----------
    private val _sortMode = MutableStateFlow(NvSortMode.NAME)
    val sortMode: StateFlow<NvSortMode> = _sortMode.asStateFlow()

    private val _dirsFirst = MutableStateFlow(true)
    val dirsFirst: StateFlow<Boolean> = _dirsFirst.asStateFlow()

    // ---------- 收藏 / 观看历史键（C11 §三 + 修订：与 PlayerActivity 历史写入同一套键） ----------
    // 键规则（与 NvPlaybackLauncher.novaxUrl / 本地绝对路径同构，保证收藏可正确续播）：
    // - NAS 源：(sourceId, NvPaths.normalize 源内路径)
    // - LOCAL 源：(null, 源根拼接后的本地绝对路径)
    private val _favoriteKeys = MutableStateFlow<Set<Pair<Long?, String>>>(emptySet())
    val favoriteKeys: StateFlow<Set<Pair<Long?, String>>> = _favoriteKeys.asStateFlow()

    /** watch_history 按键回填（继续观看 Flow + 列目录后逐键查询合并），驱动 watched✓ 与续播进度 */
    private val _historyByKey = MutableStateFlow<Map<Pair<Long?, String>, WatchHistoryEntity>>(emptyMap())
    val historyByKey: StateFlow<Map<Pair<Long?, String>, WatchHistoryEntity>> = _historyByKey.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeAll().collect { entities ->
                _sources.value = entities.mapNotNull(::toUiSource)
                _sourcesLoading.value = false
                // 进入页面后台 connect 校验，状态回写 DB（C5 §1.1）
                entities.forEach(::verifyStatusInBackground)
            }
        }
        viewModelScope.launch {
            favoriteDao.observeAll().collect { favs ->
                _favoriteKeys.value = favs.map { it.sourceId to it.path }.toSet()
            }
        }
        // 历史按键回填（BUG-C-R6-03）：订阅全表整表重建 map——继续观看 Flow 的
        // LIMIT 12/<95% 过滤会让「已看完✓」永不出现、清除历史后残留幽灵进度、12 条外无回填
        viewModelScope.launch {
            historyDao.observeAllForBackfill().collect { rows ->
                _historyByKey.value = rows.associate { (it.sourceId to it.path) to it }
            }
        }
        // 「局域网发现」总闸实时生效（关闭后 rescan/进入页面均不再发 mDNS）
        viewModelScope.launch {
            appContext.playerPrefsFlow()
                .map { it[KEY_LAN_DISCOVERY] ?: true }
                .collect { lanDiscoveryEnabled = it }
        }
        // 隐私规则实体 Flow 直通（Task 13）：驱动锁定行/FLAG_SECURE 判定的重组（判定本体走 privacy.index 内存快照）
        viewModelScope.launch {
            db.privacyRuleDao().observeAll().collect { _privacyRules.value = it }
        }
        // pendingRepoint（Task 16「重挂到…」设定 → 本任务「挂载到此处」完成回传）
        viewModelScope.launch {
            _pendingRepoint.value = runCatching { privacyStore.pendingRepointRuleId() }.getOrNull()
        }
        // §1a:「流量网络也出封面」false→true 上升沿(已收集到 false 之后的)→ 重列当前目录,
        // 走 listDir→primeThumbsForFolder 的既有链路完成重置+置顶抽帧;进程恢复首值即 true 不触发
        viewModelScope.launch {
            var prev: Boolean? = null
            appContext.playerPrefsFlow()
                .map { it[com.novax.app.player.KEY_THUMB_METERED] ?: false }
                .collect { now ->
                    val rising = prev == false && now
                    prev = now
                    if (rising) retryListing()
                }
        }
        // BUG-E-REGB-01：目录列表是打开时快照——外部新增文件（adb/生成器）扫描入库后
        // 当前浏览的源不会刷新。订阅扫描进度：正浏览的源 RUNNING→DONE 即自动重扫当前目录
        viewModelScope.launch {
            var prevRunning: Set<Long> = emptySet()
            runCatching {
                com.novax.app.data.scanner.NvScannerHolder.get(appContext).progress.collect { map ->
                    val nowRunning = map.filterValues { it.status == com.novax.app.data.db.ScanStatus.RUNNING }.keys
                    val finished = prevRunning - nowRunning
                    prevRunning = nowRunning
                    if (finished.isNotEmpty() && _browser.value?.sourceId in finished) {
                        retryListing()
                    }
                }
            }
        }
        startScan()
        // C5 §5.2：恢复持久化排序偏好
        viewModelScope.launch {
            runCatching { appContext.novaxSettings.data.first() }.onSuccess { prefs ->
                _sortMode.value =
                    prefs[KEY_FILE_SORT_MODE]?.let { stored ->
                        NvSortMode.entries.firstOrNull { it.name == stored }
                    } ?: NvSortMode.NAME
                _dirsFirst.value = prefs[KEY_FILE_SORT_DIRS_FIRST] ?: true
            }
        }
    }

    /** 排序方式变更：即时生效 + DataStore 持久化 */
    fun onSortModeChange(mode: NvSortMode) {
        _sortMode.value = mode
        persistSortPrefs()
    }

    /** 「文件夹恒置顶」变更：即时生效 + DataStore 持久化 */
    fun onDirsFirstChange(enabled: Boolean) {
        _dirsFirst.value = enabled
        persistSortPrefs()
    }

    private fun persistSortPrefs() {
        viewModelScope.launch {
            runCatching {
                appContext.novaxSettings.edit {
                    it[KEY_FILE_SORT_MODE] = _sortMode.value.name
                    it[KEY_FILE_SORT_DIRS_FIRST] = _dirsFirst.value
                }
            }
        }
    }

    /**
     * 2026-09-29 「记住每个源的上次浏览目录」写入端：novax_ui_prefs / files_last_dir_<sourceId>
     * （与 MainActivity.novaxUiPrefs 同文件同实例；排序偏好的 novax_settings 不混用）。
     * 值为 joinToPath 口径：根 = ""，其余 = "/a/b"（SMB 虚拟共享 "/@share/<名>" 亦直列可恢复）。
     * 恢复决策见 [restoreBreadcrumbFrom]（NvBrowserPolicy，纯函数单测锁定）。
     */
    private fun persistLastDir(sourceId: Long, breadcrumb: List<String>) {
        viewModelScope.launch {
            runCatching {
                appContext.novaxUiPrefs.edit {
                    it[stringPreferencesKey(lastDirPrefsKey(sourceId))] = joinToPath(breadcrumb)
                }
            }
        }
    }

    /** 条目 → 历史/收藏统一键（LOCAL=null+绝对路径；其余=sourceId+源内路径） */
    fun mediaKeyOf(sourceId: Long, entry: NvFileEntry): Pair<Long?, String> {
        val src = _sources.value.firstOrNull { it.id == sourceId } ?: return sourceId to entry.path
        return when (src.entity.type) {
            SourceType.LOCAL -> null to NvPlaybackLauncher.localPath(src.entity.shareOrPath, entry.path)
            else -> sourceId to NvPaths.normalize(entry.path)
        }
    }

    /** 文件行回填：watched✓（≥95%）与续播进度（继续观看窗口内） */
    fun decorated(sourceId: Long, entry: NvFileEntry, history: WatchHistoryEntity?): NvFileEntry {
        if (entry.isDir || history == null) return entry
        val watched = history.durationMs > 0L && history.positionMs >= history.durationMs * 0.95
        val progress = if (com.novax.app.ui.home.isContinueWindow(history.positionMs, history.durationMs)) {
            (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f)
        } else {
            null
        }
        return entry.copy(watched = watched, resumeProgress = progress)
    }

    /** 文件长按「加入/取消收藏」：双键查重（明文+hash）→ 查有删/查无插，Flow 即时刷新。
     *  隐私口径（Task 9 修复轮）：经 NvFavoriteDirect——锁命中内容密文行落盘，无痕不拦收藏 */
    fun toggleFavorite(sourceId: Long, entry: NvFileEntry) {
        viewModelScope.launch {
            runCatching {
                val (keySourceId, keyPath) = mediaKeyOf(sourceId, entry)
                val kind = when (NvFileRouter.kindOf(entry.name)) {
                    NvFileRouter.Kind.AUDIO -> WatchKind.AUDIO.name
                    else -> WatchKind.MOVIE.name
                }
                NvFavoriteDirect.toggle(
                    db,
                    privacy,
                    FavoriteEntity(
                        sourceId = keySourceId,
                        path = keyPath,
                        title = entry.name,
                        kind = kind,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** 长按「标记已看」：upsert 历史行 positionMs=durationMs（无时长则 60s/60s，移出继续观看）。
     *  隐私口径（Task 9）：经 NvHistoryDirect——无痕不写、锁定内容密文行落盘 */
    fun markWatched(sourceId: Long, entry: NvFileEntry) {
        viewModelScope.launch {
            runCatching {
                val (keySourceId, keyPath) = mediaKeyOf(sourceId, entry)
                val src = _sources.value.firstOrNull { it.id == sourceId }
                val kind = when (NvFileRouter.kindOf(entry.name)) {
                    NvFileRouter.Kind.AUDIO -> WatchKind.AUDIO.name
                    else -> WatchKind.MOVIE.name
                }
                // 时长兜底查既有行：findForPlayback 对密文行/内存进度同样有效（明文 findByKey 查不到 hash 行）
                val old = NvHistoryDirect.findForPlayback(db, privacy, keySourceId, keyPath)
                val duration = entry.durationMs?.takeIf { it > 0L }
                    ?: old?.durationMs?.takeIf { it > 0L }
                    ?: 60_000L
                NvHistoryDirect.markWatched(
                    db,
                    privacy,
                    WatchHistoryEntity(
                        sourceId = keySourceId,
                        sourceType = src?.entity?.type?.name,
                        path = keyPath,
                        title = entry.name,
                        kind = kind,
                        positionMs = duration,
                        durationMs = duration,
                        watchedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** 长按「取消已看」：hash+plain 双口径删历史行（退出已看判定与继续观看窗口；取消已看=从头看） */
    fun markUnwatched(sourceId: Long, entry: NvFileEntry) {
        viewModelScope.launch {
            runCatching {
                val (keySourceId, keyPath) = mediaKeyOf(sourceId, entry)
                NvHistoryDirect.markUnwatched(db, privacy, keySourceId, keyPath)
            }
        }
    }

    /** LOCAL 源长按「重命名」：java.io.File 重命名后刷新目录（含同名防覆盖 + 级联清理旧键；
     *  目录改名的隐私规则迁移与级联清理同 Room 事务，Task 13） */
    fun renameLocalFile(sourceId: Long, entry: NvFileEntry, newName: String) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val src = _sources.value.firstOrNull { it.id == sourceId } ?: return@withContext false
                    val abs = File(NvPlaybackLauncher.localPath(src.entity.shareOrPath, entry.path))
                    val dst = File(abs.parentFile, newName)
                    // BUG-C-R6-01（P0）：rename(2) 对已存在同名目标原子替换并返回 true——
                    // 静默覆盖丢文件，必须先做存在性检查拒绝
                    if (dst.exists()) return@runCatching false
                    val moved = abs.renameTo(dst)
                    if (moved) {
                        // 隐私级联（Task 13）：目录改名的规则迁移（含嵌套子目录规则）与级联清理同事务
                        db.withTransaction {
                            if (entry.isDir) {
                                migratePrivacyRulesForRename(sourceId, entry.path, renamedRelPathOf(entry, newName), newName)
                            }
                            cascadePurgeLocalKeys(sourceId, abs.absolutePath)
                        }
                    }
                    moved
                }.getOrDefault(false)
            }
            if (!ok) {
                android.widget.Toast.makeText(
                    appContext,
                    appContext.getString(R.string.files_rename_failed_toast),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            retryListing()
        }
    }

    /** LOCAL 源长按「删除」：java.io.File 删除后刷新目录（含级联清理；目录删除的级联规则
     *  清理走 NvSecureWipe 管道——缺此步删目录后规则成幽灵锁，Task 13） */
    fun deleteLocalFile(sourceId: Long, entry: NvFileEntry) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val src = _sources.value.firstOrNull { it.id == sourceId } ?: return@withContext false
                    val abs = File(NvPlaybackLauncher.localPath(src.entity.shareOrPath, entry.path))
                    val deleted = abs.delete()
                    if (deleted) {
                        NvSecureWipe.wiping(db) {
                            if (entry.isDir) deletePrivacyRulesUnderDir(sourceId, entry.path)
                            cascadePurgeLocalKeys(sourceId, abs.absolutePath)
                        }
                    }
                    deleted
                }.getOrDefault(false)
            }
            if (!ok) android.widget.Toast.makeText(
                appContext,
                appContext.getString(R.string.files_delete_failed_toast),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            retryListing()
        }
    }

    /**
     * 级联清理（BUG-C-R6-05）：本地文件删除/重命名后按 mediaKey 清理收藏、观看历史与
     * 媒体库行（含缩略图文件），否则留下死收藏卡/幽灵进度（与 T2-7 验收直接对应）。
     * LOCAL 源键 = (null, 绝对路径)，与 favoriteKeyOf/history 落库键同构。
     */
    private suspend fun cascadePurgeLocalKeys(sourceId: Long, absPath: String) {
        runCatching {
            // hash+plain 双口径删（Task 9 修复轮）：明文行在原 path 键、锁定内容的收藏行在 hash 键上
            NvFavoriteDirect.remove(db, privacy, null, absPath)
            // hash+plain 双口径删（Task 9）：该路径若为锁定内容，其历史行在 hash 键上
            NvHistoryDirect.markUnwatched(db, privacy, null, absPath)
            val miDao = NvGraph.mediaItemDao(appContext)
            miDao.findByKey(sourceId, absPath)?.let { mi ->
                mi.thumbnailPath?.let { t -> runCatching { File(t).delete() } }
                miDao.deleteById(mi.id)
            }
        }
    }

    private fun toUiSource(e: SourceEntity): NvSource? {
        val protocol = NvProtocol.of(e.type) ?: return null
        return NvSource(
            entity = e,
            name = e.nickname,
            protocol = protocol,
            address = addressOf(e),
            state = when (e.status) {
                SourceStatus.OK -> NvSourceState.Connected
                SourceStatus.ERROR -> NvSourceState.Failed
                SourceStatus.UNKNOWN -> NvSourceState.Offline
            },
        )
    }

    private fun addressOf(e: SourceEntity): String = when (e.type) {
        SourceType.SMB -> {
            val port = if (e.port in 1..65535) ":${e.port}" else ""
            "smb://${e.host}$port/${e.shareOrPath.trimStart('/')}"
        }
        SourceType.WEBDAV -> {
            val scheme = WebDavPaths.schemeOf(e.host)
            val host = WebDavPaths.hostOf(e.host)
            val port = if (e.port in 1..65535) ":${e.port}" else ""
            "$scheme://$host$port${e.shareOrPath}"
        }
        SourceType.LOCAL -> e.shareOrPath
        // 2026-09-29 SFTP 接入：卡片地址补端口与根路径（对齐 SMB/WebDAV 口径）
        SourceType.SFTP -> {
            val port = if (e.port in 1..65535) ":${e.port}" else ""
            "sftp://${e.host}$port${e.shareOrPath}"
        }
        // N3 合成显示（2026-10-09）：host 裸存后卡片地址镜像 JellyfinClient.baseUrlOf 拼接——
        // normalizeBaseUrl 补 scheme + 端口追加（host 已含显式端口或未填端口时原样）；
        // 验收=新建裸 host 源卡片显示 http://IP:8096
        SourceType.JELLYFIN -> {
            val base = JellyfinApi.normalizeBaseUrl(e.host)
            val authority = base.substringAfter("://")
            if (authority.contains(':') || e.port <= 0) base else "$base:${e.port}"
        }
    }

    /** 后台连通性校验：repo.connect → SourceDao.updateStatus 回写（同一源每次进页面只查一次） */
    private fun verifyStatusInBackground(e: SourceEntity) {
        // 2026-09-29：SFTP 客户端已落地，不再跳过（原「SFTP 一期未实现」守卫删除）
        if (!statusChecked.add(e.id)) return
        viewModelScope.launch {
            val ok = runCatching { repo.connect(e).isSuccess }.getOrDefault(false)
            runCatching {
                dao.updateStatus(e.id, System.currentTimeMillis(), if (ok) SourceStatus.OK else SourceStatus.ERROR)
            }
        }
    }

    // ---------- 局域网发现（真实 NsdManager，替代原 2s 假扫描） ----------

    /** 「局域网发现」总闸（2026-09-15 假开关真化：此前 KEY_LAN_DISCOVERY 无消费点，关了照样扫） */
    @Volatile
    private var lanDiscoveryEnabled = true

    fun startScan() {
        if (_scanning.value) return
        _devices.value = emptyList()
        if (!lanDiscoveryEnabled) {
            Log.d(TAG, "局域网发现已关闭（设置-网络/NAS），跳过 NSD 扫描")
            return
        }
        val nsd = nsdManager
        if (nsd == null) {
            Log.w(TAG, "NsdManager 不可用，跳过局域网发现")
            return
        }
        _scanning.value = true
        scanner = NsdScanner(
            nsd = nsd,
            mainScope = viewModelScope,
            onDevice = ::addDevice,
            onFinished = { _scanning.value = false },
        ).also { it.start() }
    }

    fun rescan() {
        stopScan()
        startScan()
    }

    /** 2026-09-15 泄漏修复：离开文件页（VM cleared）兜底停止 NSD 发现，防 mDNS 常驻耗电 */
    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    private fun stopScan() {
        scanner?.stopAll()
        scanner = null
        _scanning.value = false
    }

    private fun addDevice(device: NvDiscoveredDevice) {
        if (device.ip.isBlank()) return
        _devices.update { cur ->
            if (cur.any { it.ip == device.ip && it.port == device.port }) cur else cur + device
        }
    }

    // ---------- 源列表 ----------
    fun openSource(source: NvSource) {
        // 2026-09-29 「记住每个源的上次浏览目录」：进源读 novax_ui_prefs 的
        // files_last_dir_<sourceId>，自动恢复到上次浏览目录（无记录回落根目录，
        // 恢复决策见 [restoreBreadcrumbFrom]）。写入端在 [listDirInternal] 成功回调。
        _browser.value = NvBrowserUiState(sourceId = source.id, source = source)
        viewModelScope.launch {
            val stored = runCatching {
                appContext.novaxUiPrefs.data
                    .first()[stringPreferencesKey(lastDirPrefsKey(source.id))]
            }.getOrNull()
            // 异步读窗口内浏览器可能已被关闭/切到别的源（closeBrowser 或连点其他卡片）：
            // 恢复前校验身份，防「已关掉又被本任务重新打开」
            if (_browser.value?.sourceId != source.id) return@launch
            val crumb = restoreBreadcrumbFrom(stored)
            // 恢复目录可能已失效（改名/删除/暂不可列）：列目录失败回落根目录（见 listDirInternal）
            listDirInternal(source.id, crumb, source.entity, fallbackToRootOnFailure = crumb.isNotEmpty())
        }
    }

    /**
     * 2026-09-27 历史页「打开所在文件夹」/ 目录状态恢复共用：跳过逐级下钻，直接开到
     * 目标源+目录（breadcrumb 与 listDir 同口径，相对源根的目录段）。
     * 源列表由 DB Flow 异步加载——事件到达时可能还没就绪（实测：跳转后停在源列表），
     * 此时挂起等首个含目标源的帧再执行（正常毫秒级）。
     */
    fun openSourceAt(sourceId: Long, breadcrumb: List<String>) {
        pendingOpenJob?.cancel()
        val src = _sources.value.firstOrNull { it.id == sourceId }
        if (src != null) {
            doOpenSourceAt(src, sourceId, breadcrumb)
            return
        }
        pendingOpenJob = viewModelScope.launch {
            val found = sources
                .filter { list -> list.any { it.id == sourceId } }
                .first()
                .firstOrNull { it.id == sourceId } ?: return@launch
            doOpenSourceAt(found, sourceId, breadcrumb)
        }
    }

    private fun doOpenSourceAt(src: NvSource, sourceId: Long, breadcrumb: List<String>) {
        _browser.value = NvBrowserUiState(sourceId = sourceId, source = src, breadcrumb = breadcrumb)
        listDirInternal(sourceId, breadcrumb, src.entity)
    }

    // ---- 2026-09-27 返回根目录修复：浏览位置持久化到 SavedStateHandle ----
    // 根因：当前目录只活在 _browser（ViewModel 内存），看视频期间进程被系统杀死 → 返回时
    // MainActivity 恢复但 FilesViewModel 全新 → 直接渲染最外层源列表。SavedStateHandle 随
    // NavBackStackEntry 的 SavedStateRegistry 落盘，进程死亡/「不保留活动」场景可恢复。

    private var pendingOpenJob: kotlinx.coroutines.Job? = null

    private fun rememberBrowserLocation(sourceId: Long, breadcrumb: List<String>) {
        savedBrowser[KEY_BROWSER_SOURCE_ID] = sourceId
        savedBrowser[KEY_BROWSER_CRUMB] = breadcrumb.joinToString("/")
    }

    /**
     * 进程死亡恢复入口（FilesScreen 组合进入时调用）：重开到上次浏览的源+目录。
     * 深链（历史页跳文件夹）优先——若浏览器已开（_browser 非空）本方法自动 no-op。
     * 复用 [openSourceAt]（内含源列表就绪等待）。
     */
    fun restoreBrowserIfSaved() {
        if (_browser.value != null) return
        val sourceId = savedBrowser.get<Long>(KEY_BROWSER_SOURCE_ID) ?: return
        val crumb = savedBrowser.get<String>(KEY_BROWSER_CRUMB) ?: return
        openSourceAt(sourceId, crumb.split("/").filter { it.isNotEmpty() })
    }

    fun closeBrowser() {
        browseJob?.cancel()
        searchJob?.cancel()
        searchJob = null
        savedBrowser.remove<Long>(KEY_BROWSER_SOURCE_ID)
        savedBrowser.remove<String>(KEY_BROWSER_CRUMB)
        _browser.value = null
    }

    /**
     * 删除源：真删 Room（仅移除连接配置，不动 NAS 文件）；级联清理该源的历史、收藏、
     * 索引行、扫描态与缩略图。必须先停该源在途的扫描/刮削——否则扫描器的 commitBatch
     * 会在删除后把 media_items 幽灵行再次写回（点击即报「视频源不存在」）。
     */
    fun deleteSource(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                runCatching { NvScannerHolder.get(appContext).cancelAndJoin(id) }
                runCatching { NvScraper.get(appContext).cancelSource(id) }
                val source = dao.getById(id)
                // 隐私规则级联（Task 13）：真抹除管道包住（含 privacy_rules 删行），缺此步删源后成幽灵整源锁
                NvSecureWipe.wiping(db) {
                    db.withTransaction {
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM watch_history WHERE sourceId = ?",
                            arrayOf<Any?>(id),
                        )
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM favorites WHERE sourceId = ?",
                            arrayOf<Any?>(id),
                        )
                        db.privacyRuleDao().deleteBySource(id)
                        dao.deleteById(id)
                    }
                }
                // SFTP 删源驱逐：连接池按旧源前缀清除 + known-hosts 指纹行重置（重加=重置信任；非 SFTP 空操作）
                if (source != null) repo.evictSftp(source)
                // M3（2026-10-18 发布终审）：SMB 删源同款驱逐——已认证连接不残留到进程死（非 SMB 空操作）
                if (source != null) repo.evictSmb(source)
                // 索引行 + 失引用缩略图 jpg 一并回收（内含 mediaItemDao.deleteBySource）
                runCatching { ThumbCache.purgeSource(appContext, db.mediaItemDao(), id) }
                runCatching { db.scanStateDao().delete(id) }
            }
        }
    }

    // ---------- 文件页旁路封面（BUG-C-R6-17：文件夹里的视频也要有封面） ----------

    /** 未入库视频的旁路抽帧结果：path → 缓存 jpg 绝对路径（进程内回流，磁盘缓存跨会话复用） */
    private val _browserThumbs = MutableStateFlow<Map<String, String>>(emptyMap())
    val browserThumbs: StateFlow<Map<String, String>> = _browserThumbs.asStateFlow()

    private val browserThumbInFlight: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 在途旁路抽帧任务（key=path）：切目录时取消目录外任务，带宽聚焦用户当前待的文件夹 */
    private val browserThumbJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 用户离开目录后，取消不在当前目录子树内的旁路抽帧（前台请求本就优先于后台渐进 pass） */
    private fun cancelOrphanBrowserThumbs(currentDir: String) {
        val prefix = currentDir.trimEnd('/')
        browserThumbJobs.entries.removeAll { (path, job) ->
            val under = prefix.isEmpty() || path.startsWith("$prefix/")
            if (under) false else {
                job.cancel()
                true
            }
        }
    }

    /** 文件行可见即调：无库记录 → 旁路抽帧；缓存命中即时回流，失败静默留字母占位 */
    fun ensureBrowserThumb(sourceId: Long, path: String, sizeBytes: Long) {
        if (sizeBytes <= 0L) return
        val existing = com.novax.app.data.thumb.NvThumbHolder.get(appContext)
            .browserThumbFileFor(sourceId, path, sizeBytes)
        if (existing.exists()) {
            _browserThumbs.value = _browserThumbs.value + (path to existing.absolutePath)
            return
        }
        if (!browserThumbInFlight.add("$sourceId:$path")) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = com.novax.app.data.thumb.NvThumbHolder.get(appContext)
                    .extractBrowserThumb(sourceId, path, sizeBytes)
                if (out != null) _browserThumbs.value = _browserThumbs.value + (path to out.absolutePath)
            } finally {
                browserThumbInFlight.remove("$sourceId:$path")
                browserThumbJobs.remove(path)
            }
        }.also { job ->
            browserThumbJobs[path] = job
            job.invokeOnCompletion { browserThumbJobs.remove(path) }
        }
    }

    /** 库内行：封面非 DONE 时主动请求抽帧（requestThumb 内 claim CAS 去重，重复调无副作用）；
     *  boosted = 文件夹批量置顶档（2026-09-27：进文件夹整目录入队，排全部已排队任务之前） */
    fun requestThumbFor(mediaItemId: Long, boosted: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val holder = com.novax.app.data.thumb.NvThumbHolder.get(appContext)
                if (boosted) {
                    holder.requestThumb(mediaItemId, priority = com.novax.app.data.thumb.ThumbPriority.RANK_FOLDER_BOOST)
                } else {
                    holder.requestThumb(mediaItemId)
                }
            }
        }
    }

    /**
     * 2026-09-27 用户需求：进文件夹即把本目录全部视频的封面入队——不再等用户滚到可见行
     * 才逐个触发。库内行走置顶档 [com.novax.app.data.thumb.ThumbPriority.RANK_FOLDER_BOOST]，
     * 未入库行走旁路通道（磁盘命中/在途去重与可见行路径相同）；行内可见触发保留兜底，
     * 重复调用由 claim CAS / in-flight 去重兜住，不产生重复抽帧。
     */
    private fun primeThumbsForFolder(sourceId: Long, list: List<com.novax.app.data.NvFileEntry>) {
        val videos = list.filter { !it.isDir && isVideoFile(it.name) }
        if (videos.isEmpty()) return
        // 2026-10-13 用户验收:置顶入队必须按用户当前显示顺序——repo.list 是服务器原始序
        // (字母升序),用户排序(如时间降序)时屏幕顶部恰是原始序尾部,而抽帧队列同优先级
        // FIFO(ThumbWorkQueue seq 出队),原始序入队=从屏幕最底下的文件开始出图。
        // listComparator 是 UI 模型比较器:转 UI 条目排序后按 path 映射回原始条目
        val uiSorted = toUiEntries(videos).sortedWith(listComparator(_sortMode.value, _dirsFirst.value))
        val rawByPath = videos.associateBy { it.path }
        val displayOrder = uiSorted.mapNotNull { rawByPath[it.path] }
        viewModelScope.launch(Dispatchers.IO) {
            // §1a:进文件夹先重置本目录 FAILED 墓碑(会话去重+闸门判定在 Thumbnailer 内);
            // 失败墓碑清掉后下方 requestThumbFor 的 claim CAS 才会重新认领
            val dir = displayOrder.first().path.substringBeforeLast('/')
            runCatching {
                com.novax.app.data.thumb.NvThumbHolder.get(appContext).resetFolderFailedOnce(sourceId, dir)
            }
            // H-9b（2026-10-18 发布终审）：目录级批量预查——原逐文件 findByKey（2846 文件的
            // 目录 = 2846 次串行 DB round-trip），现对齐扫描器（MediaScanner 目录级 IN 查询，
            // 500/批）一次建映射。键统一 NvPaths.normalize（原裸 e.path 与 mediaMetaOf/
            // observeMediaMeta 的归一口径不一致——文件名含 '\' 的条目永远查不到走旁路）
            val knownByPath: Map<String, com.novax.app.data.db.MediaItemEntity> =
                displayOrder.map { NvPaths.normalize(it.path) }
                    .chunked(500)
                    .flatMap {
                        runCatching { db.mediaItemDao().findByPaths(sourceId, it) }.getOrDefault(emptyList())
                    }
                    .associateBy { it.path }
            for (e in displayOrder) {
                val known = knownByPath[NvPaths.normalize(e.path)]
                if (known != null) {
                    requestThumbFor(known.id, boosted = true)
                } else {
                    ensureBrowserThumb(sourceId, e.path, e.size)
                }
            }
        }
    }

    /** 本地源的图片原图路径：UI 直接 Coil 降采样显示，不落缩略图缓存；远程源返回 null */
    fun localImageFileOf(sourceId: Long, path: String): java.io.File? {
        val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return null
        if (src.type != SourceType.LOCAL) return null
        val f = runCatching { java.io.File(repo.localAbsolutePath(src, path)) }.getOrNull() ?: return null
        return if (f.isFile) f else null
    }

    private val browserImageInFlight: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 图片行可见即调（仅远程源）：拉取→降采样 240px→旁路缓存（磁盘命中即时回流） */
    fun ensureBrowserImageThumb(sourceId: Long, path: String, sizeBytes: Long) {
        if (sizeBytes <= 0L) return
        val existing = com.novax.app.data.thumb.NvThumbHolder.get(appContext)
            .browserThumbFileFor(sourceId, "img:$path", sizeBytes)
        if (existing.exists()) {
            _browserThumbs.value = _browserThumbs.value + (path to existing.absolutePath)
            return
        }
        if (!browserImageInFlight.add("$sourceId:$path")) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = com.novax.app.data.thumb.NvThumbHolder.get(appContext)
                    .extractBrowserImageThumb(sourceId, path, sizeBytes)
                if (out != null) _browserThumbs.value = _browserThumbs.value + (path to out.absolutePath)
            } finally {
                browserImageInFlight.remove("$sourceId:$path")
                browserThumbJobs.remove(path)
            }
        }.also { job ->
            browserThumbJobs[path] = job
            job.invokeOnCompletion { browserThumbJobs.remove(path) }
        }
    }

    // ---------- 文件浏览（真实 list） ----------
    fun listDir(sourceId: Long, breadcrumb: List<String>) {
        listDirInternal(sourceId, breadcrumb, null)
    }

    // ---------- 媒体库排除目录（sources.excludedDirs，2026-09-15 用户需求） ----------

    /**
     * 排除/恢复目录的媒体库收录：写 sources.excludedDirs（JSON 数组）→ 立即重扫该源
     * （成功对账自动清除排除子树/已不再收录的存量行）。excluded=false 即恢复收录。
     * dirPath 支持带尾分隔符的目录形式（文件页 currentDirPath / 条目路径均可直接传入）。
     */
    fun setDirsExcluded(sourceId: Long, dirPaths: Collection<String>, excluded: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: run {
            onDone?.invoke(false); return
        }
        val dirs = dirPaths.map { it.trimEnd('/') }.filter { it.isNotEmpty() && it != "/" }
        if (dirs.isEmpty()) { onDone?.invoke(false); return }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                val current = ScanRoots.parse(dao.getById(sourceId)?.excludedDirs)
                    .map { it.trimEnd('/') }.filter { it.isNotEmpty() }.toMutableSet()
                if (excluded) current += dirs else current -= dirs.toSet()
                dao.update(src.copy(excludedDirs = ScanRoots.encode(current)))
                // 重扫该源：排除时清掉子树索引；恢复时重新收录（cancelAndJoin 防同源并发扫描互踩）
                val scanner = NvScannerHolder.get(appContext)
                scanner.cancelAndJoin(sourceId)
                dao.getById(sourceId)?.let { scanner.startScan(it) }
            }
            onDone?.invoke(result.isSuccess)
        }
    }

    // ---------- 隐私锁/无痕（隐私计划 Task 13）：动作/解锁流/重挂/级联 ----------

    /** dirPath 规范口径：整源（""、"/"）→ ""（wholeSource 判定靠它）；jf:<id> 原样保留（normalize 会加前导斜杠破坏 jf 语义）；其余 NvPaths 归一 */
    private fun canonicalDirOf(dirPath: String): String = when {
        dirPath.trim('/').isEmpty() -> ""
        dirPath.startsWith(NvPrivacyIndex.JF_PREFIX) -> dirPath
        else -> NvPaths.normalize(dirPath)
    }

    /**
     * 规则 dirPath 落库口径：JELLYFIN 目录经 itemIdOf 解析为 "jf:<itemId>"（浏览路径无
     * 文件系统语义）；解析失败 null = fail-closed 拒绝建规则（宁不锁也不锁错位置）。
     */
    private suspend fun resolveDirForRule(sourceId: Long, dirPath: String): String? {
        val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return null
        if (src.type != SourceType.JELLYFIN) return canonicalDirOf(dirPath)
        if (canonicalDirOf(dirPath).isEmpty()) return ""   // 整源规则无路径语义
        val itemId = runCatching { repo.jellyfinItemIdOf(src, dirPath) }.getOrNull() ?: return null
        return NvPrivacyIndex.JF_PREFIX + itemId
    }

    /** keyHash 口径（与 T10 对账 migrate 一致）：HMAC(归一化目录路径)——UNIQUE(sourceId,keyHash) 查重/匹配键 */
    private fun keyHashOf(dir: String): String = privacyCipher.hmac(dir)

    /** 该目录/整源的精确自有规则（菜单标签口径）：双向段边界 = 相等（兼容整源 ""） */
    fun ownRuleViewOf(sourceId: Long, dirPath: String): NvPrivacyIndex.RuleView? {
        val dir = canonicalDirOf(dirPath)
        return privacy.index.views().firstOrNull {
            it.sourceId == sourceId &&
                NvPrivacyIndex.isUnderDir(it.dirPath, dir) &&
                NvPrivacyIndex.isUnderDir(dir, it.dirPath)
        }
    }

    /** 路径是否被未解锁的锁规则罩住（锁定行渲染/FLAG_SECURE/源卡片判定；会话侧随 privacyTick 重组刷新） */
    fun isPathLocked(sourceId: Long, path: String): Boolean =
        privacy.index.isLocked(sourceId, path, NvPrivacySession.unlockedRuleIds())

    /** 源的整源锁（源列表卡片与点击拦截）：整源规则/ORPHANED/BROKEN/jf fail-closed 均呈现为整源锁 */
    fun isSourceLocked(sourceId: Long): Boolean = isPathLocked(sourceId, NvPaths.SEPARATOR)

    /** 当前实际拦截该路径的规则 id（解锁面板要解的清单；无痕-only 不在其中） */
    fun blockingRuleIdsFor(sourceId: Long, path: String): List<Long> =
        runCatching { privacy.blockingRuleIds(sourceId, path) }.getOrDefault(emptyList())

    /** 重新进入文件页时重读 pendingRepoint（T16 可能刚从设置页设定） */
    fun refreshPendingRepoint() {
        viewModelScope.launch {
            _pendingRepoint.value = runCatching { privacyStore.pendingRepointRuleId() }.getOrNull()
        }
    }

    /**
     * 长按菜单/源菜单「加/移除隐私锁」入口。加锁：无 PIN 先引导 PinSetupSheet（完成后原动作
     * 续跑）→ 拍目录内容指纹（复用既有列目录口径，未列过异步 list 一次，>50 自动截断）→
     * upsert 规则 → purgeScope(不含历史) → 必做 A（既有明文历史/收藏迁移密文行）。
     * 移除（终审 C1）：先经 NeedPinVerify 验 PIN，验过才翻转旗标并恢复该范围索引/缩略图。
     */
    fun toggleLock(sourceId: Long, dirPath: String, display: String) {
        viewModelScope.launch {
            val dir = resolveDirForRule(sourceId, dirPath)
            if (dir == null) {
                toastPrivacy(R.string.privacy_action_failed_toast)
                return@launch
            }
            val turnOn = ownRuleViewOf(sourceId, dir)?.lockEnabled != true
            val action = PendingPrivacyAction(sourceId, dir, display, NvPrivacyActionPolicy.Kind.LOCK)
            when (NvPrivacyActionPolicy.offGate(turnOn)) {
                NvPrivacyActionPolicy.OffGate.VerifyPin -> _privacyStep.value = PrivacyStep.NeedPinVerify(action)
                NvPrivacyActionPolicy.OffGate.Proceed -> {
                    val hasPin = runCatching { privacyPinManager.hasPin() }.getOrDefault(false)
                    when (NvPrivacyActionPolicy.pinGate(NvPrivacyActionPolicy.Kind.LOCK, hasPin)) {
                        NvPrivacyActionPolicy.PinGate.NeedPinSetup -> _privacyStep.value = PrivacyStep.NeedPinSetup(action)
                        NvPrivacyActionPolicy.PinGate.Ready -> withContext(Dispatchers.IO) { executeLock(action) }
                    }
                }
            }
        }
    }

    /**
     * 长按菜单/源菜单「开/关无痕」入口。开启：**不要求 PIN**（spec §6 边界表——无 PIN 开无痕
     * 不得死锁）；范围内已有 N 条历史先确认「同时清除」，确认=true 走 purgeScope(includeHistory)
     * 真抹除，dismiss=仅开启不清。关闭（终审 C1）：先经 NeedPinVerify 验 PIN，验过只翻旗标。
     */
    fun toggleIncognito(sourceId: Long, dirPath: String, display: String) {
        viewModelScope.launch {
            val dir = resolveDirForRule(sourceId, dirPath)
            if (dir == null) {
                toastPrivacy(R.string.privacy_action_failed_toast)
                return@launch
            }
            val turnOn = ownRuleViewOf(sourceId, dir)?.incognitoEnabled != true
            val action = PendingPrivacyAction(sourceId, dir, display, NvPrivacyActionPolicy.Kind.INCOGNITO)
            when (NvPrivacyActionPolicy.offGate(turnOn)) {
                NvPrivacyActionPolicy.OffGate.VerifyPin -> _privacyStep.value = PrivacyStep.NeedPinVerify(action)
                NvPrivacyActionPolicy.OffGate.Proceed -> {
                    // 无痕不要求 PIN（spec §6 边界表——无 PIN 开无痕不得死锁）；范围内已有历史先确认「同时清除」
                    val count = withContext(Dispatchers.IO) { countHistoryInScope(sourceId, dir) }
                    when (val gate = NvPrivacyActionPolicy.incognitoGate(count)) {
                        is NvPrivacyActionPolicy.IncognitoGate.ConfirmClear ->
                            _privacyStep.value = PrivacyStep.ConfirmIncognito(action, gate.count)
                        NvPrivacyActionPolicy.IncognitoGate.Proceed ->
                            withContext(Dispatchers.IO) { executeIncognito(action, clearHistory = true) }
                    }
                }
            }
        }
    }

    /**
     * 关分支验 PIN 通过（终审 C1，NvPinVerifySheet 回调）：执行暂存的关动作。
     * 每次操作逐次验 PIN（与 T16 设置页同形态），验过不登记解锁会话（纯验证语义）。
     */
    fun onPinVerifyPassed() {
        val action = (_privacyStep.value as? PrivacyStep.NeedPinVerify)?.action ?: return
        _privacyStep.value = PrivacyStep.Idle
        viewModelScope.launch(Dispatchers.IO) {
            when (action.kind) {
                NvPrivacyActionPolicy.Kind.LOCK -> {
                    applyRuleFlags(action.sourceId, action.dirPath, action.display, NvPrivacyActionPolicy.Kind.LOCK, on = false, fingerprintOverride = null)
                    restoreScopeAfterUnlock(action.sourceId, action.dirPath)
                    toastPrivacy(R.string.privacy_lock_removed_toast)
                }
                NvPrivacyActionPolicy.Kind.INCOGNITO -> {
                    applyRuleFlags(action.sourceId, action.dirPath, action.display, NvPrivacyActionPolicy.Kind.INCOGNITO, on = false, fingerprintOverride = null)
                    toastPrivacy(R.string.privacy_incognito_off_toast)
                }
            }
        }
    }

    /** 关分支验 PIN 面板关闭：放弃本次关动作（规则原状保留） */
    fun onPinVerifyDismissed() {
        if (_privacyStep.value is PrivacyStep.NeedPinVerify) _privacyStep.value = PrivacyStep.Idle
    }

    /** PinSetupSheet 完成（Task 12 产出）：原动作（加锁）续跑——此刻必有 PIN */
    fun onPinSetupCompleted() {
        val action = (_privacyStep.value as? PrivacyStep.NeedPinSetup)?.action ?: return
        _privacyStep.value = PrivacyStep.Idle
        viewModelScope.launch(Dispatchers.IO) { executeLock(action) }
    }

    fun onPinSetupDismissed() {
        _privacyStep.value = PrivacyStep.Idle
    }

    /** 无痕确认对话框：确认 = 同时清历史（NvSecureWipe 真抹除）并开启 */
    fun onIncognitoClearConfirmed() = resolveIncognitoConfirm(clear = true)

    /** 无痕确认对话框：dismiss = 仅开启（既有历史保留原状，此后不再产生新记录） */
    fun onIncognitoKeepRecords() = resolveIncognitoConfirm(clear = false)

    private fun resolveIncognitoConfirm(clear: Boolean) {
        val action = (_privacyStep.value as? PrivacyStep.ConfirmIncognito)?.action ?: return
        _privacyStep.value = PrivacyStep.Idle
        viewModelScope.launch(Dispatchers.IO) { executeIncognito(action, clearHistory = clear) }
    }

    private suspend fun executeLock(action: PendingPrivacyAction) {
        val fingerprint = shootFingerprint(action.sourceId, action.dirPath)
        val ok = applyRuleFlags(
            action.sourceId, action.dirPath, action.display,
            NvPrivacyActionPolicy.Kind.LOCK, on = true, fingerprintOverride = fingerprint,
        )
        if (!ok) {
            Log.w(TAG, "NvPrivacy lock fail-closed (encrypt): source=${action.sourceId}")
            toastPrivacy(R.string.privacy_action_failed_toast)
            return
        }
        // 加锁动作对账清除该范围媒体行+缩略图（不含历史）；随后必做 A：既有明文历史/收藏迁移密文行
        NvPrivacyScan.purgeScope(db, privacy, action.sourceId, action.dirPath, includeHistory = false)
        migratePlainRowsToCipher(action.sourceId, action.dirPath)
        toastPrivacy(R.string.privacy_lock_enabled_toast)
    }

    private suspend fun executeIncognito(action: PendingPrivacyAction, clearHistory: Boolean) {
        val ok = applyRuleFlags(
            action.sourceId, action.dirPath, action.display,
            NvPrivacyActionPolicy.Kind.INCOGNITO, on = true, fingerprintOverride = null,
        )
        if (!ok) {
            Log.w(TAG, "NvPrivacy incognito fail-closed (encrypt): source=${action.sourceId}")
            toastPrivacy(R.string.privacy_action_failed_toast)
            return
        }
        if (clearHistory) {
            NvPrivacyScan.purgeScope(db, privacy, action.sourceId, action.dirPath, includeHistory = true)
        }
        toastPrivacy(R.string.privacy_incognito_on_toast)
    }

    /**
     * 规则行 upsert/删除（旗标翻转共用）：加密 fail-closed 返回 false 绝不落明文；
     * 两旗标全 false → 删规则行。既有行保留原 dirCipher/display/fingerprint（本动作只翻旗标），
     * 新行带 dirCipher 与首拍指纹。
     */
    private suspend fun applyRuleFlags(
        sourceId: Long,
        dirPath: String,
        display: String,
        kind: NvPrivacyActionPolicy.Kind,
        on: Boolean,
        fingerprintOverride: String?,
    ): Boolean {
        val dao = db.privacyRuleDao()
        val dir = canonicalDirOf(dirPath)
        val existing = dao.all().firstOrNull { it.sourceId == sourceId && it.keyHash == keyHashOf(dir) }
        val flags = NvPrivacyActionPolicy.flipped(
            NvPrivacyActionPolicy.Flags(existing?.lockEnabled ?: false, existing?.incognitoEnabled ?: false),
            kind, on,
        )
        if (flags == null) {
            existing?.let {
                dao.deleteById(it.id)
                Log.w(TAG, "NvPrivacy rule removed: source=$sourceId rule=${it.id}")
            }
            bumpPrivacyTick()
            return true
        }
        val dirCipher = privacyCipher.encrypt(dir) ?: return false   // fail-closed：绝不降级明文
        val base = existing ?: PrivacyRuleEntity(
            sourceId = sourceId,
            keyHash = keyHashOf(dir),
            dirCipher = dirCipher,
            displayCipher = privacyCipher.encrypt(display) ?: "",
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(base.copy(
            lockEnabled = flags.lockEnabled,
            incognitoEnabled = flags.incognitoEnabled,
            state = NvPrivacyIndex.STATE_ACTIVE,
            fingerprint = fingerprintOverride ?: base.fingerprint,
        ))
        Log.w(
            TAG,
            "NvPrivacy rule updated: source=$sourceId rule=${base.id} lock=${flags.lockEnabled} incognito=${flags.incognitoEnabled}",
        )
        bumpPrivacyTick()
        return true
    }

    /**
     * 加锁拍目录内容指纹（T10 改名跟随判据；NvFingerprint 超 50 自动截断）。
     * 整源规则不拍（T10 对账跳过整源）；jf 目录指纹 = 子 item id 集哈希口径，Task 17 接管。
     */
    private suspend fun shootFingerprint(sourceId: Long, dirPath: String): String? {
        val dir = canonicalDirOf(dirPath)
        if (dir.isEmpty() || dir.startsWith(NvPrivacyIndex.JF_PREFIX)) return null
        val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return null
        return runCatching {
            val entries = repo.list(src, dir).getOrDefault(emptyList())
            NvFingerprint.of(entries.map { it.name to it.size }, privacyCipher::hmac)
        }.getOrNull()
    }

    /**
     * 必做 A（T9 裁定）：加锁时点把该范围内既有明文历史/收藏行（pathCipher==null 且 path 命中
     * 范围）原位改写为密文行（path=hash、title/pathCipher=密文），全走 NvSecureWipe 管道——
     * 否则锁生效后旧明文仍在盘。LOCAL 源历史/收藏键为 (NULL, 绝对路径)：取行与范围都切绝对口径。
     * jf 目录规则（jf:<id>）按 resolver 链子树反查（Task 17 顺带闭合 T13 迁移盲区）；
     * 索引未热（链解不出）回退前缀口径（旧行为，恒不命中——锁定侧整源罩与 T14 查询层兜底）。
     * Keystore 不可用（cipherRow null）fail-closed 保留明文行不删用户数据（W 级留痕）。
     */
    private suspend fun migratePlainRowsToCipher(sourceId: Long, dirPath: String) {
        runCatching {
            val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return
            val isLocal = src.type == SourceType.LOCAL
            val rowSourceId: Long? = if (isLocal) null else sourceId
            // jf 键（原值 "jf:<itemId>"）不能过 NvPaths.normalize（会被当路径改写成 "/jf:..."）
            val jfKey = dirPath.trim().takeIf { it.startsWith(NvPrivacyIndex.JF_PREFIX) }
            val scopeDir = when {
                canonicalDirOf(dirPath).isEmpty() -> ""   // 整源：isUnderDir 对根恒真
                isLocal -> NvPaths.normalize(repo.localAbsolutePath(src, dirPath))
                jfKey != null -> jfKey
                else -> NvPaths.normalize(dirPath)
            }
            fun inScope(p: String): Boolean =
                if (jfKey != null) {
                    privacy.index.withinRuleScope(jfKey, p)
                } else {
                    NvPrivacyIndex.isUnderDir(p, scopeDir)
                }
            var migrated = 0
            NvSecureWipe.wiping(db) {
                val hDao = db.watchHistoryDao()
                hDao.allForKey(rowSourceId)
                    .filter { it.pathCipher == null && inScope(it.path) }
                    .forEach { row ->
                        val hashed = NvHistoryCipherRows.cipherRow(privacyCipher, row)
                        when {
                            hashed == null -> Log.w(TAG, "NvPrivacy history migrate fail-closed (plain row kept)")
                            hDao.findByKey(row.sourceId, hashed.path) == null ->
                                hDao.insert(hashed)          // id 同原行 → REPLACE 原位替换
                            else -> hDao.deleteById(row.id)  // 加锁前后明文+密文并存：保留密文行删明文行
                        }
                        if (hashed != null) migrated++
                    }
                val fDao = db.favoriteDao()
                fDao.allForKey(rowSourceId)
                    .filter { it.pathCipher == null && inScope(it.path) }
                    .forEach { row ->
                        val hashed = NvFavoriteCipherRows.cipherRow(privacyCipher, row)
                        when {
                            hashed == null -> Log.w(TAG, "NvPrivacy favorite migrate fail-closed (plain row kept)")
                            fDao.findByKey(row.sourceId, hashed.path) == null -> fDao.insert(hashed)
                            else -> fDao.deleteByIds(listOf(row.id))
                        }
                        if (hashed != null) migrated++
                    }
            }
            if (migrated > 0) Log.w(TAG, "NvPrivacy plain rows migrated to cipher: source=$sourceId count=$migrated")
        }.onFailure { Log.w(TAG, "NvPrivacy plain row migration skipped: ${it.javaClass.simpleName}") }
    }

    /** 范围内已有历史行数（明文+密文都算；无痕「同时清除 N 条」确认的 N）。jf 规则同迁移口径走 resolver 链反查 */
    private suspend fun countHistoryInScope(sourceId: Long, dirPath: String): Int {
        val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return 0
        val rowSourceId: Long? = if (src.type == SourceType.LOCAL) null else sourceId
        // jf 键不过 normalize（会被当路径改写成 "/jf:..."），同 migratePlainRowsToCipher 口径
        val jfKey = dirPath.trim().takeIf { it.startsWith(NvPrivacyIndex.JF_PREFIX) }
        val scopeDir = when {
            canonicalDirOf(dirPath).isEmpty() -> ""
            src.type == SourceType.LOCAL -> NvPaths.normalize(repo.localAbsolutePath(src, dirPath))
            jfKey != null -> jfKey
            else -> NvPaths.normalize(dirPath)
        }
        return runCatching {
            db.watchHistoryDao().allForKey(rowSourceId).count { row ->
                val p = if (NvHistoryCipherRows.isCiphered(row)) NvHistoryCipherRows.plainPath(privacyCipher, row) else row.path
                p != null && (
                    if (jfKey != null) {
                        privacy.index.withinRuleScope(jfKey, p)
                    } else {
                        NvPrivacyIndex.isUnderDir(p, scopeDir)
                    }
                    )
            }
        }.getOrDefault(0)
    }

    // ── 解锁流（文件页锁定目录点击 / 源列表整源锁点击） ──

    /**
     * 文件页锁定条目点击 → 解锁面板成功回调。范围上下文（sourceId+目录）由本入口持有：
     * 必做 B（T10 裁定）在此重置该范围占位缩略图行，并触发该范围补扫（T11 管道）。
     * [dirPath] 为目录路径时解锁后放行进入；null（文件行边缘态）仅登记解锁不导航。
     */
    fun onBrowserUnlocked(ruleIds: List<Long>, sourceId: Long, dirPath: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            ruleIds.forEach(NvPrivacySession::unlock)
            dirPath?.let { resetBlockedThumbsInScope(sourceId, it) }
            triggerUnlockedRescan(ruleIds)
            bumpPrivacyTick()
            if (dirPath != null) {
                listDir(sourceId, canonicalDirOf(dirPath).trim('/').split('/').filter { it.isNotEmpty() })
            }
        }
    }

    /** 源列表整源锁点击 → 解锁面板成功回调（范围 = 整源；解锁后进源浏览） */
    fun onSourceUnlocked(ruleIds: List<Long>, sourceId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            ruleIds.forEach(NvPrivacySession::unlock)
            resetBlockedThumbsInScope(sourceId, "")
            triggerUnlockedRescan(ruleIds)
            bumpPrivacyTick()
            _sources.value.firstOrNull { it.id == sourceId }?.let { openSource(it) }
        }
    }

    /** 解锁面板「忘记密码」重置完成回调：privacy_rules 已真抹除全清——刷新当前视图即可 */
    fun onPrivacyReset() {
        bumpPrivacyTick()
        retryListing()
    }

    /**
     * 必做 B（T10 裁定）：范围内被 T10 Thumbnailer 隐私拦截置成「DONE+null 路径」占位的
     * 缩略图行重置为 NONE——解锁后重新走正常抽帧认领，否则封面永远缺失。
     * DONE+null 双条件同时满足才动（正常管线不产生该形态，不误伤真 DONE 行）。
     */
    private suspend fun resetBlockedThumbsInScope(sourceId: Long, dirPath: String) {
        runCatching {
            val dir = canonicalDirOf(dirPath)
            val miDao = db.mediaItemDao()
            val paths = miDao.allPaths(sourceId).filter { NvPrivacyIndex.isUnderDir(it, dir) }
            var reset = 0
            paths.chunked(500)
                .flatMap { runCatching { miDao.findByPaths(sourceId, it) }.getOrDefault(emptyList()) }
                .forEach { row ->
                    if (row.thumbState == ThumbState.DONE.name && row.thumbnailPath == null) {
                        miDao.resetThumb(row.id)
                        reset++
                    }
                }
            if (reset > 0) Log.w(TAG, "NvPrivacy thumb placeholders reset: source=$sourceId count=$reset")
        }.onFailure { Log.w(TAG, "NvPrivacy thumb reset skipped: ${it.javaClass.simpleName}") }
    }

    /** 移除锁/解锁后的范围恢复：占位缩略图重置 + 该源补扫（媒体行在加锁时已被 purge） */
    private suspend fun restoreScopeAfterUnlock(sourceId: Long, dirPath: String) {
        resetBlockedThumbsInScope(sourceId, dirPath)
        runCatching {
            val scanner = NvScannerHolder.get(appContext)
            scanner.cancelAndJoin(sourceId)
            dao.getById(sourceId)?.let { scanner.startScan(it) }
        }.onFailure { Log.w(TAG, "NvPrivacy scope restore scan failed: source=$sourceId (${it.javaClass.simpleName})") }
    }

    /**
     * 解锁补扫（T11 交接口径，与 PlayerActivity 同形态）：cancelAndJoin→startScan；
     * 每步完成前经 NvGateWiring.rescanShouldCommit 世代守卫——重锁（世代+1）后的迟到补扫
     * 不得继续写库，只有世代未变且规则仍在解锁集才 markRescanned。
     */
    private suspend fun triggerUnlockedRescan(ruleIds: List<Long>) {
        if (ruleIds.isEmpty()) return
        val genAtStart = NvPrivacySession.generation
        for (ruleId in ruleIds) {
            val stillValid = NvGateWiring.rescanShouldCommit(
                genAtStart,
                NvPrivacySession.generation,
                ruleId in NvPrivacySession.unlockedRuleIds(),
            )
            if (!stillValid) {
                Log.w(TAG, "NvPrivacy unlocked rescan aborted (relocked) rule=$ruleId")
                return
            }
            val view = privacy.index.views().firstOrNull { it.id == ruleId }
            if (view == null) {
                Log.w(TAG, "NvPrivacy unlocked rescan rule vanished rule=$ruleId")
                continue
            }
            runCatching {
                val scanner = NvScannerHolder.get(appContext)
                scanner.cancelAndJoin(view.sourceId)
                dao.getById(view.sourceId)?.let { scanner.startScan(it) }
            }.onFailure { Log.w(TAG, "NvPrivacy unlocked rescan failed rule=$ruleId err=${it.javaClass.simpleName}") }
            if (NvGateWiring.rescanShouldCommit(
                    genAtStart,
                    NvPrivacySession.generation,
                    ruleId in NvPrivacySession.unlockedRuleIds(),
                )
            ) {
                NvPrivacySession.markRescanned(ruleId)
            }
        }
    }

    // ── pendingRepoint（T16 设定 → 长按菜单首项「挂载到此处」完成回传） ──

    /**
     * 「挂载到此处」：完成 T16 存下的重挂——更新该规则 keyHash/dirCipher/displayCipher/
     * fingerprint + state=ACTIVE，顺手核验指纹（匹配与否只作日志区分，均采纳目标目录现值），
     * 然后清 pendingRepoint。jf 规则重挂到文件页目录无路径语义，本入口 fail-closed 拒绝
     * （T16 设置页承担 jf 规则的重挂/解除出口）。终审 I1：重挂必须同源——规则挂在 A 源而
     * 当前浏览 B 源时拒绝（跨源换绑会让 A 源静默失锁），保留 pendingRepoint 供用户回到
     * 同源目录再完成。
     */
    fun completeRepointAt(sourceId: Long, entry: NvFileEntry) {
        val ruleId = _pendingRepoint.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val rule = db.privacyRuleDao().all().firstOrNull { it.id == ruleId }
            if (rule == null) {
                _pendingRepoint.value = null
                runCatching { privacyStore.setPendingRepointRuleId(null) }
                return@launch
            }
            if (!NvPrivacyActionPolicy.repointSameSource(rule.sourceId, sourceId)) {
                Log.w(
                    TAG,
                    "NvPrivacy repoint rejected (cross-source): rule=$ruleId ruleSource=${rule.sourceId} browseSource=$sourceId",
                )
                toastPrivacy(R.string.privacy_repoint_cross_source_toast)
                return@launch   // 保留 pendingRepoint：回同源目录仍可完成
            }
            val src = _sources.value.firstOrNull { it.id == sourceId }?.entity
            if (src == null || src.type == SourceType.JELLYFIN) {
                Log.w(TAG, "NvPrivacy repoint rejected: rule=$ruleId source=$sourceId")
                toastPrivacy(R.string.privacy_action_failed_toast)
                return@launch
            }
            val dir = canonicalDirOf(entry.path)
            val fresh = shootFingerprint(sourceId, dir)
            val dirCipher = privacyCipher.encrypt(dir)
            if (dirCipher == null) {
                Log.w(TAG, "NvPrivacy repoint fail-closed (encrypt) rule=$ruleId")
                toastPrivacy(R.string.privacy_action_failed_toast)
                return@launch
            }
            val oldFp = rule.fingerprint
            db.privacyRuleDao().upsert(rule.copy(
                keyHash = keyHashOf(dir),
                dirCipher = dirCipher,
                displayCipher = privacyCipher.encrypt(entry.name) ?: rule.displayCipher,
                fingerprint = NvPrivacyActionPolicy.repointFingerprint(oldFp, fresh),
                state = NvPrivacyIndex.STATE_ACTIVE,
            ))
            _pendingRepoint.value = null
            runCatching { privacyStore.setPendingRepointRuleId(null) }
            Log.w(
                TAG,
                "NvPrivacy repoint done: rule=$ruleId source=$sourceId fpMatched=${NvPrivacyActionPolicy.repointMatched(oldFp, fresh)}",
            )
            toastPrivacy(R.string.privacy_repoint_done_toast)
        }
    }

    /** 删除目录的级联规则清理：精确 + 嵌套（内存解密段边界匹配）；解不开（BROKEN）不动 fail-closed */
    private suspend fun deletePrivacyRulesUnderDir(sourceId: Long, dirPath: String) {
        val doomedDir = NvPaths.normalize(dirPath)
        val dao = db.privacyRuleDao()
        dao.all().forEach { rule ->
            if (rule.sourceId != sourceId) return@forEach
            val dir = privacyCipher.decrypt(rule.dirCipher)?.let { NvPaths.normalize(it) } ?: return@forEach
            if (dir == doomedDir || dir.startsWith("$doomedDir/")) {
                dao.deleteById(rule.id)
                Log.w(TAG, "NvPrivacy rule cascaded (dir deleted): rule=${rule.id} source=$sourceId")
            }
        }
    }

    /** 本地目录改名后的新源内相对路径：同级 + 新名（NvPaths 归一口径） */
    private fun renamedRelPathOf(entry: NvFileEntry, newName: String): String =
        NvPaths.join(NvPaths.parentOf(NvPaths.normalize(entry.path)), newName)

    /**
     * 应用内目录改名（LOCAL）的隐私规则迁移（spec §6「应用内改名→DB 事务迁移」）：
     * 精确命中规则与嵌套子目录规则整体改写新路径前缀，精确命中同步展示名。
     * 加密失败 fail-closed 不动规则（外部改名指纹对账兜底）；解不开（BROKEN）不动。
     */
    private suspend fun migratePrivacyRulesForRename(
        sourceId: Long,
        oldDirPath: String,
        newDirPath: String,
        newDisplayName: String,
    ) {
        val daoDb = db.privacyRuleDao()
        val oldDir = NvPaths.normalize(oldDirPath)
        val newDir = NvPaths.normalize(newDirPath)
        daoDb.all().forEach { rule ->
            if (rule.sourceId != sourceId) return@forEach
            val dir = privacyCipher.decrypt(rule.dirCipher)?.let { NvPaths.normalize(it) } ?: return@forEach
            val isSelf = dir == oldDir
            val isUnder = dir.startsWith("$oldDir/")
            if (!isSelf && !isUnder) return@forEach
            val replaced = if (isSelf) newDir else newDir + dir.substring(oldDir.length)
            val dirCipher = privacyCipher.encrypt(replaced) ?: return@forEach   // fail-closed：留待扫描对账
            daoDb.upsert(rule.copy(
                keyHash = keyHashOf(replaced),
                dirCipher = dirCipher,
                displayCipher = if (isSelf) (privacyCipher.encrypt(newDisplayName) ?: rule.displayCipher) else rule.displayCipher,
                state = NvPrivacyIndex.STATE_ACTIVE,
            ))
            Log.w(TAG, "NvPrivacy rule migrated by rename: rule=${rule.id} source=$sourceId")
        }
    }

    private suspend fun toastPrivacy(resId: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, appContext.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 目录列表统一入口。[fallbackToRootOnFailure] 仅进源恢复上次目录时置 true：
     * 持久化条目失效（目录不存在/暂不可列）时回落根目录——回落导航本身不带回落标记，
     * 根目录列失败照常显示错误，杜绝循环回落。
     */
    private fun listDirInternal(
        sourceId: Long,
        breadcrumb: List<String>,
        entity: SourceEntity?,
        fallbackToRootOnFailure: Boolean = false,
    ) {
        val src = entity ?: _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return
        val srcProtocol = NvProtocol.of(src.type) ?: NvProtocol.SMB
        val current = _browser.value
        // 2026-10-26 用户反馈：换目录 = 退出搜索——此前导航保留搜索词，新目录内容被旧词
        // 过滤成「没有匹配的文件」，点进搜索命中的文件夹反而像空目录。同目录重列
        // （retryListing / 扫描完成自动刷新 / 计量开关变化）不算导航，保留搜索态不打扰；
        // 子目录递归搜索随换目录作废（restartSubSearchIfNeeded 检 searchActive 自然不再重启）。
        searchJob?.cancel()
        searchJob = null
        val sameDir = current != null && current.sourceId == sourceId && current.breadcrumb == breadcrumb
        _browser.value =
            if (current?.sourceId == sourceId) {
                current.copy(
                    breadcrumb = breadcrumb,
                    loading = true,
                    error = null,
                    noPermission = false,
                    searching = false,
                    searchSubResults = emptyList(),
                    searchActive = sameDir && current.searchActive,
                    searchQuery = if (sameDir) current.searchQuery else "",
                )
            } else {
                NvBrowserUiState(sourceId = sourceId, source = toUiSource(src), breadcrumb = breadcrumb)
            }
        rememberBrowserLocation(sourceId, breadcrumb)   // 2026-09-27 进程死亡恢复锚点
        browseJob?.cancel()
        // BUG-C-R6-17：切目录即取消目录外旁路抽帧（用户所在文件夹优先，划走的不浪费带宽）
        cancelOrphanBrowserThumbs(joinToPath(breadcrumb))
        // BUG-C-R6-04：请求代次——旧目录的取消/失败/成功结果一律不得覆盖新目录状态
        //（原防护只比对 sourceId，同源快速切目录时旧结果会写进新面包屑下）
        val gen = ++browseGen
        val targetBreadcrumb = breadcrumb.toList()
        browseJob = viewModelScope.launch {
            val result = try {
                repo.list(src, joinToPath(breadcrumb))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // 取消是正常流程（换目录），不得包装成失败
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (gen != browseGen || _browser.value?.sourceId != sourceId ||
                _browser.value?.breadcrumb != targetBreadcrumb
            ) {
                return@launch
            }
            result.fold(
                onSuccess = { entries ->
                    _browser.update {
                        it?.copy(loading = false, entries = toUiEntries(entries), error = null)
                    }
                    // 2026-09-29 每次成功导航目录即写回「上次浏览目录」——显式点根/点虚拟共享
                    // 同为用户操作，按其导航结果覆盖记录（下次进源按用户操作为准）
                    persistLastDir(sourceId, breadcrumb)
                    // 2026-09-27 用户需求：列目录成功即整目录批量置顶抽封面（不等滚到可见行）
                    primeThumbsForFolder(sourceId, entries)
                    restartSubSearchIfNeeded(sourceId, breadcrumb)
                },
                onFailure = { e ->
                    // 2026-09-29 恢复的上次目录已失效：回落根目录重列（不带回落标记防环）
                    if (fallbackToRootOnFailure && breadcrumb.isNotEmpty()) {
                        listDirInternal(sourceId, emptyList(), src)
                        return@launch
                    }
                    val err = mapConnectError(e, srcProtocol)
                    // 本地目录无权限判定（i18n 后按错误标题键识别，与原中文 contains 口径等价：
                    // 「目录不可读」「无访问权限」两类标题 + 消息含权限字样的分支均落在这两键）
                    val permissionProblem = src.type == SourceType.LOCAL &&
                        (err.title.res == R.string.files_err_dir_unreadable_title ||
                            err.title.res == R.string.files_err_no_access_title)
                    _browser.update {
                        if (permissionProblem) {
                            // 本地目录不可读：进入 2.8 无权限态（去授权）
                            it?.copy(loading = false, error = null, noPermission = true)
                        } else {
                            it?.copy(
                                loading = false,
                                error = err,
                                noPermission = false,
                            )
                        }
                    }
                },
            )
        }
    }

    fun onBreadcrumbNavigate(depth: Int) {
        val b = _browser.value ?: return
        if (depth < 0) {
            closeBrowser()
        } else {
            listDir(b.sourceId, b.breadcrumb.take(depth + 1))
        }
    }

    /**
     * 浏览器返回（2026-10-19 起系统返回键专用入口；左上角箭头已改为直接 closeBrowser
     * 回源列表——箭头 = 上一级页面，返回键 = 文件上一级）：
     * 子目录回上一级；源根（面包屑空）关闭浏览器回源列表。
     * 目标状态由纯函数 [browserBackTarget] 计算（NvBrowserPolicyTest 锁定）。
     */
    fun onBrowserBack() {
        val b = _browser.value ?: return
        val target = browserBackTarget(b.breadcrumb)
        if (target == null) closeBrowser() else listDirInternal(b.sourceId, target, null)
    }

    /**
     * SMB 未选共享源：进入虚拟共享目录，保留源配置及已有索引身份。
     */
    fun selectShareAndBrowse(sourceId: Long, shareName: String) {
        if (_browser.value?.sourceId != sourceId) return
        listDir(sourceId, listOf("@share", shareName))
    }

    fun retryListing() {
        val b = _browser.value ?: return
        listDir(b.sourceId, b.breadcrumb)
    }

    // ---------- C5 二期 5.1 类型过滤 ----------

    /** 过滤器 Sheet「确定」：写入勾选组合（六类全勾 = 无过滤；全不勾 = 仅剩文件夹行） */
    fun onTypeFilterConfirm(enabled: Set<NvFileType>) {
        _browser.update { it?.copy(enabledTypes = enabled) }
    }

    /** Chip「清除全部」/ Sheet「重置」：恢复全勾 */
    fun resetTypeFilter() {
        _browser.update { it?.copy(enabledTypes = allFileTypes) }
    }

    // ---------- C5 二期 5.3 目录内搜索 ----------

    fun enterSearch() {
        _browser.update { it?.copy(searchActive = true) }
    }

    /** 退出搜索：恢复完整列表，保留类型过滤与排序态 */
    fun exitSearch() {
        searchJob?.cancel()
        searchJob = null
        _browser.update {
            it?.copy(
                searchActive = false,
                searchQuery = "",
                searching = false,
                searchSubResults = emptyList(),
            )
        }
    }

    /**
     * 搜索框输入：当前目录条目即时过滤（applyListPipeline），子目录递归搜索 150ms 防抖后启动。
     */
    fun onSearchQueryChange(text: String) {
        _browser.update { it?.copy(searchQuery = text) }
        searchJob?.cancel()
        if (text.isBlank()) {
            _browser.update { it?.copy(searching = false, searchSubResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val b = _browser.value ?: return@launch
            if (!b.searchActive || !b.searchIncludeSubs) return@launch
            runSubSearch(b.sourceId, joinToPath(b.breadcrumb), text.trim())
        }
    }

    /** 「含子目录」开关：开启且有词立即搜；关闭即取消并清空子目录命中 */
    fun onSearchIncludeSubsChange(enabled: Boolean) {
        _browser.update { it?.copy(searchIncludeSubs = enabled) }
        searchJob?.cancel()
        val b = _browser.value ?: return
        val q = b.searchQuery.trim()
        if (!enabled || q.isEmpty()) {
            _browser.update { it?.copy(searching = false, searchSubResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch { runSubSearch(b.sourceId, joinToPath(b.breadcrumb), q) }
    }

    /**
     * 列目录成功后：搜索态未退且「含子目录」开 → 以新目录为根重启子目录搜索。
     * 2026-10-26 起「换目录即退出搜索」：searchActive 只在同目录重列（retryListing 等）
     * 后仍为 true，故本函数实际仅服务同目录刷新场景；换目录后条件恒 false 为 no-op。
     */
    private fun restartSubSearchIfNeeded(sourceId: Long, breadcrumb: List<String>) {
        val b = _browser.value ?: return
        val q = b.searchQuery.trim()
        if (b.searchActive && b.searchIncludeSubs && q.isNotEmpty()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSubSearch(sourceId, joinToPath(breadcrumb), q) }
        }
    }

    /**
     * 子目录递归搜索：自当前目录 BFS，深度 ≤ [SEARCH_MAX_DEPTH]，结果 ≤ [SEARCH_RESULT_LIMIT]；
     * 查询变更/退出搜索/切换目录即取消（不留过期结果）。
     */
    private suspend fun runSubSearch(sourceId: Long, rootPath: String, needle: String) {
        val src = _sources.value.firstOrNull { it.id == sourceId }?.entity ?: return
        _browser.update { it?.copy(searching = true, searchSubResults = emptyList()) }
        val results = mutableListOf<NvFileEntry>()
        try {
            val queue = ArrayDeque<Pair<String, Int>>()   // 目录路径 to 相对当前目录的深度
            queue.add(NvPaths.asDirectory(rootPath) to 0)
            while (queue.isNotEmpty() && results.size < SEARCH_RESULT_LIMIT) {
                coroutineContext.ensureActive()
                val (dir, depth) = queue.removeFirst()
                val entries = repo.list(src, dir).getOrDefault(emptyList())
                for (e in entries) {
                    if (!e.isDir) {
                        if (matchesQuery(e.name, needle)) {
                            results += NvFileEntry(
                                name = e.name, path = e.path, isDir = false,
                                size = e.size, mtimeMs = e.lastModified,
                            )
                            if (results.size >= SEARCH_RESULT_LIMIT) break
                        }
                    } else if (depth < SEARCH_MAX_DEPTH &&
                        NvPaths.asDirectory(e.path) != NvPaths.asDirectory(dir)
                    ) {
                        queue.add(e.path to depth + 1)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce   // 过期任务：结果不回填
        } catch (t: Throwable) {
            // 单个目录列举失败：返回已收集的部分结果，不打断搜索
        }
        _browser.update { it?.copy(searching = false, searchSubResults = results) }
    }

    // ---------- C5 二期 5.4 多选 ----------

    /** 长按行进入多选：快照当前过滤/搜索态，退出时恢复 */
    fun enterSelectionMode(first: NvFileEntry) {
        _browser.update { cur ->
            cur?.copy(
                selectionMode = true,
                selectedPaths = setOf(first.path),
                preSelectionQuery = snapshotListQuery(cur),
            )
        }
    }

    private fun snapshotListQuery(s: NvBrowserUiState): NvListQuery = NvListQuery(
        enabledTypes = s.enabledTypes,
        searchQuery = s.searchQuery,
        searchActive = s.searchActive,
    )

    fun toggleSelected(path: String) {
        _browser.update {
            if (it == null) {
                null
            } else {
                it.copy(
                    selectedPaths =
                    if (path in it.selectedPaths) it.selectedPaths - path else it.selectedPaths + path,
                )
            }
        }
    }

    fun selectAll(paths: Collection<String>) {
        _browser.update { it?.copy(selectedPaths = paths.toSet()) }
    }

    fun invertSelection(paths: Collection<String>) {
        _browser.update { it?.copy(selectedPaths = paths.toSet() - it.selectedPaths) }
    }

    /** 返回键第一优先：清空勾选但停留在多选模式 */
    fun clearSelection() {
        _browser.update { it?.copy(selectedPaths = emptySet()) }
    }

    /** 退出多选：恢复进入前的过滤/搜索态（排序为全局持久化态，多选期间不可变，无需恢复） */
    fun exitSelectionMode() {
        _browser.update { cur ->
            val snap = cur?.preSelectionQuery
            cur?.copy(
                selectionMode = false,
                selectedPaths = emptySet(),
                enabledTypes = snap?.enabledTypes ?: cur.enabledTypes,
                searchActive = snap?.searchActive ?: cur.searchActive,
                searchQuery = snap?.searchQuery ?: cur.searchQuery,
                searching = false,
                searchSubResults = cur.searchSubResults,
                preSelectionQuery = null,
            )
        }
    }

    /** 批量加入收藏：仅文件（跳过文件夹），已在收藏中的跳过（含 hash 键密文行），成功 Toast 数量 */
    fun batchAddFavorites(sourceId: Long, entries: List<NvFileEntry>) {
        viewModelScope.launch {
            var added = 0
            val failed = runCatching {
                // 隐私口径（Task 9 修复轮）：经 NvFavoriteDirect 逐条 addIfAbsent（各自事务，
                // 双键查重防明文+密文两行并存）——不再外包单一大事务
                entries.filter { !it.isDir }.forEach { e ->
                    val (keySourceId, keyPath) = mediaKeyOf(sourceId, e)
                    val added1 = NvFavoriteDirect.addIfAbsent(
                        db,
                        privacy,
                        FavoriteEntity(
                            sourceId = keySourceId,
                            path = keyPath,
                            title = e.name,
                            kind = when (NvFileRouter.kindOf(e.name)) {
                                NvFileRouter.Kind.AUDIO -> WatchKind.AUDIO.name
                                else -> WatchKind.MOVIE.name
                            },
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                    if (added1) added++
                }
                false
            }.getOrElse {
                // BUG-C-R6-11：DB 失败被吞后 added==0 会误报「均已在收藏中」，失败要分流提示
                true
            }
            Toast.makeText(
                appContext,
                when {
                    failed -> appContext.getString(R.string.files_fav_failed)
                    added > 0 -> appContext.resources.getQuantityString(
                        R.plurals.files_fav_added, added, added,
                    )
                    else -> appContext.getString(R.string.files_fav_all_dups)
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 批量删除（仅 LOCAL 源调用）：逐个删除后刷新目录并退出多选（含级联清理与目录级联规则清理，Task 13） */
    fun deleteSelectedLocal(sourceId: Long, entries: List<NvFileEntry>) {
        viewModelScope.launch {
            var failed = 0
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val src = _sources.value.firstOrNull { it.id == sourceId }
                if (src != null) {
                    entries.forEach { e ->
                        val abs = File(NvPlaybackLauncher.localPath(src.entity.shareOrPath, e.path))
                        val ok = runCatching { abs.delete() }.getOrDefault(false)
                        if (ok) {
                            NvSecureWipe.wiping(db) {
                                if (e.isDir) deletePrivacyRulesUnderDir(sourceId, e.path)
                                cascadePurgeLocalKeys(sourceId, abs.absolutePath)
                            }
                        } else failed++
                    }
                }
            }
            if (failed > 0) Toast.makeText(
                appContext,
                appContext.resources.getQuantityString(R.plurals.files_delete_failed_count, failed, failed),
                Toast.LENGTH_SHORT,
            ).show()
            exitSelectionMode()
            retryListing()
        }
    }

    /** 文件行点击 → 播放地址：NAS 源 novax://<sourceId><encoded path>；LOCAL 直接绝对路径。
     *  与 NvPlaybackLauncher.resumeFrom 同构（同一构造函数），保证历史/续播键一致不重复 */
    fun playUrl(sourceId: Long, entry: NvFileEntry): String {
        val src = _sources.value.firstOrNull { it.id == sourceId } ?: return entry.path
        return when (src.entity.type) {
            SourceType.LOCAL ->
                NvPlaybackLauncher.localPath(src.entity.shareOrPath, entry.path)
            else ->
                NvPlaybackLauncher.novaxUrl(src.id, entry.path)
        }
    }

    // ---------- 添加源向导 ----------
    fun openWizard() {
        _wizard.value = NvWizardUiState(open = true)
        _wizardBrowser.value = null
    }

    fun editSource(sourceId: Long, duplicate: Boolean = false) {
        viewModelScope.launch {
            val source = dao.getById(sourceId) ?: return@launch
            // N3 存量兼容（2026-10-09）：历史 host 可能带 http(s):// 前缀——编辑回显剥离为裸 host +
            // 开关置位 + URL 内嵌端口兜底拆分；保存按新规则（裸 host / https://host 前缀），连接不坏
            val parsed = when (source.type) {
                SourceType.WEBDAV, SourceType.JELLYFIN -> parseHostField(source.host)
                else -> null
            }
            _wizard.value = NvWizardUiState(
                open = true, step = 1, original = if (duplicate) null else source,
                protocol = NvProtocol.of(source.type) ?: return@launch,
                nickname = if (duplicate) appContext.getString(R.string.files_nick_copy, source.nickname)
                else source.nickname,
                host = parsed?.host ?: source.host,
                https = parsed?.https ?: false,
                port = source.port.takeIf { it > 0 }?.toString()
                    ?: parsed?.port?.let { clampPort(it) }.orEmpty(),
                domain = source.domain, user = source.username, pass = NvSecrets.decrypt(source.password),
                anonymous = source.username.isBlank(), shareOrPath = source.shareOrPath,
                // 已存扫描根回填（副本沿用同组目录），Step2 可继续增删
                selectedScanDirs = ScanRoots.parse(source.scanRoots).toSet(),
                // 复制源：刮削开关随原源带入（BUG-C-R6-12）
                copyScrapeEnabled = if (duplicate) source.scrapeEnabled else null,
            )
            _wizardBrowser.value = null
        }
    }

    fun closeWizard() {
        wizardBrowseJob?.cancel()
        _wizard.value = NvWizardUiState()
        _wizardBrowser.value = null
    }

    /** 点发现设备 → 直填向导表单（名称 + IP + 端口），停在表单步等用户补凭据 */
    fun onDeviceClick(device: NvDiscoveredDevice) {
        _wizard.value = NvWizardUiState(
            open = true,
            step = 1,
            protocol = device.protocol,
            host = device.ip,
            port = device.port.takeIf { it in 1..65535 }?.toString().orEmpty(),
            nickname = device.name,
        )
    }

    fun onProtocolSelect(protocol: NvProtocol) {
        // 2026-10-09 批次二评审（T6）：Jellyfin 默认端口 8096 不为大众所知，选协议时直接预填。
        // 与 onHttps 端口联动同口径：端口为空或等于另一态默认（8096/443 互为另一态，覆盖
        // 切过开关再回协议步重选的场景）才改写，手改过的值不覆盖；其余协议不预填（默认端口
        // 占位符表意已足）。选协议本就整表重置，仅端口按上述规则保留/预填
        val port = when (protocol) {
            NvProtocol.Jellyfin ->
                _wizard.value.port.takeUnless { it.isBlank() || it == defaultPortLabel(protocol, true) }
                    ?: defaultPortLabel(protocol, false)
            else -> _wizard.value.port
        }
        _wizard.value = NvWizardUiState(open = true, step = 1, protocol = protocol, port = port)
    }

    /** E3-10（发版审查）：本地源存储权限被拒——留在协议步，复用既有 error 槽提示。 */
    fun onLocalPermissionDenied() {
        _wizard.update {
            it.copy(
                error = NvWizardError(
                    UiMessage(R.string.files_err_storage_perm_title),
                    UiMessage(R.string.files_err_storage_perm_msg),
                ),
            )
        }
    }

    // N3 粘贴解析（2026-10-09）：WebDAV/Jellyfin 输入带 http(s):// 前缀时自动剥离 + 开关置位 +
    // 端口拆分（如 https://host:8443 → host/端口/开关开）；SMB/SFTP 无 scheme 概念，输入原样保留
    fun onHost(v: String) = _wizard.update { w ->
        val base = w.copy(host = v, testSuccess = false, shares = emptyList(), sharesError = null)
        if (w.protocol != NvProtocol.WebDav && w.protocol != NvProtocol.Jellyfin) return@update base
        val p = parseHostField(v)
        // 无 scheme 前缀（手动逐字输入）不做任何改写，避免打字被拆散；有前缀视为整段粘贴
        if (p.https == null) return@update base
        base.copy(
            host = p.host,
            https = p.https,
            port = p.port?.let { clampPort(it) } ?: w.port,
        )
    }
    fun onPort(v: String) = _wizard.update { it.copy(port = clampPort(v), testSuccess = false) }

    /** HTTPS 开关（WebDAV/Jellyfin）：端口联动仅在端口为空或等于另一态默认值时改写，手改过不覆盖 */
    fun onHttps(v: Boolean) = _wizard.update { w ->
        val otherDefault = defaultPortLabel(w.protocol, !v)
        val port = w.port.takeUnless { it.isBlank() || it == otherDefault }
            ?: defaultPortLabel(w.protocol, v)
        w.copy(https = v, port = port, testSuccess = false)
    }

    // N3 host 字段解析（2026-10-09）：剥 http(s):// 前缀 + 拆 ":端口"。仅单个 ':' 且其后全数字
    // 才拆（IPv6 裸串多冒号不拆，'[::1]' 字面量不拆）；无前缀时 https=null（调用方视为非粘贴输入）
    private fun parseHostField(raw: String): HostParsed {
        val t = raw.trim()
        val lowered = t.lowercase()
        val https: Boolean? = when {
            lowered.startsWith("https://") -> true
            lowered.startsWith("http://") -> false
            else -> null
        }
        var rest = when (https) {
            true -> t.substring("https://".length)
            false -> t.substring("http://".length)
            null -> t
        }
        var port: String? = null
        if (!rest.startsWith("[") && rest.count { it == ':' } == 1) {
            val candidate = rest.substringAfter(':')
            if (candidate.isNotEmpty() && candidate.all { it.isDigit() }) {
                port = candidate
                rest = rest.substringBefore(':')
            }
        }
        return HostParsed(rest.trimEnd('/'), port, https)
    }

    // BUG-C-R6-09 口径不变：端口只留数字、越界夹回 65535（onHost 拆分与 onPort 手输共用）
    private fun clampPort(v: String): String {
        val digits = v.filter { c -> c.isDigit() }.take(5)
        return digits.toIntOrNull()?.takeIf { it > 65535 }?.let { "65535" } ?: digits
    }
    fun onDomain(v: String) = _wizard.update { it.copy(domain = v, testSuccess = false, shares = emptyList(), sharesError = null) }
    fun onUser(v: String) = _wizard.update { it.copy(user = v, testSuccess = false, shares = emptyList(), sharesError = null) }
    fun onPass(v: String) = _wizard.update { it.copy(pass = v, testSuccess = false, shares = emptyList(), sharesError = null) }
    fun onAnonymous(v: Boolean) = _wizard.update { it.copy(anonymous = v, testSuccess = false, shares = emptyList(), sharesError = null) }
    fun onShareOrPath(v: String) = _wizard.update { it.copy(shareOrPath = v, testSuccess = false) }
    fun onNickname(v: String) = _wizard.update { it.copy(nickname = v) }

    fun onWizardBack() {
        val w = _wizard.value
        if (w.saving) return
        if (w.original != null && w.step == 1) { closeWizard(); return }
        if (w.step > 0) _wizard.update { it.copy(step = it.step - 1, error = null) }
        else closeWizard()
    }

    /** 默认端口（UI 占位提示用）：SMB 445；WebDAV 80/443 与 Jellyfin 8096/443 随 HTTPS 开关；SFTP 22。
     *  N3（2026-10-09）：host 已统一裸存，占位改按开关取值（原按 host 前缀判定失效） */
    fun defaultPortLabel(protocol: NvProtocol, https: Boolean): String = when (protocol) {
        NvProtocol.SMB -> "445"
        NvProtocol.WebDav -> if (https) "443" else "80"
        NvProtocol.SFTP -> "22"
        NvProtocol.Local -> ""
        NvProtocol.Jellyfin -> if (https) "443" else "8096"
    }

    /** 表单校验：缺项直接给错误（UiMessage 载体），不打网络 */
    private fun validateWizard(w: NvWizardUiState): NvWizardError? = when (w.protocol) {
        NvProtocol.SMB -> when {
            w.host.isBlank() -> NvWizardError(UiMessage(R.string.files_form_host_req_title), UiMessage(R.string.files_form_host_req_msg))
            // 共享名可留空：测试连接只验证登录，浏览时自动列出共享（虚拟根）再选择
            else -> null
        }
        NvProtocol.WebDav -> when {
            w.host.isBlank() -> NvWizardError(UiMessage(R.string.files_form_host_req_title), UiMessage(R.string.files_form_host_req_msg))
            else -> null
        }
        NvProtocol.SFTP -> when {
            w.host.isBlank() -> NvWizardError(UiMessage(R.string.files_form_host_req_title), UiMessage(R.string.files_form_host_req_msg))
            // 根路径可留空 = 登录家目录；端口可留空 = 22（客户端按协议默认端口）
            else -> null
        }
        NvProtocol.Local -> when {
            w.shareOrPath.isBlank() -> NvWizardError(UiMessage(R.string.files_form_dir_req_title), UiMessage(R.string.files_form_dir_req_msg))
            !w.shareOrPath.trim().startsWith("/") -> NvWizardError(UiMessage(R.string.files_form_path_invalid_title), UiMessage(R.string.files_form_path_invalid_msg))
            else -> null
        }
        // 2026-10-07 Jellyfin：host/账号/密码均必填（无匿名开关——Media Server 必须真认证；
        // 端口可留空 = 8096，无共享名/根路径概念，错误样式同 SMB/WEBDAV 分支）
        NvProtocol.Jellyfin -> when {
            w.host.isBlank() -> NvWizardError(UiMessage(R.string.files_form_host_req_title), UiMessage(R.string.files_form_host_req_msg))
            w.user.isBlank() -> NvWizardError(UiMessage(R.string.files_form_user_req_title), UiMessage(R.string.files_form_user_req_msg))
            w.pass.isBlank() -> NvWizardError(UiMessage(R.string.files_form_pass_req_title), UiMessage(R.string.files_form_pass_req_msg))
            else -> null
        }
    }

    /** 表单 → SourceEntity（未入库；id=0 供测试连接 / 向导目录浏览临时使用） */
    private fun wizardEntity(): SourceEntity {
        val w = _wizard.value
        val nickname = w.nickname.trim().ifBlank {
            when (w.protocol) {
                NvProtocol.SMB -> w.shareOrPath.trim().ifBlank { w.host.trim() }
                    .ifBlank { appContext.getString(R.string.files_default_nick_smb) }
                NvProtocol.WebDav -> w.host.trim()
                    .ifBlank { appContext.getString(R.string.files_default_nick_webdav) }
                NvProtocol.SFTP -> w.host.trim()
                    .ifBlank { appContext.getString(R.string.files_default_nick_sftp) }
                NvProtocol.Jellyfin -> w.host.trim().ifBlank { "Jellyfin" }
                NvProtocol.Local -> w.shareOrPath.trim().trimEnd('/').substringAfterLast('/')
                    .ifBlank { appContext.getString(R.string.files_default_nick_local) }
            }
        }
        val entity = SourceEntity(
            type = w.protocol.toSourceType() ?: SourceType.SMB,
            nickname = nickname,
            // N3 存储规则（2026-10-09）：WebDAV/Jellyfin https 开→存 "https://host" 前缀，关→存裸 host
            //（WebDavPaths.schemeOf 裸值默认 http / JellyfinApi.normalizeBaseUrl 裸值补 http，
            // 均既有逻辑）——client 层与存储格式零改动；SMB/SFTP 无 scheme 概念，原样入库
            host = when (w.protocol) {
                NvProtocol.WebDav, NvProtocol.Jellyfin ->
                    (if (w.https) "https://" else "") + parseHostField(w.host).host
                else -> w.host.trim()
            },
            port = w.port.trim().toIntOrNull() ?: 0,   // 0 = 客户端按协议默认端口
            domain = w.domain.trim(),
            username = if (w.anonymous) "" else w.user.trim(),
            // 2026-09-16 审计 P2-13：口令 Keystore AES-GCM 加密入库（旧明文行读取时兼容透传）
            password = if (w.anonymous) "" else NvSecrets.encrypt(w.pass),
            shareOrPath = when (w.protocol) {
                NvProtocol.SMB -> w.shareOrPath.trim().trim('/').replace('\\', '/')
                // Jellyfin（spec §5）：正式值在下方用登录态 userId 回写，表单 share 输入不适用
                NvProtocol.Jellyfin -> ""
                else -> w.shareOrPath.trim()
            },
        )
        // Jellyfin（spec §5 userId 回写）：shareOrPath = 向导「测试连接」成功后登录态缓存的 userId
        //（可空：未测连/进程重启后 sessions 空 → 空串；浏览/播放不依赖该字段，客户端按需重登）。
        // sessions 按 source.id 键控：goNext 测连用的也是 id=0 的同口径实体，这里传构建中的临时
        // entity（含全部表单字段，id 同为 0）恰好命中同一缓存；入库时 copy(original.id) 在其后发生
        return if (w.protocol == NvProtocol.Jellyfin) {
            entity.copy(shareOrPath = repo.jellyfin.cachedUserId(entity).orEmpty())
        } else {
            entity
        }
    }

    /** 「测试连接」：调 NvSourceRepository.connect（真实网络），成功绿色提示允许下一步 */
    /**
     * 2026-09-18 用户反馈：取消独立「测试连接」按钮——下一步即测试：
     * 校验表单 → 真实连接 → 成功进目录步（附带 SMB 拉共享/电池豁免询问），失败在本步错误横幅给原因。
     */
    fun goNext() {
        val w = _wizard.value
        if (w.step != 1 || w.connecting) return
        validateWizard(w)?.let { err ->
            _wizard.update { it.copy(error = err, testSuccess = false) }
            return
        }
        _wizard.update { it.copy(connecting = true, error = null, testSuccess = false) }
        val entity = wizardEntity()
        viewModelScope.launch {
            val result = repo.connect(entity)
            if (!sameWizardConnection(w, _wizard.value)) {
                if (_wizard.value.sessionId == w.sessionId) _wizard.update { it.copy(connecting = false) }
                return@launch
            }
            result.fold(
                onSuccess = {
                    _wizard.update {
                        it.copy(connecting = false, testSuccess = true, error = null, step = 2)
                    }
                    if (entity.type == SourceType.SMB) {
                        fetchShares()                 // 测试成功自动拉一次共享列表
                        maybePromptBatteryOptimization() // 三星 MARs 掐 SMB：问一次电池豁免
                    }
                    loadWizardDir(emptyList())
                },
                onFailure = { e ->
                    _wizard.update {
                        it.copy(
                            connecting = false,
                            testSuccess = false,
                            failureCount = it.failureCount + 1,
                            error = mapConnectError(e, it.protocol),
                        )
                    }
                },
            )
        }
    }

    /**
     * 「获取共享列表」：jcifs-ng 枚举 SMB 共享（独立短连接），成功列出共享名
     * （点击回填共享名输入框）；失败显示可重试错误行，不打断手动输入。
     */
    fun fetchShares() {
        val w = _wizard.value
        if (w.sharesLoading || !w.canFetchShares) return
        _wizard.update { it.copy(sharesLoading = true, sharesError = null) }
        viewModelScope.launch {
            val result = try {
                repo.listSmbShares(
                    host = w.host.trim(),
                    port = w.port.trim().toIntOrNull() ?: 0,
                    username = if (w.anonymous) "" else w.user.trim(),
                    password = if (w.anonymous) "" else w.pass,
                    domain = w.domain.trim().takeIf { it.isNotBlank() },
                )
            } catch (e: Throwable) {
                Result.failure(e)
            }
            // 过期响应丢弃（向导已关闭或已切走协议）
            if (!sameWizardConnection(w, _wizard.value)) {
                if (_wizard.value.sessionId == w.sessionId) _wizard.update { it.copy(sharesLoading = false) }
                return@launch
            }
            result.fold(
                onSuccess = { names ->
                    _wizard.update { it.copy(sharesLoading = false, shares = names, sharesError = null) }
                },
                onFailure = { e ->
                    val err = mapConnectError(e, NvProtocol.SMB)
                    _wizard.update {
                        it.copy(sharesLoading = false, sharesError = err)
                    }
                },
            )
        }
    }

    /** 点击共享名 → 回填共享名输入框 */
    fun onShareSelect(name: String) {
        _wizard.update { it.copy(shareOrPath = name, testSuccess = false, sharesError = null) }
    }

    /** 测试通过后进入 Step 2（目录确认/浏览） */
    // ---------- Step 2 目录确认（真实 list，根为源根） ----------
    data class WizardBrowse(
        val breadcrumb: List<String>,
        val entries: List<NvFileEntry>,
        val loading: Boolean,
        val error: NvWizardError?,
    )

    fun openWizardDir(name: String) {
        val b = _wizardBrowser.value ?: return
        loadWizardDir(b.breadcrumb + name)
    }

    /** Step2 点击虚拟共享条目（共享名留空源）：回填共享名并列该共享根（内容即验证，保持 testSuccess） */
    fun onWizardShareEntry(entry: NvFileEntry) {
        val shareName = SmbShareRoot.shareNameOf(entry.path) ?: return
        _wizard.update { it.copy(shareOrPath = shareName, error = null, testSuccess = false) }
        loadWizardDir(emptyList())
    }

    fun wizardBreadcrumbTo(depth: Int) {
        val b = _wizardBrowser.value ?: return
        if (depth < 0) {
            // 面包屑「根」段：回源根目录（不关闭向导）
            loadWizardDir(emptyList())
            return
        }
        loadWizardDir(b.breadcrumb.take(depth + 1))
    }

    // ---------- Step 2 目录多选（扫描根） ----------

    /** 勾选/取消一个目录（含虚拟共享条目）；存 NvPaths 规范化路径 */
    fun toggleWizardDir(entry: NvFileEntry) {
        val key = NvPaths.normalize(entry.path)
        _wizard.update {
            it.copy(
                selectedScanDirs =
                if (key in it.selectedScanDirs) it.selectedScanDirs - key
                else it.selectedScanDirs + key,
            )
        }
    }

    /** 「全选」：当前列表目录全部已选 → 清空本层勾选；否则全部加入 */
    fun selectAllWizardDirs() {
        val dirs = _wizardBrowser.value?.entries
            ?.filter { it.isDir }
            ?.map { NvPaths.normalize(it.path) }
            ?.toSet()
            ?: emptySet()
        _wizard.update {
            if (dirs.isNotEmpty() && it.selectedScanDirs.containsAll(dirs)) {
                it.copy(selectedScanDirs = it.selectedScanDirs - dirs)
            } else {
                it.copy(selectedScanDirs = it.selectedScanDirs + dirs)
            }
        }
    }

    private fun loadWizardDir(breadcrumb: List<String>) {
        wizardBrowseJob?.cancel()
        val requestedWizard = _wizard.value
        val entity = wizardEntity()
        _wizardBrowser.value = WizardBrowse(breadcrumb, emptyList(), loading = true, error = null)
        wizardBrowseJob = viewModelScope.launch {
            val result = try {
                repo.list(entity, joinToPath(breadcrumb))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (_wizardBrowser.value?.breadcrumb != breadcrumb) return@launch   // 过期响应丢弃
            if (!sameWizardConnection(requestedWizard, _wizard.value)) return@launch
            result.fold(
                onSuccess = { entries ->
                    _wizard.update { it.copy(testSuccess = true) }
                    _wizardBrowser.value = WizardBrowse(breadcrumb, toUiEntries(entries), false, null)
                },
                onFailure = { e ->
                    val err = mapConnectError(e, _wizard.value.protocol)
                    _wizardBrowser.value = WizardBrowse(breadcrumb, emptyList(), false, err)
                },
            )
        }
    }

    /**
     * 保存源：写 Room（已测试通过 → 标记 OK），列表经 Flow 自动刷新。
     * C12 §二.1 T1：保存成功后若「自动扫描新源」开（novax_scan_autoscan，默认开），
     * fire-and-forget 触发 MediaScanner.scanSource 建立媒体索引。
     */
    fun confirmAddSource() {
        confirmAddSourceInternal(filesOnly = false)
    }

    /**
     * 2026-10-26「仅作文件源」：保存源但永不进媒体库（scanRoots=DISABLED，扫描器全路径跳过）。
     * 编辑既有源转禁用时：停扫 + 清该源已入库条目与失引用缩略图 + 清 scan_state（不留僵尸
     * 条目/旧横幅；与删源清理 ThumbCache.purgeSource 同款）。恢复扫描=正常选目录保存即可。
     */
    fun confirmAddSourceFilesOnly() {
        confirmAddSourceInternal(filesOnly = true)
    }

    private fun confirmAddSourceInternal(filesOnly: Boolean) {
        val w = _wizard.value
        if (!w.testSuccess || w.connecting || w.saving) return
        val entity = wizardEntity().copy(
            id = w.original?.id ?: 0L,
            // BUG-C-R6-12：复制源（original=null）时用 copyScrapeEnabled 带入原源开关
            scrapeEnabled = w.original?.scrapeEnabled ?: w.copyScrapeEnabled ?: false,
            // 目录多选结果 → 扫描根（JSON 数组 TEXT）；未选目录存 null = 扫根目录（回退现状）；
            // filesOnly → DISABLED（"[]"）= 仅文件源永不扫描
            scanRoots = if (filesOnly) ScanRoots.DISABLED else ScanRoots.encode(w.selectedScanDirs),
            status = SourceStatus.OK, lastCheckedAt = System.currentTimeMillis(),
        )
        _wizard.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                val scanner = NvScannerHolder.get(appContext)
                val id = if (w.original != null) {
                    scanner.cancelAndJoin(entity.id)
                    // 编辑保存驱逐：口令/host/根路径变更后，旧池键连接与旧 host 的 known-hosts 指纹行一并重置
                    repo.evictSftp(w.original)
                    dao.update(entity)
                    entity.id
                } else dao.insert(entity)
                if (filesOnly) {
                    // 编辑转禁用：清存量索引（内含 mediaItemDao.deleteBySource + 失引用缩略图 jpg）
                    // + 清扫描态（旧 DONE/FAILED 行不得继续喂横幅）。新加源无存量，同样调用零副作用。
                    // 失败必须留痕（W 级惯例）：失败=禁用源存量条目残留库中，排障需要线索
                    runCatching { ThumbCache.purgeSource(appContext, db.mediaItemDao(), id) }
                        .onFailure { android.util.Log.w("NvFiles", "files-only purgeSource failed src=$id", it) }
                    runCatching { db.scanStateDao().delete(id) }
                        .onFailure { android.util.Log.w("NvFiles", "files-only scan_state delete failed src=$id", it) }
                } else {
                    val autoScan = runCatching {
                        appContext.playerPrefsFlow().first()[KEY_LIB_AUTO_SCAN] ?: true
                    }.getOrDefault(true)
                    if (autoScan || w.original != null) {
                        dao.getById(id)?.let { saved ->
                            NvScannerHolder.get(appContext).startScan(saved)
                            Toast.makeText(
                                appContext,
                                appContext.getString(R.string.files_index_started_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }.onSuccess {
                if (filesOnly) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.files_saved_files_only_toast),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                closeWizard()
            }.onFailure {
                _wizard.update { state ->
                    state.copy(
                        saving = false,
                        error = NvWizardError(
                            UiMessage(R.string.files_save_failed_title),
                            UiMessage(R.string.files_save_failed_msg),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 2026-09-15 D3 文件行元数据徽标：命中 media_items 行（时长/分辨率已由抽帧回填）。
     * 仅读取不触发抽帧；未索引/未回填 → null。
     * 2026-09-16 审计 P2-4：文件行缩略图/徽标合并为这一次查询（原 thumbnailPathOf 已并入调用方）。
     */
    suspend fun mediaMetaOf(sourceId: Long, entryPath: String): com.novax.app.data.db.MediaItemEntity? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { db.mediaItemDao().findByKey(sourceId, NvPaths.normalize(entryPath)) }.getOrNull()
        }

    /** 行级 DB Flow（BUG-C-R6-13：抽帧完成入库后，文件页行内即时刷新缩略图/徽标，免退出重进） */
    fun observeMediaMeta(sourceId: Long, entryPath: String): kotlinx.coroutines.flow.Flow<com.novax.app.data.db.MediaItemEntity?> =
        db.mediaItemDao().observeByKey(sourceId, NvPaths.normalize(entryPath))

    // ---------- 电池优化豁免（三星 MARs 会掐 SMB 长连接，验证中已确认） ----------
    private val _batteryPrompt = MutableStateFlow(false)
    val batteryPrompt: StateFlow<Boolean> = _batteryPrompt.asStateFlow()

    /** SMB 测试成功 → 未被豁免且从未询问过时弹一次确认（DataStore 布尔位防重复骚扰） */
    private fun maybePromptBatteryOptimization() {
        viewModelScope.launch {
            val asked = runCatching {
                appContext.novaxSettings.data.first()[KEY_BATTERY_ASKED] ?: false
            }.getOrDefault(false)
            if (asked) return@launch
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true) return@launch
            _batteryPrompt.value = true
        }
    }

    /** 对话框结果：确认 → 跳系统「忽略电池优化」设置页；确认/取消都记「已询问过」 */
    fun onBatteryPromptResult(allow: Boolean) {
        _batteryPrompt.value = false
        viewModelScope.launch {
            runCatching { appContext.novaxSettings.edit { it[KEY_BATTERY_ASKED] = true } }
        }
        if (allow) {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${appContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "跳转电池优化设置失败", it) }
        }
    }

    // ---------- 工具 ----------

    /** 目录段列表 → 统一路径（"" 表示根） */
    private fun joinToPath(breadcrumb: List<String>): String =
        breadcrumb.fold("") { acc, seg -> NvPaths.join(acc, seg) }

    /** 仓库条目 → UI 条目：目录 + 全部文件（C9：任何文件点开必有去处——非视频文件经 NvFileRouter 分发到查看器/外发弹层） */
    private fun toUiEntries(list: List<com.novax.app.data.NvFileEntry>): List<NvFileEntry> =
        list.map {
            NvFileEntry(
                name = it.name,
                path = it.path,
                isDir = it.isDir,
                size = it.size,
                mtimeMs = it.lastModified,
            )
        }

    companion object {
        private const val TAG = "FilesViewModel"

        /** SavedStateHandle 键：上次浏览的源 ID 与面包屑（「/」join 的目录段） */
        private const val KEY_BROWSER_SOURCE_ID = "browser_source_id"
        private const val KEY_BROWSER_CRUMB = "browser_breadcrumb"

        /** viewModel(factory = ...) 入口：配置变更/Tab 切换复用同一实例（App 无 DI 框架）；
         *  2026-09-27 起注入 SavedStateHandle（进程死亡恢复浏览位置，见 restoreBrowserIfSaved） */
        fun factory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val handle = runCatching { extras.createSavedStateHandle() }
                        .getOrDefault(androidx.lifecycle.SavedStateHandle())
                    return FilesViewModel(appContext, handle) as T
                }
            }
    }
}

/**
 * 连接/列目录异常 → 结构化错误映射（向导与浏览器共用，UiMessage 四语载体）：
 * 认证失败（凭据）/ 连接拒绝 / 超时 / 主机不可达 / HTTP 状态码分别给出可操作文案。
 * i18n Task 8（评审移交①）：data 层四条链路改结构化判别（NvConnectException.kind 沿
 * cause 链查找），消除对异常中文原文的 message-contains 跨层匹配；英文状态码类匹配
 * （LOGON_FAILURE/access denied/HTTP 4xx 等）是底层库的稳定输出，维持原判。两侧一致性
 * 由 MapConnectErrorTest + 抛出侧单测锁定。
 */
fun mapConnectError(e: Throwable, protocol: NvProtocol): NvWizardError {
    val direct = e.message.orEmpty()
    val cause = e.cause?.message.orEmpty()
    val all = "$direct $cause ${e.cause?.cause?.message.orEmpty()}"
    val connectKind = e.findConnectKind()

    // ---- SMB/WebDAV 路径不存在（2026-10-19）：目录在服务器侧被移动/改名/暂时离线 ----
    // 先于凭据/网络分支命中（状态语义互不重叠）；UNC 路径从原始异常文本提取，指明去 NAS 找哪个目录
    if (SmbStatusText.missingPath(all)) {
        val path = SmbStatusText.uncPathOf(all)
        return NvWizardError(
            UiMessage(R.string.files_err_path_missing_title),
            if (path != null) {
                UiMessage(R.string.files_err_path_missing_with_msg, listOf(path))
            } else {
                UiMessage(R.string.files_err_path_missing_msg)
            },
        )
    }

    // ---- 认证/凭据 ----
    if (all.contains("LOGON_FAILURE", true) || all.contains("logon failure", true)) {
        return NvWizardError(UiMessage(R.string.files_err_credential_title), UiMessage(R.string.files_err_cred_smb_msg), isCredential = true)
    }
    if (all.contains("PASSWORD_EXPIRED", true)) {
        return NvWizardError(UiMessage(R.string.files_err_pwd_expired_title), UiMessage(R.string.files_err_pwd_expired_msg), isCredential = true)
    }
    if (all.contains("ACCOUNT_DISABLED", true) || all.contains("ACCOUNT_LOCKED_OUT", true)) {
        return NvWizardError(UiMessage(R.string.files_err_account_unavailable_title), UiMessage(R.string.files_err_account_unavailable_msg), isCredential = true)
    }
    if (all.contains("access denied", true) || all.contains("ACCESS_DENIED", true)) {
        return NvWizardError(UiMessage(R.string.files_err_no_access_title), UiMessage(R.string.files_err_no_access_msg), isCredential = true)
    }
    if (Regex("HTTP 401").containsMatchIn(all)) {
        return NvWizardError(UiMessage(R.string.files_err_credential_title), UiMessage(R.string.files_err_cred_webdav_msg), isCredential = true)
    }
    // Jellyfin 401：client 抛 httpError(401)（结构化 kind=JELLYFIN_CREDENTIAL），
    // 不含 "HTTP 401" 字样，上面的正则匹配不到；命中即凭据错误（凭据框高亮）
    if (protocol == NvProtocol.Jellyfin && connectKind == com.novax.app.data.NvConnectKind.JELLYFIN_CREDENTIAL) {
        return NvWizardError(UiMessage(R.string.files_err_credential_title), UiMessage(R.string.files_err_cred_jellyfin_msg), isCredential = true)
    }
    // SFTP 认证失败（sshj UserAuthException 典型文案 "Auth fail" / "Password auth failed"，2026-09-29）
    if (all.contains("auth fail", true) || all.contains("password auth failed", true) ||
        all.contains("userauth", true) && all.contains("exception", true)
    ) {
        return NvWizardError(UiMessage(R.string.files_err_credential_title), UiMessage(R.string.files_err_cred_sftp_msg), isCredential = true)
    }
    // SFTP TOFU 指纹不一致（校验器抛 NvConnectException，sshj 包成 TransportException 后
    // kind 落 cause 链——借 findConnectKind 沿链还原，同「目录不可读」分支做法）
    if (connectKind == com.novax.app.data.NvConnectKind.SFTP_HOST_KEY_CHANGED) {
        return NvWizardError(
            UiMessage(R.string.files_err_hostkey_title),
            UiMessage(R.string.files_err_hostkey_tofu_msg),
        )
    }
    if (all.contains("could not verify", true) || all.contains("host key", true)) {
        return NvWizardError(UiMessage(R.string.files_err_hostkey_title), UiMessage(R.string.files_err_hostkey_ssh_msg))
    }
    if (Regex("HTTP 403").containsMatchIn(all)) {
        return NvWizardError(UiMessage(R.string.files_err_no_access_title), UiMessage(R.string.files_err_no_access_403_msg), isCredential = true)
    }

    // ---- 网络层 ----
    if (e is java.net.ConnectException || all.contains("connection refused", true) || all.contains("Connection refused", true)) {
        return NvWizardError(
            UiMessage(R.string.files_err_refused_title),
            when (protocol) {
                NvProtocol.SMB -> UiMessage(R.string.files_err_refused_smb_msg)
                NvProtocol.SFTP -> UiMessage(R.string.files_err_refused_sftp_msg)
                else -> UiMessage(R.string.files_err_refused_msg)
            },
        )
    }
    if (e is java.net.UnknownHostException || all.contains("unable to resolve", true) || all.contains("unknown host", true) || all.contains("Unable to resolve", true)) {
        return NvWizardError(UiMessage(R.string.files_err_dns_title), UiMessage(R.string.files_err_dns_msg))
    }
    if (e is java.net.SocketTimeoutException || e is java.util.concurrent.TimeoutException ||
        all.contains("timed out", true) || all.contains("timeout", true)
    ) {
        return NvWizardError(UiMessage(R.string.files_err_timeout_title), UiMessage(R.string.files_err_timeout_msg))
    }
    if (all.contains("unreachable", true)) {
        return NvWizardError(UiMessage(R.string.files_err_unreachable_title), UiMessage(R.string.files_err_unreachable_msg))
    }
    // HTTP 404 分支已并入顶部「路径不存在」（SmbStatusText.missingPath 含 http 404，2026-10-19）

    // ---- 本地目录 ----
    if (connectKind == com.novax.app.data.NvConnectKind.DIR_UNREADABLE || all.contains("Permission denied", true)) {
        return NvWizardError(
            UiMessage(R.string.files_err_dir_unreadable_title),
            UiMessage(R.string.files_err_dir_unreadable_msg),
        )
    }
    if (connectKind == com.novax.app.data.NvConnectKind.SHARE_NOT_CONFIGURED) {
        return NvWizardError(UiMessage(R.string.files_err_share_missing_title), UiMessage(R.string.files_err_share_missing_msg))
    }
    // 「SFTP 源暂未支持」映射分支已随 SFTP 落地删除（2026-09-29），此消息不再产生

    // ---- 兜底：保留底层信息便于排查（detail 为底层原文，经占位资源透传展示） ----
    val detail = (direct.ifBlank { cause }).ifBlank { e.javaClass.simpleName }
    return NvWizardError(
        UiMessage(R.string.files_err_generic_title),
        UiMessage(R.string.files_err_generic_detail, listOf(detail.take(160))),
    )
}

/**
 * NSD 局域网发现器：_smb._tcp 与 _webdav._tcp 各 5 秒超时；
 * 发现的服务逐个 resolve（部分系统版本不允许多路并发 resolve）取 IP/端口。
 */
private class NsdScanner(
    private val nsd: NsdManager,
    private val mainScope: CoroutineScope,
    private val onDevice: (NvDiscoveredDevice) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val unfinished = AtomicInteger(2)
    private val finishedOnce = AtomicBoolean(false)
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, NvProtocol>>()
    private var resolving = false
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        discover("_smb._tcp.", NvProtocol.SMB)
        discover("_webdav._tcp.", NvProtocol.WebDav)
    }

    private fun discover(serviceType: String, protocol: NvProtocol) {
        val typeDone = AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = completeType(typeDone)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(resolveQueue) { resolveQueue.add(serviceInfo to protocol) }
                drainResolve()
            }
        }
        listeners += listener
        // 5 秒超时：停止该类型发现并计数完成。
        // 2026-09-15 泄漏修复：stop 必须 NonCancellable——mainScope=viewModelScope，离开文件页
        // 取消本任务曾导致 stopServiceDiscovery 永不执行，mDNS 发现一直跑到进程死（耗电）。
        mainScope.launch {
            delay(5_000)
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
            completeType(typeDone)
        }
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { completeType(typeDone) }
    }

    private fun completeType(typeDone: AtomicBoolean) {
        if (!typeDone.compareAndSet(false, true)) return
        if (unfinished.decrementAndGet() <= 0 && finishedOnce.compareAndSet(false, true)) {
            onFinished()
        }
    }

    private fun drainResolve() {
        if (resolving) return
        val next = synchronized(resolveQueue) { resolveQueue.removeFirstOrNull() } ?: return
        resolving = true
        val (info, protocol) = next
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveDone()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host
                    if (host != null) {
                        onDevice(
                            NvDiscoveredDevice(
                                name = info.serviceName,
                                ip = host.hostAddress.orEmpty(),
                                port = info.port,
                                protocol = protocol,
                            ),
                        )
                    }
                    resolveDone()
                }
                private fun resolveDone() {
                    resolving = false
                    drainResolve()
                }
            })
        }.onFailure { resolving = false }
    }

    fun stopAll() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        synchronized(resolveQueue) { resolveQueue.clear() }
    }
}
