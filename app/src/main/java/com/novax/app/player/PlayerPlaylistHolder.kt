package com.novax.app.player

/**
 * 文件夹内播放队列的进程内交接槽（2026-09-15 用户需求：文件夹里打开视频可上/下切换）。
 *
 * 为什么不经 Intent：队列 = 同目录全部视频的 url+标题，逐项 parcel 冗余且 1MB TransactionLimit
 * 有风险。FilesScreen 播放时 put() 得 queueId → Intent 只带 id；PlayerActivity 按 id get()。
 *
 * LRU 上限 8 份（ path 字符串，内存可忽略）：不主动删除 —— Activity 重建（旋转/系统回收 UI）
 * 后 onCreate 重读同一 queueId 队列仍在，上一个/下一个按钮不消失；超限淘汰最旧队列无害
 * （对应播放器早已退出）。
 */
object PlayerPlaylistHolder {

    /** (播放 url, 展示标题) 有序列表 */
    private const val MAX_QUEUES = 8

    private val queues = object : LinkedHashMap<String, List<Pair<String, String>>>(
        MAX_QUEUES * 4 / 3 + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, String>>>): Boolean =
            size > MAX_QUEUES
    }

    /** 登记队列并返回 queueId（Intent extra 传递） */
    @Synchronized
    fun put(items: List<Pair<String, String>>): String {
        val id = "queue-" + System.nanoTime().toString(36)
        queues[id] = items
        return id
    }

    /** 按 id 取队列（不删除，支持 Activity 重建复用）；未知 id（进程被杀后重建）返回 null */
    @Synchronized
    fun get(id: String?): List<Pair<String, String>>? = id?.let { queues[it] }
}
