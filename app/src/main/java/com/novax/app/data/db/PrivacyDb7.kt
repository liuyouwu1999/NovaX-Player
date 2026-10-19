package com.novax.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * MediaDb v6 → v7 迁移（隐私功能 Task 2）。
 *
 * 普通对象（非 Room 类）：SQL 清单独立成常量供 JVM 单测逐字断言（MigrationSqlTest，
 * 不加载 Room/Android 框架类）；建表语句与 PrivacyRuleEntity 的 Room 期望 schema 逐列
 * 一致（运行时迁移校验 TableInfo 严格比对，列名/类型/NOT NULL 错一处即崩）。
 *
 * 非破坏性：仅新增 privacy_rules 表 + watch_history/favorites 各加可空 pathCipher 列
 * （pathCipher != null ⇒ 密文行：path=HMAC hex、title=标题密文；null ⇒ 旧明文行），不动既有数据。
 */
object PrivacyDb7 {

    val SQLS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `privacy_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sourceId` INTEGER NOT NULL, `keyHash` TEXT NOT NULL, `dirCipher` TEXT NOT NULL, " +
            "`displayCipher` TEXT NOT NULL, `lockEnabled` INTEGER NOT NULL, `incognitoEnabled` INTEGER NOT NULL, " +
            "`fingerprint` TEXT, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_privacy_rules_sourceId_keyHash` " +
            "ON `privacy_rules` (`sourceId`, `keyHash`)",
        "ALTER TABLE `watch_history` ADD COLUMN `pathCipher` TEXT",
        "ALTER TABLE `favorites` ADD COLUMN `pathCipher` TEXT",
    )

    fun migrate(db: SupportSQLiteDatabase) {
        SQLS.forEach(db::execSQL)
    }
}
