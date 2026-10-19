package com.novax.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * mapConnectError「路径不存在」分支锁定（2026-10-19 用户反馈回归）：
 * SMB 状态码原文（扫描横幅同源异常）须译成可读原因并带上具体目录，
 * WebDAV 404 同义语义并入同一分支（原 HTTP 404 分支随之摘除）。
 */
class MapConnectErrorTest {

    /** 2026-10-19 用户截图原文形态：smbj 包装后的完整 message */
    @Test
    fun smbObjectPathNotFound_readableError_withUncPath() {
        val e = IOException(
            "operation homes: STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): " +
                "Create failed for \\\\192.168.1.100\\media\\video\\movies",
        )
        val err = mapConnectError(e, NvProtocol.SMB)
        assertEquals("路径不存在", err.title)
        assertTrue(err.message.contains("移动"))
        assertTrue(err.message.contains("\\\\192.168.1.100\\media\\video\\movies"))
    }

    @Test
    fun webdav404_sameBranch_withoutUncPath() {
        val err = mapConnectError(IOException("HTTP 404 Not Found"), NvProtocol.WebDav)
        assertEquals("路径不存在", err.title)
        assertTrue(err.message.contains("移动"))
    }

    /** 不相关错误不误命中：权限/认证类仍走各自分支 */
    @Test
    fun unrelatedErrors_notMisrouted() {
        assertEquals(
            "无访问权限",
            mapConnectError(IOException("STATUS_ACCESS_DENIED: x"), NvProtocol.SMB).title,
        )
        assertEquals(
            "账号或密码错误",
            mapConnectError(IOException("STATUS_LOGON_FAILURE: x"), NvProtocol.SMB).title,
        )
    }
}
