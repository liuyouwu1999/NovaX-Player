package com.novax.app.data.sftp

import com.novax.app.data.smb.RandomReadHandle
import net.schmizz.sshj.sftp.RemoteFile

/**
 * sshj 定位读适配器（2026-09-29 SFTP 源落地）：把 [net.schmizz.sshj.sftp.RemoteFile.read]
 * 适配到 [RandomReadHandle]，让 SFTP 白拿 [com.novax.app.data.smb.SmbRandomReadStream]
 * 的整条读管线（4MB 级大块缓冲 + 单块 lookahead 预读 + 读侧僵死看门狗）。
 *
 * 语义对齐（v0.38.0 javap + 源码核实，2026-09-29）：
 * - smbj `File.read(data, fileOffset, offset, length)`：返回实际读取数，EOF -1；
 * - sshj `RemoteFile.read(fileOffset, to, offset, len)`：DATA 包回实际读取数
 *   （服务端短读原样返回，checkReadResponse 按 recvLen arraycopy）；
 *   STATUS EOF 显式映射 -1（ensureStatusIs(EOF) 后 return -1）；其余 STATUS 抛 SFTPException。
 * 两者语义一致，readAt 直接透传即可，无需 EOF/短读翻译层。
 *
 * 并发安全：sshj 的 request/response 按 id 多路复用（SFTPEngine.transmit 串行化发包、
 * Promise 按 id 派发收包），SmbRandomReadStream 的主读线程与预读线程并发调用同一句柄
 * 合法——与 SMB2 多路复用同模型（见 SmbRandomReadStream 线程模型 kdoc）。
 *
 * 单请求读长上限不在本类处理：SmbRandomReadStream 的块循环把每次 readAt 长度压在
 * blockSize（SftpClient 传 256KB，见其 kdoc）以内，服务端短读由块循环自然补满。
 */
internal class SftpReadHandle(
    private val file: RemoteFile,
) : RandomReadHandle {

    override fun readAt(data: ByteArray, fileOffset: Long, offset: Int, length: Int): Int =
        file.read(fileOffset, data, offset, length)

    override fun close() {
        file.close()
    }
}
