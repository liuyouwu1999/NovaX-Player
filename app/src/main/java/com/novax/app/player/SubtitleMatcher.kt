package com.novax.app.player

/**
 * C6 §3.1 · 同目录字幕文件自动匹配（纯 Kotlin，无 Android 依赖，可单测）。
 *
 * 评分规则（从高到低）：
 *  1. 完全同名（视频去扩展名 == 字幕去扩展名，如 movie.mkv ↔ movie.srt）      score 100
 *  2. 同名 + 语言后缀（movie.zh.srt / movie.chs.ass / movie.zh-CN.srt 等）    score 90
 *  3. 基名相同 + 任意其他后缀（movie.forced.srt）                             score 80
 *  4. 同目录其他字幕：基础 40 分；文件名含视频标题关键词 +10；
 *     剧集同集号（S01E01 / 1x01 / EP01 / 第01集）+20；上限 60（永不自动选）。
 *
 * 自动选择（[pickAuto]）：候选中唯一最高分且 score >= 80 才自动加载；
 * 并列最高分或最高分不足 80 时返回 auto=false，交由用户在面板手选。
 *
 * Windows（\）与 Android/POSIX（/）路径分隔符均兼容。
 * 支持扩展名：.srt .ass .ssa .sub .vtt .idx（大小写不敏感）。
 */

/** 同目录字幕候选 */
data class SubtitleCandidate(
    val path: String,
    val displayName: String,
    val languageTag: String?,
    /** 是否与视频完全同名（评分规则 1） */
    val isExact: Boolean,
    val score: Int,
    /**
     * M4（2026-10-07）：远程 vtt 直链（Jellyfin 内嵌文本字幕）；非 null 时
     * transcodedSubtitleConfiguration 走远程分支直开连接下载（api_key 已在 URL 内）。
     * path 仍需合法扩展名（mime 判定复用既有逻辑）。
     */
    val remoteUrl: String? = null,
    /**
     * i18n Task 9②：展示与数据拆分标记（Jellyfin 内嵌字幕）——面板行由 UI
     * 拼装本地化前缀（[inline][R.string.player_sub_embedded_prefix]）；displayName
     * 本身不再预拼任何语言文案。
     */
    val embedded: Boolean = false,
    /** i18n Task 9②：内嵌轨 index（displayName 为空时 UI 用它兜底生成「字幕 N」） */
    val trackIndex: Int? = null,
)

object SubtitleMatcher {

    /** 参与匹配的字幕扩展名（小写） */
    val supportedExtensions: Set<String> = setOf("srt", "ass", "ssa", "sub", "vtt", "idx")

    const val SCORE_EXACT = 100            // 规则 1：完全同名
    const val SCORE_LANGUAGE_STANDARD = 95 // 规则 2a：同名 + 标准两字母语言码（movie.zh.srt）
    const val SCORE_LANGUAGE = 90          // 规则 2b：同名 + 区域/别名后缀（movie.cn.srt、.chs、.zh-CN）
    const val SCORE_SAME_BASE = 80         // 规则 3：同名 + 任意其他后缀
    const val SCORE_OTHER = 40             // 规则 4：同目录其他字幕基础分
    const val BONUS_TITLE_KEYWORD = 10     // 规则 4：含视频标题关键词
    const val BONUS_EPISODE_MATCH = 20     // 规则 4：剧集同集号
    const val SCORE_OTHER_CAP = 60         // 规则 4 上限（低于 AUTO_THRESHOLD，永不自动选）
    const val AUTO_THRESHOLD = 80

    /** 常见语言 tag（文件名后缀，小写比较）→ BCP-47 归一化（展示 / Media3 setLanguage） */
    val languageTagAliases: Map<String, String> = mapOf(
        "zh" to "zh",
        "zh-cn" to "zh-CN", "zh-hans" to "zh-CN", "zh-sg" to "zh-SG",
        "zh-tw" to "zh-TW", "zh-hant" to "zh-TW", "zh-hk" to "zh-HK",
        "cn" to "zh-CN", "chs" to "zh-CN", "gb" to "zh-CN", "gb2312" to "zh-CN", "sc" to "zh-CN",
        "cht" to "zh-TW", "big5" to "zh-TW", "tw" to "zh-TW", "tc" to "zh-TW", "hk" to "zh-HK",
        "en" to "en", "eng" to "en", "en-us" to "en-US", "en-gb" to "en-GB",
        "ja" to "ja", "jp" to "ja", "jpn" to "ja",
        "ko" to "ko", "kr" to "ko", "kor" to "ko",
        // M4：ISO 639-2 三字码（Jellyfin MediaStreams.Language 原值；对本地字幕文件名语言
        // 后缀同样生效——chi/zho 本就是 zh 的正确标签，规则 2 判定更准确）
        "chi" to "zh-CN", "zho" to "zh-CN",
        "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de",
        "rus" to "ru", "spa" to "es",
    )

    private val keywordSplitRegex = Regex("[^0-9a-z\\u4e00-\\u9fa5]+")
    private val seasonEpisodeRegex = Regex("s(\\d{1,2})e(\\d{1,3})")
    private val seasonEpisodeAltRegex = Regex("(?<![0-9])(\\d{1,2})x(\\d{1,3})(?![0-9])")
    private val episodeRegex = Regex("(?<![a-z0-9])ep?\\.?(\\d{1,3})(?![0-9])")
    private val cnEpisodeRegex = Regex("第\\s*(\\d{1,4})\\s*[集话回]")

    /**
     * 从同目录文件列表中找出视频的字幕候选，按分数降序（同分按文件名）返回。
     * @param videoPath 视频完整路径（/ 或 \ 分隔均可）
     * @param directoryFiles 同目录文件完整路径列表
     */
    fun findSubtitles(videoPath: String, directoryFiles: List<String>): List<SubtitleCandidate> {
        val videoBase = baseName(fileName(videoPath)).lowercase(java.util.Locale.ROOT)
        if (videoBase.isEmpty()) return emptyList()

        val bestByName = LinkedHashMap<String, SubtitleCandidate>()
        for (raw in directoryFiles) {
            val path = raw.trim()
            if (path.isEmpty()) continue
            val name = fileName(path)
            val ext = extensionOf(name)?.lowercase() ?: continue
            if (ext !in supportedExtensions) continue
            val subBase = baseName(name)
            if (subBase.isEmpty()) continue

            val candidate = scoreCandidate(videoBase, path, name, subBase)
            val key = name.lowercase()
            val prev = bestByName[key]
            if (prev == null || candidate.score > prev.score) bestByName[key] = candidate
        }
        return bestByName.values
            .sortedWith(compareByDescending<SubtitleCandidate> { it.score }.thenBy { it.displayName.lowercase() })
    }

    /**
     * 自动选择（2026-10-24 用户需求改版）：不再要求「唯一最高分」——旧逻辑同分（如
     * `video.zh.srt` 与 `video.cn.srt` 并列 90）会弹选择框打断起播；现在同分以
     * **首选语言匹配 → 分数 → 稳定文件名序** 决出唯一第一并直接自动加载，尽量不弹框，
     * 用户不满意在字幕菜单自行切换。取过滤后第一名且 score >= [AUTO_THRESHOLD]；
     * 无达标候选返回 (null, false)（不弹框，候选仍收进字幕菜单可手选）。
     *
     * @param preferredLanguages 设置「字幕首选语言」（逗号分隔串已由调用方拆分；BCP-47
     *   片段如 zh / zh-CN / en）。匹配口径：全等或子标签前缀（zh ↔ zh-CN 互认）
     */
    fun pickAuto(
        candidates: List<SubtitleCandidate>,
        preferredLanguages: List<String> = emptyList(),
    ): Pair<SubtitleCandidate?, Boolean> {
        val preferred = preferredLanguages.mapNotNull { normalizeLanguageTag(it) }.filter { it.isNotEmpty() }
        fun langRank(c: SubtitleCandidate): Int {
            if (preferred.isEmpty()) return 0
            val tag = c.languageTag ?: return 0
            val hit = preferred.any { p ->
                tag.equals(p, ignoreCase = true) ||
                    tag.startsWith("$p-", ignoreCase = true) ||
                    p.startsWith("$tag-", ignoreCase = true)
            }
            return if (hit) 1 else 0
        }

        val best = candidates
            .filter { it.score >= AUTO_THRESHOLD }
            .maxWithOrNull(
                compareBy<SubtitleCandidate>({ langRank(it) }, { it.score })
                    // max 语义下取「降序最大 = 字典序最小」：同分同语言时稳定取文件名靠前者
                    .thenByDescending { it.displayName.lowercase() },
            ) ?: return null to false
        return best to true
    }

    /**
     * side-load 可用的字幕 MIME（按扩展名映射）：
     * srt → application/x-subrip，ass/ssa → text/x-ssa，vtt → text/vtt。
     * .sub/.idx（VobSub 镜像对，需要 idx+sub 成对才能解析）单文件 side-load 不可行，返回 null。
     */
    fun mimeTypeForFile(path: String): String? = when (extensionOf(fileName(path))?.lowercase()) {
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> null
    }

    /** 语言 tag 归一化：无法识别时原样返回（去空白），空返回 null */
    fun normalizeLanguageTag(tag: String): String? {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return null
        return languageTagAliases[trimmed.lowercase()] ?: trimmed
    }

    // ---------------- 内部实现 ----------------

    private fun scoreCandidate(
        videoBase: String,
        path: String,
        displayName: String,
        subBase: String,
    ): SubtitleCandidate {
        val subLower = subBase.lowercase()

        // 规则 1：完全同名
        if (subLower == videoBase) {
            return SubtitleCandidate(path, displayName, null, isExact = true, score = SCORE_EXACT)
        }

        // 规则 2 / 3：videoBase + "." + 后缀
        if (subLower.length > videoBase.length && subLower.startsWith(videoBase) &&
            subLower[videoBase.length] == '.'
        ) {
            val firstSegment = subLower.substring(videoBase.length + 1).substringBefore('.')
            val language = languageTagAliases[firstSegment]
            if (language != null) {
                // 规则 2a/2b：同名 + 语言后缀。标准两字母主码（zh/en/ja…）95 > 区域别名
                // （cn/chs/big5/zh-CN…）90——2026-10-24 用户需求：同为中文时优先选写法
                // 规范的 `.zh.srt` 而非 `.cn.srt`，打分阶段直接拉开而非依赖文件名 tie-break
                val standard = firstSegment.length == 2 && language.startsWith(firstSegment)
                return SubtitleCandidate(
                    path, displayName, language, isExact = false,
                    score = if (standard) SCORE_LANGUAGE_STANDARD else SCORE_LANGUAGE,
                )
            }
            // 规则 3：同名 + 任意后缀（forced / sdh / proper ...），尾段若恰为语言 tag 则带上
            val tailTag = languageTagAliases[subLower.substringAfterLast('.')]
            return SubtitleCandidate(path, displayName, tailTag, isExact = false, score = SCORE_SAME_BASE)
        }

        // 规则 4：同目录其他字幕
        var score = SCORE_OTHER
        if (containsTitleKeyword(videoBase, subLower)) score += BONUS_TITLE_KEYWORD
        if (isSameEpisode(videoBase, subLower)) score += BONUS_EPISODE_MATCH
        val tailTag = languageTagAliases[subLower.substringAfterLast('.')]
        return SubtitleCandidate(
            path = path,
            displayName = displayName,
            languageTag = tailTag,
            isExact = false,
            score = score.coerceAtMost(SCORE_OTHER_CAP),
        )
    }

    /** 字幕名是否含视频标题关键词：整体包含，或包含任一 ≥3 字符（CJK ≥2 字符）的标题词 */
    private fun containsTitleKeyword(videoBase: String, subBaseLower: String): Boolean {
        if (videoBase.isEmpty()) return false
        if (subBaseLower.contains(videoBase)) return true
        return videoBase.split(keywordSplitRegex)
            .filter { it.length >= 3 || it.any { ch -> ch.code > 0x2E80 } }  // CJK 词可短至 2 字
            .any { it.length >= 2 && subBaseLower.contains(it) }
    }

    /** 剧集同集号判定：集号相同且季号不冲突（任一方无季号视为不冲突） */
    private fun isSameEpisode(videoBase: String, subBaseLower: String): Boolean {
        val a = episodeInfo(videoBase) ?: return false
        val b = episodeInfo(subBaseLower) ?: return false
        return a.second == b.second && (a.first == null || b.first == null || a.first == b.first)
    }

    /** 提取 (季, 集)；无季号则季为 null；无法提取返回 null */
    private fun episodeInfo(name: String): Pair<Int?, Int>? {
        seasonEpisodeRegex.find(name)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        seasonEpisodeAltRegex.find(name)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        cnEpisodeRegex.find(name)?.let { return null to it.groupValues[1].toInt() }
        episodeRegex.find(name)?.let { return null to it.groupValues[1].toInt() }
        return null
    }

    /** 取路径最后一段（兼容 / 与 \） */
    fun fileName(path: String): String {
        val lastSlash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    }

    /** 去掉最后一个扩展名（".srt" 这类隐藏文件式名称原样返回） */
    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 扩展名（保留原大小写，大小写不敏感匹配由调用方 lower）；无扩展名返回 null */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else null
    }
}
