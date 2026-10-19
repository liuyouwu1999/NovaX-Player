package com.novax.app.data.privacy

import androidx.sqlite.db.SupportSQLiteDatabase
import com.novax.app.data.db.MediaDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真抹除管道（隐私计划 Task 8，Task 9/10/11/13 的隐私行删除一律经此）。
 * SQLite 普通删行≠抹除：WAL 与空闲页仍留明文可被 strings 捞出——须先 `secure_delete=ON`
 * 让删行即刻零填充，删完 `wal_checkpoint(TRUNCATE)` 截断 WAL 清掉旧帧。
 * 顺序：查原 secure_delete → ON → block（删行）→ checkpoint → 恢复原值；
 * block 抛异常也保证 checkpoint+恢复（finally），恢复值查不到时按 OFF（Android SQLite 默认）。
 */
object NvSecureWipe {

    internal const val SQL_QUERY_SECURE_DELETE = "PRAGMA secure_delete"
    internal const val SQL_SECURE_DELETE_ON = "PRAGMA secure_delete=ON"
    internal const val SQL_WAL_CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"

    /**
     * SQL 执行缝（纯 JVM 单测的最小缝，不开真 SQLite）：读值查询，与行返回 PRAGMA 经 query 消费执行。
     * 主路径经 SupportSQLiteDatabase；测试传记录器断言调用顺序。
     */
    internal interface SqlRunner {
        /** 返回首行首列字符串（无行/空 → null）——查 secure_delete 原值用 */
        fun queryForString(sql: String): String?

        /** 行返回 PRAGMA 经 query 消费执行（secure_delete=ON / wal_checkpoint / 恢复） */
        fun exec(sql: String)
    }

    /**
     * 真抹除包住 [block]：调用方在 block 内经入参 SupportSQLiteDatabase 删隐私行
     * （execSQL 或 DAO 皆可，删除必须落在 block 内才算被管道包住）。IO 线程执行，主线程安全。
     */
    suspend fun <T> wiping(db: MediaDb, block: suspend (SupportSQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            val sqlite = db.openHelper.writableDatabase
            wipingVia(runnerOf(sqlite)) { block(sqlite) }
        }

    /** 内部缝：测试注入记录器 runner；block 不收 db（public 层已绑定 sqlite） */
    internal suspend fun <T> wipingVia(runner: SqlRunner, block: suspend () -> T): T {
        val previous = runner.queryForString(SQL_QUERY_SECURE_DELETE)
        runner.exec(SQL_SECURE_DELETE_ON)
        try {
            return block()
        } finally {
            runner.exec(SQL_WAL_CHECKPOINT)
            runner.exec(
                "PRAGMA secure_delete=" + if (previous?.toIntOrNull() == 1) "ON" else "OFF",
            )
        }
    }

    private fun runnerOf(sqlite: SupportSQLiteDatabase): SqlRunner = object : SqlRunner {
        override fun queryForString(sql: String): String? =
            sqlite.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        override fun exec(sql: String) {
            // 安卓上带值 PRAGMA（secure_delete=ON）与 wal_checkpoint 都返回结果行，execSQL 拒绝执行
            // （SQLiteException: Queries can be performed using query or rawQuery methods only）——
            // 统一走 query 并消费首行；DAO 的 DELETE 不经此缝（在 block 内）。
            sqlite.query(sql).use { it.moveToFirst() }
        }
    }
}
