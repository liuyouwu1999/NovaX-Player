package com.novax.app.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * P1-6 后台播放 · 播放器控制面的稳定入口（适配层）。
 *
 * PlayerScreen（手势/控制层/面板）只依赖本接口，不感知播放内核住在哪：
 * - 旧路（无服务）：`player` = PlaybackEngine 内建 ExoPlayer（行为与 P1-6 之前完全一致）；
 * - 新路（后台播放）：`player` = MediaController（media3-session 1.8 支持把 TextureView 的
 *   Surface 经 binder 转发给 service 内的 session player 渲染），智能 seek / 字幕 side-load /
 *   错误接线仍转发到 [PlaybackEngine]（同进程直调）。
 *
 * 方法面 = PlayerScreen 实际消费的 PlaybackEngine API 子集；实现零业务逻辑、纯转发。
 */
interface PlayerHandle {

    /** 渲染与状态观察用的 Player（ExoPlayer 或 MediaController） */
    val player: Player

    /** C10「精确跳转」：seek 分级实时切换 EXACT/默认（PlayerScreen 收集设置流写入） */
    var exactSeekMode: Boolean

    /** 当前 side-load 字幕轨 Format.id（null = 未加载外挂字幕） */
    val activeSubtitleId: String?

    /**
     * 底层引擎是否已释放（2026-09-15 修复「滑动进度条进度条不动」）。
     * 自适应升档/换片会重建引擎并释放旧的，而 Compose 那次手势可能还抓着旧引用；
     * 对已释放引擎 seek 会抛 `Handler on a dead thread` 并被静默丢弃 → 进度条完全不动。
     * UI 侧据此改投当前活跃引擎（见 PlayerScreen.seekViaEngine）。
     */
    val isReleased: Boolean

    /** 引擎首次源 IO 错误已就地静默重试后的轻提示回调（主线程） */
    var onSourceIoRetry: ((positionMs: Long) -> Unit)?

    /** 自愈兜不住的最终错误（PlayerScreen 据此显示错误态；主线程回调） */
    var onFatalError: ((PlaybackException) -> Unit)?

    /** 取走挂起的未消费最终错误（错误接线注册晚于引擎创建时的空窗补投递） */
    fun consumePendingFatalError(): PlaybackException?

    /**
     * 渲染器僵死自愈（2026-09-15 用户实测「画面卡死但 position 照常推进」）：
     * 位置推进 ≠ 帧在渲染——真机上出现过「position 每 500ms 稳定 +500ms、但画面永远停在
     * 同一镜头」的僵死态。此时 flush 渲染器 + 重新 prepare 是唯一可靠的唤醒手段
     * （U 层可零成本挂载，由 PlayerScreen 的帧渲染看门狗触发）。
     */
    fun forceRePrepare() = Unit

    /**
     * 帧渲染回调（2026-09-15 修复「画面卡死但 position 照常推进」）：
     * 位置推进 ≠ 帧在渲染——必须用真实出帧事件判定僵死。每次 seek/prepare 后的首帧都会触发。
     */
    var onFrameRendered: (() -> Unit)?

    /** C8 §1 分级 seek（「松手才 seek」最终入口） */
    fun smartSeek(targetMs: Long)

    /** 双击 ±10s / 手势相对 seek */
    fun seekBy(offsetMs: Long)

    /** 变速播放；pitch 固定 1f 即「变速不变调」 */
    fun setPlaybackSpeed(speed: Float, pitch: Float)

    /** C6 §3.1 运行时 side-load 同目录字幕（IO 操作，勿主线程同步调用） */
    suspend fun loadSubtitle(candidate: SubtitleCandidate): String?

    /** 移除 side-load 字幕，回到纯内嵌轨（进度保留） */
    fun clearSubtitle()

    /** 2026-09-15 D6 音量增益（毫贝尔；0 = 关闭）。引擎侧挂真 ExoPlayer 的音频会话 */
    fun setVolumeBoostGainMb(gainMb: Int) = Unit
}

/** 直连引擎适配器：`player` 即引擎内建 ExoPlayer（P1-6 之前的等价路径，服务不可用时兜底） */
class PlaybackEngineHandle(
    private val engine: PlaybackEngine,
    override val player: Player,
) : PlayerHandle {

    constructor(engine: PlaybackEngine) : this(engine, engine.player)

    override var exactSeekMode: Boolean
        get() = engine.exactSeekMode
        set(value) { engine.exactSeekMode = value }

    override val activeSubtitleId: String? get() = engine.activeSubtitleId

    override val isReleased: Boolean get() = engine.isReleased

    override var onSourceIoRetry: ((positionMs: Long) -> Unit)?
        get() = engine.onSourceIoRetry
        set(value) { engine.onSourceIoRetry = value }

    override var onFatalError: ((PlaybackException) -> Unit)?
        get() = engine.onFatalError
        set(value) { engine.onFatalError = value }

    override fun consumePendingFatalError(): PlaybackException? = engine.consumePendingFatalError()

    override var onFrameRendered: (() -> Unit)?
        get() = engine.onFrameRendered
        set(value) { engine.onFrameRendered = value }

    override fun forceRePrepare() = engine.forceRePrepare()

    override fun smartSeek(targetMs: Long) = engine.smartSeek(targetMs)

    override fun seekBy(offsetMs: Long) = engine.seekBy(offsetMs)

    override fun setPlaybackSpeed(speed: Float, pitch: Float) = engine.setPlaybackSpeed(speed, pitch)

    override suspend fun loadSubtitle(candidate: SubtitleCandidate): String? =
        engine.loadSubtitle(candidate)

    override fun clearSubtitle() = engine.clearSubtitle()

    override fun setVolumeBoostGainMb(gainMb: Int) = engine.setVolumeBoostGainMb(gainMb)
}
