package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 补丁 B(2026-10-10)纯函数内核:LOCAL 直连绝对路径 → 已索引源反查。
 * 匹配纪律:双侧 NvPaths.normalize(「..」穿越/反斜杠/尾分隔符/双斜杠消解)后
 * 最长前缀 + 段边界;嵌套根取最深;查无 = null。
 * 生产链路(resolveLocalDirectTarget → resolveNotificationArtwork)按键查库,
 * 播放 URL 与历史/收藏键零改动。
 */
class NvLocalDirectLookupTest {

    @Test
    fun rootChildMapsToInternalPath() {
        assertEquals(
            7L to "/a.mp4",
            localReverseLookup("/Media/Movies/a.mp4", listOf(7L to "/Media/Movies")),
        )
    }

    @Test
    fun segmentBoundaryNoFalseMatch() {
        // 前缀撞名( /Movies vs /MoviesExtra )不得误配——段边界是硬约束
        assertNull(localReverseLookup("/Media/MoviesExtra/a.mp4", listOf(7L to "/Media/Movies")))
    }

    @Test
    fun longestPrefixWins() {
        // 嵌套根:/Media 与 /Media/Movies 同时命中取最深,内部路径按深根换算
        assertEquals(
            8L to "/a.mp4",
            localReverseLookup(
                "/Media/Movies/a.mp4",
                listOf(7L to "/Media", 8L to "/Media/Movies"),
            ),
        )
    }

    @Test
    fun rootTrailingSeparatorNormalized() {
        assertEquals(
            7L to "/a.mp4",
            localReverseLookup("/Media/Movies/a.mp4", listOf(7L to "/Media/Movies/")),
        )
    }

    @Test
    fun rootBackslashNormalized() {
        assertEquals(
            7L to "/a.mp4",
            localReverseLookup("/Media/Movies/a.mp4", listOf(7L to "\\Media\\Movies")),
        )
    }

    @Test
    fun dotDotTraversalNormalizedBeforePrefix() {
        // 「..」先折叠再匹配:/Media/Movies/../../secret ≠ /Media/Movies 之下
        assertNull(localReverseLookup("/Media/Movies/../../secret/a.mp4", listOf(7L to "/Media/Movies")))
    }

    @Test
    fun noMatchAndEmptyRoots() {
        assertNull(localReverseLookup("/Elsewhere/a.mp4", listOf(7L to "/Media")))
        assertNull(localReverseLookup("/Media/a.mp4", emptyList()))
    }

    @Test
    fun deviceRootSourceMatchesTopLevel() {
        // 根为 "/" 的源(极端配置)天然匹配任意绝对路径,内部路径即原路径
        assertEquals(9L to "/a.mp4", localReverseLookup("/a.mp4", listOf(9L to "/")))
    }
}
