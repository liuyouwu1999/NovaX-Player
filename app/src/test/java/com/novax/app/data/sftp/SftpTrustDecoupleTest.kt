package com.novax.app.data.sftp

import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-11-01 机构整改 spec §1：连接清理与信任重置解耦——普通编辑（含改 host/口令）
 * 只驱逐连接、绝不动 known-hosts 指纹；删源是唯一信任重置路径。
 */
class SftpTrustDecoupleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hostFile get() = File(tmp.root, SftpKnownHosts.FILE_NAME)

    private fun newClient(): SftpClient = SftpClient(knownHosts = hostFile)

    private fun sftpSource(port: Int = 22) = SourceEntity(
        type = SourceType.SFTP, nickname = "t", host = "nas.local", port = port,
        domain = "", username = "u", password = "plain", shareOrPath = "/root",
    )

    private fun pin() = SftpKnownHosts(hostFile).put("nas.local", 22, "SHA256:abc123")

    @Test
    fun evictConnections_keepsFingerprint_stillTrusted() {
        pin()
        val client = newClient()
        client.evictConnections(sftpSource())
        assertEquals("SHA256:abc123", SftpKnownHosts(hostFile).get("nas.local", 22))
        assertTrue(client.isHostTrusted(sftpSource()))
    }

    @Test
    fun evictConnectionsAndTrust_removesFingerprint() {
        pin()
        val client = newClient()
        client.evictConnectionsAndTrust(sftpSource())
        assertEquals(null, SftpKnownHosts(hostFile).get("nas.local", 22))
        assertFalse(client.isHostTrusted(sftpSource()))
    }

    @Test
    fun isHostTrusted_unknownHostOrUninitialized_false() {
        assertFalse(newClient().isHostTrusted(sftpSource()))          // 未钉扎
        assertFalse(SftpClient(knownHosts = null).isHostTrusted(sftpSource()))  // E2-2 未初始化
    }

    @Test
    fun isHostTrusted_portZero_usesDefault22() {
        pin()
        assertTrue(newClient().isHostTrusted(sftpSource(port = 0)))
    }
}
