package com.novax.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * NvAvOffsetAudioProcessor 纯 JVM 单测：正偏移=延迟队列（configure/flush 预填静音保证
 * 输出连续）、负偏移=流首丢料、flush 重武装、EOS/isEnded 时序（审核 S1）、
 * 变速缩放（有效偏移 = offset × 倍速，2026-09-25 倍速音画不同步根治）。
 * 48kHz 立体声 16bit → bytesPerFrame=4；250ms = 48_000 帧 = 192_000 字节。
 */
class NvAvOffsetAudioProcessorTest {

    private val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    /** currentSpeed 是 companion 静态态，逐测试复位防跨用例泄漏 */
    @After
    fun resetSpeed() {
        NvAvOffsetAudioProcessor.currentSpeed = 1f
    }

    private fun buffer(bytes: Int, fill: Byte = 0x11): ByteBuffer =
        ByteBuffer.allocate(bytes).also { for (i in 0 until bytes) it.put(fill) }.apply { flip() }

    private fun drained(p: NvAvOffsetAudioProcessor): ByteArray {
        val out = ByteBuffer.allocate(1 shl 21)
        while (true) {
            val b = p.getOutput()
            if (!b.hasRemaining()) break
            out.put(b)
        }
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun `零偏移不激活`() {
        val p = NvAvOffsetAudioProcessor(0)
        org.junit.Assert.assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            p.configure(format)
        }
        assertFalse(p.isActive)
    }

    @Test
    fun `偏移量按上下限夹取`() {
        val p = NvAvOffsetAudioProcessor(99_999)
        p.configure(format)
        assertTrue(p.isActive)
        val p2 = NvAvOffsetAudioProcessor(-99_999)
        p2.configure(format)
        assertTrue(p2.isActive)
    }

    @Test
    fun `正偏移五百毫秒的延迟队列与EOS时序`() {
        val p = NvAvOffsetAudioProcessor(500)
        p.configure(format)
        assertTrue(p.isActive)
        val delayBytes = 96_000L // 500ms @ 48k×4B
        // configure 已预填 delayBytes 静音（J2：输出连续不 underrun）
        p.queueInput(buffer(192_000)) // 1s 内容
        val out = drained(p)
        assertEquals("1s 内容 + 0.5s 预填静音 - 0.5s 持有 = 1s 输出", 192_000, out.size.toLong())
        assertTrue("输出首段应为预填静音", out.take(96_000).all { it.toInt() == 0 })
        p.queueEndOfStream()
        // S1 时序：尾料未取干前 isEnded 必须为 false（否则 sink 短路丢尾料）
        assertFalse(p.isEnded)
        val tail = drained(p)
        assertEquals("EOS 后尾巴全部放行", delayBytes, tail.size.toLong())
        assertTrue(p.isEnded)
    }

    @Test
    fun `负偏移二百五十毫秒的流首丢料`() {
        val p = NvAvOffsetAudioProcessor(-250)
        p.configure(format)
        val dropBytes = 48_000L // 250ms @ 48k×4B
        p.queueInput(buffer(192_000))
        val out = drained(p)
        assertEquals("流首应丢 250ms，其余放行", 192_000 - dropBytes, out.size.toLong())
        // 同流后续输入不再丢（补偿只武装在流起点）
        p.queueInput(buffer(192_000, fill = 0x22))
        assertEquals(192_000, drained(p).size.toLong())
        // 负偏移 + EOS：无持有尾料，立即 ended
        p.queueEndOfStream()
        assertTrue(p.isEnded)
    }

    @Test
    fun `负偏移丢料跨多个输入缓冲（首帧不足偏移量）`() {
        val p = NvAvOffsetAudioProcessor(-250)
        p.configure(format)
        val dropBytes = 48_000L
        p.queueInput(buffer(20_000)) // 首帧不足偏移量 → 全吞
        assertEquals(0, drained(p).size)
        p.queueInput(buffer(192_000)) // 补齐剩余丢料后放行
        val out = drained(p)
        assertEquals("应丢满 48_000 后放行其余", 20_000 + 192_000 - dropBytes, out.size.toLong())
    }

    @Test
    fun `正偏移分多帧喂入的守恒`() {
        val p = NvAvOffsetAudioProcessor(250)
        p.configure(format)
        val delayBytes = 48_000L
        var totalIn = 0L
        var totalOut = 0L
        repeat(10) {
            p.queueInput(buffer(48_000, fill = (it + 1).toByte()))
            totalIn += 48_000
            totalOut += drained(p).size
        }
        p.queueEndOfStream()
        val tail = drained(p)
        totalOut += tail.size
        // 预填静音 delayBytes 在队列里，EOS 后随尾料放行 → 总输出 = 总输入 + delayBytes
        assertEquals("总输出应等于总输入 + 预填静音", totalIn + delayBytes, totalOut)
        assertTrue(p.isEnded)
    }

    @Test
    fun `flush 后负偏移重新武装`() {
        val p = NvAvOffsetAudioProcessor(-250)
        p.configure(format)
        p.queueInput(buffer(192_000))
        drained(p)
        p.flush() // seek/变速冲刷
        p.queueInput(buffer(192_000))
        val out = drained(p)
        assertEquals("flush 后应重新丢 250ms", 192_000 - 48_000L, out.size.toLong())
    }

    @Test
    fun `flush 后正偏移重建延迟`() {
        val p = NvAvOffsetAudioProcessor(500)
        p.configure(format)
        p.queueInput(buffer(192_000))
        drained(p)
        p.flush()
        // flush 后预填 96_000 静音；再喂 192_000 内容 → 输出 192_000（静音+前半内容）
        p.queueInput(buffer(192_000))
        val out = drained(p)
        assertEquals(192_000, out.size.toLong())
        assertTrue("冲刷后首段应仍为预填静音", out.take(96_000).all { it.toInt() == 0 })
    }

    @Test
    fun `reset 后回到未配置态且可重新配置`() {
        val p = NvAvOffsetAudioProcessor(500)
        p.configure(format)
        p.reset()
        assertFalse(p.isActive)
        p.configure(format)
        assertTrue("reset 后应可重新配置进入激活态", p.isActive)
    }

    // ---- 2026-09-25 变速缩放：有效偏移 = offset × 倍速 ----

    @Test
    fun `负偏移按倍速缩放丢料（2x 翻倍）`() {
        NvAvOffsetAudioProcessor.currentSpeed = 2f
        val p = NvAvOffsetAudioProcessor(-500)
        p.configure(format)
        // 有效偏移 = -500 × 2 = -1000ms 内容 = 192_000B
        p.queueInput(buffer(288_000)) // 1.5s 内容
        val out = drained(p)
        assertEquals("2x 下应丢 1000ms 内容（墙钟 500ms）", 288_000 - 192_000L, out.size.toLong())
    }

    @Test
    fun `flush 后按最新倍速重武装（2x 回 1x 丢料减半）`() {
        NvAvOffsetAudioProcessor.currentSpeed = 2f
        val p = NvAvOffsetAudioProcessor(-500)
        p.configure(format)
        p.queueInput(buffer(288_000))
        drained(p)
        NvAvOffsetAudioProcessor.currentSpeed = 1f
        p.flush() // 变速冲刷：media3 整链 drain+flush，此处读最新速度重武装
        p.queueInput(buffer(192_000))
        assertEquals("回到 1x 应只丢 500ms 内容", 192_000 - 96_000L, drained(p).size.toLong())
    }

    @Test
    fun `正偏移延迟队列按倍速缩放（半速减半）`() {
        NvAvOffsetAudioProcessor.currentSpeed = 0.5f
        val p = NvAvOffsetAudioProcessor(500)
        p.configure(format)
        // 有效偏移 = +500 × 0.5 = +250ms = 48_000B 静音预填
        p.queueInput(buffer(192_000))
        val out = drained(p)
        assertEquals(192_000, out.size.toLong())
        assertTrue("首段应为 250ms 预填静音", out.take(48_000).all { it.toInt() == 0 })
        p.queueEndOfStream()
        assertEquals("EOS 尾巴 = 250ms 持有量", 48_000L, drained(p).size.toLong())
        assertTrue(p.isEnded)
    }
}
