package com.novax.app.data.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 键清单七键（前缀 nvp_）。pending_repoint 本任务只建键，Task 13/16 的重挂回传流程读写。 */
private val KEY_PIN_CIPHER = stringPreferencesKey("nvp_pin_cipher")
private val KEY_BIOMETRIC = booleanPreferencesKey("nvp_biometric")
private val KEY_FAILED_COUNT = intPreferencesKey("nvp_failed_count")
private val KEY_LOCK_UNTIL_ELAPSED = longPreferencesKey("nvp_lock_until_elapsed")
private val KEY_LOCK_BOOT = intPreferencesKey("nvp_lock_boot")
private val KEY_PENDING_WIPE = stringPreferencesKey("nvp_pending_wipe")
private val KEY_PENDING_REPOINT = longPreferencesKey("nvp_pending_repoint")

/** 自有 DataStore 文件，勿与 MainActivity 的 novax_ui_prefs 同名（同名多声明进程内会崩） */
internal val Context.nvPrivacyDataStore: DataStore<Preferences> by preferencesDataStore(name = "novax_privacy_prefs")

/**
 * PIN/冷却/指纹开关/清理回传的持久化抽象。NvPrivacyPrefs 是唯一真实现；
 * PinManager（Task 6）只依赖此接口（单测可 fake）。
 */
interface NvPinStore {
    /** 解密后的 PIN 明文（仅内存中转）；无值或解不开 → null（fail-closed，调用方按不可解处理） */
    suspend fun pinCipher(): String?

    /** 落库的原始密文（未解密）；null = 未设置。供 hasPin/pinCipherBroken 区分「未设置」与「在但解不开」，实现必须返回落库原文 */
    suspend fun pinCipherRaw(): String?

    /** null = 清除（resetAll 逐键置空口径）；非 null 落库前经 cipher.encrypt，encrypt 失败抛异常绝不写明文 */
    suspend fun setPinCipher(v: String?)

    suspend fun failedCount(): Int
    suspend fun setFailedCount(v: Int)

    /** (lockUntilElapsed, bootCountAtLock)；未设置时 (0L, 0)——真实 bootCount 恒 >=1，CooldownMath 对 boot 0 判已过期 */
    suspend fun lockUntil(): Pair<Long, Int>
    suspend fun setLockUntil(elapsed: Long, boot: Int)

    suspend fun pendingWipeIds(): List<Long>
    suspend fun setPendingWipeIds(v: List<Long>)

    val biometricEnabled: Flow<Boolean>
    suspend fun setBiometricEnabled(v: Boolean)
}

/** 冷却剩余秒数换算（纯函数）。elapsedRealtime+bootCount 双口径，防回拨系统时间绕过（spec §3.2）。 */
object CooldownMath {

    /**
     * 同 boot 且未到期 → 剩余秒（向上取整、不透负）；boot 变化（重启）或已到期 → 0。
     * boot 变化时 elapsedRealtime 从零起算，旧截止值失去意义，一律按已过期。
     */
    fun remainingSec(lockUntilElapsed: Long, bootAtLock: Int, nowElapsed: Long, nowBoot: Int): Int {
        if (bootAtLock != nowBoot) return 0
        val remainMs = lockUntilElapsed - nowElapsed
        if (remainMs <= 0) return 0
        return ((remainMs + 999) / 1000).toInt()
    }
}

/**
 * [NvPinStore] 的 DataStore 真实现（文件 novax_privacy_prefs）。
 * pinCipher 落库经 [cipher] 加密、读出解不开 → null，绝不降级明文。
 */
class NvPrivacyPrefs(context: Context, private val cipher: NvPrivacyCipher) : NvPinStore {

    private val store = context.applicationContext.nvPrivacyDataStore

    override suspend fun pinCipher(): String? = pinCipherRaw()?.let { cipher.decrypt(it) }

    override suspend fun pinCipherRaw(): String? = store.data.first()[KEY_PIN_CIPHER]

    override suspend fun setPinCipher(v: String?) {
        if (v == null) {
            store.edit { it.remove(KEY_PIN_CIPHER) }
            return
        }
        val enc = cipher.encrypt(v)
            ?: throw NvPrivacyCryptoUnavailableException("pinCipher encrypt fail-closed")
        store.edit { it[KEY_PIN_CIPHER] = enc }
    }

    override suspend fun failedCount(): Int = store.data.first()[KEY_FAILED_COUNT] ?: 0

    override suspend fun setFailedCount(v: Int) {
        store.edit { it[KEY_FAILED_COUNT] = v }
    }

    override suspend fun lockUntil(): Pair<Long, Int> {
        val p = store.data.first()
        return Pair(p[KEY_LOCK_UNTIL_ELAPSED] ?: 0L, p[KEY_LOCK_BOOT] ?: 0)
    }

    override suspend fun setLockUntil(elapsed: Long, boot: Int) {
        store.edit { it[KEY_LOCK_UNTIL_ELAPSED] = elapsed; it[KEY_LOCK_BOOT] = boot }
    }

    override suspend fun pendingWipeIds(): List<Long> =
        (store.data.first()[KEY_PENDING_WIPE] ?: "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }

    override suspend fun setPendingWipeIds(v: List<Long>) {
        store.edit {
            if (v.isEmpty()) it.remove(KEY_PENDING_WIPE) else it[KEY_PENDING_WIPE] = v.joinToString(",")
        }
    }

    override val biometricEnabled: Flow<Boolean> = store.data.map { it[KEY_BIOMETRIC] ?: false }

    override suspend fun setBiometricEnabled(v: Boolean) {
        store.edit { it[KEY_BIOMETRIC] = v }
    }

    /** Task 16「重挂到…」暂存 ruleId → Task 13「挂载到此处」完成回传后清除；null = 清除。刻意不进 [NvPinStore]（属规则重挂流程，与 PIN 生命周期无关）。 */
    suspend fun pendingRepointRuleId(): Long? = store.data.first()[KEY_PENDING_REPOINT]

    suspend fun setPendingRepointRuleId(v: Long?) {
        store.edit { if (v == null) it.remove(KEY_PENDING_REPOINT) else it[KEY_PENDING_REPOINT] = v }
    }
}
