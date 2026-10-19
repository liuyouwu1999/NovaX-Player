package com.novax.app.player

/**
 * 网络恢复自动重连状态机（2026-09-17，调研 backlog 项）。
 *
 * 从 PlayerScreen 抽出的纯逻辑（无 Android 依赖，JVM 单测覆盖）：
 * - [onFatalError]：致命错误落地。网络类错误 → 进入「等待网络恢复」武装态；
 *   非网络类（文件不存在/解析/解码等，重试无意义）不武装。
 * - [onNetworkAvailable]：系统网络恢复事件。武装态且预算未耗尽 → 触发一次自动重试
 *   （调用 [onRetry]，同手动重试按钮）；随后退出武装态等待重试结果，防多网络
 *   （WiFi+蜂窝）同时恢复时重复触发。
 * - [onPlaybackStarted]：播放成功即重置预算——同一次观看中的多次断网各自享有全额额度。
 * - [onManualRetry]：用户手动点了重试 → 与自动重试互斥，退出武装态。
 *
 * 预算 [maxAttempts] 防 NAS 长期离线时的无限重试循环。
 */
class NvAutoReconnect(
    private val maxAttempts: Int = 3,
    private val onRetry: () -> Unit,
    private val onAutoRetry: (attempt: Int) -> Unit = {},
) {
    /** 已自动重试次数（播放成功后归零） */
    var attempts: Int = 0
        private set

    /** 武装态：网络类致命错误已发生，正在等待网络恢复 */
    var armed: Boolean = false
        private set

    /** 致命错误落地：isNetwork=true 时武装 */
    fun onFatalError(isNetwork: Boolean) {
        armed = isNetwork
    }

    /** 系统网络恢复事件：武装态且预算未耗尽 → 自动重试一次 */
    fun onNetworkAvailable() {
        if (!armed || attempts >= maxAttempts) return
        armed = false
        attempts++
        onAutoRetry(attempts)
        onRetry()
    }

    /** 播放成功：预算重置（多次断网各自享有全额额度） */
    fun onPlaybackStarted() {
        attempts = 0
        armed = false
    }

    /** 用户手动重试：与自动重试互斥，等待结果期间退出武装态 */
    fun onManualRetry() {
        armed = false
    }
}
