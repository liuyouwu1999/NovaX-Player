package com.novax.app.data.thumb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.ScanStatus
import com.novax.app.player.KEY_THUMB_METERED
import com.novax.app.player.playerPrefsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Thumbnailer 单例访问点（App 无 DI 框架）：懒创建，避免全局 constructor 副作用
 * （抽帧 scope / 缓存目录只有真正用到来才建立）。App 全程共享一个实例：
 * 优先级队列 / Semaphore(3) 并发预算 / inFlight 去重集 / 后台渐进抽帧循环跨调用一致（C12 §三.5 + 本任务提速）。
 */
object NvThumbHolder {

    /**
     * FAILED 复位的最小间隔（2026-09-21 审计 P2）：网络切换/恢复事件可能密集到达，
     * 每次 reset 都让全部 FAILED 行多试一轮——2 分钟防抖把「快速断连-重连风暴」下的
     * 重复重试压到至多 1 轮。
     */
    private const val RETRY_MIN_INTERVAL_MS = 2 * 60 * 1000L

    private val lastRetryAt = AtomicLong(0L)

    @Volatile
    private var thumbnailer: Thumbnailer? = null

    /** §1a:「流量网络也出封面」开关的进程级快照;偏好 Flow 收集刷入。
     *  不走 playerPrefsSnapshot()——其缓存只在写偏好/播放器启动时填充,文件浏览路径
     *  永不加热,新进程直奔文件夹会读不到已保存的开关(冷缓存洞) */
    @Volatile
    private var thumbMeteredAllowed: Boolean = false

    /** 闸门闭包写入的实时判定探针(闭包持 appContext;null=未初始化,按受限保守处理) */
    @Volatile
    private var networkAllowedProbe: (() -> Boolean)? = null

    /** §1a 封面闸门同源判定(供进文件夹重置前置检查;未初始化时按受限=false 保守) */
    fun thumbNetworkAllowed(): Boolean = networkAllowedProbe?.invoke() ?: false

    fun get(context: Context): Thumbnailer =
        thumbnailer ?: synchronized(this) {
            thumbnailer ?: create(context.applicationContext).also { thumbnailer = it }
        }

    private fun create(appContext: Context): Thumbnailer {
        val db = MediaDb.get(appContext)
        val dao = db.mediaItemDao()
        val scanDao = db.scanStateDao()
        // 2026-09-28 偷跑流量修复（F1a）：蜂窝等计量网络禁止自动抽帧（标 FAILED 走
        // 下方网络恢复通道，到 Wi-Fi 自动补抽）；网络服务不可得时按受限处理（宁可不抽）。
        // 抽帧闸门与网络恢复回调（F9）共用同一判定
        val isThumbNetworkAllowed = {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm != null && (!cm.isActiveNetworkMetered || thumbMeteredAllowed)
        }
        // §1a:偏好 Flow 收集开关(app 级 scope;现成同型 FilesViewModel KEY_LAN_DISCOVERY 收集)
        NvGraph.appIoScope.launch {
            appContext.playerPrefsFlow()
                .map { it[KEY_THUMB_METERED] ?: false }
                .collect { thumbMeteredAllowed = it }
        }
        networkAllowedProbe = { isThumbNetworkAllowed() }
        val t = Thumbnailer(
            context = appContext,
            repo = NvGraph.repository(),
            dao = dao,
            sourceProvider = { sourceId -> db.sourceDao().getById(sourceId) },
            isThumbNetworkAllowed = isThumbNetworkAllowed,
        )
        // 扫描 DONE 后自动后台渐进抽帧（低优先级 + ≤1 个/秒限速，Thumbnailer 内实现）
        t.startScanDoneWatcher(scanDao.observeAll())
        // C12 §三.7：冷启动后延迟 30s 跑一次 LRU 清理（无主文件 + 超 200MB 淘汰）；失败不打断，下次扫描结束后再清
        t.scheduleMaintenance(delayMs = 30_000) { ThumbCache.enforce(appContext, dao) }
        // 2026-09-21 审计 P2：网络恢复时批量复位 FAILED 墓碑并重启渐进 pass——
        // NAS 离线会把待抽行全部烧成永久 FAILED（重扫不重置），mp4/mkv 无任何恢复通道。
        // 默认网络 onAvailable 是边沿触发（含注册时的当次回调），配 2min 防抖防切换风暴。
        registerNetworkRecovery(appContext, scanDao, t, isThumbNetworkAllowed)
        return t
    }

    private fun registerNetworkRecovery(
        appContext: Context,
        scanDao: com.novax.app.data.db.ScanStateDao,
        t: Thumbnailer,
        isThumbNetworkAllowed: () -> Boolean,
    ) {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // 2026-09-28 偷跑流量修复（F9）：计量网络（蜂窝）的 onAvailable 直接
                        // 跳过——抽帧闸门会把刚复位的行再次标 FAILED，reset→FAILED 是纯
                        // DB churn（零视频字节、白耗电），蜂窝下没有任何可恢复对象
                        if (!isThumbNetworkAllowed()) return
                        val now = System.currentTimeMillis()
                        val last = lastRetryAt.get()
                        if (now - last < RETRY_MIN_INTERVAL_MS) return
                        if (!lastRetryAt.compareAndSet(last, now)) return
                        NvGraph.appIoScope.launch {
                            runCatching {
                                if (t.retryFailedThumbs() > 0) {
                                    val done = scanDao.getAll()
                                        .filter { it.status == ScanStatus.DONE }
                                    t.restartPasses(done)
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
