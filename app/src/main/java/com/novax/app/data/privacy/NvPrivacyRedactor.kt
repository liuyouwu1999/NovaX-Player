package com.novax.app.data.privacy

/**
 * 日志/异常/崩溃落盘通用脱敏（隐私功能计划 Task 7）：纯 JVM、无 Android 依赖，
 * 全 App 凡含媒体 URL/路径/文件名的日志出口与崩溃落盘统一包一层（红线：锁定内容
 * 路径/文件名不进日志与崩溃文件）。
 *
 * 两层语义：
 * 1. covers 命中（锁/无痕规则覆盖）→ 命中的路径子串整体替换 "[locked]"。covers 拿到的是
 *    从文本提取的候选路径与 **恒为 null 的 sourceId**（redactor 无法从自由文本判源，与
 *    T4「sourceId==null 不命中」裁定一致）——调用方若掌握 sourceId 应闭包捕获后再转发
 *    index.covers；无 access 的调用点直接传 null 走通用掩码。
 * 2. covers 为 null 或未命中 → 通用掩码：常见媒体扩展名的文件名打码保留扩展名（目录结构
 *    保留便于排障）、URL query 整体掩码（stream token 不落日志）。
 * 3. lockedDirHints（可选，默认空集零行为变化）：hint 目录子串替换 "[lockedDir]"——崩溃落盘
 *    的目录名段兜底（规格 §7.11「无已锁路径明文」含目录名；NvCrashLog 经
 *    NvPrivacyAccess.hintsIfReady 供进程内存快照）。
 *
 * 已知取舍（fail toward more masking）：含空格的路径按空白截断——截断前缀命中 covers 即
 * 整段锁、残余词再走通用文件名掩码；只保证红线（不出明文），不追求完美解析。
 */
object NvPrivacyRedactor {

    /** 占位符与 NvPrivacyAccess.redactOf 同词；独立常量防 redactor 反向依赖 Access */
    private const val LOCKED = "[locked]"
    private const val LOCKED_DIR = "[lockedDir]"
    private const val MASK = "***"

    private val URI_RE = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>\\]+""")
    private val PATH_RE = Regex("""/[^\s"'<>\\]*""")
    private val NAME_RE = Regex("""[^\s"'<>\\/]+[.][A-Za-z0-9]{1,5}\b""")

    /** 常见媒体扩展名（视频+字幕+音频+播放列表）：集合从宽，宁多掩不漏掩；刻意不含 xml 等非媒体后缀 */
    private val MEDIA_EXTS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts",
        "mts", "vob", "3gp", "ogv", "rm", "rmvb", "asf", "divx", "f4v", "iso",
        "srt", "ass", "ssa", "sub", "vtt", "idx", "sup", "smi",
        "mp3", "flac", "aac", "m4a", "wav", "ogg", "oga", "opus", "wma", "ape", "mka", "m3u8",
    )

    fun redactText(
        text: String,
        covers: ((sourceId: Long?, path: String) -> Boolean)?,
        lockedDirHints: Collection<String> = emptyList(),
    ): String {
        if (text.isEmpty()) return text
        var out = text
        // 已锁目录名段兜底（崩溃落盘等 covers 恒 null 的出口）：hint 子串（含作为路径段出现处）
        // 整体替换 [lockedDir]；空集零行为变化。长度降序——父目录 hint 若抢先把子目录 hint
        // 拦腰替换会残留子段；length>1 滤掉空串与裸 "/"（否则所有路径分隔符全被替换）
        if (lockedDirHints.isNotEmpty()) {
            for (hint in lockedDirHints.sortedByDescending { it.length }) {
                if (hint.length > 1) out = out.replace(hint, LOCKED_DIR)
            }
        }
        if (covers != null) {
            out = lockUris(out, covers)
            out = lockPaths(out, covers)
        }
        out = maskUris(out)
        out = maskPathsAndNames(out)
        return out
    }

    // ---- 第 1 层：covers 命中 → [locked]（先于通用掩码，命中即整体替换不再细掩） ----

    private fun lockUris(text: String, covers: (Long?, String) -> Boolean): String =
        URI_RE.replace(text) { m ->
            if (uriCovers(covers, m.value)) LOCKED else m.value
        }

    /** URL 候选：整条（去 query）与路径部分（scheme://host 之后）都问一遍 covers，任一命中即整条锁 */
    private fun uriCovers(covers: (Long?, String) -> Boolean, uri: String): Boolean {
        val noQuery = uri.substringBefore('?')
        val schemeEnd = uri.indexOf("://")
        val pathStart = if (schemeEnd >= 0) uri.indexOf('/', schemeEnd + 3) else -1
        if (covers(null, noQuery)) return true
        return pathStart >= 0 && covers(null, noQuery.substring(pathStart))
    }

    private fun lockPaths(text: String, covers: (Long?, String) -> Boolean): String =
        PATH_RE.replace(text) { m ->
            val p = m.value.trimEnd('/')
            if (p.length > 1 && covers(null, p)) LOCKED else m.value
        }

    // ---- 第 2 层：通用掩码（文件名打码保留扩展名 + URL query 掩码） ----

    private fun maskUris(text: String): String =
        URI_RE.replace(text) { m ->
            val uri = m.value
            val query = uri.indexOf('?')
            val masked = maskLastSegment(if (query >= 0) uri.substring(0, query) else uri)
            if (query >= 0) "$masked?***" else masked
        }

    private fun maskPathsAndNames(text: String): String {
        val paths = PATH_RE.replace(text) { m -> maskLastSegment(m.value) }
        // 裸文件名（无斜杠上下文，如 file.name 日志）兜底；对已掩码结果幂等
        return NAME_RE.replace(paths) { m ->
            val name = m.value
            val dot = name.lastIndexOf('.')
            if (name.substring(dot + 1).lowercase() in MEDIA_EXTS) MASK + name.substring(dot) else name
        }
    }

    /** 最后一段为媒体扩展名文件名 → 掩码茎保留扩展名（dot==0 的 .hidden 不动） */
    private fun maskLastSegment(s: String): String {
        val core = s.trimEnd('/')
        val slash = core.lastIndexOf('/')
        val last = if (slash >= 0) core.substring(slash + 1) else core
        val dot = last.lastIndexOf('.')
        if (dot <= 0) return s
        if (last.substring(dot + 1).lowercase() !in MEDIA_EXTS) return s
        return core.substring(0, slash + 1) + MASK + last.substring(dot) + s.substring(core.length)
    }
}
