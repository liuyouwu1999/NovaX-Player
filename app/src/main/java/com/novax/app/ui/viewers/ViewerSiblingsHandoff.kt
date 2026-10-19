package com.novax.app.ui.viewers

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 图片翻页 siblings 的 Intent 传递兜底（2026-10-30 闪退修复）：
 * 文件页点图会把同目录全部图片的 uri 列表（nv_sibling_uris）塞进 Intent extras 供查看器
 * HorizontalPager 翻页；目录图片成百上千时该 Bundle 走 Binder 事务（上限 1MB），超限直接
 * TransactionTooLargeException——startActivity 失败且前台进程被杀。用户真机实测
 * 打开大图库目录必闪退（系统侧记录 Large outgoing transaction ≈1MB + "Can't real start
 * activity"，无 Java 栈）；模拟器 6000 张中文名图目录 1:1 复现（data parcel 984132 bytes）。
 *
 * 兜底：列表估算体积超过安全阈值时改写 cacheDir 固定名临时文件，Intent 只带文件路径
 * （EXTRA_SIBLINGS_FILE），查看器 onCreate 读回。小列表仍走原 extras，行为零变化。
 * 格式 writeInt(count) + count×writeUTF（单条 64KB 上限；URL 编码后的 nv uri 远达不到）。
 * 固定文件名覆盖写：查看器可能在读入前被杀重建后重读，故读侧不删文件；同名单文件天然防堆积。
 */
object ViewerSiblingsHandoff {

    /** Intent extras 走 Binder 的安全预算：远低于 1MB 事务上限，给同 Intent 其他数据与并发事务留余量 */
    internal const val MAX_EXTRAS_BYTES = 128 * 1024

    private const val FILE_NAME = "pending_image_siblings.bin"

    /** 估算列表进 Bundle 的体积（Parcel String 每字符 2 字节 + 长度头，再放大余量） */
    internal fun estimatedBundleBytes(uris: List<String>): Long =
        uris.sumOf { it.length * 2L + 40L } + 128L

    fun shouldOffload(uris: List<String>): Boolean =
        estimatedBundleBytes(uris) > MAX_EXTRAS_BYTES

    /** 大列表落盘（cacheDir 固定名）；失败返回 null（调用方回退原 extras——最坏回到崩溃路径，不阻塞打开） */
    fun write(context: Context, uris: List<String>): File? = writeTo(context.cacheDir, uris)

    /** [write] 的目录参数版本（纯逻辑，单测用临时目录直调） */
    internal fun writeTo(dir: File, uris: List<String>): File? = runCatching {
        val out = File(dir, FILE_NAME)
        DataOutputStream(BufferedOutputStream(FileOutputStream(out))).use { s ->
            s.writeInt(uris.size)
            uris.forEach { s.writeUTF(it) }
        }
        out
    }.getOrNull()

    /** 查看器侧读回；缺失/损坏/条数异常返回 null（调用方退回 extras） */
    fun read(file: File): List<String>? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { s ->
            val n = s.readInt()
            if (n < 0 || n > 1_000_000) return@runCatching null
            List(n) { s.readUTF() }
        }
    }.getOrNull()
}
