package com.novax.app.player

/**
 * RMVB 兜底播放页的 libVLC 参数构建 + 起播/假终判据（2026-09-20 流畅度专项、
 * 2026-10-12 片尾假终治理，纯 JVM 函数供单测锁定）。
 *
 * 对齐官方 VLC App（vlc-android VLCOptions.libOptions）的研究结论：
 * - 旧版全局 `--avcodec-skip-frame=1` 是假优化：VLC 侧 1=AVDISCARD_DEFAULT≈不跳；
 *   「跳非参考帧」是 2，官方仅作可选性能模式（默认关）——改为设置「RMVB 性能模式」。
 * - 官方默认携带 `--avcodec-skiploopfilter N` 自适应跳环路滤波（好设备 1 / 弱机 3 /
 *   armv6·mips 4），RMVB/WMV 软解省 CPU 的正路；FFmpeg RV40/VC-1 是否 honoring 该项
 *   未查证（无负作用），真机收益由 NvPerf 吞吐/卡顿诊断日志取证。
 * - `--audio-resampler=soxr`：官方同款重采样器；模块缺失时 VLC 忽略该选项，无害。
 * - `--audio-time-stretch`：倍速不变调（与主播放器 pitch 固定 1f 同语义），官方默认开。
 *
 * 设备规格入参全部取基本类型（不引 libvlc/Android 类），判据与官方 getDeblocking 一致
 * （VLCOptions.kt:267-291，含 bogoMIPS 兜底分支）；specs 为 null 时按弱机 3 兜底。
 */
internal fun buildLibVlcOptions(
    networkCachingMs: Int,
    perfFrameSkip: Boolean,
    /** armv6 无 v7 或 MIPS（官方判据：这类机器直接跳全部环路滤波） */
    mipsLike: Boolean,
    /** CPU 最高主频 MHz（MachineSpecs.frequency；读不到给 0） */
    freqMhz: Int,
    /** bogoMIPS（官方兜底判据：frequency 缺失时用它；读不到给 0） */
    bogoMips: Int,
    /** 核心数（读不到给 1，永不为 >2 → 走 3 弱机档，不误跳） */
    processors: Int,
): List<String> {
    val options = mutableListOf(
        // 对齐官方 VLC4 分支 VLCOptions.libOptions 的音频参数（2026-09-21 用户实测教训：
        // 精简掉后 2x 明显劣化——VLC4 内核里时间拉伸默认值未知/偏保守，显式传才稳）：
        // - --audio-time-stretch：scaletempo 变速不变调（官方 values-v19 默认 true 且显式传）
        // - --audio-resampler=soxr：官方 VLC4 分支同款重采样器
        "--audio-time-stretch",
        "--audio-resampler=soxr",
        // 2026-09-21 倍速延迟根治（docs/RESEARCH-vlc4-rate-latency.md + web 调研子代理
        // 源码取证）：VLC4 Android 默认 aout 是 aaudio（capability 190 > audiotrack 180），
        // aaudio 模块不设缓冲上限、无条件入队，变速时旧速率残留 600-765ms+
        //（"deferring start" 日志），是长按倍速进出 1~3s 延迟的主体。audiotrack 模块的
        // 写线程被 i_max_audiotrack_samples（getBufferSizeInFrames ≈ min*2 ≈ 几十 ms）
        // 硬顶住，残留量级与 VLC3 时代相同 → 强制 audiotrack，裸 setRate 即接近
        // VLC3 的即时体感。
        // 2026-11-01 v1.0.0.14：此 libVLC 级 --aout 已被 NvVlcEngine.start 的 mp 级
        // setAudioOutput("opensles") 恒遮蔽（VLC 变量继承链 mp 更近；审核 A 项已做字节码
        // +VLC core 源码取证）——保留作 setAudioOutput 失败时的兜底与倍速调优文档锚点，勿删。
        "--aout=audiotrack",
        "--network-caching=$networkCachingMs",
    )
    val skipLoopFilter = when {
        mipsLike -> 4
        (freqMhz >= 1_200 || bogoMips >= 1_200) && processors > 2 -> 1
        else -> 3
    }
    options += "--avcodec-skiploopfilter"
    options += skipLoopFilter.toString()
    if (perfFrameSkip) {
        // 官方 KEY_ENABLE_FRAME_SKIP 同值：2 = 跳非参考帧 / 跳 IDCT（轻微画质代价）
        options += "--avcodec-skip-frame"
        options += "2"
        options += "--avcodec-skip-idct"
        options += "2"
    }
    return options
}

/**
 * 画面比例档（[com.novax.app.player.RmvbPlayerActivity] aspectScales 下标）→
 * 双指缩放夹紧用的 AspectMode 口径。libVLC 的 SURFACE_FIT_SCREEN 官方语义是
 * 「铺满屏幕（短轴溢出裁剪）」，夹紧按 FILL（内容足迹=全屏）处理最接近。
 * 纯函数可测；用下标而非 MediaPlayer.ScaleType 入参，避免 JVM 单测加载 libvlc 类。
 */
internal fun aspectIndexToZoomMode(index: Int): AspectMode = when (index) {
    2 -> AspectMode.FILL          // SURFACE_FILL
    3 -> AspectMode.R16_9         // SURFACE_16_9
    4 -> AspectMode.R4_3          // SURFACE_4_3
    1 -> AspectMode.FILL          // SURFACE_FIT_SCREEN（铺满语义近似）
    else -> AspectMode.FIT        // 0 = SURFACE_BEST_FIT（保比例 letterbox）
}

/**
 * AspectMode → aspectScales 下标（spec B-4 / 2026-11-09 审计 A4）：跨会话恢复画面比例的
 * 反向映射，与 [aspectIndexToZoomMode] 在 0/2/3/4 四档互逆。唯一不对合点=下标 1
 * （SURFACE_FIT_SCREEN 映射到 FILL 后无法区分 FIT_SCREEN/FILL，恢复取 SURFACE_FILL=2），
 * 单测钉死。FIT_SCREEN 档只在用户本会话显式轮换时可达，跨会话恢复不会落到该档。
 */
internal fun aspectModeToIndex(mode: AspectMode): Int = when (mode) {
    AspectMode.FILL -> 2          // SURFACE_FILL
    AspectMode.R16_9 -> 3         // SURFACE_16_9
    AspectMode.R4_3 -> 4          // SURFACE_4_3
    else -> 0                     // AspectMode.FIT = SURFACE_BEST_FIT（保比例 letterbox）
}

/**
 * ms → libVLC delay 参数换算（spec B-6 / 2026-11-09 审计 A12）。libvlc eap29 绑定实测
 * setAudioDelay(long)/setSpuDelay(long)，参数为微秒族；符号与主播放器同向（正=延后，
 * 参考 NvAvOffsetAudioProcessor 负=音频提前）。若真机实测单位非微秒，只改本函数一处。
 */
internal fun delayMsToVlc(delayMs: Long): Long = delayMs * 1000L

/**
 * 静态片尾裁决（2026-10-12 假终治理 spec §4.1）：续播位是否落在 libvlc 实测片尾区间。
 * [reportedLenMs] 必须与 seek 目标同一坐标系（libvlc 运行时 length），不用库内 metadata——
 * RM 头标时长虚标是本治理的根因。len≤0（长度未知）或 pos≤0（无续播位）一律 false 不裁决。
 * 阈值与共用件 EpisodeMediaKit.episodeResumePositionMs 的距尾闸门同族（10s）。
 * 同一函数两处复用：seek 前（静态层）、EndReached 时（守卫静态复核，lenAtEnd 实时值）。
 */
internal fun isRmvbTailZoneResume(startPositionMs: Long, reportedLenMs: Long, tailZoneMs: Long = 10_000L): Boolean {
    if (reportedLenMs <= 0L) return false
    if (startPositionMs <= 0L) return false
    return reportedLenMs - startPositionMs <= tailZoneMs
}

/**
 * 假终判别（spec §4.1）：续播 seek 后到来的 nearEnd EndReached 是否为死区假终。
 * 短路顺序即安全边界：
 * ① 静态复核——EndReached 时刻实时 [lenAtEndMs] 距 [seekTargetMs] ≤[tailZoneMs] → 真终
 *    放行（杀掉 Playing 时 len 未就绪窗口里「真播最后 ≤10s」的假阳性）；lenAtEndMs≤0 跳过
 *    （nearEnd 闸已要求 len>0，此分支防御性保留）；
 * ② 强形态——[advancedBeyondTarget]=false（seek 后从未有 TimeChanged 超过目标）→ 无论
 *    [wallSinceSeekMs] 判假终（远程代理拉尾延迟不可控，墙钟窗会过期）；
 * ③ 弱形态——墙钟 <[windowMs] 且净推进（[maxTimeSeenMs]−target）<[minRealAdvanceMs]。
 * [maxTimeSeenMs] 是 seek 后观测到的最大 media time（初值=target，TimeChanged 单调取 max），
 * 不是推进量。推进按媒体时间计与倍速无关：复核放行所有距 len ≤10s 的目标后，真播完推进
 * ≥10s ≥ minRealAdvanceMs，弱形态无误判面。
 */
internal fun isRmvbFakeTailEnd(
    advancedBeyondTarget: Boolean,
    wallSinceSeekMs: Long,
    maxTimeSeenMs: Long,
    seekTargetMs: Long,
    lenAtEndMs: Long,
    windowMs: Long = 8_000L,
    minRealAdvanceMs: Long = 5_000L,
    tailZoneMs: Long = 10_000L,
): Boolean {
    if (lenAtEndMs > 0L && lenAtEndMs - seekTargetMs <= tailZoneMs) return false
    if (!advancedBeyondTarget) return true
    return wallSinceSeekMs < windowMs && maxTimeSeenMs - seekTargetMs < minRealAdvanceMs
}
