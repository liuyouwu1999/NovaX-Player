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
}
