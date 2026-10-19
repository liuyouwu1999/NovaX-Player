package com.novax.app.data.thumb

/** 会话级一次性集合(§1a 文件夹重置去重):进程内 add 语义,首次 true 此后 false */
internal class SessionOnceSet {
    private val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    fun once(key: String): Boolean = seen.add(key)
}
