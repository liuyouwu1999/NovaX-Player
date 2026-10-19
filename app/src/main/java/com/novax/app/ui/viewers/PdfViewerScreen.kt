@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.ui.viewers

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvSkeletonBox
import com.novax.app.ui.components.NvSlider
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * C9 §三 PDF 阅读器：
 * - 远程源先全量缓存到 cache/viewers（NvDownloadBanner 进度 + 取消；>200MB 先确认「先下载后打开」）；
 * - PdfRenderer 渲染每页 Bitmap（LruCache 上限 6 页，渲染在 Dispatchers.IO，页宽按屏宽、密度上限 2048px）；
 * - LazyColumn 纵向连续页（白纸页 + 4dp 圆角 + 8dp 间距），页位骨架 → 位图 150ms 淡入；
 * - 顶栏页码 x/y（tnum 等宽防跳）+ 页码跳转 NvBottomSheet（大数字 + Slider）；
 * - 状态：下载中 / 超限确认 / 解析失败重试 / 加密 / 空文档（§3.2）。
 */
@Composable
fun PdfViewerScreen(
    input: ViewerInput,
    sizeBytes: Long,
    onBack: () -> Unit,
    onOpenWith: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 观感返工①（2026-10-15）：顶栏静止态隐形（PDF 页滚过首页渐显）
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)

    var phase by remember { mutableStateOf<PdfPhase>(PdfPhase.Idle) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }   // null = 总量未知（不确定进度）
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var openAttempt by remember { mutableIntStateOf(0) }   // BUG-C-R8-05：打开失败重试的 DisposableEffect key
    var showJump by remember { mutableStateOf(false) }

    // Ready → 打开 PdfRenderer（加密/损坏分别进入空态/错误态）
    val readyFile = (phase as? PdfPhase.Ready)?.file
    var renderer by remember { mutableStateOf<NvPdfRenderer?>(null) }
    var encrypted by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(readyFile, openAttempt) {
        encrypted = false
        openError = null
        var opened: NvPdfRenderer? = null
        if (readyFile != null) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = ParcelFileDescriptor.open(readyFile, ParcelFileDescriptor.MODE_READ_ONLY)
                opened = NvPdfRenderer(pfd)
                renderer = opened
            } catch (e: SecurityException) {
                runCatching { pfd?.close() }   // BUG-C-R8-08：构造失败时 fd 必须显式关（原实现泄漏）
                encrypted = true
            } catch (e: Exception) {
                runCatching { pfd?.close() }   // 同上
                openError = e.message ?: e.javaClass.simpleName
            }
        } else {
            renderer = null
        }
        onDispose {
            runCatching { opened?.close() }
            renderer = null
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetWidthPx = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    }

    fun startDownload() {
        val inp = input as? ViewerInput.Remote ?: return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            phase = PdfPhase.Downloading
            downloadProgress = null
            try {
                val file = ViewerShare.materialize(context, inp, inp.title, "viewers", knownTotal = knownSizeOf(inp, sizeBytes)) { p ->
                    downloadProgress = p
                }
                phase = PdfPhase.Ready(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = PdfPhase.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // 主流程：本地直接打开；远程 >200MB 先确认，否则直接下载
    LaunchedEffect(input, reloadKey) {
        when (val inp = input) {
            is ViewerInput.Local ->
                phase = if (inp.file.isFile) PdfPhase.Ready(inp.file)
                else PdfPhase.Error("文件不存在或不可读")
            is ViewerInput.Remote ->
                if (knownSizeOf(inp, sizeBytes) > NvFileRouter.PDF_CONFIRM_BYTES) {
                    phase = PdfPhase.ConfirmLarge
                } else {
                    startDownload()
                }
        }
    }

    // Liquid Glass（decisions.md §4.5）：source 挂内容区，NvTopBar 悬浮做兄弟；
    // 背景在 source 之后（§4.5a：hazeSource 只录制链中位于其后的绘制，栏后区域须有源像素）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .nvGlassSource(glassEnv)
                .background(NvColors.Background),
        ) {
            when (val p = phase) {
                PdfPhase.Idle, PdfPhase.ConfirmLarge -> Box(Modifier.fillMaxSize().padding(top = topInset)) {
                    PageSkeletons()
                }
                PdfPhase.Downloading -> Column(Modifier.padding(top = topInset)) {
                    NvDownloadBanner(
                        progress = downloadProgress,
                        onCancel = {
                            downloadJob?.cancel()
                            onBack()   // §3.2 取消 → 中断下载并退出
                        },
                    )
                    PageSkeletons()
                }
                is PdfPhase.Error -> Box(
                    Modifier.fillMaxSize().padding(top = topInset).padding(horizontal = 40.dp),
                ) {
                    NvErrorState(
                        title = "无法打开此 PDF",
                        message = p.message,
                        onRetry = { reloadKey++ },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                is PdfPhase.Ready -> when {
                    encrypted -> Box(
                        Modifier.fillMaxSize().padding(top = topInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        NvEmptyState(Icons.Outlined.Lock, "PDF 已加密", "暂不支持密码解锁")
                    }
                    openError != null -> Box(
                        Modifier.fillMaxSize().padding(top = topInset).padding(horizontal = 40.dp),
                    ) {
                        NvErrorState(
                            title = "无法打开此 PDF",
                            message = openError.orEmpty(),
                            // BUG-C-R8-05：retry 递增打开尝试序号——原 DisposableEffect 以同一 File 为 key，
                            // readyFile 相等不重跑，「重试」是死按钮
                            onRetry = { reloadKey++; openAttempt++ },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    renderer == null -> Box(Modifier.fillMaxSize().padding(top = topInset)) {
                        PageSkeletons()
                    }
                    renderer!!.pageCount == 0 -> Box(
                        Modifier.fillMaxSize().padding(top = topInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        NvEmptyState(Icons.Outlined.Description, "此 PDF 不包含任何页面")
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        // 首页经 contentPadding 避让悬浮顶栏（原顶栏下方 L 间距不变），
                        // 翻页时页钻入顶栏后透进玻璃（§4.5 配方）
                        contentPadding = PaddingValues(
                            start = NvSpacing.L,
                            top = topInset + NvSpacing.L,
                            end = NvSpacing.L,
                            bottom = NvSpacing.L,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NvSpacing.S),
                    ) {
                        val r = renderer!!
                        items(r.pageCount, key = { it }) { index ->
                            NvPdfPage(renderer = r, index = index, targetWidthPx = targetWidthPx)
                        }
                    }
                }
            }
        }

        // §八登记 NvPdfTopBar：返回 + 文件名 + 页码 x/y + 跳转（悬浮常驻——顶栏常驻不隐藏，阅读场景）
        NvTopBar(
            title = input.title,
            onBack = onBack,
            scrolled = scrolled,   // 观感返工①：静止态隐形，翻页滚过首页渐显
            actions = listOf({
                val r = renderer
                if (r != null && r.pageCount > 0) {
                    Text(
                        "${(listState.firstVisibleItemIndex + 1).coerceAtMost(r.pageCount)} / ${r.pageCount}",
                        style = NvTypography.LabelMedium.copy(fontFeatureSettings = "tnum"),
                        color = NvColors.TextSecondary,
                    )
                    Spacer(Modifier.width(NvSpacing.S))
                }
                IconButton(onClick = { showJump = true }, Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.FormatListNumbered, "跳转到页面",
                        Modifier.size(24.dp), tint = NvColors.TextPrimary,
                    )
                }
            }),
        )
    }

    if (phase is PdfPhase.ConfirmLarge) {
        NvConfirmDialog(
            title = "先下载后打开",
            message = "该文件 ${fmtSizeOf(knownSizeOf(input, sizeBytes))}，将先下载到本机缓存再打开",
            confirmText = "下载并打开",
            onConfirm = { startDownload() },
            onDismiss = onBack,
        )
    }

    val r = renderer
    if (showJump && r != null && r.pageCount > 0) {
        NvPageJumpSheet(
            currentPage = listState.firstVisibleItemIndex,
            pageCount = r.pageCount,
            onJump = { target ->
                showJump = false
                scope.launch { listState.animateScrollToItem(target.coerceIn(0, r.pageCount - 1)) }
            },
            onDismiss = { showJump = false },
        )
    }
}

@Composable
private fun PageSkeletons() {
    Column(Modifier.padding(NvSpacing.L)) {
        repeat(3) { i ->
            NvSkeletonBox(Modifier.fillMaxWidth().height(220.dp), corner = 4.dp)
            if (i < 2) Spacer(Modifier.height(NvSpacing.S))
        }
    }
}

/** §八登记 NvPdfPage：页位骨架 → 位图 150ms 淡入（白纸页 + 4dp 圆角，无投影）。
 *  2026-09-18 用户反馈：双指无法放大——加捏合缩放（1x–3x graphicsLayer 变换，位图放大有
 *  软化属预期）；缩放态（>1x）单指拖动平移页面并消费事件（不与 LazyColumn 滚动打架），
 *  1x 时单指照常滚页；双击 2.5x ↔ 1x；缩放页 zIndex 置顶避免被相邻页盖住。 */
@Composable
internal fun NvPdfPage(
    renderer: NvPdfRenderer,
    index: Int,
    targetWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    var bmp by remember(index) { mutableStateOf<Bitmap?>(null) }
    var alpha by remember(index) { mutableFloatStateOf(0f) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var pageSize by remember(index) { mutableStateOf(IntSize.Zero) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * pageSize.width / 2f
        val maxY = (s - 1f) * pageSize.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(renderer.pageAspect(index))
            .onSizeChanged { pageSize = it }
            .zIndex(if (scale > 1f) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val multiTouch = event.changes.size > 1
                        val handled = if (multiTouch || scale > 1f) {
                            if (zoomChange != 1f) {
                                scale = (scale * zoomChange).coerceIn(1f, NvPdfRenderer.PDF_MAX_ZOOM)
                            }
                            offset = clampOffset(offset + panChange, scale)
                            true
                        } else {
                            false
                        }
                        if (handled) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (scale == 1f) offset = Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        offset = Offset.Zero
                    }
                })
            },
    ) {
        val b = bmp
        if (b == null) {
            NvSkeletonBox(Modifier.fillMaxSize(), corner = 4.dp)
        } else {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
    LaunchedEffect(index, renderer) {
        runCatching { renderer.render(index, targetWidthPx) }
            .onSuccess { bitmap ->
                bmp = bitmap
                animate(0f, 1f, animationSpec = tween(NvMotion.DurationFast)) { v, _ -> alpha = v }
            }
    }
}

/** §八登记 NvDownloadBanner：远程缓存下载进度横幅（4dp 进度 + 百分比 + 取消，内联非全屏） */
@Composable
private fun NvDownloadBanner(progress: Float?, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(NvColors.Surface)
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f),
                color = NvColors.Primary,
                trackColor = NvColors.SurfaceElevated,
            )
            Spacer(Modifier.width(NvSpacing.S))
            Text(
                "${(progress * 100).toInt()}%",
                style = NvTypography.LabelMedium,
                color = NvColors.TextSecondary,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.weight(1f),
                color = NvColors.Primary,
                trackColor = NvColors.SurfaceElevated,
            )
            Spacer(Modifier.width(NvSpacing.S))
            Text("准备下载…", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
        }
        NvTextButton("取消", onClick = onCancel, tint = NvColors.TextSecondary)
    }
}

/** §八登记 NvPageJumpSheet：页码跳转弹层（大数字 + Slider + 跳转按钮） */
@Composable
private fun NvPageJumpSheet(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }
    NvBottomSheet(onDismiss = onDismiss) {
        Text(
            "跳转到页面",
            Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
            style = NvTypography.TitleMedium,
        )
        Text(
            "${target.toInt() + 1}",
            Modifier.fillMaxWidth().padding(vertical = NvSpacing.S),
            style = NvTypography.TitleLarge,
            color = NvColors.Primary,
            textAlign = TextAlign.Center,
        )
        NvSlider(
            value = target,
            onValueChange = { target = it },
            valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
            steps = (pageCount - 2).coerceAtLeast(0),
        )
        NvPrimaryButton(
            "跳转",
            { onJump(target.toInt().coerceIn(0, pageCount - 1)) },
            Modifier.fillMaxWidth().padding(horizontal = NvSpacing.L),
        )
    }
}

/** PDF 屏内阶段状态机（§3.2） */
private sealed interface PdfPhase {
    data object Idle : PdfPhase
    data object ConfirmLarge : PdfPhase
    data object Downloading : PdfPhase
    data class Ready(val file: File) : PdfPhase
    data class Error(val message: String) : PdfPhase
}

/**
 * §八登记 NvPdfRenderer（C9 §3.3）：PdfRenderer 包装 + LruCache（上限 6 页），
 * 页渲染在 Dispatchers.IO 且对 renderer 串行化（PdfRenderer 非线程安全）。
 */
class NvPdfRenderer(pfd: ParcelFileDescriptor) : Closeable {
    private val renderer = PdfRenderer(pfd)
    val pageCount: Int get() = renderer.pageCount

    private val cache = object : LruCache<Int, Bitmap>(PDF_PAGE_CACHE_PAGES) {}

    /** 已知页宽高（渲染时回填；驱动骨架页 aspectRatio 就位） */
    var dims by mutableStateOf<Map<Int, Pair<Int, Int>>>(emptyMap())
        private set

    fun pageAspect(index: Int): Float {
        val d = dims[index] ?: return 1f / 1.4142f   // A4 纵向默认
        return if (d.second <= 0) 1f else d.first.toFloat() / d.second
    }

    suspend fun render(index: Int, targetWidthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        cache.get(index)?.let { return@withContext it }
        synchronized(renderer) {
            cache.get(index)?.let { return@withContext it }
            renderer.openPage(index).use { page ->
                val w = targetWidthPx.coerceAtMost(MAX_RENDER_WIDTH)
                val h = (page.height.toLong() * w / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                dims = dims + (index to (page.width to page.height))
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }.also { cache.put(index, it) }
        }
    }

    override fun close() {
        runCatching { cache.evictAll() }
        runCatching { renderer.close() }
    }

    companion object {
        /** 位图缓存上限：6 页（约 40MB @1080p 屏宽） */
        const val PDF_PAGE_CACHE_PAGES = 6
        const val MAX_RENDER_WIDTH = 2048

        /** 页面捏合缩放上限（位图按屏宽渲染，继续放大只会更软，3x 是可读性与清晰度的折中） */
        const val PDF_MAX_ZOOM = 3f
    }
}
