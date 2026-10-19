package com.novax.app.data.privacy

import android.util.Log
import com.novax.app.data.NvPaths
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.thumb.ThumbCache
import com.novax.app.player.NvHistoryDirect
import com.novax.app.player.NvNotificationArtwork
import java.io.File

/**
 * 加锁对账清除原语（隐私功能计划 Task 10，spec §4.1 加锁动作 / §5.4 真抹除）：
 * 删除某源某目录范围内的媒体索引行、缩略图文件与（可选）历史/收藏行。自身不判定时机——
 * 由组合方调用：Task 13 加锁/开无痕（长按动作后立即清）、Task 11 重锁编排（含冷启动补跑）。
 *
 * 全程 NvSecureWipe 真抹除管道包裹（secure_delete=ON + 删行 + wal_checkpoint(TRUNCATE)）：
 * SQLite 普通删行在 WAL/空闲页留明文，隐私行删除必须走此管道。
 *
 * 范围判定全部在内存做（[NvPrivacyIndex.isUnderDir] 段边界口径，与播放闸同款判定）：
 * media_items 路径恒明文（隐私规则从不加密库行，锁定内容直接删行）；历史/收藏存在密文行
 * （path=HMAC 无法前缀匹配）→ allForSource 取回内存解密回明文路径再判范围。
 * dirPath 传 ""（整源规则口径）时 isUnderDir 对根恒真 = 清空该源全部范围。
 *
 * 本对象只提供删除原语：不做「明文历史行迁移密文」（T13 加锁动作的组合职责），
 * 不触发重扫（解锁补扫由 Task 12 解锁回调负责）。
 */
object NvPrivacyScan {

    private const val TAG = "NvPrivacyScan"

    // KeystoreCipher 进程级单例，与 NvPrivacyAccess/NvHistoryDirect/NvFavoriteDirect 同一实例（同款直取口径）
    private val CIPHER: NvPrivacyCipher = NvPrivacyCrypto.keystoreCipher()

    /**
     * 缩略图目录供给缝：生产由 NvPrivacyAccess.get 首次装配时接线 ThumbCache::thumbsDir
     * （purgeScope 的共享签名无 Context 参数，经此缝取 cacheDir/thumbs）；
     * 未接线即删文件（理论上不发生——调用方必先经 NvPrivacyAccess.get 拿 access）→ W 级留痕跳过。
     */
    @Volatile
    internal var thumbsDirOf: () -> File? = { null }

    /**
     * 通知封面缓存目录（cacheDir/notif_art）供给缝，接线同 [thumbsDirOf]（Task 11 审查跟进）：
     * 该目录文件名 = md5(原始 key 串)，命中范围的行内字段（path/sizeBytes/海报与缩略图路径）
     * 可反算出精确 key——「锁定内容解码帧不留盘」能精确就精确；反算不出的形态
     * （jf:<itemId> 键、行删后的遗留）走既有 7 天按龄清扫兜底（ThumbCache.sweepDirByAge）。
     */
    @Volatile
    internal var notifArtDirOf: () -> File? = { null }

    /** media_items 行按内存前缀判定后分批删/查的批大小（SQL 变量上限内，与扫描器 900 口径一致） */
    private const val SQL_CHUNK = 500

    suspend fun purgeScope(db: MediaDb, access: NvPrivacyAccess?, sourceId: Long, dirPath: String, includeHistory: Boolean) {
        // Task 17：jf 规则的 dirPath 原值是 "jf:<itemId>"，不能先过 NvPaths.normalize（会被当
        // 路径改写成 "/jf:..."）——先在原值上认前缀，范围判定走 withinRuleScope 的 resolver 链
        // 子树反查（闭合 T13 报告的 purge 盲区）；access 未初始化/索引未热（链解不出）回退前缀
        // 口径（= T13 旧行为：对 jf 键恒不命中，宁少删不误删），锁定侧整源罩照常兜底
        val jfKey = dirPath.trim().takeIf { it.startsWith(NvPrivacyIndex.JF_PREFIX) }
        if (jfKey != null && access == null) {
            Log.w(TAG, "NvPrivacy purgeScope jf rule without access instance: source=$sourceId (prefix fallback)")
        }
        val dir = NvPaths.normalize(dirPath)
        fun inScope(p: String): Boolean =
            when {
                jfKey != null -> access?.index?.withinRuleScope(jfKey, p) == true
                else -> NvPrivacyIndex.isUnderDir(p, dir)
            }
        NvSecureWipe.wiping(db) {
            val dao = db.mediaItemDao()
            // ① 范围内 media_items 行：路径恒明文，内存段边界判定（同播放闸口径）
            val doomedPaths = dao.allPaths(sourceId).filter { inScope(it) }
            // 实体先取（删行前）：旁路封面文件名含 sizeBytes，行删了就查不到了
            val doomedRows = doomedPaths.chunked(SQL_CHUNK).flatMap { chunk ->
                runCatching { dao.findByPaths(sourceId, chunk) }.getOrDefault(emptyList())
            }
            doomedPaths.chunked(SQL_CHUNK).forEach { chunk -> dao.deleteByPaths(sourceId, chunk) }
            // ② 缩略图文件：主缓存 key=md5("sourceId:path")（ThumbCache.hashKey 单一出处）；
            //    旁路封面（文件页/通知）按 (path,sizeBytes) 反查 browser 子目录。文件名是 MD5 无明文，
            //    删文件本身不涉敏感信息，失败不阻断（下次 ThumbCache.enforce 无主清扫兜底）
            val thumbsDir = thumbsDirOf()
            if (thumbsDir == null) {
                Log.w(TAG, "NvPrivacy purgeScope thumbs dir not wired: source=$sourceId count=${doomedPaths.size}")
            } else {
                doomedPaths.forEach { p -> runCatching { File(thumbsDir, ThumbCache.hashKey(sourceId, p) + ".jpg").delete() } }
                doomedRows.forEach { row ->
                    runCatching {
                        File(File(thumbsDir, "browser"), ThumbCache.browserThumbKey(sourceId, row.path, row.sizeBytes) + ".jpg").delete()
                    }
                }
            }
            // ②b 通知封面缓存（Task 11 审查跟进，重锁/加锁的 notif_art 清扫）：精确反算
            //    NvNotificationArtwork.cacheKey 的 4 类 key 逐个删（bypass/frame 按 (path,sizeBytes)、
            //    local 按海报与缩略图绝对路径）；反算不出的（jf: 键、frame:direct: 直连键、key 含
            //    旧 sizeBytes）靠既有 7 天按龄清扫兜底——解码帧不要求 0 秒出盘，但出量有界。
            val notifDir = notifArtDirOf()
            if (notifDir == null) {
                Log.w(TAG, "NvPrivacy purgeScope notif_art dir not wired: source=$sourceId count=${doomedRows.size}")
            } else {
                doomedRows.flatMap { notifArtKeysOf(sourceId, it) }.forEach { key ->
                    runCatching { File(notifDir, key).delete() }
                }
                runCatching { ThumbCache.sweepDirByAge(notifDir) }
            }
            // ③ 历史/收藏（includeHistory）：密文行解密回明文路径判范围后，经 Direct 收口双口径删
            //    （hash 键+明文键都清，顺带清会话内进度记忆）；解不开的密文行放弃（fail-closed 保留，锁继续罩着）
            if (includeHistory) {
                db.watchHistoryDao().allForSource(sourceId)
                    .mapNotNull { row -> plainPathOfHistory(row)?.takeIf { inScope(it) } }
                    .distinct()
                    .forEach { NvHistoryDirect.markUnwatched(db, access, sourceId, it) }
                db.favoriteDao().allForSource(sourceId)
                    .mapNotNull { row -> plainPathOfFavorite(row)?.takeIf { inScope(it) } }
                    .distinct()
                    .forEach { NvFavoriteDirect.remove(db, access, sourceId, it) }
            }
            Log.w(TAG, "NvPrivacy purgeScope done: source=$sourceId rows=${doomedPaths.size} history=$includeHistory")
        }
    }

    /** 密文行解密回明文路径；明文行原样；解不开（密钥丢失/损坏）→ null（范围内外无法判定，保留） */
    private fun plainPathOfHistory(e: WatchHistoryEntity): String? =
        if (NvHistoryCipherRows.isCiphered(e)) NvHistoryCipherRows.plainPath(CIPHER, e) else e.path

    private fun plainPathOfFavorite(e: FavoriteEntity): String? =
        if (NvFavoriteCipherRows.isCiphered(e)) NvFavoriteCipherRows.plainPath(CIPHER, e) else e.path

    /**
     * 行的 notif_art 候选文件名（key 单一出处 = [NvNotificationArtwork.cacheKey]，禁止自拼）：
     * bypass/frame 两级 key 的 size 项须与封面写入当时的文件大小一致（行内 sizeBytes 快照，
     * 文件后来变过大小则 miss → 按龄兜底接管）；local 两级按海报/缩略图绝对路径。
     */
    private fun notifArtKeysOf(sourceId: Long, row: MediaItemEntity): List<String> = buildList {
        add(NvNotificationArtwork.cacheKey("bypass:$sourceId:${row.path}:${row.sizeBytes}"))
        add(NvNotificationArtwork.cacheKey("frame:$sourceId:${row.path}:${row.sizeBytes}"))
        row.scrapePosterPath?.takeIf { it.isNotBlank() }?.let { add(NvNotificationArtwork.cacheKey("local:$it")) }
        row.thumbnailPath?.let { add(NvNotificationArtwork.cacheKey("local:$it")) }
    }
}
