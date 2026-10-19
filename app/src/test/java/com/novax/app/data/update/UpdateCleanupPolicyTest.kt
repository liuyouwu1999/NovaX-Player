package com.novax.app.data.update

import android.app.DownloadManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val RUN = DownloadManager.STATUS_RUNNING

class UpdateCleanupPolicyTest {
    @Test fun `活动下载目标文件保留`() {
        assertFalse(UpdateCleanupPolicy.shouldDelete("a.apk", active = Triple(7L, RUN, "a.apk"), pendingVersion = "1.0.0.1", currentVersion = "1.0.0.0", completedPendingFile = null))
    }
    @Test fun `活动下载但不同名则删`() {
        assertTrue(UpdateCleanupPolicy.shouldDelete("old.apk", active = Triple(7L, RUN, "a.apk"), pendingVersion = "1.0.0.1", currentVersion = "1.0.0.0", completedPendingFile = null))
    }
    @Test fun `已下载待装且目标版本更新则保留`() {
        assertFalse(UpdateCleanupPolicy.shouldDelete("a.apk", active = null, pendingVersion = "1.0.0.1", currentVersion = "1.0.0.0", completedPendingFile = "a.apk"))
    }
    @Test fun `授权页往返期间待装包存活`() {
        // 用户跳系统「安装未知应用」授权页往返期间状态查询中断——pendingVersion 仍高于当前，必须保留
        assertFalse(UpdateCleanupPolicy.shouldDelete("a.apk", active = null, pendingVersion = "1.0.0.1", currentVersion = "1.0.0.0", completedPendingFile = "a.apk"))
    }
    @Test fun `已装到目标版本则删`() {
        assertTrue(UpdateCleanupPolicy.shouldDelete("a.apk", active = null, pendingVersion = "1.0.0.1", currentVersion = "1.0.0.1", completedPendingFile = "a.apk"))
    }
    @Test fun `本地比待装版本更新也删`() {
        assertTrue(UpdateCleanupPolicy.shouldDelete("a.apk", active = null, pendingVersion = "1.0.0.1", currentVersion = "1.0.0.2", completedPendingFile = "a.apk"))
    }
    @Test fun `失败残留与无关文件删`() {
        assertTrue(UpdateCleanupPolicy.shouldDelete("old.apk", active = null, pendingVersion = "1.0.0.1", currentVersion = "1.0.0.0", completedPendingFile = "a.apk"))
        assertTrue(UpdateCleanupPolicy.shouldDelete("a.apk", active = null, pendingVersion = null, currentVersion = "1.0.0.0", completedPendingFile = "a.apk"))
    }
}
