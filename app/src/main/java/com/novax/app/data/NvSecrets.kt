package com.novax.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NAS 口令静态加密（2026-09-16 审计 P2-13）：Room 里 sources.password 不再存明文。
 * - 写入：AES-256-GCM（Android Keystore 硬件密钥 `novax_src_pwd`，不可导出），密文带
 *   [PREFIX] 前缀 + 随机 IV；落库路径 [encryptOrNull] 在 Keystore 异常时返回 null——调用方
 *   必须让用户知情选择，禁止静默降级明文落库（2026-11-01 机构整改 spec §2）；[encrypt]
 *   保留为连接/浏览临时实体专用（降级明文），落库禁用；
 * - 读取：带前缀才解密；无前缀旧行按历史明文原样透传（零迁移成本，用户无感升级）。
 *   认证路径用 [decrypt]（解密失败降级空串——只影响本次认证，无回写通道）；**编辑回显
 *   必须用 [decryptOrNull]**（null=解不开），杜绝「空串当口令覆盖原列」的静默清空
 *   （2026-11-01 机构整改 §2 新发现破坏通道）；
 * - [keyFactory] 为 JVM 单测注入缝（软件密钥/强制失败），产品代码不得改写；
 * - Base64 用 java.util（与 android.util.Base64 NO_WRAP 逐字节同格式）：JVM 单测可跑
 *   （android.util.Base64 在 JVM 抛 not mocked），存量密文行零影响。
 */
object NvSecrets {

    private const val TAG = "NvSecrets"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "novax_src_pwd"
    private const val PREFIX = "nvenc1:"
    private const val GCM_TAG_BITS = 128

    /** 密钥供给缝：默认 Android Keystore；JVM 单测注入软件密钥或强制异常 */
    internal var keyFactory: () -> SecretKey = { androidKeystoreKey() }

    /** 存库前调用：成功返回 "nvenc1:iv:ct"；Keystore 异常返回 null（不静默降级，见类 kdoc） */
    fun encryptOrNull(plain: String): String? {
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // 两参 init 自动生成随机 IV，随后 cipher.iv 取出随密文落库
            cipher.init(Cipher.ENCRYPT_MODE, keyFactory())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(ct)
        }.onFailure { Log.w(TAG, "encrypt failed (fail-closed): ${it.message}") }
            .getOrNull()
    }

    /** 连接/浏览临时实体专用（非落库）：Keystore 异常降级明文——保住失效期测连/换密码自救
     *  通道（spec §2.2-1 实施修订 v2）。落库一律走 [encryptOrNull]+知情确认 */
    fun encrypt(plain: String): String = encryptOrNull(plain) ?: plain

    /** 认证路径读取：解密失败降级空串（本次认证自然失败）。编辑回显禁用本函数，用 [decryptOrNull] */
    fun decrypt(stored: String): String = decryptOrNull(stored) ?: ""

    /** 三态读取：历史明文/空串原样返回；解不开（密钥丢失等）返回 null——编辑回显专用 */
    fun decryptOrNull(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyFactory(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(parts[0])),
            )
            String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "decrypt failed: ${it.message}") }
            .getOrNull()
    }

    private fun androidKeystoreKey(): SecretKey {
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
