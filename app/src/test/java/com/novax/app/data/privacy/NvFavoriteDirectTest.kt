package com.novax.app.data.privacy

import com.novax.app.data.db.FavoriteDao
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.FavoriteWithThumb
import com.novax.app.data.db.PrivacyRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

class NvFavoriteDirectTest {

    // ── fakes（与 NvHistoryDirectTest 同款；Room 实库不进 JVM 单测）──

    private fun fakeCipher(encryptFail: Boolean = false): NvPrivacyCipher {
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

    private class FakeDao : FavoriteDao {
        val rows = mutableListOf<FavoriteEntity>()
        override suspend fun findByKey(sourceId: Long?, path: String): FavoriteEntity? =
            rows.firstOrNull { it.sourceId == sourceId && it.path == path }
        override suspend fun insert(e: FavoriteEntity) {
            rows.removeAll { e.id != 0L && it.id == e.id }
            rows.add(if (e.id == 0L) e.copy(id = (rows.maxOfOrNull { it.id } ?: 0L) + 1) else e)
        }
        override suspend fun deleteByKey(sourceId: Long?, path: String) {
            rows.removeAll { it.sourceId == sourceId && it.path == path }
        }
        override fun observeAll(): Flow<List<FavoriteEntity>> = emptyFlow()
        override fun observeAllWithThumb(): Flow<List<FavoriteWithThumb>> = emptyFlow()
        override fun observeByKey(sourceId: Long?, path: String): Flow<FavoriteEntity?> = emptyFlow()
        override fun observeByKeyEither(sourceId: Long?, path: String, hashedPath: String): Flow<FavoriteEntity?> = emptyFlow()
        override suspend fun clearAll() { rows.clear() }
        override suspend fun allForSource(sourceId: Long): List<FavoriteEntity> =
            rows.filter { it.sourceId == sourceId }
        override suspend fun allForKey(sourceId: Long?): List<FavoriteEntity> =
            rows.filter { it.sourceId == sourceId }
        override suspend fun deleteByIds(ids: List<Long>) { rows.removeAll { it.id in ids } }
    }

    private fun base(
        path: String = "/nas/私人/电影.mkv",
        title: String = "私人电影",
        sourceId: Long? = 7L,
    ) = FavoriteEntity(
        sourceId = sourceId, path = path, title = title, kind = "MOVIE", addedAt = 42L,
    )

    /** 锁规则索引：dir="/nas/私人" */
    private fun index(vararg rules: PrivacyRuleEntity): NvPrivacyIndex =
        NvPrivacyIndex(fakeCipher()).also { it.replaceAll(rules.toList()) }

    private fun lockRule(id: Long = 1L, sourceId: Long = 7L, dir: String = "/nas/私人", lock: Boolean = true, incognito: Boolean = false) =
        PrivacyRuleEntity(
            id = id, sourceId = sourceId, keyHash = "h$id",
            dirCipher = fakeCipher().encrypt(dir)!!, displayCipher = dir,
            lockEnabled = lock, incognitoEnabled = incognito, createdAt = 0L,
        )

    // ── 密文行构造/判定（与历史行同约定）──

    @Test
    fun `cipherRow 密文行约定——path为64位小写hex_title与pathCipher为可解密文_其余字段原样`() {
        val c = fakeCipher()
        val row = NvFavoriteCipherRows.cipherRow(c, base())!!
        assertEquals(c.hmac("/nas/私人/电影.mkv"), row.path)
        assertTrue(row.path.matches(Regex("[0-9a-f]{64}")))
        assertEquals("私人电影", c.decrypt(row.title))
        assertEquals("/nas/私人/电影.mkv", c.decrypt(row.pathCipher!!))
        assertNotEquals("/nas/私人/电影.mkv", row.title)
        assertEquals(7L, row.sourceId); assertEquals("MOVIE", row.kind); assertEquals(42L, row.addedAt)
    }

    @Test
    fun `isCiphered 与 plainPath 往返_解不开与明文行返回null`() {
        val c = fakeCipher()
        assertFalse(NvFavoriteCipherRows.isCiphered(base()))
        assertNull(NvFavoriteCipherRows.plainPath(c, base()))
        val row = NvFavoriteCipherRows.cipherRow(c, base())!!
        assertTrue(NvFavoriteCipherRows.isCiphered(row))
        assertEquals("/nas/私人/电影.mkv", NvFavoriteCipherRows.plainPath(c, row))
        assertNull(NvFavoriteCipherRows.plainPath(c, row.copy(pathCipher = "垃圾")))
    }

    @Test
    fun `encrypt失败fail-closed——cipherRow返回null绝不降级明文`() {
        assertNull(NvFavoriteCipherRows.cipherRow(fakeCipher(encryptFail = true), base()))
    }

    // ── cipherRequired：锁命中才加密，无痕不拦收藏 ──

    @Test
    fun `锁规则命中即密文`() {
        val idx = index(lockRule())
        assertTrue(NvFavoriteDirect.cipherRequired(idx, 7L, "/nas/私人/电影.mkv"))
        assertFalse(NvFavoriteDirect.cipherRequired(idx, 7L, "/别的/电影.mkv"))
    }

    @Test
    fun `无痕only规则不拦收藏`() {
        val idx = index(lockRule(lock = false, incognito = true))
        assertFalse(NvFavoriteDirect.cipherRequired(idx, 7L, "/nas/私人/电影.mkv"))
    }

    @Test
    fun `锁与无痕并存规则按锁口径加密`() {
        val idx = index(lockRule(lock = true, incognito = true))
        assertTrue(NvFavoriteDirect.cipherRequired(idx, 7L, "/nas/私人/电影.mkv"))
    }

    @Test
    fun `index为null_sourceId为null_跨源均不加密`() {
        val idx = index(lockRule())
        assertFalse(NvFavoriteDirect.cipherRequired(null, 7L, "/nas/私人/电影.mkv"))
        assertFalse(NvFavoriteDirect.cipherRequired(idx, null, "/nas/私人/电影.mkv"))
        assertFalse(NvFavoriteDirect.cipherRequired(idx, 9L, "/nas/私人/电影.mkv"))
    }

    // ── toggleIn：双键查重 → 查有删 / 查无插 ──

    @Test
    fun `PLAIN 往返——插明文行再toggle删除_单行`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        assertTrue(NvFavoriteDirect.toggleIn(dao, c, ciphered = false, base = base()))
        assertEquals(1, dao.rows.size)
        assertFalse(NvFavoriteDirect.toggleIn(dao, c, ciphered = false, base = base()))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `CIPHER toggle——插hash键密文行_明文零残留_三连仍单行`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        assertTrue(NvFavoriteDirect.toggleIn(dao, c, ciphered = true, base = base()))
        val row = dao.rows.single()
        assertEquals(c.hmac("/nas/私人/电影.mkv"), row.path)
        assertEquals("私人电影", c.decrypt(row.title))
        assertEquals("/nas/私人/电影.mkv", c.decrypt(row.pathCipher!!))
        assertFalse(NvFavoriteDirect.toggleIn(dao, c, ciphered = true, base = base()))
        assertTrue(dao.rows.isEmpty())
        assertTrue(NvFavoriteDirect.toggleIn(dao, c, ciphered = true, base = base()))
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `旧明文行存在时CIPHER toggle——删明文行不插重`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        dao.insert(base())   // 加锁前的遗留明文收藏
        assertFalse(NvFavoriteDirect.toggleIn(dao, c, ciphered = true, base = base()))
        assertTrue("明文遗留行应被删", dao.rows.isEmpty())
    }

    @Test
    fun `CIPHER 且加密不可用——fail-closed不写不插`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher(encryptFail = true)
        assertFalse(NvFavoriteDirect.toggleIn(dao, c, ciphered = true, base = base()))
        assertFalse(NvFavoriteDirect.addIfAbsentIn(dao, c, ciphered = true, base = base()))
        assertTrue(dao.rows.isEmpty())
    }

    // ── addIfAbsentIn：任一形态已存在即跳过 ──

    @Test
    fun `批量加入——明文行或hash行存在都跳过`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        assertTrue(NvFavoriteDirect.addIfAbsentIn(dao, c, ciphered = false, base = base()))
        assertFalse("明文行存在应跳过", NvFavoriteDirect.addIfAbsentIn(dao, c, ciphered = false, base = base()))
        assertEquals(1, dao.rows.size)
        dao.clearAll()
        dao.insert(NvFavoriteCipherRows.cipherRow(c, base())!!)
        assertFalse("hash 行存在应跳过", NvFavoriteDirect.addIfAbsentIn(dao, c, ciphered = true, base = base()))
        assertEquals(1, dao.rows.size)
    }

    // ── removeIn：hash+plain 双口径删 ──

    @Test
    fun `双查删——明文行与密文行都删`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        dao.insert(base())
        dao.insert(NvFavoriteCipherRows.cipherRow(c, base())!!)
        assertEquals(2, dao.rows.size)
        NvFavoriteDirect.removeIn(dao, c, 7L, "/nas/私人/电影.mkv")
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `双查删——null sourceId 键同样处理`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        dao.insert(base(sourceId = null, path = "/sdcard/a.mkv"))
        NvFavoriteDirect.removeIn(dao, c, null, "/sdcard/a.mkv")
        assertTrue(dao.rows.isEmpty())
    }
}
