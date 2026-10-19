package com.novax.app.data.privacy

import com.novax.app.data.db.PrivacyRuleEntity
import java.util.concurrent.atomic.AtomicReference

/**
 * 隐私规则内存索引（隐私功能计划 Task 4）：落库规则全量解密进内存做匹配判定，
 * 不依赖 SQL 对明文查询（落盘零明文，见 [PrivacyRuleEntity]）。纯 Kotlin 无 Android 依赖，
 * 单测以 fakeCipher 直接构造（见 NvPrivacyIndexTest）。
 *
 * 判定语义（fail-closed，绝不漏判）：
 * - 命中 = 整源规则，或段边界前缀匹配（[isUnderDir]，与 ScanRules.isUnderExcludedDir 同判定、
 *   独立实现防隐私层耦合扫描器）；
 * - dirCipher 解不开（decrypt null）或 state 为 ORPHANED/BROKEN → 升级「覆盖全源」参与判定；
 * - Jellyfin 规则（dirPath 以 [JF_PREFIX] 开头）不走路径前缀匹配：把查询路径浅→深逐段经
 *   resolver 解析成 itemId 后与 "jf:<id>" 比较（含祖先段 → 锁内子项在库/历史行不漏判）；
 *   resolver 未接或任一段解析不出，且该源存在 jf 规则 → 该源全部 jf 规则按整源覆盖
 *   （规格 §3.1「临时整源锁」）；resolver 未接的 fail-closed 只牵连有 jf 规则的源；
 * - sourceId == null（外部 content:// 等无源路径）按设计豁免，不命中任何规则；
 * - 嵌套规则任一 lockEnabled 未解锁即为锁，解锁逐条；无痕任一命中规则开启即为真。
 */
class NvPrivacyIndex(private val cipher: NvPrivacyCipher) {

    data class RuleView(
        val id: Long,
        val sourceId: Long,
        val dirPath: String,
        val display: String,
        val lockEnabled: Boolean,
        val incognitoEnabled: Boolean,
        val wholeSource: Boolean,   // dirPath=="" 或 ORPHANED/BROKEN（fail-closed 升整源）
        val fingerprint: String? = null,   // 加锁时拍的子内容指纹（Task 10 改名跟随的匹配判据）
    )

    private val snapshot = AtomicReference<List<RuleView>>(emptyList())

    @Volatile
    private var jfResolver: ((path: String) -> String?)? = null

    /** 全量替换内存快照（NvPrivacyAccess 常驻监 observeAll 的唯一写入口）；解不开 → dirPath 未知 → wholeSource */
    fun replaceAll(rules: List<PrivacyRuleEntity>) {
        snapshot.set(
            rules.map { r ->
                val dir = cipher.decrypt(r.dirCipher)
                val display = if (r.displayCipher.isEmpty()) "" else cipher.decrypt(r.displayCipher)
                RuleView(
                    id = r.id,
                    sourceId = r.sourceId,
                    dirPath = dir ?: "",
                    display = display ?: "",
                    lockEnabled = r.lockEnabled,
                    incognitoEnabled = r.incognitoEnabled,
                    wholeSource = dir == null || dir.isEmpty() ||
                        r.state == STATE_ORPHANED || r.state == STATE_BROKEN,
                    fingerprint = r.fingerprint,
                )
            },
        )
    }

    /** path→itemId；Task 17 接 JellyfinPathIndex.idOf ?: 逐段重解析；传 null 清除（与未接同 fail-closed 口径） */
    fun attachJellyfinResolver(resolve: ((path: String) -> String?)?) {
        jfResolver = resolve
    }

    fun isLocked(sourceId: Long?, path: String, unlockedRuleIds: Set<Long>): Boolean =
        covering(sourceId, path).any { it.lockEnabled && it.id !in unlockedRuleIds }

    fun isIncognito(sourceId: Long?, path: String): Boolean =
        covering(sourceId, path).any { it.incognitoEnabled }

    fun covers(sourceId: Long?, path: String): Boolean = covering(sourceId, path).isNotEmpty()

    fun ruleIdsCovering(sourceId: Long?, path: String): List<Long> =
        covering(sourceId, path).map { it.id }

    /** 命中的锁规则里未解锁的 id（NvPrivacyAccess.blockingRuleIds 用；无痕-only 不拦播放，不在此列） */
    internal fun blockingIds(sourceId: Long?, path: String, unlockedRuleIds: Set<Long>): List<Long> =
        covering(sourceId, path).filter { it.lockEnabled && it.id !in unlockedRuleIds }.map { it.id }

    /** 当前解密快照（设置页规则管理列表消费；规则量小，直接全量给） */
    fun views(): List<RuleView> = snapshot.get()

    /** 命中该 (sourceId, path) 的全部规则；jf 段解析整条链只做一次 */
    private fun covering(sourceId: Long?, path: String): List<RuleView> {
        if (sourceId == null) return emptyList()
        val inSource = snapshot.get().filter { it.sourceId == sourceId }
        if (inSource.isEmpty()) return emptyList()
        // jfFailClosed = resolver 未接或任一段解析不出 → 该源全部 jf 规则按整源覆盖
        var jfIds: List<String>? = null
        var jfFailClosed = false
        if (inSource.any { !it.wholeSource && it.dirPath.startsWith(JF_PREFIX) }) {
            val chain = resolveJfChain(path)
            if (chain == null) jfFailClosed = true else jfIds = chain
        }
        return inSource.filter { r ->
            when {
                r.wholeSource -> true
                r.dirPath.startsWith(JF_PREFIX) ->
                    jfFailClosed || jfIds!!.contains(r.dirPath.removePrefix(JF_PREFIX))
                else -> isUnderDir(path, r.dirPath)
            }
        }
    }

    /** 查询路径浅→深各段（含自身）逐段经 resolver 解析成 id；resolver 未接或任一段解析不出 → null */
    private fun resolveJfChain(path: String): List<String>? {
        val resolver = jfResolver ?: return null
        val p = path.trimEnd('/')
        if (p.isEmpty()) return emptyList()
        val ids = ArrayList<String>(4)
        var i = 1   // 跳过首位 '/'；相对路径（无前导斜杠）也从第 2 字符找边界，末段整串兜底
        while (true) {
            val slash = p.indexOf('/', i)
            val seg = if (slash == -1) p else p.substring(0, slash)
            val id = resolver(seg) ?: return null
            ids.add(id)
            if (slash == -1) return ids
            i = slash + 1
        }
    }

    /**
     * [path] 是否落在规则范围 [dirPath] 内（purgeScope/明文迁移的范围判定核，Task 17 顺带
     * 闭合 T13 报告的 jf 盲区）：常规规则 = [isUnderDir] 段边界前缀；jf 规则（jf:<itemId>）
     * 无路径前缀语义，按 resolver 链子树反查（祖先段或自身命中该 itemId）。链解不出
     * （resolver 未接/索引未热/跨源歧义）→ false 回退 T13 旧行为——purge 方向宁少删不误删
     * （误删用户历史不可恢复；少删由锁定侧 fail-closed 整源罩 + T14 查询层过滤兜底隐藏）。
     */
    fun withinRuleScope(dirPath: String, path: String): Boolean =
        if (dirPath.startsWith(JF_PREFIX)) {
            resolveJfChain(path)?.contains(dirPath.removePrefix(JF_PREFIX)) ?: false
        } else {
            isUnderDir(path, dirPath)
        }

    companion object {
        const val JF_PREFIX = "jf:"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_ORPHANED = "ORPHANED"
        const val STATE_BROKEN = "BROKEN"

        /**
         * 段边界前缀匹配：path 与 dir 相等，或 path 以 "dir/" 开头（两侧先去尾 '/'）。
         * 独立实现不 import ScanRules（规格 §3.1：同判定、防耦合）。
         */
        fun isUnderDir(path: String, dir: String): Boolean {
            val p = path.trimEnd('/')
            val d = dir.trimEnd('/')
            return p == d || p.startsWith("$d/")
        }
    }
}
