package com.novax.app.player

import com.novax.app.data.NvConnectException
import com.novax.app.data.NvConnectKind
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.FileNotFoundException

class PlaybackPathErrorsTest {
    @Test fun wrappedMissingFile_isPathError() {
        assertTrue(isInaccessiblePlaybackPath(IOException("load", FileNotFoundException("missing"))))
    }
    @Test fun smbMissingShareOrDenied_isPathError() {
        for (status in listOf("STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_BAD_NETWORK_NAME", "STATUS_ACCESS_DENIED")) {
            assertTrue(isInaccessiblePlaybackPath(IOException("load", IOException(status))))
        }
    }
    @Test fun malformedContainerAndTimeout_areNotPathErrors() {
        assertFalse(isInaccessiblePlaybackPath(IOException("None of the available extractors could read the stream")))
        assertFalse(isInaccessiblePlaybackPath(IOException("Read timed out")))
    }
    /** i18n Task 9①：中文 message-contains 匹配迁移为结构化 kind（沿 cause 链穿透包装层） */
    @Test fun smbShareNotConfiguredKind_isPathError() {
        assertTrue(
            isInaccessiblePlaybackPath(
                IOException("load", NvConnectException(NvConnectKind.SHARE_NOT_CONFIGURED, "SMB 源未配置共享名: nas")),
            )
        )
    }
    @Test fun unrelatedConnectKind_isNotPathError() {
        assertFalse(
            isInaccessiblePlaybackPath(
                IOException("load", NvConnectException(NvConnectKind.DIR_UNREADABLE, "目录不可读: /sdcard/x")),
            )
        )
    }
}
