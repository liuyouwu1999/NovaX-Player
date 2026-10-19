package com.novax.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.SourceType
import com.novax.app.data.scrape.NvScraper
import com.novax.app.player.KEY_SMB_BLOCK
import com.novax.app.player.KEY_SMB_PREFETCH
import com.novax.app.player.KEY_UI_START_TAB
import com.novax.app.player.KEY_UI_THEME
import com.novax.app.ui.settings.KEY_LIB_SCRAPE_GLOBAL
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvBottomBar
import com.novax.app.player.KEY_TMDB_API_KEY
import com.novax.app.player.playerPrefsFlow
import com.novax.app.ui.home.NvPlaybackLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.novax.app.player.PlayerActivity
import com.novax.app.player.PlayerPlaylistHolder
import com.novax.app.ui.detail.DetailScreen
import com.novax.app.ui.files.FilesScreen
import com.novax.app.ui.home.HomeScreen
import com.novax.app.ui.library.LibraryScreen
import com.novax.app.ui.search.SearchScreen
import com.novax.app.ui.settings.SettingsScreen
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.cast.CastOverlay
import com.novax.app.ui.theme.NovaXTheme
import kotlinx.coroutines.flow.map

// C11 §5.3：「返回键直接退出」开关（novax_ui_back_exit）。
// 独立 DataStore 文件（novax_ui_prefs，C10 修正节：与 novax_settings / novax_player_prefs 分离，
// 避免同名文件多 DataStore 实例冲突）；设置页（WS4/C10）经本委托读写同一文件同一键
// （internal：仅本模块可见，ui/settings/SettingsViewModel 为写入端）。
internal val Context.novaxUiPrefs by preferencesDataStore(name = "novax_ui_prefs")
private val KEY_BACK_EXIT = booleanPreferencesKey("novax_ui_back_exit")

/** 库页排序持久化（BUG-C-R6-16：排序选择重启恢复；2026-10-16 默认基准 ADDED→TITLE，机制不变） */
internal val KEY_LIB_SORT = stringPreferencesKey("novax_ui_lib_sort")

/** 通知权限「已问过」标记（首启一次性请求，拒绝也算已问；请求时机在隐私同意之后，spec §1.2） */
private val KEY_NOTIF_PERM_ASKED = booleanPreferencesKey("novax_notif_perm_asked")

/** 隐私政策同意版本与时间（首启弹窗门控；缺省 0=未同意。政策重大变更时 VERSION+1 重新弹出，spec §1.2） */
internal val KEY_PRIVACY_AGREED_VERSION =
    androidx.datastore.preferences.core.intPreferencesKey("novax_privacy_agreed_version")
internal val KEY_PRIVACY_AGREED_AT =
    androidx.datastore.preferences.core.longPreferencesKey("novax_privacy_agreed_at")

/** 「曾有过真实历史」持久标记（P2 修复：清除历史后不再回退 mock 占位；HomeViewModel 写入/消费） */
internal val KEY_HAS_REAL_HISTORY = booleanPreferencesKey("novax_has_real_history")

class MainActivity : ComponentActivity() {
    // 2026-09-20 用户需求：后台播放依赖通知栏控制（Android 13+ 需要 POST_NOTIFICATIONS），
    // 授权请求从「首次播放时」提前到「首次启动时」，一次性（问过就不再问，拒绝也算已问——
    // 系统在两次拒绝后会静默拒绝，重复调用只会产出死代码）。设置-隐私与数据里可再补授权路径。
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不阻塞流程 */ }
    /** 2026-09-20：首启一次性请求通知权限（Android 13+）；拒绝也算已问 */
    private fun maybeAskNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        lifecycleScope.launch {
            val asked = novaxUiPrefs.data.map { it[KEY_NOTIF_PERM_ASKED] ?: false }.first()
            if (asked) return@launch
            novaxUiPrefs.edit { it[KEY_NOTIF_PERM_ASKED] = true }
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 2026-09-15 D2：本地崩溃日志（纯本地落盘，设置-关于可查看/分享）
        com.novax.app.data.NvCrashLog.install(this)
        com.novax.app.data.NvCrashLog.recordLastExit(this)
        setContent {
            // 2026-09-20 浅色模式激活（设置-界面「主题」chips 原为禁用占位）：外观偏好存
            // novax_player_prefs KEY_UI_THEME（设置页 vm.set 写入），与系统深浅合成最终主题；
            // 状态栏/导航栏图标深浅随最终主题（enableEdgeToEdge 可重复调用更新样式）
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val playerPrefs by ctx.playerPrefsFlow().collectAsState(initial = null)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val darkTheme = when (playerPrefs?.get(KEY_UI_THEME) ?: "dark") {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                )
            }
            NovaXTheme(darkTheme = darkTheme) {
                // 首启隐私门控（spec §1.2）：未同意（或政策版本升级）→ 弹窗/全文屏；
                // 同意 → 写版本+时间戳，进入主界面，通知权限一次性请求后移至此
                val uiPrefs by ctx.novaxUiPrefs.data.collectAsState(initial = null)
                val agreedVersion = uiPrefs?.get(KEY_PRIVACY_AGREED_VERSION) ?: 0
                var showPolicyDoc by remember { mutableStateOf(false) }
                when {
                    // DataStore 首读窗口期：只渲染背景空屏，绝不提前放行主界面
                    uiPrefs == null -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(NvColors.Background),
                    )
                    agreedVersion < com.novax.app.ui.policy.NvPolicyText.VERSION -> {
                    if (showPolicyDoc) {
                        // T15 评审移交：全文屏只拦了屏内返回按钮，系统返回键/手势没拦——
                        // 会走 Activity 默认 finish 直接退出应用。补 BackHandler 折回同意弹窗。
                        BackHandler { showPolicyDoc = false }
                        com.novax.app.ui.policy.NvPolicyScreen(
                                com.novax.app.ui.policy.PolicyDoc.PRIVACY,
                            ) { showPolicyDoc = false }
                        } else {
                            com.novax.app.ui.policy.NvPolicyConsentDialog(
                                onAgree = {
                                    lifecycleScope.launch {
                                        ctx.novaxUiPrefs.edit {
                                            it[KEY_PRIVACY_AGREED_VERSION] = com.novax.app.ui.policy.NvPolicyText.VERSION
                                            it[KEY_PRIVACY_AGREED_AT] = System.currentTimeMillis()
                                        }
                                    }
                                },
                                onDecline = {
                                    // T15 评审移交：拒绝后 finishAffinity 立即销毁 activity，
                                    // 其 context 的 Toast 在 API 26-29 会被 pin 满 3.5s——
                                    // applicationContext 挂应用级 token，不随 activity 消亡。
                                    Toast.makeText(
                                        applicationContext,
                                        "未同意隐私政策与用户协议，应用已退出",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    finishAffinity()
                                },
                                onViewPolicy = { showPolicyDoc = true },
                            )
                        }
                    }
                    else -> {
                        LaunchedEffect(Unit) { maybeAskNotificationPermission() }
                        NovaXApp()
                    }
                }
            }
        }
        // 2026-09-15 假开关真化：SMB 块大小/并发预读设置 → NvNetTuning 运行时槽。
        // 键未写入（用户没动过设置）时为默认值，行为与旧版完全一致（contains() 门控语义）。
        lifecycleScope.launch {
            playerPrefsFlow().collect { prefs ->
                com.novax.app.data.smb.NvNetTuning.blockBytesOverride =
                    prefs[KEY_SMB_BLOCK]?.let { com.novax.app.data.smb.NvNetTuning.parseBlockBytes(it) }
                com.novax.app.data.smb.NvNetTuning.prefetchEnabled = prefs[KEY_SMB_PREFETCH] ?: true
            }
        }
        // 2026-09-15 TMDB 刮削自动触发：某源扫描 RUNNING→DONE 且三重闸
        // （总开关 KEY_LIB_SCRAPE_GLOBAL + Key 已配 + source.scrapeEnabled）满足时刮该源；
        // 源重新开始扫描则取消在途刮削（文件可能已变动）。手动入口在设置-媒体库。
        lifecycleScope.launch {
            val scanner = com.novax.app.data.scanner.NvScannerHolder.get(this@MainActivity)
            val scraper = NvScraper.get(this@MainActivity)
            val sourceDao = MediaDb.get(this@MainActivity).sourceDao()
            var seenDone = emptyList<Long>()
            scanner.progress.collect { live ->
                val runningNow = live.values.filter { it.status == ScanStatus.RUNNING }.map { it.sourceId }
                runningNow.forEach { scraper.cancelSource(it) }
                val doneNow = live.values.filter { it.status == ScanStatus.DONE }.map { it.sourceId }
                val newlyDone = doneNow.filterNot { it in seenDone }
                seenDone = doneNow
                if (newlyDone.isEmpty()) return@collect
                val prefs = playerPrefsFlow().first()
                if (prefs[KEY_LIB_SCRAPE_GLOBAL] != true) return@collect
                if (prefs[KEY_TMDB_API_KEY].isNullOrBlank()) return@collect
                newlyDone.forEach { sid ->
                    val src = runCatching { sourceDao.getById(sid) }.getOrNull()
                    if (src?.scrapeEnabled == true) scraper.scrapeSource(sid)
                }
            }
        }
    }
}

data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

val bottomTabs = listOf(
    TabItem("home", "首页", Icons.Outlined.Home, Icons.Filled.Home),
    TabItem("library", "媒体库", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    TabItem("files", "文件", Icons.Outlined.Folder, Icons.Filled.Folder),
    TabItem("search", "搜索", Icons.Outlined.Search, Icons.Filled.Search),
    TabItem("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

fun playVideo(
    context: android.content.Context,
    videoUrl: String,
    title: String,
    positionMs: Long = 0L,
    historyPath: String? = null,
    queueId: String? = null,
) {
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra("videoUrl", videoUrl)
            .putExtra("title", title)
            .putExtra("positionMs", positionMs)
            .putExtra(PlayerActivity.EXTRA_HISTORY_PATH, historyPath)
            .apply { queueId?.let { putExtra(PlayerActivity.EXTRA_QUEUE_ID, it) } }
    )
}

/**
 * 2026-09-15 用户需求：文件夹内播放支持上/下切换。
 * 队列（同目录音视频按当前排序）经 [PlayerPlaylistHolder] 进程内交接，Intent 只带 queueId。
 */
fun playVideoQueued(
    context: android.content.Context,
    urls: List<String>,
    index: Int,
) {
    val safeIndex = index.coerceIn(0, urls.lastIndex.coerceAtLeast(0))
    if (urls.isEmpty()) return
    val queueId = PlayerPlaylistHolder.put(urls.map { it to filePlayTitle(it) })
    playVideo(context, urls[safeIndex], filePlayTitle(urls[safeIndex]), queueId = queueId)
}

@Composable
fun NovaXApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val tabRoutes = bottomTabs.map { it.route }

    // C11 §1.3(c)：「上一个用过的 Tab」单槽记忆（只回退一步，防来回弹跳）+ 双击退出时间戳
    var lastTabBeforeSwitch by rememberSaveable { mutableStateOf<String?>(null) }
    var lastBackAt by remember { mutableStateOf(0L) }
    val activity = LocalContext.current
    // 设置-界面「返回键直接退出」（novax_ui_back_exit，默认 false = 双击退出）
    val backExitOn by activity.novaxUiPrefs.data
        .map { prefs -> prefs[KEY_BACK_EXIT] ?: false }
        .collectAsState(initial = false)
    // P3：启动页 = 设置-界面「启动页」（player.PlayerPrefs 的 novax_ui_start_tab，只读 import）
    // BUG-C-R5-05：只在冷启动取一次首个值——原实现动态绑定 startDestination，运行期改设置
    // 会立即重建导航图（跳转 + 清空返回栈），冷启动异步值与 initial 不同还会闪跳
    // 2026-09-27 返回根目录修复（次因）：进程死亡恢复时 remember 丢失→异步重设 startDestination
    // 再建图，把恢复出来的返回栈清掉（症状：播放返回后跳回根目录/首页）。改 rememberSaveable——
    // 恢复场景首帧即拿到上次值，不再二次建图；真正冷启动行为不变
    var coldStartTab by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (coldStartTab == null) {
            coldStartTab = activity.playerPrefsFlow().map { prefs -> prefs[KEY_UI_START_TAB] ?: "home" }.first()
        }
    }
    val startDestination = coldStartTab?.takeIf { it in tabRoutes } ?: "home"

    // Tab 切换统一入口（底部导航与搜索页「去文件搜索」共用）
    fun switchTab(tab: String) {
        if (tab !in tabRoutes) return
        // C11 §5.3：先于 navigate 记录来源 Tab（切 Tab 成功后槽=来源）
        lastTabBeforeSwitch = currentRoute
        navController.navigate(tab) {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // App 级 BackHandler（优先级 3）：仅 Tab 根拦截；二级路由（详情）留给默认 popBackStack。
    // BUG-C-R6-15：Tab 内下钻态（文件页浏览器/向导、库页合集）时不做「回上一 Tab」——
    // 正常路径该 Tab 自己的 BackHandler 先拦；若调度优先级被框架重挂打乱（播放器
    // 前台返回后有实测案例），此处经 NvTabBackBus 把返回动作路由回 Tab 内执行，
    // 双通道等价语义，杜绝「文件夹里按返回跳到别的 Tab 并恢复其旧状态」。
    if (currentRoute in tabRoutes) {
        BackHandler {
            if (com.novax.app.ui.components.NvTabBackBus.isDrilled(currentRoute)) {
                com.novax.app.ui.components.NvTabBackBus.requestInTabBack(currentRoute!!)
                return@BackHandler
            }
            val prev = lastTabBeforeSwitch
            if (prev != null && prev != currentRoute) {
                // 回「上一个用过的 Tab」（一次性，清槽不形成 Tab 环）
                lastTabBeforeSwitch = null
                navController.navigate(prev) {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            } else if (backExitOn) {
                // 开关开：首次按返回直接退出（R1）
                (activity as? Activity)?.finishAffinity()
            } else {
                // 默认：双击退出（2s 窗口）
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2_000) {
                    (activity as? Activity)?.finishAffinity()
                } else {
                    lastBackAt = now
                    Toast.makeText(activity, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Liquid Glass（spec §9）：主域 env 下发。decisions.md §4.5 裁定：hazeSource 下沉到各页滚动
    // 容器（内容 Box 级 source 会让页内玻璃面落进 source 子树被防递归静默跳过——Task 5 实测），
    // 底栏玻璃的取样源由各 Tab 页滚动容器注册；底栏在 bottomBar slot 恒为兄弟（§4.1）。
    com.novax.app.ui.glass.LocalNvGlassEnvProviding {
        Scaffold(
            containerColor = NvColors.Background,
            bottomBar = {
                if (currentRoute in tabRoutes) {
                    NvBottomBar(
                        tabs = bottomTabs,
                        currentRoute = currentRoute,
                        onSelect = { tab -> switchTab(tab) },
                    )
                }
            },
        ) { padding ->
            // 内容从底栏下滚过（spec §9）：原 NavHost 的 bottom padding 与成对的
            // consumeWindowInsets 删除——键盘避让改由页内 imePadding 直接承担
            // （保留 consume 会把子级 ime insets 错误扣掉 barH；Step 4 键盘回归验证）
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                    // 2026-09-20 动效改造：库默认是 ~700ms 纯交叉淡化（慢且糊），统一收紧为
                    // NvMotion 快速淡化（Tab 切换/一般导航）；detail 路由单独覆盖为水平转场
                    enterTransition = {
                        fadeIn(tween(NvMotion.DurationNormal, easing = NvMotion.CurveDecelerate))
                    },
                    exitTransition = {
                        fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate))
                    },
                    popEnterTransition = {
                        fadeIn(tween(NvMotion.DurationNormal, easing = NvMotion.CurveDecelerate))
                    },
                    popExitTransition = {
                        fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate))
                    },
                ) {
                    composable("home") {
                        HomeScreen(
                            onOpenDetail = { navController.navigate("detail/$it") },
                            onOpenFolder = { switchTab("files") },
                            onGoToFiles = { switchTab("files") },
                            onGoToSearch = { switchTab("search") },
                            onGoToLibrary = { switchTab("library") },
                            onGoToSettings = { switchTab("settings") },
                            // 首页「添加源」：先挂一次性事件再切 Tab，文件页进入时消费并自动开向导
                            onOpenAddSource = {
                                com.novax.app.ui.files.NvUiEvents.requestAddSourceWizard()
                                switchTab("files")
                            },
                        )
                    }
                    composable("library") {
                        LibraryScreen(
                            onOpenDetail = { navController.navigate("detail/$it") },
                            onGoToFiles = { switchTab("files") },
                        )
                    }
                    composable("files") {
                        val context = LocalContext.current
                        FilesScreen(
                            onPlay = { url -> playVideo(context, url, filePlayTitle(url)) },
                            onPlayInFolder = { url, urls, idx -> playVideoQueued(context, urls, idx) },
                        )
                    }
                    composable("search") {
                        val context = LocalContext.current
                        SearchScreen(
                            onOpenDetail = { navController.navigate("detail/$it") },
                            onPlay = { id -> playById(context, id) },
                            onGoToFiles = { switchTab("files") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onOpenHistory = { navController.navigate("history") })
                    }
                    // 2026-09-27 用户需求：观看历史管理页（条目可见：续播/跳文件夹/删除/清空）
                    composable("history") {
                        com.novax.app.ui.history.WatchHistoryScreen(
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { target ->
                                // 先落事件槽，关历史页，再切文件 Tab（返回栈不悬置二级页；
                                // lastTabBeforeSwitch 清槽——文件页返回走双击退出语义而非弹回历史页）
                                com.novax.app.ui.files.NvFilesDeepLink.put(target)
                                lastTabBeforeSwitch = null
                                navController.popBackStack()
                                navController.navigate("files") {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                    // 详情页水平转场（2026-09-20）：从右滑入 250ms / 返回向右滑出 200ms，
                    // 底下页面走全局默认淡化——二级页的方向感与播放器控制层同一动效语言
                    composable(
                        "detail/{mediaId}",
                        enterTransition = {
                            slideInHorizontally(
                                tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate),
                            ) { it } + fadeIn(tween(NvMotion.DurationSlow, easing = NvMotion.CurveDecelerate))
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate),
                            ) { it } + fadeOut(tween(NvMotion.DurationNormal, easing = NvMotion.CurveAccelerate))
                        },
                    ) { entry ->
                        val context = LocalContext.current
                        val mediaId = entry.arguments?.getString("mediaId") ?: ""
                        DetailScreen(
                            mediaId = mediaId,
                            onPlay = { id -> playById(context, id) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
            // C13 §5 已投屏态：投屏中在主界面悬浮入口（须在 NavHost 之后声明以盖在内容上层），
            // 点按打开遥控面板，可拉回本机播放。
            // 放在 source Box 之外（Scaffold content 的兄弟位）：chip 是 nvGlassSurface 玻璃面，
            // 进 source 子树会违反 decisions.md §4.1 且把自身像素喂进自己的模糊采样
            if (currentRoute in tabRoutes) {
                CastOverlay(bottomPadding = padding.calculateBottomPadding())
            }
        }
    }
}

// 按 mediaId 播放：纯数字 = media_items 真实条目（2026-09-15 详情路由接真）→ novax/本地链路；
// "id#S1E2" = 详情页剧集选集（E 序号 = 同文件夹按名称排序的下标+1，与 DetailViewModel 一致）；
// 其余 = 假数据影片（内置样片，历史主键 = mediaId）
fun playById(context: android.content.Context, mediaId: String) {
    val numericId = mediaId.substringBefore('#').toLongOrNull()
    if (numericId != null) {
        val suffix = mediaId.substringAfter('#', "")
        val appContext = context.applicationContext
        // 2026-09-16 审计 P2-10：改用 App 级共享作用域（裸 CoroutineScope 无异常处理器、不可管理）
        com.novax.app.data.NvGraph.appIoScope.launch {
            val dao = MediaDb.get(appContext).mediaItemDao()
            var item = runCatching { dao.findById(numericId) }.getOrNull() ?: return@launch
            if (suffix.startsWith("S")) {
                // 选集：按同文件夹排序取第 E 个（DetailViewModel 的 EpisodeItem.number 即此序）
                val epNum = suffix.substringAfter('E', "").filter { it.isDigit() }.toIntOrNull()
                if (epNum != null && epNum >= 1) {
                    val dir = item.path.substringBeforeLast('/', "")
                    runCatching { dao.videosInDir(item.sourceId, com.novax.app.data.db.escapeLikePattern(dir)) }.getOrNull()
                        ?.getOrNull(epNum - 1)?.let { item = it }
                }
            }
            val source = runCatching { MediaDb.get(appContext).sourceDao().getById(item.sourceId) }.getOrNull()
            val url = if (source?.type == SourceType.LOCAL) {
                NvPlaybackLauncher.localPath(source.shareOrPath, item.path)
            } else {
                NvPlaybackLauncher.novaxUrl(item.sourceId, item.path)
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
                        .putExtra(
                            PlayerActivity.EXTRA_TITLE,
                            item.scrapedTitle?.takeIf { it.isNotBlank() } ?: item.name,
                        ),
                )
            }
        }
        return
    }
    // BUG-C-R5-06：mock 兼容播放分支加 debug 门控——release 下遗留 mock id（旧收藏/旧历史行）
    // 不再静默路由到内置样片；正常数据流零影响（真实条目已在上方 return）
    val isDebugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!isDebugBuild) return
    val media = com.novax.app.data.MockMediaRepository.byId(mediaId) ?: return
    val resume = com.novax.app.data.MockMediaRepository.continueWatching
        .firstOrNull { it.id == mediaId }?.progress
        ?.times(media.durationMinutes * 60_000L)?.toLong() ?: 0L
    playVideo(
        context,
        com.novax.app.data.MockMediaRepository.videoUrlFor(mediaId),
        media.title,
        // P3：内置样片仅 ~10s，续播位置 clamp 到 8s 内，避免起播即 EOS 结束
        resume.coerceAtMost(8_000L),
        // C11 §2.1：假数据影片历史主键 = mediaId（避免所有 mock 影片共用 asset URL 互相覆盖）
        historyPath = mediaId,
    )
}

/** P3：文件播放标题 = URL 解码后的文件名去扩展名（novax:// 编码路径 / 本地绝对路径通用） */
private fun filePlayTitle(url: String): String {
    val name = runCatching { java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }
        .getOrDefault(url.substringAfterLast('/'))
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}
