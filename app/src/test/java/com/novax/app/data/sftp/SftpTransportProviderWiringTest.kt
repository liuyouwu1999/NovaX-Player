package com.novax.app.data.sftp

import net.schmizz.sshj.common.SecurityUtils
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-11-02 SFTP 传输层修复（sshj × Android 系统提供器撞名）。
 *
 * 机制：sshj 首次加密操作触发 register()——类路径上有自带依赖 bcprov 时，实例化
 * BouncyCastleProvider 并把名字 "BC" 记为默认安全提供器；但 Android 系统里同名 "BC"
 * （兼容残留版）已占住注册名，sshj 只跳过注册、仍按名字记账 → 之后所有算法请求都
 * 打到系统裁剪空壳上，任何 SSH 密钥交换必挂（API 35 实测：no such algorithm:
 * X25519/EC/SHA-256 for provider BC，SFTP 在现代 Android 完全无法建链）。
 * 修复：SftpClient 初始化禁用 sshj 的 BC 注册并把可能已缓存的记账纠回平台默认 JCE。
 *
 * 本测试在桌面 JVM 上复现同一机制：宿主 JVM 无内置 "BC"，sshj 会把自带完整版 bcprov
 * 注册为 "BC"——断言接线后提供器记账为 null（平台默认）即证明撞名路径被关闭。
 */
class SftpTransportProviderWiringTest {

    @Test
    fun sftpClientInit_sshjUsesPlatformDefault_notBC() {
        SftpClient(knownHosts = null)              // 实例 init 接线
        SecurityUtils.getMessageDigest("SHA-256")  // 触发 sshj register()
        assertNull(
            "sshj 默认安全提供器必须为 null（平台默认 JCE）——按名记账 \"BC\" 就是模拟器上全挂的根源",
            SecurityUtils.getSecurityProvider(),
        )
        assertNotEquals("BC", SecurityUtils.getMessageDigest("SHA-256").provider.name)
    }

    @Test
    fun wiring_correctsPreviouslyCachedBcRegistration() {
        // 模拟「接线前 register() 已被别的路径触发」的时序：手动置 BC 缓存 → 构造 SftpClient → 必须纠回
        SecurityUtils.setSecurityProvider("BC")
        SftpClient(knownHosts = null)
        SecurityUtils.getMessageDigest("SHA-256")
        assertNull(SecurityUtils.getSecurityProvider())
    }
}
