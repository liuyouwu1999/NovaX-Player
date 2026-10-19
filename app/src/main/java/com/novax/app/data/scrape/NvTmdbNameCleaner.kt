package com.novax.app.data.scrape

/**
 * 播放文件名 → TMDB 检索名的清洗（纯 Kotlin，JVM 可测）。
 *
 * NAS 上的真实命名五花八门：
 * 「The.Wild.Robot.2024.2160p.WEB-DL.x265.10bit.HDR.mkv」「沙丘2 Dune Part Two 2024 1080p.mp4」
 * 「 langeSeason01/lange S01E01 2024.mkv」——直接拿文件名去 search 命中率极低，
 * 必须剥掉发布组标签/分辨率/编码/集数标记。
 */
object NvTmdbNameCleaner {

    /** 集数标记：S01E02 / E02 / EP02 / 第02集 / 第02话 / [02] 结尾数字 */
    private val EPISODE_PATTERNS = listOf(
        Regex("(?i)[\\s._-]S\\d{1,2}E\\d{1,3}"),
        Regex("(?i)[\\s._-]EP?\\d{1,3}$"),
        Regex("第\\s*\\d{1,4}\\s*[集话期]$"),
        Regex("[\\s._-]\\[?\\d{1,3}\\]?\\s*$"),
    )

    private val NOISE_TOKENS = setOf(
        "2160p", "1080p", "1080i", "720p", "480p", "360p", "4k", "2k", "8k",
        "x264", "x265", "h264", "h265", "h.264", "h.265", "avc", "hevc", "av1", "xvid", "divx",
        "hdr", "hdr10", "hdr10+", "dolby", "vision", "dv", "dovi", "atmos", "truehd",
        "bluray", "blu-ray", "bdrip", "remux", "web-dl", "webdl", "webrip", "web", "hdtv",
        "dvdrip", "hdrip", "10bit", "8bit", "60fps", "24fps", "aac", "flac", "dts", "ac3",
        "eac3", "ddp", "dd+", "5.1", "7.1", "2.0", "chs", "cht", "eng", "chs&eng", "mandarin",
        "国语", "中字", "中英双字", "简繁", "蓝光", "高清", "4k修复", "修复版", "完整版", "未删减",
        // 命名里按 '-' 断开的常见组合（WEB-DL → web + dl）
        "web", "dl", "rip", "remux",
    )

    private val YEAR_REGEX = Regex("(?:18|19|20)\\d{2}")

    /** 提取文件名里的年份（用于 TMDB year 参数消歧），无则 null。 */
    fun extractYear(raw: String): Int? =
        YEAR_REGEX.findAll(raw).lastOrNull()?.value?.toIntOrNull()

    /**
     * 清洗为检索名。扩展名、括号/方括号内标签（发布组/语言/规格）、分辨率/编码
     * token、集数标记、开尾分隔符全部剥除；失败（剥完为空）时逐级回退。
     */
    fun clean(raw: String): String {
        val base = raw.substringBeforeLast('.', raw)
        // 括号内容整体视为标签（中文发布组常见「【XXX】」「（4K修复）」）
        var name = base
            .replace(Regex("【[^】]*】"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("（[^）]*）"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
        name = EPISODE_PATTERNS.fold(name) { acc, re -> re.replace(acc, " ") }
        name = name.split('.', ' ', '_', '-', '·')
            .filter { it.isNotBlank() }
            .filter { it.lowercase() !in NOISE_TOKENS }
            // 纯 4 位数字 token 视为年份，走 &year= 参数消歧，不进查询名
            //（"1917"/"2012" 这类年份片名剥光后由兜底回原文，不受影响）
            .filter { !(it.length == 4 && it.all { ch -> ch.isDigit() }) }
            .joinToString(" ")
        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.isNotEmpty()) return name
        // 全被剥光的兜底：只去扩展名和集数标记
        val fallback = EPISODE_PATTERNS.fold(base) { acc, re -> re.replace(acc, " ") }
        return fallback.replace(Regex("\\s+"), " ").trim().ifEmpty { base }
    }

    /** 文件名是否像「剧集的某一集」（先去扩展名再判，否则「第12集.mkv」结尾不是"集"）。 */
    fun looksLikeEpisodeFile(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.', fileName)
        return EPISODE_PATTERNS.any { it.containsMatchIn(base) }
    }
}
