package com.novax.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.novax.app.player.NvMeteredGuard
import com.novax.app.player.PlaybackService

/**
 * 应用入口（2026-10-11 流量提醒三档「仅提醒一次」，spec §4.5）：唯一职责是给流量确认
 * 会话提供终结信号——前台 Activity 计数归零 且 服务引擎已释放（activeEngine==null）时
 * 重置会话标志，下次进入首次计费起播重新弹一次。
 * 计数只钩 Started/Stopped（onCreate 内 finish() 等不经 Started 的路径天然配平）；
 * 两个终结钩子（Activity 全 stop / 服务 onDestroy）都收敛到 [maybeEndMeteredSession]，
 * 防止终结条件漂移。仅主线程生命周期回调触发。
 */
class NvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // E2-2（发版审查）：仓库 Context 注入前移到 Application——深链（PlayerActivity VIEW /
        // PlaybackService 媒体按键）冷起不经 MainActivity，此前 SftpClient 拿不到 known-hosts
        // 会回落 accept-all（任意主机密钥放行，NAS 口令可被 MITM 截获）。
        com.novax.app.data.NvGraph.attach(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foregroundActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount--
                maybeEndMeteredSession(PlaybackService.activeEngine != null)
            }
            // 其余回调不关心：本工具链 android.jar 将五个核心回调声明为 abstract，须显式
            // 空实现（= Unit）；若未来 compileSdk 使其变为 default，这些空覆写成为无害冗余
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        /** 前台 Activity 计数（服务侧终结判定也要读；仅主线程写，@Volatile 防跨线程读旧值） */
        @Volatile
        var foregroundActivityCount = 0
            private set

        /**
         * 会话终结统一判定（双钩子收敛于此）：前台 Activity 归零 且 引擎不在场 →
         * 重置流量确认会话（spec §3：引擎在场（播放中/暂停/播完未释放）即同场）。
         * [playbackActive] 由调用方按各自时点注入：onStop 钩子传 activeEngine != null，
         * 服务 onDestroy 传 false（引擎已清）。
         */
        fun maybeEndMeteredSession(playbackActive: Boolean) {
            if (foregroundActivityCount <= 0 && !playbackActive) NvMeteredGuard.resetSession()
        }
    }
}
