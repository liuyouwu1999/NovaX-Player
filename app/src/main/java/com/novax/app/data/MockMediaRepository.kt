package com.novax.app.data

// 首页/媒体库共用的内存假数据仓库（真实感影片占位，海报用 picsum seed）
object MockMediaRepository {

    data class MediaItem(
        val id: String,
        val title: String,
        val year: Int,
        val kind: String,            // "movie" / "series"
        val source: String,          // 来源（SMB/WebDAV 等）
        val durationMinutes: Int,
        val codec: String,
        val resolution: String,
        val path: String,
    )

    data class ContinueItem(
        val id: String,
        val title: String,
        val episodeLabel: String?,   // "S1E5" 或 null（电影）
        val progress: Float,         // 0f..1f
        val backdropUrl: String,
    )

    private fun url(seed: String) = "https://picsum.photos/seed/$seed/300/450"

    val library: List<MediaItem> = listOf(
        MediaItem("m1", "星际穿越", 2014, "movie", "SMB · 黑群晖", 169, "HEVC", "1080p", "/media/movies/interstellar.mkv"),
        MediaItem("m2", "沙丘 2", 2024, "movie", "SMB · 黑群晖", 166, "HEVC", "4K HDR", "/media/movies/dune-two.mkv"),
        MediaItem("m3", "奥本海默", 2023, "movie", "WebDAV · 阿里云盘", 181, "H.264", "1080p", "/webdav/oppenheimer.mp4"),
        MediaItem("m4", "流人 第一季", 2022, "series", "SMB · 黑群晖", 58, "HEVC", "1080p", "/media/tv/slow-horses/s1/"),
        MediaItem("m5", "最后生还者", 2023, "series", "WebDAV · 阿里云盘", 55, "HEVC", "4K HDR", "/webdav/tlof/"),
        MediaItem("m6", "坠落的审判", 2023, "movie", "DLNA · 客厅盒子", 151, "H.264", "1080p", "/dlna/anatomy.mp4"),
        MediaItem("m7", "瞬息全宇宙", 2022, "movie", "SFTP · VPS", 139, "HEVC", "1080p", "/sftp/eaao.mkv"),
        MediaItem("m8", "真探：夜之国度", 2024, "series", "SMB · 黑群晖", 62, "HEVC", "2160p", "/media/tv/true-detective-s4/"),
        MediaItem("m9", "沙丘", 2021, "movie", "SMB · 黑群晖", 155, "HEVC", "4K HDR", "/media/movies/dune.mkv"),
        MediaItem("m10", "幕府将军", 2024, "series", "WebDAV · 阿里云盘", 60, "HEVC", "1080p", "/webdav/shogun/"),
        MediaItem("m11", "寂静之地：入侵日", 2024, "movie", "DLNA · 客厅盒子", 99, "H.264", "1080p", "/dlna/quiet-place.mkv"),
        MediaItem("m12", "熊家餐馆 第二季", 2023, "series", "SFTP · VPS", 34, "H.264", "1080p", "/sftp/the-bear/s2/"),
    )

    val continueWatching: List<ContinueItem> = listOf(
        ContinueItem("m2", "沙丘 2", null, 0.62f, "https://picsum.photos/seed/dune2-wide/640/360"),
        ContinueItem("m4", "流人 第一季", "S1E5", 0.35f, "https://picsum.photos/seed/horses-wide/640/360"),
        ContinueItem("m10", "幕府将军", "S1E3", 0.78f, "https://picsum.photos/seed/shogun-wide/640/360"),
        ContinueItem("m3", "奥本海默", null, 0.12f, "https://picsum.photos/seed/oppen-wide/640/360"),
    )

    val recentlyAdded: List<PosterEntry> = listOf(
        PosterEntry("m2", "沙丘 2", "SMB · 2024", url("dune2"), isNew = true),
        PosterEntry("m8", "真探：夜之国度", "SMB · 2024", url("truedetective"), isNew = true),
        PosterEntry("m10", "幕府将军", "WebDAV · 2024", url("shogun"), isNew = true),
        PosterEntry("m11", "寂静之地：入侵日", "DLNA · 2024", url("quietplace")),
        PosterEntry("m6", "坠落的审判", "DLNA · 2023", url("anatomy")),
        PosterEntry("m12", "熊家餐馆 第二季", "SFTP · 2023", url("thebear"), isNew = true),
    )

    val favorites: List<PosterEntry> = listOf(
        PosterEntry("m1", "星际穿越", "SMB · 2014", url("interstellar")),
        PosterEntry("m5", "最后生还者", "WebDAV · 2023", url("tlof")),
        PosterEntry("m9", "沙丘", "SMB · 2021", url("dune")),
        PosterEntry("m7", "瞬息全宇宙", "SFTP · 2022", url("eaao")),
    )

    val recommendations: List<PosterEntry> = listOf(
        PosterEntry("m9", "沙丘", "SMB · 2021", url("dune")),
        PosterEntry("m3", "奥本海默", "WebDAV · 2023", url("oppenheimer")),
        PosterEntry("m6", "坠落的审判", "DLNA · 2023", url("anatomy")),
        PosterEntry("m7", "瞬息全宇宙", "SFTP · 2022", url("eaao")),
        PosterEntry("m5", "最后生还者", "WebDAV · 2023", url("tlof")),
    )

    fun byId(id: String): MediaItem? = library.firstOrNull { it.id == id }

    // ---- C6/C8 播放器：可实际播放的公开测试流（追加，不影响已有 API） ----
    // storage.googleapis.com 在中国大陆不可达（无限缓冲），已换成实测 200 OK 的源：
    // 内置 asset:///sample.mp4（Big Buck Bunny 720p 10s，任何网络可播）+
    // media.w3.org / vjs.zencdn.net 网络流（2026-09 实测可达）。

    /** 内置演示片（app/src/debug/assets/sample.mp4，≈4.8MB），ExoPlayer 原生支持 asset:// scheme。
     *  2026-10-18 发布终审 BUG-6：由 main 移入 debug 源集——mock 播放仅 debug 可达
     *  （MainActivity FLAG_DEBUGGABLE 门控），release 包不再携带 4.8MB×3 ABI 死重 */
    const val assetSampleUrl = "asset:///sample.mp4"

    const val defaultVideoUrl = assetSampleUrl

    val sampleStreamsById: Map<String, String> = mapOf(
        "m1" to assetSampleUrl,
        "m2" to assetSampleUrl,
        "m3" to assetSampleUrl,
        "m9" to "https://media.w3.org/2010/05/sintel/trailer.mp4",
        "m11" to "https://vjs.zencdn.net/v/oceans.mp4",
    )

    fun videoUrlFor(id: String?): String = sampleStreamsById[id] ?: defaultVideoUrl

    fun subtitleFor(url: String): String? =
        when {
            url.contains("sintel", ignoreCase = true) -> "Demo · 480p · H.264"
            url.contains("oceans", ignoreCase = true) -> "Demo · 1080p · H.264"
            else -> null
        }

    data class PosterEntry(
        val id: String,
        val title: String,
        val subtitle: String,
        val posterUrl: String,
        val isNew: Boolean = false,
    )
}
