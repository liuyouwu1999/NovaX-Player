package com.novax.app.data.privacy

import com.novax.app.data.db.FavoriteEntity

/**
 * 收藏密文行的构造/判定/解密（隐私计划 Task 9 修复轮 1；与 [NvHistoryCipherRows] 同约定：
 * pathCipher != null ⇒ path=HMAC hex（64 位小写，保 UNIQUE(sourceId, path)）、title=标题密文、
 * pathCipher=路径密文；null ⇒ 旧明文行）。加密任一步失败一律 fail-closed 返回 null，
 * 调用方放弃写入，绝不降级明文落库。
 */
object NvFavoriteCipherRows {

    /** 密文行 path 列值：HMAC hex（只取 path——跨源隔离由 UNIQUE 的 sourceId 半边承担） */
    fun hashOf(cipher: NvPrivacyCipher, sourceId: Long?, path: String): String = cipher.hmac(path)

    /** 明文形态行 → 密文行；path/title 任一加密失败 → null（fail-closed） */
    fun cipherRow(cipher: NvPrivacyCipher, base: FavoriteEntity): FavoriteEntity? {
        val pathCipher = cipher.encrypt(base.path) ?: return null
        val title = cipher.encrypt(base.title) ?: return null
        return base.copy(
            path = hashOf(cipher, base.sourceId, base.path),
            title = title,
            pathCipher = pathCipher,
        )
    }

    /** 密文行判定：pathCipher != null（写读两端唯一口径） */
    fun isCiphered(e: FavoriteEntity): Boolean = e.pathCipher != null

    /** 密文行 → 明文 path；明文行（pathCipher=null）或解不开 → null */
    fun plainPath(cipher: NvPrivacyCipher, e: FavoriteEntity): String? =
        e.pathCipher?.let { cipher.decrypt(it) }
}
