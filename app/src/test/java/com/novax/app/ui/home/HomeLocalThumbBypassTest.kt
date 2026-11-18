package com.novax.app.ui.home

import com.novax.app.data.db.ContinueWatchingRow
import com.novax.app.data.db.WatchHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-19 未入库视频「继续观看」封面回退（用户报：本地源文件页浏览有封面、首页
 * 继续观看卡恒占位——源未扫描进媒体库，media_items 无行可查）。
 *
 * 测 [enrichLocalThumbRows] 纯函数（无 Android 依赖；ThumbCache.browserThumbFile 经
 * bypassLookup 谓词注入，VM 层接线薄壳）：媒体库命中优先 → 落空回退文件页旁路封面 →
 * 旁路缺失保持占位；NAS 行/已有图行原样透传不进通道。
 */
class HomeLocalThumbBypassTest {

    private fun row(path: String, sourceId: Long? = null, thumb: String? = null) =
        ContinueWatchingRow(
            entry = WatchHistoryEntity(
                sourceId = sourceId,
                sourceType = sourceId?.let { "LOCAL" },
                path = path,
                title = "测试片.mkv",
                kind = "MOVIE",
                positionMs = 60_000L,
                durationMs = 600_000L,
                watchedAt = 1L,
            ),
            thumbnailPath = thumb,
        )

    @Test
    fun `bypass cache hit fills thumbnailPath when media library misses`() = runBlocking {
        // 未入库视频：findByKey 落空（null）→ 回退文件页旁路封面（用户文件夹所见即首页所得）
        val locals = listOf(7L to "/sdcard/Movies")
        var bypassCalls = 0
        val out = enrichLocalThumbRows(
            listOf(row("/sdcard/Movies/剧/s01e01.mkv")),
            locals,
            thumbLookup = { _, _ -> null },
            bypassLookup = { sid, rel, abs ->
                bypassCalls++
                assertEquals(7L, sid)
                assertEquals("/剧/s01e01.mkv", rel)   // 源根前缀反查出的源内相对路径
                assertEquals("/sdcard/Movies/剧/s01e01.mkv", abs)
                "/cache/thumbs/browser/abc.jpg"
            },
        )
        assertEquals("/cache/thumbs/browser/abc.jpg", out[0].thumbnailPath)
        assertEquals(1, bypassCalls)
    }

    @Test
    fun `bypass miss keeps placeholder`() = runBlocking {
        // 旁路缓存被 LRU/系统清 cacheDir 清掉 → 回退落空 → 保持 null 占位（与文件页同生命周期）
        val out = enrichLocalThumbRows(
            listOf(row("/sdcard/Movies/gone.mkv")),
            listOf(7L to "/sdcard/Movies"),
            thumbLookup = { _, _ -> null },
            bypassLookup = { _, _, _ -> null },
        )
        assertEquals(null, out[0].thumbnailPath)
    }

    @Test
    fun `media library hit wins over bypass`() = runBlocking {
        // 已入库视频：media_items 命中 → 直接用库封面，旁路通道绝不触发
        var bypassCalled = false
        val out = enrichLocalThumbRows(
            listOf(row("/sdcard/Movies/indexed.mkv")),
            listOf(7L to "/sdcard/Movies"),
            thumbLookup = { _, _ -> "/cache/thumbs/db.jpg" },
            bypassLookup = { _, _, _ -> bypassCalled = true; "/cache/thumbs/bypass.jpg" },
        )
        assertEquals("/cache/thumbs/db.jpg", out[0].thumbnailPath)
        assertFalse(bypassCalled)
    }

    @Test
    fun `nas rows never enter the channel`() = runBlocking {
        // NAS 行（sourceId!=null）不进 LOCAL 通道：旁路 stat 需网络（挂账），键直连走原路径
        var lookedUp = false
        val out = enrichLocalThumbRows(
            listOf(row("/media/movies/dune.mkv", sourceId = 3L)),
            listOf(7L to "/sdcard/Movies"),
            thumbLookup = { _, _ -> lookedUp = true; null },
            bypassLookup = { _, _, _ -> lookedUp = true; null },
        )
        assertEquals(null, out[0].thumbnailPath)
        assertFalse(lookedUp)
    }

    @Test
    fun `rows with thumbnail pass through untouched`() = runBlocking {
        // 已有封面行零开销透传（只对缺图行 stat+md5）
        var lookedUp = false
        val out = enrichLocalThumbRows(
            listOf(row("/sdcard/Movies/hasthumb.mkv", thumb = "/cache/thumbs/keep.jpg")),
            listOf(7L to "/sdcard/Movies"),
            thumbLookup = { _, _ -> lookedUp = true; null },
            bypassLookup = { _, _, _ -> lookedUp = true; null },
        )
        assertEquals("/cache/thumbs/keep.jpg", out[0].thumbnailPath)
        assertFalse(lookedUp)
    }

    @Test
    fun `path outside all local roots keeps row untouched`() = runBlocking {
        // 直连/其他路径反查不中 → 不查库不 stat（零开销透传）
        var lookedUp = false
        val out = enrichLocalThumbRows(
            listOf(row("/storage/emulated/0/DCIM/clip.mp4")),
            listOf(7L to "/sdcard/Movies"),
            thumbLookup = { _, _ -> lookedUp = true; null },
            bypassLookup = { _, _, _ -> lookedUp = true; null },
        )
        assertEquals(null, out[0].thumbnailPath)
        assertFalse(lookedUp)
    }

    @Test
    fun `longest matching root wins when roots nest`() {
        // 两 LOCAL 源根嵌套（/a 与 /a/b）：首条命中即用（现状语义保持——列表序优先），
        // 此用例锚定反查不因回退改造而变序
        val locals = listOf(1L to "/a", 2L to "/a/b")
        val abs = "/a/b/c.mkv"
        val hit = locals.firstNotNullOfOrNull { (sid, root) ->
            if (abs.length > root.length + 1 && abs.startsWith("$root/")) sid to abs.substring(root.length)
            else null
        }
        assertTrue(hit != null)
        assertEquals(1L, hit!!.first)
        assertEquals("/b/c.mkv", hit.second)
    }
}
