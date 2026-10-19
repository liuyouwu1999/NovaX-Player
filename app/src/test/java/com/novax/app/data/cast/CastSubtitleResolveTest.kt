package com.novax.app.data.cast

import com.novax.app.player.SubtitleCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSubtitleResolveTest {
    private fun cand(path: String, score: Int = 10) =
        SubtitleCandidate(path, path.substringAfterLast('/'), null, isExact = false, score = score)

    @Test fun `自动选最高分候选的源内路径`() {
        // pickAuto 只认 >= AUTO_THRESHOLD(80) 的高分候选（与本地播放同一纪律）
        val picked = CastSubtitle.resolveAuto(listOf(cand("/s/a.chs.srt", 90), cand("/s/a.eng.srt", 85)))
        assertEquals("/s/a.chs.srt", picked)
    }

    @Test fun `低分候选不自动选`() {
        assertNull(CastSubtitle.resolveAuto(listOf(cand("/s/a.chs.srt", 30))))
    }

    @Test fun `无候选返回null`() {
        assertNull(CastSubtitle.resolveAuto(emptyList()))
    }

    @Test fun `ASS且无同名SRT时需要提示`() {
        assertTrue(CastSubtitle.needsStyleWarning("/s/xx.ass"))
        assertTrue(CastSubtitle.needsStyleWarning("/s/xx.SSA"))
        assertFalse(CastSubtitle.needsStyleWarning("/s/xx.srt"))
    }
}
