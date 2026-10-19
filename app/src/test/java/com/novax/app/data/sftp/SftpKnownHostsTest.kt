package com.novax.app.data.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64

/**
 * SFTP 主机密钥 TOFU 纯 JVM 单测：known-hosts 文件读写 roundtrip（换实例重读，防退化成
 * 内存态）、指纹格式（SHA256 + base64 无填充）、[TofuHostKeyVerifier] 三态（无记录记入 /
 * 一致放行 / 不一致拒绝）。sshj 连接路径不造假测试（对齐 SftpReadStreamReuseTest 口径）。
 */
class SftpKnownHostsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val kp1 = keyPair()
    private val kp2 = keyPair()

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun newStore(): SftpKnownHosts = SftpKnownHosts(File(tmp.root, SftpKnownHosts.FILE_NAME))

    // ---- 文件读写 roundtrip ----

    @Test
    fun putGet_roundtrip_survivesNewInstanceOnDisk() {
        val file = File(tmp.root, SftpKnownHosts.FILE_NAME)
        SftpKnownHosts(file).put("nas.local", 22, "SHA256:abc123")
        assertEquals("SHA256:abc123", SftpKnownHosts(file).get("nas.local", 22))
    }

    @Test
    fun get_missingRecord_returnsNull() {
        assertEquals(null, newStore().get("nas.local", 22))
    }

    @Test
    fun put_sameHostOverwrites_keepsSingleLine() {
        val store = newStore()
        store.put("nas.local", 22, "SHA256:old")
        store.put("nas.local", 22, "SHA256:new")
        assertEquals("SHA256:new", store.get("nas.local", 22))
        assertEquals(1, File(tmp.root, SftpKnownHosts.FILE_NAME).readLines().size)
    }

    @Test
    fun remove_dropsOnlyTargetHost_otherHostIntact() {
        val store = newStore()
        store.put("nas.local", 22, "SHA256:a")
        store.put("nas.local", 2222, "SHA256:b")
        assertTrue(store.remove("nas.local", 22))
        assertEquals(null, store.get("nas.local", 22))
        assertEquals("SHA256:b", store.get("nas.local", 2222))
    }

    @Test
    fun remove_missingRecord_returnsFalse() {
        assertFalse(newStore().remove("nas.local", 22))
    }

    // ---- 指纹格式 ----

    @Test
    fun fingerprint_sha256Base64WithoutPadding() {
        val fp = publicKeyFingerprint(kp1.public)
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(kp1.public.encoded))
        assertEquals(expected, fp)
        assertFalse("不得含填充字符 '=': $fp", fp.contains('='))
    }

    @Test
    fun fingerprint_differsAcrossKeys() {
        assertNotEquals(publicKeyFingerprint(kp1.public), publicKeyFingerprint(kp2.public))
    }

    // ---- TOFU 三态 ----

    @Test
    fun tofu_noRecord_pinsFingerprintThenAllows() {
        val store = newStore()
        val verifier = TofuHostKeyVerifier(store, "nas.local", 22)
        assertTrue(verifier.verify("nas.local", 22, kp1.public))
        assertEquals(publicKeyFingerprint(kp1.public), store.get("nas.local", 22))
    }

    @Test
    fun tofu_consistentKey_allows() {
        val store = newStore()
        val verifier = TofuHostKeyVerifier(store, "nas.local", 22)
        assertTrue(verifier.verify("nas.local", 22, kp1.public))
        assertTrue(verifier.verify("nas.local", 22, kp1.public))
    }

    @Test
    fun tofu_mismatchedKey_throwsActionableIOException() {
        val store = newStore()
        val verifier = TofuHostKeyVerifier(store, "nas.local", 22)
        verifier.verify("nas.local", 22, kp1.public)
        try {
            verifier.verify("nas.local", 22, kp2.public)
            fail("指纹不一致必须拒绝")
        } catch (e: IOException) {
            assertTrue("应含中文可行动文案: ${e.message}", e.message!!.contains("指纹与首次连接时不一致"))
        }
    }
}
