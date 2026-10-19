package com.novax.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novax.app.data.NvGraph
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.WatchHistoryEntity
import com.novax.app.ui.files.NvFilesDeepLink
import com.novax.app.ui.files.folderTargetOf as sharedFolderTargetOf
import com.novax.app.ui.home.NvPlaybackLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 历史行 UI 模型：原始历史 + 派生的「所在文件夹」能力（跳转/展示用） */
data class HistoryRowUi(
    val entry: WatchHistoryEntity,
    /** 所在文件夹展示文案（源内父目录；本地为绝对路径父目录） */
    val folderLabel: String,
    /** 「打开所在文件夹」是否可用：mock mediaId / 远程直链 URL 无文件夹概念 */
    val canOpenFolder: Boolean,
    /** 非 null 时长按菜单可直接跳转 */
    val folderTarget: NvFilesDeepLink.Target?,
    /** 剧集角标（S1E2）；非剧集为 null */
    val episodeTag: String?,
)

/**
 * 观看历史页（2026-09-27 用户需求）：此前历史只有设置页的计数+全清，看不到内容。
 * 全量倒序列表（含看完的——区别于首页「继续观看」的 15s/95% 过滤横排），
 * 点=续播（NvPlaybackLauncher.resumeFrom），长按=打开所在文件夹/删除。
 */
class WatchHistoryViewModel(app: Application) : AndroidViewModel(app) {
    // AndroidViewModel 构造：viewModel() 默认工厂（NewInstanceFactory）按项目惯例可实例化
    private val appContext: Application = app

    private val historyDao = NvGraph.historyDao(appContext)
    private val sourceDao = NvGraph.sourceDao(appContext)

    val rows: StateFlow<List<HistoryRowUi>> =
        combine(historyDao.observeAllForBackfill(), sourceDao.observeAll()) { list, sources ->
            list.sortedByDescending { it.watchedAt }.map { decorate(it, sources) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { historyDao.deleteById(id) }
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { historyDao.clearAll() }
        }
    }

    // ---------- 派生：文件夹能力 ----------

    private fun decorate(e: WatchHistoryEntity, sources: List<SourceEntity>): HistoryRowUi {
        val target = folderTargetOf(e, sources)
        val source = sources.firstOrNull { it.id == e.sourceId }
        val parent = e.path.trim('/').substringBeforeLast('/')
        val label = when {
            !NvPlaybackLauncher.isMockMediaId(e.path) && e.path.contains('/') ->
                if (source != null && source.nickname.isNotBlank()) "${source.nickname} · $parent" else parent
            else -> e.path
        }
        val tag = if (e.kind == "EPISODE" && e.seasonNumber != null && e.episodeNumber != null) {
            "S${e.seasonNumber}E${e.episodeNumber}"
        } else {
            null
        }
        return HistoryRowUi(
            entry = e,
            folderLabel = label,
            canOpenFolder = target != null,
            folderTarget = target,
            episodeTag = tag,
        )
    }

    /**
     * 「打开所在文件夹」目标推导：
     * - novax 源条目（sourceId != null）：父目录段即面包屑，直接定位；
     * - 本地直连（sourceId == null 且绝对路径）：回配 shareOrPath 为其前缀的 LOCAL 源，
     *   转成源内相对面包屑（历史键=绝对路径，与 NvPlaybackLauncher.localPath 同构）；
     * - mock mediaId / 远程直链 URL：无文件夹概念，返回 null（菜单隐藏该项）。
     * 2026-10-13 推导逻辑提取共享（ui/files/NvFilesDeepLink.kt），本处仅保留 mock 前置判断
     */
    private fun folderTargetOf(e: WatchHistoryEntity, sources: List<SourceEntity>): NvFilesDeepLink.Target? {
        if (NvPlaybackLauncher.isMockMediaId(e.path)) return null
        return sharedFolderTargetOf(e.sourceId, e.path, sources)
    }
}
