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
        // 隐私重锁中枢（隐私计划 Task 11）：先于生命周期回调装配；init 内异步冷启动补跑
        // pendingWipeIds（上次重锁清理中途被杀的欠账），接入点全走异步不阻塞启动。
        com.novax.app.data.privacy.NvPrivacyRelockOrchestrator.init(
            db = com.novax.app.data.db.MediaDb.get(this),
            prefs = com.novax.app.data.privacy.NvPrivacyPrefs(
                this,
                com.novax.app.data.privacy.NvPrivacyCrypto.keystoreCipher(),
            ),
            access = com.novax.app.data.privacy.NvPrivacyAccess.get(this),
            scope = kotlinx.coroutines.MainScope(),
        )
        // 隐私重锁停投屏（隐私计划 Task 15）：Task 11 留的注入点在此接线。Holder 未初始化
        //（本次进程从未打开投屏面板）时 stopAllAndClear 自身空操作，不必在此强拉 CastSessionHolder.init
        com.novax.app.data.privacy.NvPrivacyRelockOrchestrator.castStopHook =
            { com.novax.app.data.cast.CastSessionHolder.stopAllAndClear() }
        // 隐私计划 Task 17：jf: 规则的 path→itemId 解析器接线（无此接线 jf 目录级规则恒
        // fail-closed 整源临时锁）。resolver 只做进程内索引查表（跨源歧义→null 仍 fail-closed，
        // 见 JellyfinPathIndex.unambiguousIdOf），绝不发网络——判定位（isLocked/isIncognito/covers）
        // 是同步判定且调用方含主线程（服务起播/暂停回调/历史心跳），阻塞 HTTP（connect 10s/read
        // 30s 每跳）必 ANR。冷索引的解析在起播链 jellyfinItemIdOf 的 walk 先行完成并回填索引，
        // 到 reporter 判定/播放闸精判时点索引必热；真冷（重启直开）由 index 侧整源临时锁兜底。
        com.novax.app.data.privacy.NvPrivacyAccess.get(this).index.attachJellyfinResolver { path ->
            com.novax.app.data.jellyfin.JellyfinPathIndex.unambiguousIdOf(
                com.novax.app.data.NvGraph.repository().jellyfin.allPathIndexes(),
                path,
            )
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foregroundActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount--
                maybeEndMeteredSession(PlaybackService.activeEngine != null)
                // 隐私重锁（Task 11）：前台 Activity 归零=退前台。打开播放器等叠层切换
                // （新 Activity 先 Started 后旧 Stopped）计数不归零不误触发；PiP/分屏不收
                // ON_STOP，由 PlayerActivity 退出小窗处补挂——绝不在 MainActivity.ON_STOP 挂
                if (foregroundActivityCount <= 0) {
                    com.novax.app.data.privacy.NvPrivacyRelockOrchestrator.relock()
                }
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
