package com.novax.app.data.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastCodecSniffTest {
    private fun head(vararg markers: String) = markers.joinToString(separator = "").toByteArray()

    @Test fun `mp4头含dvcC判定DV`() {
        assertTrue(CastCodecSniff.isDolbyVision(head("ftypisom", "dvcC"), ByteArray(0)))
    }

    @Test fun `头或尾含dvh1即DV`() {
        assertTrue(CastCodecSniff.isDolbyVision(head("ftyp"), head("moovdvh1")))
    }

    @Test fun `普通片不误报`() {
        assertFalse(CastCodecSniff.isDolbyVision(head("ftypisomavc1moov"), head("free")))
    }

    @Test fun `空字节安全`() {
        assertFalse(CastCodecSniff.isDolbyVision(ByteArray(0), ByteArray(0)))
    }
}
