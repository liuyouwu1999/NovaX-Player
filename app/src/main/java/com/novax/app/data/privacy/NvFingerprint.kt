package com.novax.app.data.privacy

/**
 * 目录内容指纹：条目按名称稳定排序后逐行 hmac("name:size")，再对逐行结果序列整体 hmac。
 * 目录改名但条目集不变 → 指纹不变（Task 10 改名跟随的跟随判据）；内容增删/大小变化 → 指纹变。
 * hmac 由调用方注入（生产走 NvPrivacyCipher.hmac，测试传真函数）；空目录 = hmac("")。
 */
object NvFingerprint {

    /** 截断取稳定排序前 50：防超巨目录指纹成本失控；口径固定保证同名集合同指纹 */
    const val MAX_ENTRIES = 50

    fun of(entries: List<Pair<String, Long>>, hmac: (String) -> String): String {
        val lines = entries
            .sortedBy { it.first }   // sortedBy 稳定：同名条目入参顺序不影响截断与结果
            .take(MAX_ENTRIES)
            .map { (name, size) -> hmac("$name:$size") }
        // 逐行指纹为定长 hex，不含换行，"\n" 连接无歧义；空列表连接得 "" → hmac("")
        return hmac(lines.joinToString("\n"))
    }

    fun matches(a: String, b: String): Boolean = a == b
}
