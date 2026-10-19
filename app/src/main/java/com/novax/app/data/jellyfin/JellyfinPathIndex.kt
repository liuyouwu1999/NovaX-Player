package com.novax.app.data.jellyfin

import com.novax.app.data.NvPaths
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一路径 → Jellyfin itemId 的进程内索引（spec §5：Jellyfin 导航按 id、无真实路径，
 * 面包屑浏览自顶向下逐级 remember；进程死亡/索引丢失时由 client 按 [walkSegments]
 * 从根逐段重新解析——Review Focus 1）。
 * 每源一个实例（client 内按 sourceId 持有，跨调用方共享）；同一索引存在 MediaScanner
 * 扫描协程、UI 浏览、ExoPlayer loader 三路并发读写方（repo 门面并不串行化），
 * 故 ids 用 ConcurrentHashMap 保证并发安全。
 */
class JellyfinPathIndex {

    private val ids = ConcurrentHashMap<String, String>()

    /** [path] 先经 NvPaths 归一；目录带不带尾分隔符等价（统一存不带尾分隔符的形式） */
    fun remember(path: String, id: String) {
        val n = NvPaths.normalize(path)
        if (n != NvPaths.SEPARATOR) ids[n.trimEnd('/')] = id
    }

    fun idOf(path: String): String? {
        val n = NvPaths.normalize(path)
        if (n == NvPaths.SEPARATOR) return null   // 根无需 id：根列表 = Views
        return ids[n.trimEnd('/')]
    }

    /** [path] 的从浅到深逐级路径（不含根；供索引丢失时逐级重解析） */
    fun walkSegments(path: String): List<String> {
        val n = NvPaths.normalize(path)
        if (n == NvPaths.SEPARATOR) return emptyList()
        val segs = n.trim('/').split('/')
        return (1..segs.size).map { i -> "/" + segs.subList(0, i).joinToString("/") }
    }
}
