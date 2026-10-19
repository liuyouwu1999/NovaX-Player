package com.novax.app.data.thumb

import android.media.MediaDataSource
import com.novax.app.data.NvSourceRepository
import com.novax.app.data.db.SourceEntity

/**
 * SMB/WebDAV/本地 随机读包装（C12 §三.2；2026-09-15 常驻流改造）：
 * 给 MediaMetadataRetriever 的定位读数据源。retriever 对容器做十几次范围读（moov 探测/
 * 索引解析/定帧），旧实现每次 readAt 独立开流读完即关 → SMB 块流整块（4MB）读放大最高 64×
 * + 每读一次 CREATE/CLOSE 一条会话。新实现持 [SequentialRandomReadPipe] 常驻一条流按位置
 * 连续服务：顺序读零重开，回跳才重开；块大小经 [openStream] 注入（抽帧路径 256KB）。
 *
 * size 契约：[sizeBytes] 必须 ≥0 才允许构造——[Thumbnailer] 在 size ≤0 时不进入取帧
 * （绝不携带 -1"未知"哨兵进来）。
 *
 * ⚠ API 等级：setDataSource(MediaDataSource) 需要 API 29+（minSdk=26）；
 * SDK<29 的降级在 [Thumbnailer.extractCore] 统一处理（本地缓存文件中转：文件头落盘
 * cacheDir 后 setDataSource(路径)，2026-09-29 phase2 落地，不经本类）。
 */
class NvMediaDataSource internal constructor(
    private val sizeBytes: Long,            // 扫描已入库的真实大小
    /**
     * 累计读字节预算（0=不限）：抽帧流量保险丝，超限熔断落 FAILED 占位（2026-09-28 偷跑流量事故）。
     * 无默认值（2026-09-28 T1 编译期锁）：删掉调用点的预算参数 = 编译失败，防 32MB 接线
     * 静默回归（删一行、487 测试照样全绿的事故形态）。
     */
    private val maxBytes: Long,
    private val openStream: (Long) -> java.io.InputStream,
) : MediaDataSource() {

    private val pipe = SequentialRandomReadPipe(openStream, maxBytes = maxBytes)

    /** §1b 观测透出:本数据源累计跳过字节(extractCore 日志消费) */
    internal val pipeSkippedBytes: Long get() = pipe.totalSkippedBytes

    /**
     * 便捷构造（repo 直连）。⚠ 当前零调用点；注意其预算固定 0L=不限——任何新调用方
     * 都应改走主构造并显式传入 maxBytes（2026-09-28 T1 编译期锁语义）。
     */
    constructor(
        repo: NvSourceRepository,
        source: SourceEntity,
        path: String,
        sizeBytes: Long,
    ) : this(
        sizeBytes,
        0L,
        openStream = { offset ->
            // thumb lane：与播放连接通道隔离（驱逐互不波及，2026-09-15）
            repo.openStreamBlocking(source, path, offset, lane = com.novax.app.data.smb.SmbClient.LANE_THUMB)
        },
    )

    override fun readAt(offset: Long, buffer: ByteArray, offsetInBuffer: Int, length: Int): Int {
        // EOF 守卫仅在 size 已知时生效；sizeBytes<0 绝不伪造 EOF——否则 offset=0 即命中，
        // readAt 恒返 -1，retriever 读不到任何字节、抽帧 100% 失败（-1 哨兵 bug，C12 §三.2）
        if (sizeBytes >= 0 && offset >= sizeBytes) return -1
        return pipe.readAt(offset, buffer, offsetInBuffer, length)
    }

    override fun getSize(): Long = sizeBytes

    /** 释放常驻流（extractOne finally 必须调用；幂等） */
    override fun close() {
        pipe.close()
    }

    companion object {
        /** 读满 [length] 字节或遇 EOF；读不到任何字节返回 -1（EOF 信号，retriever 依赖它） */
        internal fun readFully(ins: java.io.InputStream, buffer: ByteArray, offsetInBuffer: Int, length: Int): Int {
            var total = 0
            while (total < length) {
                val n = ins.read(buffer, offsetInBuffer + total, length - total)
                if (n <= 0) break // n==0 防自旋（2026-09-16 审计同型位点）
                total += n
            }
            return if (total == 0) -1 else total
        }
    }
}
