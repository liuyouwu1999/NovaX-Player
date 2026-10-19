package com.novax.app.ui.viewers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.novax.app.data.NvGraph
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.playback.NvUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * nv_uri → 已解析的查看器文件输入（C9 §0.2 ViewerContract）：
 * - novax://<sourceId>/<path>：Room 取 SourceEntity → NvSourceRepository.openStream（只读复用）；
 * - file:// 或绝对路径：直接 File。
 */
sealed interface ViewerInput {
    val title: String

    data class Local(val file: File, override val title: String) : ViewerInput
    data class Remote(val sourceId: Long, val path: String, override val title: String) : ViewerInput

    companion object {
        fun parse(uriString: String, title: String): ViewerInput? {
            if (uriString.startsWith(NvUri.SCHEME + "://")) {
                val (id, path) = NvUri.parse(uriString) ?: return null
                return Remote(id, path, title)
            }
            val path = if (uriString.startsWith("file://")) {
                Uri.parse(uriString).path ?: return null
            } else {
                uriString
            }
            return Local(File(path), title)
        }
    }
}

/** novax 源解析（Room 查 SourceEntity；不存在抛中文可读异常） */
private suspend fun resolveSource(context: Context, sourceId: Long): SourceEntity =
    withContext(Dispatchers.IO) {
        NvGraph.sourceDao(context).getById(sourceId)
    } ?: throw IOException("文件源不存在或已被删除（id=$sourceId）")

/** 统一打开输入流（SMB/WebDAV 连接建立在仓库层；调用方负责 close） */
suspend fun openViewerStream(context: Context, input: ViewerInput): InputStream = when (input) {
    is ViewerInput.Local -> withContext(Dispatchers.IO) { FileInputStream(input.file) }
    is ViewerInput.Remote -> NvGraph.repository().openStream(resolveSource(context, input.sourceId), input.path)
}

/** 已知文件大小（-1 未知）：本地直接取文件；远程由调用方传 nv_size_bytes */
fun knownSizeOf(input: ViewerInput, remoteSizeBytes: Long): Long = when (input) {
    is ViewerInput.Local -> if (input.file.isFile) input.file.length() else -1L
    is ViewerInput.Remote -> remoteSizeBytes
}

/** 还原为可传递/可缓存的 uri 字符串（外发完整打开用） */
fun ViewerInput.toUriString(): String = when (this) {
    is ViewerInput.Local -> "file://" + file.absolutePath
    is ViewerInput.Remote ->
        NvUri.SCHEME + "://" + sourceId + Uri.encode(com.novax.app.data.NvPaths.normalize(path), "/")
}

/**
 * 外发与缓存工具（C9 §0.3 / §六）：
 * - [materialize]：novax:// 或任意本地文件 → cacheDir/<subDir>/ 副本（带进度、可取消）；
 * - [share]：FileProvider + ACTION_VIEW（grantUriPermissions）；
 * - [mimeTypeOf]：按扩展名推断 MIME，推断不出 application/octet-stream。
 */
object ViewerShare {

    /**
     * 把 uri 指向的文件复制到 cacheDir/<subDir>/<displayName>。
     * 已有同名且大小一致的副本直接复用（查看器退出不立即清，返回再看不重下）。
     * onProgress：总量已知传 0f..1f，未知传 null（调用方显示不确定进度）。
     */
    suspend fun materialize(
        context: Context,
        uri: String,
        displayName: String,
        subDir: String,
        knownTotal: Long = -1L,
        onProgress: (Float?) -> Unit = {},
    ): File {
        val input = ViewerInput.parse(uri, displayName)
            ?: throw IOException("无法解析文件地址: $uri")
        return materialize(context, input, displayName, subDir, knownTotal, onProgress)
    }

    /** 同上，接受已解析的 [ViewerInput]（查看器屏内部直接持有 input 时使用）；knownTotal：调用方已知的文件大小（-1 未知） */
    suspend fun materialize(
        context: Context,
        input: ViewerInput,
        displayName: String,
        subDir: String,
        knownTotal: Long = -1L,
        onProgress: (Float?) -> Unit = {},
    ): File {
        val dir = File(context.cacheDir, subDir).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[/\\\\?%*:|\"<>]"), "_").ifBlank { "file" }
        // BUG-C-R8-06：远程副本缓存键加 uri 稳定哈希前缀——原实现只按展示名+大小复用，
        // 同名同大小的不同文件会串内容
        val cacheKeyPrefix = when (input) {
            is ViewerInput.Remote -> cacheKeyOf(input.toUriString()) + "_"
            is ViewerInput.Local -> ""
        }
        val out = File(dir, cacheKeyPrefix + safeName)

        return withContext(Dispatchers.IO) {
            val total = when (input) {
                is ViewerInput.Local -> if (input.file.isFile) input.file.length() else -1L
                is ViewerInput.Remote -> knownTotal
            }
            if (out.isFile && total > 0 && out.length() == total) {
                // 续用副本视为「刚被使用」：否则 30s 一次的缓存按龄清扫有低概率并发删掉它
                runCatching { out.setLastModified(System.currentTimeMillis()) }
                onProgress(1f)
                return@withContext out
            }
            // 2026-09-16 审计 P2：临时名加唯一后缀——两个协程并发物化同一文件时
            // 固定 ".download" 名会互相截断对方半成品，rename 出损坏结果
            val tmp = File(dir, "$safeName.${java.util.UUID.randomUUID()}.download")
            openViewerStream(context, input).use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    var soFar = 0L
                    while (true) {
                        coroutineContext.ensureActive()          // 取消：中断复制
                        val n = ins.read(buf)
                        if (n <= 0) break // n==0 防自旋（2026-09-16 审计同型位点）
                        outs.write(buf, 0, n)
                        soFar += n
                        onProgress(if (total > 0) (soFar.toDouble() / total).toFloat().coerceIn(0f, 1f) else null)
                    }
                    onProgress(1f)
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            out
        }
    }

    /** FileProvider 外发 ACTION_VIEW；无可处理 App / 拉起失败返回 false（调用方 Toast 提示） */
    fun share(context: Context, file: File, mime: String): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    /** 扩展名 → MIME（未知 → application/octet-stream） */
    fun mimeTypeOf(fileName: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(NvFileRouter.extensionOf(fileName))
            ?: "application/octet-stream"

    /** uri → 稳定缓存键（MD5 前 16 hex），远程流先落盘再给 Coil 加载用 */
    fun cacheKeyOf(uri: String): String =
        MessageDigest.getInstance("MD5").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
