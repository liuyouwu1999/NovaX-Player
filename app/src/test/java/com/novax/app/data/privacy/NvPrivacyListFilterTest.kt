package com.novax.app.data.privacy

import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.WatchHistoryEntity
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

/**
 * 查询层过滤 + 密文行解密展示（隐私计划 Task 14）纯 JVM 单测：
 * 锁定行滤掉 / 解锁保留 / sourceId=null 豁免 / 密文行解密展示 / 解密失败 fail-closed。
 * NvPrivacyAccess 构造私有（Room 依赖）不可 fake——判定核心 filterIn 注入 isLocked 直测。
 */
class NvPrivacyListFilterTest {

    private fun fake(decryptFail: Boolean = false): NvPrivacyCipher {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val macKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")
        return object : NvPrivacyCipher {
            override fun encrypt(plain: String): String? = runCatching {
                val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
                NvPrivacyCrypto.CIPHER_PREFIX + java.util.Base64.getEncoder().encodeToString(c.iv) + ":" +
                    java.util.Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray()))
            }.getOrNull()
            override fun decrypt(cipherText: String): String? = runCatching {
                if (decryptFail) return null
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

    private fun hist(path: String, sourceId: Long? = 7L, title: String = "私人电影") = WatchHistoryEntity(
        sourceId = sourceId, sourceType = "SMB", path = path, title = title,
        kind = "MOVIE", positionMs = 123_456L, durationMs = 720_000L, watchedAt = 42L,
    )

    private fun fav(path: String, sourceId: Long? = 7L) = FavoriteEntity(
        sourceId = sourceId, path = path, title = "私人收藏", kind = "MOVIE", addedAt = 9L,
    )

    /** 锁定语义快照：7 号源 /nas/私人 下未解锁即锁（规则按源隔离）；unlocked=true 模拟会话已解锁 */
    private fun lockOf(unlocked: Boolean): (Long?, String) -> Boolean =
        { sid, p -> !unlocked && sid == 7L && p.startsWith("/nas/私人") }

    // ---------- filter：锁定行滤掉 / 解锁保留 / sourceId=null 豁免 ----------

    @Test
    fun `filter 锁定行滤掉_其余保留_顺序不变`() {
        val rows = listOf(
            hist("/nas/公开/a.mkv", 7L),
            hist("/nas/私人/b.mkv", 7L),
            hist("/nas/私人/c.mkv", 8L),   // 同前缀、异源：规则按源隔离，不受 7 号源锁影响
        )
        val out = NvPrivacyListFilter.filterIn(rows, lockOf(unlocked = false)) { it.sourceId to it.path }
        assertEquals(listOf("/nas/公开/a.mkv", "/nas/私人/c.mkv"), out.map { it.path })
    }

    @Test
    fun `filter 解锁后同批行保留`() {
        val rows = listOf(hist("/nas/私人/b.mkv", 7L), hist("/nas/私人/c.mkv", 7L))
        val out = NvPrivacyListFilter.filterIn(rows, lockOf(unlocked = true)) { it.sourceId to it.path }
        assertEquals(2, out.size)
    }

    @Test
    fun `filter sourceId为null恒保留_T4豁免口径`() {
        val rows = listOf(hist("/nas/私人/local_abs.mkv", sourceId = null))
        val out = NvPrivacyListFilter.filterIn(rows, lockOf(unlocked = false)) { it.sourceId to it.path }
        assertEquals(1, out.size)
    }

    @Test
    fun `filter 空表安全`() {
        assertTrue(NvPrivacyListFilter.filterIn(emptyList<WatchHistoryEntity>(), lockOf(false)) { it.sourceId to it.path }.isEmpty())
    }

    // ---------- decryptDisplay：历史行 ----------

    @Test
    fun `decryptDisplay 历史密文行还原明文形态`() {
        val c = fake()
        val ciphered = NvHistoryCipherRows.cipherRow(c, hist("/nas/私人/电影.mkv"))!!
        val plain = NvPrivacyListFilter.decryptDisplay(c, ciphered)!!
        assertEquals("/nas/私人/电影.mkv", plain.path)
        assertEquals("私人电影", plain.title)
        assertNull("展示形态不得残留 pathCipher", plain.pathCipher)
        assertEquals(123_456L, plain.positionMs); assertEquals(720_000L, plain.durationMs)
        assertEquals(42L, plain.watchedAt); assertEquals(7L, plain.sourceId)
    }

    @Test
    fun `decryptDisplay 历史明文行原样返回`() {
        val e = hist("/nas/公开/a.mkv")
        val out = NvPrivacyListFilter.decryptDisplay(fake(), e)!!
        assertEquals(e, out)
    }

    @Test
    fun `decryptDisplay 解密失败返回null_fail_closed不外露密文形态`() {
        val c = fake()
        val ciphered = NvHistoryCipherRows.cipherRow(c, hist("/nas/私人/电影.mkv"))!!
        // 密钥丢失/损坏（换机恢复）：decrypt 恒 null → 调用方按锁定滤掉，绝不显示乱码
        assertNull(NvPrivacyListFilter.decryptDisplay(fake(decryptFail = true), ciphered))
    }

    @Test
    fun `decryptDisplay 标题解不开同样fail_closed`() {
        val c = fake()
        val base = hist("/nas/私人/电影.mkv")
        val half = NvHistoryCipherRows.cipherRow(c, base)!!.copy(title = "垃圾密文")
        assertNull(NvPrivacyListFilter.decryptDisplay(c, half))
    }

    // ---------- decryptDisplay：收藏行 ----------

    @Test
    fun `decryptDisplay 收藏密文行还原_明文原样_失败null`() {
        val c = fake()
        val ciphered = NvFavoriteCipherRows.cipherRow(c, fav("/nas/私人/收藏.mkv"))!!
        val plain = NvPrivacyListFilter.decryptDisplay(c, ciphered)!!
        assertEquals("/nas/私人/收藏.mkv", plain.path)
        assertEquals("私人收藏", plain.title)
        assertNull(plain.pathCipher)
        assertEquals(9L, plain.addedAt)
        assertEquals(fav("/nas/公开/x.mkv"), NvPrivacyListFilter.decryptDisplay(c, fav("/nas/公开/x.mkv")))
        assertNull(NvPrivacyListFilter.decryptDisplay(fake(decryptFail = true), ciphered))
    }

    // ---------- 读端组合口径：解密（失败滤掉）→ 锁态过滤 ----------

    @Test
    fun `读端管线_解密失败行滤掉_锁定行滤掉_解锁后密文行明文展示`() {
        val c = fake()
        val locked = NvHistoryCipherRows.cipherRow(c, hist("/nas/私人/锁.mkv"))!!
        val broken = NvHistoryCipherRows.cipherRow(c, hist("/nas/私人/坏行.mkv"))!!
        val plain = hist("/nas/公开/明文.mkv")
        fun display(rows: List<WatchHistoryEntity>, cipher: NvPrivacyCipher): List<WatchHistoryEntity> =
            rows.mapNotNull { NvPrivacyListFilter.decryptDisplay(cipher, it) }
                .let { NvPrivacyListFilter.filterIn(it, lockOf(unlocked = false)) { e -> e.sourceId to e.path } }

        // 锁定期间：密文锁定行滤掉、解密失败行滤掉，只剩公开明文行
        assertEquals(listOf("/nas/公开/明文.mkv"), display(listOf(locked, broken, plain), c).map { it.path })
        // 解锁后：密文行解密明文展示
        val out = listOf(locked, plain)
            .mapNotNull { NvPrivacyListFilter.decryptDisplay(c, it) }
            .let { NvPrivacyListFilter.filterIn(it, lockOf(unlocked = true)) { e -> e.sourceId to e.path } }
        assertEquals(listOf("/nas/私人/锁.mkv", "/nas/公开/明文.mkv"), out.map { it.path })
    }
}
