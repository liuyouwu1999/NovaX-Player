package com.novax.app.data.smb

/**
 * SMB 随机读的偏移/长度计算（纯函数，可单测）。
 *
 * smbj 的 [com.hierynomus.smbj.share.File.read] 每次发送一个 SMB2 READ 请求，
 * 请求长度超过协商的 maxReadSize 会被服务器拒绝；读请求也不应越过文件末尾
 * （返回 STATUS_END_OF_FILE / -1）。DataSource 的 open/read/seek 都经由这里计算：
 * seek = ExoPlayer 以新 position 重新 open → 一次 seek = 一次定址 READ，无重握手（C8 §3.1）。
 */
object SmbReadMath {

    /** C8 §3.1：单次 SMB READ 1MB（千兆网 1MB/块 ≈ 10ms） */
    const val TARGET_CHUNK_BYTES = 1 shl 20

    /**
     * 播放流单块缓冲上限 4MB：块 = min(协商 maxReadSize, 此值)。
     * SMB3 large MTU 下 Windows/群晖常协商出 1~8MB 的 maxReadSize，
     * 4MB 块让一次网络往返服务 ~64 次 ExoPlayer 64KB read（配合预读线程）。
     */
    const val MAX_STREAM_BLOCK_BYTES = 4 shl 20

    /** 流缓冲块大小：协商值与 [capBytes] 取小（协商 64KB 的老 NAS 自动退化，仍可工作） */
    fun blockBytes(maxReadSize: Int, capBytes: Int = MAX_STREAM_BLOCK_BYTES): Int =
        maxReadSize.coerceAtMost(capBytes)

    /**
     * 本次实际发起的 READ 长度：
     * - 不超过 [maxChunk]（连接协商 maxReadSize，取协商值与目标块的较小者）；
     * - 已知 [contentLength]（>=0）时不越过文件末尾；
     * - 越过末尾（position >= contentLength）返回 0（上游据此判定 EOF）。
     */
    fun chunkLength(position: Long, requested: Int, contentLength: Long, maxChunk: Int): Int {
        val capped = requested.coerceAtMost(maxChunk)
        if (contentLength < 0) return capped
        val remaining = contentLength - position
        if (remaining <= 0) return 0
        return capped.toLong().coerceAtMost(remaining).toInt()
    }

    /** position 是否可读（非负；已知长度时不超过文件末尾） */
    fun isValidPosition(position: Long, contentLength: Long): Boolean =
        position >= 0 && (contentLength < 0 || position <= contentLength)
}

/**
 * Windows FILETIME（1601-01-01 起 100ns）→ Unix epoch 毫秒（纯函数，可单测）。
 * smbj 的 FileTime 已提供转换，这里保留纯实现用于无 smbj 依赖的路径与测试。
 */
object SmbTime {
    /** FILETIME epoch (1601-01-01T00:00:00Z) 与 Unix epoch 的毫秒差 */
    const val WINDOWS_TO_UNIX_EPOCH_MS = 11_644_473_600_000L

    fun windowsToEpochMillis(windowsTimestamp: Long): Long =
        windowsTimestamp / 10_000L - WINDOWS_TO_UNIX_EPOCH_MS
}
