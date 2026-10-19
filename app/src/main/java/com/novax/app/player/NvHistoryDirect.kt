package com.novax.app.player

import android.util.Log
import androidx.room.withTransaction
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.WatchHistoryDao
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.privacy.HistoryWriteMode
import com.novax.app.data.privacy.NvHistoryCipherRows
import com.novax.app.data.privacy.NvPrivacyAccess
import com.novax.app.data.privacy.NvPrivacyCipher
import com.novax.app.data.privacy.NvPrivacyCrypto
import java.util.concurrent.ConcurrentHashMap

/**
 * 历史旁路写点与真续播读取的统一收口（隐私计划 Task 9）：
 * 文件页/库页/首页对 watch_history 的旁路写（不经 NvHistoryWriter 心跳路径的那些）一律改经
 * [markWatched]/[markUnwatched]——无痕（SKIP）不落库、锁定内容（CIPHER）密文落盘、其余明文；
 * 真续播读取一律经 [findForPlayback] 三级链（明文行 → hash 行解密 → 会话内进度）。
 *
 * 会话内进度记忆（规格 §3.5）：无痕/密文内容没有跨会话历史，但同一会话内续播不受影响——
 * [rememberProgress]/[recallProgress] 是进程级 ConcurrentHashMap，零落盘，进程死即失。
 * 消费方注意：NvHistoryWriter 每次成功 record 都会顺带 [rememberProgress]。
 */
object NvHistoryDirect {

    private const val TAG = "NvPrivacyHistory"

    // KeystoreCipher 是进程级单例对象，与 NvPrivacyAccess 内部持有的是同一实例；
    // 直取而非从 access 暴露——不改 Task 4 的既有接口面
    private val CIPHER: NvPrivacyCipher = NvPrivacyCrypto.keystoreCipher()

    // ── 会话内进度（进程内存，零落盘） ──

    // position to duration——duration 供「已看完」判定（replay 归零口径）；旧调用只记位置时为 0
    private val memory = ConcurrentHashMap<String, Pair<Long, Long>>()

    internal fun memKey(sourceId: Long?, path: String): String = "${sourceId ?: "_"}|$path"

    fun rememberProgress(sourceId: Long?, path: String, positionMs: Long) {
        rememberProgress(sourceId, path, positionMs, 0L)
    }

    fun rememberProgress(sourceId: Long?, path: String, positionMs: Long, durationMs: Long) {
        memory[memKey(sourceId, path)] = positionMs to durationMs
    }

    /** 原始位置（未套 replay 归零——归零只发生在 [findForPlayback] 播放链出口） */
    fun recallProgress(sourceId: Long?, path: String): Long? =
        memory[memKey(sourceId, path)]?.first

    /**
     * 「已看完」判定：duration 已知且 position ≥ 95%（与继续观看 <95% 未看完同口径；
     * markWatchedToEnd 落 positionMs=durationMs 同命中）。duration 未知（0）不触发。
     */
    internal fun watchedToEnd(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= durationMs * 0.95

    /**
     * 续播行出口归零（2026-10-25 replay 口径）：已看完的点开/切集从头播——
     * 起点粘在片尾会即刻 STATE_ENDED 自动跳下一集。只改返回副本，**落库历史行不改写**
     * （看完状态不被破坏、watched 徽标不消失）。positionMs<=0 本就从 0 播，不特殊处理。
     */
    private fun replayRow(e: WatchHistoryEntity): WatchHistoryEntity =
        if (watchedToEnd(e.positionMs, e.durationMs)) e.copy(positionMs = 0L) else e

    // ── 直写点（公开 API，db 入参加事务防 UNIQUE 并发插重） ──

    /**
     * 「标记已看」形态直写（文件页长按已看 / 库页切换 / 移出继续观看）。
     * [e] 传明文形态 base（path/title 明文、watchedAt 由调用方定并原样落库）；传既有密文行
     * （继续观看卡片流出）会先解密回明文再分类改写。SKIP 不写；CIPHER 密文行 upsert；PLAIN 明文。
     */
    suspend fun markWatched(db: MediaDb, access: NvPrivacyAccess?, e: WatchHistoryEntity) {
        runCatching {
            db.withTransaction { markWatchedIn(db.watchHistoryDao(), CIPHER, modeOf(access, e), e) }
        }.onFailure { Log.w(TAG, "NvPrivacy markWatched fail: ${it.javaClass.simpleName}") }
    }

    /**
     * 「取消已看 / 移除历史行」：hash+plain 双口径删（密文行在 hash 键上、明文行在原 path 键上，
     * 两边都查都删）。删除本身无明文泄漏面，不受 SKIP/CIPHER 分类限制；顺带清会话内进度记忆
     * （用户明确要求"取消已看"= 从头看）。
     */
    suspend fun markUnwatched(db: MediaDb, access: NvPrivacyAccess?, sourceId: Long?, path: String) {
        runCatching {
            db.withTransaction { markUnwatchedIn(db.watchHistoryDao(), CIPHER, sourceId, path) }
        }.onFailure { Log.w(TAG, "NvPrivacy markUnwatched fail: ${it.javaClass.simpleName}") }
    }

    /**
     * 真续播读取三级链：明文行 → hash 行解密 → 会话内进度兜底。返回行恒为明文形态
     * （path/title 已还原、pathCipher 已清）；全部未中 → null。
     * 密文行解不开（密钥丢失/损坏）按未中处理，绝不外露密文形态行（fail-closed）。
     * 已看完（positionMs ≥ durationMs·95%）返回行 positionMs=0——点开/切集从头播，
     * 落库行原值不动（只归零出口副本）。
     */
    suspend fun findForPlayback(
        db: MediaDb,
        access: NvPrivacyAccess?,
        sourceId: Long?,
        path: String,
    ): WatchHistoryEntity? = runCatching {
        findForPlaybackIn(db.watchHistoryDao(), CIPHER, sourceId, path)
    }.onFailure { Log.w(TAG, "NvPrivacy findForPlayback fail: ${it.javaClass.simpleName}") }
        .getOrNull()

    // ── internal 核心（dao/cipher/mode 注入，JVM 直测；MediaDb 不进单测） ──

    /** access 未就绪（理论上不发生，构造点都传 get(context)）按旧语义 PLAIN */
    internal fun modeOf(access: NvPrivacyAccess?, base: WatchHistoryEntity): HistoryWriteMode =
        access?.historyMode(base.sourceId, base.path) ?: HistoryWriteMode.PLAIN

    /** 密文行 → 明文形态 base；明文行原样返回；path/title 任一解不开 → null（放弃本次改写） */
    internal fun plainBaseOf(cipher: NvPrivacyCipher, e: WatchHistoryEntity): WatchHistoryEntity? {
        if (!NvHistoryCipherRows.isCiphered(e)) return e
        val p = NvHistoryCipherRows.plainPath(cipher, e) ?: return null
        val t = cipher.decrypt(e.title) ?: return null
        return e.copy(path = p, title = t, pathCipher = null)
    }

    internal suspend fun markWatchedIn(
        dao: WatchHistoryDao,
        cipher: NvPrivacyCipher,
        mode: HistoryWriteMode,
        base: WatchHistoryEntity,
    ) {
        val plain = plainBaseOf(cipher, base) ?: return
        when (mode) {
            HistoryWriteMode.SKIP -> Unit                      // 无痕：直写点同 writer 口径不落库
            HistoryWriteMode.CIPHER -> {
                val row = NvHistoryCipherRows.cipherRow(cipher, plain)
                if (row == null) {
                    Log.w(TAG, "NvPrivacy history cipher fail-closed: direct write skipped")
                    return
                }
                upsertByKey(dao, row)
            }
            HistoryWriteMode.PLAIN -> upsertByKey(dao, plain)
        }
    }

    internal suspend fun markUnwatchedIn(
        dao: WatchHistoryDao,
        cipher: NvPrivacyCipher,
        sourceId: Long?,
        path: String,
    ) {
        memory.remove(memKey(sourceId, path))
        dao.findByKey(sourceId, path)?.let { dao.deleteById(it.id) }               // 明文行
        dao.findByKey(sourceId, cipher.hmac(path))?.let { dao.deleteById(it.id) }  // 密文行（hash 键）
    }

    internal suspend fun findForPlaybackIn(
        dao: WatchHistoryDao,
        cipher: NvPrivacyCipher,
        sourceId: Long?,
        path: String,
    ): WatchHistoryEntity? {
        dao.getForResume(sourceId, path)?.let { return replayRow(it) }               // ① 明文行
        dao.findByKey(sourceId, cipher.hmac(path))                                 // ② hash 行解密
            ?.takeIf { NvHistoryCipherRows.isCiphered(it) }
            ?.let { row ->
                val p = NvHistoryCipherRows.plainPath(cipher, row)
                val t = cipher.decrypt(row.title)
                if (p != null && t != null) return replayRow(row.copy(path = p, title = t, pathCipher = null))
                Log.w(TAG, "NvPrivacy history row decrypt fail: fall back to memory")
            }
        // ③ 会话内进度兜底（无痕/密文）：合成零落盘临时行，调用方只消费 positionMs；
        // 已看完同样归零（无痕内容播完同会话内重开不粘片尾）
        return memory[memKey(sourceId, path)]?.let { (pos, dur) ->
            WatchHistoryEntity(
                sourceId = sourceId, sourceType = null, path = path, title = "",
                kind = "MOVIE",                       // 合法枚举名，防调用侧 valueOf 语义炸裂
                positionMs = if (watchedToEnd(pos, dur)) 0L else pos,
                durationMs = 0L, watchedAt = 0L,
            )
        }
    }

    /** writer 同款 upsert：事务内 find→REPLACE 防 UNIQUE 并发插重；row.path 已是最终键（明文或 hash） */
    private suspend fun upsertByKey(dao: WatchHistoryDao, row: WatchHistoryEntity) {
        val old = dao.findByKey(row.sourceId, row.path)
        dao.insert(
            if (old != null) {
                row.copy(
                    id = old.id,
                    durationMs = row.durationMs.takeIf { it > 0L } ?: old.durationMs,
                )
            } else {
                row
            },
        )
    }
}
