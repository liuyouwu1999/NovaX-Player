package com.novax.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NvSha256Test {

    private val REAL = """
974f2ad6e722a51ab8781257d15a61a7c3546940e6876738197df3543493c333 *NovaX-1.0.0.0-arm64-v8a.apk
78f5277a76ed9883266bc94e10374767d434a31043a17c2e91db0bc0e474f873 *NovaX-1.0.0.0-armeabi-v7a.apk
2189923087c60c1c1e4d5c38525b2479a59839e217585733cfe7873789be8aee *NovaX-1.0.0.0-x86_64.apk
""".trimIndent()

    @Test fun `解析真身格式`() {
        val m = NvSha256SumsParser.parse(REAL)
        assertEquals(3, m.size)
        assertEquals("974f2ad6e722a51ab8781257d15a61a7c3546940e6876738197df3543493c333", m["NovaX-1.0.0.0-arm64-v8a.apk"])
    }
    @Test fun `两空格无星号格式与垃圾行容错`() {
        val m = NvSha256SumsParser.parse("abc  file.apk\ngarbage\n\n${"a".repeat(64)}  b.apk")
        assertEquals(1, m.size)   // "abc"非64hex 被拒
        assertEquals("a".repeat(64), m["b.apk"])
    }
    @Test fun `空文件哈希与matches`() {
        // 与 brief 唯一偏差：createTempFile 前缀 JDK 要求 >=3 字符，"nv" 会抛 IllegalArgumentException
        val f = File.createTempFile("nvx", ".tmp").apply { writeBytes(ByteArray(0)) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", NvSha256.ofFile(f))
        assertTrue(NvSha256.matches(f, "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"))
        assertFalse(NvSha256.matches(f, "deadbeef"))
        f.delete()
    }
}
