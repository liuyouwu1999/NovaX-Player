package com.novax.app.ui.files

import com.novax.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * mapConnectError「路径不存在」分支锁定（2026-10-19 用户反馈回归）：
 * SMB 状态码原文（扫描横幅同源异常）须译成可读原因并带上具体目录，
 * WebDAV 404 同义语义并入同一分支（原 HTTP 404 分支随之摘除）。
 * i18n 改造后断言 UiMessage 的资源键与参数（不再断言中文字面量）。
 */
class MapConnectErrorTest {

    /** 2026-10-19 用户截图原文形态：smbj 包装后的完整 message */
    @Test
    fun smbObjectPathNotFound_readableError_withUncPath() {
        val e = IOException(
            "operation homes: STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): " +
                "Create failed for \\\\100.64.200.3\\迅雷\\sx\\3dvr",
        )
        val err = mapConnectError(e, NvProtocol.SMB)
        assertEquals(R.string.files_err_path_missing_title, err.title.res)
        assertEquals(R.string.files_err_path_missing_with_msg, err.message.res)
        assertEquals(
            listOf("\\\\100.64.200.3\\迅雷\\sx\\3dvr"),
            err.message.args,
        )
    }

    @Test
    fun webdav404_sameBranch_withoutUncPath() {
        val err = mapConnectError(IOException("HTTP 404 Not Found"), NvProtocol.WebDav)
        assertEquals(R.string.files_err_path_missing_title, err.title.res)
        assertEquals(R.string.files_err_path_missing_msg, err.message.res)
        assertTrue(err.message.args.isEmpty())
    }

    /** 不相关错误不误命中：权限/认证类仍走各自分支 */
    @Test
    fun unrelatedErrors_notMisrouted() {
        assertEquals(
            R.string.files_err_no_access_title,
            mapConnectError(IOException("STATUS_ACCESS_DENIED: x"), NvProtocol.SMB).title.res,
        )
        assertEquals(
            R.string.files_err_credential_title,
            mapConnectError(IOException("STATUS_LOGON_FAILURE: x"), NvProtocol.SMB).title.res,
        )
    }

    // ── i18n Task 8（评审移交①）：四条跨层链路改结构化判别，匹配侧与抛出侧一致性锁定 ──

    /** Jellyfin 401：client 抛 httpError(401)（kind=JELLYFIN_CREDENTIAL）→ 凭据分支 */
    @Test
    fun jellyfin401_structuredKind_credentialBranch() {
        val err = mapConnectError(
            com.novax.app.data.NvConnectException(
                com.novax.app.data.NvConnectKind.JELLYFIN_CREDENTIAL,
                "用户名或密码错误",
            ),
            NvProtocol.Jellyfin,
        )
        assertEquals(R.string.files_err_credential_title, err.title.res)
        assertEquals(R.string.files_err_cred_jellyfin_msg, err.message.res)
    }

    /** SFTP TOFU 指纹不一致：kind 经中间包装（模拟 sshj TransportException）沿 cause 链命中 */
    @Test
    fun sftpTofu_structuredKind_throughWrapper() {
        val inner = com.novax.app.data.NvConnectException(
            com.novax.app.data.NvConnectKind.SFTP_HOST_KEY_CHANGED,
            "主机密钥指纹与首次连接时不一致（可能存在中间人）",
        )
        val err = mapConnectError(java.io.IOException("Transport exception: key exchange failed", inner), NvProtocol.SFTP)
        assertEquals(R.string.files_err_hostkey_title, err.title.res)
        assertEquals(R.string.files_err_hostkey_tofu_msg, err.message.res)
    }

    /** 本地目录不可读（NvSourceRepository.requireDir）→ 目录不可读分支 */
    @Test
    fun localDirUnreadable_structuredKind() {
        val err = mapConnectError(
            com.novax.app.data.NvConnectException(
                com.novax.app.data.NvConnectKind.DIR_UNREADABLE,
                "目录不可读: /sdcard/x (源A)",
            ),
            NvProtocol.Local,
        )
        assertEquals(R.string.files_err_dir_unreadable_title, err.title.res)
        assertEquals(R.string.files_err_dir_unreadable_msg, err.message.res)
    }

    /** SMB 未配置共享名（SmbClient.createEntry）→ 共享名缺失分支 */
    @Test
    fun smbShareMissing_structuredKind() {
        val err = mapConnectError(
            com.novax.app.data.NvConnectException(
                com.novax.app.data.NvConnectKind.SHARE_NOT_CONFIGURED,
                "SMB 源未配置共享名: 源B",
            ),
            NvProtocol.SMB,
        )
        assertEquals(R.string.files_err_share_missing_title, err.title.res)
        assertEquals(R.string.files_err_share_missing_msg, err.message.res)
    }
}
