package com.novax.app.data

import java.io.IOException

/**
 * 连接类错误的结构化判别类别（i18n Task 8，Task 4-7 评审移交①）：取代此前上层
 * （FilesViewModel.mapConnectError）对 data 层异常**中文原文**的 message-contains 跨层匹配
 * ——四语化后异常消息语言不再是稳定协议，分类改走机器判别的 [kind]。
 *
 * 异常 message 本身保留中文诊断原文（供 logcat/崩溃取证与兜底 detail 透传，白名单登记）；
 * 用户可见文案由上层按 kind 映射为 UiMessage 资源键。两侧一致性由 MapConnectErrorTest +
 * 抛出侧单测锁定。
 */
enum class NvConnectKind {
    /** Jellyfin 401（用户名或密码错误）→ 凭据错误（向导凭据框高亮） */
    JELLYFIN_CREDENTIAL,

    /** SFTP TOFU 主机密钥指纹与首连不一致（sshj 包成 TransportException 后中文落 cause 链） */
    SFTP_HOST_KEY_CHANGED,

    /** 本地源目录不可读（NvSourceRepository.requireDir） */
    DIR_UNREADABLE,

    /** SMB 源未配置共享名（SmbClient.createEntry） */
    SHARE_NOT_CONFIGURED,
}

/** 携带结构化判别的连接错误：kind 供上层分类，message 保留中文诊断原文（不入资源） */
class NvConnectException(
    val kind: NvConnectKind,
    message: String?,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 沿 cause 链查找结构化连接错误（sshj TransportException / mapToIo 等中间包装不丢判别；
 * 深度上限 8 防御异常环）。无命中返回 null —— 调用方回落既有 message 匹配/兜底分支。
 */
fun Throwable.findConnectKind(): NvConnectKind? =
    generateSequence(this as Throwable?) { it.cause }
        .take(8)
        .filterIsInstance<NvConnectException>()
        .firstOrNull()
        ?.kind
