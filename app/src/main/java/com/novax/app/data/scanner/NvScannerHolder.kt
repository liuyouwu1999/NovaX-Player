package com.novax.app.data.scanner

import android.content.Context
import android.net.ConnectivityManager
import com.novax.app.data.NvGraph
import com.novax.app.data.NvSecrets
import com.novax.app.data.db.MediaDb

/**
 * MediaScanner 单例访问点（App 无 DI 框架）：懒创建，避免全局 constructor 副作用
 * （扫描 scope / 连接池只有真正开始扫描才建立）。App 全程共享一个实例，
 * 进度 StateFlow 与 jobs 表随进程存活。
 */
object NvScannerHolder {

    @Volatile
    private var scanner: MediaScanner? = null

    fun get(context: Context): MediaScanner =
        scanner ?: synchronized(this) {
            scanner ?: create(context.applicationContext).also { scanner = it }
        }

    private fun create(appContext: Context): MediaScanner {
        val db = MediaDb.get(appContext)
        return MediaScanner(
            mediaDao = db.mediaItemDao(),
            scanDao = db.scanStateDao(),
            lister = { source, dir -> NvGraph.repository().list(source, dir) },
            shareLister = { source ->
                NvGraph.repository().listSmbShares(
                    host = source.host,
                    port = source.port,
                    username = source.username,
                    password = NvSecrets.decrypt(source.password),
                    domain = source.domain.trim().takeIf { it.isNotBlank() },
                )
            },
            // 2026-09-28 偷跑流量修复（P1-1）：蜂窝等计量网络下远程源跳过 headerReader 嗅探
            // （仅列目录建索引）；网络服务不可得时按受限保守处理（宁可不嗅探）。
            // 注入模式照抄 NvThumbHolder.isThumbNetworkAllowed
            allowNetworkSniff = {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm != null && !cm.isActiveNetworkMetered
            },
        )
    }
}
