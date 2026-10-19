package com.novax.app.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.novax.app.data.NvGraph
import com.novax.app.data.db.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 流量确认闸门的共享判定（2026-09-28 审计 P0-2 修复收口；2026-10-11 三档化）。
 *
 * 此前的闸门只在 PlayerActivity.onCreate「首次起播」生效——自动连播、错误重试、
 * 暖态 resync、通知/锁屏切集全部绕过；本对象把「是否需要确认」收成单一判定，
 * 起播家族的全部入口（新建/重试/切集/服务切集；RMVB 兜底页走自有
 * needsMeteredConfirmTarget 但共用本对象与 NvMeteredGuard 的同一套纯函数）统一调用：
 * **每次起播都重新判定**；「仅提醒一次」档的会话豁免口径见
 * docs/superpowers/specs/2026-10-11-metered-remind-session-design.md §3
 * （前台 Activity 归零且服务引擎已释放才终结会话，不再有「同一段网络」约束）。
 */
object MeteredGate {

    /**
     * 档位读取收口（含旧键读时迁移；DataStore 读失败按默认档 SESSION_ONCE，宁弹勿漏）。
     * 旧键字面量在此与 SettingsGroups（§4.6）两处内联，注释互指防拼写漂移（spec §4.1）。
     */
    suspend fun remindMode(context: Context): MeteredRemindMode {
        val prefs = runCatching { context.playerPrefsStore.data.first() }.getOrNull()
        // 旧键读取用字面量内联（KEY_METERED_PLAY_REMIND 常量已废，仅迁移兼容，勿再引用）
        val legacy = prefs?.get(booleanPreferencesKey("novax_metered_play_remind"))
        return meteredRemindMode(prefs?.get(KEY_METERED_REMIND_MODE), legacy)
    }

    /**
     * 本次起播是否需要流量确认（三档裁决，spec §4.3）：
     * - never → 免；every → 恒弹（2026-09-28 原行为）；session_once → 会话未确认才弹；
     * - novax 源的类型解析失败/源不存在 → 按 SMB 保守处理（宁弹勿漏：误弹只烦一次，
     *   漏弹就是蜂窝拉整片的流量事故）；
     * - 本地目标（LOCAL 源 / file:/content: / 本地绝对路径）恒免。
     */
    suspend fun needsConfirm(context: Context, url: String, novax: Pair<Long, String>?): Boolean {
        val mode = remindMode(context)
        if (!NvMeteredGuard.needsConfirmation(mode, NvMeteredGuard.sessionConfirmed)) return false
        val type = novax?.let { (sid, _) ->
            withContext(Dispatchers.IO) {
                runCatching { NvGraph.sourceDao(context).getById(sid) }.getOrNull()?.type
            } ?: SourceType.SMB
        }
        return NvMeteredGuard.needsDataConfirmation(url, type, NvMeteredGuard.isMeteredNetwork(context))
    }
}
