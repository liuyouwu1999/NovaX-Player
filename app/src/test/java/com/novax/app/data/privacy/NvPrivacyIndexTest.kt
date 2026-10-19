package com.novax.app.data.privacy

import com.novax.app.data.db.PrivacyRuleEntity
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

/**
 * 纯 JVM（fakeCipher 注入，Room/Keystore 不进单测，见全局约束）：
 * Task 4 简报 Step 1 的九组验收线 + Access 纯判定函数。
 * Access 的 observeAll Flow 监听不在此测（Task 18 冒烟兜底）。
 */
class NvPrivacyIndexTest {

    // ---- fake：javax.crypto AES-GCM 固定密钥加解密（同 NvPrivacyCryptoTest 形态）+ 真 HMAC ----
    private fun fakeCipher(): NvPrivacyCipher {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val macKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")
        return object : NvPrivacyCipher {
            override fun encrypt(plain: String): String? = runCatching {
                val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
                NvPrivacyCrypto.CIPHER_PREFIX + java.util.Base64.getEncoder().encodeToString(c.iv) + ":" +
                    java.util.Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray()))
            }.getOrNull()
            override fun decrypt(cipherText: String): String? = runCatching {
                require(cipherText.startsWith(NvPrivacyCrypto.CIPHER_PREFIX))
                val p = cipherText.removePrefix(NvPrivacyCrypto.CIPHER_PREFIX).split(":")
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, java.util.Base64.getDecoder().decode(p[0])))
                String(c.doFinal(java.util.Base64.getDecoder().decode(p[1])))
            }.getOrNull()
            override fun hmac(plain: String): String =
                Mac.getInstance("HmacSHA256").run { init(macKey); doFinal(plain.toByteArray()) }
                    .joinToString("") { "%02x".format(it) }
        }
    }

    /** dir=null → dirCipher 塞不可解垃圾（模拟密钥丢失后的解不开行，BROKEN 口径） */
    private fun rule(
        cipher: NvPrivacyCipher,
        id: Long,
        sourceId: Long = 1L,
        dir: String? = "/dir$id",
        lock: Boolean = false,
        incognito: Boolean = false,
        state: String = "ACTIVE",
    ) = PrivacyRuleEntity(
        id = id, sourceId = sourceId, keyHash = "hash$id",
        dirCipher = if (dir == null) "垃圾密文" else cipher.encrypt(dir)!!,
        displayCipher = cipher.encrypt("显示$id")!!,
        lockEnabled = lock, incognitoEnabled = incognito, state = state, createdAt = 0L,
    )

    private fun indexOf(cipher: NvPrivacyCipher, vararg rules: PrivacyRuleEntity): NvPrivacyIndex {
        val i = NvPrivacyIndex(cipher)
        i.replaceAll(rules.toList())
        return i
    }

    // ① 段边界：/a/b 不命中 /a/bc、命中 /a/b/c、尾 / 归一
    @Test fun `段边界_前缀粘连不命中`() {
        assertFalse(NvPrivacyIndex.isUnderDir("/a/bc", "/a/b"))
        assertTrue(NvPrivacyIndex.isUnderDir("/a/b/c", "/a/b"))
        assertTrue(NvPrivacyIndex.isUnderDir("/a/b", "/a/b"))
    }

    @Test fun `段边界_尾斜杠归一`() {
        assertTrue(NvPrivacyIndex.isUnderDir("/a/b/", "/a/b"))
        assertTrue(NvPrivacyIndex.isUnderDir("/a/b", "/a/b/"))
        assertTrue(NvPrivacyIndex.isUnderDir("/a/b/c", "/a/b/"))
    }

    @Test fun `目录规则_经索引段边界判定`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 11, dir = "/a/b", lock = true))
        assertFalse(i.isLocked(1L, "/a/bc", emptySet()))
        assertTrue(i.isLocked(1L, "/a/b/c", emptySet()))
        assertTrue(i.isLocked(1L, "/a/b", emptySet()))
        assertFalse(i.isLocked(2L, "/a/b/c", emptySet()))   // 他源同路径不串扰
    }

    // ② 整源规则命中一切
    @Test fun `整源规则命中一切`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 10, dir = "", lock = true))
        for (p in listOf("", "/", "/x", "/x/y/z")) assertTrue("path=$p", i.isLocked(1L, p, emptySet()))
        assertTrue(i.covers(1L, "/x"))
        assertFalse(i.isLocked(2L, "/x", emptySet()))
    }

    // ③ 嵌套：整源锁+子目录锁，解锁整源 id 后子目录仍锁（解锁逐条）
    @Test fun `嵌套锁_解锁整源后子目录仍锁`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 10, dir = "", lock = true), rule(c, 11, dir = "/a", lock = true))
        assertTrue(i.isLocked(1L, "/a/b", emptySet()))
        assertTrue(i.isLocked(1L, "/a/b", setOf(10L)))          // 只解整源，子目录仍锁
        assertFalse(i.isLocked(1L, "/a/b", setOf(10L, 11L)))    // 逐条全解才放行
        assertTrue(i.isLocked(1L, "/z", emptySet()))
        assertFalse(i.isLocked(1L, "/z", setOf(10L)))
    }

    // ④ ORPHANED 锁规则 = 整源锁
    @Test fun `ORPHANED锁规则升级整源`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 12, dir = "/gone", lock = true, state = "ORPHANED"))
        assertTrue(i.isLocked(1L, "/anywhere/else", emptySet()))
        assertTrue(i.isLocked(1L, "", emptySet()))
        assertTrue(i.ruleIdsCovering(1L, "/anywhere").contains(12L))
    }

    // ⑤ BROKEN（decrypt null）= 整源锁
    @Test fun `密文解不开升级整源`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 13, dir = null, lock = true, state = "ACTIVE"))
        assertTrue(i.isLocked(1L, "/x", emptySet()))
        assertTrue(i.covers(1L, ""))
    }

    // ⑥ 无痕：任一命中规则 incognito 即 true
    @Test fun `无痕_任一命中即真`() {
        val c = fakeCipher()
        val i = indexOf(
            c,
            rule(c, 20, dir = "/a", lock = true),
            rule(c, 21, dir = "/b", incognito = true),
        )
        assertTrue(i.isIncognito(1L, "/b/c"))
        assertFalse(i.isIncognito(1L, "/a/c"))
        assertFalse(i.isIncognito(1L, "/c/d"))
        assertFalse(i.isIncognito(2L, "/b/c"))   // 他源不串扰
    }

    // ⑦ ORPHANED 无痕-only = 整源无痕（不拦播放）
    @Test fun `ORPHANED无痕only_整源无痕不拦播放`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 22, dir = "/gone", incognito = true, state = "ORPHANED"))
        assertTrue(i.isIncognito(1L, "/whatever"))
        assertTrue(i.covers(1L, "/whatever"))
        assertFalse(i.isLocked(1L, "/whatever", emptySet()))
    }

    // ⑧ jf：resolver 给 id 匹配 jf:<id>；resolver null 且该源有 jf 规则 → isLocked=true
    @Test fun `jf规则_resolver解析id匹配_子项经祖先段覆盖`() {
        val c = fakeCipher()
        // 模拟 JellyfinPathIndex：path → itemId
        val ids = mapOf("/v" to "1", "/v/f5" to "5", "/v/f5/ep.mkv" to "99", "/v/other" to "7")
        val i = indexOf(c, rule(c, 30, sourceId = 2L, dir = "jf:5", lock = true))
        i.attachJellyfinResolver { p -> ids[p] }
        assertTrue(i.isLocked(2L, "/v/f5", emptySet()))          // 目录本身：id 5 直接命中
        assertTrue(i.isLocked(2L, "/v/f5/ep.mkv", emptySet()))   // 锁内子项：祖先段命中（库/历史行不漏判）
        assertFalse(i.isLocked(2L, "/v/other", emptySet()))
        assertFalse(i.isLocked(2L, "/v/f5", setOf(30L)))         // 解锁逐条
    }

    @Test fun `jf规则_resolver未接_failclosed整源锁`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 31, sourceId = 2L, dir = "jf:5", lock = true))
        assertTrue(i.isLocked(2L, "/v/f5", emptySet()))          // resolver null → fail-closed
        assertTrue(i.covers(2L, "/v/anything"))
        assertEquals(listOf(31L), i.ruleIdsCovering(2L, "/v/anything"))
    }

    @Test fun `jf_failclosed只牵连有jf规则的源_无痕按有无jf无痕规则`() {
        val c = fakeCipher()
        val i = indexOf(
            c,
            rule(c, 32, sourceId = 2L, dir = "jf:5", incognito = true),
            rule(c, 33, sourceId = 1L, dir = "/a", lock = true),
        )
        assertTrue(i.isLocked(1L, "/a/b", emptySet()))   // 源 1 无 jf 规则 → resolver 缺失不牵连，目录锁正常生效
        assertFalse(i.isLocked(1L, "/a/b", setOf(33L)))
        assertTrue(i.isIncognito(2L, "/v/anything"))     // fail-closed 无痕：有 jf 无痕规则 → true
        assertFalse(i.isLocked(2L, "/v/anything", emptySet()))  // 无痕-only 不拦播放
    }

    @Test fun `jf规则_resolver接了但解析不出_failclosed同口径`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 34, sourceId = 2L, dir = "jf:5", lock = true))
        i.attachJellyfinResolver { null }
        assertTrue(i.isLocked(2L, "/v/f5", emptySet()))
        i.attachJellyfinResolver(null)   // 显式清空与未接同口径
        assertTrue(i.isLocked(2L, "/v/f5", emptySet()))
    }

    // ⑨ covers / ruleIdsCovering 命中集合正确（脱敏入口是 Access.redact，index 无 redact 方法）
    @Test fun `covers与ruleIdsCovering_命中集合正确`() {
        val c = fakeCipher()
        val i = indexOf(
            c,
            rule(c, 40, dir = "", lock = true),
            rule(c, 41, dir = "/a", lock = true),
            rule(c, 42, dir = "/a/incog", incognito = true),
            rule(c, 43, sourceId = 2L, dir = "/a", lock = true),
        )
        assertEquals(listOf(40L, 41L), i.ruleIdsCovering(1L, "/a/x"))
        assertEquals(listOf(40L), i.ruleIdsCovering(1L, "/z"))
        assertEquals(listOf(43L), i.ruleIdsCovering(2L, "/a/x"))
        assertTrue(i.covers(1L, "/a/incog/deep"))
        assertTrue(i.ruleIdsCovering(1L, "/a/incog/deep").contains(42L))
        assertEquals(emptyList<Long>(), i.ruleIdsCovering(null, "/a/x"))   // 外部路径豁免
    }

    // replaceAll 换快照
    @Test fun `replaceAll_整表替换旧快照`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 50, dir = "/a", lock = true))
        assertTrue(i.covers(1L, "/a/b"))
        i.replaceAll(emptyList())
        assertFalse(i.covers(1L, "/a/b"))
        assertFalse(i.isLocked(1L, "/a/b", emptySet()))
    }

    // RuleView：display 解密 + wholeSource 标记（设置页规则管理列表消费）
    @Test fun `RuleView_解密display与wholeSource标记`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 60, dir = "/a", lock = true), rule(c, 61, dir = null, incognito = true))
        val vs = i.views()
        assertEquals(2, vs.size)
        val v0 = vs.first { it.id == 60L }
        assertEquals("/a", v0.dirPath)
        assertEquals("显示60", v0.display)
        assertTrue(v0.lockEnabled)
        assertFalse(v0.wholeSource)
        assertTrue(vs.first { it.id == 61L }.wholeSource)
    }

    // ---- NvPrivacyAccess 纯判定函数（internal 可测抽出） ----

    @Test fun `historyMode_无痕SKIP_命中CIPHER_否则PLAIN`() {
        assertEquals(HistoryWriteMode.SKIP, NvPrivacyAccess.historyModeOf(incognitoHit = true, covered = true))
        assertEquals(HistoryWriteMode.SKIP, NvPrivacyAccess.historyModeOf(incognitoHit = true, covered = false))
        assertEquals(HistoryWriteMode.CIPHER, NvPrivacyAccess.historyModeOf(incognitoHit = false, covered = true))
        assertEquals(HistoryWriteMode.PLAIN, NvPrivacyAccess.historyModeOf(incognitoHit = false, covered = false))
    }

    @Test fun `redact_命中换占位_否则原文`() {
        assertEquals("[locked]", NvPrivacyAccess.redactOf(covered = true, path = "/nas/私人/a.mkv"))
        assertEquals("/nas/私人/a.mkv", NvPrivacyAccess.redactOf(covered = false, path = "/nas/私人/a.mkv"))
    }

    // ---- withinRuleScope（Task 17 顺带闭合 T13 purge/迁移盲区的范围判定核） ----

    @Test fun `范围判定_jf规则按resolver链子树反查`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 70, sourceId = 2L, dir = "jf:5", lock = true))
        val ids = mapOf("/v" to "1", "/v/f5" to "5", "/v/f5/ep.mkv" to "99", "/v/other" to "7")
        i.attachJellyfinResolver { p -> ids[p] }
        assertTrue(i.withinRuleScope("jf:5", "/v/f5"))           // 目录自身
        assertTrue(i.withinRuleScope("jf:5", "/v/f5/ep.mkv"))    // 子项（祖先段命中）
        assertFalse(i.withinRuleScope("jf:5", "/v/other"))       // 兄弟子树
        assertFalse(i.withinRuleScope("jf:5", "/v"))             // 祖先方向不在范围内
        assertFalse(i.withinRuleScope("jf:5", "/x/y"))           // 链解不出的段 → 不在范围
    }

    @Test fun `范围判定_jf规则链解不出回退false（宁少删不误删）`() {
        val c = fakeCipher()
        val i = indexOf(c, rule(c, 71, sourceId = 2L, dir = "jf:5", lock = true))
        assertFalse(i.withinRuleScope("jf:5", "/v/f5"))          // resolver 未接
        i.attachJellyfinResolver { null }                        // 接了但索引未热
        assertFalse(i.withinRuleScope("jf:5", "/v/f5/ep.mkv"))
    }

    @Test fun `范围判定_常规规则仍走段边界前缀`() {
        val c = fakeCipher()
        val i = indexOf(c)
        assertTrue(i.withinRuleScope("/a/b", "/a/b/c"))
        assertTrue(i.withinRuleScope("/a/b", "/a/b"))
        assertFalse(i.withinRuleScope("/a/b", "/a/bc"))
        assertTrue(i.withinRuleScope("", "/anything/deep"))      // 整源口径：根恒真
    }
}
