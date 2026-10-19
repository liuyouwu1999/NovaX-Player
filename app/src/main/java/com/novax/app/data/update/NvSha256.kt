package com.novax.app.data.update

import java.io.File
import java.security.MessageDigest

/** `<64hex> *<file>` 与 `<64hex>  <file>` 双格式兼容（真身为二进制星号格式） */
object NvSha256SumsParser {
    private val line = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
    fun parse(text: String): Map<String, String> = text.lines().mapNotNull {
        val m = line.find(it.trim()) ?: return@mapNotNull null
        m.groupValues[2].trim() to m.groupValues[1].lowercase()
    }.toMap()
}

object NvSha256 {
    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun matches(file: File, expectedHex: String): Boolean = ofFile(file) == expectedHex.lowercase()
}
