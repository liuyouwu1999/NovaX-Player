package com.novax.app.data.privacy

import android.util.Log
import androidx.room.withTransaction
import com.novax.app.data.db.FavoriteDao
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.MediaDb

/**
 * 收藏旁路写点/删除的统一收口（隐私计划 Task 9 修复轮 1，规格 §3.5 favorites 同口径落盘）。
 *
 * 与历史三分口径（SKIP/CIPHER/PLAIN）不同，收藏只有 PLAIN/CIPHER 二态：
 * **无痕不拦收藏**（无痕只管"播放记录"，收藏是用户主动动作）；CIPHER ⇔ 命中锁规则——
 * 已解锁与否不参与判定（解锁态若写明文，重锁后即明文残留，违背零明文落盘）。
 *
 * 查重/删除一律 hash+plain 双口径（密文行在 hash 键上、明文/遗留行在原 path 键上），
 * 防同一内容明文+密文两行并存。密文行的展示/过滤归 Task 14。
 */
object NvFavoriteDirect {

    private const val TAG = "NvPrivacyFavorite"

    // KeystoreCipher 为进程级单例对象，与 NvPrivacyAccess 内部同一实例
    private val CIPHER: NvPrivacyCipher = NvPrivacyCrypto.keystoreCipher()

    /**
     * 收藏写口径判定（internal 抽 [NvPrivacyIndex] 直测——Access 构造私有，JVM 不可 fake）：
     * 命中规则里任一 lockEnabled 即密文；index 未就绪/无命中/无锁规则（无痕-only）→ 明文。
     */
    internal fun cipherRequired(index: NvPrivacyIndex?, sourceId: Long?, path: String): Boolean {
        index ?: return false
        val covering = index.ruleIdsCovering(sourceId, path)
        if (covering.isEmpty()) return false
        val lockIds = index.views().filter { it.lockEnabled }.mapTo(HashSet()) { it.id }
        return covering.any { it in lockIds }
    }

    /**
     * 收藏 toggle：双键查重（明文键 → hash 键）→ 查有删 / 查无插（锁命中插 hash 键密文行）。
     * [base] 恒传明文形态（path/title 明文）。返回操作后是否已收藏；fail-closed 加密失败不写。
     * 终审 C2：密文口径先经 LOCAL 反解（(null, 绝对路径) → 归属源相对路径），存储键仍按
     * [base] 原样（(null, abs) 行，与 T13 迁移同形态）。
     */
    suspend fun toggle(db: MediaDb, access: NvPrivacyAccess?, base: FavoriteEntity): Boolean = try {
        val (sid, p) = access?.resolveViewPath(base.sourceId, base.path) ?: (base.sourceId to base.path)
        db.withTransaction {
            toggleIn(db.favoriteDao(), CIPHER, cipherRequired(access?.index, sid, p), base)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "NvPrivacy favorite toggle fail: ${t.javaClass.simpleName}")
        false
    }

    /**
     * 仅加入（批量收藏用）：任一形态已存在 → false 跳过（防明文+密文两行并存）；插入成功 → true。
     * 密文口径 LOCAL 反解同 [toggle]。
     */
    suspend fun addIfAbsent(db: MediaDb, access: NvPrivacyAccess?, base: FavoriteEntity): Boolean = try {
        val (sid, p) = access?.resolveViewPath(base.sourceId, base.path) ?: (base.sourceId to base.path)
        db.withTransaction {
            addIfAbsentIn(db.favoriteDao(), CIPHER, cipherRequired(access?.index, sid, p), base)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "NvPrivacy favorite addIfAbsent fail: ${t.javaClass.simpleName}")
        false
    }

    /** 取消收藏/级联清理：hash+plain 双口径删（明文行与密文行都清） */
    suspend fun remove(db: MediaDb, access: NvPrivacyAccess?, sourceId: Long?, path: String) = try {
        db.withTransaction { removeIn(db.favoriteDao(), CIPHER, sourceId, path) }
    } catch (t: Throwable) {
        Log.w(TAG, "NvPrivacy favorite remove fail: ${t.javaClass.simpleName}")
    }

    // ── internal 核心（dao/cipher 注入，JVM 直测；MediaDb 不进单测） ──

    internal suspend fun toggleIn(
        dao: FavoriteDao,
        cipher: NvPrivacyCipher,
        ciphered: Boolean,
        base: FavoriteEntity,
    ): Boolean {
        val existing = dao.findByKey(base.sourceId, base.path)
            ?: dao.findByKey(base.sourceId, cipher.hmac(base.path))
        if (existing != null) {
            // 按命中行自身键删（明文行删明文键、密文行删 hash 键）；若明文+hash 异常并存，下次 toggle 再收余行
            dao.deleteByKey(existing.sourceId, existing.path)
            return false
        }
        return insertC(dao, cipher, ciphered, base)
    }

    internal suspend fun addIfAbsentIn(
        dao: FavoriteDao,
        cipher: NvPrivacyCipher,
        ciphered: Boolean,
        base: FavoriteEntity,
    ): Boolean {
        val existing = dao.findByKey(base.sourceId, base.path)
            ?: dao.findByKey(base.sourceId, cipher.hmac(base.path))
        if (existing != null) return false
        return insertC(dao, cipher, ciphered, base)
    }

    internal suspend fun removeIn(dao: FavoriteDao, cipher: NvPrivacyCipher, sourceId: Long?, path: String) {
        dao.deleteByKey(sourceId, path)                    // 明文行
        dao.deleteByKey(sourceId, cipher.hmac(path))       // 密文行（hash 键）
    }

    private suspend fun insertC(
        dao: FavoriteDao,
        cipher: NvPrivacyCipher,
        ciphered: Boolean,
        base: FavoriteEntity,
    ): Boolean {
        val row = if (ciphered) {
            NvFavoriteCipherRows.cipherRow(cipher, base) ?: run {
                Log.w(TAG, "NvPrivacy favorite cipher fail-closed: write skipped")
                return false
            }
        } else {
            base
        }
        dao.insert(row)
        return true
    }
}
