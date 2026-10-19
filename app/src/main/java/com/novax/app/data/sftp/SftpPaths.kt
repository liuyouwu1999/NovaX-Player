package com.novax.app.data.sftp

/**
 * SFTP 远程路径计算（纯函数，可单测；2026-09-29 SFTP 源落地配套）。
 *
 * 源根（SourceEntity.shareOrPath，如 "/home/nas/videos" 或空 = SFTP 会话家目录）+
 * 统一内部路径（NvPaths 表示，"/" 开头相对源根）→ sshj 期望的远程路径。
 *
 * 语义约定：
 * - 配了根：结果恒为绝对路径 "/root/..."（OpenSSH 兼容服务器均按文件系统绝对路径解析）；
 * - 没配根：结果为相对会话 CWD（登录家目录）的路径——根目录本身用 "." 表示
 *   （SFTP 无 "家目录" 通配，ls/stat/open 都吃 CWD 相对路径）。
 * - 重复分隔符/反斜杠/空白段折叠；不解析 ".."（库内路径来自服务器列表，上游
 *   NvPaths.normalize 已折叠穿越段，此处只做拼接）。
 */
object SftpPaths {

    /** 拼源根与统一路径为 sshj 远程路径（见类 kdoc 语义约定） */
    fun remotePath(root: String, path: String): String {
        val rootSegs = splitSegments(root)
        val pathSegs = splitSegments(path)
        val all = rootSegs + pathSegs
        if (all.isEmpty()) return "."                    // 根 + 未配根 = 会话 CWD（家目录）
        val joined = all.joinToString("/")
        return if (rootSegs.isEmpty()) joined else "/$joined"
    }

    private fun splitSegments(path: String): List<String> =
        path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
}
