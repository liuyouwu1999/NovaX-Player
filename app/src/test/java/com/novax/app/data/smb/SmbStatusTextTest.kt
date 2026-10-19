package com.novax.app.data.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SmbStatusText 纯函数锁定（2026-10-19 用户反馈回归）：
 * 扫描横幅/浏览器错误把 NTSTATUS 原文翻译成人话；「路径不存在」类是
 * MediaScanner 扫描根降级跳过（不再整源 FAILED）的判定依据。
 */
class SmbStatusTextTest {

    /** 真实样本形态：smbj 包装后的完整异常 message（2026-10-19 用户截图原文） */
    private val rawCreateFailed =
        "operation homes: STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): Create failed for \\\\192.168.1.100\\media\\video\\movies"

    @Test
    fun missingPath_matchesStatusConstants_andHexCodes() {
        assertTrue(SmbStatusText.missingPath(rawCreateFailed))
        assertTrue(SmbStatusText.missingPath("opendir \\a\\b failed (0xC000003A)"))    // 小写 0x 码
        assertTrue(SmbStatusText.missingPath("STATUS_OBJECT_NAME_NOT_FOUND (0xc0000034): x"))
        assertTrue(SmbStatusText.missingPath("Server returned HTTP 404 Not Found"))    // WebDAV 同义语义
    }

    @Test
    fun missingPath_rejectsUnrelatedErrors() {
        assertFalse(SmbStatusText.missingPath("目录不可读: /locked/"))                  // 权限类：根失败仍 FAILED
        assertFalse(SmbStatusText.missingPath("无法连接主机"))
        assertFalse(SmbStatusText.missingPath("STATUS_LOGON_FAILURE: bad credentials"))
        assertFalse(SmbStatusText.missingPath(""))
    }

    @Test
    fun uncPathOf_extractsServerPathFromCreateFailedMessage() {
        assertEquals("\\\\192.168.1.100\\media\\video\\movies", SmbStatusText.uncPathOf(rawCreateFailed))
    }

    @Test
    fun uncPathOf_returnsNull_whenNoUncPath() {
        assertNull(SmbStatusText.uncPathOf("Server returned HTTP 404 Not Found"))
        assertNull(SmbStatusText.uncPathOf(""))
    }

    @Test
    fun missingPathReason_isActionableText() {
        val reason = SmbStatusText.missingPathReason()
        assertTrue(reason.contains("不存在"))
        assertTrue(reason.contains("移动") && reason.contains("改名"))
    }
}
