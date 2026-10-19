package com.novax.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import com.novax.app.R
import com.novax.app.novaxUiPrefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** DownloadManager 状态快照（status 取平台 DownloadManager.STATUS_* 常量） */
data class UpdateDmState(
    val status: Int,
    val bytesSoFar: Long,
    val totalBytes: Long,
    val localPath: String?,
)

/**
 * spec §2.5：`update/` 目录清理策略——只保留两类，其余一律删：
 * ① 活动下载的目标文件（状态 ∈ PENDING/RUNNING/PAUSED）；
 * ② 已下载完成的待装包，且 pendingVersion 比 currentVersion 新
 *    （判空先于比较：pendingVersion=null 一律不留）。
 */
object UpdateCleanupPolicy {

    fun shouldDelete(
        fileName: String,
        active: Triple<Long, Int, String?>?,
        pendingVersion: String?,
        currentVersion: String,
        completedPendingFile: String?,
    ): Boolean {
        // 保留①：活动下载目标文件（pending/running/paused；successful/failed 不在此列，交②或删）
        if (active != null && active.third == fileName && active.second in ACTIVE_KEEP_STATUSES) {
            return false
        }
        // 保留②：待装包且目标版本更新（判空先于比较）
        if (completedPendingFile == fileName &&
            pendingVersion != null &&
            NvVersionCompare.compare(currentVersion, pendingVersion) < 0
        ) {
            return false
        }
        return true
    }

    private val ACTIVE_KEEP_STATUSES = intArrayOf(
        DownloadManager.STATUS_PENDING,
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED,
    )
}

/**
 * spec §2.5：系统 DownloadManager 薄壳——enqueue/query/cancel/清理/安装意图。
 * 仅做编排，不做业务判断（是否可装、校验等在 Task 9）。
 */
class UpdateDownloader(private val context: Context) {

    // 清持久化键是 DataStore 挂起写：文件删除在调用线程同步完成，键清理后台 fire-and-forget
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dm(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** 入队下载；enqueue 前防御性删除同名残留（断点/损坏旧包不干扰本次下载） */
    fun enqueue(asset: NvReleaseAsset, versionName: String): Long {
        File(context.getExternalFilesDir(null), "update/${asset.name}").delete()
        val req = DownloadManager.Request(asset.url.toUri())
            .setMimeType("application/vnd.android.package-archive")
            .setTitle(context.getString(R.string.update_dl_notif_title))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "update/${asset.name}")
        return dm().enqueue(req)
    }

    /** 按 id 查状态；查无（记录已被删/刚入队未落库）返回 null */
    fun query(id: Long): UpdateDmState? {
        dm().query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            return UpdateDmState(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bytesSoFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localPath = localUri?.let { Uri.parse(it).path },
            )
        }
    }

    fun cancel(id: Long) {
        dm().remove(id)
    }

    fun fileOf(state: UpdateDmState): File? = state.localPath?.let { File(it) }

    /**
     * spec §2.5：`update/` 目录逐文件套清理策略，true 即删；
     * 发现 pendingVersion ≤ currentVersion（含 null=无待装）时同时清持久化键
     * （明文「删除文件并清持久化键」：download_id / pending_version 不留陈账）。
     */
    fun cleanup(pendingVersion: String?, currentVersion: String, activeId: Long?) {
        // active 现场 query 折成 Triple(id, status, 目标文件名)——策略按文件名比对
        val active = activeId?.let { id ->
            query(id)?.let { s -> Triple(id, s.status, s.localPath?.substringAfterLast('/')) }
        }
        val files = File(context.getExternalFilesDir(null), DIR).listFiles().orEmpty()
        for (f in files) {
            if (UpdateCleanupPolicy.shouldDelete(
                    fileName = f.name,
                    active = active,
                    pendingVersion = pendingVersion,
                    currentVersion = currentVersion,
                    completedPendingFile = f.name,
                )
            ) {
                f.delete()
            }
        }
        if (pendingVersion == null || NvVersionCompare.compare(currentVersion, pendingVersion) >= 0) {
            scope.launch {
                // 清键失败不致命（下次 cleanup 会再试）：DataStore 磁盘 I/O 异常静默兜底，
                // 不让未捕获异常沿协程默认处理器崩掉进程（cleanup 挂在检查/安装两条用户路径上）
                runCatching {
                    context.novaxUiPrefs.edit {
                        it.remove(KEY_UPDATE_DOWNLOAD_ID)
                        it.remove(KEY_UPDATE_PENDING_VERSION)
                    }
                }
            }
        }
    }

    /** 应用内安装意图：FileProvider content URI + 授权读 + 新任务栈 */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val DIR = "update"
    }
}
