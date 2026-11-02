package com.novax.app.ui.files

/**
 * 口令落库三分辨（2026-11-01 机构整改 spec §2，D1 用户拍板）：
 * - [PassResolution.KeepExisting]：编辑回显解不开原口令（Keystore 异常）且用户未重输——
 *   沿用原列密文，杜绝「空串当口令覆盖原列」的静默清空；
 * - [PassResolution.Empty]：匿名/空口令；
 * - [PassResolution.NeedsEncrypt]：需加密的明文——Keystore 健康落密文；异常走知情确认。
 * 纯函数无 Android 依赖（对齐 SourceEditPolicy 惯例）。
 */
internal object SourceSavePolicy {

    /** 与 NvSecrets 实际密文前缀的一致性由 NvSecretsTest.plainPrefix_matchesNvSecretsActualPrefix 锚定 */
    const val PLAIN_PREFIX = "nvenc1:"

    sealed interface PassResolution {
        /** 沿用原行 password 列（调用方取 w.original?.password，original=null 时为空串+测试连接必败兜底） */
        data object KeepExisting : PassResolution
        data object Empty : PassResolution
        data class NeedsEncrypt(val plain: String) : PassResolution
    }

    fun resolvePassword(anonymous: Boolean, passUnknown: Boolean, pass: String): PassResolution = when {
        anonymous -> PassResolution.Empty
        passUnknown && pass.isEmpty() -> PassResolution.KeepExisting
        else -> PassResolution.NeedsEncrypt(pass)
    }

    /** 落库指令（纯函数产物）：VM 只做薄壳执行——「沿用原列」这一最危险映射由此钉死在测试里 */
    sealed interface SaveCommand {
        data class Save(val filesOnly: Boolean, val storedPassword: String) : SaveCommand
        data class ConfirmPlaintext(val filesOnly: Boolean, val plain: String) : SaveCommand
    }

    fun resolveSave(
        filesOnly: Boolean,
        anonymous: Boolean,
        passUnknown: Boolean,
        pass: String,
        originalPassword: String?,
        encrypt: (String) -> String?,
    ): SaveCommand = when (val res = resolvePassword(anonymous, passUnknown, pass)) {
        PassResolution.KeepExisting -> SaveCommand.Save(filesOnly, originalPassword.orEmpty())
        PassResolution.Empty -> SaveCommand.Save(filesOnly, "")
        is PassResolution.NeedsEncrypt -> when (val stored = encrypt(res.plain)) {
            null -> SaveCommand.ConfirmPlaintext(filesOnly, res.plain)
            else -> SaveCommand.Save(filesOnly, stored)
        }
    }

    /** 该落库值是否为明文形态（角标判定 + 降级标记共用；空串=匿名不算明文） */
    fun isPlaintextStored(stored: String): Boolean = stored.isNotEmpty() && !stored.startsWith(PLAIN_PREFIX)

    /**
     * 免重测判定（spec §2.2-2 评审二轮 P0-R）：passUnknown 且未重输且连接相关字段与原源
     * 全等——连接没变就没有需要重新验证的东西（原口令已不可解，认证解密与回显解密同 key
     * 同败，任何「用原凭据重测」必然空口令失败）。candidate 传调用方 wizardEntity() 产物
     * （享受 host/https 归一化口径）。复制源（original=null）恒 false，按新源流程重输。
     */
    fun canSkipRetest(passUnknown: Boolean, pass: String, original: com.novax.app.data.db.SourceEntity?, candidate: com.novax.app.data.db.SourceEntity): Boolean {
        val o = original ?: return false
        return passUnknown && pass.isEmpty() &&
            candidate.type == o.type && candidate.host == o.host && candidate.port == o.port &&
            candidate.domain == o.domain && candidate.username == o.username &&
            candidate.shareOrPath == o.shareOrPath
    }
}
