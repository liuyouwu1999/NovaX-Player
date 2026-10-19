package com.novax.app.data

import android.content.Context
import com.novax.app.data.db.FavoriteDao
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.MediaItemDao
import com.novax.app.data.db.ScanStateDao
import com.novax.app.data.db.SourceDao
import com.novax.app.data.db.WatchHistoryDao
import com.novax.app.data.sftp.SftpClient
import com.novax.app.data.sftp.SftpKnownHosts
import java.io.File

/**
 * App 级单例图（无 DI 框架的轻量替代）：
 * - [repository]：SMB session / OkHttp 连接池随进程存活（C8 §3 连接复用），
 *   FilesViewModel 与 PlayerActivity 共享同一实例，浏览与播放复用连接链。
 * - [sourceDao]：Room（novax_media.db）访问入口。
 */
object NvGraph {

    /** 作用域兜底（E5-1 发版审查）：DataStore 磁盘读失败（EIO/ENOSPC）等沿 collect 直达此处——记日志落盘，不崩进程。 */
    private val ioScopeHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.w("NvAppScope", "uncaught in appIoScope", e)
        appContext?.let { ctx -> runCatching { NvCrashLog.recordNonFatal(ctx, "appIoScope", e) } }
    }

    /**
     * App 级后台作用域（2026-09-16 审计 P2-10）：供「点条目起播查库」这类无需页面生命周期的
     * 短任务共用——替代随手 new 的裸 CoroutineScope（无异常处理器、实例不可管理）。
     * SupervisorJob：单任务失败不波及其他；进程存活期不 cancel（App 级语义）。
     */
    val appIoScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + ioScopeHandler,
        )

    @Volatile
    private var repository: NvSourceRepository? = null

    @Volatile
    private var appContext: Context? = null

    /**
     * App 级 Context 注入（E2-2 前移：由 NvApp.onCreate 最先调用）：仓库内 SFTP 连接的
     * known-hosts 指纹文件依赖 filesDir（TOFU，见 SftpClient 类 kdoc）。进程冷起若先经
     * 播放器深链建仓（未经本方法），SftpClient 拿不到指纹文件时直接抛错、不再回落 accept-all。
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun repository(): NvSourceRepository =
        repository ?: synchronized(this) {
            repository ?: NvSourceRepository(
                sftp = SftpClient(knownHosts = appContext?.let { File(it.filesDir, SftpKnownHosts.FILE_NAME) }),
            ).also { repository = it }
        }

    fun sourceDao(context: Context): SourceDao = MediaDb.get(context).sourceDao()

    /** 观看历史 DAO（C11 §5） */
    fun historyDao(context: Context): WatchHistoryDao = MediaDb.get(context).watchHistoryDao()

    /** 收藏 DAO（C11 §三） */
    fun favoriteDao(context: Context): FavoriteDao = MediaDb.get(context).favoriteDao()

    /** 媒体索引 DAO（C12 §1.4） */
    fun mediaItemDao(context: Context): MediaItemDao = MediaDb.get(context).mediaItemDao()

    /** 扫描状态 DAO（C12 §1.4） */
    fun scanStateDao(context: Context): ScanStateDao = MediaDb.get(context).scanStateDao()
}
