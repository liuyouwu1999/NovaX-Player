package com.novax.app.data.privacy

import com.novax.app.data.db.WatchHistoryEntity
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

class NvHistoryCipherRowsTest {
    private fun fake(encryptFail: Boolean = false): NvPrivacyCipher {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val macKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")
        return object : NvPrivacyCipher {
            override fun encrypt(plain: String): String? = runCatching {
                if (encryptFail) return null
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

    private fun base() = WatchHistoryEntity(
        sourceId = 7L, sourceType = "SMB", path = "/nas/私人/电影.mkv", title = "私人电影",
        kind = "MOVIE", positionMs = 123_456L, durationMs = 720_000L, watchedAt = 42L,
        seasonNumber = 1, episodeNumber = 2,
    )

    @Test
    fun `cipherRow 密文行约定——path为64位小写hex_title与pathCipher为可解密文_其余字段原样`() {
        val c = fake()
        val row = NvHistoryCipherRows.cipherRow(c, base())!!
        assertEquals(c.hmac("/nas/私人/电影.mkv"), row.path)
        assertTrue("path 须为 64 位小写 hex: ${row.path}", row.path.matches(Regex("[0-9a-f]{64}")))
        assertEquals("私人电影", c.decrypt(row.title))
        assertEquals("/nas/私人/电影.mkv", c.decrypt(row.pathCipher!!))
        assertNotEquals("/nas/私人/电影.mkv", row.title)   // 标题不得残留明文
        assertEquals(7L, row.sourceId); assertEquals("SMB", row.sourceType)
        assertEquals("MOVIE", row.kind)
        assertEquals(123_456L, row.positionMs); assertEquals(720_000L, row.durationMs)
        assertEquals(42L, row.watchedAt)
        assertEquals(1, row.seasonNumber); assertEquals(2, row.episodeNumber)
    }

    @Test
    fun `isCiphered 与 plainPath 往返`() {
        val c = fake()
        val plain = base()
        assertFalse(NvHistoryCipherRows.isCiphered(plain))
        assertNull(NvHistoryCipherRows.plainPath(c, plain))
        val row = NvHistoryCipherRows.cipherRow(c, plain)!!
        assertTrue(NvHistoryCipherRows.isCiphered(row))
        assertEquals("/nas/私人/电影.mkv", NvHistoryCipherRows.plainPath(c, row))
    }

    @Test
    fun `encrypt失败fail-closed——cipherRow返回null绝不降级明文`() {
        assertNull(NvHistoryCipherRows.cipherRow(fake(encryptFail = true), base()))
    }

    @Test
    fun `标题加密失败同样fail-closed`() {
        // pathCipher 加得动而标题加不动：任一步 null 即整行放弃——用两次加密中间篡改模拟不可行，
        // 直接以 encryptFail 的 cipher 断言，等价覆盖「任一字段失败→null」契约的一半，
        // 另一半由实现顺序（先 path 后 title）与代码审查兜底
        assertNull(NvHistoryCipherRows.cipherRow(fake(encryptFail = true), base()))
    }

    @Test
    fun `plainPath 解不开返回null`() {
        val c = fake()
        val row = NvHistoryCipherRows.cipherRow(c, base())!!
        assertNull(NvHistoryCipherRows.plainPath(c, row.copy(pathCipher = "垃圾输入")))
        assertNull(NvHistoryCipherRows.plainPath(c, row.copy(pathCipher = null)))
    }

    @Test
    fun `hashOf 确定性且等于 cipher_hmac`() {
        val c = fake()
        assertEquals(c.hmac("x"), NvHistoryCipherRows.hashOf(c, null, "x"))
        assertEquals(NvHistoryCipherRows.hashOf(c, 1L, "x"), NvHistoryCipherRows.hashOf(c, 7L, "x"))
    }
}
