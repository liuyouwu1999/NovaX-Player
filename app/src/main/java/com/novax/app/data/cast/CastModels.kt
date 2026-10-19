package com.novax.app.data.cast


/** 已发现的 DLNA 渲染器（volumeUri=null 表示未声明 RCS，UI 隐藏音量条） */
data class CastDevice(
    val udn: String,
    val name: String,
    val controlUri: String,
    val volumeUri: String?,
)

/**
 * 一次投屏的片源描述。subtitlePath 是源内路径（与 [path] 同一命名空间；SMB 上的
 * 外挂字幕才是主场景，读取统一走 repo 管线），由 CastSubtitle（Task 10）解析；
 * playUrl 是本机回拉播放用的 URL（NvPlaybackLauncher.localPath/novaxUrl 产物）。
 */
data class CastMedia(
    val sourceId: Long,
    val path: String,
    val title: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val playUrl: String,
    val subtitlePath: String?,
)

object CastMime {
    /**
     * DIDL protocolInfo 与流服务器 Content-Type 共用（一致性由构造保证：buildServer 传同一函数值）。
     *
     * 返工3-续（2026-10-16 WMP「只有声音没画面」修复）：WMP/upnphost 按 protocolInfo 的 MIME
     * 分类媒体——MIME 不以 video/ 开头（含 application/octet-stream）会被当音频/未知内容渲染
     * （音乐视图+音符图标）。常见视频容器必须全部映射到 video 前缀 MIME，未收录的冷门容器才落
     * octet-stream（电视不支持属预期内失败，WMP 严格模式会拒，如实浮出而非误分类）。
     */
    fun videoMimeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "ts", "m2ts", "mts", "tp" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "wmv" -> "video/x-ms-wmv"
        "asf" -> "video/x-ms-asf"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        "3gp", "3g2" -> "video/3gpp"
        "mpg", "mpeg", "mpe", "vob" -> "video/mpeg"
        "ogv" -> "video/ogg"
        else -> "application/octet-stream"
    }
}
