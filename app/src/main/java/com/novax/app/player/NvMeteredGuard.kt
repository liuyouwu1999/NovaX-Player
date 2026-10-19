package com.novax.app.player

import com.novax.app.data.db.SourceType

/**
 * 流量播放确认（2026-09-28 用户需求，配合封面抽帧计量网络闸门）：
 * 「只有 Wi-Fi（非计量网络）才能不经确认直接播放视频」——走网络的目标在计量网络
 * （蜂窝/热点）下必须先经用户确认才起播。
 * 判定纯函数无 Android 依赖（JVM 单测）；isMetered 由调用方注入
 * （ConnectivityManager.isActiveNetworkMetered），保证可测。
 * 2026-10-11 三档化：新增会话确认标志（本 object 唯一可变状态，仍不新增 Android
 * 依赖）与档位裁决纯函数；「仅提醒一次」的会话口径见 spec §3。
 */
object NvMeteredGuard {

    /**
     * 播放目标是否走网络：显式源按类型（LOCAL 不走）；无源的裸 URL 按 scheme——
     * file:/content: 是本机文件（外部「打开方式」入口），http(s)/ftp 恒走网络；
     * 其余形态（本地绝对路径等）按本地处理，宁可不弹不可误弹。
     * scheme 大小写不敏感（URI 规范）：先归一化 scheme 再比对，
     * 防止外部 VIEW 传 HTTP:// 绕过计量确认（2026-09-28 审计 P2-5）。
     */
    fun isRemoteTarget(url: String, sourceType: SourceType?): Boolean = when {
        sourceType != null -> sourceType != SourceType.LOCAL
        else -> {
            // scheme 大小写不敏感（URI 规范）：手工取冒号前段小写比对，防外部 VIEW 传
            // HTTP:// 绕过计量确认（2026-09-28 审计 P2-5）
            val colon = url.indexOf(':')
            val scheme = if (colon > 0) url.substring(0, colon).lowercase() else ""
            when (scheme) {
                "content", "file" -> false
                "http", "https", "ftp" -> true
                else -> false
            }
        }
    }

    /** 需要流量确认 = 目标走网络 且 当前网络计量 */
    fun needsDataConfirmation(url: String, sourceType: SourceType?, isMetered: Boolean): Boolean =
        isRemoteTarget(url, sourceType) && isMetered

    /** 当前网络是否计量；连接服务不可得按 true（保守：宁弹确认，不放流量） */
    fun isMeteredNetwork(context: android.content.Context): Boolean =
        (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
            ?.isActiveNetworkMetered ?: true

    /**
     * 本会话内是否已确认过流量播放（2026-10-11 三档「仅提醒一次」，spec §3/§4.2）。
     * 进程级 volatile：进程死亡天然重置；NvApp/PlaybackService 在「前台 Activity 归零
     * 且服务引擎已释放」时调 [resetSession]。仅主线程生命周期回调触发，@Volatile 是
     * 跨线程可见性冗余保险。
     */
    @Volatile
    var sessionConfirmed: Boolean = false
        private set

    /** 弹窗「继续播放」确认后置位（三个入口的确认回调共用；取消分支不得调用） */
    fun markSessionConfirmed() { sessionConfirmed = true }

    /** 会话终结（前台归零且引擎已释放）时由 NvApp/PlaybackService 调用 */
    fun resetSession() { sessionConfirmed = false }

    /**
     * 档位裁决（纯函数，三处判定入口共用；本地目标/非计量网络的前置豁免仍在调用方）：
     * every 恒需确认（2026-09-28 原行为），never 恒免，
     * session_once 随 [sessionConfirmed] 标志。spec §4.2/§4.3。
     */
    fun needsConfirmation(mode: MeteredRemindMode, sessionConfirmed: Boolean): Boolean = when (mode) {
        MeteredRemindMode.NEVER -> false
        MeteredRemindMode.EVERY -> true
        MeteredRemindMode.SESSION_ONCE -> !sessionConfirmed
    }
}
