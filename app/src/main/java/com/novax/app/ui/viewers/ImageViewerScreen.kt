@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
package com.novax.app.ui.viewers

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvMotion
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSkeletonBox
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * C9 §二 图片查看器：黑底沉浸、无常驻 chrome；
 * - 同目录图片 HorizontalPager（±1 预加载，翻页缩放自动复位 1x，单图隐藏 Pager/页码）；
 * - 捏合 1x–5x、双击 1x↔2.5x（以双击点为中心）、缩放 >1x 才可平移（边界阻尼夹取）；
 * - 点按显隐信息栏（200ms 淡入淡出），显示 5s 无操作自动隐藏；
 * - 远程流（novax://）先拉到 cache/viewers 再交 Coil；加载中满幅骨架，失败页内失败卡可重试（不破坏 Pager）。
 */
@Composable
fun ImageViewerScreen(
    uris: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
    onOpenWith: (String, String) -> Unit,
) {
    val zoom = remember { NvZoomState() }
    val scope = rememberCoroutineScope()
    var infoVisible by remember { mutableStateOf(true) }
    var infoTimerKey by remember { mutableIntStateOf(0) }   // 每次点按重置 5s 自动隐藏计时
    var metas by remember { mutableStateOf<Map<Int, ImageMeta>>(emptyMap()) }

    val multi = uris.size > 1
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0)),
        pageCount = { uris.size },
    )
    // §2.1 翻页自动复位 1x（缩放状态上提屏幕级，BackHandler 可读）
    LaunchedEffect(pagerState.currentPage) { zoom.reset() }
    LaunchedEffect(infoVisible, infoTimerKey) {
        if (infoVisible) {
            delay(5_000)
            infoVisible = false
        }
    }

    val shownIndex = if (multi) pagerState.currentPage else 0
    val shownName = nameOf(uris.getOrNull(shownIndex).orEmpty())

    Box(Modifier.fillMaxSize().background(NvColors.Background)) {
        if (multi) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { uris[it] },
                beyondViewportPageCount = 1,   // 预加载相邻 ±1 页
            ) { page ->
                ImagePage(
                    uri = uris[page],
                    zoom = zoom,
                    onTap = { infoVisible = !infoVisible; infoTimerKey++ },
                    onMeta = { m -> metas = metas + (page to m) },
                    onOpenWith = onOpenWith,
                )
            }
        } else if (uris.isNotEmpty()) {
            ImagePage(
                uri = uris[0],
                zoom = zoom,
                onTap = { infoVisible = !infoVisible; infoTimerKey++ },
                onMeta = { m -> metas = metas + (0 to m) },
                onOpenWith = onOpenWith,
            )
        }

        // §2.1 信息栏：顶栏（返回 + 文件名）+ 底部 meta（尺寸 · 大小 + 页码指示），渐变遮罩底
        AnimatedVisibility(
            visible = infoVisible,
            enter = fadeIn(tween(NvMotion.DurationNormal, easing = NvMotion.CurveDecelerate)),
            exit = fadeOut(tween(NvMotion.DurationFast)),
        ) {
            NvImageViewerInfoBar(
                title = shownName,
                meta = metas[shownIndex],
                pageIndicator = if (multi) "${shownIndex + 1} / ${uris.size}" else null,
                onBack = onBack,
            )
        }
    }

    // §2.1 返回语义：缩放 >1x 先复位 1x（消费本次返回，200ms 减速），1x 才真正退出
    BackHandler(enabled = true) {
        if (zoom.scale > 1f) {
            zoom.animJob?.cancel()
            val from = zoom.scale
            zoom.animJob = scope.launch {
                animate(from, 1f, animationSpec = tween(NvMotion.DurationNormal, easing = NvMotion.CurveDecelerate)) { v, _ ->
                    zoom.scale = v
                }
                zoom.offset = Offset.Zero
            }
        } else {
            onBack()
        }
    }
}

/** 当前页缩放/平移状态（调用方持有：翻页复位与返回先复位都依赖） */
class NvZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    internal var animJob: Job? = null

    fun reset() {
        animJob?.cancel()
        animJob = null
        scale = 1f
        offset = Offset.Zero
    }
}

private data class ImageMeta(val dims: String? = null, val size: String? = null, val isGif: Boolean = false)

private sealed interface ImgLoad {
    data object Loading : ImgLoad
    /** width/height = 原图像素尺寸（bounds 头读取，0 表未知），供原图尺寸解码请求使用 */
    data class Ready(val file: File, val width: Int = 0, val height: Int = 0) : ImgLoad
    data class Failed(val message: String) : ImgLoad
}

@Composable
private fun ImagePage(
    uri: String,
    zoom: NvZoomState,
    onTap: () -> Unit,
    onMeta: (ImageMeta) -> Unit,
    onOpenWith: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val name = nameOf(uri)

    // §2.2 格式不支持：HEIC 需 Android 9+，旧系统给空态 + 外发出路
    if (NvFileRouter.extensionOf(name) == "heic" && Build.VERSION.SDK_INT < 28) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            NvEmptyState(
                Icons.Outlined.ImageNotSupported, "此格式需要 Android 9+",
                "当前系统无法解码 HEIC 图片",
                "用其他应用打开", { onOpenWith(uri, name) },
            )
        }
        return
    }

    var reloadKey by remember(uri) { mutableIntStateOf(0) }
    val load by produceState<ImgLoad>(ImgLoad.Loading, uri, reloadKey) {
        value = ImgLoad.Loading
        val result = runCatching {
            // BUG-C-R8-02：传入 knownTotal 让同名副本可复用——原实现 total=-1 使
            // 「同名同大小复用」条件永假，NAS 上翻页/回看每张图都整图重下。
            // 远程大小从媒体库表取（未入库保持 -1，走原路径）
            val parsed = ViewerInput.parse(uri, name)
            val knownTotal = when (parsed) {
                is ViewerInput.Remote -> withContext(Dispatchers.IO) {
                    runCatching {
                        com.novax.app.data.NvGraph.mediaItemDao(context)
                            .findByKey(parsed.sourceId, parsed.path)?.sizeBytes ?: -1L
                    }.getOrDefault(-1L)
                }
                is ViewerInput.Local -> parsed.file.takeIf { it.isFile }?.length() ?: -1L
                null -> -1L
            }
            // SMB/WebDAV 流不可直接给 Coil：先拉到 cache/viewers（副本复用，返回再看不重下）
            val file = ViewerShare.materialize(context, uri, name, "viewers", knownTotal)
            // 顺路读原图 bounds（仅解析文件头）：原图尺寸解码与信息栏 meta 共用，不二次解码
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath, opts) }
            ImgLoad.Ready(file, opts.outWidth, opts.outHeight)
        }
        value = result.fold(
            onSuccess = { it },
            onFailure = { ImgLoad.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    // meta 上报（尺寸 / 大小 / GIF 徽标），信息栏按当前页展示
    LaunchedEffect(load) {
        val ready = load as? ImgLoad.Ready ?: return@LaunchedEffect
        onMeta(
            ImageMeta(
                dims = if (ready.width > 0) "${ready.width} × ${ready.height}" else null,
                size = fmtSizeOf(ready.file.length()),
                isGif = NvFileRouter.extensionOf(name) == "gif",
            ),
        )
    }

    when (val s = load) {
        is ImgLoad.Ready -> {
            // 画质修复：查看器按原图尺寸解码（见 originalSizeRequest），放大不发糊
            val request = remember(s.file, s.width, s.height) {
                originalSizeRequest(context, s.file, s.width, s.height)
            }
            NvZoomableImage(model = request, zoom = zoom, onTap = onTap)
        }
        is ImgLoad.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // §2.2 加载失败：页内轻量失败卡（不破坏 Pager 上下文），重试仅重载当前页
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, null, Modifier.size(48.dp), tint = NvColors.TextDisabled)
                Spacer(Modifier.height(NvSpacing.M))
                Text(name, style = NvTypography.BodyMedium, color = NvColors.TextSecondary, maxLines = 2)
                Spacer(Modifier.height(NvSpacing.M))
                NvTextButton("重试", onClick = { reloadKey++ })
            }
        }
        ImgLoad.Loading -> NvSkeletonBox(Modifier.fillMaxSize(), corner = 0.dp)
    }
}

/**
 * §八登记 NvZoomableImage：双击 1x↔2.5x（200ms 减速、以双击点为中心）、捏合 1x–5x 跟手、
 * >1x 单指平移（边界夹取阻尼）；scale/offset 状态由调用方持有（NvZoomState）。
 *
 * 画质约定：查看器场景请传显式按原图尺寸构建的 ImageRequest（见 [originalSizeRequest]）——
 * Coil 默认按 composable 尺寸（屏幕像素）降采样解码，1x 正常但捏合 2x–5x 是在放大
 * "已降采样的屏保图"，细节发糊；1x 显示仍由 ContentScale.Fit 缩放，显示尺寸不变。
 */
@Composable
internal fun NvZoomableImage(
    model: Any?,
    zoom: NvZoomState,
    modifier: Modifier = Modifier,
    maxZoom: Float = 5f,
    doubleTapZoom: Float = 2.5f,
    onTap: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        zoom.animJob?.cancel()
                        val target = if (zoom.scale > 1f) 1f else doubleTapZoom
                        val c = Offset(viewport.width / 2f, viewport.height / 2f)
                        val startScale = zoom.scale
                        val startOffset = zoom.offset
                        // 双击点在 fit 坐标里的位置（相对中心），缩放过程中保持其锚定
                        val rel = (pos - c - startOffset) * (1f / startScale)
                        zoom.animJob = scope.launch {
                            animate(
                                startScale, target,
                                animationSpec = tween(NvMotion.DurationNormal, easing = NvMotion.CurveDecelerate),
                            ) { v, _ ->
                                zoom.scale = v
                                zoom.offset = if (target == 1f) Offset.Zero
                                else clampOffset(pos - c - rel * v, v, viewport)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // BUG：原 .transformable 在 1x 也把单指横拖消费掉 → 外层 HorizontalPager
                // 收不到滑动，翻页失灵。自定义手势：捏合始终消费；1x 单指拖动不消费
                //（让给 Pager 翻页）；>1x 平移消费（边界夹取在 clampOffset）。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f || zoom.scale > 1f) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            zoom.animJob?.cancel()
                            val newScale = (zoom.scale * zoomChange).coerceIn(1f, maxZoom)
                            zoom.scale = newScale
                            // 缩放 >1x 才允许平移（松手夹取在边界内）；1x 恒复位
                            zoom.offset = if (newScale > 1f) {
                                clampOffset(zoom.offset + panChange, newScale, viewport)
                            } else {
                                Offset.Zero
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
            contentScale = ContentScale.Fit,
            loading = { NvSkeletonBox(Modifier.fillMaxSize(), corner = 0.dp) },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.BrokenImage, null, Modifier.size(48.dp), tint = NvColors.TextDisabled)
                }
            },
        )
    }
}

/** §八登记 NvImageViewerInfoBar：顶文件名栏 + 底 meta 行（点按不透传，避免误关） */
@Composable
private fun NvImageViewerInfoBar(
    title: String,
    meta: ImageMeta?,
    pageIndicator: String?,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .pointerInput(Unit) { detectTapGestures { } },   // 信息栏点击不透传
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = NvColors.TextPrimary)
                }
                Text(
                    title,
                    Modifier.weight(1f).padding(end = NvSpacing.S),
                    style = NvTypography.TitleMedium,
                    color = NvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(48.dp))   // 平衡返回键，文件名视觉居中
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val metaText = listOfNotNull(meta?.dims, meta?.size).joinToString(" · ")
                Text(
                    metaText,
                    Modifier.weight(1f),
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta?.isGif == true) {
                    Text(
                        "GIF",
                        Modifier
                            .background(NvColors.SurfaceElevated, NvShape.Badge)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = NvTypography.LabelSmall,
                        color = NvColors.TextPrimary,
                    )
                    Spacer(Modifier.width(NvSpacing.S))
                }
                // §八登记 NvPageIndicator：页码 3 / 12（多图时才显示）
                if (pageIndicator != null) {
                    Text(
                        pageIndicator,
                        style = NvTypography.LabelMedium,
                        color = NvColors.TextPrimary,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// ---------- 工具 ----------

/**
 * 原图尺寸解码请求（放大发糊修复）：显式 size 优先于 Coil 按 composable 尺寸的自动降采样，
 * 捏合放大后的像素直接来自原图；1x 显示由 ContentScale.Fit 缩放呈现，显示尺寸不变。
 * 内存保护：解码预算 = 最长边 ≤6000px（5x 放大在 1080p 屏仍锐利）且面积 ≤1600 万像素
 * （单张 ARGB ≈64MB）。HorizontalPager 相邻 ±1 页并发解码，再大的原图会把 native 堆
 * 直接顶穿（LMK/native OOM，NvCrashLog 抓不到），超限按两条约束中更紧的等比降采样。
 */
private const val MAX_DECODE_EDGE = 6000
private const val MAX_DECODE_PIXELS = 16_000_000.0

private fun originalSizeRequest(context: Context, file: File, width: Int, height: Int): ImageRequest {
    val builder = ImageRequest.Builder(context).data(file)
    if (width <= 0 || height <= 0) return builder.size(Size.ORIGINAL).build()
    val maxEdge = maxOf(width, height)
    val pixels = width.toLong() * height.toLong()
    if (maxEdge <= MAX_DECODE_EDGE && pixels <= MAX_DECODE_PIXELS.toLong()) {
        return builder.size(Size.ORIGINAL).build()
    }
    val k = min(
        MAX_DECODE_EDGE.toFloat() / maxEdge,
        sqrt(MAX_DECODE_PIXELS / pixels).toFloat(),
    )
    return builder
        .size((width * k).roundToInt().coerceAtLeast(1), (height * k).roundToInt().coerceAtLeast(1))
        .build()
}

private fun clampOffset(o: Offset, scale: Float, viewport: IntSize): Offset {
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/** uri / 路径 → 文件名（novax:// 内部路径 URL 编码，解码还原中文） */
internal fun nameOf(uri: String): String {
    val raw = uri.substringAfterLast('/').substringAfterLast('\\')
    return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}

/** 字节大小 → 人类可读（1024 进制；查看器 meta 行 / 横幅共用） */
internal fun fmtSizeOf(bytes: Long): String {
    if (bytes <= 0L) return "未知"
    val mb = bytes / 1_048_576.0
    val kb = bytes / 1024.0
    return when {
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
