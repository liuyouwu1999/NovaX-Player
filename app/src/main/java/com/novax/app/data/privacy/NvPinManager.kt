package com.novax.app.data.privacy

import android.util.Log
import com.novax.app.data.db.MediaDb
import java.security.MessageDigest

/** PIN 验证结果四态。Broken = PIN 密文在但解不开（或未设置时被误调），fail-closed，不进错误计数。 */
sealed class PinResult {
    object Ok : PinResult()
    /** 本次错误新触发的冷却秒数（0 = 未达冷却阈值） */
    data class Wrong(val cooldownSec: Int) : PinResult()
    /** 冷却中，剩余秒数（恒定时间比较之前就拦截，正确 PIN 也要等） */
    data class Cooldown(val remainingSec: Int) : PinResult()
    object Broken : PinResult()
}

/**
 * PIN 生命周期：setup / verify / change / resetAll。
 * 错误计数恰达 5/10 次 → 冷却 30/300 秒，≥15 次每次错饱和冷却 1800 秒（阶梯末端不免费，
 * 防「付一次冷却后全速穷举」）；阈值之间只计数不锁。冷却按 elapsedRealtime+bootCount
 * 双口径换算（[CooldownMath]，防回拨系统时间绕过）。
 */
class NvPinManager(
    private val store: NvPinStore,
    private val cipher: NvPrivacyCipher,
    private val clock: NvMonotonicClock,
    /** 重置时的真抹除管道，默认空实现；Task 8 在 NvPrivacyAccess 初始化处接为真实现 */
    private val resetAllWipe: suspend (MediaDb) -> Unit = {},
) {

    companion object {
        private const val TAG = "NvPrivacyPin"
        private val PIN_FORMAT = Regex("""^\d{4}$""")
        private const val SEC_MS = 1000L

        fun isValidPin(pin: String): Boolean = PIN_FORMAT.matches(pin)

        /**
         * 错误次数 → 本次新触发的冷却秒。5/10 恰触发（阈值之间 Wrong(0) 继续累计），
         * ≥15 饱和 1800——阶梯末端不免费，否则 16 次后冷却机制整体失效可全速穷举。
         */
        internal fun cooldownSecFor(failedCount: Int): Int = when {
            failedCount >= 15 -> 1800
            failedCount == 10 -> 300
            failedCount == 5 -> 30
            else -> 0
        }
    }

    /**
     * 是否设过 PIN。按落库原文判定：密文解不开（Keystore 密钥丢失）也视作已设——
     * 绝不降级成「未设置」，否则攻击路径变成「丢密钥后直接 setup 新 PIN 绕过验证」。
     */
    suspend fun hasPin(): Boolean = store.pinCipherRaw() != null

    /** store 有 pinCipher 但解不开（Keystore 密钥丢失/密文损坏）；未设置 → false */
    suspend fun pinCipherBroken(): Boolean {
        val raw = store.pinCipherRaw() ?: return false
        return cipher.decrypt(raw) == null
    }

    /**
     * 设置 PIN（`^\d{4}$`）。加密失败（[NvPrivacyCryptoUnavailableException]）返回 false，
     * 不写半态；成功后顺带清历史错误计数与冷却锁。
     */
    suspend fun setup(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        return try {
            store.setPinCipher(pin)
            store.setFailedCount(0)
            store.setLockUntil(0L, 0)
            true
        } catch (e: NvPrivacyCryptoUnavailableException) {
            Log.w(TAG, "NvPrivacy setup fail-closed: ${e.message}")
            false
        }
    }

    /**
     * 验证 PIN。调用方约束：仅在 [hasPin] 为 true 时调用——未设置时本实现同样返回
     * [PinResult.Broken]（fail-closed，调用方不得把 Broken 当「未设置」引导去 setup）。
     * 冷却中恒返回 [PinResult.Cooldown]（正确 PIN 也等冷却走完，且不计数）；
     * 密文解不开 → [PinResult.Broken]（不进错误计数）。
     */
    suspend fun verify(pin: String): PinResult {
        val raw = store.pinCipherRaw() ?: return PinResult.Broken
        val reference = cipher.decrypt(raw) ?: return PinResult.Broken
        val nowElapsed = clock.elapsedRealtime()
        val nowBoot = clock.bootCount()
        val (untilElapsed, bootAtLock) = store.lockUntil()
        val remaining = CooldownMath.remainingSec(untilElapsed, bootAtLock, nowElapsed, nowBoot)
        if (remaining > 0) return PinResult.Cooldown(remaining)
        val matched = try {
            // 恒定时间比较两侧 hmac（等长），防时序侧信道与长度泄露
            MessageDigest.isEqual(
                cipher.hmac(reference).toByteArray(),
                cipher.hmac(pin).toByteArray(),
            )
        } catch (e: NvPrivacyCryptoUnavailableException) {
            Log.w(TAG, "NvPrivacy verify fail-closed: ${e.message}")
            return PinResult.Broken
        }
        return if (matched) {
            store.setFailedCount(0)
            store.setLockUntil(0L, 0)
            PinResult.Ok
        } else {
            val count = store.failedCount() + 1
            store.setFailedCount(count)
            val cooldownSec = cooldownSecFor(count)
            if (cooldownSec > 0) store.setLockUntil(nowElapsed + cooldownSec * SEC_MS, nowBoot)
            PinResult.Wrong(cooldownSec)
        }
    }

    /**
     * 改 PIN。新 PIN 格式先验（非法直接 false，旧 PIN 记错不白增计数）；
     * 旧 PIN 验证必须 Ok；新 PIN 加密失败返回 false 且旧 PIN 原样。
     */
    suspend fun change(old: String, new: String): Boolean {
        if (!isValidPin(new)) return false
        if (verify(old) != PinResult.Ok) return false
        return try {
            store.setPinCipher(new)
            true
        } catch (e: NvPrivacyCryptoUnavailableException) {
            Log.w(TAG, "NvPrivacy change fail-closed: ${e.message}")
            false
        }
    }

    /** 忘记密码重置：NvPinStore 逐键置空 + [resetAllWipe]（Task 8 接真实现） */
    suspend fun resetAll(db: MediaDb) {
        store.setPinCipher(null)
        store.setFailedCount(0)
        store.setLockUntil(0L, 0)
        store.setPendingWipeIds(emptyList())
        store.setBiometricEnabled(false)
        resetAllWipe(db)
    }
}
