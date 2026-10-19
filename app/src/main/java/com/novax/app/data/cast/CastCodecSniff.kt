package com.novax.app.data.cast

/**
 * 投屏前 Dolby Vision best-effort 嗅探（review M6）：读片源头/尾各一小段，
 * 找 mp4/mov 的 DV 盒标记（dvcC/dvwC/dvh1/dvhe）。mkv 封装的 DV 不在覆盖内
 * （无廉价标记），属已知限制——命中仅用于推送前非阻塞提醒，不做拦截。
 */
object CastCodecSniff {
    private val MARKERS = listOf("dvcC", "dvwC", "dvh1", "dvhe")

    fun isDolbyVision(head: ByteArray, tail: ByteArray): Boolean {
        val markers = MARKERS.map { it.toByteArray(Charsets.US_ASCII) }
        return listOf(head, tail).any { chunk ->
            markers.any { chunk.indexOf(it) >= 0 }
        }
    }

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        if (isEmpty() || pattern.isEmpty() || size < pattern.size) return -1
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) {
                if (this[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
