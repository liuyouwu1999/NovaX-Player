package com.novax.app.player

import com.novax.app.data.db.ContinueWatchingRow
import com.novax.app.data.db.WatchHistoryDao
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.privacy.HistoryWriteMode
import com.novax.app.data.privacy.NvHistoryCipherRows
import com.novax.app.data.privacy.NvPrivacyCipher
import com.novax.app.data.privacy.NvPrivacyCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

class NvHistoryDirectTest {

    // ── fakes ──

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

    /** 纯 JVM 内存版 WatchHistoryDao（Room 实库不进单测）；只实现被测方法，其余即抛 */
    private class FakeDao : WatchHistoryDao {
        val rows = mutableListOf<WatchHistoryEntity>()
        override suspend fun findByKey(sourceId: Long?, path: String): WatchHistoryEntity? =
            rows.firstOrNull { it.sourceId == sourceId && it.path == path }
        override suspend fun insert(e: WatchHistoryEntity) {
            rows.removeAll { e.id != 0L && it.id == e.id }
            rows.add(if (e.id == 0L) e.copy(id = (rows.maxOfOrNull { it.id } ?: 0L) + 1) else e)
        }
        override suspend fun getForResume(sourceId: Long?, path: String) = findByKey(sourceId, path)
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override fun observeContinueWatching(): Flow<List<ContinueWatchingRow>> = emptyFlow()
        override suspend fun markWatchedToEnd(id: Long) = unsupported()
        override fun observeAllForBackfill(): Flow<List<WatchHistoryEntity>> =
            MutableStateFlow(rows.toList())
        override suspend fun hasAny(): Boolean = rows.isNotEmpty()
        override fun observeCount(): Flow<Int> = MutableStateFlow(rows.size)
        override suspend fun clearAll() { rows.clear() }
        override suspend fun allForSource(sourceId: Long): List<WatchHistoryEntity> =
            rows.filter { it.sourceId == sourceId }
        override suspend fun allForKey(sourceId: Long?): List<WatchHistoryEntity> =
            rows.filter { it.sourceId == sourceId }
        override suspend fun deleteByIds(ids: List<Long>) { rows.removeAll { it.id in ids } }
        private fun unsupported(): Nothing = throw UnsupportedOperationException("test 不触及")
    }

    private fun base(
        path: String = "/nas/私人/电影.mkv",
        title: String = "私人电影",
        sourceId: Long? = 7L,
        positionMs: Long = 600_000L,
        durationMs: Long = 720_000L,
    ) = WatchHistoryEntity(
        sourceId = sourceId, sourceType = "SMB", path = path, title = title,
        kind = "MOVIE", positionMs = positionMs, durationMs = durationMs, watchedAt = 42L,
    )

    // ── markWatchedIn ──

    @Test
    fun `PLAIN 原逻辑——明文键落库且同键覆盖不插重`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.PLAIN, base(positionMs = 10))
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.PLAIN, base(positionMs = 20))
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals("/nas/私人/电影.mkv", row.path)
        assertEquals("私人电影", row.title)
        assertNull(row.pathCipher)
        assertEquals(20L, row.positionMs)
    }

    @Test
    fun `CIPHER——密文行落hash键_明文零残留_同键覆盖`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base(positionMs = 10))
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base(positionMs = 20))
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals(c.hmac("/nas/私人/电影.mkv"), row.path)
        assertEquals("私人电影", c.decrypt(row.title))
        assertEquals("/nas/私人/电影.mkv", c.decrypt(row.pathCipher!!))
        assertEquals(20L, row.positionMs)
    }

    @Test
    fun `SKIP 不写任何行`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.SKIP, base())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `CIPHER 且加密不可用——fail-closed放弃绝不降级明文`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher(encryptFail = true)
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `CIPHER 覆盖时新duration为0保留旧值`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base(durationMs = 720_000L))
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base(positionMs = 30, durationMs = 0L))
        assertEquals(720_000L, dao.rows.single().durationMs)
    }

    @Test
    fun `传密文行形态的base——先解密回明文再按CIPHER改写_单行不插重`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base(positionMs = 100))
        val cardRow = dao.rows.single()   // 继续观看卡片流出的密文行
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, cardRow.copy(positionMs = 500_000L))
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals(c.hmac("/nas/私人/电影.mkv"), row.path)
        assertEquals(500_000L, row.positionMs)
        assertEquals("/nas/私人/电影.mkv", NvHistoryCipherRows.plainPath(c, row))
    }

    // ── markUnwatchedIn ──

    @Test
    fun `双查删——明文行与密文行都删_内存进度同清`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        // 同一内容的两种形态并存（历史遗留明文行 + 本轮密文行）
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.PLAIN, base(path = "/a.mkv", positionMs = 111))
        NvHistoryDirect.rememberProgress(7L, "/a.mkv", 111L)
        val plainId = dao.rows.single { it.path == "/a.mkv" }.id
        dao.insert(NvHistoryCipherRows.cipherRow(c, base(path = "/a.mkv", positionMs = 222))!!)
        assertEquals(2, dao.rows.size)

        NvHistoryDirect.markUnwatchedIn(dao, c, 7L, "/a.mkv")

        assertTrue("明文行应删", dao.rows.none { it.id == plainId })
        assertTrue("hash 行应删", dao.rows.isEmpty())
        assertNull("会话内进度应清", NvHistoryDirect.recallProgress(7L, "/a.mkv"))
    }

    @Test
    fun `双查删——null sourceId 键同样处理`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.PLAIN, base(sourceId = null, path = "/sdcard/a.mkv"))
        assertEquals(1, dao.rows.size)
        NvHistoryDirect.markUnwatchedIn(dao, c, null, "/sdcard/a.mkv")
        assertTrue(dao.rows.isEmpty())
    }

    // ── findForPlaybackIn 三级链 ──

    @Test
    fun `①明文行直中`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.PLAIN, base(positionMs = 333))
        val got = NvHistoryDirect.findForPlaybackIn(dao, c, 7L, "/nas/私人/电影.mkv")!!
        assertEquals(333L, got.positionMs)
        assertNull(got.pathCipher)
    }

    @Test
    fun `②hash行解密——返回明文形态`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.markWatchedIn(dao, c, HistoryWriteMode.CIPHER, base(positionMs = 444))
        val got = NvHistoryDirect.findForPlaybackIn(dao, c, 7L, "/nas/私人/电影.mkv")!!
        assertEquals("/nas/私人/电影.mkv", got.path)
        assertEquals("私人电影", got.title)
        assertNull(got.pathCipher)
        assertEquals(444L, got.positionMs)
    }

    @Test
    fun `③内存兜底——无痕内容零落盘仍有会话内续播`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        NvHistoryDirect.rememberProgress(7L, "/x.mkv", 555_000L)
        val got = NvHistoryDirect.findForPlaybackIn(dao, c, 7L, "/x.mkv")!!
        assertEquals(555_000L, got.positionMs)
        assertEquals("/x.mkv", got.path)
        assertTrue(dao.rows.isEmpty())     // 兜底行零落盘
    }

    @Test
    fun `全未中返回null_密文行解不开按未中不外露`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        val ciphered = NvHistoryCipherRows.cipherRow(c, base(positionMs = 666))!!
        dao.insert(ciphered.copy(pathCipher = NvPrivacyCrypto.CIPHER_PREFIX + "坏数据"))
        assertNull(NvHistoryDirect.findForPlaybackIn(dao, c, 7L, "/nas/私人/电影.mkv"))
        assertNull(NvHistoryDirect.findForPlaybackIn(dao, c, 7L, "/别的.mkv"))
    }

    @Test
    fun `内存兜底区分 null 与有值 sourceId`() {
        NvHistoryDirect.rememberProgress(null, "/x", 1L)
        NvHistoryDirect.rememberProgress(1L, "/x", 2L)
        assertEquals(1L, NvHistoryDirect.recallProgress(null, "/x"))
        assertEquals(2L, NvHistoryDirect.recallProgress(1L, "/x"))
        assertNull(NvHistoryDirect.recallProgress(2L, "/x"))
    }

    // ── plainBaseOf ──

    @Test
    fun `plainBaseOf——明文行原样_密文行还原_解不开为null`() {
        val c = fakeCipher()
        val plain = base()
        assertEquals(plain, NvHistoryDirect.plainBaseOf(c, plain))
        val ciphered = NvHistoryCipherRows.cipherRow(c, plain)!!
        val got = NvHistoryDirect.plainBaseOf(c, ciphered)!!
        assertEquals("/nas/私人/电影.mkv", got.path)
        assertEquals("私人电影", got.title)
        assertNull(got.pathCipher)
        val broken = ciphered.copy(pathCipher = NvPrivacyCrypto.CIPHER_PREFIX + "坏")
        assertNull(NvHistoryDirect.plainBaseOf(c, broken))
    }
}
