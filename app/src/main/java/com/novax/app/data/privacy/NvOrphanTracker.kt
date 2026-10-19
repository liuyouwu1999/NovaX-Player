package com.novax.app.data.privacy

import com.novax.app.data.NvPaths

/**
 * 改名跟随状态机（隐私功能计划 Task 10，spec §3.3「外部改名/移动」）：
 * 扫描完整跑完后对账——规则路径仍在 → 不动；确认丢失（父目录本轮被 BFS 列举过，故确证
 * 「消失」而非「不可达」）→ 在候选池（本轮确认存活的目录集）内按内容指纹找唯一匹配：
 * 唯一 → [onScanFinished] 的 migrate 回调（调用方更新 keyHash/dirCipher/displayCipher/
 * fingerprint 且 state=ACTIVE）；零/多匹配或规则无指纹 → orphan 回调置 ORPHANED
 * （fail-closed 整源临时锁：跟不住就锁大的，绝不静默失锁）。
 *
 * 纯逻辑核心可 JVM 测：规则快照与哈希口径经构造注入（[rulesOf]/[hashOf]），
 * 指纹拍摄与 DB 动作经 onScanFinished 回调注入——Android 类不进单测（见 NvOrphanTrackerTest）。
 *
 * 成本红线：候选指纹**惰性**——无丢失规则一个指纹都不拍；有丢失规则也逐候选**记忆化**
 * （多条规则共享一份候选指纹，每目录只列一次目录拍一次 HMAC），绝不做全源全量 HMAC。
 * 迁移后的候选被互斥消费（防同指纹目录被两条规则重复认领）。
 *
 * lockEnabled/incognitoEnabled 不参与状态机：无痕-only 规则与锁规则同走 migrate/orphan
 * （ORPHANED 后的「整源」语义——锁或无痕——由 NvPrivacyIndex 的 wholeSource 判定兜底）。
 */
class NvOrphanTracker(
    /** 规则快照供给（生产 = index.views() 过滤本源；测试注入固定表） */
    private val rulesOf: suspend (sourceId: Long) -> List<RuleSnap>,
    /** 新 keyHash 口径（生产 = NvPrivacyCipher::hmac——与 privacy_rules.keyHash 同款「只取路径」口径） */
    private val hashOf: (dir: String) -> String,
) {

    /**
     * 单条规则的对账快照。dirPath 空串 = 整源规则（无路径可跟随，恒跳过）；
     * [parentListed] = 父目录本轮是否被 BFS 实际列举——false 时目录状态未知
     * （父被剪枝/深度截断/失读），**不动**（不可达 ≠ 丢失，绝不误 ORPHANED）。
     */
    data class RuleSnap(
        val id: Long,
        val dirPath: String,
        val fingerprint: String?,
        val parentListed: Boolean,
    )

    suspend fun onScanFinished(
        sourceId: Long,
        visitedDirs: Set<String>,                          // 本次扫描确认存活的目录（入队 + 剪枝边界目击，调用方归一化前口径皆可）
        fingerprintOf: suspend (dir: String) -> String?,   // 对候选目录惰性拍指纹（列目录→NvFingerprint）；null=列举失败
        migrate: suspend (ruleId: Long, newDir: String, newHash: String, fp: String) -> Unit,
        orphan: suspend (ruleId: Long) -> Unit,
    ) {
        val rules = rulesOf(sourceId)
            .filter { it.dirPath.isNotBlank() }            // 整源规则无路径可跟随
            .sortedBy { NvPaths.normalize(it.dirPath).count { ch -> ch == '/' } }   // 浅层优先：父规则先认领候选
        if (rules.isEmpty()) return
        val alive = visitedDirs.mapTo(HashSet()) { NvPaths.normalize(it) }
        // 丢失 = 路径不在存活集，且父目录本轮被列举过（确证消失而非不可达）
        val missing = rules.filter { NvPaths.normalize(it.dirPath) !in alive && it.parentListed }
        if (missing.isEmpty()) return
        val candidates = alive.toSortedSet()               // 稳定顺序：断言与消费行为可复现
        val fpCache = HashMap<String, String?>()           // 逐候选记忆化：多规则共享一份候选指纹
        val consumed = HashSet<String>()                   // 已被某规则 migrate 认领的候选（互斥消费）
        for (rule in missing) {
            val want = rule.fingerprint ?: run { orphan(rule.id); continue }   // 无指纹无从跟随 → fail-closed
            var hit: String? = null
            var ambiguous = false
            for (cand in candidates) {
                if (cand in consumed) continue
                // 不能用 getOrPut：null（列举失败）是合法缓存值，getOrPut 对 null 会重复回调指纹
                val fp = if (fpCache.containsKey(cand)) fpCache[cand] else fingerprintOf(cand).also { fpCache[cand] = it }
                if (fp != null && fp == want) {
                    if (hit != null) { ambiguous = true; break }
                    hit = cand
                }
            }
            when {
                ambiguous || hit == null -> orphan(rule.id)
                else -> {
                    consumed += hit!!
                    migrate(rule.id, hit, hashOf(hit), want)
                }
            }
        }
    }
}
