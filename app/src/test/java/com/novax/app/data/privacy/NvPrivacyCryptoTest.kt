package com.novax.app.data.privacy
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*
class NvPrivacyCryptoTest {
    private fun fake(): NvPrivacyCipher {
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
    @Test fun `加解密往返`() { val c = fake(); val e = c.encrypt("/nas/私人")!!; assertNotEquals("/nas/私人", e); assertEquals("/nas/私人", c.decrypt(e)) }
    @Test fun `两次加密密文不同（随机IV）`() { val c = fake(); assertNotEquals(c.encrypt("a"), c.encrypt("a")) }
    @Test fun `hmac 确定性且不同输入不同`() { val c = fake(); assertEquals(c.hmac("x"), c.hmac("x")); assertNotEquals(c.hmac("x"), c.hmac("y")) }
    @Test fun `解不开返回null`() { assertEquals(null, fake().decrypt("垃圾输入")) }
}
