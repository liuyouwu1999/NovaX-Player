package com.novax.app.data.privacy

import org.junit.Assert.*; import org.junit.Test
import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec

/**
 * 纯 JVM：hmac 以函数参数注入（真 HmacSHA256 固定密钥，断言要真）。
 * Task 5 简报五组验收线：同内容同指纹 / 顺序无关 / 大小不同不同 / 超 50 截断稳定 / 空目录相等。
 */
class NvFingerprintTest {

    // 真 HmacSHA256 固定密钥：同输入恒同输出，指纹比较才有意义
    private fun hmac(): (String) -> String {
        val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "HmacSHA256")
        return { plain ->
            Mac.getInstance("HmacSHA256").run { init(key); doFinal(plain.toByteArray()) }
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun fp(vararg entries: Pair<String, Long>): String =
        NvFingerprint.of(entries.toList(), hmac())

    // ① 同内容同指纹
    @Test fun `同内容同指纹`() {
        assertEquals(fp("a.mkv" to 1L, "b.mkv" to 2L), fp("a.mkv" to 1L, "b.mkv" to 2L))
    }

    // ② 顺序无关：入参顺序打乱指纹不变（Task 10 改名跟随的前提）
    @Test fun `顺序无关`() {
        assertEquals(
            fp("a.mkv" to 1L, "b.mkv" to 2L, "c.mkv" to 3L),
            fp("c.mkv" to 3L, "a.mkv" to 1L, "b.mkv" to 2L),
        )
    }

    // ③ 大小不同不同 / 名称不同也不同（name:size 逐行进 hmac，任一变即指纹变）
    @Test fun `大小或名称不同_指纹不同`() {
        assertNotEquals(fp("a.mkv" to 1L), fp("a.mkv" to 2L))
        assertNotEquals(fp("a.mkv" to 1L), fp("b.mkv" to 1L))
        assertNotEquals(fp("a.mkv" to 1L), fp("a.mkv" to 1L, "b.mkv" to 2L))   // 增删条目也变
    }

    // ④ 超 50 截断稳定：恒取稳定排序前 50，且打乱入参顺序截断结果一致
    @Test fun `超50截断稳定`() {
        assertEquals(50, NvFingerprint.MAX_ENTRIES)
        val sixty = (0 until 60).map { "f%02d".format(it) to (it + 1).toLong() }
        val first50 = sixty.take(50)
        val last50 = sixty.takeLast(50)
        assertEquals(NvFingerprint.of(first50, hmac()), NvFingerprint.of(sixty, hmac()))
        assertNotEquals(NvFingerprint.of(last50, hmac()), NvFingerprint.of(sixty, hmac()))
        // 打乱顺序后仍取同一批（稳定排序截断，不依赖入参顺序）
        assertEquals(
            NvFingerprint.of(sixty, hmac()),
            NvFingerprint.of(sixty.shuffled(java.util.Random(42)), hmac()),
        )
    }

    // ⑤ 空目录相等：空条目集 = hmac("")
    @Test fun `空目录等于hmac空串`() {
        assertEquals(hmac()(""), NvFingerprint.of(emptyList(), hmac()))
        assertEquals(NvFingerprint.of(emptyList(), hmac()), NvFingerprint.of(emptyList(), hmac()))
    }

    // matches = 字符串相等
    @Test fun `matches即字符串相等`() {
        val x = fp("a.mkv" to 1L)
        val y = fp("b.mkv" to 1L)
        assertTrue(NvFingerprint.matches(x, x))
        assertFalse(NvFingerprint.matches(x, y))
    }
}
