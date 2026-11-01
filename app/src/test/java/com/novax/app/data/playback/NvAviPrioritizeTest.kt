package com.novax.app.data.playback

import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-01 v1.0.0.13 AVI 提位单测：Mp3Extractor 在默认序列中排在 AviExtractor 之前，
 * 其嗅探器会在 AVI 的 movi 数据里搜到疑似 MP3 帧同步即误嗅接管整个文件（真机 NAS 老 AVI
 * 「只出声没画面零提示」根因）——AVI 必须提位到链首。AviExtractor.sniff 只认 RIFF….AVI
 * 魔数，提位对非 AVI 文件零行为变化。
 */
class NvAviPrioritizeTest {

    @Test
    fun `AviExtractor 提位到链首且其余相对序保持`() {
        val mp3 = Mp3Extractor()
        val avi = AviExtractor()
        val out = NvMediaSourceBuilder.prioritizeAvi(listOf(mp3, avi))
        assertEquals(avi, out[0])
        assertEquals(listOf<androidx.media3.extractor.Extractor>(mp3), out.drop(1))
    }

    @Test
    fun `无 AviExtractor 时零变化`() {
        val mp3 = Mp3Extractor()
        val out = NvMediaSourceBuilder.prioritizeAvi(listOf(mp3))
        assertEquals(listOf<androidx.media3.extractor.Extractor>(mp3), out)
    }

    @Test
    fun `AviExtractor 已在链首时零变化`() {
        val avi = AviExtractor()
        val mp3 = Mp3Extractor()
        val out = NvMediaSourceBuilder.prioritizeAvi(listOf(avi, mp3))
        assertEquals(listOf<androidx.media3.extractor.Extractor>(avi, mp3), out)
    }

    /** 兜底：真实嗅探行为锁——AviExtractor 对 AVI 魔数 sniff true、对 MP4 魔数 false */
    @Test
    fun `AviExtractor 嗅探只认 RIFF-AVI 魔数`() {
        val avi = AviExtractor()
        val aviMagic = byteArrayOf(*"RIFF".toByteArray(), 0, 0, 0, 0, *"AVI ".toByteArray())
        val mp4Magic = byteArrayOf(0, 0, 0, 0, *"ftypisom".toByteArray())
        val doSniff = { data: ByteArray ->
            val inStream = java.io.ByteArrayInputStream(data)
            val reader = object : androidx.media3.common.DataReader {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val r = inStream.read(b, off, len)
                    return if (r == -1) -1 else r
                }
            }
            val input = androidx.media3.extractor.DefaultExtractorInput(reader, 0, data.size.toLong())
            try {
                avi.sniff(input)
            } finally {
                inStream.close()
            }
        }
        assertTrue(doSniff(aviMagic + ByteArray(16)))
        assertTrue(!doSniff(mp4Magic + ByteArray(8)))
    }
}
