package com.novax.app.data.privacy

import com.novax.app.data.db.MediaDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*; import org.junit.Test
import java.io.File
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.*

/**
 * 纯 JVM（fake store + fake cipher + 可拨动 fake clock，Android 框架类不进单测，见全局约束）：
 * Task 6 简报 Step 1 验收线——验证/递增冷却/Broken 不计数/setup fail-closed/change/resetAll。
 */
class NvPinManagerTest {

    // ---- fake：javax.crypto AES-GCM 固定密钥加解密 + 真 HMAC（同 NvPrivacyIndexTest 形态） ----
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

    /** Keystore 不可用形态：encrypt 恒 null（真实现据此抛 NvPrivacyCryptoUnavailableException）、decrypt null */
    private fun brokenEncryptCipher() = object : NvPrivacyCipher {
        override fun encrypt(plain: String): String? = null
        override fun decrypt(cipherText: String): String? = null
        override fun hmac(plain: String): String = throw NvPrivacyCryptoUnavailableException("mac unavailable")
    }

    /** setPinCipher 模拟真实现口径：encrypt 失败抛异常，绝不写明文（T3 事实） */
    private class FakeStore(private val cipher: NvPrivacyCipher) : NvPinStore {
        var rawPin: String? = null
        var failed = 0
        var lock: Pair<Long, Int> = 0L to 0
        var pendingWipe: List<Long> = emptyList()
        override suspend fun pinCipher(): String? = rawPin?.let { cipher.decrypt(it) }
        override suspend fun pinCipherRaw(): String? = rawPin
        override suspend fun setPinCipher(v: String?) {
            if (v == null) { rawPin = null; return }
            rawPin = cipher.encrypt(v) ?: throw NvPrivacyCryptoUnavailableException("pinCipher encrypt fail-closed")
        }
        override suspend fun failedCount(): Int = failed
        override suspend fun setFailedCount(v: Int) { failed = v }
        override suspend fun lockUntil(): Pair<Long, Int> = lock
        override suspend fun setLockUntil(elapsed: Long, boot: Int) { lock = elapsed to boot }
        override suspend fun pendingWipeIds(): List<Long> = pendingWipe
        override suspend fun setPendingWipeIds(v: List<Long>) { pendingWipe = v }
        val biometricFlow = MutableStateFlow(false)
        override val biometricEnabled: kotlinx.coroutines.flow.Flow<Boolean> = biometricFlow
        override suspend fun setBiometricEnabled(v: Boolean) { biometricFlow.value = v }
    }

    /** 可拨动的单调时钟 */
    private class FakeClock(var elapsed: Long = 100_000L, var boot: Int = 3) : NvMonotonicClock {
        override fun elapsedRealtime(): Long = elapsed
        override fun bootCount(): Int = boot
    }

    private inner class Fixtures(cipher: NvPrivacyCipher? = null) {
        private val c = cipher ?: fakeCipher()   // inner 类默认参不能引外部接收者，落进 body 解析
        val cipher: NvPrivacyCipher get() = c
        val store = FakeStore(c)
        val clock = FakeClock()
        var wiped = false
        val manager = NvPinManager(store, c, clock) { _ -> wiped = true }
    }

    // ---- 验证主链路 ----

    /** MediaDb 是 Room 抽象类（实库不进单测）；泛型擦除塞 null，只验 resetAll 不解引用 db */
    @Suppress("UNCHECKED_CAST")
    private fun <T> uncheckedNull(): T = null as T

    @Test fun `verify_正确PIN返回Ok并清错误计数`() = runBlocking {
        val f = Fixtures()
        assertTrue(f.manager.setup("1234"))
        repeat(3) { assertEquals(PinResult.Wrong(0), f.manager.verify("0000")) }
        assertEquals(3, f.store.failed)
        assertEquals(PinResult.Ok, f.manager.verify("1234"))
        assertEquals(0, f.store.failed)          // 成功清计数
        assertEquals(PinResult.Wrong(0), f.manager.verify("0000"))  // 计数确已归零而非沿用
    }

    @Test fun `错1到4次_Wrong0不冷却`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        repeat(4) { n -> assertEquals(PinResult.Wrong(0), f.manager.verify("0000")); assertEquals(n + 1, f.store.failed) }
        assertEquals(0L to 0, f.store.lock)      // 未到阈值不落锁
    }

    @Test fun `第5次错_Wrong30_随后Cooldown30且冷却中不计数`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        repeat(4) { f.manager.verify("0000") }
        assertEquals(PinResult.Wrong(30), f.manager.verify("0000"))
        assertEquals(100_000L + 30_000 to 3, f.store.lock)   // 以当时钟落锁截止
        assertEquals(5, f.store.failed)
        assertEquals(PinResult.Cooldown(30), f.manager.verify("9999"))  // 冷却中
        assertEquals(PinResult.Cooldown(30), f.manager.verify("1234"))  // 冷却中连正确 PIN 也要等
        assertEquals(5, f.store.failed)          // 冷却期间错误不进计数
    }

    @Test fun `冷却走完_正确PIN_Ok_错误继续累计`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        repeat(5) { f.manager.verify("0000") }
        f.clock.elapsed += 31_000                // 钟走够 30 秒冷却
        assertEquals(PinResult.Wrong(0), f.manager.verify("9999"))  // 冷却已过、错 6 次（<10 不再冷却）
        assertEquals(6, f.store.failed)
        assertEquals(PinResult.Ok, f.manager.verify("1234"))
    }

    @Test fun `错到10次_冷却300`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        repeat(5) { f.manager.verify("0000") }
        f.clock.elapsed += 31_000
        repeat(4) { assertEquals(PinResult.Wrong(0), f.manager.verify("0000")) }   // 6~9 次
        assertEquals(PinResult.Wrong(300), f.manager.verify("0000"))               // 第 10 次
        assertEquals(131_000L + 300_000 to 3, f.store.lock)
        assertEquals(PinResult.Cooldown(300), f.manager.verify("0000"))
    }

    @Test fun `错到15次_冷却1800_16次起每次错饱和续1800`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        repeat(5) { f.manager.verify("0000") }
        f.clock.elapsed += 31_000
        repeat(5) { f.manager.verify("0000") }                                     // 6~10 次
        f.clock.elapsed += 301_000
        repeat(4) { assertEquals(PinResult.Wrong(0), f.manager.verify("0000")) }   // 11~14 次
        assertEquals(PinResult.Wrong(1800), f.manager.verify("0000"))              // 第 15 次
        assertEquals(PinResult.Cooldown(1800), f.manager.verify("0000"))
        f.clock.elapsed += 1_801_000
        assertEquals(PinResult.Wrong(1800), f.manager.verify("0000"))              // 16 次起饱和续 1800
        assertEquals(PinResult.Cooldown(1800), f.manager.verify("0000"))
    }

    @Test fun `boot变化_冷却失效_可正常验证`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        repeat(5) { f.manager.verify("0000") }
        assertEquals(PinResult.Cooldown(30), f.manager.verify("1234"))
        f.clock.boot = 4                         // 重启：旧截止作废
        assertEquals(PinResult.Ok, f.manager.verify("1234"))
    }

    @Test fun `冷却秒数换算阈值表_5和10恰触发_15起饱和`() {
        assertEquals(0, NvPinManager.cooldownSecFor(4))
        assertEquals(30, NvPinManager.cooldownSecFor(5))
        assertEquals(0, NvPinManager.cooldownSecFor(6))
        assertEquals(0, NvPinManager.cooldownSecFor(9))
        assertEquals(300, NvPinManager.cooldownSecFor(10))
        assertEquals(0, NvPinManager.cooldownSecFor(14))
        assertEquals(1800, NvPinManager.cooldownSecFor(15))
        assertEquals(1800, NvPinManager.cooldownSecFor(16))
        assertEquals(1800, NvPinManager.cooldownSecFor(9999))
    }

    // ---- Broken：解不开不进错误计数 ----

    @Test fun `密文解不开_Broken且不进错误计数`() = runBlocking {
        val f = Fixtures()
        f.store.rawPin = "垃圾密文"               // 模拟 Keystore 密钥丢失后落库值解不开
        assertEquals(PinResult.Broken, f.manager.verify("1234"))
        assertEquals(0, f.store.failed)          // 不进错误计数
        assertTrue(f.manager.pinCipherBroken())
        assertTrue(f.manager.hasPin())
    }

    @Test fun `未设置PIN_verify返回Broken_调用方约束先hasPin`() = runBlocking {
        val f = Fixtures()
        assertFalse(f.manager.hasPin())
        assertEquals(PinResult.Broken, f.manager.verify("1234"))
        assertFalse(f.manager.pinCipherBroken())
    }

    @Test fun `mac密钥不可用_verify转Broken且不进错误计数`() = runBlocking {
        // 专覆盖 verify 的 hmac fail-closed catch 分支：decrypt 正常但 hmac 抛异常
        // （brokenEncryptCipher 走 decrypt-null 提前返回，到不了该分支）
        val c = fakeCipher()
        val noMac = object : NvPrivacyCipher {
            override fun encrypt(plain: String): String? = c.encrypt(plain)
            override fun decrypt(cipherText: String): String? = c.decrypt(cipherText)
            override fun hmac(plain: String): String = throw NvPrivacyCryptoUnavailableException("mac unavailable")
        }
        val store = FakeStore(noMac)
        val m = NvPinManager(store, noMac, FakeClock())
        assertTrue(m.setup("1234"))                        // encrypt 可用，能正常设 PIN
        assertEquals(PinResult.Broken, m.verify("1234"))   // 正确 PIN 也 Broken（无法安全比对）
        assertEquals(PinResult.Broken, m.verify("0000"))
        assertEquals(0, store.failed)                      // 不进错误计数
        assertFalse(m.pinCipherBroken())                   // 密文本体解得开，非「密文损坏」态
    }

    @Test fun `pinCipherBroken_三态`() = runBlocking {
        val f = Fixtures()
        assertFalse(f.manager.pinCipherBroken())                 // 未设置
        f.store.rawPin = f.cipher.encrypt("1234")
        assertFalse(f.manager.pinCipherBroken())                 // 正常
        f.store.rawPin = "垃圾密文"
        assertTrue(f.manager.pinCipherBroken())                  // 在但解不开
    }

    // ---- setup ----

    @Test fun `setup_格式校验_非4位数字一律false`() = runBlocking {
        val f = Fixtures()
        for (bad in listOf("", "123", "12345", "12a4", "12 4", "１２３４")) {
            assertFalse("bad=$bad", f.manager.setup(bad))
        }
        assertFalse(f.manager.hasPin())
    }

    @Test fun `setup_加密失败返回false且不写半态`() = runBlocking {
        val f = Fixtures(brokenEncryptCipher())
        assertFalse(f.manager.setup("1234"))
        assertNull(f.store.rawPin)               // fail-closed：不留半态
        assertFalse(f.manager.hasPin())
    }

    @Test fun `setup成功_hasPin并清历史计数与锁`() = runBlocking {
        val f = Fixtures()
        f.store.failed = 7
        f.store.lock = 999_999L to 3
        assertTrue(f.manager.setup("1234"))
        assertTrue(f.manager.hasPin())
        assertEquals(0, f.store.failed)
        assertEquals(0L to 0, f.store.lock)
    }

    // ---- change ----

    @Test fun `change_旧PIN错_false旧PIN仍有效`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1111")
        assertFalse(f.manager.change("9999", "2222"))
        assertEquals(1, f.store.failed)                          // 旧 PIN 记错进了一次计数
        assertEquals(PinResult.Ok, f.manager.verify("1111"))     // 旧 PIN 未被动过
    }

    @Test fun `change_新PIN格式非法_false且不动旧PIN不白增计数`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1111")
        assertFalse(f.manager.change("9999", "22"))
        assertEquals(0, f.store.failed)          // 新格式先验，旧 PIN 记错不白进计数
        assertFalse(f.manager.change("9999", "abcd"))
        assertEquals(0, f.store.failed)
    }

    @Test fun `change_成功_新PIN可验旧PIN失效`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1111")
        assertTrue(f.manager.change("1111", "2222"))
        assertEquals(PinResult.Ok, f.manager.verify("2222"))
        assertEquals(PinResult.Wrong(0), f.manager.verify("1111"))
    }

    @Test fun `change_新PIN加密失败_false旧PIN仍在`() = runBlocking {
        val c = fakeCipher()
        val flaky = object : NvPrivacyCipher {
            var encryptEnabled = true
            override fun encrypt(plain: String): String? = if (encryptEnabled) c.encrypt(plain) else null
            override fun decrypt(cipherText: String): String? = c.decrypt(cipherText)
            override fun hmac(plain: String): String = c.hmac(plain)
        }
        val store = FakeStore(flaky)
        val clock = FakeClock()
        val m = NvPinManager(store, flaky, clock)
        assertTrue(m.setup("1111"))
        flaky.encryptEnabled = false             // Keystore 此刻不可用
        assertFalse(m.change("1111", "2222"))
        assertEquals(PinResult.Ok, m.verify("1111"))   // 旧 PIN 原样
    }

    // ---- resetAll ----

    @Test fun `resetAll_逐键置空并调用wipe`() = runBlocking {
        val f = Fixtures()
        f.manager.setup("1234")
        f.store.failed = 3
        f.store.lock = 500_000L to 3
        f.store.pendingWipe = listOf(1L, 2L)
        f.store.setBiometricEnabled(true)
        assertTrue(f.manager.hasPin())
        f.manager.resetAll(uncheckedNull())      // 默认 wipe 管道参数，fake 只记调用
        assertTrue(f.wiped)
        assertFalse(f.manager.hasPin())
        assertNull(f.store.rawPin)
        assertEquals(0, f.store.failed)
        assertEquals(0L to 0, f.store.lock)
        assertTrue(f.store.pendingWipe.isEmpty())
        assertFalse(f.store.biometricFlow.value)
    }

    @Test fun `resetAll_未设PIN时也安全`() = runBlocking {
        val f = Fixtures()
        f.manager.resetAll(uncheckedNull())
        assertTrue(f.wiped)
        assertFalse(f.manager.hasPin())
    }

    // ---- 防退化：恒定时间比较必须走 MessageDigest.isEqual ----

    @Test fun `实现源码含MessageDigestisEqual_防退化成普通等号比较`() {
        val candidates = listOf(
            File("src/main/java/com/novax/app/data/privacy/NvPinManager.kt"),
            File("app/src/main/java/com/novax/app/data/privacy/NvPinManager.kt"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到 NvPinManager.kt 源文件：$candidates")
        val text = file.readText()
        assertTrue("verify 必须用 MessageDigest.isEqual 做恒定时间比较", text.contains("MessageDigest.isEqual"))
    }
}
