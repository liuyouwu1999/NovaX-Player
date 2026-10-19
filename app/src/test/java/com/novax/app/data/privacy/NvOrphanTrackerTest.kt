package com.novax.app.data.privacy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM（fake fingerprintOf/migrate/orphan，Android 类不进单测）：改名跟随状态机（Task 10，spec §3.3）——
 * 规则路径仍在 visited → 不动；确认丢失（父目录本轮被列举）→ 候选池指纹唯一匹配 → migrate；
 * 零/多匹配或无指纹 → ORPHANED。候选指纹惰性（有丢失规则才拍）且逐候选记忆化；候选迁移后互斥消费。
 */
class NvOrphanTrackerTest {

    /** 记录器：migrate/orphan 调用与 fingerprintOf 拍指纹次数全部可断言 */
    private class Recorder {
        val migrated = mutableListOf<Triple<Long, String, Pair<String, String>>>()   // ruleId, newDir, (newHash, fp)
        val orphaned = mutableListOf<Long>()
        val fpCalls = mutableListOf<String>()
        val fpResults = mutableMapOf<String, String?>()   // 候选目录 → 预设指纹（缺省 null=列举失败）

        suspend fun fingerprintOf(dir: String): String? {
            fpCalls += dir
            return fpResults[dir]
        }

        fun tracker(rules: List<NvOrphanTracker.RuleSnap>, hashOf: (String) -> String = { "h($it)" }) =
            NvOrphanTracker(rulesOf = { rules }, hashOf = hashOf)
    }

    private fun rule(id: Long, dir: String, fp: String? = "fp$id", parentListed: Boolean = true) =
        NvOrphanTracker.RuleSnap(id = id, dirPath = dir, fingerprint = fp, parentListed = parentListed)

    // ---- ① 规则路径在 visited → 不动（且零指纹成本） ----

    @Test fun `规则路径在visited_不动且不拍指纹`() = runBlocking {
        val r = Recorder()
        r.tracker(rules = listOf(rule(1, "/media/secret")))
            .onScanFinished(7L, setOf("/media", "/media/secret", "/media/other"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertTrue(r.orphaned.isEmpty())
        assertTrue(r.fpCalls.isEmpty())
    }

    @Test fun `规则路径尾分隔符归一化后在visited_不动`() = runBlocking {
        val r = Recorder()
        r.tracker(rules = listOf(rule(1, "/media/secret/")))
            .onScanFinished(7L, setOf("/media", "/media/secret"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertTrue(r.orphaned.isEmpty())
        assertTrue(r.fpCalls.isEmpty())
    }

    // ---- ② 确认丢失 + 唯一匹配 → migrate（新 hash/dir/fingerprint） ----

    @Test fun `唯一匹配_migrate带新目录新hash与指纹`() = runBlocking {
        val r = Recorder()
        r.fpResults["/media/renamed"] = "fp1"
        r.tracker(rules = listOf(rule(1, "/media/secret")))
            .onScanFinished(7L, setOf("/media", "/media/renamed", "/media/other"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertEquals(listOf(Triple(1L, "/media/renamed", ("h(/media/renamed)" to "fp1"))), r.migrated)
        assertTrue(r.orphaned.isEmpty())
    }

    @Test fun `零匹配_orphan不migrate`() = runBlocking {
        val r = Recorder()
        r.fpResults["/media/renamed"] = "different"
        r.tracker(rules = listOf(rule(1, "/media/secret")))
            .onScanFinished(7L, setOf("/media", "/media/renamed"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertEquals(listOf(1L), r.orphaned)
    }

    @Test fun `多匹配歧义_orphan不migrate`() = runBlocking {
        val r = Recorder()
        r.fpResults["/media/copyA"] = "fp1"
        r.fpResults["/media/copyB"] = "fp1"
        r.tracker(rules = listOf(rule(1, "/media/secret")))
            .onScanFinished(7L, setOf("/media", "/media/copyA", "/media/copyB"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertEquals(listOf(1L), r.orphaned)
    }

    @Test fun `候选列举失败返回null_不算匹配`() = runBlocking {
        val r = Recorder()
        // /media/gone 未预设 → fingerprintOf 返回 null（列举失败）
        r.tracker(rules = listOf(rule(1, "/media/secret")))
            .onScanFinished(7L, setOf("/media", "/media/gone"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertEquals(listOf(1L), r.orphaned)
    }

    // ---- ③ 无痕-only 规则同状态机（tracker 不区分锁/无痕，wholeSource 兜底在 index） ----

    @Test fun `无痕only规则_唯一匹配同样migrate`() = runBlocking {
        val r = Recorder()
        r.fpResults["/media/moved"] = "fp9"
        r.tracker(rules = listOf(rule(9, "/media/incognito")))
            .onScanFinished(7L, setOf("/media", "/media/moved"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertEquals(listOf(Triple(9L, "/media/moved", ("h(/media/moved)" to "fp9"))), r.migrated)
        assertTrue(r.orphaned.isEmpty())
    }

    @Test fun `无痕only规则_零匹配同样orphan`() = runBlocking {
        val r = Recorder()
        r.tracker(rules = listOf(rule(9, "/media/incognito")))
            .onScanFinished(7L, setOf("/media", "/media/elsewhere"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertEquals(listOf(9L), r.orphaned)
    }

    // ---- ④ 边界：父目录未列举（不可达≠丢失）→ 不动；整源规则跳过；无指纹 → orphan ----

    @Test fun `父目录未列举_目录状态未知_不动不拍指纹`() = runBlocking {
        val r = Recorder()
        // 两个规则的父目录都不在 visited（如被整源锁剪枝/深度截断/失读跳过）——不可达不能当丢失
        r.tracker(rules = listOf(rule(1, "/media/secret", parentListed = false), rule(2, "/media/secret/kid", parentListed = false)))
            .onScanFinished(7L, setOf("/media/elsewhere"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertTrue(r.orphaned.isEmpty())
        assertTrue(r.fpCalls.isEmpty())
    }

    @Test fun `整源规则dirPath为空_跳过对账`() = runBlocking {
        val r = Recorder()
        r.tracker(rules = listOf(rule(1, "")))
            .onScanFinished(7L, emptySet(), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertTrue(r.orphaned.isEmpty())
        assertTrue(r.fpCalls.isEmpty())
    }

    @Test fun `规则无指纹_无从跟随直接orphan`() = runBlocking {
        val r = Recorder()
        r.tracker(rules = listOf(rule(1, "/media/secret", fp = null)))
            .onScanFinished(7L, setOf("/media", "/media/twin"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.migrated.isEmpty())
        assertEquals(listOf(1L), r.orphaned)
        assertTrue("无指纹不应拍任何候选指纹", r.fpCalls.isEmpty())
    }

    // ---- ⑤ 惰性与记忆化：候选指纹每目录只拍一次；迁移后的候选互斥消费 ----

    @Test fun `两条丢失规则_候选指纹记忆化每目录只拍一次`() = runBlocking {
        val r = Recorder()
        r.fpResults["/media/a"] = "fp1"
        r.fpResults["/media/b"] = "fp2"
        r.tracker(rules = listOf(rule(1, "/x/one"), rule(2, "/x/two")))
            .onScanFinished(7L, setOf("/media", "/media/a", "/media/b"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertEquals(
            listOf(Triple(1L, "/media/a", ("h(/media/a)" to "fp1")), Triple(2L, "/media/b", ("h(/media/b)" to "fp2"))),
            r.migrated,
        )
        // 候选 2 个，虽然两条规则都找匹配，但指纹逐候选记忆化 → 总拍摄次数 = 候选数
        assertEquals(listOf("/media", "/media/a", "/media/b"), r.fpCalls)
        assertTrue(r.orphaned.isEmpty())
    }

    @Test fun `候选迁移后互斥消费_同指纹目录不二次认领`() = runBlocking {
        val r = Recorder()
        r.fpResults["/media/a"] = "fp1"
        r.fpResults["/media/b"] = "other"
        r.tracker(rules = listOf(rule(1, "/x/one"), rule(2, "/x/two", fp = "fp1")))
            .onScanFinished(7L, setOf("/media", "/media/a", "/media/b"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        // 规则 1 认领 /media/a 后，规则 2（同指纹）再无可用候选 → orphan
        assertEquals(listOf(Triple(1L, "/media/a", ("h(/media/a)" to "fp1"))), r.migrated)
        assertEquals(listOf(2L), r.orphaned)
    }

    @Test fun `无丢失规则_一个指纹都不拍`() = runBlocking {
        val r = Recorder()
        r.tracker(rules = emptyList())
            .onScanFinished(7L, setOf("/media", "/media/a"), r::fingerprintOf,
                { id, d, h, fp -> r.migrated += Triple(id, d, h to fp) }, { r.orphaned += it })
        assertTrue(r.fpCalls.isEmpty())
    }
}
