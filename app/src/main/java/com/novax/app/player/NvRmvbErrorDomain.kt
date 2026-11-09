package com.novax.app.player

/**
 * 兜底页（libVLC）错误域的网络可恢复判定（2026-11-09 审计 A3 / spec B-3）。
 * 与主播放器 isNetworkRecoverableErrorCode（PlayerScreen，media3 错误码域）**分域**：
 * 兜底页 setup 阶段抛 Exception（源客户端/代理上游），VLC EncounteredError 无错误码。
 * IOException 族（含 cause 链，深度封顶防环）= 网络类；其余默认非网络（重试无意义，不武装）。
 */
internal fun isRmvbNetworkRecoverable(t: Throwable?): Boolean {
    var cur = t
    var depth = 0
    while (cur != null && depth < 8) {
        if (cur is java.io.IOException) return true
        cur = cur.cause
        depth++
    }
    return false
}
