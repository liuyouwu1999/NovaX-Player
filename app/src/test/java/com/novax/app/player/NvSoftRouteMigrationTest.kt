package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** FFmpeg 音频升级迁移纯函数：只清 mime 集合 + 「音轨」原因的 per-file 路由；视频类原因保留 */
class NvSoftRouteMigrationTest {
    @Test
    fun `drops mime set and audio-reason routes, keeps video reasons`() {
        val entries = mapOf(
            "novax_soft_unsupported_audio_mimes" to "[audio/ac3, audio/vnd.dts]",
            "novax_softroute_http://nas/f1.mkv" to "音轨 DTS",
            "novax_softroute_http://nas/f2.mkv" to "音轨 E-AC-3",
            "novax_softroute_http://nas/f3.mkv" to "杜比视界画面",
            "novax_left_slowmo" to "true",
        )
        assertEquals(
            setOf(
                "novax_soft_unsupported_audio_mimes",
                "novax_softroute_http://nas/f1.mkv",
                "novax_softroute_http://nas/f2.mkv",
            ),
            planSoftRouteMigration(entries),
        )
    }

    @Test
    fun `empty or unrelated entries produce empty drop set plus mime key`() {
        assertEquals(
            setOf("novax_soft_unsupported_audio_mimes"),
            planSoftRouteMigration(emptyMap()),
        )
        assertEquals(
            setOf("novax_soft_unsupported_audio_mimes"),
            planSoftRouteMigration(mapOf("novax_softroute_x" to "杜比视界画面")),
        )
    }
}
