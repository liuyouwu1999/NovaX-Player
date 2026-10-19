@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.data.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.SourceEntity
import com.novax.app.player.PlaybackEngine
import com.novax.app.player.SubtitleCandidate
import com.novax.app.player.SubtitleMatcher

/**
 * C8 · PlayerActivity 接入用 MediaSource 构建器：
 * 输入 [SourceEntity] + 源内路径 → 输出带 NAS DataSource 的 [MediaSource]
 * （连接复用策略由 [NvDataSourceFactory]/[NvSourceRepository] 承担，seek 不重建会话）。
 *
 * 同目录字幕（C6 §3.1）：调用方先经 [NvSourceRepository.listSubtitles]（IO 线程）
 * 拿 [SubtitleCandidate] 列表传入；匹配评分复用 player.SubtitleMatcher。
 * 字幕与视频共用同一 [NvDataSourceFactory]（DefaultMediaSourceFactory 会把
 * SubtitleConfiguration 以 SingleSampleMediaSource 合并，upstream 即本工厂），
 * 因此 SMB/WebDAV 外挂字幕可直接经 novax:// URI 读取，无需下载到本地。
 */
object NvMediaSourceBuilder {

    private const val TAG = "NvMediaSourceBuilder"

    /**
     * 构建视频 MediaSource。
     * @param subtitleCandidates 同目录字幕候选（可空；由 repository.listSubtitles 提供）
     */
    fun build(
        context: Context,
        source: SourceEntity,
        path: String,
        repository: NvSourceRepository,
        subtitleCandidates: List<SubtitleCandidate> = emptyList(),
    ): MediaSource {
        val factory = NvDataSourceFactory(source, path, repository)
        val configs = subtitleConfigurationsFor(source, subtitleCandidates)
        val mediaItem = MediaItem.Builder()
            .setUri(NvUri.forSource(source, path))
            .setSubtitleConfigurations(configs)
            .build()
        return DefaultMediaSourceFactory(factory, nvExtractorsFactory())
            .setSubtitleParserFactory(com.novax.app.player.NvShiftedSubtitleParserFactory())
            .createMediaSource(mediaItem)
    }

    /**
     * 提取器工厂（2026-09-30 坏索引 MKV 降级 seek；2026-10-03 扩 MP4 分片）：
     * 保持 DefaultExtractorsFactory 的默认嗅探顺序，MatroskaExtractor 包 Matroska 规格、
     * Mp4Extractor（media3 默认族中 MP4 含分片 fMP4 都由它处理）包 MP4 规格（'moof' 锚点）——
     * 有索引片源 / 其他容器在包装器内零行为变化，仅 Unseekable+长度已知时激活降级。
     * 包装逻辑抽为 [wrapWithFallbackSeek]（纯函数，JVM 单测直接测它）。
     */
    internal fun nvExtractorsFactory(): androidx.media3.extractor.ExtractorsFactory =
        androidx.media3.extractor.ExtractorsFactory {
            wrapWithFallbackSeek(androidx.media3.extractor.DefaultExtractorsFactory().createExtractors())
                .also { arr ->
                    android.util.Log.d(
                        "NvFallbackSeek",
                        "factory: " + arr.joinToString(",") { it.javaClass.simpleName },
                    )
                }
        }

    internal fun wrapWithFallbackSeek(
        extractors: Array<androidx.media3.extractor.Extractor>,
    ): Array<androidx.media3.extractor.Extractor> = extractors.map { extractor ->
        when (extractor) {
            is androidx.media3.extractor.mkv.MatroskaExtractor ->
                NvFallbackSeekExtractor(extractor, NvFallbackSeekExtractor.ResyncSpec.MATROSKA)
            // 2026-10-03 真机实测补口：media3 1.8.0 默认序列中 FragmentedMp4Extractor 排在
            // Mp4Extractor 之前且默认只包了后者——分片 MP4（无 sidx 的下载/转存片源）嗅探
            // 先命中 FragmentedMp4Extractor，完全绕过降级管线 → 进度条依旧「源限制」拒绝
            //（模拟器 factory 组成日志实证：Flv,…,FragmentedMp4Extractor,NvFallbackSeek…）
            is androidx.media3.extractor.mp4.FragmentedMp4Extractor ->
                NvFallbackSeekExtractor(extractor, NvFallbackSeekExtractor.ResyncSpec.MP4)
            is androidx.media3.extractor.mp4.Mp4Extractor ->
                NvFallbackSeekExtractor(extractor, NvFallbackSeekExtractor.ResyncSpec.MP4)
            else -> extractor
        }
    }.toTypedArray()

    /**
     * 同目录外挂字幕（含自动选择标记）→ SubtitleConfiguration 列表。
     * 仅保留可识别 MIME 的候选（srt/ass/ssa/vtt；.sub/.idx 需成对，跳过）。
     * pickAuto 唯一最高分（score>=80）置 SELECTION_FLAG_DEFAULT 供默认选中。
     */
    fun subtitleConfigurationsFor(
        source: SourceEntity,
        candidates: List<SubtitleCandidate>,
    ): List<MediaItem.SubtitleConfiguration> {
        if (candidates.isEmpty()) return emptyList()
        // BUG-C-R5-07：只给自动选择命中的候选置 SELECTION_FLAG_DEFAULT——原实现全量置
        // DEFAULT，「唯一最高分自动选中」语义失效（全部默认选中 = 轨道选择器随机选）
        val (autoPick, autoOk) = SubtitleMatcher.pickAuto(candidates)
        val configs = candidates.mapNotNull { c ->
            val mime = SubtitleMatcher.mimeTypeForFile(c.path) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(NvUri.forSource(source, c.path))
                .setId(subtitleIdFor(c.path))
                .setMimeType(mime)
                .setLanguage(c.languageTag?.let { SubtitleMatcher.normalizeLanguageTag(it) })
                .setLabel(c.displayName)
                .setSelectionFlags(if (autoOk && c.path == autoPick?.path) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        if (configs.isNotEmpty()) {
            Log.d(TAG, "subtitles=${configs.size} auto=${if (autoOk) autoPick?.displayName else "none"}")
        }
        return configs
    }

    /** 字幕轨稳定 Format.id（UI 选中态对齐用；与运行时 side-load 保持同一 id 体系） */
    fun subtitleIdFor(path: String): String = PlaybackEngine.sideLoadIdFor(path)

    /**
     * novax:// 路由 MediaSource.Factory：novax URI 走 [NvDataSourceFactory]（NAS 随机读），
     * 其余 scheme 回落 DefaultMediaSourceFactory（file/http/asset 原生支持）。
     * 注入 PlaybackEngine 后，运行时 side-load 字幕的 setMediaItem 同样经此工厂，
     * 连接池在 repository 层复用，换字幕不会重建会话。
     */
    class NvRoutingMediaSourceFactory(
        context: Context,
        private val source: SourceEntity,
        private val repository: NvSourceRepository,
    ) : MediaSource.Factory {

        private val delegate = DefaultMediaSourceFactory(context, nvExtractorsFactory())
            .setSubtitleParserFactory(com.novax.app.player.NvShiftedSubtitleParserFactory())

        /**
         * 读侧僵死回调（2026-09-15 播放卡死修复）：运行时 side-load 字幕 / 重建引擎后
         * 重新打开的流同样要挂上僵死自愈，否则只有起播那条流受保护。
         * 注意：必须在使用时动态读 [NvStallRecovery.handler]——该 handler 由 PlayerActivity
         * 起播管线注入，本工厂构造在它之前（进程冷启动后第一次播放必然为 null），
         * 字段初始化时快照会让首会话所有 side-load 流的自愈永久失效。
         */
        private val onStalled: (() -> Unit)?
            get() = com.novax.app.player.NvStallRecovery.handler

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val uri: Uri? = mediaItem.localConfiguration?.uri
            if (uri == null || uri.scheme != NvUri.SCHEME) {
                return delegate.createMediaSource(mediaItem)
            }
            val path = NvUri.pathFrom(uri, source, defaultPath = uri.path ?: "")
            return DefaultMediaSourceFactory(
                NvDataSourceFactory(source, path, repository, onStalled),
                nvExtractorsFactory(),
            )
                .setSubtitleParserFactory(com.novax.app.player.NvShiftedSubtitleParserFactory())
                .createMediaSource(mediaItem)
        }
    }

    /**
     * 便捷方法（IO 调用）：列视频同目录 → SubtitleMatcher 评分 → 供 [build]。
     * 等价于 repository.listSubtitles(source, videoPath)。
     */
    suspend fun listSubtitleCandidates(
        source: SourceEntity,
        videoPath: String,
        repository: NvSourceRepository,
    ): List<SubtitleCandidate> = repository.listSubtitles(source, videoPath)
}
