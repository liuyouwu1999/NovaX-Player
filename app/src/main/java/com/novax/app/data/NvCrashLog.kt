package com.novax.app.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志（2026-09-15 D2 成熟软件补齐）：
 * UncaughtExceptionHandler 落 files/crash/crash-<时间>.txt（保留最近 [MAX_FILES] 份），
 * 然后交还原 handler（保持系统崩溃行为不变）。设置页「关于」可查看/分享最近一份。
 * 纯本地，零网络上报。
 */
object NvCrashLog {

    private const val MAX_FILES = 5

    fun dir(context: Context): File = File(context.filesDir, "crash")

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 幂等安装（复审 MINOR-4：Activity 重建重复包裹会让一次崩溃写多份日志） */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 最近一份崩溃日志（无记录返回 null） */
    fun latest(context: Context): File? =
        dir(context).listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.name }

    /** 非致命异常落盘（E5-1 发版审查，协程作用域兜底处理器用）：与崩溃日志同目录同轮换，首行 source= 前缀便于区分。 */
    fun recordNonFatal(context: Context, source: String, throwable: Throwable) {
        runCatching { write(context, Thread.currentThread(), throwable, source) }
    }

    /** 全部分崩溃记录（老 → 新） */
    fun all(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }.orEmpty()

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /**
     * 上次异常退出取证（2026-09-16 审计 P2-14，API 30+）：UncaughtExceptionHandler 抓不到
     * native crash / LMK 杀前台 / ANR——用 ApplicationExitInfo 在下次冷启动补记
     * （排除正常退出与用户主动停止）。落 files/crash/exit-<时间>.txt + Logcat，纯本地诊断。
     */
    fun recordLastExit(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return
            val excluded = setOf(
                android.app.ApplicationExitInfo.REASON_EXIT_SELF,
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED,
                android.app.ApplicationExitInfo.REASON_USER_STOPPED,
            )
            if (last.reason in excluded) return
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(last.timestamp))
            File(dir(context), "exit-$ts.txt").writeText(
                "reason=${last.reason} (${last.description})\n" +
                    "status=${last.status}\nimportance=${last.importance}\n" +
                    "endAt=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(last.timestamp)) + "\n",
            )
            android.util.Log.w("NvCrashLog", "last exit: reason=${last.reason} desc=${last.description}")
            // exit-* 同样只留最近 MAX_FILES 份
            dir(context).listFiles { f -> f.name.startsWith("exit-") }
                ?.sortedBy { it.name }?.dropLast(MAX_FILES)?.forEach { runCatching { it.delete() } }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable, source: String? = null) {
        val d = dir(context)
        if (!d.isDirectory) d.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(d, "crash-$ts.txt")
        StringWriter().use { sw ->
            PrintWriter(sw).use { pw ->
                // 非致命日志带来源前缀（E5-1），与崩溃日志共用文件名规则，不影响既有调用方
                source?.let { pw.println("source=$it") }
                pw.println("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                pw.println("thread=" + thread.name)
                pw.println("app=NovaX debug")
                pw.println()
                throwable.printStackTrace(pw)
            }
            out.writeText(sw.toString())
        }
        // 滚动清理：只留最近 MAX_FILES 份
        all(context).dropLast(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}
