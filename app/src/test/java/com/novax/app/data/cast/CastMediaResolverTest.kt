package com.novax.app.data.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastMediaResolverTest {
    // review C1：novaxUrl 的 path 是 Uri.encode 过的，必须解码回源内路径
    @Test fun `novax地址解码中文与空格路径`() {
        // 生产 URL 形态：Uri.encode(path, "/")——保留 "/"、空格→%20、中文→%XX
        val (sourceId, path) = CastMediaResolver.parseNovaxPath(
            "novax://1/media/%E6%AF%9B%E9%AA%97%20S01E03.mp4",
        )!!
        assertEquals(1L, sourceId)
        assertEquals("/media/毛骗 S01E03.mp4", path)
    }

    @Test fun `非novax地址返回null`() {
        assertEquals(null, CastMediaResolver.parseNovaxPath("/storage/emulated/0/a.mp4"))
    }
}
