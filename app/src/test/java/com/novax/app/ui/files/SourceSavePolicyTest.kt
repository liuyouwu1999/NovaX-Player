package com.novax.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-01 机构整改 spec §2：口令落库三分辨（纯函数） */
class SourceSavePolicyTest {

    @Test
    fun anonymous_wins_overEverything() {
        assertEquals(
            SourceSavePolicy.PassResolution.Empty,
            SourceSavePolicy.resolvePassword(anonymous = true, passUnknown = true, pass = "x"),
        )
    }

    @Test
    fun passUnknown_andUntouched_keepsExistingColumn() {
        assertEquals(
            SourceSavePolicy.PassResolution.KeepExisting,
            SourceSavePolicy.resolvePassword(anonymous = false, passUnknown = true, pass = ""),
        )
    }

    @Test
    fun passUnknown_butRetyped_encryptsNewValue() {
        assertEquals(
            SourceSavePolicy.PassResolution.NeedsEncrypt("newpw"),
            SourceSavePolicy.resolvePassword(anonymous = false, passUnknown = true, pass = "newpw"),
        )
    }

    @Test
    fun normalEdit_needsEncrypt() {
        assertEquals(
            SourceSavePolicy.PassResolution.NeedsEncrypt("pw"),
            SourceSavePolicy.resolvePassword(anonymous = false, passUnknown = false, pass = "pw"),
        )
    }

    @Test
    fun plaintextStored_detection() {
        assertTrue(SourceSavePolicy.isPlaintextStored("legacy"))
        assertFalse(SourceSavePolicy.isPlaintextStored(""))
        assertFalse(SourceSavePolicy.isPlaintextStored("nvenc1:aa:bb"))
    }

    @Test
    fun duplicate_brokenKeystore_keepsExistingYieldsEmpty_documentedEdge() {
        // 复制源 original=null：KeepExisting 解析出 KeepExisting 后由调用方落 w.original?.password.orEmpty()=""
        // → 测试连接必败强制重输（不崩溃、无假保存成功）。此测试锚定解析本身不含 original 语义。
        assertEquals(
            SourceSavePolicy.PassResolution.KeepExisting,
            SourceSavePolicy.resolvePassword(anonymous = false, passUnknown = true, pass = ""),
        )
    }

    @Test
    fun resolveSave_keepExisting_usesOriginalPasswordColumn() {
        // 主守卫（评审 P1-2）：沿用原列映射钉死在纯函数里，执行者写错（如落 w.pass）此处必红
        val cmd = SourceSavePolicy.resolveSave(
            filesOnly = false, anonymous = false, passUnknown = true, pass = "",
            originalPassword = "nvenc1:cipher",
        ) { null }
        assertEquals(SourceSavePolicy.SaveCommand.Save(false, "nvenc1:cipher"), cmd)
    }

    @Test
    fun resolveSave_encryptDown_yieldsConfirm_withPlain() {
        val cmd = SourceSavePolicy.resolveSave(
            filesOnly = true, anonymous = false, passUnknown = false, pass = "pw",
            originalPassword = null,
        ) { null }   // 注入=模拟 Keystore 失败
        assertEquals(SourceSavePolicy.SaveCommand.ConfirmPlaintext(true, "pw"), cmd)
    }

    @Test
    fun resolveSave_encryptOk_yieldsCiphertext() {
        val cmd = SourceSavePolicy.resolveSave(
            filesOnly = false, anonymous = false, passUnknown = false, pass = "pw",
            originalPassword = null,
        ) { p -> "nvenc1:E:$p" }
        assertEquals(SourceSavePolicy.SaveCommand.Save(false, "nvenc1:E:pw"), cmd)
    }

    @Test
    fun reencrypt_chain_policyAnchor() {
        // 自愈链（spec §2.2-5）：明文行 → 正常保存 → 密文 → 角标消失
        val plain = "legacy"
        assertTrue(SourceSavePolicy.isPlaintextStored(plain))
        val cmd = SourceSavePolicy.resolveSave(false, false, false, plain, null) { p -> "nvenc1:E:$p" }
        val stored = (cmd as SourceSavePolicy.SaveCommand.Save).storedPassword
        assertFalse(SourceSavePolicy.isPlaintextStored(stored))
    }

    // ---- 免重测判定（spec §2.2-2 评审二轮 P0-R）----

    private fun srcEntity(host: String = "nas.local") = com.novax.app.data.db.SourceEntity(
        type = com.novax.app.data.db.SourceType.SMB, nickname = "t", host = host, port = 445,
        domain = "", username = "u", password = "x", shareOrPath = "/media",
    )

    @Test
    fun canSkipRetest_unchangedConnFields_true() {
        assertTrue(SourceSavePolicy.canSkipRetest(passUnknown = true, pass = "", original = srcEntity(), candidate = srcEntity()))
    }

    @Test
    fun canSkipRetest_changedHost_false() {
        assertFalse(SourceSavePolicy.canSkipRetest(passUnknown = true, pass = "", original = srcEntity(), candidate = srcEntity(host = "new-nas.local")))
    }

    @Test
    fun canSkipRetest_duplicateOrRetypedOrHealthy_false() {
        val e = srcEntity()
        assertFalse(SourceSavePolicy.canSkipRetest(passUnknown = true, pass = "", original = null, candidate = e))   // 复制源
        assertFalse(SourceSavePolicy.canSkipRetest(passUnknown = true, pass = "retype", original = e, candidate = e)) // 已重输
        assertFalse(SourceSavePolicy.canSkipRetest(passUnknown = false, pass = "", original = e, candidate = e))     // Keystore 健康
    }
}
