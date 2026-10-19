package com.novax.app.data.privacy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 隐私子系统的加密边界。与 [com.novax.app.data.NvSecrets] 的关键差异是 fail-closed：
 * Keystore 不可用或密文解不开一律返回 null（调用方按"不可解"处理），绝不降级明文；
 * hmac 失败抛 [NvPrivacyCryptoUnavailableException]。所有依赖方通过接口注入使用，
 * 单测用 javax.crypto 固定密钥的 fake（见 NvPrivacyCryptoTest）。
 */
interface NvPrivacyCipher {
    /** null = fail-closed（Keystore 不可用），调用方不得把 null 当空串/明文落库 */
    fun encrypt(plain: String): String?

    /** null = 解不开（密钥丢失/密文损坏/前缀不符），调用方必须按不可解处理而非透传原文 */
    fun decrypt(cipherText: String): String?

    /** HmacSHA256，64 位小写 hex；不可用抛 [NvPrivacyCryptoUnavailableException] */
    fun hmac(plain: String): String
}

class NvPrivacyCryptoUnavailableException(message: String) : IllegalStateException(message)

object NvPrivacyCrypto {

    /**
     * 密文格式 `nvpenc1:iv_b64:ct_b64`。与 NvSecrets 的 `nvenc1:` 前缀刻意不同，
     * 防止两套密钥体系下的密文被对方误认（后者会对无前缀旧值做明文透传）。
     */
    const val CIPHER_PREFIX = "nvpenc1:"

    fun keystoreCipher(): NvPrivacyCipher = KeystoreCipher
}

/** Android Keystore 实现：AES-256-GCM（novax_privacy_enc）+ HmacSHA256（novax_privacy_mac） */
private object KeystoreCipher : NvPrivacyCipher {

    private const val TAG = "NvPrivacyCrypto"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ENC_ALIAS = "novax_privacy_enc"
    private const val MAC_ALIAS = "novax_privacy_mac"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    override fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 两参 init 自动生成随机 IV，随后随密文一起落库（同 NvSecrets 形态）
        cipher.init(Cipher.ENCRYPT_MODE, encKey())
        NvPrivacyCrypto.CIPHER_PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "NvPrivacy encrypt fail-closed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()

    override fun decrypt(cipherText: String): String? = runCatching {
        require(cipherText.startsWith(NvPrivacyCrypto.CIPHER_PREFIX))
        val parts = cipherText.removePrefix(NvPrivacyCrypto.CIPHER_PREFIX).split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "NvPrivacy decrypt fail-closed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()

    override fun hmac(plain: String): String = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey())
        mac.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        // 只抛异常类型与消息，绝不带明文
        throw NvPrivacyCryptoUnavailableException("hmac unavailable: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun encKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ENC_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ENC_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun macKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(MAC_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(MAC_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKey()
    }
}
