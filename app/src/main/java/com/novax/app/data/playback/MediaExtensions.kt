package com.novax.app.data.playback

/**
 * 扩展名/路由域**单一权威表**（2026-11-09 审计 A6 / spec B-8）：
 * 此前 VIDEO/IMAGE/AUDIO/FALLBACK/LIBVLC 五表散落四处拷贝（FileRouter/ScanRules/
 * RmvbPlayerActivity companion/Thumbnailer），靠注释「严格一致」人肉同步，历史上已
 * 实际漏过（BUG-C-R6-10 缺 rm/rmvb 等致文件页永不缩略图、v1.0.0.5 补 m4v 逐处追）。
 * 收敛后各定义点改为委托本表（FileRouter/ScanRules 的旧常量名保留=兼容面零改动），
 * 新代码一律直接引用 MediaExtensions。
 * 一致性由 MediaExtensionsTest 钉死（三副本同一/子集关系/容错语义）。
 */
object MediaExtensions {

    /** 视频（小写）：入库（ScanRules）与文件路由（NvFileRouter.Kind.VIDEO）共用 */
    val VIDEO = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts",
        "m2ts", "mpg", "mpeg", "3gp", "ogv", "rm", "rmvb", "m4v",
    )

    /** 图片（小写）：入库 kind=IMAGE 与 ViewerActivity 路由共用 */
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

    /**
     * 音频：2026-10-26 补全（m4b/wma/ape 等曾点开弹「用其他应用打开」）。
     * media3 不支持的容器（wma/ape/aiff）由 FALLBACK 转 VLC。
     * m4p 不收（苹果 DRM，收了也播不出）。
     */
    val AUDIO = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
        "m4b", "oga", "mka", "wma", "ape", "aiff", "aif",
    )

    /**
     * 兜底扩展名集：ExoPlayer 无对应容器/解码支持的格式（PlayerActivity 路由判定唯一依据）。
     * 2026-10-26 补 wma/ape/aiff/aif（随音频路由表收编，VLC 原生可播）；m4b/mka/oga 走
     * media3（MP4/Matroska/Ogg 容器）不在此表。
     * 2026-11-01 v1.0.0.14 补 avi：XviD 族老编码在 media3 软解码器上「出格式不出帧」
     * （真机 vivo frames=0 冻首帧），VLC 的 MPEG-4 Part 2 软解是看家本领——整体路由兜底页。
     */
    val FALLBACK = setOf("rm", "rmvb", "wmv", "asf", "wma", "ape", "aiff", "aif", "avi")

    /** libVLC 兜底抽帧候选（FALLBACK 的视频子集；wma/ape/aiff 为纯音频无画面不抽帧） */
    val LIBVLC_THUMB_EXTS = setOf("rm", "rmvb", "wmv", "asf")

    /**
     * 兜底目标判定：尾部空格/点容错（否则脏尾巴文件 "x.wma " 以 AUDIO/VIDEO 身份进
     * 播放器却匹配不上兜底集，留在 media3 必播错）。裸扩展名（无点无斜杠）取不到
     * 扩展名恒 false——这是现状语义（MediaExtensionsTest 钉死），本函数吃 path/url。
     */
    fun isFallbackTarget(pathOrUrl: String): Boolean {
        val name = pathOrUrl.substringBefore('?').substringAfterLast('/')
            .trim().trimEnd('.', ' ')
        return name.substringAfterLast('.', "").lowercase() in FALLBACK
    }
}
