package com.novax.app.data.privacy

import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.WatchHistoryEntity

/**
 * 查询层过滤 + 密文行解密展示（隐私计划 Task 14）——防露馅的最后一层网。
 *
 * 读端统一口径（规格 §3.5「读取端统一经解密层」）：
 * 1. [decryptDisplay]：密文行（pathCipher != null ⇒ path=HMAC、title=密文）还原为明文形态展示；
 *    解不开（密钥丢失/损坏）→ null，调用方按锁定行滤掉——fail-closed，绝不外露密文形态乱码。
 * 2. [filter]：命中锁规则且未解锁的行滤掉；解锁后保留（锁=暂时看不见，不是删除）。
 *    sourceId==null 恒保留（T4 裁定豁免口径：content:// 等无源路径不命中任何规则）。
 *
 * 键约定：[filter] 的 key 返回行的 (sourceId, 明文 path)——密文行须先经 [decryptDisplay]
 * 再传入（hash 无法参与前缀判定）。media_items 明文行直接给 (sourceId, path)。
 */
object NvPrivacyListFilter {

    // ── 解密展示（历史/收藏两实体同约定，分别走 T9 的两套 CipherRows 口径） ──

    /**
     * 历史行 → 明文形态展示行：明文行（pathCipher=null）原样返回；密文行还原 path/title
     * 并清 pathCipher；path/title 任一解不开 → null（fail-closed）。
     * 与 NvHistoryDirect.plainBaseOf 同一约定（写读两端共用 §3.5 密文行格式）。
     */
    fun decryptDisplay(cipher: NvPrivacyCipher, e: WatchHistoryEntity): WatchHistoryEntity? {
        if (!NvHistoryCipherRows.isCiphered(e)) return e
        val p = NvHistoryCipherRows.plainPath(cipher, e) ?: return null
        val t = cipher.decrypt(e.title) ?: return null
        return e.copy(path = p, title = t, pathCipher = null)
    }

    /** 收藏行 → 明文形态展示行（与历史行同约定，[NvFavoriteCipherRows] 口径） */
    fun decryptDisplay(cipher: NvPrivacyCipher, e: FavoriteEntity): FavoriteEntity? {
        if (!NvFavoriteCipherRows.isCiphered(e)) return e
        val p = NvFavoriteCipherRows.plainPath(cipher, e) ?: return null
        val t = cipher.decrypt(e.title) ?: return null
        return e.copy(path = p, title = t, pathCipher = null)
    }

    // ── 锁态过滤 ──

    /**
     * 查询层过滤（共享总览签名）：锁定行滤掉，其余原序保留。
     * 判定 = LOCAL 反解后 `access.isViewLocked`（终审 C2：历史/收藏的 (null, 绝对路径) 行
     * 先按 LOCAL 源根反解归属源再判锁；反解无命中 = 外部文件恒保留）。
     * Access 构造私有，单测走 [filterIn]。
     */
    fun <T> filter(rows: List<T>, access: NvPrivacyAccess, key: (T) -> Pair<Long?, String>): List<T> =
        filterIn(rows, { sid, p -> access.isViewLocked(sid, p) }, key)

    /** internal 核心（isLocked 注入，JVM 直测）；原序保留（调用方多为「最近优先」流），每行只求一次键 */
    internal fun <T> filterIn(
        rows: List<T>,
        isLocked: (sourceId: Long?, plainPath: String) -> Boolean,
        key: (T) -> Pair<Long?, String>,
    ): List<T> = rows.filter { row -> key(row).let { (sid, p) -> !isLocked(sid, p) } }
}
