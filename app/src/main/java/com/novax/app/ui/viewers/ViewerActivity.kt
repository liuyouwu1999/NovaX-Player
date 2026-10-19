package com.novax.app.ui.viewers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.novax.app.ui.theme.NovaXTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * C9 §0：全部非视频查看器（图片 / PDF / 文本）的单一承载 Activity。
 * 全屏纯黑沉浸（无系统栏）；按 nv_type 切换 Compose 屏幕；返回 = finish（各屏内部处理缩放复位等语义）。
 *
 * intent extras（ViewerContract）：
 * - nv_type：image / pdf / text（大小写不敏感）
 * - nv_uri：file:// / 绝对路径 / novax://<sourceId>/<path>
 * - nv_title：文件名（顶栏 / 占位）
 * - nv_size_bytes：远程文件预估大小（下载进度），未知 -1
 * - nv_sibling_uris：同目录图片列表（图片 Pager 用，含自身）
 * - nv_start_index：被点图片在 sibling 列表中的下标
 *
 * 音频不进本 Activity（复用 PlayerActivity）；未知类型在文件页内弹 NvBottomSheet。
 * Manifest：仅 debug 清单 exported（真机 adb 冒烟：am start 直接拉起），release 不导出。
 */
class ViewerActivity : ComponentActivity() {

    private var pendingToast by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kind = parseType(
            intent.getStringExtra(EXTRA_TYPE) ?: intent.getStringExtra(LEGACY_TYPE_KEY).orEmpty(),
        )
        val uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: uri.substringAfterLast('/').substringAfterLast('\\').ifBlank { "文件" }
        val sizeBytes = intent.getLongExtra(EXTRA_SIZE, -1L)
        val siblings = (intent.getStringArrayListExtra(EXTRA_SIBLINGS)
            ?: arrayListOf(uri)).filter { it.isNotBlank() }
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, siblings.lastIndex.coerceAtLeast(0))

        // 全屏沉浸（与播放器同配方）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (uri.isBlank() || kind == null) {
            Toast.makeText(this, "无法打开文件（参数缺失）", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val input = ViewerInput.parse(uri, title)
        if (input == null) {
            Toast.makeText(this, "无法解析文件地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            NovaXTheme {
                // Liquid Glass（spec §6 第 4 域）：env 下发包 setContent 根。source 不挂根 Box——
                // decisions.md §4.5：根级 source 会把玻璃面（NvTopBar）裹进子树被防递归静默跳过，
                // source 由各查看器挂其内容容器、NvTopBar 悬浮做兄弟（TextViewerScreen/PdfViewerScreen 各自接线）。
                com.novax.app.ui.glass.LocalNvGlassEnvProviding {
                    val onBack = { finish() }
                    val onOpenWith: (String, String) -> Unit = { u, name -> openWithExternal(u, name) }
                    when (kind) {
                        ViewerKind.IMAGE -> ImageViewerScreen(
                            uris = siblings,
                            startIndex = startIndex,
                            onBack = onBack,
                            onOpenWith = onOpenWith,
                        )
                        ViewerKind.PDF -> PdfViewerScreen(
                            input = input,
                            sizeBytes = sizeBytes,
                            onBack = onBack,
                            onOpenWith = onOpenWith,
                        )
                        ViewerKind.TEXT -> TextViewerScreen(
                            input = input,
                            sizeBytes = sizeBytes,
                            onBack = onBack,
                            onOpenWith = onOpenWith,
                        )
                        else -> Unit
                    }
                    pendingToast?.let { msg ->
                        // 非组合内 Toast：挂一次性副作用（如外发失败提示）
                        androidx.compose.runtime.LaunchedEffect(msg) {
                            Toast.makeText(this@ViewerActivity, msg, Toast.LENGTH_SHORT).show()
                            pendingToast = null
                        }
                    }
                }
            }
        }
    }

    /** 查看器内「用其他应用打开」：先全量缓存到 cacheDir/share/ 再 FileProvider 外发 */
    private fun openWithExternal(uri: String, displayName: String) {
        val name = displayName.substringAfterLast('/').ifBlank { "file" }
        lifecycleScope.launch {
            try {
                val file = ViewerShare.materialize(this@ViewerActivity, uri, name, "share")
                if (!ViewerShare.share(this@ViewerActivity, file, ViewerShare.mimeTypeOf(name))) {
                    pendingToast = "未找到可处理此类型的应用"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingToast = "打开失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    companion object {
        const val EXTRA_TYPE = "nv_type"
        const val EXTRA_URI = "nv_uri"
        const val EXTRA_TITLE = "nv_title"
        const val EXTRA_SIZE = "nv_size_bytes"
        const val EXTRA_SIBLINGS = "nv_sibling_uris"
        const val EXTRA_START_INDEX = "nv_start_index"
        private const val LEGACY_TYPE_KEY = "nv_viewer_type"

        enum class ViewerKind { IMAGE, PDF, TEXT }

        fun parseType(raw: String): ViewerKind? = when (raw.trim().lowercase()) {
            "image" -> ViewerKind.IMAGE
            "pdf" -> ViewerKind.PDF
            "text" -> ViewerKind.TEXT
            else -> null
        }

        /** 文件页路由入口：图片/PDF/文本统一经此拉起查看器 */
        fun start(
            context: Context,
            kind: NvFileRouter.Kind,
            uri: String,
            title: String,
            sizeBytes: Long = -1L,
            siblings: List<String> = emptyList(),
            startIndex: Int = 0,
        ) {
            val viewerKind = when (kind) {
                NvFileRouter.Kind.IMAGE -> ViewerKind.IMAGE
                NvFileRouter.Kind.PDF -> ViewerKind.PDF
                NvFileRouter.Kind.TEXT -> ViewerKind.TEXT
                else -> return
            }
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putExtra(EXTRA_TYPE, viewerKind.name.lowercase())
                    .putExtra(EXTRA_URI, uri)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SIZE, sizeBytes)
                    .putStringArrayListExtra(EXTRA_SIBLINGS, ArrayList(siblings.ifEmpty { listOf(uri) }))
                    .putExtra(EXTRA_START_INDEX, startIndex),
            )
        }
    }
}
