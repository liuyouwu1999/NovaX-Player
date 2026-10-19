package com.novax.app.data.privacy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯 JVM（不开真 SQLite，Android 框架类不进单测）：经内部 SqlRunner 缝注入记录器，
 * 断言真抹除管道的调用顺序与恢复——查原 secure_delete → ON → block → checkpoint → 恢复；
 * block 抛异常也必须 checkpoint+恢复。公共路径绑定与 resetAllWipe 接通用源码断言兜底
 * （MediaDb/SupportSQLiteDatabase 无法进 JVM，同 NvPinManagerTest 的 MessageDigest.isEqual 手法）。
 */
class NvSecureWipeSqlTest {

    /** 记录器 runner：每条 SQL 记进 calls；queryForString 按预设返回 secure_delete 原值 */
    private class Recorder(private val secureDeleteRaw: String?) : NvSecureWipe.SqlRunner {
        val calls = mutableListOf<String>()
        override fun queryForString(sql: String): String? { calls += sql; return secureDeleteRaw }
        override fun exec(sql: String) { calls += sql }
    }

    // ---- 顺序与恢复 ----

    @Test fun `顺序_查原值_ON_block_checkpoint_恢复`() = runBlocking {
        val r = Recorder("0")
        val result = NvSecureWipe.wipingVia(r) { r.calls += "BLOCK"; "ret" }
        assertEquals(
            listOf(
                "PRAGMA secure_delete",
                "PRAGMA secure_delete=ON",
                "BLOCK",
                "PRAGMA wal_checkpoint(TRUNCATE)",
                "PRAGMA secure_delete=OFF",
            ),
            r.calls,
        )
        assertEquals("ret", result)          // block 返回值透传
    }

    @Test fun `原值为1_恢复成ON不降级`() = runBlocking {
        val r = Recorder("1")
        NvSecureWipe.wipingVia(r) { r.calls += "BLOCK" }
        assertEquals(
            listOf(
                "PRAGMA secure_delete",
                "PRAGMA secure_delete=ON",
                "BLOCK",
                "PRAGMA wal_checkpoint(TRUNCATE)",
                "PRAGMA secure_delete=ON",
            ),
            r.calls,
        )
    }

    @Test fun `原值查不到_按OFF恢复且五步不缺`() = runBlocking {
        val r = Recorder(null)
        NvSecureWipe.wipingVia(r) { r.calls += "BLOCK" }
        assertEquals(5, r.calls.size)
        assertEquals("PRAGMA secure_delete=OFF", r.calls.last())
    }

    @Test fun `block抛异常_checkpoint与恢复仍执行且异常上抛`() = runBlocking {
        val r = Recorder("1")
        val thrown: IllegalStateException? = try {
            NvSecureWipe.wipingVia(r) { error("boom") }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("block 异常必须上抛", thrown)
        assertEquals(
            listOf(
                "PRAGMA secure_delete",
                "PRAGMA secure_delete=ON",
                "PRAGMA wal_checkpoint(TRUNCATE)",
                "PRAGMA secure_delete=ON",
            ),
            r.calls,
        )
    }

    // ---- 防退化源码断言（JVM 进不了真库路径，源码口径兜底） ----

    @Test fun `公共路径绑定openHelperwritableDatabase且checkpoint恢复齐全`() {
        val text = sourceOf("NvSecureWipe.kt")
        assertTrue("public wiping 必须经 db.openHelper.writableDatabase 取真库", text.contains("db.openHelper.writableDatabase"))
        assertTrue("checkpoint 必须是 TRUNCATE 口径", text.contains("PRAGMA wal_checkpoint(TRUNCATE)"))
        assertTrue("恢复必须按原值发 PRAGMA secure_delete=", text.contains("PRAGMA secure_delete="))
    }

    @Test fun `runnerOf的exec必须走query消费结果行_不得用execSQL`() {
        val text = sourceOf("NvSecureWipe.kt")
        val execBody = text.substringAfter("override fun exec(sql: String)")
        assertTrue(
            "exec 必须经 query 执行并消费结果行（真机崩溃：带值 PRAGMA 与 wal_checkpoint 都返回结果行，execSQL 一律拒绝）",
            execBody.contains("sqlite.query(sql)"),
        )
        assertFalse(
            "NvSecureWipe 内不得出现 execSQL(sql)（行返回 PRAGMA 走 execSQL 直接抛 SQLiteException）",
            text.contains("execSQL(sql)"),
        )
    }

    @Test fun `resetAllWipe已在NvPrivacyAccess接为真实现`() {
        val text = sourceOf("NvPrivacyAccess.kt")
        assertTrue(
            "resetAllWipe 必须经 NvSecureWipe.wiping 包住 privacy_rules 全清",
            text.contains("NvSecureWipe.wiping") && text.contains("DELETE FROM privacy_rules"),
        )
        assertTrue(
            "必须提供 PinManager 统一构造点并注入 resetAllWipe（缺此步忘码重置不真抹除）",
            text.contains("fun newPinManager") && text.contains("resetAllWipe = resetAllWipe"),
        )
    }

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/novax/app/data/privacy/$name"),
            File("app/src/main/java/com/novax/app/data/privacy/$name"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到源文件：$candidates")
        return file.readText()
    }
}
