package com.novax.app.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 终审 C2：视图键反解内核纯逻辑测试（NvPrivacyAccess.resolveViewPathIn）——
 * LOCAL 源裸绝对路径 (null, abs) → (源id, 源内相对路径) 的归属判定。
 * Access 构造私有（Room/Keystore 不进单测），反解本体抽 companion internal 纯函数直测。
 */
class NvPrivacyAccessResolveTest {

    // 根口径与 NvPrivacyAccess 常驻监快照一致：去尾分隔符；root="/" 的源去尾后为空串
    private val roots = listOf(
        1L to "/sdcard/Movies",
        2L to "/sdcard",
        3L to "",          // 源根 = "/"（罩住一切绝对路径，前缀最短）
    )

    /** 无 "/" 根源的最小口径（段边界/根自身/外部豁免用） */
    private val plainRoots = listOf(1L to "/sdcard/Movies", 2L to "/sdcard")

    @Test
    fun `novax目标原样返回不经反解`() {
        assertEquals(7L to "/A/B.mp4", NvPrivacyAccess.resolveViewPathIn(roots, 7L, "/A/B.mp4"))
        // 即便 path 是绝对路径（novax 源内路径本就以 / 开头），sourceId 非空就不反解
        assertEquals(7L to "/sdcard/x.mp4", NvPrivacyAccess.resolveViewPathIn(roots, 7L, "/sdcard/x.mp4"))
    }

    @Test
    fun `LOCAL裸路径命中归属源并剥根前缀`() {
        assertEquals(1L to "/a.mp4", NvPrivacyAccess.resolveViewPathIn(plainRoots, null, "/sdcard/Movies/a.mp4"))
        assertEquals(2L to "/a.mp4", NvPrivacyAccess.resolveViewPathIn(plainRoots, null, "/sdcard/a.mp4"))
    }

    @Test
    fun `段边界不误命中`() {
        // 单根口径：/sdcard/MoviesX 不是 /sdcard/Movies 之下的段（无更宽根罩住）
        val single = listOf(1L to "/sdcard/Movies")
        assertNull(NvPrivacyAccess.resolveViewPathIn(single, null, "/sdcard/MoviesX/a.mp4"))
        assertNull(NvPrivacyAccess.resolveViewPathIn(single, null, "/sdcard/Music/a.mp4"))
    }

    @Test
    fun `多源命中取最长前缀`() {
        // /sdcard/Movies 与 /sdcard 都罩住 → 取更长的 Movies
        assertEquals(1L to "/a.mp4", NvPrivacyAccess.resolveViewPathIn(roots, null, "/sdcard/Movies/a.mp4"))
        assertEquals(2L to "/Music/a.mp4", NvPrivacyAccess.resolveViewPathIn(roots, null, "/sdcard/Music/a.mp4"))
    }

    @Test
    fun `恰好根自身不命中`() {
        // 根是目录不是文件：等于根自身且无更宽根罩住 → 无归属（文件必在根之下）
        val single = listOf(1L to "/sdcard/Movies")
        assertNull(NvPrivacyAccess.resolveViewPathIn(single, null, "/sdcard/Movies"))
        val nested = listOf(1L to "/sdcard/Movies", 2L to "/sdcard")
        // 嵌套根并存时，"另一根的普通子路径"归更宽的根（属多源命中最长前缀语义，非根自身特例）
        assertEquals(2L to "/Movies", NvPrivacyAccess.resolveViewPathIn(nested, null, "/sdcard/Movies"))
    }

    @Test
    fun `无命中为真外部文件走豁免`() {
        assertNull(NvPrivacyAccess.resolveViewPathIn(plainRoots, null, "/storage/0000-1111/a.mp4"))
        assertNull(NvPrivacyAccess.resolveViewPathIn(emptyList(), null, "/sdcard/a.mp4"))
    }

    @Test
    fun `非绝对路径不参与反解`() {
        // content://、http(s)://、相对路径都不可能属于 LOCAL 源
        assertNull(NvPrivacyAccess.resolveViewPathIn(roots, null, "content://media/a.mp4"))
        assertNull(NvPrivacyAccess.resolveViewPathIn(roots, null, "https://example.com/a.mp4"))
        assertNull(NvPrivacyAccess.resolveViewPathIn(roots, null, "a.mp4"))
    }

    @Test
    fun `空根源罩住其余绝对路径且相对路径原样`() {
        // /sdcard 之外的绝对路径归 "/" 源（id=3），相对路径=原样（空根无前缀可剥）
        assertEquals(3L to "/storage/0000-1111/a.mp4", NvPrivacyAccess.resolveViewPathIn(roots, null, "/storage/0000-1111/a.mp4"))
        // 与常规根并存时长前缀仍优先
        assertEquals(1L to "/a.mp4", NvPrivacyAccess.resolveViewPathIn(roots, null, "/sdcard/Movies/a.mp4"))
    }

    @Test
    fun `相对路径保留前导斜杠与规则口径互逆`() {
        // NvPlaybackLauncher.localPath = 根去尾 + normalize(源内路径)；反解必须还原出以 / 开头的源内路径
        val r = NvPrivacyAccess.resolveViewPathIn(roots, null, "/sdcard/Movies/系列/第1集.mp4")
        assertEquals(1L to "/系列/第1集.mp4", r)
    }
}
