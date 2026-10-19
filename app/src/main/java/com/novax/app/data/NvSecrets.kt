package com.novax.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NAS 口令静态加密（2026-09-16 审计 P2-13）：Room 里 sources.password 不再存明文。
 * - 写入：AES-256-GCM（Android Keystore 硬件密钥 `novax_src_pwd`，不可导出），密文带
 *   [PREFIX] 前缀 + 随机 IV；
 * - 读取：带前缀才解密；无前缀的旧行按历史明文原样透传（零迁移成本，用户无感升级）；
 * - Keystore 异常（极少数 ROM）时降级为明文存取——与旧行为一致，绝不因加密失败阻断加源。
 */
object NvSecrets {

    private const val TAG = "NvSecrets"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "novax_src_pwd"
    private const val PREFIX = "nvenc1:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** 存库前调用：成功返回 "nvenc1:iv:ct"，Keystore 不可用时原样返回（降级明文） */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // 两参 init 自动生成随机 IV，随后 cipher.iv 取出随密文落库
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "encrypt fallback to plain: ${it.message}") }
            .getOrDefault(plain)
    }

    /** 读库后调用：带 [PREFIX] 才解密，否则视为历史明文原样返回 */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "decrypt failed, fallback empty: ${it.message}") }
            .getOrDefault("")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
