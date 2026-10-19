package com.novax.app.data.scanner

import com.novax.app.data.NvFileEntry
import com.novax.app.data.db.MediaItemDao
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaItemWithWatch
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.ScanStateDao
import com.novax.app.data.db.ScanStateEntity
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceStatus
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.ThumbRef
import com.novax.app.data.db.ThumbState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaScanner BFS 行为（注入 fake 列目录/列共享，不真连 SMB）：
 * 深度上限截断、单源 5000 上限、隐藏过滤、upsert 合并保护、对账清扫、可取消、失败终态。
 */
class MediaScannerBfsTest {

    @Test fun cancelAll_stopsBatchBeforeNextSource() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val dao = FakeMediaItemDao()
        val states = FakeScanStateDao()
        val scanner = MediaScanner(dao, states, lister = { source, _ ->
            calls.add(source.id)
            entered.complete(Unit)
            kotlinx.coroutines.delay(150)
            Result.success(emptyList())
        }, shareLister = { Result.success(emptyList()) }, scope = this)
        scanner.scanAll(listOf(1L, 2L).map { SourceEntity(id = it, type = SourceType.SMB, nickname = "test", shareOrPath = "media") })
        entered.await()
        scanner.cancelAll()
        kotlinx.coroutines.delay(350)
        assertEquals(listOf(1L), calls)
        assertEquals(ScanStatus.CANCELLED, states.states[1L]?.status)
    }

    // ---------- fakes ----------

    /** 内存版 MediaItemDao：记录行与对账调用，其余只读方法返回空 */
    private class FakeMediaItemDao : MediaItemDao {
        val rows = LinkedHashMap<Long, MediaItemEntity>()
        private var nextId = 1L
        var deleteNotInCalls = 0
        var upsertCalls = 0

        fun seed(e: MediaItemEntity): MediaItemEntity {
            val id = if (e.id != 0L) e.id else nextId++
            return e.copy(id = id).also { rows[id] = it }
        }

        override suspend fun upsertAll(items: List<MediaItemEntity>) {
            upsertCalls++
            items.forEach { seed(it) }
        }

        override suspend fun findByPaths(sourceId: Long, paths: List<String>): List<MediaItemEntity> {
            val keys = paths.toSet()
            return rows.values.filter { it.sourceId == sourceId && it.path in keys }
        }

        override suspend fun deleteNotIn(sourceId: Long, paths: List<String>): Int {
            deleteNotInCalls++
            val keep = paths.toSet()
            val gone = rows.values.filter { it.sourceId == sourceId && it.path !in keep }
            gone.forEach { rows.remove(it.id) }
            return gone.size
        }

        override suspend fun deleteByPaths(sourceId: Long, paths: List<String>): Int {
            val gone = rows.values.filter { it.sourceId == sourceId && it.path in paths.toSet() }
            gone.forEach { rows.remove(it.id) }
            return gone.size
        }

        override suspend fun allPaths(sourceId: Long): List<String> =
            rows.values.filter { it.sourceId == sourceId }.map { it.path }

        override fun observeLibrary(
            sourceId: Long?,
            segment: String,
            watched: Int,
            sort: String,
        ): Flow<List<MediaItemWithWatch>> = flowOf(emptyList())

        override suspend fun search(q: String, qLower: String, qUpper: String): List<MediaItemEntity> = emptyList()

        override suspend fun updateThumb(id: Long, path: String?, state: String, durationMs: Long?, width: Int?, height: Int?, expectedSize: Long, expectedMtime: Long) {
            rows[id] = rows.getValue(id).copy(
                thumbnailPath = path, thumbState = state,
                durationMs = durationMs, width = width, height = height,
            )
        }

        override suspend fun claimThumb(id: Long): Int = 0
        override suspend fun claimThumbForRetry(id: Long): Int = 0

        override suspend fun resetStaleRunning() {}

        override suspend fun resetFailedThumbs(): Int {
            var n = 0
            rows.values.forEach {
                if (it.thumbState == ThumbState.FAILED.name) {
                    rows[it.id] = it.copy(thumbnailPath = null, thumbState = ThumbState.NONE.name)
                    n++
                }
            }
            return n
        }

        // 2026-10-12 §1a 新增 DAO 方法的测试桩:仅重置 FAILED(绝不碰 RUNNING),与生产语义一致
        override suspend fun resetFailedThumbsInDir(sourceId: Long, escapedDir: String): Int {
            var n = 0
            rows.values.forEach {
                if (it.sourceId == sourceId && it.thumbState == ThumbState.FAILED.name) {
                    rows[it.id] = it.copy(thumbnailPath = null, thumbState = ThumbState.NONE.name)
                    n++
                }
            }
            return n
        }

        override suspend fun upgradeKindToMovie(id: Long) {
            rows[id]?.let { rows[it.id] = it.copy(kind = MediaKind.MOVIE) }
        }

        override suspend fun resetThumb(id: Long) {
            rows[id]?.let { rows[it.id] = it.copy(thumbnailPath = null, thumbState = ThumbState.NONE.name) }
        }

        override fun observeRecentlyAdded(): Flow<List<MediaItemEntity>> = flowOf(emptyList())

        override fun observeById(id: Long): Flow<MediaItemEntity?> = flowOf(rows[id])

        // 2026-09-20 DAO 新增 observeByKey（行级 Flow）：fake 按内存行同步实现，解锁单测编译
        override fun observeByKey(sourceId: Long, path: String): Flow<MediaItemEntity?> =
            flowOf(rows.values.firstOrNull { it.sourceId == sourceId && it.path == path })

        override suspend fun findById(id: Long): MediaItemEntity? = rows[id]

        override suspend fun count(): Int = rows.size

        override suspend fun findByKey(sourceId: Long, path: String): MediaItemEntity? =
            rows.values.firstOrNull { it.sourceId == sourceId && it.path == path }

        override suspend fun deleteBySource(sourceId: Long) {
            rows.values.filter { it.sourceId == sourceId }.forEach { rows.remove(it.id) }
        }

        override suspend fun deleteById(id: Long) {
            rows.remove(id)
        }

        override suspend fun suspectVideoRows(sourceId: Long, limit: Int): List<MediaItemEntity> =
            rows.values
                .filter { it.sourceId == sourceId && it.kind == MediaKind.VIDEO && it.durationMs == null }
                .take(limit)

        override suspend fun allThumbRefs(): List<ThumbRef> = emptyList()

        // H-4（2026-10-18 发布终审）海报引用集：BFS 扫描测试不触达
        override suspend fun allPosterRefs(): List<String> = emptyList()

        // ---- TMDB 刮削（MediaDb v6）桩：BFS 扫描测试不触达 ----
        override suspend fun updateScrape(
            id: Long,
            title: String?,
            overview: String?,
            posterPath: String?,
            year: Int?,
            rating: Double?,
        ) = Unit

        override suspend fun updateScrapeByPaths(
            sourceId: Long,
            paths: List<String>,
            title: String?,
            overview: String?,
            posterPath: String?,
            year: Int?,
            rating: Double?,
        ) = Unit

        override suspend fun scrapePendingMovies(sourceId: Long, limit: Int): List<MediaItemEntity> = emptyList()

        override suspend fun scrapePendingEpisodes(sourceId: Long, limit: Int): List<MediaItemEntity> = emptyList()

        override suspend fun clearAllScrapePosterRefs() = Unit

        override suspend fun resetScrape(id: Long) = Unit

        override suspend fun videosInDir(sourceId: Long, dir: String): List<MediaItemEntity> = emptyList()

        override fun observeKindCounts(): kotlinx.coroutines.flow.Flow<List<com.novax.app.data.db.KindCount>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        // ---- 拼音键存量回填桩（2026-09-29 DAO 新增；BFS 扫描测试不触达，解锁单测编译） ----
        override suspend fun pinyinBackfillCandidates(limit: Int): List<MediaItemEntity> = emptyList()

        override suspend fun updatePinyinKeys(id: Long, pinyin: String, initials: String) = Unit
    }

    private class FakeScanStateDao : ScanStateDao {
        val states = ConcurrentHashMap<Long, ScanStateEntity>()

        override suspend fun upsert(s: ScanStateEntity) {
            states[s.sourceId] = s
        }

        override fun observeAll(): Flow<List<ScanStateEntity>> = flowOf(states.values.toList())

        override suspend fun get(sourceId: Long): ScanStateEntity? = states[sourceId]

        override suspend fun getAll(): List<ScanStateEntity> = states.values.toList()

        override suspend fun delete(sourceId: Long) {
            states.remove(sourceId)
        }
    }

    /** 一次测试的完整夹具：fake DAO + fake 列目录（内存目录树）+ 被测 scanner */
    private class Fixture {
        val dao = FakeMediaItemDao()
        val scanState = FakeScanStateDao()
        val visitedDirs = mutableListOf<String>()
        val tree = mutableMapOf<String, List<NvFileEntry>>()   // 2026-09-15：scannerFor 扩展需读

        val source = SourceEntity(
            id = 7L,
            type = SourceType.SMB,
            nickname = "测试NAS",
            host = "192.168.1.10",
            shareOrPath = "media",
            status = SourceStatus.OK,
        )

        /** 登记目录内容；parent 形如 "/"、"/a/b/"（以 / 开头、目录以 / 结尾） */
        fun putDir(parent: String, entries: List<NvFileEntry>) {
            tree[parent] = entries
        }

        fun dir(parent: String, name: String) = NvFileEntry(name, "$parent$name/", true)

        /** 默认 size = 入库下限整边界（恰 50MB → 入库，钉死"严格小于才跳过"的边界行为） */
        fun file(parent: String, name: String, size: Long = ScanRules.MIN_LIBRARY_VIDEO_BYTES) =
            NvFileEntry(name, "$parent$name", false, size, 123L)

        val scanner = MediaScanner(
            mediaDao = dao,
            scanDao = scanState,
            lister = { _, dirPath ->
                visitedDirs.add(dirPath)
                Result.success(tree[dirPath] ?: emptyList())
            },
            shareLister = { Result.success(emptyList()) },
            // 2026-09-15 非媒体嗅探：默认注入 null（fail-open）→ 既有视频用例行为不变；
            // 嗅探专测通过 scannerFor(headerReader=...) 自带读头逻辑
            headerReader = { _, _ -> null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    /** 带自定义文件头读取器的 scanner（非媒体嗅探专测用） */
    private fun Fixture.scannerFor(headerReader: suspend (SourceEntity, String) -> ByteArray?): MediaScanner =
        scannerFor(headerReader, clock = { 0L })

    private fun Fixture.scannerFor(
        headerReader: suspend (SourceEntity, String) -> ByteArray?,
        clock: () -> Long,
    ): MediaScanner =
        MediaScanner(
            mediaDao = dao,
            scanDao = scanState,
            lister = { _, dirPath ->
                visitedDirs.add(dirPath)
                Result.success(tree[dirPath] ?: emptyList())
            },
            shareLister = { Result.success(emptyList()) },
            headerReader = headerReader,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).also { it.clockMs = clock }

    /** 共享名留空源：先枚举共享再逐个扫 —— 见 sharelessSource_enumeratesSharesFirst */

    // ---------- 2026-09-21 审计修复回归 ----------

    /**
     * 审计 P3（visited 剪枝）：同一归一化目录路径只入队一次。
     * 场景：服务端 junction/重复条目让 "/shared/" 同时出现在 /x/ 与 /y/ 两个父目录的
     * 列表里（相同 path 字符串）。无剪枝时该目录被列两次；剪枝后仅一次。
     */
    @Test
    fun bfs_dedupesDuplicateDirPaths() {
        val f = Fixture()
        val sharedEntry = NvFileEntry("shared", "/shared/", true, 0, 0)
        f.putDir("/", listOf(f.dir("/", "x"), f.dir("/", "y")))
        f.putDir("/x/", listOf(sharedEntry))
        f.putDir("/y/", listOf(sharedEntry))
        f.putDir("/shared/", listOf(f.file("/shared/", "s.mkv")))

        runBlocking { f.scanner.scanSourceNow(f.source) }

        assertEquals(1, f.visitedDirs.count { it == "/shared/" })
    }

    /**
     * 审计 P1 契约锁：嗅探块上限必须是 64KB（512B 需求 + 老 NAS 最小协商）。
     * 漏传 maxBlockBytes 时块 = min(协商 maxReadSize, 4MB) + 预读块，
     * 实测每文件从 NAS 拉 2×4MB（512B 需求 → 16000× 放大，首扫 5000 文件 ≈ 40GB）。
     */
    @Test
    fun sniffBlockContract_is64KB() {
        assertEquals(64 * 1024, MediaScanner.HEADER_SNIFF_BLOCK_BYTES)
        // 即使 NAS 协商出 8MB maxReadSize，嗅探块也必须被压到 64KB
        assertEquals(
            64 * 1024,
            com.novax.app.data.smb.SmbReadMath.blockBytes(
                maxReadSize = 8 * 1024 * 1024,
                capBytes = MediaScanner.HEADER_SNIFF_BLOCK_BYTES,
            ),
        )
    }

    /**
     * 嗅探计量网络闸门契约锁（2026-09-28 审计 P1-1）：远程源（SMB/WebDAV/SFTP）在受限
     * 网络推迟嗅探（仅列目录建索引）；本地文件不耗流量不受限。表驱动覆盖全部
     * SourceType × 网络态（与 Thumbnailer.shouldDeferForNetwork 同构的扫描侧判定）。
     */
    @Test
    fun sniffGate_meteredRemoteDefers_localUnaffected() {
        data class Row(val type: SourceType, val sniffAllowed: Boolean, val defer: Boolean)
        val rows = SourceType.values().flatMap { type ->
            listOf(
                Row(type, sniffAllowed = true, defer = false),
                Row(type, sniffAllowed = false, defer = type != SourceType.LOCAL),
            )
        }
        for (r in rows) {
            assertEquals(
                "type=${r.type} sniffAllowed=${r.sniffAllowed}",
                r.defer,
                MediaScanner.shouldDeferSniffForNetwork(r.type, r.sniffAllowed),
            )
        }
    }

    /** headerReader 桩：记账并返回全零 512B 头（无任何媒体签名 → looksLikeMediaContainer=false → 入库方会跳过该文件） */
    private fun sniffRejectingHeader(calls: MutableList<String>): suspend (SourceEntity, String) -> ByteArray? =
        { _, path -> calls.add(path); ByteArray(MediaScanner.HEADER_SNIFF_BYTES) }

    /**
     * 审计 P1-1 回归（2026-09-28）：计量网络（allowNetworkSniff=false）下远程源跳过嗅探——
     * headerReader 零调用、候选照常入库（与「读头失败 fail-open」同语义：宁可入库不误杀
     * 真媒体，仅列目录建索引）。
     */
    @Test
    fun meteredRemote_skipsSniff_andStillIndexes() {
        val f = Fixture()
        val sniffCalls = mutableListOf<String>()
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath -> Result.success(f.tree[dirPath] ?: emptyList()) },
            shareLister = { Result.success(emptyList()) },
            allowNetworkSniff = { false },
            headerReader = sniffRejectingHeader(sniffCalls),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        f.putDir("/", listOf(f.file("/", "movie.mkv")))

        runBlocking { scanner.scanSourceNow(f.source) }

        assertTrue(sniffCalls.isEmpty())
        assertEquals(1, f.dao.rows.size)
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    /** 对照组：网络不受限时同源同桩照常嗅探——全零头非媒体被拒之库外（证明嗅探确实在工作） */
    @Test
    fun unmeteredRemote_stillSniffs_andRejectsNonMedia() {
        val f = Fixture()
        val sniffCalls = mutableListOf<String>()
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath -> Result.success(f.tree[dirPath] ?: emptyList()) },
            shareLister = { Result.success(emptyList()) },
            allowNetworkSniff = { true },
            headerReader = sniffRejectingHeader(sniffCalls),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        f.putDir("/", listOf(f.file("/", "movie.mkv")))

        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals(listOf("/movie.mkv"), sniffCalls)
        assertEquals(0, f.dao.rows.size)
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    /** 本地源不受嗅探闸门：计量网络下照样嗅探（本地读零流量，非媒体仍拒绝入库） */
    @Test
    fun meteredLocal_stillSniffs() {
        val f = Fixture()
        val sniffCalls = mutableListOf<String>()
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath -> Result.success(f.tree[dirPath] ?: emptyList()) },
            shareLister = { Result.success(emptyList()) },
            allowNetworkSniff = { false },
            headerReader = sniffRejectingHeader(sniffCalls),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        f.putDir("/", listOf(f.file("/", "movie.mkv")))
        val local = SourceEntity(id = 9L, type = SourceType.LOCAL, nickname = "本地")

        runBlocking { scanner.scanSourceNow(local) }

        assertEquals(listOf("/movie.mkv"), sniffCalls)
        assertEquals(0, f.dao.rows.size)
        assertEquals(ScanStatus.DONE, f.scanState.states[9L]!!.status)
    }

    // ---------- 深度上限 ----------

    @Test
    fun bfs_depthCappedAtSixLevels() {
        val f = Fixture()
        f.putDir("/", listOf(f.dir("/", "a"), f.file("/", "root.mkv")))
        f.putDir("/a/", listOf(f.dir("/a/", "b"), f.file("/a/", "a.mkv")))
        f.putDir("/a/b/", listOf(f.dir("/a/b/", "c"), f.file("/a/b/", "b.mkv")))
        f.putDir("/a/b/c/", listOf(f.dir("/a/b/c/", "d"), f.file("/a/b/c/", "c.mkv")))
        f.putDir("/a/b/c/d/", listOf(f.dir("/a/b/c/d/", "e"), f.file("/a/b/c/d/", "d.mkv")))
        f.putDir("/a/b/c/d/e/", listOf(f.dir("/a/b/c/d/e/", "f"), f.file("/a/b/c/d/e/", "e.mkv")))
        f.putDir("/a/b/c/d/e/f/", listOf(f.file("/a/b/c/d/e/f/", "f.mkv")))

        runBlocking { f.scanner.scanSourceNow(f.source) }

        // 根 + 5 层子目录 = 6 层被列；第 7 层 /a/b/c/d/e/f/ 不可达
        assertEquals(
            listOf("/", "/a/", "/a/b/", "/a/b/c/", "/a/b/c/d/", "/a/b/c/d/e/"),
            f.visitedDirs,
        )
        assertEquals(6, f.dao.rows.size)   // root/a/b/c/d/e 各 1 个视频；f.mkv 不入库
        assertNull(f.dao.rows.values.firstOrNull { it.path == "/a/b/c/d/e/f/f.mkv" })
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    // ---------- 单源上限截断 ----------

    @Test
    fun bfs_itemCapTruncates_andSkipsReconcile() {
        val f = Fixture()
        f.putDir("/", (0 until 5010).map { f.file("/", "v$it.mkv") })
        // 预置一个"本次未见到"的旧行：截断时不得被对账误删
        f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = "/survivor.mkv", name = "survivor", nameLower = "survivor",
                sizeBytes = 1L, mtimeMs = 1L, kind = MediaKind.VIDEO, addedAt = 1L,
            ),
        )

        runBlocking { f.scanner.scanSourceNow(f.source) }

        assertEquals(ScanRules.MAX_ITEMS_PER_SOURCE + 1, f.dao.rows.size)
        val st = f.scanState.states[7L]!!
        assertEquals(ScanStatus.DONE, st.status)
        assertNotNull(st.error)
        assertTrue(st.error!!.contains("5000"))
        assertEquals(0, f.dao.deleteNotInCalls)   // 截断不对账
        assertNotNull(f.dao.rows.values.firstOrNull { it.path == "/survivor.mkv" })
    }

    // ---------- 隐藏过滤 + 合并保护 + 对账 ----------

    @Test
    fun bfs_hiddenSkipped_upsertMerges_andReconcileDeletesVanished() {
        val f = Fixture()
        fun seed(path: String) = f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = path, name = "x", nameLower = "x",
                // 与 Fixture.file 默认 size 一致（未变更文件）：测的是"未变更不丢封面"合并保护，
                // size/mtime 变更清封面是 ScanMerge 的另一条既有规则（见 ScanMergeTest）
                sizeBytes = ScanRules.MIN_LIBRARY_VIDEO_BYTES, mtimeMs = 123L, kind = MediaKind.VIDEO,
                thumbnailPath = "/cache/t.jpg", thumbState = ThumbState.DONE.name, addedAt = 9L,
            ),
        )
        seed("/kept.mkv")
        seed("/gone.mkv")

        f.putDir(
            "/",
            listOf(
                f.file("/", "kept.mkv"),
                f.file("/", "new.mkv"),
                f.file("/", "notes.txt"),          // 非视频
                f.file("/", ".hidden.mkv"),        // 隐藏文件
                f.dir("/", "\$RECYCLE.BIN"),
                f.dir("/", "@eaDir"),
            ),
        )
        f.putDir("/\$RECYCLE.BIN/", listOf(f.file("/\$RECYCLE.BIN/", "junk.mkv")))
        f.putDir("/@eaDir/", listOf(f.file("/@eaDir/", "thumb.mkv")))

        runBlocking { f.scanner.scanSourceNow(f.source) }

        assertEquals(setOf("/kept.mkv", "/new.mkv"), f.dao.rows.values.map { it.path }.toSet())
        val kept = f.dao.rows.values.first { it.path == "/kept.mkv" }
        assertEquals("/cache/t.jpg", kept.thumbnailPath)   // 合并保护：仍在文件不丢封面
        assertEquals(9L, kept.addedAt)
        val st = f.scanState.states[7L]!!
        assertEquals(ScanStatus.DONE, st.status)
        assertNull(st.error)
        assertEquals(1, f.dao.deleteNotInCalls)
    }

    // ---------- 入库跳过规则（相机/监控命名 + 大小下限；仅 VIDEO 类） ----------

    @Test
    fun videoSkipRules_cameraSegmentOrTiny_notIndexed_andReconcileRemovesLegacyRows() {
        val f = Fixture()
        val mb = 1024L * 1024
        // 存量垃圾行（旧版本入库的监控切片/相机碎片）：文件仍在源中，但命中跳过规则
        // → 不进 seenPaths → 重扫一次被对账清除（对账清理扩展）
        fun seedLegacy(path: String, size: Long) = f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = path, name = "x", nameLower = "x",
                sizeBytes = size, mtimeMs = 123L, kind = MediaKind.VIDEO,
                thumbnailPath = "/cache/t.jpg", thumbState = ThumbState.DONE.name, addedAt = 9L,
            ),
        )
        seedLegacy("/VID_0001.mp4", 30L * mb)
        seedLegacy("/00M_69901148_1.mp4", 200L * mb)
        seedLegacy("/movie_segment.mp4", 10L * mb)

        f.putDir(
            "/",
            listOf(
                f.file("/", "VID_0001.mp4", size = 30L * mb),          // 相机命名 → 跳过（再小也跳）
                f.file("/", "00M_69901148_1.mp4", size = 200L * mb),   // 监控分钟切片，再大也按名跳过
                f.file("/", "海市蜃楼.E01.mp4", size = 1_100L * mb),    // 真影片 → 保留
                f.file("/", "12只猴子.mkv", size = 8L * 1024 * mb),     // 真影片 → 保留
                f.file("/", "小视频.mp4", size = 10L * mb),             // <50MB → 跳过
            ),
        )

        runBlocking { f.scanner.scanSourceNow(f.source) }

        assertEquals(
            setOf("/海市蜃楼.E01.mp4", "/12只猴子.mkv"),
            f.dao.rows.values.map { it.path }.toSet(),
        )
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    @Test
    fun videoSkipRules_sizeBoundary_exact50MBIndexed_belowSkipped_imageFloorRespected() {
        val f = Fixture()
        f.putDir(
            "/",
            listOf(
                f.file("/", "at50mb.mkv", size = ScanRules.MIN_LIBRARY_VIDEO_BYTES),         // 整边界 → 入库
                f.file("/", "under50.mkv", size = ScanRules.MIN_LIBRARY_VIDEO_BYTES - 1),    // 差 1 字节 → 跳过
                f.file("/", "photo.jpg", size = 500L * 1024),                                // 图片 → 不入库（2026-09-15）
                f.file("/", "icon.png", size = 3L * 1024),                                   // 图片 → 不入库
            ),
        )

        runBlocking { f.scanner.scanSourceNow(f.source) }

        // 2026-09-15：图片退出媒体库后，只入"≥50MB 边界内"的视频行
        assertEquals(
            setOf("/at50mb.mkv"),
            f.dao.rows.values.map { it.path }.toSet(),
        )
    }

    // ---------- 共享名留空源：先枚举共享再逐个扫 ----------

    @Test
    fun sharelessSource_enumeratesSharesFirst() {
        val f = Fixture()
        val blankShareSource = f.source.copy(shareOrPath = "")
        val visited = mutableListOf<String>()
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath ->
                visited.add(dirPath)
                Result.success(listOf(f.file(dirPath, "clip.mkv")))
            },
            shareLister = { Result.success(listOf("media", "video")) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        runBlocking { scanner.scanSourceNow(blankShareSource) }

        assertEquals(listOf("/@share/media/", "/@share/video/"), visited)
        assertEquals(2, f.dao.rows.size)
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    // ---------- 扫描根 = 向导所选目录（sources.scanRoots） ----------

    @Test
    fun scanRoots_bfsStartsFromStoredRoots_only() {
        val f = Fixture()
        val withRoots = f.source.copy(scanRoots = ScanRoots.encode(listOf("/video", "/music")))
        f.putDir(
            "/",
            listOf(f.dir("/", "video"), f.dir("/", "music"), f.dir("/", "other"), f.file("/", "root.mkv")),
        )
        f.putDir("/video/", listOf(f.file("/video/", "v.mkv")))
        f.putDir("/music/", listOf(f.file("/music/", "m.mp3"), f.file("/music/", "m.mkv")))
        f.putDir("/other/", listOf(f.file("/other/", "x.mkv")))

        runBlocking { f.scanner.scanSourceNow(withRoots) }

        // 只从所选两根起 BFS：根 "/" 与 /other 不可达
        assertTrue("/" !in f.visitedDirs)
        assertTrue("/other/" !in f.visitedDirs)
        assertEquals(listOf("/video/", "/music/"), f.visitedDirs)
        assertEquals(setOf("/video/v.mkv", "/music/m.mkv"), f.dao.rows.values.map { it.path }.toSet())
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    @Test
    fun scanRoots_nestedRootsDeduplicated_beforeBfs() {
        val f = Fixture()
        val withRoots = f.source.copy(scanRoots = ScanRoots.encode(listOf("/video/", "/video/sub")))
        f.putDir("/video/", listOf(f.dir("/video/", "sub"), f.file("/video/", "v.mkv")))
        f.putDir("/video/sub/", listOf(f.file("/video/sub/", "s.mkv")))

        runBlocking { f.scanner.scanSourceNow(withRoots) }

        // 嵌套根 /video/sub 只作为普通子目录被 BFS 到一次，不作为第二根重复起扫
        assertEquals(listOf("/video/", "/video/sub/"), f.visitedDirs)
        assertEquals(1, f.visitedDirs.count { it == "/video/sub/" })
        assertEquals(setOf("/video/v.mkv", "/video/sub/s.mkv"), f.dao.rows.values.map { it.path }.toSet())
    }

    @Test
    fun scanRoots_depthBudgetIsPerRoot() {
        val f = Fixture()
        val withRoots = f.source.copy(scanRoots = ScanRoots.encode(listOf("/l1/l2/l3/l4/l5")))
        f.putDir("/l1/l2/l3/l4/l5/", listOf(f.file("/l1/l2/l3/l4/l5/", "deep.mkv")))

        runBlocking { f.scanner.scanSourceNow(withRoots) }

        // 深度相对所选根计算（根为 0）：第 6 层的文件仍可达
        assertEquals(listOf("/l1/l2/l3/l4/l5/"), f.visitedDirs)
        assertEquals("/l1/l2/l3/l4/l5/deep.mkv", f.dao.rows.values.single().path)
    }

    @Test
    fun scanRoots_nullOrGarbage_fallsBackToWholeSourceRoot() {
        val f = Fixture()
        f.putDir("/", listOf(f.file("/", "root.mkv")))
        for (raw in listOf<String?>(null, "broken", "[]")) {
            f.dao.rows.clear()
            runBlocking { f.scanner.scanSourceNow(f.source.copy(scanRoots = raw)) }
            assertTrue("raw=$raw 应回退扫根", "/" in f.visitedDirs)
        }
    }

    // ---------- 图片入索引（kind=IMAGE，不抽帧） ----------

    // ---------- 2026-09-15 图片退出媒体库：图片条目不再索引（文件页保留浏览/查看器） ----------

    @Test
    fun imageFiles_notIndexed_atAll_videosOnly() {
        val f = Fixture()
        val kb = 1024L
        f.putDir(
            "/",
            listOf(
                f.file("/", "IMG_0001.jpg", size = 300 * kb),
                f.file("/", "photo.PNG", size = 500 * kb),
                f.file("/", "movie.heic", size = 2_000 * kb),
                f.file("/", "icon.png", size = 3 * kb),
                f.file("/", "notes.txt"),      // 非媒体不入库
                f.file("/", "v.mkv"),
            ),
        )

        runBlocking { f.scanner.scanSourceNow(f.source) }

        // 只剩视频行；图片（无论大小）全部不入库
        assertEquals(listOf("/v.mkv"), f.dao.rows.values.map { it.path })
        assertEquals(0, f.dao.rows.values.count { it.kind == MediaKind.IMAGE })
    }

    // ---------- 2026-09-15 非媒体文件魔数嗅探（sam3_output/哈希名伪视频治理） ----------

    @Test
    fun nonMediaHeader_videoFile_skippedNotIndexed() {
        val f = Fixture()
        // 真实样本形态：AI 训练输出（有 .mp4 扩展名、超 50MB，内容是文本日志）
        val junk = "sam3_output: checkpoint epoch=12 loss=0.0231\n".toByteArray() + ByteArray(64)
        f.putDir("/", listOf(f.file("/", "sam3_output.mp4", size = 80L * 1024 * 1024)))
        val scanner = f.scannerFor { _, _ -> junk }

        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals("非媒体容器不得入库", 0, f.dao.rows.size)
    }

    @Test
    fun realMediaHeader_videoFile_indexed() {
        val f = Fixture()
        val mp4 = ByteArray(32)
        mp4[4] = 'f'.code.toByte(); mp4[5] = 't'.code.toByte(); mp4[6] = 'y'.code.toByte(); mp4[7] = 'p'.code.toByte()
        f.putDir("/", listOf(f.file("/", "real.mp4")))
        val scanner = f.scannerFor { _, _ -> mp4 }

        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals(listOf("/real.mp4"), f.dao.rows.values.map { it.path })
    }

    @Test
    fun sniffReadFailure_failOpen_indexesFile() {
        val f = Fixture()
        f.putDir("/", listOf(f.file("/", "net-broken.mp4")))
        val scanner = f.scannerFor { _, _ -> null }   // 读头失败：宁可入库不误杀

        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals(listOf("/net-broken.mp4"), f.dao.rows.values.map { it.path })
    }

    @Test
    fun unchangedExistingRow_skipsSniff_noExtraReads() {
        val f = Fixture()
        f.putDir("/", listOf(f.file("/", "a.mkv")))
        runBlocking { f.scanner.scanSourceNow(f.source) }   // 首扫入库（fail-open）
        // 模拟抽帧已完成（真视频抽帧必回填 durationMs）：不再是 purgeSuspect 的可疑行
        runBlocking {
            val row = f.dao.rows.values.single()
            f.dao.upsertAll(listOf(row.copy(durationMs = 600_000L)))
        }

        // 重扫：行未变（size/mtime 相同）→ walk 不读头；durationMs 已回填 → purge 也不读头
        var sniffCalls = 0
        val scanner = f.scannerFor { _, _ -> sniffCalls++; null }
        f.putDir("/", listOf(f.file("/", "a.mkv")))
        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals(0, sniffCalls)
        assertEquals(1, f.dao.rows.size)
    }

    @Test
    fun purgeSuspectRows_removesJunkButKeepsRealMedia() {
        val f = Fixture()
        // 存量：两条 VIDEO 行 durationMs=null（真视频未抽帧 vs 垃圾文件），文件都还在
        val junkRow = com.novax.app.data.db.MediaItemEntity(
            sourceId = 7L, path = "/sam3_output.mp4", name = "sam3_output", nameLower = "sam3_output",
            sizeBytes = 80L * 1024 * 1024, mtimeMs = 123L, kind = MediaKind.VIDEO, addedAt = 1L,
        )
        val realRow = junkRow.copy(path = "/real.mp4", name = "real", nameLower = "real")
        // 注意：upsertAll 是 suspend，须在 runBlocking 内调用（见下）
        val junk = "garbage bytes not a container".toByteArray() + ByteArray(48)
        val mp4 = ByteArray(32).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        f.putDir("/", listOf(f.file("/", "sam3_output.mp4", size = 80L * 1024 * 1024), f.file("/", "real.mp4")))
        val scanner = f.scannerFor { _, path -> if (path.endsWith("sam3_output.mp4")) junk else mp4 }

        runBlocking {
            f.dao.upsertAll(listOf(junkRow, realRow))
            scanner.scanSourceNow(f.source)
        }

        // 垃圾行被清；真媒体（读头命中 ftyp）保留
        assertEquals(listOf("/real.mp4"), f.dao.rows.values.map { it.path }.sorted())
    }

    // ---------- 可取消 ----------

    @Test
    fun cancellation_marksSourceCancelled_andKeepsCommittedBatches() {
        val f = Fixture()
        val lister: suspend (SourceEntity, String) -> Result<List<NvFileEntry>> = { _, dirPath ->
            if (dirPath == "/stop/") throw CancellationException("用户取消")
            Result.success(listOf(f.file("/", "a.mkv"), f.dir("/", "stop")))
        }
        val scanner = MediaScanner(
            mediaDao = f.dao, scanDao = f.scanState, lister = lister,
            shareLister = { Result.success(emptyList()) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        try {
            runBlocking { scanner.scanSourceNow(f.source) }
            throw AssertionError("应抛出取消")
        } catch (expected: CancellationException) {
            // 预期：取消沿协程向外传播
        }
        assertEquals(ScanStatus.CANCELLED, f.scanState.states[7L]!!.status)
        assertTrue(f.dao.rows.isNotEmpty())   // 已入库批次保留（增量语义）
    }

    // ---------- D1（2026-10-09 审计 P1）：非根目录失读 → 跳过子树，不整源 FAILED，不对账 ----------

    /**
     * D1 回归：非根目录列举失败（API 30+ 的 /Android/data 系统受限、SD 卡热拔）不得致命化
     * 整源——跳过该子树继续扫，DONE 带 skipped note；失读子树不进 seen，本次对账/复验必须
     * 整体跳过，防止把失读子树的存量行当"已消失"误删（S1-F8 同机理共用 incomplete 闸）。
     */
    @Test
    fun listFailure_nonRootDir_skipsSubtree_skipsReconcile_notFailed() {
        val f = Fixture()
        // 存量行：一条在失读子树内（不得被误删），一条在可达区（本次 seen 正常保留）
        fun seed(path: String) = f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = path, name = "x", nameLower = "x",
                sizeBytes = ScanRules.MIN_LIBRARY_VIDEO_BYTES, mtimeMs = 123L, kind = MediaKind.VIDEO,
                thumbnailPath = "/cache/t.jpg", thumbState = ThumbState.DONE.name, addedAt = 9L,
            ),
        )
        seed("/locked/old.mkv")
        seed("/kept.mkv")
        f.putDir("/", listOf(f.dir("/", "ok"), f.dir("/", "locked")))
        f.putDir("/ok/", listOf(f.file("/ok/", "v.mkv")))
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath ->
                if (dirPath == "/locked/") Result.failure(IOException("目录不可读: /locked/"))
                else {
                    f.visitedDirs.add(dirPath)
                    Result.success(f.tree[dirPath] ?: emptyList())
                }
            },
            shareLister = { Result.success(emptyList()) },
            headerReader = { _, _ -> null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        runBlocking { scanner.scanSourceNow(f.source) }

        val st = f.scanState.states[7L]!!
        assertEquals(ScanStatus.DONE, st.status)   // 不再整源 FAILED
        assertNotNull(st.error)
        assertTrue(st.error!!.contains("1 个目录不可读"))
        // 失读子树存量行不被误删；可达区新文件照常入库
        assertEquals(
            setOf("/ok/v.mkv", "/kept.mkv", "/locked/old.mkv"),
            f.dao.rows.values.map { it.path }.toSet(),
        )
        assertEquals(0, f.dao.deleteNotInCalls)    // 发生过失读 → 本次对账跳过
    }

    /** 对照：扫描根本身不可读（depth 0）维持整源 FAILED，不做局部跳过 */
    @Test
    fun listFailure_scanRootUnreadable_stillFailed() {
        val f = Fixture()
        val lister: suspend (SourceEntity, String) -> Result<List<NvFileEntry>> =
            { _, _ -> Result.failure(IOException("目录不可读: /")) }
        val scanner = MediaScanner(
            mediaDao = f.dao, scanDao = f.scanState, lister = lister,
            shareLister = { Result.success(emptyList()) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals(ScanStatus.FAILED, f.scanState.states[7L]!!.status)
    }

    /**
     * 2026-10-19 用户裁定：扫描根「路径不存在」类失败（STATUS_OBJECT_PATH_NOT_FOUND——
     * NAS 侧把目录改名/移动，迅雷类自管共享常态）不再整源 FAILED——降级跳过该根继续扫，
     * 其余根照常建索引，DONE 带 missing note；失效根子树不进 seen，存量行由 incomplete
     * 闸保护不被对账误删（目录可能只是暂时改名，用户随时会编辑源重选）。
     */
    @Test
    fun scanRoot_missingPath_downgradedToSkip_othersStillIndexed_notFailed() {
        val f = Fixture()
        val withRoots = f.source.copy(scanRoots = ScanRoots.encode(listOf("/gone", "/ok")))
        // 失效根子树的存量行：不得因根降级跳过被对账当"已消失"误删
        f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = "/gone/old.mkv", name = "old", nameLower = "old",
                sizeBytes = ScanRules.MIN_LIBRARY_VIDEO_BYTES, mtimeMs = 123L, kind = MediaKind.VIDEO,
                thumbnailPath = "/cache/t.jpg", thumbState = ThumbState.DONE.name, addedAt = 9L,
            ),
        )
        f.putDir("/ok/", listOf(f.file("/ok/", "v.mkv")))
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath ->
                if (dirPath == "/gone/") Result.failure(
                    IOException(
                        "operation 测试NAS: STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): " +
                            "Create failed for \\\\192.168.1.10\\media\\gone",
                    ),
                ) else {
                    f.visitedDirs.add(dirPath)
                    Result.success(f.tree[dirPath] ?: emptyList())
                }
            },
            shareLister = { Result.success(emptyList()) },
            headerReader = { _, _ -> null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        runBlocking { scanner.scanSourceNow(withRoots) }

        val st = f.scanState.states[7L]!!
        assertEquals(ScanStatus.DONE, st.status)   // 不再整源 FAILED
        assertNotNull(st.error)
        assertTrue(st.error!!.contains("1 个扫描目录"))
        assertTrue(st.error!!.contains("重新选择扫描目录"))
        assertEquals(listOf("/ok/"), f.visitedDirs)   // 失效根跳过，其余根照常扫
        assertEquals(
            setOf("/ok/v.mkv", "/gone/old.mkv"),
            f.dao.rows.values.map { it.path }.toSet(),
        )
        assertEquals(0, f.dao.deleteNotInCalls)      // 发生缺路径跳过 → 本次对账跳过
    }

    /** 扫描根缺路径 + 非根失读同时发生：note 两段并列，互不覆盖 */
    @Test
    fun scanRoot_missingPath_andUnreadableSubdir_bothNoted() {
        val f = Fixture()
        // 两个平级根（嵌套根会被 ScanRoots.dedup 折叠进 "/"，缺路径根就不会被列出）
        val withRoots = f.source.copy(scanRoots = ScanRoots.encode(listOf("/gone", "/media")))
        f.putDir("/media/", listOf(f.dir("/media/", "locked"), f.file("/media/", "m.mkv")))
        f.putDir("/media/locked/", listOf(f.file("/media/locked/", "x.mkv")))
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath ->
                when {
                    dirPath == "/gone/" -> Result.failure(
                        IOException("operation x: STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): Create failed for \\\\h\\s\\gone"),
                    )
                    dirPath == "/media/locked/" -> Result.failure(IOException("目录不可读: /media/locked/"))
                    else -> {
                        f.visitedDirs.add(dirPath)
                        Result.success(f.tree[dirPath] ?: emptyList())
                    }
                }
            },
            shareLister = { Result.success(emptyList()) },
            headerReader = { _, _ -> null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        runBlocking { scanner.scanSourceNow(withRoots) }

        val st = f.scanState.states[7L]!!
        assertEquals(ScanStatus.DONE, st.status)
        assertTrue(st.error!!.contains("1 个扫描目录"))
        assertTrue(st.error!!.contains("1 个目录不可读"))
        assertTrue(st.error!!.contains("；"))
    }

    // ---------- 失败 ----------

    @Test
    fun listFailure_marksSourceFailed_noReconcile() {
        val f = Fixture()
        f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = "/old.mkv", name = "old", nameLower = "old",
                sizeBytes = 1L, mtimeMs = 1L, kind = MediaKind.VIDEO, addedAt = 1L,
            ),
        )
        val lister: suspend (SourceEntity, String) -> Result<List<NvFileEntry>> =
            { _, _ -> Result.failure(IOException("无法连接主机")) }
        val scanner = MediaScanner(
            mediaDao = f.dao, scanDao = f.scanState, lister = lister,
            shareLister = { Result.success(emptyList()) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        runBlocking { scanner.scanSourceNow(f.source) }

        val st = f.scanState.states[7L]!!
        assertEquals(ScanStatus.FAILED, st.status)
        assertTrue(st.error!!.contains("无法连接"))
        assertEquals(1, f.dao.rows.size)   // 失败不清理、保留旧行（对账跳过）
        assertEquals(0, f.dao.deleteNotInCalls)
    }

    // ---------- 排除目录（sources.excludedDirs，2026-09-15 用户需求） ----------

    @Test
    fun excludedDirs_subtreePruned_fromBfs_andLegacyRowsReconciled() {
        val f = Fixture()
        val withExcl = f.source.copy(excludedDirs = ScanRoots.encode(listOf("/睿图工作文件")))
        // 存量行：排除子树内的旧索引（对账应清）+ 排除外的正常行（应保留）
        fun seed(path: String, kind: MediaKind) = f.dao.seed(
            MediaItemEntity(
                sourceId = 7L, path = path, name = "x", nameLower = "x",
                sizeBytes = 500L * 1024, mtimeMs = 123L, kind = kind, addedAt = 1L,
            ),
        )
        seed("/睿图工作文件/next.png", MediaKind.IMAGE)
        seed("/keep.mkv", MediaKind.VIDEO)

        f.putDir(
            "/",
            listOf(
                f.dir("/", "睿图工作文件"),
                f.file("/", "keep.mkv"),
            ),
        )
        f.putDir(
            "/睿图工作文件/",
            listOf(
                f.file("/睿图工作文件/", "big-tool.mp4", size = 800L * 1024 * 1024),
                f.file("/睿图工作文件/", "icon.png", size = 300L * 1024),
            ),
        )

        runBlocking { f.scanner.scanSourceNow(withExcl) }

        // BFS 剪枝：排除目录整体未列
        assertTrue("/睿图工作文件/" !in f.visitedDirs)
        // 排除子树内存量行被对账清除，排除外行保留
        assertEquals(setOf("/keep.mkv"), f.dao.rows.values.map { it.path }.toSet())
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    @Test
    fun excludedDir_matchingScanRoot_skipsWholeSourceBfs() {
        val f = Fixture()
        val s = f.source.copy(
            scanRoots = ScanRoots.encode(listOf("/video", "/movies")),
            excludedDirs = ScanRoots.encode(listOf("/video")),
        )
        f.putDir("/video/", listOf(f.file("/video/", "v.mkv")))
        f.putDir("/movies/", listOf(f.file("/movies/", "m.mkv")))

        runBlocking { f.scanner.scanSourceNow(s) }

        assertEquals(listOf("/movies/"), f.visitedDirs)
        assertEquals(setOf("/movies/m.mkv"), f.dao.rows.values.map { it.path }.toSet())
    }

    @Test
    fun excludedDirs_allRootsExcluded_indexEmptied_notFallbackToWholeSource() {
        val f = Fixture()
        val s = f.source.copy(
            scanRoots = ScanRoots.encode(listOf("/video")),
            excludedDirs = ScanRoots.encode(listOf("/video")),
        )
        f.putDir("/", listOf(f.file("/", "root.mkv")))
        f.putDir("/video/", listOf(f.file("/video/", "v.mkv")))

        runBlocking { f.scanner.scanSourceNow(s) }

        // 全部扫描根被排除 → 本次扫描空跑并对账清空，绝不回退扫 "/"（排除即所见）
        assertTrue(f.visitedDirs.isEmpty())
        assertTrue(f.dao.rows.isEmpty())
        assertEquals(ScanStatus.DONE, f.scanState.states[7L]!!.status)
    }

    @Test
    fun sparseDirs_timeFlush_commitsFoundItemsBeforeWalkEnds() {
        // 2026-09-15 用户反馈（评审 P4）：目录稀疏时 batch 不满 100 个不提交 →
        // 扫描前半程已发现的视频一直不可见。现按 3s 时间 flush（假时钟驱动）。
        val f = Fixture()
        var now = 0L
        f.putDir("/", listOf(f.dir("/", "d1"), f.dir("/", "d2")))
        f.putDir("/d1/", listOf(f.file("/d1/", "a.mkv")))
        f.putDir("/d2/", listOf(f.file("/d2/", "b.mkv")))
        var seenD2Rows = -1
        val scanner = MediaScanner(
            mediaDao = f.dao,
            scanDao = f.scanState,
            lister = { _, dirPath ->
                if (dirPath == "/d2/") seenD2Rows = f.dao.rows.size   // 列 d2 前，d1 的行应已因时间 flush 落库
                now += 4_000L                                          // 每次列目录时钟 +4s（> 3s 阈值）
                Result.success(f.tree[dirPath] ?: emptyList())
            },
            shareLister = { Result.success(emptyList()) },
            headerReader = { _, _ -> null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).also { it.clockMs = { now } }

        runBlocking { scanner.scanSourceNow(f.source) }

        assertEquals("d1 的行必须在列 d2 之前已提交（时间 flush）", 1, seenD2Rows)
        assertEquals(2, f.dao.rows.size)
    }}
