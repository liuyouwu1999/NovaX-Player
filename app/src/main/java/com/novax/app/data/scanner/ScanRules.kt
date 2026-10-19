package com.novax.app.data.scanner

import com.novax.app.data.db.MediaKind

/**
 * 扫描纯规则（C12 §1.3 / §二.2）：扩展名过滤、隐藏项过滤、kind 判定、展示名解析。
 * 全部无 IO / 无 Android 依赖，可单测。
 */
object ScanRules {

    /** BFS 目录深度上限：相对源根的目录层级，超过不再下钻（C12 §二.2） */
    const val MAX_DEPTH = 6

    /** 单源视频文件上限：达到即截断（status=DONE + error 提示，C12 §二.2 / §八S13） */
    const val MAX_ITEMS_PER_SOURCE = 5000

    /**
     * 批量 upsert 大小（C12 §二.3；2026-09-15 用户反馈 500→100）：
     * 扫描中发现的内容按批提交入库——批越大，用户在库页看到已发现视频的延迟越长
     * （旧值 500 时前 499 个文件期间列表始终是骨架）。100 个/批 = 事务量仍安全（SQL 变量
     * 100+1 ≪ 999 上限），扫描期库页刷新频率可接受（RUNNING 进度已 300ms 节流）。
     */
    const val BATCH_SIZE = 100

    /**
     * 媒体库 VIDEO 类入库大小下限（字节）：sizeBytes < 该值不入库——监控分钟切片/相机碎片的主力形态，
     * 也是无谓扫描/抽帧 I/O 的主要来源。恰好等于阈值 → 入库（规则为"严格小于才跳过"）。
     * 阈值可按库容需要调整；仅影响媒体库索引，文件浏览器不受影响（文件页仍可见可播）。
     */
    const val MIN_LIBRARY_VIDEO_BYTES = 50L * 1024 * 1024

    /**
     * 媒体库 IMAGE 类入库规则已随「图片退出媒体库」移除（2026-09-15 用户需求：
     * 图片只在文件页浏览/查看器打开，媒体库仅收视频；MIN_LIBRARY_IMAGE_BYTES 与
     * shouldIndexImage 已删，存量 IMAGE 行由扫描对账自动清除）。
     */

    /**
     * 相机/监控命名模式（case-insensitive）：
     * - 文件名前缀：IMG_ / VID_ / MOV_（手机相机）、DJI_（大疆）、GOPR（GoPro）、
     *   GXdd / SXdd（无人机/行车记录仪分段）、ddM_（监控分钟切片，如 00M_69901148_1.mp4）；
     * - 或文件名任意位置含 "_segment"（监控切片/分段导出）。
     */
    val CAMERA_OR_SEGMENT_REGEX: Regex = Regex(
        "^(IMG_|VID_|MOV_|DJI_|GOPR|GX\\d{2}|SX\\d{2}|\\d{2}M_)|_segment",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 视频扩展名（小写）：与 ui/viewers NvFileRouter.VIDEO_EXTS 严格一致——
     * 索引过的文件点击必有去处（播放器），FileRouter 判 UNKNOWN 的扩展名绝不入库。
     * 2026-10-26 补 m4v：入库+抽帧封面与其他视频同口径（用户反馈 m4v 曾不入库无封面）。
     */
    val VIDEO_EXTS: Set<String> = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts",
        "m2ts", "mpg", "mpeg", "3gp", "ogv", "rm", "rmvb", "m4v",
    )

    /**
     * 图片扩展名（小写）：与 NvFileRouter.IMAGE_EXTS 严格一致（jpg/jpeg/png/webp/bmp/gif/heic）。
     * 图片入索引 kind=IMAGE：不抽帧（thumbnailPath 直接 = 文件路径）、durationMs=0。
     */
    val IMAGE_EXTS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic",
    )

    /**
     * 精确排除的目录/文件名（NAS 系统垃圾 + 代码仓库垃圾，C12 §二.2）：
     * - \$RECYCLE.BIN / System Volume Information / @eaDir：NAS 系统目录；
     * - node_modules / __MACOSX / __pycache__ / site-packages：代码仓库/压缩包常见垃圾目录
     *   （2026-09-15 用户 NAS 实测：下载目录里的 GitHub 仓库解压目录污染媒体库）。
     */
    private val HIDDEN_EXACT = setOf(
        "\$RECYCLE.BIN",
        "System Volume Information",
        "@eaDir",
        "node_modules",
        "__MACOSX",
        "__pycache__",
        "site-packages",
    )

    /** 剧集正则（C12 §1.3）：S01E02 / EP12 / E7 / 第3集 */
    private val EPISODE_REGEXES = listOf(
        Regex("(?i)(?<![a-z0-9])s\\d{1,2}e\\d{1,3}(?![a-z0-9])"),
        Regex("(?i)(?<![a-z0-9])ep?\\d{1,3}(?![a-z0-9])"),
        Regex("第[0-9一二三四五六七八九十百零两]+[集话期]"),
    )

    /**
     * 小写扩展名（无扩展名/以点结尾 → ""，与 NvFileRouter.extensionOf 同式；
     * 2026-10-26 起含尾部空格/点容错，同式同步）
     */
    fun extensionOf(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
            .trim().trimEnd('.', ' ')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 是否视频文件（大小写不敏感，集合与 NvFileRouter 一致） */
    fun isVideoFile(fileName: String): Boolean = extensionOf(fileName) in VIDEO_EXTS

    /** 是否图片文件（大小写不敏感，集合与 NvFileRouter.IMAGE_EXTS 一致） */
    fun isImageFile(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTS

    /** 隐藏项："." 开头、"._*" AppleDouble、$RECYCLE.BIN、System Volume Information、@eaDir */
    fun isHiddenEntry(name: String): Boolean =
        name.startsWith(".") || name in HIDDEN_EXACT

    /** 展示名 = 去扩展名文件名（C12 §1.1） */
    fun displayNameOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName else fileName.substring(0, dot)
    }

    /** 扫描期 kind 判定：EPISODE 正则命中 → EPISODE，否则 VIDEO 兜底（MOVIE 由抽帧回填 duration 后升级） */
    fun kindOf(fileName: String, path: String = fileName): MediaKind {
        if (EPISODE_REGEXES.any { it.containsMatchIn(fileName) }) return MediaKind.EPISODE
        for (dir in path.replace('\\', '/').substringBeforeLast('/', "").split('/').asReversed()) {
            when (dir.lowercase(java.util.Locale.ROOT)) {
                "电影", "影片", "movies", "movie", "films" -> return MediaKind.MOVIE
                "电视剧", "剧集", "tv shows", "tv", "series", "动漫", "综艺" -> return MediaKind.EPISODE
            }
        }
        return MediaKind.VIDEO
    }

    /**
     * VIDEO 类入库跳过规则（纯函数，集中放置便于调整；仅 VIDEO 类调用，IMAGE 不受影响，
     * 文件浏览器不受影响）。满足任一即不入 media_items：
     * 1) 文件名（取最后一段，兼容传入完整路径）命中 [CAMERA_OR_SEGMENT_REGEX]——
     *    无论多大都不入库（监控切片命名与正片无关）；
     * 2) sizeBytes < [MIN_LIBRARY_VIDEO_BYTES]（50MB）——恰好 50MB 入库（严格小于才跳过）。
     * 后续要放宽/收紧只改这两个常量与本函数。
     */
    fun shouldIndexVideo(name: String, sizeBytes: Long): Boolean {
        val baseName = name.substringAfterLast('/').substringAfterLast('\\')
        if (CAMERA_OR_SEGMENT_REGEX.containsMatchIn(baseName)) return false
        return sizeBytes >= MIN_LIBRARY_VIDEO_BYTES
    }

    /**
     * 媒体容器魔数嗅探（2026-09-15 用户需求：sam3_output/哈希名等「有视频扩展名但不是视频」的
     * 文件不得入库——扩展名+大小过滤对它们无效，只有内容头能证明身份；业界标准做法即文件签名
     * 校验：MP4=offset4 'ftyp'、MKV/WebM=EBML 1A45DFA3、AVI=RIFF..AVI、FLV、MPEG-TS 0x47 同步、
     * MPEG-PS 0x000001BA、OggS、ASF/WMV、RM。纯函数，JUnit 可测）。
     *
     * @param header 文件头（建议 ≥512 字节，覆盖 TS 188/376 同步字节；短于 4 字节一律 false）
     * @return true=签名命中任一已知媒体容器；false=非媒体（不入库）。
     * 注意：调用方对「读头失败」应 fail-open（宁可入库不可误杀真媒体）。
     */
    fun looksLikeMediaContainer(header: ByteArray, length: Int = header.size): Boolean {
        if (length < 12) return false

        fun startsWith(offset: Int, sig: ByteArray): Boolean {
            if (offset + sig.size > length) return false
            for (i in sig.indices) if (header[offset + i] != sig[i]) return false
            return true
        }
        fun ascii(offset: Int, s: String): Boolean {
            if (offset + s.length > length) return false
            for (i in s.indices) if (header[offset + i] != s[i].code.toByte()) return false
            return true
        }

        // MP4/MOV/3GP/DASH 分段：首 box type 在 offset 4（box size 4B + type 4B）
        if (ascii(4, "ftyp") || ascii(4, "moov") || ascii(4, "mdat") || ascii(4, "free") ||
            ascii(4, "skip") || ascii(4, "wide") || ascii(4, "pnot") || ascii(4, "styp") ||
            ascii(4, "sidx") || ascii(4, "msdh") || ascii(4, "msix")
        ) return true

        // MKV/WebM：EBML 头
        if (startsWith(0, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) return true

        // AVI：RIFF....AVI ；WAV 同前缀但不是视频扩展名，不单列
        if (ascii(0, "RIFF") && ascii(8, "AVI ")) return true

        // FLV
        if (ascii(0, "FLV")) return true

        // OGG（ogv）
        if (ascii(0, "OggS")) return true

        // ASF/WMV
        if (startsWith(0, byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75.toByte(), 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11))) return true

        // RM/RMVB：0x2E 52 4D 46（.RMF）
        if (startsWith(0, byteArrayOf(0x2E, 0x52, 0x4D, 0x46))) return true

        // MPEG-TS（ts/m2ts）：0x47 同步字节 @0/188/376——单点 0x47 太弱，三点同查才认
        if (length >= 512 &&
            header[0] == 0x47.toByte() && header[188] == 0x47.toByte() && header[376] == 0x47.toByte()
        ) return true

        // MPEG-PS（mpg/mpeg）
        if (startsWith(0, byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte())) ||
            startsWith(0, byteArrayOf(0x00, 0x00, 0x01, 0xBE.toByte()))
        ) return true

        return false
    }

    /**
     * 路径是否落在排除目录内（用户在文件页勾选目录"从媒体库排除"，存 sources.excludedDirs）：
     * - path 与排除目录相等，或以 "排除目录/" 开头 → true；
     * - 比较前两侧都去掉尾部 "/"（目录条目路径带尾分隔符，排除配置存目录形式不带尾），
     *   根 "/" 本身不可被排除（排除根 = 全库无意义，入口已挡，此处兜底 false）；
     * - excludedDirs 为空 → false（O(1) 快路径，BFS 每个目录条目都要问一次）。
     */
    fun isUnderExcludedDir(path: String, excludedDirs: Collection<String>): Boolean {
        if (excludedDirs.isEmpty()) return false
        val p = path.trimEnd('/').let { if (it.isEmpty()) "/" else it }
        if (p == "/") return false
        return excludedDirs.any { ex ->
            val e = ex.trimEnd('/').let { if (it.isEmpty()) "/" else it }
            p == e || p.startsWith("$e/")
        }
    }
}
