package com.novax.app.data.privacy

import org.junit.Assert.*; import org.junit.Test

/**
 * 纯 JVM（Task 7 简报四组验收线）：redactor 本体不依赖 Access，covers 为注入参数；
 * 红线 = 锁定内容路径/文件名不进日志与崩溃文件（fail toward more masking）。
 */
class NvPrivacyRedactorTest {

    // ---- ① covers 命中 → 命中路径子串整体替换 [locked] ----
    @Test fun `covers命中_裸路径整体替换locked`() {
        val covers: (Long?, String) -> Boolean = { _, p -> p.startsWith("/media/secret") }
        val out = NvPrivacyRedactor.redactText(
            "orientation timeout: playUrl=/media/secret/Show/S01E01.mkv size=1280x720", covers,
        )
        assertEquals("orientation timeout: playUrl=[locked] size=1280x720", out)
    }

    @Test fun `covers命中_URL按路径部分判中后整条替换`() {
        val covers: (Long?, String) -> Boolean = { _, p -> p.startsWith("/vfs/secret") }
        val out = NvPrivacyRedactor.redactText(
            "SetURI CurrentURI=http://192.168.1.5:8080/vfs/secret/Movie.mp4?token=abc", covers,
        )
        assertEquals("SetURI CurrentURI=[locked]", out)
    }

    /** redactor 无法从自由文本判源——恒以 null sourceId 调 covers（与 T4 裁定口径一致） */
    @Test fun `covers收到nullSourceId`() {
        var seenSid: Long? = Long.MIN_VALUE
        // 注意：新增第三参后 covers 不能再用尾 lambda（尾 lambda 恒绑最后参 lockedDirHints）
        NvPrivacyRedactor.redactText("/a/b/c.mp4", { sid, _ -> seenSid = sid; false })
        assertNull(seenSid)
    }

    // ---- ② covers 未命中 → 文件名打码保留扩展名（目录结构保留便于排障） ----
    @Test fun `未命中_文件名打码保留扩展名与目录`() {
        val out = NvPrivacyRedactor.redactText(
            "open /media/Movies/Big.Buck.Bunny.mp4 ok", { _, _ -> false },
        )
        assertEquals("open /media/Movies/***.mp4 ok", out)
    }

    // ---- ③ covers=null → 仍通用掩码 ----
    @Test fun `covers为null_仍通用掩码`() {
        val out = NvPrivacyRedactor.redactText("/media/Movies/私藏.mp4", null)
        assertEquals("/media/Movies/***.mp4", out)
    }

    @Test fun `URL_query整体掩码`() {
        val out = NvPrivacyRedactor.redactText(
            "stream http://192.168.1.5:49152/vfs/Show/S01E02.mkv?token=SECRET&x=1 end", null,
        )
        assertEquals("stream http://192.168.1.5:49152/vfs/Show/***.mkv?*** end", out)
        assertFalse(out.contains("SECRET"))
    }

    // ---- ④ DIDL XML 文本 → 掩文件名不破坏 XML 结构 ----
    @Test fun `DIDL文本_掩文件名不破坏XML结构`() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <item id="0/12" parentID="0/1" restricted="1">
            <dc:title>Movie.mp4</dc:title>
            <res size="123456" duration="0:21:30">http://192.168.1.5:8080/vfs/TV/S01E03.mkv?token=tok</res>
            <upnp:class>object.item.videoItem</upnp:class>
            </item></DIDL-Lite>
        """.trimIndent()
        val out = NvPrivacyRedactor.redactText(didl, null)
        assertFalse(out.contains("Movie.mp4"))
        assertFalse(out.contains("S01E03"))
        assertFalse(out.contains("token=tok"))
        assertTrue(out.contains("***.mp4"))
        assertTrue(out.contains("***.mkv"))
        assertTrue(out.contains("<dc:title>***.mp4</dc:title>"))
        // XML 仍可解析且根/标题元素完好
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals("DIDL-Lite", doc.documentElement.tagName)
        assertEquals("***.mp4", doc.getElementsByTagName("dc:title").item(0).textContent)
    }

    // ---- 稳健性：无路径文本原样返回；已掩码文本幂等 ----
    @Test fun `无路径文本原样返回`() {
        val s = "thread=main app=NovaX debug code=ERROR(2002) at PlayerScreen.kt:933"
        assertEquals(s, NvPrivacyRedactor.redactText(s, null))
    }

    @Test fun `已掩码文本幂等`() {
        val once = NvPrivacyRedactor.redactText("/media/Movies/Big.mp4?token=x", null)
        assertEquals(once, NvPrivacyRedactor.redactText(once, null))
    }

    // ---- 第 1 轮补强：lockedDirHints（崩溃落盘的已锁目录名段兜底） ----
    @Test fun `hints命中_目录段替换lockedDir`() {
        val out = NvPrivacyRedactor.redactText(
            "java.io.FileNotFoundException: /nas/media/私人/秘密.mkv", null,
            listOf("/nas/media/私人"),
        )
        assertEquals("java.io.FileNotFoundException: [lockedDir]/***.mkv", out)
        assertFalse(out.contains("私人"))
        assertFalse(out.contains("秘密"))
    }

    @Test fun `hints多目录_逐个替换`() {
        val out = NvPrivacyRedactor.redactText(
            "scan /a/机密/x.mp4 done; backup /b/私密/y.mkv failed", null,
            listOf("/a/机密", "/b/私密"),
        )
        assertFalse(out.contains("机密"))
        assertFalse(out.contains("私密"))
        assertTrue(out.contains("[lockedDir]/***.mp4"))
        assertTrue(out.contains("[lockedDir]/***.mkv"))
    }

    /** 长者优先：父目录 hint 若抢先替换，子目录段会残留——必须按长度降序替换 */
    @Test fun `hints嵌套_长短都给_目录名不残留`() {
        val out = NvPrivacyRedactor.redactText(
            "open /media/私人/剧集/S01E01.mkv ok", null,
            listOf("/media/私人", "/media/私人/剧集"),
        )
        assertEquals("open [lockedDir]/***.mkv ok", out)
    }

    @Test fun `hints命中XML_不破坏结构`() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <item id="0/12" restricted="1">
            <dc:title>/nas/私人/秘密.mkv</dc:title>
            <res>http://192.168.1.5:8080/vfs/nas/私人/秘密.mkv?token=t</res>
            </item></DIDL-Lite>
        """.trimIndent()
        val out = NvPrivacyRedactor.redactText(didl, null, listOf("/nas/私人"))
        assertFalse(out.contains("私人"))
        assertFalse(out.contains("秘密"))
        assertFalse(out.contains("token=t"))
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals("DIDL-Lite", doc.documentElement.tagName)
    }

    @Test fun `hints空_与不传行为完全一致`() {
        val s = "/media/Movies/Big.mp4 + http://h/v/Cat.mkv?token=x"
        assertEquals(
            NvPrivacyRedactor.redactText(s, null),
            NvPrivacyRedactor.redactText(s, null, emptyList()),
        )
        assertEquals("/media/Movies/***.mp4 + http://h/v/***.mkv?***", NvPrivacyRedactor.redactText(s, null, emptyList()))
    }
}
