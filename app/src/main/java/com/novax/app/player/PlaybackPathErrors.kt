package com.novax.app.player

import com.novax.app.data.NvConnectKind
import com.novax.app.data.findConnectKind
import java.io.FileNotFoundException

/**
 * Inspect causes without treating genuine container or decoder errors as path failures.
 *
 * i18n Task 9（Task 8 评审移交①）：「SMB 源未配置共享名」原为跨层中文 message-contains
 * 匹配——四语化后异常消息语言不再是稳定协议，改走结构化 [NvConnectKind]（抛出侧
 * SmbClient.createEntry 携带 kind，[findConnectKind] 沿 cause 链查找，与
 * FilesViewModel.mapConnectError 同口径）。STATUS_* 为底层库稳定英文输出，保留匹配。
 */
internal fun isInaccessiblePlaybackPath(error: Throwable): Boolean {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    while (cause != null && visited.add(cause)) {
        if (cause is FileNotFoundException) return true
        if (cause.findConnectKind() == NvConnectKind.SHARE_NOT_CONFIGURED) return true
        val message = cause.message.orEmpty()
        if (listOf("STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND",
                "STATUS_BAD_NETWORK_NAME", "STATUS_ACCESS_DENIED")
                .any { message.contains(it, ignoreCase = true) }) return true
        cause = cause.cause
    }
    return false
}
