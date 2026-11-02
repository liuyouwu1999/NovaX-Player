package com.novax.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-11-01 机构整改 spec §2：三态口令 API。软件密钥注入缝（keyFactory）使 AES-GCM
 * 全路径在纯 JVM 可测；Keystore 失败路径用抛异常工厂模拟。
 */
class NvSecretsTest {

    private val swKey: SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun injectSoftwareKey() {
        NvSecrets.keyFactory = { SecretKeySpec(swKey.encoded, "AES") }
    }

    @Test
    fun roundtrip_encryptPrefix_decryptReturnsOriginal() {
        val stored = NvSecrets.encryptOrNull("secret!")
        assertTrue("密文必须带 nvenc1: 前缀: $stored", stored!!.startsWith("nvenc1:"))
        assertEquals("secret!", NvSecrets.decryptOrNull(stored))
        assertEquals("secret!", NvSecrets.decrypt(stored))
    }

    @Test
    fun encryptOrNull_emptyString_passthrough() {
        assertEquals("", NvSecrets.encryptOrNull(""))
    }

    @Test
    fun encryptOrNull_keystoreDown_returnsNull_notPlaintext() {
        NvSecrets.keyFactory = { error("keystore down") }
        assertNull(NvSecrets.encryptOrNull("secret!"))   // 绝不静默返回明文
    }

    @Test
    fun decryptOrNull_legacyPlaintext_passthrough() {
        assertEquals("oldplain", NvSecrets.decryptOrNull("oldplain"))
    }

    @Test
    fun decryptOrNull_undecryptable_returnsNull() {
        assertNull(NvSecrets.decryptOrNull("nvenc1:AAAA:BBBB"))
    }

    @Test
    fun decrypt_undecryptable_fallsBackEmpty_authPathAnchor() {
        assertEquals("", NvSecrets.decrypt("nvenc1:AAAA:BBBB"))
    }
}
