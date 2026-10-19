package com.novax.app.data.privacy

import android.util.Log
import com.novax.app.data.db.MediaDb
import com.novax.app.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 重锁编排中枢（隐私计划 Task 11）：App 退前台（NvApp 前台 Activity 计数归零）与退出
 * PiP/分屏小窗（PlayerActivity）都收敛到 [relock]。硬顺序（spec §5.3/§5.4）：
 * generation+1 使在途补扫失效 → session.relockAll() 得待清理集 → **pendingWipe 先落盘**
 * （进程被杀冷启动可补跑）→ 逐规则 [NvPrivacyScan.purgeScope] → 只清成功的标记 →
 * 暂停本地播放 → 投屏停止钩子（Task 15 注入）→ 发重锁事件。
 *
 * 无解锁会话（待清理集为空）时整个动作短路：绝不误暂停正常后台播放/误停投屏——
 * 重锁是「把解锁会话恢复成锁定态」，与普通退后台无关。
 *
 * 挂点纪律：**不用 MainActivity ON_STOP**（打开播放器会误触发）；**不引 lifecycle-process**。
 */
object NvPrivacyRelockOrchestrator {

    private const val TAG = "NvPrivacyRelock"

    /**
     * 重锁时是否连带清历史/收藏行：**否**。解锁会话期写入的行本就是密文行
     * （historyMode 的 covers 判定与解锁态无关），锁规则范围落盘无明文；清掉反而丢用户
     * 「锁=暂时看不见」的历史（Task 14 锁定期间过滤、解锁后解密可见）。
     * includeHistory=true 仅用于无痕清除/加锁对账，不属于重锁语义。
     */
    private const val RELOCK_INCLUDE_HISTORY = false

    /**
     * Task 15 注入点：重锁时停止投屏会话并清 DIDL；null = 未接线（安全空操作）。
     * 异常吞掉不阻断收尾（W 级留痕）。
     */
    @Volatile
    var castStopHook: (suspend () -> Unit)? = null

    /**
     * 生产依赖装配（注入缝全是 internal：单测全换 fake，Room/PlaybackService 不进 JVM）。
     * [ruleScopeOf] 规则 id → (sourceId, dirPath)，整源/失效规则 dirPath=""（purgeScope 对根恒真）；
     * null = 规则已消失（范围随之消亡，跳过清理）。
     */
    internal class Deps(
        val prefs: NvPinStore,
        val ruleScopeOf: suspend (ruleId: Long) -> Pair<Long, String>?,
        val purgeOf: suspend (ruleId: Long, sourceId: Long, dirPath: String, includeHistory: Boolean) -> Unit,
        val pausePlayers: () -> Unit,
        val scope: CoroutineScope,
    )

    private val mutex = Mutex()   // relock 与冷启动补跑互斥（pendingWipe 写清纪律不被交错打乱）

    @Volatile
    private var deps: Deps? = null

    /**
     * 进程内装配（NvApp.onCreate，先于 ActivityLifecycleCallbacks 注册）：
     * 组装生产缝并冷启动补跑 pendingWipeIds（上次重锁清理中途被杀的欠账）。
     */
    fun init(db: MediaDb, prefs: NvPinStore, access: NvPrivacyAccess?, scope: CoroutineScope) {
        val a = access
        val cipher = NvPrivacyCrypto.keystoreCipher()
        val d = Deps(
            prefs = prefs,
            ruleScopeOf = { ruleId -> resolveRuleScope(a, db, cipher, ruleId) },
            purgeOf = { _, sourceId, dirPath, includeHistory ->
                withContext(Dispatchers.IO) {
                    NvPrivacyScan.purgeScope(db, a, sourceId, dirPath, includeHistory)
                }
            },
            pausePlayers = { PlaybackService.pauseAll() },
            scope = scope,
        )
        deps = d
        d.scope.launch { catchUpPendingWipes(d) }
    }

    /** 重锁入口（主线程生命周期回调直调）：未装配静默；动作异步，不阻塞调用方 */
    fun relock() {
        val d = deps ?: return
        d.scope.launch { performRelock(d) }
    }

    /** 硬顺序本体（internal 供单测直调，注入缝全 fake 后纯 JVM 断言） */
    internal suspend fun performRelock(d: Deps) = mutex.withLock {
        val wiped = NvPrivacySession.relockAll()   // 世代号在此 +1，在途补扫即刻失效
        if (wiped.isEmpty()) return@withLock       // 无解锁会话 = 无可重锁，全部短路
        runCatching { d.prefs.setPendingWipeIds(wiped) }
            .onFailure { Log.w(TAG, "NvPrivacy relock persist pendingWipe failed", it) }
        val failed = mutableListOf<Long>()
        wiped.forEach { id ->
            val scopePair = runCatching { d.ruleScopeOf(id) }.getOrNull()
            if (scopePair == null) {
                Log.w(TAG, "NvPrivacy relock rule gone, skip purge rule=$id")
            } else {
                runCatching { d.purgeOf(id, scopePair.first, scopePair.second, RELOCK_INCLUDE_HISTORY) }
                    .onFailure {
                        failed += id
                        Log.w(TAG, "NvPrivacy relock purge failed rule=$id", it)
                    }
            }
        }
        // 只清成功的：失败的留在 pendingWipe，进程被杀/本次失败都由冷启动补跑兜底
        runCatching { d.prefs.setPendingWipeIds(failed) }
            .onFailure { Log.w(TAG, "NvPrivacy relock clear pendingWipe failed", it) }
        runCatching { d.pausePlayers() }
            .onFailure { Log.w(TAG, "NvPrivacy relock pause failed", it) }
        castStopHook?.let { hook ->
            runCatching { hook() }.onFailure { Log.w(TAG, "NvPrivacy relock cast stop failed", it) }
        }
        NvPrivacySession.relockEvents.emit(NvPrivacySession.RelockEvent(wiped))
        Log.w(TAG, "NvPrivacy relocked: wiped=${wiped.size} failed=${failed.size}")
    }

    /** 冷启动补跑（init 时异步）：只清理与清标记，不暂停不投屏不发事件（起播前无会话可收） */
    internal suspend fun catchUpPendingWipes(d: Deps) = mutex.withLock {
        val pending = runCatching { d.prefs.pendingWipeIds() }.getOrDefault(emptyList())
        if (pending.isEmpty()) return@withLock
        val failed = mutableListOf<Long>()
        pending.forEach { id ->
            val scopePair = runCatching { d.ruleScopeOf(id) }.getOrNull()
            if (scopePair == null) {
                Log.w(TAG, "NvPrivacy catch-up rule gone, skip rule=$id")
            } else {
                runCatching { d.purgeOf(id, scopePair.first, scopePair.second, RELOCK_INCLUDE_HISTORY) }
                    .onFailure {
                        failed += id
                        Log.w(TAG, "NvPrivacy catch-up purge failed rule=$id", it)
                    }
            }
        }
        runCatching { d.prefs.setPendingWipeIds(failed) }
            .onFailure { Log.w(TAG, "NvPrivacy catch-up clear pendingWipe failed", it) }
        Log.w(TAG, "NvPrivacy pending wipe catch-up done: pending=${pending.size} failed=${failed.size}")
    }

    /**
     * 规则范围解析：先内存 index 视图（唯一正源，含解密/失效规则的整源升级）；
     * 查无则直查 DAO + 解密镜像兜底——冷启动补跑可能先于 observeAll 首帧到站，
     * 此时 index 还是空的，若只认 index 会把欠账误判「规则已消失」永久丢清理。
     * 镜像口径 = index.replaceAll：解不开/空目录/非 ACTIVE → ""（整源，fail-closed）。
     */
    private suspend fun resolveRuleScope(
        access: NvPrivacyAccess?,
        db: MediaDb,
        cipher: NvPrivacyCipher,
        ruleId: Long,
    ): Pair<Long, String>? {
        access?.index?.views()?.firstOrNull { it.id == ruleId }?.let { v ->
            return v.sourceId to if (v.wholeSource) "" else v.dirPath
        }
        val rule = runCatching { withContext(Dispatchers.IO) { db.privacyRuleDao().all() } }
            .getOrNull()?.firstOrNull { it.id == ruleId } ?: return null
        val dir = if (rule.state != NvPrivacyIndex.STATE_ACTIVE) {
            ""
        } else {
            cipher.decrypt(rule.dirCipher)?.takeIf { it.isNotBlank() } ?: ""
        }
        return rule.sourceId to dir
    }
}
