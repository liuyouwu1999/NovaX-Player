package com.novax.app.data.privacy

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * 单调时钟抽象：PIN 锁定（跨重启累计锁定时长）需要「已开机时长 + 开机次数」两个维度，
 * 纯 JVM 单测注入 fake，生产走 [NvMonotonicClock.androidSystem]。
 */
interface NvMonotonicClock {
    /** 开机以来经过的毫秒数（含深度睡眠） */
    fun elapsedRealtime(): Long

    /** 设备累计开机次数；个别 ROM 读不到时兜底 0（配合 elapsedRealtime 重启即归零仍可判定重启） */
    fun bootCount(): Int

    companion object {
        fun androidSystem(context: Context): NvMonotonicClock = AndroidSystemClock(context)
    }
}

private class AndroidSystemClock(context: Context) : NvMonotonicClock {

    private val appContext = context.applicationContext

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.onFailure {
        Log.w("NvPrivacyClock", "NvPrivacy bootCount unavailable: ${it.message}")
    }.getOrDefault(0)
}
