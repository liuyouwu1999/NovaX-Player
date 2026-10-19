package com.novax.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** E2-3（发版审查）：备份规则必须排除 SFTP 主机指纹文件与崩溃日志（两代规则、两个域全覆盖）。
 * 读取方式：单测工作目录即模块根（app/），直接以相对路径读 res/xml 源文件。 */
class BackupRulesTest {

    private fun load(name: String): String = java.io.File("src/main/res/xml/$name").readText()

    @Test fun `dataExtractionRules两域都排除sftp_known_hosts与crash`() {
        val xml = load("data_extraction_rules.xml")
        for (domain in listOf("cloud-backup", "device-transfer")) {
            val block = xml.substringAfter("<$domain>").substringBefore("</$domain>")
            assertTrue("$domain 缺 sftp_known_hosts 排除", block.contains("""domain="file" path="sftp_known_hosts""""))
            assertTrue("$domain 缺 crash 排除", block.contains("""domain="file" path="crash""""))
        }
    }

    @Test fun `fullBackupContent排除sftp_known_hosts与crash`() {
        val xml = load("full_backup_content.xml")
        assertTrue(xml.contains("""domain="file" path="sftp_known_hosts""""))
        assertTrue(xml.contains("""domain="file" path="crash""""))
    }
}
