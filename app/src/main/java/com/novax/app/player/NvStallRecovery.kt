package com.novax.app.player

import android.util.Log

/**
 * SMB 读侧僵死自愈的进程内交接点（2026-09-15 用户实测「画面卡住 + 进度条在走」修复）。
 *
 * 背景（真机现场取证）：SMB 的 `DiskShare` 被群晖服务端回收后，播放流挂在死句柄上，
 * 重建后依然 `bytes=0 netRtt=0 served=0`（一个字节都读不到）→ 解码器无数据不出帧 →
 * **画面永久冻住**；而播放器仍报 `playing=true / buffering=false`、position 照常推进、
 * 音频还有残量在放，连 seek 都救不回来。
 * 播放器状态完全不可信，唯一可信信号是「读侧有没有拿到字节」。
 *
 * 交接原因：DataSource 由 `data/playback`（NvDataSourceFactory / NvMediaSourceBuilder）
 * 创建，而重新 prepare 的能力在 `player` 包的 PlaybackEngine 上；两者不能直接互相引用，
 * 故用这个轻量单例传递（同 PlayerPlaylistHolder 的进程内交接模式）。
 */
object NvStallRecovery {

    /**
     * 画面出帧证据（2026-09-15 用户录屏取证后固化）：
     *
     * `onRenderedFirstFrame` **只在 prepare/seek 后触发一次，不是每帧信号**，因此它不能当
     * 「是否在出帧」的判据（此前据此做的看门狗在正常播放时 sinceFrame 涨到 53s → 连续误报，
     * 已撤除）。但它**可以当「自上次 seek/prepare 以来是否至少出过一帧」的证据**——
     * 只要把它按「seek 序号」作废即可：一次 seek 后迟迟不出现新帧 ⇒ 这次 seek 真的没出画。
     *
     * 用法：seek/prepare 前 [noteSeek]（帧计数作废），出帧回调里 [noteFrame]（记录帧号），
     * 心跳里用 [framesSinceSeek] 判断「本次 seek 之后拿到了几帧」。0 且位置在推进 = 画面没出。
     */
    @Volatile
    var frameCount: Long = 0L
        private set

    /** 最近一次 seek/prepare 时的帧号快照 */
    @Volatile
    var frameCountAtSeek: Long = 0L
        private set

    /** 本次 seek 后已渲染的帧数（0 = 本次 seek 一帧都没出过） */
    val framesSinceSeek: Long get() = frameCount - frameCountAtSeek

    @Volatile
    var lastFrameAtMs: Long = 0L
        private set

    /** 视频尺寸变更次数（画面缩小/比例跳变的证据；UI 侧按尺寸变化重绑 surface） */
    @Volatile
    var videoSizeChanges: Int = 0
        private set

    @Volatile
    var lastVideoWidth: Int = 0
        private set

    @Volatile
    var lastVideoHeight: Int = 0
        private set

    /** 出帧回调（每 prepare/seek 首帧）统一入口 */
    fun noteFrame(nowMs: Long) {
        frameCount++
        lastFrameAtMs = nowMs
    }

    /** seek/prepare 前调用：本次 seek 的帧计数从此起算 */
    fun noteSeek() {
        frameCountAtSeek = frameCount
    }

    /** 视频尺寸变更统一入口；返回 true 表示尺寸与上次不同（调用方可据此重绑 surface） */
    fun noteVideoSize(width: Int, height: Int): Boolean {
        val changed = width != lastVideoWidth || height != lastVideoHeight
        if (changed) {
            videoSizeChanges++
            lastVideoWidth = width
            lastVideoHeight = height
        }
        return changed
    }


    /** 由 PlayerActivity 在起播时注入；null = 没有活跃引擎（此时无需自愈） */
    @Volatile
    var handler: (() -> Unit)? = null

    /** 供读侧直接调用（内部做了活跃引擎判空与异常兜底） */
    fun recover(reason: String) {
        val h = handler ?: return
        Log.w("NvPerf", "stall recovery triggered: $reason")
        runCatching { h() }.onFailure { Log.w("NvPerf", "stall recovery failed", it) }
    }

    // ---------------- 画面侧自愈（2026-09-15 真机复现「画面没了」后新增） ----------------

    /**
     * 「在播但没出画」自愈次数。这是**画面侧**的计数器（与上面的 SMB 读侧 `recover` 分开），
     * 供 UI 层观察：每自愈一次就把 surface 重挂一次（见 PlayerScreen 的 recoveryTick）。
     */
    @Volatile
    var noPictureRecoverCount: Int = 0
        private set

    /**
     * 画面侧自愈回调（PlayerScreen 注册）：用于在自愈时**重挂 surface**。
     *
     * 为什么单靠 `forceRePrepare()` 不够（真机取证的完整因果链）：
     *   自适应切档（4K→1080P）会销毁旧解码器、新建一个解码器；旧解码器的 bufferqueue
     *   在这一刻断开（平台日志 `C2MtkBufferManager: bufferqueue may disconnected`）。
     *   新解码器拿到的 output surface 是**旧 Surface 的 consumer**，于是
     *   `afterRender` 一直 decode-only：画面彻底没有，而 position/audio 完全正常
     *   （用户原话「进度条在走、声音在走、画面没了」）。
     *   `setOutputSurface()`（= 重挂 surface）能让解码器重新拿到有效 bufferqueue，
     *   这是与「重新 prepare」互补的另一半，必须一起做。
     */
    @Volatile
    var onNoPictureRecover: (() -> Unit)? = null

    /** 画面侧自愈统一入口：先记数，再通知 UI 重挂 surface；返回本次是否真的执行了自愈 */
    fun notifyNoPictureRecover(reason: String): Boolean {
        noPictureRecoverCount++
        Log.w("NvPerf", "no-picture recovery #$noPictureRecoverCount: $reason")
        val h = onNoPictureRecover ?: return false
        runCatching { h() }.onFailure { Log.w("NvPerf", "no-picture surface rebind failed", it) }
        return true
    }
}
