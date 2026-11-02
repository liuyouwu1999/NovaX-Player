package com.novax.app.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MediaDb 迁移自动化验证（2026-11-02 机构整改 spec §5，D2 拍板方案 A=Robolectric+MigrationTestHelper；
 * 一期 MigrationSqlTest 只是 SQL 文本断言，防不了「迁移结果与实体注解漂移」——本类补上）。
 *
 * 防漂移原理：runMigrationsAndValidate 迁移后按 app/schemas/…7.json（exportSchema=true 生成的
 * 权威快照）对六表做 TableInfo 严格比对，列名/类型/NOT NULL/索引任何漂移即红。仓库只导出了
 * v7 快照（历史版本无 JSON），故 v1 基库用 FrameworkSQLiteOpenHelperFactory 手工建立（V1_DDL
 * 与 7.json sources 列集减 v3/v4/v5 加列逐字对照）再交 helper；1→6 段直接调用生产 Migration
 * 对象执行，零复制零漂移。
 *
 * 覆盖裁剪说明（spec §5.3「目标全链 1→7，按成本裁剪」）：fullChain 走满 1→7 全链并携带 v1
 * 数据行；sixToSeven 聚焦 6→7（隐私功能迁移），在 v6 形态插 sources/watch_history/favorites
 * 三表数据并验证 privacy_rules 唯一索引真实生效。
 *
 * Windows 宿主变通（AGP 9.1 + Room 2.8.2 + androidx.sqlite 2.6.1，2026-11-02 落地实证）：
 * MigrationTestHelper 默认走 SupportSQLiteDriver，其 open() 校验库名只按 '/' 切 basename
 * （androidx.sqlite SupportSQLiteDriver.android.kt:48-55 substringAfterLast('/')），而 Windows
 * 上 Room SupportTestConnectionManager.openConnection 传入的是 getDatabasePath().absolutePath
 * （反斜杠、无 '/' 可切）→ require 必炸「This driver is configured to open a database named …」
 * ——真机/Linux 路径含 '/' 不受影响，属上游对 Windows 路径分隔符的疏漏，仅咬 Windows 宿主的
 * Robolectric。变通：helper() 传入 WinPathAwareFactory 包装 FrameworkSQLiteOpenHelperFactory
 * （delegate 照常按裸名开同一个库文件，仅把上报的 databaseName 改为与请求串逐字相等的绝对路径，
 * 走 == 分支放行）；两个用例的断言与流程零改动。androidx.sqlite 修复分隔符处理后可整体删除。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MigrationRobolectricTest {

    /**
     * v1 sources 基表 DDL：= 7.json sources 表 DDL 减去 scrapeEnabled（v3）/scanRoots（v4）/
     * excludedDirs（v5）三列。实体无 @ColumnInfo(defaultValue)，快照无 DEFAULT 子句。
     * 特例备忘（评审 P2-1，Room TableInfo 判等语义）：MIGRATION_2_3 给 scrapeEnabled 带的
     * `DEFAULT 0` 在 DB 侧多出、实体侧无 defaultValue——TableInfo.Column 判等只在**实体侧
     * defaultValue 非 null** 时才强制比对默认值，DB 侧多出 DEFAULT 被容忍，判等不失败
     * （生产设备跨 6 个 DB 版本升级从未崩的实况互证）。对照 7.json 与迁移 SQL 时勿误判要
     * 「修」实体。
     */
    private val v1SourcesDdl =
        "CREATE TABLE IF NOT EXISTS `sources` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
            "`nickname` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
            "`domain` TEXT NOT NULL, `username` TEXT NOT NULL, `password` TEXT NOT NULL, " +
            "`shareOrPath` TEXT NOT NULL, `lastCheckedAt` INTEGER NOT NULL, `status` TEXT NOT NULL)"

    private fun helper() = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MediaDb::class.java,
        emptyList(),
        WinPathAwareFactory(),
    )

    /**
     * Windows 宿主 Robolectric 专项变通（真机/Linux 无此问题），详见类 kdoc：包装
     * FrameworkSQLiteOpenHelperFactory，delegate 全部行为原样（仍按配置里的裸名开库，
     * 与手工 openRaw 建的 v1 文件同一路径），仅覆写上报的 databaseName 为
     * getDatabasePath().absolutePath——与 Room SupportTestConnectionManager.openConnection
     * 请求 open() 时用的串同源同值（同 context、同 name、同一确定性 API），使
     * SupportSQLiteDriver 的 `openHelperDatabaseName == fileName` 分支成立。
     */
    private class WinPathAwareFactory : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val delegate = FrameworkSQLiteOpenHelperFactory().create(configuration)
            val absPath = configuration.context.getDatabasePath(configuration.name!!).absolutePath
            return object : SupportSQLiteOpenHelper by delegate {
                override val databaseName: String?
                    get() = absPath
            }
        }
    }

    /** 手工打开 v1 基库（新文件时执行 V1_DDL；旧文件直接开——两用例共用） */
    private fun openRaw(name: String): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v1SourcesDdl)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun migration1To7_fullChain_carriesV1Row_and_allSixTablesMatchSchema() {
        val name = "mig-1to7.db"
        openRaw(name).use { db ->
            db.execSQL(
                "INSERT INTO sources (type,nickname,host,port,domain,username,password,shareOrPath,lastCheckedAt,status) " +
                    "VALUES ('WEBDAV','v1row','192.0.2.9',5005,'','svc','legacy123','/dav',0,'UNKNOWN')",
            )
        }
        helper().runMigrationsAndValidate(
            name, 7, true,
            MediaDb.MIGRATION_1_2, MediaDb.MIGRATION_2_3, MediaDb.MIGRATION_3_4,
            MediaDb.MIGRATION_4_5, MediaDb.MIGRATION_5_6, MediaDb.MIGRATION_6_7,
        ).use { db7 ->
            db7.query("SELECT password FROM sources WHERE nickname='v1row'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("legacy123", c.getString(0))   // v1 数据行原样穿越全链
            }
            // 六表全部可读（迁移结果 = 实体注解的严格比对在 runMigrationsAndValidate 内已做）
            listOf("sources", "watch_history", "favorites", "media_items", "scan_state", "privacy_rules").forEach { t ->
                db7.query("SELECT count(*) FROM `$t`").use { c -> assertTrue(c.moveToFirst()) }
            }
            // privacy_rules（v7 新表）真实可写
            db7.execSQL(
                "INSERT INTO privacy_rules (sourceId,keyHash,dirCipher,displayCipher,lockEnabled," +
                    "incognitoEnabled,fingerprint,state,createdAt) VALUES (1,'h','d','p',1,0,NULL,'LOCKED',0)",
            )
        }
    }

    @Test
    fun migration6To7_privacyRulesCreated_pathCipherAdded_v6DataIntact() {
        val name = "mig-6to7.db"
        openRaw(name).use { db ->
            // 1→6 段：生产 Migration 对象直调（零复制）；随后置 user_version=6 并插 v6 形态数据
            MediaDb.MIGRATION_1_2.migrate(db)
            MediaDb.MIGRATION_2_3.migrate(db)
            MediaDb.MIGRATION_3_4.migrate(db)
            MediaDb.MIGRATION_4_5.migrate(db)
            MediaDb.MIGRATION_5_6.migrate(db)
            db.version = 6
            db.execSQL(
                "INSERT INTO sources (type,nickname,host,port,domain,username,password,shareOrPath," +
                    "lastCheckedAt,status,scrapeEnabled,scanRoots,excludedDirs) " +
                    "VALUES ('SMB','v6row','192.0.2.8',445,'WORKGROUP','svc','legacy456','media',0,'UNKNOWN',1,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO watch_history (sourceId,sourceType,path,title,kind,positionMs,durationMs," +
                    "watchedAt,seasonNumber,episodeNumber) VALUES (1,'SMB','/m/a.mkv','a','VIDEO',1,2,3,4,5)",
            )
            db.execSQL("INSERT INTO favorites (sourceId,path,title,kind,addedAt) VALUES (1,'/m/b.mkv','b','VIDEO',9)")
        }
        helper().runMigrationsAndValidate(name, 7, true, MediaDb.MIGRATION_6_7).use { db7 ->
            db7.query("SELECT password FROM sources WHERE nickname='v6row'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("legacy456", c.getString(0))
            }
            db7.query("SELECT pathCipher FROM watch_history").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))   // v6 旧行 = 明文行约定（pathCipher null）
            }
            db7.query("SELECT addedAt FROM favorites WHERE path='/m/b.mkv'").use { c -> assertTrue(c.moveToFirst()) }
            // privacy_rules 建成且 UNIQUE(sourceId,keyHash) 索引真实生效（同键第二行必撞）
            db7.execSQL(
                "INSERT INTO privacy_rules (sourceId,keyHash,dirCipher,displayCipher,lockEnabled," +
                    "incognitoEnabled,fingerprint,state,createdAt) VALUES (1,'h','d','p',1,0,NULL,'LOCKED',0)",
            )
            assertThrows(
                Exception::class.java,
            ) {
                db7.execSQL(
                    "INSERT INTO privacy_rules (sourceId,keyHash,dirCipher,displayCipher,lockEnabled," +
                        "incognitoEnabled,fingerprint,state,createdAt) VALUES (1,'h','d2','p2',1,0,NULL,'LOCKED',0)",
                )
            }
        }
    }
}
