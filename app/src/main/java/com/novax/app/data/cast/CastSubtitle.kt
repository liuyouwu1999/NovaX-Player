package com.novax.app.data.cast

import com.novax.app.player.SubtitleCandidate
import com.novax.app.player.SubtitleMatcher

/**
 * 外挂字幕解析（spec C13 §4.1）：候选来自 repo.listSubtitles（与本机播放同一套
 * SubtitleMatcher 评分），选轨沿用 pickAuto；产出源内路径交给流服务器 /subtitle 路由
 * （编码统一 UTF-8，转码在服务器侧收口）。
 */
object CastSubtitle {

    /** 自动选一（与本地播放同一套 pickAuto）；无候选返回 null */
    fun resolveAuto(candidates: List<SubtitleCandidate>): String? =
        SubtitleMatcher.pickAuto(candidates).first?.path

    /** 复杂特效字幕（ASS/SSA）——按 srt 声明投递，样式可能丢失，推送前提示 */
    fun needsStyleWarning(subtitlePath: String): Boolean =
        subtitlePath.substringAfterLast('.').lowercase() in setOf("ass", "ssa")
}
