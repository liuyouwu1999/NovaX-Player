package com.novax.app.data.thumb

import android.content.Context
import com.novax.app.data.db.MediaItemDao
import java.io.File
import java.security.MessageDigest

/**
 * 缩略图 LRU 淘汰选样（C12 §三.7）——纯函数，可单测。
 * 按"最旧→最新"顺序选入淘汰集，直到剩余总量 ≤ maxBytes；被引用文件同样参与
 * （全部跳过则引用集满时永不收敛），淘汰引用中文件由调用方先清 DB 引用再删文件。
 */
internal object ThumbLru {

    /** 按 [entries] 给定顺序（调用方已按 lastModified 升序排好）返回需要淘汰的条目子集 */
    fun <T> selectEvictions(entries: List<T>, sizeOf: (T) -> Long, maxBytes: Long): List<T> {
        var total = entries.sumOf(sizeOf)
        if (total <= maxBytes) return emptyList()
        val evicted = ArrayList<T>()
        for (e in entries) {
            if (total <= maxBytes) break
            evicted.add(e)
            total -= sizeOf(e)
        }
        return evicted
    }
}

/**
 * 缩略图磁盘缓存（cacheDir/thumbs，上限 [MAX_BYTES]，C12 §三.7）。
 * 时机：App 冷启动后延迟 30s（NvThumbHolder 启动维护协程）+ 每次全量扫描结束后各跑一次 [enforce]。
 */
object ThumbCache {

    const val MAX_BYTES: Long = 200L * 1024 * 1024

    /** 临时缓存目录(查看器物化副本 / 外发副本 / side-load 字幕 / 通知栏封面)按龄清扫参数;
     *  notif_art 无 DB 引用联动,7 天清扫是唯一出量机制(spec 2026-10-10 §5.1) */
    internal val TEMP_SUBDIRS = listOf("viewers", "share", "sideload_subs", "notif_art")
    private const val TEMP_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** 缩略图文件名 key：md5("sourceId:path") hex（同源同路径复用同一 jpg，重抽覆盖写） */
    fun hashKey(sourceId: Long, path: String): String =
        MessageDigest.getInstance("MD5")
            .digest("$sourceId:$path".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun thumbsDir(context: Context): File = File(context.cacheDir, "thumbs").apply { mkdirs() }

    /**
     * 文件页旁路封面目录（BUG-C-R6-17）：这些封面没有 media_items 行引用，混在
     * [thumbsDir] 顶层会被 [enforce] 的「无主文件清扫」当孤儿误删（抽→删→再抽死循环）。
     * 独立子目录天然豁免顶层扫描，配 [enforceBrowserDir] 自己的 LRU 上限。
     */
    fun browserThumbsDir(context: Context): File = File(thumbsDir(context), "browser")

    /** 旁路封面 key(spec 2026-10-12 §4.5):文件页与通知封面同源;调用方一律走本函数,禁止自拼 */
    fun browserThumbKey(sourceId: Long, path: String, sizeBytes: Long): String =
        hashKey(sourceId, "bypass:$path:$sizeBytes")

    /** 旁路封面文件(Thumbnailer.browserThumbFileFor 实体化;通知封面接线同用,key 单一出处) */
    fun browserThumbFile(context: android.content.Context, sourceId: Long, path: String, sizeBytes: Long): File =
        File(browserThumbsDir(context), browserThumbKey(sourceId, path, sizeBytes) + ".jpg")

    /** 浏览器旁路封面 LRU 上限（独立于库内 [MAX_BYTES]） */
    const val BROWSER_MAX_BYTES: Long = 64L * 1024 * 1024

    /** 旁路封面独立 LRU（最旧优先淘汰；无 DB 引用，直接删文件即可） */
    suspend fun enforceBrowserDir(context: Context) {
        val dir = browserThumbsDir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val evicted = ThumbLru.selectEvictions(
            files.sortedBy { it.lastModified() },
            sizeOf = { it.length() },
            maxBytes = BROWSER_MAX_BYTES,
        )
        evicted.forEach { it.delete() }
    }

    // ---------------- H-4（2026-10-18 发布终审）：刮削海报缓存 ----------------

    /** 海报目录（写入方 NvScraper.posterFile；本对象只读+清理，不负责创建） */
    fun postersDir(context: Context): File = File(context.cacheDir, "scrape_posters")

    /**
     * 海报独立 LRU 上限 256MB（w500 jpg ≈ 50-150KB/张 → 约 1700~5000 张）。
     * 此前 scrape_posters 完全无清理：全量刮削 5000 条 ≈ 250-500MB 常驻 cacheDir 且删源
     * 不清——唯一出口是设置页手动清缓存。海报文件被淘汰后 DB 的 scrapePosterPath 仍指向
     * 缺失文件，NvPosterCard 的 AsyncImage 不 onSuccess → 落 NvPosterFallback 首字母渐变
     * （优雅降级，无空白无崩溃），重扫/重刮即恢复。
     */
    const val POSTERS_MAX_BYTES: Long = 256L * 1024 * 1024

    /** 海报独立 LRU（最旧优先；与 [enforce] 主 LRU 隔离，随同一次维护跑） */
    suspend fun enforcePostersDir(context: Context) {
        val files = postersDir(context).listFiles()?.filter { it.isFile } ?: return
        val evicted = ThumbLru.selectEvictions(
            files.sortedBy { it.lastModified() },
            sizeOf = { it.length() },
            maxBytes = POSTERS_MAX_BYTES,
        )
        evicted.forEach { it.delete() }
    }

    /**
     * 查看器/字幕临时缓存按龄清扫（2026-09-16 审计 P2-6）：viewers/share/sideload_subs
     * 原本只进不出、依赖系统 cacheDir 被动回收——按「最后修改 >7 天即删」主动清。
     * .download 半成品（含物化失败的孤儿）同样被此清扫兜底。维护时机随 [enforce]。
     */
    private fun sweepTempCaches(context: Context) {
        TEMP_SUBDIRS.forEach { name -> sweepDirByAge(File(context.cacheDir, name)) }
    }

    /**
     * 按龄清扫单个临时目录（sweepTempCaches 与隐私重锁/加锁的 notif_art 兜底共用口径，
     * 隐私计划 Task 11 抽出）：「最后修改 >7 天即删」。notif_art 文件名不可全量反查范围，
     * 反算不出 key 的旧解码帧由本清扫兜底出量。
     */
    internal fun sweepDirByAge(dir: File) {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }

    /**
     * 1) 无主文件清理：磁盘上的 jpg 不在 DB 引用集 → 直接删（含对账清扫后被删行的遗图）；
     * 2) LRU：按 lastModified 升序删最旧，直到总大小 ≤ [MAX_BYTES]。
     *    被引用文件同样参与淘汰；删除引用中文件前必须先 resetThumb(id) 清 thumbnailPath——
     *    否则真帧分支继续加载已删文件 → onError → 再复位 → S10 死循环（C12 §三.7）。
     *    清引用后可见项自动重新 CAS 入队重抽（单向回流），时长/分辨率元数据由 resetThumb 保留。
     */
    suspend fun enforce(context: Context, dao: MediaItemDao) {
        sweepTempCaches(context)
        enforceBrowserDir(context)   // 旁路封面独立 LRU（与无主清扫隔离，见 browserThumbsDir 注释）
        enforcePostersDir(context)   // H-4：刮削海报独立 LRU（此前无任何清理，见 enforcePostersDir 注释）
        val dir = thumbsDir(context)
        val refs = dao.allThumbRefs().associateBy { it.thumbnailPath }
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: return
        // 1) 无主文件：磁盘有图但无行引用（重抽覆盖遗留 / 对账清扫后被删行的遗图）→ 直接删。
        //    在途保护（BUG-C-R5-09）：抽帧「写盘 → updateThumb(DONE) 入库」之间有窗口，
        //    刚写完尚未入库的 jpg 会被快照差集误判无主删掉 → 卡片闪骨架重抽。跳过 60s 内新文件。
        val freshCutoff = System.currentTimeMillis() - 60_000L
        val referenced = refs.keys.toHashSet()
        val orphaned = files
            .filterNot { it.path in referenced }
            .filterNot { it.lastModified() >= freshCutoff }
        orphaned.forEach { it.delete() }
        // 2) LRU 淘汰（最旧优先；引用中文件先清 DB 引用再删）
        val kept = files.filter { it.path in referenced }
        val evicted = ThumbLru.selectEvictions(
            kept.sortedBy { it.lastModified() },
            sizeOf = { it.length() },
            maxBytes = MAX_BYTES,
        )
        evicted.forEach { f ->
            refs[f.path]?.let { dao.resetThumb(it.id) }   // 先清 DB 引用再删文件
            f.delete()
        }
    }

    /**
     * 删除单源（C12 §八X4 源被删）联动：先删该源全部索引行（引用解除）→
     * 磁盘上不再被任何行引用的 jpg 即该源遗图，直接清。
     * H-4（2026-10-18 发布终审）：同口径清 scrape_posters 遗留海报——该源行的
     * scrapePosterPath 随 deleteBySource 消失，海报文件落在剩余全库引用集之外即删。
     */
    suspend fun purgeSource(context: Context, dao: MediaItemDao, sourceId: Long) {
        dao.deleteBySource(sourceId)
        val referenced = dao.allThumbRefs().mapTo(HashSet()) { it.thumbnailPath }
        thumbsDir(context).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".jpg") && f.path !in referenced) f.delete()
        }
        val posterRefs = runCatching { dao.allPosterRefs().toHashSet() }.getOrDefault(emptySet())
        postersDir(context).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".jpg") && f.path !in posterRefs) f.delete()
        }
    }
}
