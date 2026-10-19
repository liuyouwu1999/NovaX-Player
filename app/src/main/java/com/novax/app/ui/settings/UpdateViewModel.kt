package com.novax.app.ui.settings

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novax.app.KEY_PRIVACY_AGREED_VERSION
import com.novax.app.R
import com.novax.app.data.update.KEY_UPDATE_AUTO_CHECK
import com.novax.app.data.update.KEY_UPDATE_DOWNLOAD_ID
import com.novax.app.data.update.KEY_UPDATE_LAST_CHECK_MILLIS
import com.novax.app.data.update.KEY_UPDATE_PENDING_VERSION
import com.novax.app.data.update.NvSha256
import com.novax.app.data.update.OkHttpReleaseHttp
import com.novax.app.data.update.UpdateAutoCheckGate
import com.novax.app.data.update.UpdateCheckResult
import com.novax.app.data.update.UpdateDownloader
import com.novax.app.data.update.UpdateRepository
import com.novax.app.data.update.UpdateDmState
import com.novax.app.novaxUiPrefs
import com.novax.app.ui.policy.NvPolicyText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 更新检查 UI 状态机（spec §2.4/§2.5）。
 *  fromAuto 标记结果来源：自动检查产生的 UpToDate/CheckFailed 置 true——行副标题映射对其走
 *  默认分支（自动检查一律静默）；发现新版仍弹窗（AvailableShow 不带标记，DialogHost 照常渲染）。 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class AvailableShow(val check: UpdateCheckResult.Available) : UpdateUiState
    data class UpToDate(val fromAuto: Boolean = false) : UpdateUiState

    /** @param canOpenProjectPage 手动检查的网络类失败才给「打开项目主页」出口（spec §2.4 可达性落位） */
    data class CheckFailed(
        val reason: UpdateCheckResult.Failed.Reason,
        val canOpenProjectPage: Boolean,
        val fromAuto: Boolean = false,
    ) : UpdateUiState

    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val file: File) : UpdateUiState
    data object VerifyFailed : UpdateUiState
    data class NeedPermission(val file: File) : UpdateUiState
    data object DownloadFailed : UpdateUiState
}

/**
 * 检查更新 ViewModel（spec §2.6：独立轻量，关于组页面作用域——实际落位为 Activity 级单实例，
 * 见 AboutGroupPage / MainActivity 两处取同一 ViewModelStore 的单实例裁决注释）。
 *
 * 自身不写可测分支：版本比较/JSON/资产匹配/校验和/自动检查门/清理策略全部复用
 * data/update 包 Task 1-8 已测对象，这里只做编排（协程调度 + DataStore 读写 + 状态机流转）。
 *
 * 单实例裁决（blocker 防线）：AboutGroupPage 用
 * `viewModel(viewModelStoreOwner = LocalContext.current as ViewModelStoreOwner)`、
 * MainActivity else 分支用裸 `viewModel()`、onResume 用 `by viewModels()`——三者同属
 * Activity 的 ViewModelStore，天然同一实例；「设置行点击检查 → Activity 层弹窗」链路由此成立。
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app.applicationContext

    private val repo = UpdateRepository(OkHttpReleaseHttp())
    private val downloader = UpdateDownloader(appContext)

    /** 本地版本名（裸 versionName，供比较/清理；展示用 versionLabel() 带 (code) 后缀） */
    private val versionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private val deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 自动检查开关（novax_update_auto_check，默认开；Room 式回显：写 DataStore 后 Flow 自动刷新） */
    val autoCheckEnabled: StateFlow<Boolean> = appContext.novaxUiPrefs.data
        .map { it[KEY_UPDATE_AUTO_CHECK] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 当前轮询协程与下载 id（内存现场；持久化以 DataStore 四键为准） */
    private var pollJob: Job? = null
    private var currentId: Long? = null

    /** 本会话内下载时的检查结果（校验需要 SUMS 哈希；跨进程重启后为 null → 恢复路径现场补查） */
    private var activeCheck: UpdateCheckResult.Available? = null

    // ---------------- 检查 ----------------

    /** 手动检查（spec §2.4：不受/不产生 24h 节流；结果以行副标题或弹窗呈现） */
    fun manualCheck() {
        if (!canStartCheck()) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = checkNow()
            _state.value = when (result) {
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(fromAuto = false)
                is UpdateCheckResult.Available -> UpdateUiState.AvailableShow(result)
                is UpdateCheckResult.Failed -> UpdateUiState.CheckFailed(
                    reason = result.reason,
                    canOpenProjectPage = result.reason != UpdateCheckResult.Failed.Reason.BAD_RESPONSE,
                    fromAuto = false,
                )
                null -> UpdateUiState.CheckFailed(
                    UpdateCheckResult.Failed.Reason.NETWORK,
                    canOpenProjectPage = true,
                    fromAuto = false,
                )
            }
        }
    }

    /** 自动检查（spec §2.4）：gate 四条件（开关/隐私同意/在线/24h 节流）全过才发起；
     *  无更新与失败一律静默（fromAuto=true），发现新版仍弹窗（DialogHost 驻 MainActivity）。 */
    fun maybeAutoCheck(activity: Activity) {
        viewModelScope.launch {
            val prefs = runCatching { appContext.novaxUiPrefs.data.first() }.getOrNull() ?: return@launch
            val allowed = UpdateAutoCheckGate.shouldAutoCheck(
                now = System.currentTimeMillis(),
                lastCheckMillis = prefs[KEY_UPDATE_LAST_CHECK_MILLIS] ?: 0L,
                autoEnabled = prefs[KEY_UPDATE_AUTO_CHECK] ?: true,
                // else 分支（本调用点）已保证同意，但 gate 按四条件显式复核，防调用点漂移
                privacyAgreed = (prefs[KEY_PRIVACY_AGREED_VERSION] ?: 0) >= NvPolicyText.VERSION,
                online = isOnline(activity.applicationContext),
            )
            if (!allowed || !canStartCheck()) return@launch
            _state.value = UpdateUiState.Checking
            val result = checkNow()
            _state.value = when (result) {
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(fromAuto = true)
                is UpdateCheckResult.Available -> UpdateUiState.AvailableShow(result)
                is UpdateCheckResult.Failed -> UpdateUiState.CheckFailed(
                    result.reason,
                    canOpenProjectPage = false,
                    fromAuto = true,
                )
                null -> UpdateUiState.CheckFailed(
                    UpdateCheckResult.Failed.Reason.NETWORK,
                    canOpenProjectPage = false,
                    fromAuto = true,
                )
            }
            // 节流基准只由自动检查写（UpdateAutoCheckGate 注释约定）。失败也写：离线常态已被
            // gate 的在线条件挡住，走到这里的多为限流——记时间戳防每次启动都去撞 403
            runCatching {
                appContext.novaxUiPrefs.edit { it[KEY_UPDATE_LAST_CHECK_MILLIS] = System.currentTimeMillis() }
            }
        }
    }

    private suspend fun checkNow(): UpdateCheckResult? =
        withContext(Dispatchers.IO) { runCatching { repo.check(versionName, deviceAbis) }.getOrNull() }

    /** 仅瞬态结果态可发起新检查——Checking/Downloading/弹窗/待装态不被手动点击打断 */
    private fun canStartCheck(): Boolean = when (_state.value) {
        is UpdateUiState.Idle,
        is UpdateUiState.UpToDate,
        is UpdateUiState.CheckFailed,
        is UpdateUiState.DownloadFailed,
        is UpdateUiState.VerifyFailed,
        -> true
        else -> false
    }

    // ---------------- 下载（spec §2.5） ----------------

    /** 应用内下载：持久化 downloadId + pendingVersion 后进入 1s 轮询（进度驱动行副标题）。 */
    fun startDownload() {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        val check = s.check
        // 防线②（Review Focus 1）：canInstall=false 时主按钮已隐藏，这里再守一道——宁可不装
        val asset = check.asset?.takeIf { check.canInstall } ?: return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { downloader.enqueue(asset, check.release.tagName) }.getOrNull()
            }
            if (id == null) {
                _state.value = UpdateUiState.DownloadFailed
                return@launch
            }
            runCatching {
                appContext.novaxUiPrefs.edit {
                    it[KEY_UPDATE_DOWNLOAD_ID] = id
                    it[KEY_UPDATE_PENDING_VERSION] = check.release.tagName
                }
            }
            currentId = id
            activeCheck = check
            startPolling(id, check, check.release.tagName)
        }
    }

    /** 取消（spec §2.5「取消」= DownloadManager.remove）：停轮询、删记录、清持久化键。 */
    fun cancelDownload() {
        pollJob?.cancel()
        val id = currentId
        currentId = null
        activeCheck = null
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { downloader.cancel(id) } }
        }
        clearDownloadKeys()
        if (_state.value is UpdateUiState.Downloading) _state.value = UpdateUiState.Idle
    }

    /** 1s 轮询循环。[check] 为 null = 恢复模式（进程重启后无内存现场，SUCCESSFUL 时补查校验）。 */
    private fun startPolling(id: Long, check: UpdateCheckResult.Available?, pendingTag: String?) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var nullStreak = 0   // enqueue 刚落库 DM 可能查无记录：连续多次才算真失败
            while (isActive) {
                val st = withContext(Dispatchers.IO) { runCatching { downloader.query(id) }.getOrNull() }
                when {
                    st == null -> {
                        nullStreak++
                        if (nullStreak >= MAX_QUERY_NULL_STREAK) {
                            clearDownloadIdKey()
                            _state.value = UpdateUiState.DownloadFailed
                            return@launch
                        }
                    }
                    st.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        settleSuccessful(st, check, pendingTag)
                        return@launch
                    }
                    st.status == DownloadManager.STATUS_FAILED -> {
                        // brief 口径：清 downloadId 键 + DownloadFailed（残留文件与 pendingVersion
                        // 陈账交给下一次 cleanup 收口）
                        clearDownloadIdKey()
                        _state.value = UpdateUiState.DownloadFailed
                        return@launch
                    }
                    else -> {
                        nullStreak = 0
                        val pct = if (st.totalBytes > 0) {
                            ((st.bytesSoFar * 100) / st.totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        _state.value = UpdateUiState.Downloading(pct)
                    }
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** 下载完成收口：空安全取期望哈希 → SHA 校验 → ReadyToInstall / NeedPermission，否则删文件+清键+VerifyFailed。 */
    private suspend fun settleSuccessful(
        st: UpdateDmState,
        sessionCheck: UpdateCheckResult.Available?,
        pendingTag: String?,
    ) {
        val file = downloader.fileOf(st)
        // 恢复模式（进程重启后进关于页续查）：内存无 check，按 pendingTag 现场补一次检查取 SUMS
        val check = sessionCheck ?: fetchCheckFor(pendingTag)
        if (check == null) {
            // 补查失败（离线/限流/契约破坏）：无法校验 = 绝不安装（宁可不装，spec §2.5）。
            // 文件保留（清理策略口径② pendingVersion 保护），仅清 downloadId 终止恢复，静默回 Idle
            clearDownloadIdKey()
            _state.value = UpdateUiState.Idle
            return
        }
        // 空安全取期望哈希：SUMS 缺条目 → null 视同校验失败，绝不允许 NPE 或未校验安装
        val file0 = file
        val expected = check.asset?.let { check.sums?.get(it.name) }
        val verified = file0 != null && expected != null &&
            withContext(Dispatchers.IO) { runCatching { NvSha256.matches(file0, expected) }.getOrDefault(false) }
        if (verified) {
            // downloadId 使命完成即清：待装包改由 pendingVersion（口径②）保护；
            // 否则重进关于页会反复恢复轮询→反复拉起安装器
            clearDownloadIdKey()
            _state.value =
                if (grantedInstall()) UpdateUiState.ReadyToInstall(file0) else UpdateUiState.NeedPermission(file0)
        } else {
            // 校验失败：删文件 + 清持久化键 + VerifyFailed（spec §2.5 明文口径；
            // 恢复场景补查到同 tag 但哈希不匹配同口径——文件已损坏无保留价值）
            file?.delete()
            clearDownloadKeys()
            _state.value = UpdateUiState.VerifyFailed
        }
    }

    /** 恢复模式补查：tag 必须与待装包一致且 canInstall（资产/SUMS 齐备），否则视为无法校验 */
    private suspend fun fetchCheckFor(pendingTag: String?): UpdateCheckResult.Available? {
        if (pendingTag.isNullOrBlank()) return null
        val fresh = checkNow()
        val avail = fresh as? UpdateCheckResult.Available ?: return null
        return avail.takeIf { it.release.tagName == pendingTag && it.canInstall }
    }

    // ---------------- 生命周期钩子（spec §2.5 清理时机 / 续装） ----------------

    /** ① MainActivity 启动清理：update/ 只留两类；pendingVersion ≤ 当前版本时删文件并清键 */
    fun cleanupOnLaunch() {
        viewModelScope.launch { cleanupOnce() }
    }

    /** 进入关于页：清理（spec §2.5 清理时机 = 启动与进入关于页）+ 恢复轮询（设置页重进可恢复进度）。 */
    fun onAboutEnter() {
        viewModelScope.launch {
            cleanupOnce()
            if (_state.value !is UpdateUiState.Idle) return@launch
            // 清理后重读键（cleanup 的清键是 fire-and-forget，读到旧键时恢复路径自身安全侧兜底）
            val prefs = runCatching { appContext.novaxUiPrefs.data.first() }.getOrNull() ?: return@launch
            val id = prefs[KEY_UPDATE_DOWNLOAD_ID] ?: return@launch
            currentId = id
            startPolling(id, activeCheck, prefs[KEY_UPDATE_PENDING_VERSION])
        }
    }

    private suspend fun cleanupOnce() {
        val prefs = runCatching { appContext.novaxUiPrefs.data.first() }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                downloader.cleanup(
                    pendingVersion = prefs[KEY_UPDATE_PENDING_VERSION],
                    currentVersion = versionName,
                    activeId = prefs[KEY_UPDATE_DOWNLOAD_ID],
                )
            }
        }
    }

    /** ④ 宿主 onResume：NeedPermission 态复查授权——已授 → ReadyToInstall 续装（DialogHost 拉起安装器） */
    fun onHostResumed() {
        val s = _state.value
        if (s is UpdateUiState.NeedPermission && grantedInstall()) {
            _state.value = UpdateUiState.ReadyToInstall(s.file)
        }
    }

    // ---------------- 弹窗动作与出口 ----------------

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { appContext.novaxUiPrefs.edit { it[KEY_UPDATE_AUTO_CHECK] = enabled } }
        }
    }

    /** 「浏览器下载」：资产直链优先（国内可交下载工具，spec §2.3），无资产回落 releases 页 */
    fun openBrowser(context: Context) {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        openUrl(context, s.check.asset?.url ?: s.check.release.htmlUrl)
    }

    /** spec §2.4「打开项目主页」出口：检查失败时的可达性兜底 */
    fun openProjectPage(context: Context) {
        openUrl(context, PROJECT_PAGE_URL)
    }

    /** 「复制下载链接」：写剪贴板 + Toast（spec §2.4） */
    fun copyLink(context: Context) {
        val s = _state.value as? UpdateUiState.AvailableShow ?: return
        val url = s.check.asset?.url ?: s.check.release.htmlUrl
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("url", url)) }
        Toast.makeText(context, context.getString(R.string.update_link_copied), Toast.LENGTH_SHORT).show()
    }

    /** 弹窗「以后再说」/授权说明弹窗关闭：回 Idle。NeedPermission 场景不动已下载文件
     *  （清理策略口径②保护，spec §2.5 授权页往返存活）。 */
    fun dismissDialog() {
        when (_state.value) {
            is UpdateUiState.AvailableShow, is UpdateUiState.NeedPermission -> _state.value = UpdateUiState.Idle
            else -> Unit
        }
    }

    /** 行副标题 3s 复位计时到期：仅瞬态结果（已是最新/检查失败）回 Idle，下载/待装/弹窗态不受影响 */
    fun dismissTransient() {
        when (_state.value) {
            is UpdateUiState.UpToDate, is UpdateUiState.CheckFailed -> _state.value = UpdateUiState.Idle
            else -> Unit
        }
    }

    /** 应用内安装意图（薄壳产装：FileProvider content URI + 授权读 + 新任务栈） */
    fun installerIntent(file: File): Intent = downloader.installIntent(file)

    private fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun needPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${appContext.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** minSdk 26：直接查「安装未知应用」授权（spec §2.5） */
    private fun grantedInstall(): Boolean =
        runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private fun clearDownloadKeys() {
        viewModelScope.launch {
            runCatching {
                appContext.novaxUiPrefs.edit {
                    it.remove(KEY_UPDATE_DOWNLOAD_ID)
                    it.remove(KEY_UPDATE_PENDING_VERSION)
                }
            }
        }
    }

    private fun clearDownloadIdKey() {
        viewModelScope.launch {
            runCatching { appContext.novaxUiPrefs.edit { it.remove(KEY_UPDATE_DOWNLOAD_ID) } }
        }
    }

    // ---------------- 弹窗宿主（spec §2.4：弹窗只在主界面出现，宿主驻 MainActivity） ----------------

    /** 收集 state 渲染弹窗；ReadyToInstall 拉起系统安装器后回 Idle。
     *  DownloadFailed/VerifyFailed 不弹窗（行副标题驻留承载，Step 1）。 */
    @Composable
    fun DialogHost() {
        val s by _state.collectAsState()
        val context = LocalContext.current
        when (val st = s) {
            is UpdateUiState.AvailableShow -> NvUpdateAvailableDialog(
                check = st.check,
                currentVersion = versionLabel(context),
                onInstall = { startDownload() },
                onBrowser = { openBrowser(context) },
                onCopyLink = { copyLink(context) },
                onViewNotes = { openUrl(context, st.check.release.htmlUrl) },
                onLater = { dismissDialog() },
            )
            is UpdateUiState.ReadyToInstall -> LaunchedEffect(st.file) {
                // 拉起系统安装器后即回 Idle（downloadId 已在校验通过时清掉，重进关于页不会重复拉起）
                runCatching { context.startActivity(installerIntent(st.file)) }
                _state.value = UpdateUiState.Idle
            }
            is UpdateUiState.NeedPermission -> NvUpdatePermissionDialog(
                onGrant = { runCatching { context.startActivity(needPermissionIntent()) } },
                onLater = { dismissDialog() },
            )
            else -> Unit
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L

        /** enqueue 落库窗口内 query 可能查无记录：连续 N 次才判 DownloadFailed */
        const val MAX_QUERY_NULL_STREAK = 3

        /** spec §2.4「打开项目主页」出口指向项目 releases 页（与 UpdateRepository 同仓库） */
        const val PROJECT_PAGE_URL = "https://github.com/liuyouwu1999/NovaX-Player"
    }
}

