package com.novax.app.data.privacy
import com.novax.app.data.db.PrivacyDb7
import org.junit.Assert.*; import org.junit.Test
class MigrationSqlTest {
    @Test fun `v7迁移SQL完整`() {
        val sql = PrivacyDb7.SQLS
        assertTrue(sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `privacy_rules`") && it.contains("`keyHash` TEXT NOT NULL") && it.contains("`state` TEXT NOT NULL") })
        assertTrue(sql.any { it.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_privacy_rules_sourceId_keyHash`") })
        assertTrue(sql.any { it == "ALTER TABLE `watch_history` ADD COLUMN `pathCipher` TEXT" })
        assertTrue(sql.any { it == "ALTER TABLE `favorites` ADD COLUMN `pathCipher` TEXT" })
    }
}
