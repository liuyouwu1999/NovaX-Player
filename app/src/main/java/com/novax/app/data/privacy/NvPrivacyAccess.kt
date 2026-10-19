package com.novax.app.data.privacy

import android.content.Context
import android.util.Log
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** 历史/收藏落盘口径（NvHistoryWriter 与各直写点消费）：无痕不写、锁命中加密写、其余明文 */
enum class HistoryWriteMode { SKIP, PLAIN, CIPHER }

/**
 * 隐私判定进程级门面（隐私功能计划 Task 4）：Task 7~17 一律经 [get] 取用，不直连 [NvPrivacyIndex]。
 * 构造即挂 privacy_rules + sources 双 observeAll 常驻监（scope 参数）→ index.replaceAll /
 * LOCAL 源根快照；监听不进单测（Task 18 冒烟兜底）。
 * 注意：监听建立失败会随协程异常上抛（进程崩）——规则加载不了时静默放行 = fail-open，绝不接受。
 */
class NvPrivacyAccess private constructor(
    val db: MediaDb,
    private val cipher: NvPrivacyCipher,
    scope: CoroutineScope,
    private val unlockedIds: () -> Set<Long> = { emptySet() },  // Task 11 回填 NvPrivacySession::unlockedRuleIds
) {
    val index = NvPrivacyIndex(cipher)

    private val _rulesSnapshot = kotlinx.coroutines.flow.MutableStateFlow<List<com.novax.app.data.db.PrivacyRuleEntity>>(emptyList())

    /**
     * 规则实体快照（UI 渲染期读锁的重组信号源）。2026-10-26 忘码重置实测：NvSecureWipe
     * 管道（execSQL + PRAGMA secure_delete/wal_checkpoint）删行后 Room InvalidationTracker
     * 不广播（三星真机 logcat 实证——DAO 正常增删有广播，wipe 路径没有），observeAll 消费方
     * 全部饿死到进程重启。故快照统一收拢在此：正常路径随 observeAll 刷新 + wipe 后由
     * [resyncRulesNow] 主动重查兜底，UI 一律读本快照，不再各自直连 observeAll。
     */
    val rulesSnapshot: kotlinx.coroutines.flow.StateFlow<List<com.novax.app.data.db.PrivacyRuleEntity>> = _rulesSnapshot

    /** 已锁/无痕目录名提示快照（解密后 dirPath；进程内存、不落盘）：崩溃落盘目录名段兜底用 */
    @Volatile
    private var dirHints: List<String> = emptyList()

    /**
     * LOCAL 源根快照（终审 C2 反解归属用）：sourceId → 去尾分隔符根路径（根 "/" 去尾后为
     * 空串 = 罩住一切绝对路径）。进程内存快照，判定热路径零 Room 查询；首帧到站前
     * [viewResolutionReady] 为 false（PlayerActivity 冷启动 settle 循环据此前等，防漏判）。
     */
    @Volatile
    private var localRoots: List<Pair<Long, String>> = emptyList()

    @Volatile
    private var localRootsSettled: Boolean = false

    init {
        scope.launch {
            db.privacyRuleDao().observeAll().collect {
                android.util.Log.w("NvPrivacyUi", "NvPrivacyAccess rules flow: ${it.size} rules")
                applyRules(it)
            }
        }
        // 终审 C2：LOCAL 源的播放地址是裸绝对路径（sourceId=null），历史/收藏/通知也按
        // (null, 绝对路径) 落库——闸/写分类/读过滤/通知脱敏四链统一先反解归属源再判定
        scope.launch {
            db.sourceDao().observeAll().collect { sources ->
                localRoots = sources
                    .filter { it.type == SourceType.LOCAL && it.shareOrPath.isNotBlank() }
                    .map { it.id to it.shareOrPath.trim().trimEnd('/', '\\') }
                localRootsSettled = true
            }
        }
    }

    /**
     * 播放视图键 → 规则判定键（终审 C2 统一反解）：sourceId != null 原样返回（novax:// 目标）；
     * sourceId == null 且 path 以某 LOCAL 源根 + 分隔符开头 → (该源 id, 源内相对路径)；
     * 无命中 → null = 真外部文件（content:// 等），按设计豁免。存储键口径不变
     * （(null, 绝对路径) 行与 hash 均按原样，仅判定时反解——终审裁决，避免二次迁移）。
     */
    fun resolveViewPath(sourceId: Long?, path: String): Pair<Long?, String>? =
        resolveViewPathIn(localRoots, sourceId, path)

    /** LOCAL 源根快照是否已到站（首帧 Room 发射即 true，含空列表）；冷启动闸 settle 循环消费 */
    fun viewResolutionReady(): Boolean = localRootsSettled

    /** 播放/扫描/缩略图闸：命中锁规则且未解锁 → false（LOCAL 裸路径先反解；外部豁免恒 true） */
    fun startAllowed(sourceId: Long?, path: String): Boolean = !isViewLocked(sourceId, path)

    /** 当前实际拦截播放的规则 id（解锁面板要解的清单；无痕-only 不拦播放，不在其中） */
    fun blockingRuleIds(sourceId: Long?, path: String): List<Long> {
        val (sid, p) = resolveViewPath(sourceId, path) ?: return emptyList()
        return index.blockingIds(sid, p, unlockedIds())
    }

    /** T14 读端过滤判定（isLocked 的 LOCAL 反解版）：反解无命中 = 外部文件不锁定 */
    fun isViewLocked(sourceId: Long?, path: String): Boolean {
        val (sid, p) = resolveViewPath(sourceId, path) ?: return false
        return index.isLocked(sid, p, unlockedIds())
    }

    /** T15 通知脱敏判定（covers 的 LOCAL 反解版）：反解无命中 = 外部文件不命中 */
    fun coversView(sourceId: Long?, path: String): Boolean {
        val (sid, p) = resolveViewPath(sourceId, path) ?: return false
        return index.covers(sid, p)
    }

    fun historyMode(sourceId: Long?, path: String): HistoryWriteMode {
        val (sid, p) = resolveViewPath(sourceId, path) ?: return HistoryWriteMode.PLAIN
        return historyModeOf(index.isIncognito(sid, p), index.covers(sid, p))
    }

    /** 日志/异常/元数据脱敏第一层：命中任一规则（锁或无痕）→ 占位符，否则原文（通用掩码由 NvPrivacyRedactor 做） */
    fun redact(sourceId: Long?, path: String): String {
        val (sid, p) = resolveViewPath(sourceId, path) ?: return path
        return redactOf(index.covers(sid, p), p)
    }

    /** 目录名提示快照（进程内存、不落盘）；直接消费方应走 [Companion.hintsIfReady] 防误触发初始化 */
    fun lockedDirHintsSnapshot(): List<String> = dirHints

    /** 规则集统一应用（observeAll 与 resyncRulesNow 共用）：index + dirHints + 快照三同步 */
    private fun applyRules(rules: List<com.novax.app.data.db.PrivacyRuleEntity>) {
        index.replaceAll(rules)
        // 含无痕-only 规则的目录——同属不该进崩溃文件的私密目录名（fail toward more masking）
        dirHints = index.views().map { it.dirPath }.filter { it.isNotBlank() }
        _rulesSnapshot.value = rules
    }

    /**
     * 主动重查规则表并刷新内存快照。忘码重置（NvSecureWipe 管道）后 Room 失效广播不可靠
     * （见 [rulesSnapshot] 注释），重置流程必须在 wipe 后调它兜底；observeAll 正常路径不受影响。
     */
    suspend fun resyncRulesNow() {
        // 失败必须留痕（审核 P2-1）：resync 失败 = 快照陈旧、原 bug 无痕复现
        runCatching { applyRules(db.privacyRuleDao().all()) }
            .onFailure { Log.w("NvPrivacyUi", "resyncRulesNow failed", it) }
    }

    companion object {
        private const val TAG = "NvPrivacyAccess"

        /**
         * 忘码重置的真抹除管道（隐私计划 Task 8，接 NvPinManager.resetAllWipe）：
         * SQLite 删行≠抹除（WAL/空闲页留明文），全清必须经 [NvSecureWipe.wiping] 包住。
         * DataStore 侧键（pin/fails/pendingWipe 等）由 NvPinStore 逐键置空负责，不归本管道。
         */
        val resetAllWipe: suspend (MediaDb) -> Unit = { db ->
            NvSecureWipe.wiping(db) { it.execSQL("DELETE FROM privacy_rules") }
        }

        /**
         * NvPinManager 统一构造点（Task 12/16 消费）：resetAllWipe 已接真抹除管道，
         * 勿绕过此工厂自建 NvPinManager——绕过即忘码重置退化为普通删行，明文留 WAL。
         */
        fun newPinManager(context: Context, store: NvPinStore): NvPinManager = NvPinManager(
            store = store,
            cipher = NvPrivacyCrypto.keystoreCipher(),
            clock = NvMonotonicClock.androidSystem(context),
            resetAllWipe = resetAllWipe,
        )

        /** historyMode 决策矩阵（internal 纯函数供单测；无痕优先于 covers） */
        internal fun historyModeOf(incognitoHit: Boolean, covered: Boolean): HistoryWriteMode = when {
            incognitoHit -> HistoryWriteMode.SKIP
            covered -> HistoryWriteMode.CIPHER
            else -> HistoryWriteMode.PLAIN
        }

        /**
         * 视图键反解内核（internal 纯函数供单测；[resolveViewPath] 委托至此）：
         * - sourceId != null 原样（novax:// 目标不经反解）；
         * - 非 "/" 开头（content://、http(s)://）不可能属于 LOCAL 源 → null 豁免；
         * - 空根（用户配的源根 = "/"，去尾分隔符后为空串）罩住一切绝对路径；
         * - 常规：path 以 "root/" 开头才命中（恰好等于根自身不算——根是目录不是文件）；
         *   多源命中取最长前缀；相对路径 = path 去掉根前缀（保留前导 '/'，与规则 dirPath
         *   及 NvPlaybackLauncher.localPath 的拼接口径互逆）。
         */
        internal fun resolveViewPathIn(
            localRoots: List<Pair<Long, String>>,
            sourceId: Long?,
            path: String,
        ): Pair<Long?, String>? {
            if (sourceId != null) return sourceId to path
            if (!path.startsWith("/")) return null
            var bestRootLen = -1
            var bestId = 0L
            for ((id, root) in localRoots) {
                val hit = root.isEmpty() || path.startsWith("$root/")
                if (hit && root.length > bestRootLen) {
                    bestRootLen = root.length
                    bestId = id
                }
            }
            if (bestRootLen < 0) return null
            return bestId to path.substring(bestRootLen)
        }

        /** internal 纯函数供单测；占位符与规格 §5.7 日志脱敏同词 */
        internal fun redactOf(covered: Boolean, path: String): String = if (covered) "[locked]" else path

        @Volatile
        private var instance: NvPrivacyAccess? = null

        /** 进程级单例（仿 MediaDb.get）；首次 get 即挂 observeAll 常驻监 */
        fun get(context: Context): NvPrivacyAccess =
            instance ?: synchronized(this) {
                instance ?: NvPrivacyAccess(
                    db = MediaDb.get(context),
                    cipher = NvPrivacyCrypto.keystoreCipher(),
                    scope = MainScope(),
                    // Task 11 解锁接线（缺此步解锁永远无效）：闸放行按会话解锁集
                    unlockedIds = NvPrivacySession::unlockedRuleIds,
                ).also {
                    instance = it
                    // Task 10：purgeScope 共享签名无 Context，缩略图目录经此缝一次性接线
                    //（凡调 purgeScope 必先经本工厂拿 access，接线必然先于删除发生）
                    NvPrivacyScan.thumbsDirOf = { com.novax.app.data.thumb.ThumbCache.thumbsDir(context.applicationContext) }
                    // Task 11：通知封面缓存 notif_art 同口径（重锁清扫精确删 + 按龄兜底的目标目录）
                    NvPrivacyScan.notifArtDirOf = { java.io.File(context.applicationContext.cacheDir, "notif_art") }
                    Log.w(TAG, "NvPrivacy access singleton ready (rules observer attached)")
                }
            }

        /**
         * 崩溃落盘取目录名提示：崩溃时机 Access 可能尚未初始化——只读既有单例的内存快照，
         * **绝不经 [get] 触发 Room 开库**（崩溃处理器里开库 = 二次故障面）；
         * 未初始化返回 emptyList()，退化为纯通用掩码（文件名仍打码）。
         */
        fun hintsIfReady(): List<String> = instance?.lockedDirHintsSnapshot() ?: emptyList()
    }
}
