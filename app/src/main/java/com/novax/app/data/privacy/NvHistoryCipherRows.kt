package com.novax.app.data.privacy

import com.novax.app.data.db.WatchHistoryEntity

/**
 * 密文行的构造/判定/解密（隐私计划 Task 9；写端=NvHistoryWriter/NvHistoryDirect，读端=Task 14）。
 * 密文行约定（MediaDb v7 迁移注释同款）：pathCipher != null ⇒ path=HMAC hex（64 位小写，
 * 保 UNIQUE(sourceId, path)）、title=标题密文、pathCipher=路径密文；null ⇒ 旧明文行。
 * 加密任一步失败一律 fail-closed 返回 null，调用方放弃写入，绝不降级明文落库。
 */
object NvHistoryCipherRows {

    /**
     * 密文行 path 列值：HMAC hex（与 privacy_rules.keyHash 同款口径，只取 path——
     * 跨源隔离由 UNIQUE(sourceId, path) 的 sourceId 半边承担；sourceId 参数仅为
     * 共享签名逐字保留，供将来口径升级）。
     */
    fun hashOf(cipher: NvPrivacyCipher, sourceId: Long?, path: String): String = cipher.hmac(path)

    /** 明文形态行 → 密文行；path/title 任一加密失败 → null（fail-closed） */
    fun cipherRow(cipher: NvPrivacyCipher, base: WatchHistoryEntity): WatchHistoryEntity? {
        val pathCipher = cipher.encrypt(base.path) ?: return null
        val title = cipher.encrypt(base.title) ?: return null
        return base.copy(
            path = hashOf(cipher, base.sourceId, base.path),
            title = title,
            pathCipher = pathCipher,
        )
    }

    /** 密文行判定：pathCipher != null（写读两端唯一口径） */
    fun isCiphered(e: WatchHistoryEntity): Boolean = e.pathCipher != null

    /** 密文行 → 明文 path；明文行（pathCipher=null）或解不开 → null */
    fun plainPath(cipher: NvPrivacyCipher, e: WatchHistoryEntity): String? =
        e.pathCipher?.let { cipher.decrypt(it) }
}
