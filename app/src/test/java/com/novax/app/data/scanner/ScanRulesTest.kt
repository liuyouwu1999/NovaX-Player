package com.novax.app.data.scanner

import com.novax.app.data.db.MediaKind
import com.novax.app.ui.viewers.NvFileRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫描纯规则：扩展名过滤（与 NvFileRouter 严格一致）、隐藏项、kind 判定、展示名 */
class ScanRulesTest {
    @Test fun hashAndEmbeddedNumbers_areNotEpisodes() {
        assertEquals(MediaKind.VIDEO, ScanRules.kindOf("e8532728adc228.mp4"))
        assertEquals(MediaKind.VIDEO, ScanRules.kindOf("Scene12abc.mp4"))
    }
    @Test fun exactDirectoryHintsClassifyWithoutDurationGuess() {
        assertEquals(MediaKind.MOVIE, ScanRules.kindOf("旅行.mp4", "/电影/旅行.mp4"))
        assertEquals(MediaKind.EPISODE, ScanRules.kindOf("02.mp4", "/剧集/作品/02.mp4"))
        assertEquals(MediaKind.VIDEO, ScanRules.kindOf("长录像.mp4", "/电影剪辑素材/长录像.mp4"))
    }

    // ---------- 扩展名过滤 ----------

    @Test
    fun videoExtensions_matchFileRouter() {
        // 索引过的文件点击必有去处：集合与 FileRouter.VIDEO_EXTS 完全一致
        assertEquals(NvFileRouter.VIDEO_EXTS, ScanRules.VIDEO_EXTS)
    }

    // ---------- 图片扩展名（入索引 kind=IMAGE，不抽帧） ----------

    @Test
    fun imageExtensions_matchFileRouter() {
        assertEquals(NvFileRouter.IMAGE_EXTS, ScanRules.IMAGE_EXTS)
    }

    @Test
    fun isImageFile_caseInsensitive() {
        assertTrue(ScanRules.isImageFile("photo.jpg"))
        assertTrue(ScanRules.isImageFile("IMG_0001.JPEG"))
        assertTrue(ScanRules.isImageFile("screenshot.PNG"))
        assertTrue(ScanRules.isImageFile("anim.webp"))
        assertTrue(ScanRules.isImageFile("scan.bmp"))
        assertTrue(ScanRules.isImageFile(" pix.gif"))
        assertTrue(ScanRules.isImageFile("portrait.heic"))
        assertTrue(ScanRules.isImageFile("/dcim/camera/IMG_1.jpg"))
    }

    @Test
    fun isImageFile_rejectsNonImage() {
        org.junit.Assert.assertFalse(ScanRules.isImageFile("movie.mkv"))
        org.junit.Assert.assertFalse(ScanRules.isImageFile("notes.txt"))
        org.junit.Assert.assertFalse(ScanRules.isImageFile("jpg"))     // 无扩展名
        org.junit.Assert.assertFalse(ScanRules.isImageFile(".jpg"))    // 隐藏文件（dot<=0）
        org.junit.Assert.assertFalse(ScanRules.isImageFile("trailing."))
    }

    @Test
    fun isVideoFile_caseInsensitive() {
        assertTrue(ScanRules.isVideoFile("a.mkv"))
        assertTrue(ScanRules.isVideoFile("A.MKV"))
        assertTrue(ScanRules.isVideoFile("home movie.Mp4"))
        assertTrue(ScanRules.isVideoFile("clip.WEBM"))
    }

    @Test
    fun isVideoFile_rejectsNonVideo() {
        assertFalse(ScanRules.isVideoFile("notes.txt"))
        assertFalse(ScanRules.isVideoFile("cover.jpg"))
        assertFalse(ScanRules.isVideoFile("noext"))
        assertFalse(ScanRules.isVideoFile(".mkv"))        // 隐藏文件（dot<=0）
        assertFalse(ScanRules.isVideoFile("trailing."))   // 以点结尾
        assertFalse(ScanRules.isVideoFile("md.webp"))     // 图片，不在 VIDEO_EXTS
    }

    @Test
    fun isVideoFile_acceptsFullPath() {
        assertTrue(ScanRules.isVideoFile("/media/movies/dune.mkv"))
        assertTrue(ScanRules.isVideoFile("a\\b\\c.AVI"))
    }

    // ---------- 隐藏项过滤 ----------

    @Test
    fun hiddenEntries_filtered() {
        assertTrue(ScanRules.isHiddenEntry(".hidden"))
        assertTrue(ScanRules.isHiddenEntry("._document"))     // AppleDouble
        assertTrue(ScanRules.isHiddenEntry("\$RECYCLE.BIN"))
        assertTrue(ScanRules.isHiddenEntry("System Volume Information"))
        assertTrue(ScanRules.isHiddenEntry("@eaDir"))         // 群晖缩略图目录
    }

    @Test
    fun normalEntries_notHidden() {
        assertFalse(ScanRules.isHiddenEntry("Movies"))
        assertFalse(ScanRules.isHiddenEntry("movie.mkv"))
        assertFalse(ScanRules.isHiddenEntry("@eatDir"))       // 精确匹配，非前缀
        assertFalse(ScanRules.isHiddenEntry("system volume information")) // 大小写敏感的精确集合：保持原样不过滤
    }

    // ---------- kind 判定 ----------

    @Test
    fun episodePatterns_detected() {
        assertEquals(MediaKind.EPISODE, ScanRules.kindOf("Show.Name.S01E02.mkv"))
        assertEquals(MediaKind.EPISODE, ScanRules.kindOf("综艺第12期.mp4"))
        assertEquals(MediaKind.EPISODE, ScanRules.kindOf("动画 第3集 .mkv"))
    }

    @Test
    fun everythingElse_fallsBackToVideo() {
        assertEquals(MediaKind.VIDEO, ScanRules.kindOf("Home Movie 2024.mp4"))
        assertEquals(MediaKind.VIDEO, ScanRules.kindOf("drone footage 001.mov"))
        // MOVIE 是抽帧回填 duration 后的升级结果，扫描期不判定
    }

    // ---------- 展示名 ----------

    @Test
    fun displayName_stripsExtensionOnly() {
        assertEquals("Movie.2019.1080p", ScanRules.displayNameOf("Movie.2019.1080p.mkv"))
        assertEquals("NoExt", ScanRules.displayNameOf("NoExt"))
        assertEquals(".mkv", ScanRules.displayNameOf(".mkv"))   // dot<=0 原样返回
    }

    // ---------- VIDEO 入库跳过规则（相机/监控命名 + 大小下限；IMAGE 不受影响） ----------

    @Test
    fun shouldIndexVideo_cameraAndSegmentNames_skipped_caseInsensitive() {
        val big = 200L * 1024 * 1024
        // 真实样本：监控分钟切片 / 相机命名碎片——无论多大都不入库
        assertFalse(ScanRules.shouldIndexVideo("00M_69901148_1.mp4", big))   // ddM_ 监控分钟切片
        assertFalse(ScanRules.shouldIndexVideo("DJI_20260517.mp4", big))     // 大疆
        assertFalse(ScanRules.shouldIndexVideo("GOPR5678.MP4", big))         // GoPro（大写扩展名也算）
        assertFalse(ScanRules.shouldIndexVideo("dji_test.mp4", big))         // 前缀大小写不敏感
        assertFalse(ScanRules.shouldIndexVideo("IMG_1234.mov", big))         // 手机相机
        assertFalse(ScanRules.shouldIndexVideo("vid_20240101_0930.mp4", big))
        assertFalse(ScanRules.shouldIndexVideo("MOV_0071.mp4", big))
        assertFalse(ScanRules.shouldIndexVideo("GX010012.MP4", big))         // GXdd
        assertFalse(ScanRules.shouldIndexVideo("SX123456.mp4", big))         // SXdd
        assertFalse(ScanRules.shouldIndexVideo("recording_segment.mp4", big))   // 含 _segment
        assertFalse(ScanRules.shouldIndexVideo("CLIP_SEGMENT.MP4", big))        // _segment 大小写不敏感
        // 传入完整路径按文件名判定（前缀锚定文件名，而非路径开头）
        assertFalse(ScanRules.shouldIndexVideo("/monitor/00M_69901148_1.mp4", big))
    }

    @Test
    fun shouldIndexVideo_realMovies_kept() {
        val gb = 1024L * 1024 * 1024
        assertTrue(ScanRules.shouldIndexVideo("海市蜃楼.E01.mp4", 1_100L * 1024 * 1024))   // ~1.1GB
        assertTrue(ScanRules.shouldIndexVideo("12只猴子.mkv", 8L * gb))
        assertTrue(ScanRules.shouldIndexVideo("异物.The.Record.2023.S01E01.mkv", 2L * gb))
        // 前缀相似但非相机命名的正片不误伤（\d{2}M_ 需 数字数字M下划线）
        assertTrue(ScanRules.shouldIndexVideo("2012.mkv", 2L * gb))
        assertTrue(ScanRules.shouldIndexVideo("12.monkeys.mkv", 2L * gb))
    }

    @Test
    fun shouldIndexVideo_sizeThreshold_skipsTinyVideos_exact50MBBoundaryIndexed() {
        val min = ScanRules.MIN_LIBRARY_VIDEO_BYTES
        assertFalse(ScanRules.shouldIndexVideo("小视频.mp4", 10L * 1024 * 1024))   // 10MB → 跳过
        assertFalse(ScanRules.shouldIndexVideo("小视频.mp4", min - 1))              // 差 1 字节 → 跳过
        // 50MB 整边界：规则为"严格小于才跳过" → 恰好 50MB 入库
        assertTrue(ScanRules.shouldIndexVideo("小视频.mp4", min))
        assertTrue(ScanRules.shouldIndexVideo("小视频.mp4", min + 1))
    }

    // ---------- 非媒体容器魔数嗅探（2026-09-15：sam3_output/哈希名等伪视频文件不入库） ----------

    private fun mp4FtypHeader(): ByteArray {
        val b = ByteArray(32)
        b[4] = 'f'.code.toByte(); b[5] = 't'.code.toByte(); b[6] = 'y'.code.toByte(); b[7] = 'p'.code.toByte()
        return b
    }

    private fun ebmlHeader(): ByteArray =
        byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x42.toByte(), 0x82.toByte(), 0x88.toByte(), 0x6D.toByte(), 0x61.toByte(), 0x74.toByte(), 0x72.toByte(), 0x6F.toByte())

    @Test
    fun mediaContainerSniff_knownSignatures_pass() {
        assertTrue(ScanRules.looksLikeMediaContainer(mp4FtypHeader()))
        assertTrue(ScanRules.looksLikeMediaContainer(ebmlHeader()))                       // MKV/WebM
        // MOV 首 box 也可能是 mdat/free/skip（无 ftyp 的少见封装顺序）
        val mdatFirst = ByteArray(16); "mdat".toByteArray().copyInto(mdatFirst, 4)
        assertTrue(ScanRules.looksLikeMediaContainer(mdatFirst))
        val avi = ByteArray(16); "RIFF".toByteArray().copyInto(avi, 0); "AVI ".toByteArray().copyInto(avi, 8)
        assertTrue(ScanRules.looksLikeMediaContainer(avi))
        assertTrue(ScanRules.looksLikeMediaContainer(
            byteArrayOf(0x46.toByte(), 0x4C.toByte(), 0x56.toByte(), 0x01.toByte(), 0x05.toByte(), 0x00, 0x00, 0x09.toByte()) + ByteArray(8)
        ))
        assertTrue(ScanRules.looksLikeMediaContainer("OggSabcdef".toByteArray() + ByteArray(8)))
        // MPEG-TS：0x47@0/188/376 三点同步
        val ts = ByteArray(512); ts[0] = 0x47; ts[188] = 0x47; ts[376] = 0x47
        assertTrue(ScanRules.looksLikeMediaContainer(ts))
        // MPEG-PS
        val ps = ByteArray(16); ps[0]=0; ps[1]=0; ps[2]=0x01; ps[3]=0xBA.toByte()
        assertTrue(ScanRules.looksLikeMediaContainer(ps))
    }

    @Test
    fun mediaContainerSniff_nonMedia_fails() {
        // 真实样本形态：AI 训练输出 / 数据包 / 哈希名碎片（有 .mp4 扩展名但内容是任意字节）
        val junkText = "sam3_output: checkpoint epoch=12 loss=0.0231\n".toByteArray() + ByteArray(24)
        assertFalse(ScanRules.looksLikeMediaContainer(junkText))
        assertFalse(ScanRules.looksLikeMediaContainer(ByteArray(64)))                     // 全零碎片
        assertFalse(ScanRules.looksLikeMediaContainer(
            byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte()) + ByteArray(24)
        ))  // zip 包
        val jpeg = ByteArray(16); jpeg[0] = 0xFF.toByte(); jpeg[1] = 0xD8.toByte()
        assertFalse(ScanRules.looksLikeMediaContainer(jpeg))                              // 图片不是视频容器
        assertFalse(ScanRules.looksLikeMediaContainer(ByteArray(4)))                      // 过短
        // TS 单点 0x47 不认（任意二进制都可能是 'G'）
        val weakTs = ByteArray(512); weakTs[0] = 0x47
        assertFalse(ScanRules.looksLikeMediaContainer(weakTs))
    }

    // ---------- 代码仓库垃圾目录（node_modules 等） ----------

    @Test
    fun hiddenEntries_repoJunkDirsFiltered() {
        assertTrue(ScanRules.isHiddenEntry("node_modules"))
        assertTrue(ScanRules.isHiddenEntry("__MACOSX"))      // macOS 压缩包解压垃圾
        assertTrue(ScanRules.isHiddenEntry("__pycache__"))
        assertTrue(ScanRules.isHiddenEntry("site-packages"))
        assertFalse(ScanRules.isHiddenEntry("node_modules_backup"))   // 精确匹配，非前缀
    }

    // ---------- 排除目录匹配（sources.excludedDirs，BFS 剪枝 + 对账共用） ----------

    @Test
    fun isUnderExcludedDir_matchSemantics() {
        val excluded = listOf("/睿图工作文件", "/下载/临时")
        // 目录自身命中
        assertTrue(ScanRules.isUnderExcludedDir("/睿图工作文件", excluded))
        // 子树命中（多层）
        assertTrue(ScanRules.isUnderExcludedDir("/睿图工作文件/yolo26/main.py", excluded))
        assertTrue(ScanRules.isUnderExcludedDir("/下载/临时/a/b", excluded))
        // 目录条目路径带尾分隔符（BFS 入队形式）同样命中
        assertTrue(ScanRules.isUnderExcludedDir("/睿图工作文件/", excluded))
        assertTrue(ScanRules.isUnderExcludedDir("/睿图工作文件/子目录/", excluded))
        // 前缀相似但非同目录不误伤
        assertFalse(ScanRules.isUnderExcludedDir("/睿图工作文件备份", excluded))
        assertFalse(ScanRules.isUnderExcludedDir("/下载/临时文件", excluded))
        assertFalse(ScanRules.isUnderExcludedDir("/电影", excluded))
        // 根目录永不命中（排除根 = 全库无意义）
        assertFalse(ScanRules.isUnderExcludedDir("/", excluded))
        // 空集快路径
        assertFalse(ScanRules.isUnderExcludedDir("/睿图工作文件", emptyList()))
    }
}
