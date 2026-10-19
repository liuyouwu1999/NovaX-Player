package com.novax.app.player

import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NvNotificationArtworkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun source(type: SourceType) =
        SourceEntity(id = 7L, type = type, nickname = "NAS", shareOrPath = "//s/v")

    private val raw = byteArrayOf(1, 2, 3)
    private val pressed = byteArrayOf(9)

    private fun failCall(what: String): Nothing = throw AssertionError("不应触发$what")

    /**
     * dbFail=true(默认)= DB 被查即失败(JELLYFIN 分支不查 DB 的守卫);
     * 本地分级用例传 dbFail=false 并给 row。download 默认被调即失败。
     */
    private fun resolver(
        dir: File = tmp.newFolder("notif_art"),
        metered: Boolean = false,
        row: NvNotificationArtwork.ThumbRow? = null,
        dbFail: Boolean = true,
        download: (String) -> ByteArray? = { failCall("下载") },
        codecCalls: MutableList<Int>? = null,
        icon: () -> ByteArray? = { failCall("图标回退") },
        browserReader: suspend (Long, String, String) -> ByteArray? = { _, _, _ -> failCall("旁路读取") },
        frameGrab: (suspend (String) -> ByteArray?)? = { failCall("抓帧") },
    ): NvNotificationArtwork {
        val counter = codecCalls
        return NvNotificationArtwork(
            notifArtDir = dir,
            isMetered = { metered },
            findByMediaItem = { _, _ ->
                if (dbFail) failCall("DB 查询")
                row
            },
            downloadPoster = { itemId -> download(itemId) },
            iconFallback = icon,
            browserThumbReader = browserReader,
            frameGrabber = frameGrab,
            codec = {
                counter?.add(1)
                pressed
            },
            io = Dispatchers.Default,
        )
    }

    // ---- 缓存(spec Review Focus 3 前半:命中零网络)----

    @Test
    fun jellyfin_cacheHitSkipsNetworkAndCodec() = runBlocking {
        val dir = tmp.newFolder("notif_art")
        File(dir, NvNotificationArtwork.cacheKey("jf:ITEM1")).writeBytes(pressed)
        val codecCalls = mutableListOf<Int>()
        val art = resolver(dir = dir, codecCalls = codecCalls, download = { failCall("缓存命中不应下载") })
        // 缓存命中路径经 File.readBytes() 回读,JUnit assertEquals 对 byte[] 按引用比较,
        // 必须用 assertArrayEquals 按内容比较(与任务书原文的差异,原因见任务报告)
        assertArrayEquals(pressed, art.resolve(source(SourceType.JELLYFIN), "p", "ITEM1"))
        assertTrue(codecCalls.isEmpty())
    }

    @Test
    fun local_cacheHitSkipsDecodeAndSourceRead() = runBlocking {
        val dir = tmp.newFolder("notif_art")
        // 本地分支 DB 查询先行(key 派生自 DB 行 thumbnailPath,二轮评审定稿);命中省的是解码与源文件读
        val thumb = tmp.newFile("th.jpg").apply { writeBytes(raw) }
        File(dir, NvNotificationArtwork.cacheKey("local:$thumb")).writeBytes(pressed)
        val codecCalls = mutableListOf<Int>()
        val art = resolver(
            dir = dir,
            row = NvNotificationArtwork.ThumbRow(scrapePosterPath = null, thumbnailPath = thumb.absolutePath),
            dbFail = false,
            codecCalls = codecCalls,
            download = { failCall("本地路径不应下载") },
        )
        assertArrayEquals(pressed, art.resolve(source(SourceType.SMB), "ep1.mp4", null))
        assertTrue(codecCalls.isEmpty())
    }

    // ---- 计量闸门(spec Review Focus 3:闸门先行,连登录 POST 都不碰)----

    @Test
    fun jellyfin_meteredSkipsDownloadEntirely() = runBlocking {
        // 终审修复：原 failCall 闸门的 AssertionError 会被 resolve 的 runCatching 吞掉
        // （删掉闸门测试仍绿，零区分度）——download 改「返回真字节并计数」，
        // 断言 计数==0 且 结果==null：闸门失效时 resolve 会拿到 pressed，断言必红
        var downloads = 0
        val art = resolver(metered = true, download = { downloads++; raw })
        assertNull(art.resolve(source(SourceType.JELLYFIN), "p", "ITEM1"))
        assertEquals(0, downloads)
    }

    @Test
    fun jellyfin_nullItemIdSkipsDownload() = runBlocking {
        var downloads = 0
        val art = resolver(download = { downloads++; raw })
        assertNull(art.resolve(source(SourceType.JELLYFIN), "p", null))
        assertEquals(0, downloads)
    }

    // ---- 本地分级(spec §4.2:刮削海报优先、空串墓碑、文件缺失回落)----

    @Test
    fun local_scrapePosterPreferredOverThumb() = runBlocking {
        val poster = tmp.newFile("poster.jpg").apply { writeBytes(raw) }
        val art = resolver(
            row = NvNotificationArtwork.ThumbRow(scrapePosterPath = poster.absolutePath, thumbnailPath = "/gone/th.jpg"),
            dbFail = false,
            download = { failCall("本地路径不应下载") },
        )
        assertEquals(pressed, art.resolve(source(SourceType.LOCAL), "movie.mkv", null))
    }

    @Test
    fun local_blankScrapeTombstoneFallsToThumb() = runBlocking {
        val thumb = tmp.newFile("th.jpg").apply { writeBytes(raw) }
        val art = resolver(
            row = NvNotificationArtwork.ThumbRow(scrapePosterPath = "", thumbnailPath = thumb.absolutePath),
            dbFail = false,
            download = { failCall("本地路径不应下载") },
        )
        assertEquals(pressed, art.resolve(source(SourceType.SMB), "ep1.mp4", null))
    }

    @Test
    fun local_missingThumbFileReturnsNull() = runBlocking {
        val art = resolver(
            row = NvNotificationArtwork.ThumbRow(scrapePosterPath = null, thumbnailPath = "/evicted/gone.jpg"),
            dbFail = false,
            download = { failCall("本地路径不应下载") },
        )
        assertNull(art.resolve(source(SourceType.SMB), "ep1.mp4", null))
    }

    @Test
    fun local_nullRowReturnsNull() = runBlocking {
        val art = resolver(row = null, dbFail = false, download = { failCall("本地路径不应下载") })
        assertNull(art.resolve(source(SourceType.SMB), "ep1.mp4", null))
    }

    // ---- 失败回落(spec §5.3:永不抛)----

    @Test
    fun jellyfin_downloadFailureReturnsNull() = runBlocking {
        val art = resolver(download = { throw java.io.IOException("boom") })
        assertNull(art.resolve(source(SourceType.JELLYFIN), "p", "ITEM1"))
    }

    @Test
    fun nullSourceReturnsNull() = runBlocking {
        val art = resolver(download = { failCall("无 source 不应下载") })
        assertNull(art.resolve(null, "p", "ITEM1"))
    }

    // ---- 二次走缓存 ----

    @Test
    fun secondCallHitsCache() = runBlocking {
        val dir = tmp.newFolder("notif_art")
        var downloads = 0
        val art = resolver(dir = dir, download = { downloads++; raw })
        // 第二次经缓存回读是新数组,按内容断言(同上,任务书原文差异)
        assertArrayEquals(pressed, art.resolve(source(SourceType.JELLYFIN), "p", "ITEM1"))
        assertArrayEquals(pressed, art.resolve(source(SourceType.JELLYFIN), "p", "ITEM1"))
        assertEquals(1, downloads)
    }

    // ---- 图标垫底(2026-10-10 补丁 A:解析落空 → app 图标字节,场景 B 纯色块修复)----

    @Test
    fun fallback_resolverMissReturnsIconBytes() = runBlocking {
        val icon = byteArrayOf(7, 8, 9)
        // 场景 B 本体:已索引源查无此行(未索引文件直开)→ 图标字节,不再是 null/纯色块
        val art = resolver(row = null, dbFail = false, icon = { icon })
        assertArrayEquals(icon, art.resolve(source(SourceType.LOCAL), "unindexed.mp4", null))
    }

    @Test
    fun fallback_resolverHitSkipsIcon() = runBlocking {
        val thumb = tmp.newFile("th.jpg").apply { writeBytes(raw) }
        val art = resolver(
            row = NvNotificationArtwork.ThumbRow(scrapePosterPath = null, thumbnailPath = thumb.absolutePath),
            dbFail = false,
            icon = { failCall("解析命中不应回退图标") },
        )
        assertEquals(pressed, art.resolve(source(SourceType.SMB), "ep1.mp4", null))
    }

    @Test
    fun fallback_iconThrowStillNull() = runBlocking {
        // 永不抛纪律(§5.3):图标加载自身失败 = null,照旧发无图通知
        val art = resolver(row = null, dbFail = false, icon = { throw IllegalStateException("icon boom") })
        assertNull(art.resolve(source(SourceType.LOCAL), "unindexed.mp4", null))
    }

    // ---- 2026-10-12 spec §4.5 第4/5级(LOCAL;零网络;永不抛)----

    /** 本任务新用例共用的已索引 LOCAL 源(4/5 级仅 LOCAL;SMB 走 smbSource 用例单建) */
    private val src = source(SourceType.LOCAL)

    /** lengthOf 门卫要求真实存在的非空文件——统一用临时文件当 absPath */
    private fun mediaFile(name: String) = tmp.newFile(name).apply { writeBytes(ByteArray(8)) }

    @Test
    fun local_bypassHitReturnsReaderBytes_grabNotCalled() = runBlocking {
        val video = mediaFile("a.mp4")
        val reads = mutableListOf<String>()
        val r = resolver(
            row = null, dbFail = false,
            browserReader = { s, p, a -> reads.add("$s|$p|$a"); raw },
            frameGrab = { failCall("抓帧") },
            icon = { failCall("图标回退") },
        )
        assertArrayEquals(pressed, r.resolve(src, "a.mp4", null, absPath = video.path))
        assertEquals(listOf("7|a.mp4|${video.path}"), reads)
    }

    @Test
    fun local_indexedRowEmptyThumb_grabFrameWritesNotifArtCache() = runBlocking {
        val dir = tmp.newFolder("notif_art")
        val video = mediaFile("a.mp4")
        val grabs = mutableListOf<String>()
        val r = resolver(
            dir = dir, row = NvNotificationArtwork.ThumbRow(null, null), dbFail = false,
            browserReader = { _, _, _ -> null },
            frameGrab = { a -> grabs.add(a); raw },
        )
        assertArrayEquals(pressed, r.resolve(src, "a.mp4", null, absPath = video.path))
        assertEquals(1, dir.listFiles()!!.size)          // 第5级 notif_art 落缓存
        assertEquals(1, grabs.size)
        // 同参第二次:缓存命中,不再抓帧(spec §5.1 行3)
        assertArrayEquals(pressed, r.resolve(src, "a.mp4", null, absPath = video.path))
        assertEquals(1, grabs.size)
    }

    @Test
    fun local_level2Hit_shortCircuitsReaderAndGrab() = runBlocking {
        val thumbs = tmp.newFolder("thumbs")
        val thumb = File(thumbs, "t.jpg").apply { writeBytes(raw) }
        val r = resolver(
            row = NvNotificationArtwork.ThumbRow(null, thumb.path), dbFail = false,
            browserReader = { _, _, _ -> failCall("旁路读取") },
            frameGrab = { failCall("抓帧") },
        )
        assertArrayEquals(pressed, r.resolve(src, "a.mp4", null, absPath = mediaFile("a.mp4").path))
    }

    @Test
    fun nullSource_directAbsPath_grabsWithDirectKey() = runBlocking {
        val dir = tmp.newFolder("notif_art")
        val video = mediaFile("x.mp4")
        val r = resolver(
            dir = dir, dbFail = false,
            frameGrab = { raw }, icon = { failCall("图标回退") },
        )
        assertArrayEquals(pressed, r.resolve(null, null, null, absPath = video.path))
        // spec §5.1 行5:key 前缀 direct:
        val expected = File(dir, NvNotificationArtwork.cacheKey("frame:direct:${video.path}:${video.length()}"))
        assertTrue(expected.exists())
    }

    @Test
    fun grabThrows_fallsToIcon_neverThrows() = runBlocking {
        val video = mediaFile("a.mp4")
        val r = resolver(
            row = null, dbFail = false,
            browserReader = { _, _, _ -> null },
            frameGrab = { throw IllegalStateException("boom") },
            icon = { byteArrayOf(7) },
        )
        assertArrayEquals(byteArrayOf(7), r.resolve(src, "a.mp4", null, absPath = video.path))
    }

    @Test
    fun grabReturnsNull_fallsToIcon() = runBlocking {
        val video = mediaFile("a.mp4")
        val r = resolver(
            row = null, dbFail = false,
            browserReader = { _, _, _ -> null },
            frameGrab = { null },
            icon = { byteArrayOf(7) },
        )
        assertArrayEquals(byteArrayOf(7), r.resolve(src, "a.mp4", null, absPath = video.path))
    }

    @Test
    fun grabSlowSuspension_stillFallsToIcon() = runBlocking {
        val video = mediaFile("a.mp4")
        val r = resolver(
            row = null, dbFail = false,
            browserReader = { _, _, _ -> null },
            // resolver 级语义:慢/挂起实现不挂死解析、落图标;1s 硬墙钟本身归 Task 3 线程形态评审项
            // (spec §4.5 轮2 P2:withTimeoutOrNull 对阻塞 native 非硬墙钟)
            frameGrab = { kotlinx.coroutines.delay(150); null },
            icon = { byteArrayOf(7) },
        )
        assertArrayEquals(byteArrayOf(7), r.resolve(src, "a.mp4", null, absPath = video.path))
    }

    @Test
    fun smbSource_neverTouchesLocalFallbacks() = runBlocking {
        val r = resolver(   // browserReader/frameGrab 默认被调即失败
            row = null, dbFail = false,
            icon = { byteArrayOf(7) },
        )
        assertArrayEquals(byteArrayOf(7), r.resolve(source(SourceType.SMB), "a.mp4", null, absPath = "//s/v/a.mp4"))
    }

    @Test
    fun zeroSize_skipsLocalFallbacks() = runBlocking {
        val empty = tmp.newFile("empty.mp4")   // newFile 创建的即 0 字节文件
        val r = resolver(
            row = null, dbFail = false,
            icon = { byteArrayOf(7) },
        )
        assertArrayEquals(byteArrayOf(7), r.resolve(src, "a.mp4", null, absPath = empty.path))
    }
}
