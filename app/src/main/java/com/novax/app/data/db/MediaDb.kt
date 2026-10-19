package com.novax.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 媒体库数据库（C 数据层底座）。
 * version 1：sources 表（NAS 源配置）。
 * version 2（C11）：新增 watch_history / favorites 两表（MIGRATION_1_2 非破坏性迁移）。
 * version 3（C12）：新增 media_items / scan_state 两表 + sources 加 scrapeEnabled 列
 * （MIGRATION_2_3 非破坏性迁移，保留用户已配 sources 与全部历史/收藏，禁止 drop/recreate）。
 * version 4：sources 加 scanRoots 列（TEXT 可空；向导所选扫描根目录集，JSON 数组，
 * 编解码见 data/scanner/ScanRoots；null=扫根目录。MIGRATION_3_4 非破坏性迁移）。
 * version 5：sources 加 excludedDirs 列（TEXT 可空；媒体库排除目录集，JSON 数组，
 * 编解码同 ScanRoots；null=无排除。MIGRATION_4_5 非破坏性迁移）。
 * version 6：media_items 加 TMDB 刮削四列（scrapeOverview/scrapePosterPath/scrapeYear/
 * scrapeRating，均 NULLABLE。MIGRATION_5_6 非破坏性迁移；写入方 data/scrape/NvScraper，
 * ScanMerge 重扫保留旧值）。
 * version 7（隐私功能）：新增 privacy_rules 表（无 FK，UNIQUE(sourceId,keyHash)）+
 * watch_history/favorites 各加 pathCipher 可空列（密文行约定：非 null ⇒ path=HMAC hex、
 * title=标题密文、pathCipher=路径密文；null ⇒ 旧明文行。MIGRATION_6_7 非破坏性迁移，
 * SQL 清单见 PrivacyDb7）。
 */
@Database(
    entities = [
        SourceEntity::class,
        WatchHistoryEntity::class,
        FavoriteEntity::class,
        MediaItemEntity::class,
        ScanStateEntity::class,
        PrivacyRuleEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class MediaDb : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun favoriteDao(): FavoriteDao

    /** 媒体索引 DAO（C12 §1.6） */
    abstract fun mediaItemDao(): MediaItemDao

    /** 扫描状态 DAO（C12 §1.6） */
    abstract fun scanStateDao(): ScanStateDao

    /** 隐私锁规则 DAO（隐私功能 Task 2） */
    abstract fun privacyRuleDao(): PrivacyRuleDao

    companion object {
        private const val DB_NAME = "novax_media.db"

        @Volatile
        private var instance: MediaDb? = null

        /** MediaDb v1 → v2（C11 §5.1）：watch_history / favorites 建表 + (sourceId, path) 唯一索引 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceId` INTEGER, `sourceType` TEXT, `path` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`watchedAt` INTEGER NOT NULL, `seasonNumber` INTEGER, `episodeNumber` INTEGER)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_history_sourceId_path` " +
                        "ON `watch_history` (`sourceId`, `path`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceId` INTEGER, `path` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_sourceId_path` " +
                        "ON `favorites` (`sourceId`, `path`)",
                )
            }
        }

        /**
         * MediaDb v2 → v3（C12 §1.4）：media_items / scan_state 建表 +
         * sources 加 scrapeEnabled（默认 0）。非破坏性：不动 sources/watch_history/favorites 既有数据。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceId` INTEGER NOT NULL, `path` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`nameLower` TEXT NOT NULL, `pinyin` TEXT NOT NULL, `initials` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, `mtimeMs` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`durationMs` INTEGER, `width` INTEGER, `height` INTEGER, " +
                        "`thumbnailPath` TEXT, `thumbState` TEXT NOT NULL, " +
                        "`scrapedTitle` TEXT, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_items_sourceId_path` " +
                        "ON `media_items` (`sourceId`, `path`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_name` ON `media_items` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_kind` ON `media_items` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_addedAt` ON `media_items` (`addedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_state` (`sourceId` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `scannedDirs` INTEGER NOT NULL, `foundItems` INTEGER NOT NULL, " +
                        "`lastScanAt` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`sourceId`))",
                )
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `scrapeEnabled` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * MediaDb v3 → v4：sources 加 scanRoots（TEXT 可空，默认 null=扫根目录）。
         * 非破坏性：仅加列，不动任何既有数据。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `scanRoots` TEXT")
            }
        }

        /**
         * MediaDb v4 → v5：sources 加 excludedDirs（TEXT 可空，默认 null=无排除）。
         * 非破坏性：仅加列，不动任何既有数据。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `excludedDirs` TEXT")
            }
        }

        /**
         * MediaDb v5 → v6：media_items 加 TMDB 刮削四列（TEXT/TEXT/INTEGER/REAL，均可空）。
         * 非破坏性：仅加列，不动任何既有数据。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `scrapeOverview` TEXT")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `scrapePosterPath` TEXT")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `scrapeYear` INTEGER")
                db.execSQL("ALTER TABLE `media_items` ADD COLUMN `scrapeRating` REAL")
            }
        }

        /** MediaDb v6 → v7（隐私功能 Task 2）：建表/加列 SQL 全部委托 PrivacyDb7（单测断言同一清单） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                PrivacyDb7.migrate(db)
            }
        }

        /**
         * 开库自愈（2026-09-18）：2026-09-15 起图片不入媒体库（MediaScanner 对 isImageFile 直接
         * continue），但旧版本已入库的 kind='IMAGE' 存量行只能靠"完整重扫成功后的对账"清除——
         * 升级后没重扫的用户会一直看到这批假条目（点开报容器解析失败，RM 兜底内核把单张 jpg
         * 当静态帧放出来）。onOpen 幂等清理；kind 列有索引，千级行开销可忽略。
         */
        private val PURGE_LEGACY_IMAGE_ROWS = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("DELETE FROM media_items WHERE kind = 'IMAGE'")
            }
        }

        /** 进程级单例（App 无 DI 框架，保持简单） */
        fun get(context: Context): MediaDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediaDb::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .addCallback(PURGE_LEGACY_IMAGE_ROWS)
                    .build()
                    .also { instance = it }
            }
    }
}
