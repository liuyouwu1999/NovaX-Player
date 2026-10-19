package com.novax.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.novax.app.data.NvPaths
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.player.PlayerActivity

/**
 * C11 §5.8 续播启动器（首页继续观看卡/收藏行点击）：
 * - sourceId != null：NAS 源 → 重建 novax://<sourceId><encoded path> 续播 positionMs
 * - 直连 URL / 本地绝对路径：直接起播
 * - 假数据影片（path 为 mediaId）：走现有 playById()（mock 播放地址 + mock 进度）
 */
object NvPlaybackLauncher {

    /**
     * novax:// 播放/续播地址唯一构造器（与 NvUri.forSource 同构）：
     * "novax://<id>" + Uri.encode(NvPaths.normalize(path), "/")。
     * 路径先 normalize 保证以 "/" 开头（不会产生 "novax://1//a" 分叉），再整体 encode；
     * FilesViewModel.playUrl 与 [resumeFrom] 必须共用本函数，确保同文件同一历史键。
     */
    fun novaxUrl(sourceId: Long, path: String): String =
        buildNovaxUrl(sourceId, path) { s, keep -> Uri.encode(s, keep) }

    /** 纯函数内核（encode 注入，供 JUnit 验证与 playUrl 的同构性） */
    internal fun buildNovaxUrl(sourceId: Long, path: String, encode: (String, String) -> String): String =
        "novax://$sourceId" + encode(NvPaths.normalize(path), "/")

    /**
     * LOCAL 源绝对路径唯一构造器：源根去尾分隔符 + NvPaths.normalize(源内路径)。
     * 播放历史（PlayerActivity 直连路径写入）与收藏键（sourceId=null + 绝对路径）共用。
     */
    fun localPath(sourceRoot: String, path: String): String =
        sourceRoot.trimEnd('/', '\\') + NvPaths.normalize(path)

    /** 假数据 mediaId 判定：既非 URL 亦非绝对路径（C11 §4.3 约定 path=mediaId） */
    fun isMockMediaId(path: String): Boolean =
        !path.contains("://") && !path.startsWith("/")

    fun resumeFrom(context: Context, e: WatchHistoryEntity) {
        val url: String = when {
            e.sourceId != null ->
                // 与 FilesViewModel.playUrl 完全同构（normalize 后 encode、无多余斜杠）
                novaxUrl(e.sourceId, e.path)
            !isMockMediaId(e.path) -> e.path
            else -> {
                com.novax.app.playById(context, e.path)   // 假数据影片：现有 mock 链路
                return
            }
        }
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, e.title)
                .putExtra(PlayerActivity.EXTRA_POSITION_MS, e.positionMs),
        )
    }
}
