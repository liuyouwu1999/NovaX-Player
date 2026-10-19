@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.novax.app.data.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.smb.SmbReadMath
import java.io.IOException
import java.io.InputStream

/**
 * NovaX 统一 NAS DataSource（C8 §3 连接复用标准）：
 * SMB 走 SmbClient 定址随机读、WebDAV 走 OkHttp Range 请求、本地走 RandomAccessFile，
 * 由 [NvSourceRepository] 按 [SourceEntity.type] 路由。
 *
 * Seek 模型（ExoPlayer 约定）：seek 不复用已打开的流，而是 close 后以新 position 的
 * DataSpec 重新 open。本类保证：
 * - open 只在仓库层的连接池上开一个文件句柄/Range 流（SMB session、HTTP keep-alive 均复用，
 *   一次 seek = 1 次定址 READ/Range 请求，无握手 —— C8 §3.1/§3.2）；
 * - position/length 严格按 DataSpec 语义处理：read 不越过 dataSpec.length，
 *   length 未知时读至 EOF 返回 [C.RESULT_END_OF_INPUT]；
 * - close 释放句柄并把连接归还池（绝不关闭 session/连接池）。
 *
 * TransferListener：经 [BaseDataSource] 注册分发（transferInitializing/Started/bytesTransferred/Ended）。
 */
class NvDataSource(
    private val source: SourceEntity,
    private val repository: NvSourceRepository,
    /** DataSpec.uri 非 novax:// 时的回落路径（如从 MediaItem 直接携带的本地路径） */
    private val defaultPath: String,
    /**
     * 读侧僵死回调（2026-09-15 播放卡死修复）：SMB 连接链被服务端回收后读不到任何字节，
     * 由上层重建连接链并重新 prepare（真机现场：画面冻住 + 进度条在走 + seek 也救不回来）。
     */
    private val onStalled: (() -> Unit)? = null,
    /**
     * 文件总长提供者（2026-09-30 C8 §4.3 落地）：Factory 级 memo，起播一次网络往返后
     * 恒定；<=0 视为未知（长度未知时 open 回退 LENGTH_UNSET 旧行为）。
     */
    private val contentLengthProvider: () -> Long = { -1L },
) : BaseDataSource(true) {

    private var dataSpec: DataSpec? = null
    private var stream: InputStream? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    /**
     * 打开流。position 即读取起点（seek 场景 ExoPlayer 以新 position 重新 open）。
     * 返回剩余可读长度：dataSpec.length 已知时为其值；总长已知时返回 总长-position
     * （2026-09-30 C8 §4.3 落地——已知长度让 Extractor 建精确/降级 SeekMap，坏索引
     * MKV 的降级 seek 与 MP4 尾部 moov 都吃这个）；两者皆未知则 LENGTH_UNSET（读至 EOF）。
     */
    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        // 2026-10-03 字幕劫持修复：side-load 字幕挂载的是本地转码临时文件（file://），
        // 经 DefaultMediaSourceFactory(本工厂) 创建的字幕 MediaSource 同样走本 DataSource。
        // 旧逻辑对非 novax:// 一律回落 defaultPath（=视频路径）→ 字幕周期读到视频字节，
        // SubripParser 报 contentIsMalformed → 轨道被禁用（真机复现：字幕已选中但永不渲染）。
        // file:// 一律按本地文件读，绝不回落 NAS 路径（纵深防御：任何调用点误接也不劫持）。
        if (dataSpec.uri.scheme == "file") return openLocalFile(dataSpec)
        val path = NvUri.pathFrom(dataSpec.uri, source, defaultPath)
        val total = contentLengthProvider().takeIf { it > 0 }
        // 2026-09-16 审计 P2：非法位置按 IOException 抛——require 的 IllegalArgumentException
        // 会被 ExoPlayer 归为 unexpected error（文案兜底差且不上错误分类链路）
        if (!SmbReadMath.isValidPosition(dataSpec.position, contentLength = total ?: -1L)) {
            throw IOException("非法读取位置: ${dataSpec.position}")
        }
        // ExoPlayer 加载线程为阻塞模型：直接走阻塞 open（连接池复用在仓库层），
        // 一次 seek = 一次定址 READ / Range 请求，无重握手（C8 §3.1/§3.2）
        // 2026-09-15：播放走独立连接通道（play lane），浏览/抽帧的驱逐不再波及播放流
        val s = repository.openStreamBlocking(
            source,
            path,
            dataSpec.position,
            lane = com.novax.app.data.smb.SmbClient.LANE_PLAY,
            onStalled = onStalled,
        )
        stream = s
        opened = true
        transferStarted(dataSpec)
        bytesRemaining = resolveBytesRemaining(dataSpec.length, dataSpec.position, total ?: -1L)
        logOpen(path, total ?: -1L)
        return bytesRemaining
    }

    /** file:// 本地文件（side-load 字幕临时文件等）：RandomAccessFile 定位读，语义与 NAS 路一致 */
    @Throws(IOException::class)
    private fun openLocalFile(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path ?: throw IOException("file URI 无路径: ${dataSpec.uri}")
        val f = java.io.File(path)
        if (!f.isFile) throw IOException("本地文件不存在: $path")
        if (!SmbReadMath.isValidPosition(dataSpec.position, contentLength = f.length())) {
            throw IOException("非法读取位置: ${dataSpec.position}")
        }
        val raf = java.io.RandomAccessFile(f, "r")
        if (dataSpec.position > 0) raf.seek(dataSpec.position)
        // stream 关闭即关 channel → 关 raf，无需单独持有
        stream = java.nio.channels.Channels.newInputStream(raf.channel)
        opened = true
        transferStarted(dataSpec)
        bytesRemaining = resolveBytesRemaining(dataSpec.length, dataSpec.position, f.length())
        Log.d(TAG, "local file open pos=${dataSpec.position} total=${f.length()} path=$path")
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = bytesRemaining
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining > 0) minOf(remaining, length.toLong()).toInt() else length
        val s = stream ?: throw IOException("read 前未 open（NvDataSource 生命周期错误）")
        val n = s.read(target, offset, toRead)
        if (n == C.RESULT_END_OF_INPUT) {
            if (remaining > 0) throw IOException("流在已知长度前提前结束（remaining=$remaining）")
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining = if (remaining > 0) remaining - n else remaining
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        val s = stream
        stream = null
        runCatching { s?.close() }
            .onFailure { Log.w(TAG, "close stream failed", it) }
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }

    /** C8 §5.1 连接复用埋点：open 频率应与 seek 同量级、session 重建数应为 0 */
    private fun logOpen(path: String, total: Long) {
        when (source.type) {
            com.novax.app.data.db.SourceType.SMB ->
                Log.d(TAG, "smb open pos=${dataSpec?.position} total=$total path=$path ${repository.smb.stats}")
            else -> Log.d(TAG, "${source.type} open pos=${dataSpec?.position} total=$total path=$path")
        }
    }

    companion object {
        private const val TAG = "NvDataSource"

        /**
         * bytesRemaining 决策（纯函数，可单测）：spec.length 优先（ExoPlayer 定长片段）；
         * 总长已知且 position 未越界时返回剩余；否则 LENGTH_UNSET（读至 EOF，旧行为）。
         */
        internal fun resolveBytesRemaining(specLength: Long, position: Long, total: Long): Long = when {
            specLength != C.LENGTH_UNSET.toLong() -> specLength
            total > 0 && position in 0 until total -> total - position
            else -> C.LENGTH_UNSET.toLong()
        }
    }
}

/** [DataSource.Factory]：PlayerActivity/播放器构建入口使用 */
class NvDataSourceFactory(
    private val source: SourceEntity,
    private val path: String,
    private val repository: NvSourceRepository,
    /** 读侧僵死回调：透传到每次创建的 DataSource（见 NvDataSource.onStalled） */
    private val onStalled: (() -> Unit)? = null,
) : DataSource.Factory {

    /**
     * 文件总长 memo（2026-09-30 C8 §4.3）：Factory 级取一次——同一播放会话里 DataSource
     * 可能随 period 重建多次，memo 挂在 Factory 上保证整个会话只发一次元数据查询。
     * 2026-10-03 加固：失败不再永久记 -1——起播瞬间的 SMB 瞬断不该杀死整个会话的
     * 降级 seek（坏索引 MKV 长度未知 → Unseekable → 进度条跳回开头），改为失败后
     * 冷却期内直接返回 -1、冷却结束允许重试（成功则永久缓存）。
     */
    private val lengthMemo = ContentLengthMemo(query = { repository.fileSizeBlocking(source, path) })

    private val contentLength: () -> Long = lengthMemo::get

    /** 每次 create 新实例；连接池在 repository 层跨实例复用（同 source 同 session） */
    override fun createDataSource(): DataSource =
        NvDataSource(source, repository, path, onStalled, contentLength)
}

/**
 * 文件总长查询 memo（可单测）：成功永久缓存；失败记录时间戳、冷却期内返回 -1 不再打网络，
 * 冷却结束允许重试。加锁串行化避免 period 重建风暴下的并发重复查询。
 */
internal class ContentLengthMemo(
    private val query: () -> Long,
    private val clock: () -> Long = System::nanoTime,
    private val cooldownNanos: Long = 30_000_000_000L,
) {
    private val cached = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val failedAt = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

    @Synchronized
    fun get(): Long {
        cached.get().takeIf { it != Long.MIN_VALUE }?.let { return it }
        val now = clock()
        val lastFail = failedAt.get()
        if (lastFail != Long.MIN_VALUE && now - lastFail < cooldownNanos) return -1L
        val v = runCatching { query() }.getOrDefault(-1L)
        return if (v > 0) {
            cached.set(v)
            v
        } else {
            failedAt.set(now)
            -1L
        }
    }
}

/** 播放 URI 约定：novax://<sourceId>/<统一路径>；DataSource 据此还原真实 path */
object NvUri {
    const val SCHEME = "novax"

    /**
     * 构建 novax:// URI：路径段经 URI 编码（中文/空格 → %xx，保留 "/" 分隔符），
     * 消费方用 [parse] / [pathFrom]（Uri.path 自动解码）还原。
     */
    fun forSource(source: SourceEntity, path: String): Uri =
        Uri.parse("$SCHEME://${source.id}" + Uri.encode(com.novax.app.data.NvPaths.normalize(path), "/"))

    /** novax:// URI 字符串 → (sourceId, 源内路径)；非 novax 或缺 sourceId/path 返回 null */
    fun parse(uriString: String): Pair<Long, String>? {
        val u = Uri.parse(uriString)
        if (u.scheme != SCHEME) return null
        val id = u.host?.toLongOrNull() ?: return null
        val path = u.path?.takeIf { it.isNotEmpty() } ?: return null
        return id to path
    }

    /**
     * DataSpec.uri → 源内路径：novax:// 且 host 匹配 source.id 时取 uri.path，
     * 否则回落 defaultPath（如外部直接传 file:// 等）。
     */
    fun pathFrom(uri: Uri, source: SourceEntity, defaultPath: String): String =
        if (uri.scheme == SCHEME && uri.host == source.id.toString()) {
            uri.path?.takeIf { it.isNotEmpty() } ?: defaultPath
        } else {
            defaultPath
        }
}
