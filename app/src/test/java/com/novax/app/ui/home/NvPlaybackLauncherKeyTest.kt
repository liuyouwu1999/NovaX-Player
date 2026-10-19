package com.novax.app.ui.home

import com.novax.app.data.NvPaths
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1 修复回归：续播（NvPlaybackLauncher.resumeFrom）与播放（FilesViewModel.playUrl）
 * 必须同构——两处共用 buildNovaxUrl / localPath 构造器，本测试锁定其行为规格：
 * normalize 后拼接、绝不出现 "novax://1//a" 双斜杠分叉、本地绝对路径拼接一致。
 */
class NvPlaybackLauncherKeyTest {

    /** 单测内不依赖 android.net.Uri：注入恒等 encode，只验证结构（normalize + 单斜杠拼接） */
    private val identity: (String, String) -> String = { s, _ -> s }

    @Test
    fun `novax url keeps single slash between host and normalized path`() {
        // 旧 bug：resumeFrom 侧 "novax://1/" + encode(path) 与 playUrl 侧 "novax://1" + encode 分叉
        assertEquals(
            "novax://1/media/movies/a.mkv",
            NvPlaybackLauncher.buildNovaxUrl(1L, "/media/movies/a.mkv", identity),
        )
    }

    @Test
    fun `novax url normalizes duplicate slashes and missing leading slash`() {
        assertEquals(
            "novax://1/media/movies/a.mkv",
            NvPlaybackLauncher.buildNovaxUrl(1L, "//media//movies//a.mkv", identity),
        )
        assertEquals(
            "novax://1/media/movies/a.mkv",
            NvPlaybackLauncher.buildNovaxUrl(1L, "media/movies/a.mkv", identity),
        )
    }

    @Test
    fun `play side and resume side produce identical keys for same file`() {
        // playUrl 侧传入列表路径（可能无前导 /），resumeFrom 侧传入解码后的历史 path（normalize 形态）
        val listed = "media/tv/ep01.mp4"
        val decoded = NvPaths.normalize(listed)
        assertEquals(
            NvPlaybackLauncher.buildNovaxUrl(7L, listed, identity),
            NvPlaybackLauncher.buildNovaxUrl(7L, decoded, identity),
        )
    }

    @Test
    fun `encoded path round-trips to normalized path`() {
        val raw = "media/影 片/第 1 集.mkv"
        val encoded = java.net.URLEncoder.encode(NvPaths.normalize(raw), "UTF-8")
        val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
        assertEquals(NvPaths.normalize(raw), decoded)
        // 解码后的历史 path 再构造（resumeFrom 侧）与直接构造（playUrl 侧）一致
        assertEquals(
            NvPlaybackLauncher.buildNovaxUrl(2L, NvPaths.normalize(raw), identity),
            NvPlaybackLauncher.buildNovaxUrl(2L, decoded, identity),
        )
    }

    @Test
    fun `local path joins source root and normalized entry path`() {
        assertEquals(
            "/storage/emulated/0/Movies/a.mkv",
            NvPlaybackLauncher.localPath("/storage/emulated/0/Movies/", "a.mkv"),
        )
        assertEquals(
            "/storage/emulated/0/Movies/a.mkv",
            NvPlaybackLauncher.localPath("/storage/emulated/0/Movies", "a.mkv"),
        )
        assertEquals(
            "/root/sub/v.mp4",
            NvPlaybackLauncher.localPath("/root", "\\sub\\v.mp4"),
        )
    }
}
