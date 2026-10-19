package com.novax.app.data.thumb

import org.junit.Assert.assertEquals
import org.junit.Test

/** spec 2026-10-12 §4.5:通知封面接线与文件页旁路封面必须同一 key(严禁自拼 hashKey) */
class ThumbCacheKeyTest {
    /** 金样本:md5("12:bypass:/sdcard/DCIM/a.mp4:12345")——key 公式变更会在此暴露 */
    @Test
    fun browserThumbKey_goldenHex() {
        assertEquals(
            "8321b9c6416a6bfda9335621454dfc17",
            ThumbCache.browserThumbKey(12L, "/sdcard/DCIM/a.mp4", 12345L),
        )
    }
}
