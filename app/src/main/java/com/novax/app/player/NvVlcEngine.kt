package com.novax.app.player

import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * RMVB 软解引擎对象持有器（2026-09-28 审计 A 第②刀，保守边界）。
 *
 * 拥有 LibVLC / MediaPlayer / content fd 三样对象的生命周期与媒体构造——与
 * PlaybackEngine（Exo 内核）平行的「对象所有权」边界。刻意**不**承载：
 * - 事件裁决（onVlcEvent 的状态 web 留在宿主 Activity，经构造回调接收事件）；
 * - NvRmvbProxy 编排（startFor + isActive 复查的取消语义是上轮审计修复，留在宿主）。
 * 这两块的风险大于抽象收益，本类只收敛对象创建/参数装配/媒体构造/释放顺序。
 *
 * 宿主以 `vlcPlayer get() = engine.mediaPlayer` 委托保持全部既有引用形态。
 */
class NvVlcEngine(
    private val appContext: android.content.Context,
    private val contentResolver: android.content.ContentResolver,
    private val onEvent: (MediaPlayer.Event) -> Unit,
) {
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var contentPfd: android.os.ParcelFileDescriptor? = null

    /** 活跃播放器（未启动 = null） */
    val mediaPlayer: MediaPlayer? get() = player

    val isStarted: Boolean get() = libVlc != null && player != null

    /**
     * 起引擎（幂等）：选项由调用方装配（网络缓存档按是否有代理、性能模式跳帧、
     * 弱机自适应降块——见 [buildLibVlcOptions] 注释），本类只负责 LibVLC/MediaPlayer
     * 构造与事件监听接线。
     *
     * [aout]：音频输出模块名（如 "opensles"/"audiotrack"），null = libVLC 默认。
     * 2026-11-01 v1.0.0.14 起 fallback 页恒传 "opensles"：audiotrack 模块在部分文件
     * （24kHz MP3-in-AVI 实测，真机 vivo + 无头模拟器双复现）写入时触发
     * `AudioTrack_WriteByteArrayV23: assertion "jbuffer.size > 0"` **SIGABRT 直接杀死
     * 进程**（用户表现=打开即闪退）；opensles 是 libvlc-all 自带的成熟替代输出，
     * 写入路径完全不同，不受该断言影响。
     */
    fun start(options: ArrayList<String>, aout: String? = null) {
        if (libVlc != null) return
        libVlc = LibVLC(appContext, options)
        player = MediaPlayer(libVlc).also { mp ->
            aout?.let {
                // W 级留痕（审核 P2-2）：setAudioOutput 失败会静默回退默认 aout——若哪台设备
                // 上 opensles 模块缺失导致回退 audiotrack，闪退会复活，此处是唯一排查线索
                //（vivo 只认 W 级日志）。
                android.util.Log.w("NvVlc", "aout=$it")
                mp.setAudioOutput(it)
            }
            mp.setEventListener(onEvent)
        }
    }

    /**
     * 按 URI 构造媒体（2026-09-18 真机修复 + 2026-09-28 外部入口两笔修复的承载处）：
     * - Media(libVlc, String) 把字符串按**本地路径**解释——代理 http URL 必须按 Uri 构造；
     * - VLC 核心不认 content MRL（Media(Uri) 只是 encodeVLCUri→nativeNewFromLocation），
     *   须自开文件描述符走 Media(fd)（VLC 官方 App 同款）；fd 归本类持有、release 时关闭。
     */
    fun openMedia(uri: String): Media {
        val lib = requireNotNull(libVlc) { "NvVlcEngine 未启动（先 start）" }
        val media = if (uri.startsWith("content://")) {
            contentPfd?.let { runCatching { it.close() } }
            val pfd = contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")
                ?: throw java.io.IOException("无法打开内容 URI: $uri")
            contentPfd = pfd
            Media(lib, pfd.fileDescriptor)
        } else {
            // file:// 按 URI 解析（File() 会把它当相对路径产生双前缀 MRL）
            val mediaUri = if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("file://")) {
                android.net.Uri.parse(uri)
            } else {
                android.net.Uri.fromFile(java.io.File(uri))
            }
            Media(lib, mediaUri)
        }
        return media.apply { setHWDecoderEnabled(true, false) }
    }

    /** 释放（顺序：player → libVLC → content fd；surface detach 由宿主先行）。幂等。 */
    fun release() {
        runCatching { player?.release() }
        runCatching { libVlc?.release() }
        runCatching { contentPfd?.close() }
        contentPfd = null
        player = null
        libVlc = null
    }
}
