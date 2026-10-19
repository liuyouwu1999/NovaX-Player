package com.novax.app.player

import com.novax.app.data.db.ContinueWatchingRow
import com.novax.app.data.db.WatchHistoryDao
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.privacy.NvPrivacyCipher
import com.novax.app.data.privacy.NvPrivacyCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

/**
 * 「已看完从头播」口径（replay 归零）：findForPlayback 解析出的起播行若命中
 * durationMs > 0 且 positionMs >= durationMs * 0.95（与继续观看 <95% 未看完同口径，
 * markWatchedToEnd 落 positionMs=durationMs 同命中）→ 返回行 positionMs=0，
 * 其余字段原样；**落库历史行不改写**（看完状态/watched 徽标不消失）。
 * 内存进度（recallProgress）命中同规则；duration 未知（0）不触发。
 */
class NvHistoryDirectReplayTest {

    // ── fakes（与 NvHistoryDirectTest 同款形态）──

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

    private fun base(positionMs: Long, durationMs: Long) = WatchHistoryEntity(
        sourceId = 7L, sourceType = "SMB", path = "/nas/剧/S01E01.mkv", title = "第1集",
        kind = "EPISODE", positionMs = positionMs, durationMs = durationMs, watchedAt = 42L,
    )

    // ── 历史行（明文 ①）──

    @Test
    fun `positionMs等于duration——已看完归零`() = runBlocking {
        val dao = FakeDao(); dao.insert(base(720_000L, 720_000L))
        val got = NvHistoryDirect.findForPlaybackIn(dao, fakeCipher(), 7L, "/nas/剧/S01E01.mkv")!!
        assertEquals(0L, got.positionMs)
        assertEquals(720_000L, got.durationMs)   // 其余字段原样
        assertEquals("第1集", got.title)
    }

    @Test
    fun `positionMs恰达95%阈值——归零`() = runBlocking {
        val dao = FakeDao(); dao.insert(base(684_000L, 720_000L))   // 0.95 * 720_000
        assertEquals(0L, NvHistoryDirect.findForPlaybackIn(dao, fakeCipher(), 7L, "/nas/剧/S01E01.mkv")!!.positionMs)
    }

    @Test
    fun `positionMs未达95%——原样返回`() = runBlocking {
        val dao = FakeDao(); dao.insert(base(683_999L, 720_000L))
        assertEquals(
            683_999L,
            NvHistoryDirect.findForPlaybackIn(dao, fakeCipher(), 7L, "/nas/剧/S01E01.mkv")!!.positionMs,
        )
    }

    @Test
    fun `duration未知0——不触发归零`() = runBlocking {
        val dao = FakeDao(); dao.insert(base(999_999L, 0L))
        assertEquals(
            999_999L,
            NvHistoryDirect.findForPlaybackIn(dao, fakeCipher(), 7L, "/nas/剧/S01E01.mkv")!!.positionMs,
        )
    }

    @Test
    fun `positionMs本来就为0——不特殊处理`() = runBlocking {
        val dao = FakeDao(); dao.insert(base(0L, 720_000L))
        assertEquals(0L, NvHistoryDirect.findForPlaybackIn(dao, fakeCipher(), 7L, "/nas/剧/S01E01.mkv")!!.positionMs)
    }

    // ── 历史行不改写 ──

    @Test
    fun `落库行保持原positionMs——只改返回副本`() = runBlocking {
        val dao = FakeDao(); dao.insert(base(720_000L, 720_000L))
        NvHistoryDirect.findForPlaybackIn(dao, fakeCipher(), 7L, "/nas/剧/S01E01.mkv")
        assertEquals("看完状态不被破坏", 720_000L, dao.rows.single().positionMs)
    }

    // ── 密文行（②）──

    @Test
    fun `密文行已看完——解密后归零`() = runBlocking {
        val dao = FakeDao(); val c = fakeCipher()
        dao.insert(com.novax.app.data.privacy.NvHistoryCipherRows.cipherRow(c, base(720_000L, 720_000L))!!)
        val got = NvHistoryDirect.findForPlaybackIn(dao, c, 7L, "/nas/剧/S01E01.mkv")!!
        assertEquals(0L, got.positionMs)
        assertEquals("/nas/剧/S01E01.mkv", got.path)   // 明文形态不破坏
        assertEquals(720_000L, dao.rows.single().positionMs)   // 落库行不改写
    }

    // ── 会话内内存进度（③）──

    @Test
    fun `内存进度已看完——归零`() = runBlocking {
        NvHistoryDirect.rememberProgress(7L, "/x.mkv", 720_000L, 720_000L)
        val got = NvHistoryDirect.findForPlaybackIn(FakeDao(), fakeCipher(), 7L, "/x.mkv")!!
        assertEquals(0L, got.positionMs)
    }

    @Test
    fun `内存进度未达95%——原样`() = runBlocking {
        NvHistoryDirect.rememberProgress(7L, "/x.mkv", 100_000L, 720_000L)
        assertEquals(
            100_000L,
            NvHistoryDirect.findForPlaybackIn(FakeDao(), fakeCipher(), 7L, "/x.mkv")!!.positionMs,
        )
    }

    @Test
    fun `内存进度时长未知——不触发归零`() = runBlocking {
        NvHistoryDirect.rememberProgress(7L, "/x.mkv", 720_000L)   // 旧三参形态：duration=0
        assertEquals(
            720_000L,
            NvHistoryDirect.findForPlaybackIn(FakeDao(), fakeCipher(), 7L, "/x.mkv")!!.positionMs,
        )
    }

    @Test
    fun `recallProgress仍返回原始位置——归零只在播放链`() {
        NvHistoryDirect.rememberProgress(7L, "/x.mkv", 720_000L, 720_000L)
        assertEquals(720_000L, NvHistoryDirect.recallProgress(7L, "/x.mkv"))
    }

    @Test
    fun `watchedToEnd判定口径`() {
        assertTrue(NvHistoryDirect.watchedToEnd(720_000L, 720_000L))
        assertTrue(NvHistoryDirect.watchedToEnd(684_000L, 720_000L))
        assertFalse(NvHistoryDirect.watchedToEnd(683_999L, 720_000L))
        assertFalse(NvHistoryDirect.watchedToEnd(999_999L, 0L))
        assertFalse(NvHistoryDirect.watchedToEnd(0L, 720_000L))
    }
}
