package com.novax.app.ui.viewers

/**
 * C9 §一 文件类型路由表（纯函数、无 Android 依赖，可单测）：
 * 按扩展名（小写）判定路由类型。判定细则：
 * - 视频/音频仍走播放器（PlayerActivity，音频为播放器音频模式，行为不变）；
 * - 图片/PDF/文本进 ViewerActivity（nv_type + nv_uri + nv_title，见 ViewerContract）；
 * - 其他一切 → UNKNOWN → 文件页内 NvBottomSheet「用其他应用打开」（任何文件点开必有去处）。
 */
object NvFileRouter {

    /** 路由类型（C9 §一） */
    enum class Kind { VIDEO, IMAGE, PDF, TEXT, AUDIO, UNKNOWN }

    /** PDF 远程打开前置下载确认阈值：>200MB 先确认「先下载后打开」 */
    const val PDF_CONFIRM_BYTES: Long = 200L * 1024 * 1024

    /** 未知类型外发确认阈值：>512MB 先确认「先下载到本机缓存再打开」 */
    const val SHARE_CONFIRM_BYTES: Long = 512L * 1024 * 1024

    val VIDEO_EXTS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts",
        "m2ts", "mpg", "mpeg", "3gp", "ogv", "rm", "rmvb",
    )
    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    val TEXT_EXTS = setOf(
        "txt", "log", "md", "srt", "ass", "ssa", "vtt", "ini", "conf", "json", "xml", "csv",
    )
    val AUDIO_EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")

    /** 取小写扩展名：无扩展名 / 以点结尾 / 隐藏文件（".gitignore"）→ ""（走 UNKNOWN） */
    fun extensionOf(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 扩展名 → 路由类型（C9 路由表全表） */
    fun kindOf(fileName: String): Kind {
        val ext = extensionOf(fileName)
        return when {
            ext in VIDEO_EXTS -> Kind.VIDEO
            ext in IMAGE_EXTS -> Kind.IMAGE
            ext == "pdf" -> Kind.PDF
            ext in TEXT_EXTS -> Kind.TEXT
            ext in AUDIO_EXTS -> Kind.AUDIO
            else -> Kind.UNKNOWN
        }
    }
}
