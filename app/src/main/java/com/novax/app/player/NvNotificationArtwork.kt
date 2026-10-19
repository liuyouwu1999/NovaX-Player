package com.novax.app.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import com.novax.app.data.NvGraph
import com.novax.app.data.NvPaths
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.thumb.ThumbCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 通知栏封面解析器(spec 2026-10-10 §4.2):现成资源分级,挂起、永不抛——任何失败 = null =
 * 通知栏回落 app 图标(与旧版行为一致)。
 *
 * 分级:notif_art 磁盘缓存(先于计量闸——读本地文件零网络)→ 非 JELLYFIN 已索引 novax 源
 * (LOCAL/SMB/SFTP/WEBDAV):scrapePosterPath(空串=无命中墓碑,跳过)优先、thumbnailPath 次之
 * → JELLYFIN:计量闸门先行后定宽小图下载。
 *
 * 铁律(spec §5.1/§5.2):绝不触碰抽帧管线(requestThumb/thumbState);文件名一律以 DB 行为准,
 * 严禁自拼 hashKey;JELLYFIN 下载是本类唯一网络操作,闸门必须先于任何 client 调用
 * (冷 session 登录 POST 也是请求)。
 *
 * 依赖全部构造注入(JVM 单测,codec 为 android.graphics 不入单测);生产接线见
 * [resolveNotificationArtwork]。
 */
class NvNotificationArtwork(
    private val notifArtDir: File,
    /** 计量网络(连接服务不可得按 true,与 NvMeteredGuard 同款保守口径) */
    private val isMetered: () -> Boolean,
    /** media_items 单行查询(已索引抽帧/刮削海报);null=查无此行 */
    private val findByMediaItem: suspend (sourceId: Long, path: String) -> ThumbRow?,
    /** Jellyfin 海报下载(调用方闸门已过;失败 null) */
    private val downloadPoster: suspend (itemId: String) -> ByteArray?,
    /**
     * 文件页旁路封面只读(spec 2026-10-12 §4.5 第4级):仅 LOCAL 且调用方已推导出 absPath 时被调;
     * 生产实现=读 ThumbCache.browserThumbFile 同一文件(用户文件夹所见封面),纯磁盘零网络,
     * 不触发抽帧不写库。spec §3.4:这是「文件夹可见、通知不可见」断层的主修。
     */
    private val browserThumbReader: suspend (sourceId: Long, relPath: String, absPath: String) -> ByteArray? = { _, _, _ -> null },
    /**
     * 本地单帧抓帧(spec §4.5 第5级,评审可裁点):前四级全落空后调用;生产实现必须自带 1s 硬墙钟
     * (独立线程+future.get;withTimeoutOrNull 对阻塞 native 非硬墙钟,spec §4.5 轮2 P2 修正形态)。
     * null=禁用(默认;生产接线显式开启)。
     */
    private val frameGrabber: (suspend (absPath: String) -> ByteArray?)? = null,
    /**
     * 解析落空兜底(2026-10-10 补丁 A):查无此行/计量闸/下载失败/异常统一垫 app 图标字节——
     * 系统模板无封面时渲染纯色块,喂图标才是 spec §2「app 图标兜底」的可见实现;
     * 生产注入 [appIconArtwork],JVM 测试注入假实现
     */
    private val iconFallback: () -> ByteArray? = { null },
    /** 解码+归一化(android.graphics;JVM 测试注入假实现) */
    private val codec: (ByteArray) -> ByteArray? = ::press,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** media_items 行里通知栏关心的两列(解耦 Room 实体,便于 JVM 测试) */
    data class ThumbRow(val scrapePosterPath: String?, val thumbnailPath: String?)

    suspend fun resolve(
        source: SourceEntity?,
        path: String?,
        jellyfinItemId: String?,
        absPath: String? = null,
    ): ByteArray? {
        val resolved = withContext(io) {
            runCatching {
                if (source == null) return@runCatching resolveDirectGrab(absPath)
                if (source.type == SourceType.JELLYFIN) resolveJellyfin(jellyfinItemId)
                else resolveIndexed(source.id, path) ?: resolveLocalFallbacks(source, path, absPath)
            }.getOrNull()
        }
        // 补丁 A:落空统一图标垫底;图标加载自身失败仍 = null(§5.3 永不抛)
        return resolved ?: withContext(io) { runCatching { iconFallback() }.getOrNull() }
    }

    private suspend fun resolveJellyfin(itemId: String?): ByteArray? {
        if (itemId.isNullOrBlank()) return null
        val key = cacheKey("jf:$itemId")
        cacheHit(key)?.let { return it }
        if (isMetered()) return null   // 闸门在任何 client 调用之前(冷 session 登录 POST 也是请求)
        val raw = downloadPoster(itemId) ?: return null
        val pressed = codec(raw) ?: return null
        writeCache(key, pressed)
        return pressed
    }

    private suspend fun resolveIndexed(sourceId: Long, path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val row = findByMediaItem(sourceId, path) ?: return null
        // 已刮削海报优先(EPISODE 行=整组剧集海报,与库页同源非错图);空串墓碑跳过
        val file = listOfNotNull(
            row.scrapePosterPath?.takeIf { it.isNotBlank() },
            row.thumbnailPath,
        ).firstOrNull { File(it).isFile } ?: return null
        val key = cacheKey("local:$file")   // thumbnailPath 含 size+mtime → 文件一变 key 即变,天然免失效
        cacheHit(key)?.let { return it }
        val pressed = codec(File(file).readBytes()) ?: return null
        writeCache(key, pressed)
        return pressed
    }

    /** 第4/5级(spec §4.5 数据契约):仅 LOCAL;SMB/SFTP/WEBDAV 天然不进(shareOrPath 非本地绝对路径) */
    private suspend fun resolveLocalFallbacks(source: SourceEntity, path: String?, absPath: String?): ByteArray? {
        if (source.type != SourceType.LOCAL) return null
        if (path.isNullOrBlank() || absPath.isNullOrBlank()) return null
        val len = lengthOf(absPath) ?: return null
        // 第4级:旁路缓存(先查 notif_art 归一化缓存,再读旁路原文件;key 单一出处=Task 1 的 ThumbCache)
        val key4 = cacheKey("bypass:${source.id}:$path:$len")
        cacheHit(key4)?.let { return it }
        browserThumbReader(source.id, path, absPath)?.let { raw ->
            codec(raw)?.let {
                writeCache(key4, it)
                return it
            }
        }
        return grabAndCache(cacheKey("frame:${source.id}:$path:$len"), absPath)
    }

    /** source==null(源根之外直连文件)仅第5级可跑,key 前缀 direct:(spec §4.5 数据契约行3) */
    private suspend fun resolveDirectGrab(absPath: String?): ByteArray? {
        // T2 评审遗留清理:原先 val grab 绑定未使用,仅判空即可
        if (frameGrabber == null) return null
        if (absPath.isNullOrBlank()) return null
        val len = lengthOf(absPath) ?: return null
        return grabAndCache(cacheKey("frame:direct:$absPath:$len"), absPath)
    }

    /** 第5级:先查缓存再抓帧(同参第二次解析不再调 frameGrabber,spec §5.1 行3;轮1计划评审 B-P0-2) */
    private suspend fun grabAndCache(key: String, absPath: String): ByteArray? {
        cacheHit(key)?.let { return it }
        val raw = frameGrabber?.invoke(absPath) ?: return null
        val pressed = codec(raw) ?: return null
        writeCache(key, pressed)
        return pressed
    }

    /** sizeBytes≤0/文件不存在 = 跳过4/5级(对齐文件页 ensureBrowserThumb 同款语义,spec §4.5) */
    private fun lengthOf(absPath: String): Long? =
        runCatching { File(absPath).takeIf { it.isFile }?.length() }.getOrNull()
            ?.takeIf { it > 0L }

    private fun cacheHit(key: String): ByteArray? =
        File(notifArtDir, key)
            .takeIf { it.isFile && it.length() > 0 }
            ?.let { f -> runCatching { f.readBytes() }.getOrNull() }

    private fun writeCache(key: String, bytes: ByteArray) {
        runCatching {
            notifArtDir.mkdirs()
            File(notifArtDir, key).writeBytes(bytes)
        }
    }

    companion object {
        /** 归一化目标:长边 ≤480px;字节 ≤64KB(session Bundle/内存纪律,spec §5.3) */
        internal const val TARGET_PX = 480
        internal const val MAX_BYTES = 64L * 1024

        fun cacheKey(raw: String): String =
            MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * bounds-first 下采样解码 + 归一化(spec §4.2 第 6 条):服务器原图可达数千像素,
         * 禁止全量解码(数百 MB 瞬时分配 = 低端机 GC 风暴面);inSampleSize 采样后
         * createScaledBitmap 收尾,质量 80→55→35 逐级重压直到 ≤64KB。
         * android.graphics 依赖:仅生产接线走默认值,JVM 测试注入假 codec。
         */
        internal fun press(raw: ByteArray): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                raw, 0, raw.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > TARGET_PX) {
                val scale = TARGET_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                var best: ByteArray? = null
                for (quality in intArrayOf(80, 55, 35)) {
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val bytes = out.toByteArray()
                    if (bytes.size <= MAX_BYTES) return bytes
                    best = bytes
                }
                return best
            } finally {
                scaled.recycle()
                if (scaled !== bitmap) bitmap.recycle()
            }
        }
    }
}

/**
 * 生产接线(spec §4.2/§4.3):真实依赖组装;挂起、永不抛。调用方纪律(spec §4.3 调度纪律):
 * JELLYFIN 会话必须在 itemId 解析点(首个 client 调用、session 已暖,authenticate 短路无并发
 * 双登录)之后调用;Activity 侧与字幕/转码解析窗口重叠(async),Service 换集侧串行
 * (冷缓存双 5s 独立收窄,单次尝试,最坏 ~10s,spec §5.2 已知成本)。
 * source=null(直连无源)不早退——落空路径统一走图标垫底(补丁 A)。
 */
suspend fun resolveNotificationArtwork(
    context: android.content.Context,
    source: SourceEntity?,
    path: String?,
    jellyfinItemId: String?,
    absPath: String? = null,
): ByteArray? {
    val dao = NvGraph.mediaItemDao(context)
    val client = NvGraph.repository().jellyfin
    val resolver = NvNotificationArtwork(
        notifArtDir = File(context.cacheDir, "notif_art"),
        isMetered = { NvMeteredGuard.isMeteredNetwork(context) },
        findByMediaItem = { sourceId, p ->
            runCatching {
                dao.findByPaths(sourceId, listOf(p)).firstOrNull()
                    ?.let { NvNotificationArtwork.ThumbRow(it.scrapePosterPath, it.thumbnailPath) }
            }.getOrNull()
        },
        downloadPoster = { itemId -> source?.let { client.posterBytes(it, itemId) } },
        iconFallback = { appIconArtwork(context) },
        browserThumbReader = { sourceId, relPath, abs ->
            // 文件页旁路封面只读(spec §4.5 第4级):用户文件夹所见即通知所得;纯磁盘,零网络
            val f = ThumbCache.browserThumbFile(context, sourceId, relPath, File(abs).length())
            if (f.isFile && f.length() > 0) f.readBytes() else null
        },
        frameGrabber = grabNotificationFrame(context),
    )
    // absPath 必须透传(轮1计划评审 A-P2):漏传 = 编译与单测全绿但生产 4/5 级永不触发
    return resolver.resolve(source, path, jellyfinItemId, absPath)
}

/** 第5级抓帧进程级单飞(防换集风暴堆积);返回 JPEG 原帧字节(codec 归一化在 resolver 内) */
private val frameGrabMutex = Mutex()

private fun grabNotificationFrame(context: android.content.Context): suspend (String) -> ByteArray? = { absPath ->
    frameGrabMutex.withLock {
        withContext(Dispatchers.IO) {
            val exec = Executors.newSingleThreadExecutor()
            try {
                val future = exec.submit(Callable {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(absPath)
                        r.embeddedPicture ?: run {
                            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                            // 10% 处(ms→μs);durMs=0 时 getFrameAtTime(0) 取首帧,合法
                            val frame = r.getFrameAtTime(durMs * 100L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (frame != null) {
                                val out = ByteArrayOutputStream()
                                frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                val bytes = out.toByteArray()
                                frame.recycle()
                                bytes
                            } else {
                                null
                            }
                        }
                    } finally {
                        r.release()
                    }
                })
                // 1s 拿不到=弃单落 app 图标(spec §4.5 时延预算);被弃线程随 native 返回自然消亡,
                // 单飞互斥保证至多一个在途,泄漏面有界
                runCatching { future.get(1L, TimeUnit.SECONDS) }.getOrNull()
            } finally {
                exec.shutdownNow()
            }
        }
    }
}

/**
 * LOCAL 直连反查内核(2026-10-10 补丁 B,纯函数):绝对路径 → (源 id, 源内路径)。
 * 双侧 NvPaths.normalize(「..」穿越/反斜杠/尾分隔符消解)后最长前缀 + 段边界匹配;
 * 嵌套根取最深;查无 = null。播放 URL 与历史/收藏键零改动——封面素材按键查库,不迁移键。
 */
internal fun localReverseLookup(absPath: String, localRoots: List<Pair<Long, String>>): Pair<Long, String>? {
    if (localRoots.isEmpty()) return null
    val nabs = NvPaths.normalize(absPath)
    return localRoots
        .map { (id, root) -> id to NvPaths.normalize(root) }
        .filter { (_, nroot) ->
            nabs.startsWith(nroot) && (nroot == "/" || nabs.length == nroot.length || nabs[nroot.length] == '/')
        }
        .maxByOrNull { it.second.length }
        ?.let { (id, nroot) -> id to NvPaths.normalize(nabs.removePrefix(nroot)) }
}

/**
 * LOCAL 直连反查生产件(补丁 B):绝对路径 → 已索引 LOCAL 源 + 源内路径;查无 = null。永不抛。
 * 纯本地 DB 查询零网络;调用方拿结果喂 [resolveNotificationArtwork] 与源昵称副文字。
 * 仅认裸绝对路径(库页/文件页 LOCAL 播放 URL 的唯一形态);file:// 等由调用方先解路径。
 */
suspend fun resolveLocalDirectTarget(
    context: android.content.Context,
    absPath: String,
): Pair<SourceEntity, String>? = withContext(Dispatchers.IO) {
    runCatching {
        if (!absPath.startsWith("/")) return@runCatching null
        val dao = NvGraph.sourceDao(context)
        val roots = dao.getAll()
            .filter { it.type == SourceType.LOCAL }
            .map { it.id to it.shareOrPath }
        val hit = localReverseLookup(absPath, roots) ?: return@runCatching null
        dao.getById(hit.first)?.let { it to hit.second }
    }.getOrNull()
}

/** app 图标封面字节(补丁 A 生产件):自适应图标 → 192px JPEG;进程内 memo。永不抛 */
@Volatile
private var appIconBytes: ByteArray? = null

fun appIconArtwork(context: android.content.Context): ByteArray? {
    appIconBytes?.let { return it }
    return runCatching {
        val drawable = context.getDrawable(com.novax.app.R.mipmap.ic_launcher) ?: return null
        val side = 192
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(bmp).let { c ->
            drawable.setBounds(0, 0, side, side)
            drawable.draw(c)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()?.also { appIconBytes = it }
}
