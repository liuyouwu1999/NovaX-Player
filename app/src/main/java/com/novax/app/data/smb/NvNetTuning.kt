package com.novax.app.data.smb

/**
 * SMB 网络调优运行时槽（2026-09-15 假开关真化）：
 * 设置页「SMB 读取块大小 / 并发预读」此前是无消费点的 placebo 开关（评审 C3-P0）。
 * 现经 MainActivity 的 playerPrefsFlow 收集器写入本槽，SmbClient.openReadBlocking 每次开流
 * 时读取——不显式改过设置（DataStore 无该键）时行为与旧行为完全一致（4MB 块 + 预读开）。
 */
object NvNetTuning {

    /** SMB 块流单块上限覆盖（null = 默认 4MB）；抽帧路径固定 256KB 不受此影响 */
    @Volatile
    var blockBytesOverride: Int? = null

    /** 预读开关（默认开；关闭 = 主路径读块，省一次预读网络往返） */
    @Volatile
    var prefetchEnabled: Boolean = true

    /** "256KB"/"512KB"/"1MB"/"4MB" → 字节数；未知值返回 null（回落默认） */
    fun parseBlockBytes(raw: String): Int? = when (raw) {
        "256KB" -> 256 * 1024
        "512KB" -> 512 * 1024
        "1MB" -> 1024 * 1024
        "4MB" -> 4 * 1024 * 1024
        else -> null
    }
}
