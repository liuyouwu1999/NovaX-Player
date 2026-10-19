package com.novax.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novax.app.R
import com.novax.app.data.NvGraph
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.FavoriteEntity
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.data.db.WatchKind
import com.novax.app.data.scanner.NvScannerHolder
import com.novax.app.data.db.ScanStatus
import com.novax.app.data.scanner.ScanProgress
import com.novax.app.ui.files.NvFilesDeepLink
import com.novax.app.ui.files.folderTargetOf as sharedFolderTargetOf
import com.novax.app.data.thumb.NvThumbHolder
import com.novax.app.data.privacy.NvFavoriteCipherRows
import com.novax.app.data.privacy.NvFavoriteDirect
import com.novax.app.data.privacy.NvHistoryCipherRows
import com.novax.app.data.privacy.NvPrivacyCrypto
import com.novax.app.data.privacy.NvPrivacyListFilter
import com.novax.app.player.NvHistoryDirect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 UI 状态（2026-09-15 重设计版）：
 * 全部字段来自真实数据源（Room Flow / 扫描器 StateFlow），零 mock、零假扫描延迟。
 * - isEmptySource 由 sources 表派生（旧行为是恒 false 的死字段，本轮修复）
 * - scanRunning = 扫描器真实 RUNNING 进度（有值才显示横幅，替代旧 1.7s 假骨架）
 */
data class HomeUiState(
    val isEmptySource: Boolean = false,
    val scanRunning: List<ScanProgress> = emptyList(),
    val continueRows: List<ContinueRow> = emptyList(),
    val favoriteRows: List<FavoriteRow> = emptyList(),
    val realFavoriteKeys: Set<String> = emptySet(),
    val recentRows: List<MediaItemEntity> = emptyList(),
    val kindCounts: Map<String, Int> = emptyMap(),
    val sourceCount: Int = 0,
)

class HomeViewModel(private val appContext: Context) : ViewModel() {

    private val historyDao = NvGraph.historyDao(appContext)
    private val favoriteDao = NvGraph.favoriteDao(appContext)
    private val db = MediaDb.get(appContext)
    // 隐私分类门面（Task 9）：继续观看行的改写经 NvHistoryDirect，不旁路 historyDao
    private val privacy = com.novax.app.data.privacy.NvPrivacyAccess.get(appContext)
    // 密文行解密（Task 14 读端）：与 Access 内部同一进程级单例实例
    private val cipher = NvPrivacyCrypto.keystoreCipher()
    private val mediaItemDao = db.mediaItemDao()
    private val sourceDao = db.sourceDao()

    /** LOCAL 源播放 URL 构造需要 source 实体（playUrlOf）；sources 流的最新快照 */
    @Volatile
    private var cachedSources: List<com.novax.app.data.db.SourceEntity> = emptyList()

    /** 继续观看行（带缩略图 JOIN）+ 存活源 id 集（VM 层 join sources 过滤失效源条目） */
    private data class ContinueFeed(
        val rows: List<com.novax.app.data.db.ContinueWatchingRow>,
        val validSourceIds: Set<Long>,
    )

    private val continueFeed = combine(
        historyDao.observeContinueWatching(),
        sourceDao.observeAll(),
    ) { rows, sources ->
        cachedSources = sources
        // 隐私口径（Task 14）：密文行解密展示（解密失败 fail-closed 滤掉）+ 锁定行滤掉。
        // T9 移交：密文行 path=hash，observeContinueWatching 的 LEFT JOIN media_items 按 path
        // 落空、缩略图全灭——解密后须按 (sourceId, plainPath) 回查 media_items 重建 JOIN 语义
        val visible = rows.mapNotNull { r ->
            NvPrivacyListFilter.decryptDisplay(cipher, r.entry)?.let { r.copy(entry = it) to NvHistoryCipherRows.isCiphered(r.entry) }
        }.let { pairs ->
            NvPrivacyListFilter.filter(pairs, privacy) { it.first.entry.sourceId to it.first.entry.path }
        }
        ContinueFeed(
            rebuildCipherThumbs(enrichLocalThumbs(visible.map { it.first }, sources), visible.mapNotNull { p -> p.first.entry.id.takeIf { p.second } }.toSet()),
            sources.mapTo(mutableSetOf()) { it.id },
        )
    }

    /**
     * 密文行缩略图回查（Task 14，T9 移交项）：原密文行在 SQL JOIN 按 hash path 落空
     * （thumbnailPath=null），解密后按明文键补查 media_items。只补原密文且仍缺图的
     * NAS 行（sourceId!=null；LOCAL 行 sourceId==null 走 enrichLocalThumbs 的根前缀反查）。
     */
    private suspend fun rebuildCipherThumbs(
        rows: List<com.novax.app.data.db.ContinueWatchingRow>,
        cipheredIds: Set<Long>,
    ): List<com.novax.app.data.db.ContinueWatchingRow> = rows.map { row ->
        if (row.entry.id !in cipheredIds || row.thumbnailPath != null || row.entry.sourceId == null) {
            row
        } else {
            runCatching { mediaItemDao.findByKey(row.entry.sourceId, row.entry.path)?.thumbnailPath }.getOrNull()
                ?.let { row.copy(thumbnailPath = it) } ?: row
        }
    }

    /**
     * BUG-4 对偶（2026-10-18 发布终审）：继续观看行的 LOCAL 封面回填——SQL JOIN 按
     * (sourceId,path) 直连键取 mi.thumbnailPath，LOCAL 播放历史是 (null, 绝对路径)
     * 恒不命中 → 本地片在首页恒渐变占位（帧封面明明已抽）。仅对 thumbPath==null 且
     * sourceId==null 的行：绝对路径按 LOCAL 源根前缀反查 (源id, 相对路径) 再查
     * media_items（(sourceId,path) 唯一索引单行查）。流量上限 = 继续观看 LIMIT 50，
     * 且只对缺图行逐行查；NAS 源键直连命中不进本路径，零开销。
     */
    private suspend fun enrichLocalThumbs(
        rows: List<com.novax.app.data.db.ContinueWatchingRow>,
        sources: List<com.novax.app.data.db.SourceEntity>,
    ): List<com.novax.app.data.db.ContinueWatchingRow> {
        if (rows.all { it.thumbnailPath != null || it.entry.sourceId != null }) return rows
        val locals = sources.asSequence()
            .filter { it.type == SourceType.LOCAL }
            .map { it.id to it.shareOrPath.trimEnd('/', '\\') }
            .filter { it.second.isNotEmpty() }
            .toList()
        if (locals.isEmpty()) return rows
        return rows.map { row ->
            if (row.thumbnailPath != null || row.entry.sourceId != null) return@map row
            val abs = row.entry.path
            val hit = locals.firstNotNullOfOrNull { (sid, root) ->
                if (abs.length > root.length + 1 && abs.startsWith("$root/")) sid to abs.substring(root.length)
                else null
            } ?: return@map row
            val thumb = runCatching { mediaItemDao.findByKey(hit.first, hit.second)?.thumbnailPath }.getOrNull()
            if (thumb != null) row.copy(thumbnailPath = thumb) else row
        }
    }

    private val librarySnapshot = combine(
        sourceDao.observeAll(),
        mediaItemDao.observeRecentlyAdded(),
        mediaItemDao.observeKindCounts(),
    ) { sources, recent, counts ->
        Triple(sources, recent, counts.associate { it.kind to it.count })
    }

    private val favoritesWithScan = combine(
        favoriteDao.observeAllWithThumb(),
        NvScannerHolder.get(appContext).progress,
    ) { favs, scan ->
        // 隐私口径（Task 14）：密文收藏行解密展示（失败 fail-closed 滤掉）+ 锁定行滤掉；
        // JOIN 按 hash path 落空的缩略图按明文键回查（仅原密文且仍缺图的 NAS 行）
        val visible = favs.mapNotNull { f ->
            NvPrivacyListFilter.decryptDisplay(cipher, f.favorite)?.let { f.copy(favorite = it) to NvFavoriteCipherRows.isCiphered(f.favorite) }
        }.let { pairs ->
            NvPrivacyListFilter.filter(pairs, privacy) { it.first.favorite.sourceId to it.first.favorite.path }
        }
        visible.map { (f, wasCipher) ->
            if (!wasCipher || f.thumbnailPath != null || f.favorite.sourceId == null) f
            else runCatching { mediaItemDao.findByKey(f.favorite.sourceId, f.favorite.path)?.thumbnailPath }.getOrNull()
                ?.let { f.copy(thumbnailPath = it) } ?: f
        } to scan
    }

    val state: StateFlow<HomeUiState> = combine(
        librarySnapshot,
        continueFeed,
        favoritesWithScan,
    ) { (sources, recent, counts), feed, (favs, scan) ->
        // 继续观看卡真帧：JOIN 已带出 thumbnailPath，按 (sourceId,path) 键对回过滤后的行
        val thumbByKey = feed.rows.associate {
            favoriteKey(it.entry.sourceId, it.entry.path) to it.thumbnailPath
        }
        HomeUiState(
            isEmptySource = sources.isEmpty(),
            scanRunning = scan.values.filter { it.status == ScanStatus.RUNNING },
            continueRows = filterContinueWatching(feed.rows.map { it.entry }, feed.validSourceIds)
                .map { e -> e.toContinueRow(thumbByKey[favoriteKey(e.sourceId, e.path)]) },
            favoriteRows = favs.map { it.favorite.toFavoriteRow(it.thumbnailPath) },
            realFavoriteKeys = favs.map { favoriteKey(it.favorite.sourceId, it.favorite.path) }.toSet(),
            recentRows = recent,
            kindCounts = counts,
            sourceCount = sources.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** 概览卡文案（i18n：纯函数层给 (计数, plurals) 分段，此处按四语解析后拼接，spec §1.3） */
    fun overviewLine(counts: Map<String, Int>): String? =
        libraryOverviewSegments(counts).takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { (n, res) -> appContext.resources.getQuantityString(res, n, n) }

    /** 扫描横幅第 1 行（i18n Task 8 评审移交②：文案资源化，与库页同式） */
    fun scanTitleOf(p: ScanProgress): String {
        val name = cachedSources.firstOrNull { it.id == p.sourceId }?.nickname
            ?: appContext.getString(R.string.home_source_fallback, p.sourceId)
        return appContext.getString(
            R.string.data_scan_banner_title,
            name, p.scannedDirs, p.foundItems,
        )
    }

    /** 首页横幅停止扫描（2026-09-15 补：旧版首页横幅无 ×，用户无法在首页停止扫描） */
    fun cancelAllScans() {
        NvScannerHolder.get(appContext).cancelAll()
    }

    /**
     * 长按「打开所在文件夹」目标（2026-10-13）：mock 检查 + 共享推导；
     * sources 用 continueFeed 分支持续刷新的 cachedSources。null = 无文件夹概念，菜单隐藏。
     */
    fun folderTargetOf(sourceId: Long?, path: String): NvFilesDeepLink.Target? =
        if (NvPlaybackLauncher.isMockMediaId(path)) null
        else sharedFolderTargetOf(sourceId, path, cachedSources)

    /** 收藏唯一键（与库页 favoriteKeyOf 同构：LOCAL=(null, 绝对路径)，其余=(sourceId, 源内路径)） */
    fun favoriteKeyOf(item: MediaItemEntity): Pair<Long?, String> {
        val src = cachedSources.firstOrNull { it.id == item.sourceId }
        return if (src?.type == SourceType.LOCAL) {
            null to NvPlaybackLauncher.localPath(src.shareOrPath, item.path)
        } else {
            item.sourceId to item.path
        }
    }

    /** 最近添加卡长按「收藏」态判定 */
    fun isFavorite(item: MediaItemEntity, keys: Set<String>): Boolean =
        favoriteKeyOf(item).let { (sid, path) -> favoriteKey(sid, path) } in keys

    // ── 抽帧（最近添加卡 NONE 触发 / 长按重抽）：与库页同一 NvThumbHolder 路径 ──

    fun needThumb(mediaItemId: Long) {
        viewModelScope.launch {
            runCatching { NvThumbHolder.get(appContext).requestThumb(mediaItemId) }
        }
    }

    /** 重新抽帧：resetThumb 清路径+复位 NONE → requestThumb 重新 CAS 入队（与库页 reextractThumb 同构） */
    fun reextractThumb(item: MediaItemEntity) {
        viewModelScope.launch {
            runCatching {
                mediaItemDao.resetThumb(item.id)
                NvThumbHolder.get(appContext).requestThumb(item.id)
            }
        }
    }

    // ── 继续观看长按菜单 ──

    /** 「从继续观看移除」：标记看完（positionMs=durationMs），退出 <95% 续播窗口。
     *  隐私口径（Task 9）：经 NvHistoryDirect 改写——无痕不写、密文行解密后原样按密文改写，
     *  watchedAt 保持原值（与旧 markWatchedToEnd SQL 只动 positionMs 的口径一致） */
    fun removeFromContinueWatching(e: WatchHistoryEntity) {
        viewModelScope.launch {
            runCatching { NvHistoryDirect.markWatched(db, privacy, e.copy(positionMs = e.durationMs)) }
        }
    }

    /** 「清除这条记录」：按主键删除该条历史（Room Flow 自动刷新首页） */
    fun deleteHistoryEntry(e: WatchHistoryEntity) {
        viewModelScope.launch {
            runCatching { historyDao.deleteById(e.id) }
        }
    }

    // ── 收藏 ──

    /**
     * 真收藏切换（双键查重：查有删/查无插）：kind 按条目真实类型写入
     * （旧行为恒写 MOVIE 会污染媒体库 kind 统计，2026-09-15 修复）。
     * 隐私口径（Task 9 修复轮）：经 NvFavoriteDirect——锁命中内容密文行落盘，无痕不拦收藏。
     */
    fun toggleRealFavorite(sourceId: Long?, path: String, title: String, kind: String) {
        viewModelScope.launch {
            runCatching {
                NvFavoriteDirect.toggle(
                    db,
                    privacy,
                    FavoriteEntity(
                        sourceId = sourceId,
                        path = path,
                        title = title,
                        kind = kind,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    // ── 播放（最近添加/收藏点击）：与 LibraryViewModel.playUrlOf 同构 ──

    fun playUrlOf(item: MediaItemEntity): String {
        val source = cachedSources.firstOrNull { it.id == item.sourceId }
        return if (source?.type == SourceType.LOCAL) {
            NvPlaybackLauncher.localPath(source.shareOrPath, item.path)
        } else {
            NvPlaybackLauncher.novaxUrl(item.sourceId, item.path)
        }
    }

    /** 收藏 kind 判定（与 LibraryViewModel.watchKindOf 同构） */
    fun watchKindOf(e: MediaItemEntity): String = when (e.kind) {
        com.novax.app.data.db.MediaKind.EPISODE -> WatchKind.EPISODE.name
        else -> WatchKind.MOVIE.name
    }

    companion object {
        /** viewModel(factory = ...) 入口（App 无 DI 框架） */
        fun factory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(appContext) as T
            }
    }
}
