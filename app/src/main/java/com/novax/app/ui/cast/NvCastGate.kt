package com.novax.app.ui.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.novax.app.data.cast.CastMedia
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceType
import com.novax.app.player.KEY_METERED_REMIND_MODE
import com.novax.app.player.METERED_REMIND_NEVER
import com.novax.app.player.MeteredGate
import com.novax.app.player.NvMeteredGuard
import com.novax.app.player.putPlayerPref
import com.novax.app.ui.components.NvMeteredConfirmDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 投屏计量网络确认闸门（2026-09-28 审计 P0-1）：投屏 = 手机从 NAS 拉整片字节经本地流服务器
 * 转发电视，蜂窝/Tailscale 下等于整片流量过手机——与播放同受「仅 Wi-Fi 免确认」纪律约束
 * （此前的流量闸门只覆盖了播放与抽帧，投屏三个入口完全无闸）。
 *
 * 用法（三个投屏入口统一）：`val gate = rememberNvCastGateState()`，onCast 协程里先
 * `if (!gate.awaitConsent(media)) return`，再 `NvCastMeteredDialogHost(gate)` 渲染确认框。
 * 档位与播放确认框共用同一解析（MeteredGate.remindMode；「不提醒」档 = 原勾选
 * 「流量下不再提醒」，设置页三档可改回；「仅提醒一次」档会话内已确认过则直接放行，spec §4.3）。
 */
class NvCastMeteredGateState(private val appContext: android.content.Context) {

    internal var pending by mutableStateOf<CompletableDeferred<Boolean>?>(null)
    var remindChecked by mutableStateOf(false)

    /** 需要确认则挂起等用户裁决：true=放行，false=用户取消（调用方放弃投屏，零流量） */
    suspend fun awaitConsent(media: CastMedia): Boolean {
        if (!NvMeteredGuard.isMeteredNetwork(appContext)) return true
        val source = runCatching { MediaDb.get(appContext).sourceDao().getById(media.sourceId) }.getOrNull()
        // 本地源投屏 = 电视从手机局域网拉文件，不经蜂窝；源已删除走 cast() 既有错误路径
        if (source == null || source.type == SourceType.LOCAL) return true
        // 三档裁决（2026-10-11，与播放同一豁免口径）：never 免；every 恒确认；
        // session_once 会话内已确认（含播放页确认后的跨入口豁免）免
        if (!NvMeteredGuard.needsConfirmation(
                MeteredGate.remindMode(appContext), NvMeteredGuard.sessionConfirmed)
        ) return true
        remindChecked = false
        val d = CompletableDeferred<Boolean>()
        pending = d
        return try {
            d.await()
        } finally {
            if (pending === d) pending = null
        }
    }

    fun continueCast() {
        if (remindChecked) {
            // 勾「流量下不再提醒」→ 写「不提醒」档（fire-and-forget，IO 落盘；设置页可改回）
            appScope.launch { runCatching { appContext.putPlayerPref(KEY_METERED_REMIND_MODE, METERED_REMIND_NEVER) } }
        }
        // 「仅提醒一次」档：本会话已确认（取消走 cancelCast，不置位，spec §4.4）
        NvMeteredGuard.markSessionConfirmed()
        pending?.complete(true)
        pending = null
    }

    fun cancelCast() {
        pending?.complete(false)
        pending = null
    }

    private companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable
fun rememberNvCastGateState(): NvCastMeteredGateState {
    val app = LocalContext.current.applicationContext
    return remember { NvCastMeteredGateState(app) }
}

/** 确认框宿主：pending 非空时弹「流量播放确认」（与播放页同一组件） */
@Composable
fun NvCastMeteredDialogHost(gate: NvCastMeteredGateState) {
    if (gate.pending != null) {
        NvMeteredConfirmDialog(
            remindDisabledChecked = gate.remindChecked,
            onRemindDisabledCheckedChange = { gate.remindChecked = it },
            onContinue = { gate.continueCast() },
            onCancel = { gate.cancelCast() },
        )
    }
}
