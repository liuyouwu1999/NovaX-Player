package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastDidlTest {
    private fun media(sub: String? = null) = CastMedia(
        sourceId = 1, path = "/media/毛骗 S01E03.mp4", title = "毛骗 <S01E03> &花絮",
        sizeBytes = 140_000_000L, durationMs = 240_000L, playUrl = "nvx://1/media/x.mp4",
        subtitlePath = sub,
    )

    @Test fun `转义标题中的 XML 特殊字符`() {
        val didl = CastDidl.build(media(), "http://192.168.1.2:40000/stream", null)
        assertTrue(didl.contains("毛骗 &lt;S01E03&gt; &amp;花絮"))
        assertFalse(didl.contains("<S01E03>"))
    }

    @Test fun `有字幕时输出_sec_双声明`() {
        val didl = CastDidl.build(media("/s/xx.srt"), "http://ip:1/stream", "http://ip:1/subtitle")
        assertTrue(didl.contains("xmlns:sec=\"http://www.sec.co.kr/dlna\""))
        assertTrue(didl.contains("sec:type=\"srt\""))
        assertTrue(didl.contains("CaptionInfoEx"))
        assertTrue(didl.contains("http://ip:1/subtitle"))
    }

    @Test fun `无字幕时不出现字幕声明`() {
        val didl = CastDidl.build(media(), "http://ip:1/stream", null)
        assertFalse(didl.contains("CaptionInfoEx"))
        assertFalse(didl.contains("x-subrip"))
    }

    @Test fun `视频_MIME_按扩展名映射`() {
        assertTrue(CastMime.videoMimeOf("/a/x.mkv").contains("x-matroska"))
        assertTrue(CastMime.videoMimeOf("/a/x.mp4").contains("mp4"))
        val rmvb = CastMime.videoMimeOf("/a/x.rmvb")
        assertTrue(rmvb.contains("rmvb") || rmvb.contains("octet-stream"))
    }

    @Test fun `时长格式化与缺失容忍`() {
        val didl = CastDidl.build(media(), "http://ip:1/stream", null)
        assertTrue(didl.contains("""duration="0:04:00""""))
        val noDur = CastDidl.build(media().copy(durationMs = 0), "http://ip:1/stream", null)
        assertFalse(noDur.contains("duration="))
    }

    /** 返工3 修复 1：DLNA 第 4 参数 OP/CI 必填——缺 CI 被 WMP 类渲染器静默拒播（连上但不播） */
    @Test fun `protocolInfo_含OP与CI两项_WMP严格栈不拒`() {
        val didl = CastDidl.build(media(), "http://ip:1/stream", null)
        assertTrue(didl.contains(""":DLNA.ORG_OP=01;DLNA.ORG_CI=0""""))
        // 字幕 res 是 sec 扩展声明（4 段 *），不受 DLNA 栈校验约束，保持原样
        val subDidl = CastDidl.build(media("/s/xx.srt"), "http://ip:1/stream", "http://ip:1/subtitle")
        assertTrue(subDidl.contains("""protocolInfo="http-get:*:application/x-subrip:*""""))
    }

    // ── 返工3-续（2026-10-16 真机两问题）：WMP 音频误分类防线 + 未知大小不硬失败 ──

    /** WMP 按 upnp:class 分类媒体（音乐视图/音符图标=audioItem）——恒为 object.item.videoItem */
    @Test fun `upnp_class_恒为_object_item_videoItem_WMP按视频渲染`() {
        for (path in listOf("/a/x.mp4", "/a/x.mkv", "/a/毛骗 S01E01.mpg", "/a/x.wmv")) {
            val didl = CastDidl.build(media().copy(path = path), "http://ip:1/stream", null)
            assertTrue("path=$path", didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
            assertFalse(didl.contains("audioItem"))
        }
    }

    /** 未知大小（三级来源全空）：DIDL res 省略 @size，DLNA 允许（流侧 connection-close 分帧） */
    @Test fun `未知大小省略_res_size属性`() {
        val didl = CastDidl.build(media().copy(sizeBytes = 0L), "http://ip:1/stream", null)
        assertFalse(didl.contains("size="))
        assertTrue(didl.contains("http-get:*:video/mp4"))
        // 已知大小保持带 size（E01 场景：库元数据回填后 DIDL 带 size）
        assertTrue(CastDidl.build(media(), "http://ip:1/stream", null).contains("""size="140000000""""))
    }

    /** DIDL protocolInfo 的 MIME 与 CastMime（= 流服务器 Content-Type）同源一致——WMP 校验两者相符 */
    @Test fun `protocolInfo_MIME与CastMime一致_且常见容器全为video斜杠`() {
        for (path in listOf(
            "/a/x.mp4", "/a/x.mkv", "/a/x.ts", "/a/x.m2ts", "/a/x.mts", "/a/x.tp", "/a/x.avi",
            "/a/x.wmv", "/a/x.asf", "/a/x.flv", "/a/x.webm", "/a/x.3gp", "/a/x.mpg", "/a/x.mpeg",
            "/a/x.vob", "/a/x.ogv",
        )) {
            val didl = CastDidl.build(media().copy(path = path), "http://ip:1/stream", null)
            val mime = didl.substringAfter("protocolInfo=\"http-get:*:").substringBefore(':')
            assertEquals("path=$path", CastMime.videoMimeOf(path), mime)
            assertTrue("path=$path 必须 video/*", mime.startsWith("video/"))
        }
        // 未收录容器才落 octet-stream（WMP 严格模式拒——如实浮出而非误分类为音频）
        assertEquals("application/octet-stream", CastMime.videoMimeOf("/a/x.rmvb"))
        assertEquals("application/octet-stream", CastMime.videoMimeOf("/a/x.unknownext"))
    }
}
