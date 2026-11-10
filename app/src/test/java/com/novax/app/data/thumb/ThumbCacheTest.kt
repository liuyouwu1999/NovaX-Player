package com.novax.app.data.thumb

import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbCacheTest {

    /** spec 2026-10-10 §5.1:notif_art 是通知栏封面缓存,无 DB 引用联动,必须进 7 天按龄清扫,
     *  不得成为全仓库唯一无出量机制的 cacheDir 子目录(评审 P1) */
    @Test
    fun notifArtIncludedInTempSweep() {
        assertTrue(ThumbCache.TEMP_SUBDIRS.contains("notif_art"))
    }

    /** 审计 F7（spec B-10）：cacheDir 根 thumb_relay_* 前缀文件与 thumbs/rmvb 子目录
     *  原为清扫死角（进程中途被杀才残留，永不清理）——并入按龄清扫；根下非前缀文件不误删。 */
    @Test
    fun thumbRelayAndRmvbDeadCornersSweptByAge() {
        val tmp = org.junit.rules.TemporaryFolder()
        tmp.create()
        val cacheDir = tmp.newFolder("cache")
        val old = System.currentTimeMillis() - 8L * 24 * 3600 * 1000
        val oldRelay = java.io.File(cacheDir, "thumb_relay_x.jpg").apply { writeText("x"); setLastModified(old) }
        val newRelay = java.io.File(cacheDir, "thumb_relay_y.jpg").apply { writeText("y") }
        val rmvbDir = java.io.File(cacheDir, "thumbs/rmvb").apply { mkdirs() }
        val oldMkv = java.io.File(rmvbDir, "a.mkv").apply { writeText("x"); setLastModified(old) }
        val newMkv = java.io.File(rmvbDir, "b.mkv").apply { writeText("y") }
        val unrelatedOld = java.io.File(cacheDir, "unrelated_old.jpg").apply { writeText("x"); setLastModified(old) }

        ThumbCache.sweepThumbDeadCorners(cacheDir)

        assertTrue("超龄 relay 删", !oldRelay.exists())
        assertTrue("新鲜 relay 留", newRelay.exists())
        assertTrue("超龄 rmvb 子目录文件删", !oldMkv.exists())
        assertTrue("新鲜 rmvb 子目录文件留", newMkv.exists())
        assertTrue("根下非前缀文件不误删", unrelatedOld.exists())
        tmp.delete()
    }
}
