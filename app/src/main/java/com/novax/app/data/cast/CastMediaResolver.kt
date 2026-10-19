package com.novax.app.data.cast

import android.content.Context
import com.novax.app.data.NvGraph
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceType

/**
 * playUrl → CastMedia 解析器（播放器内投屏用：屏内只有 URL+标题+进度，没有库表行）。
 * - novax://<sourceId><源内路径>（NvPlaybackLauncher.novaxUrl 产物）：id 与路径直接还原，
 *   sizeBytes/durationMs 回查 media_items 补全；
 * - LOCAL 源绝对路径（NvPlaybackLauncher.localPath 产物）：按 shareOrPath 前缀匹配 LOCAL 源。
 * 解析不到返回 null（上层提示「当前视频无法投屏」——假数据/未知来源）。
 */
object CastMediaResolver {

    /**
     * novax://<sourceId><Uri.encode 过的路径> → (sourceId, 解码后的源内路径)。
     * review C1：novaxUrl 构造时对路径做了 Uri.encode（保留 "/"），必须解码——
     * 否则中文/空格路径查库与 SMB 打开全部落空。非 novax 地址返回 null。
     */
    fun parseNovaxPath(playUrl: String): Pair<Long, String>? {
        if (!playUrl.startsWith("novax://")) return null
        val rest = playUrl.removePrefix("novax://")
        val sourceId = rest.substringBefore('/').toLongOrNull() ?: return null
        val decoded = runCatching {
            java.net.URLDecoder.decode(rest.substringAfter('/', ""), "UTF-8")
        }.getOrNull() ?: return null
        val path = "/" + decoded.removePrefix("/")
        return sourceId to path
    }

    suspend fun resolve(context: Context, playUrl: String, title: String): CastMedia? {
        val db = MediaDb.get(context)
        return when {
            playUrl.startsWith("novax://") -> {
                val (sourceId, path) = parseNovaxPath(playUrl) ?: return null
                resolveItem(db, sourceId, path, playUrl, title)
            }
            playUrl.startsWith("/") -> {
                val source = db.sourceDao().getAll()
                    .filter { it.type == SourceType.LOCAL }
                    .filter { it.shareOrPath.isNotBlank() }
                    .filter { playUrl.startsWith(it.shareOrPath.trimEnd('/', '\\')) }
                    .maxByOrNull { it.shareOrPath.length } ?: return null
                val inner = playUrl.removePrefix(source.shareOrPath.trimEnd('/', '\\'))
                val path = if (inner.startsWith("/")) inner else "/$inner"
                resolveItem(db, source.id, path, playUrl, title)
            }
            else -> null
        }
    }

    private suspend fun resolveItem(
        db: MediaDb,
        sourceId: Long,
        path: String,
        playUrl: String,
        title: String,
    ): CastMedia? {
        val item = db.mediaItemDao().findByKey(sourceId, path)
        return CastMedia(
            sourceId = sourceId,
            path = path,
            title = title,
            sizeBytes = item?.sizeBytes ?: 0L,
            durationMs = item?.durationMs ?: 0L,
            playUrl = playUrl,
            subtitlePath = null,   // T10 由 CastSubtitle 解析
        )
    }
}
