package com.novax.app.data.sftp

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * SFTP 主机密钥指纹存储（TOFU，校验语义见 [SftpClient] 类 kdoc）：filesDir 下
 * sftp_known_hosts 文件，行格式 `host:port SHA256:<base64无填充指纹>`。
 *
 * 不用 DataStore：连接建立存在阻塞路径（openReadBlocking/fileSizeBlocking 由 ExoPlayer
 * 加载线程直调，无挂起点），DataStore 同步读需 runBlocking；本文件整读整写均为 KB 级，
 * 任意线程直接同步 IO。写入走「临时文件 + rename」同目录原子替换，防进程中途被杀在文件
 * 里留半行脏数据；rename 覆盖已存在目标在 Linux/Android 原子成功，Windows（单测环境）
 * 会失败——退化为删后重试。同实例方法级互斥（连接建立的 verify 回调与删源驱逐可并发）。
 */
internal class SftpKnownHosts(private val file: File) {

    /** 该 host:port 的已记录指纹（`SHA256:...`）；无记录返回 null */
    @Synchronized
    fun get(host: String, port: Int): String? =
        lines().firstOrNull { it.startsWith(prefix(host, port)) }?.substringAfter(' ')

    /** 记入/覆盖指纹（TOFU 首连，或删源重加后的重新钉扎） */
    @Synchronized
    fun put(host: String, port: Int, fingerprint: String) =
        atomicWrite(lines().filterNot { it.startsWith(prefix(host, port)) } + record(host, port, fingerprint))

    /** 移除该 host:port 的记录（删源/编辑保存的重置信任落点）；本就无记录返回 false */
    @Synchronized
    fun remove(host: String, port: Int): Boolean {
        val all = lines()
        val remaining = all.filterNot { it.startsWith(prefix(host, port)) }
        if (remaining.size == all.size) return false
        atomicWrite(remaining)
        return true
    }

    private fun lines(): List<String> =
        if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun atomicWrite(rows: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(if (rows.isEmpty()) "" else rows.joinToString("\n") + "\n")
        if (!tmp.renameTo(file)) {
            // Windows 不支持覆盖 rename（见类 kdoc）：删旧目标后重试；仍失败向上抛，连接建立失败
            runCatching { file.delete() }
            check(tmp.renameTo(file)) { "known-hosts 写入失败: ${file.path}" }
        }
    }

    companion object {
        /** filesDir 下的指纹文件名（NvGraph 接线处引用） */
        const val FILE_NAME = "sftp_known_hosts"

        private fun key(host: String, port: Int) = "$host:$port"

        private fun prefix(host: String, port: Int) = "${key(host, port)} "

        private fun record(host: String, port: Int, fingerprint: String) = "${key(host, port)} $fingerprint"
    }
}

/**
 * 公钥 → `SHA256:<base64无填充>` 指纹（OpenSSH known_hosts 行内指纹同款格式）。
 * 纯函数，可单测；[TofuHostKeyVerifier] 与单测共用。
 */
internal fun publicKeyFingerprint(key: PublicKey): String =
    "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))

/**
 * TOFU 主机密钥校验器（sshj [HostKeyVerifier]）：无记录 → 记入放行；一致 → 放行；
 * 不一致 → 抛中文可行动错误。sshj 会把 verify 内抛出的异常包成
 * TransportException(KEY_EXCHANGE_FAILED, cause) 上抛（0.38.0 KeyExchanger 字节码核实），
 * 中文文案在 cause 链里，由调用方错误映射还原。host/port 固定用源配置值——与
 * [SftpClient.evictFor] 的清除键同源，不用 verify 回调 hostname（同一连接两者恒等）。
 */
internal class TofuHostKeyVerifier(
    private val store: SftpKnownHosts,
    private val host: String,
    private val port: Int,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = publicKeyFingerprint(key)
        val recorded = store.get(host, port)
        if (recorded == null) {
            store.put(host, port, fingerprint)   // TOFU：首连记指纹放行
            return true
        }
        if (recorded == fingerprint) return true
        throw IOException("主机密钥指纹与首次连接时不一致（可能存在中间人）。如确认是服务器变更，请删除该源后重新添加以重置信任")
    }

    /** 指纹记录不含密钥算法信息，无从按已记录算法收敛主机密钥协商，交 sshj 全量协商 */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
