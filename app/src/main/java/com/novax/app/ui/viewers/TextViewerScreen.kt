@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.ui.viewers

import android.widget.Toast
import com.novax.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvErrorState
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSkeletonBox
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTextButton
import com.novax.app.ui.components.NvTopBar
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.glass.nvGlassSource
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * C9 §四 文本阅读器：
 * - 流式读前 2MB（>2MB 截断降级：只解码前 512KB + 底部横幅「完整打开」外发）；
 * - 编码自动检测（BOM → 严格 UTF-8 → GBK 启发式 → ISO-8859-1），顶部 Chip 可手选重载（内存字节重解码，不重下）；
 * - 等宽/无衬线切换 + 自动换行开关（换行关 = 单行 + 整体横向滚动，看全行）；
 * - 正文 LazyColumn 按行渲染，仅可见行参与组合；
 * - 状态：加载骨架 / 读取失败重试 / 二进制拒绝（外发） / 空文件 / 截断横幅。
 */
@Composable
fun TextViewerScreen(
    input: ViewerInput,
    sizeBytes: Long,
    onBack: () -> Unit,
    onOpenWith: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var loadKey by remember { mutableIntStateOf(0) }
    var manualCharset by remember { mutableStateOf<NvTextCharsetOption?>(null) }   // null = 自动检测
    var mono by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var showEncoding by remember { mutableStateOf(false) }

    // 流式读取（最多 2MB+1：读超 CAP 即判大文件；编码探测只用前 8KB）
    val read by produceState<TextRead>(TextRead.Loading, input, loadKey) {
        value = TextRead.Loading
        val result = withContext(Dispatchers.IO) {
            runCatching {
                openViewerStream(context, input).use { ins ->
                    val out = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (total <= NvTextLimits.READ_CAP_BYTES) {
                        val want = minOf(chunk.size, NvTextLimits.READ_CAP_BYTES + 1 - total)
                        val n = ins.read(chunk, 0, want)
                        if (n <= 0) break // n==0 防自旋（2026-09-16 审计同型位点）
                        out.write(chunk, 0, n)
                        total += n
                    }
                    TextRaw(out.toByteArray(), total)
                }
            }
        }
        value = result.fold(
            onSuccess = { TextRead.Done(it) },
            onFailure = { TextRead.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    // 解码（编码 Chip 切换时仅重解码内存字节，立即生效）
    // 2026-09-15 修复：decode+split 2MB 文本在组合期主线程执行会卡数百毫秒 → 挪进 produceState 的 Default 段
    val doc by androidx.compose.runtime.produceState(initialValue = null as TextDoc?, read, manualCharset) {
        val done = read as? TextRead.Done ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bytes = done.raw.bytes
            val headLen = minOf(bytes.size, NvTextLimits.DETECT_HEAD_BYTES)
            val detected = NvTextCodec.detectCharset(bytes, headLen)
            val option = manualCharset ?: NvTextCharsetOption.of(detected)
            val keep = if (NvTextLimits.isOversize(done.raw.readCount)) {
                bytes.copyOf(NvTextLimits.TRUNCATE_KEEP_BYTES)
            } else {
                bytes
            }
            var text = keep.toString(option.charset)
            if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)   // 去 BOM 字符
            TextDoc(
                option = option,
                lines = text.split('\n').map { it.trimEnd('\r') },
                truncated = NvTextLimits.isOversize(done.raw.readCount),
                binary = NvTextCodec.isBinary(bytes, headLen),
                empty = done.raw.readCount == 0,
            )
        }
    }

    // Liquid Glass（decisions.md §4.5）：source 挂内容容器（文本滚动列所在），NvTopBar 悬浮做兄弟；
    // 背景在 source 之后（§4.5a：hazeSource 只录制链中位于其后的绘制，栏后区域须有源像素）
    val glassEnv = com.novax.app.ui.glass.LocalNvGlassEnv.current
    val topInset = com.novax.app.ui.glass.rememberNvTopBarInset()
    // 观感返工①（2026-10-15）：正文列表滚动态上提（原 TextViewCodeArea 内部私有），顶栏渐显接线
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by com.novax.app.ui.components.rememberNvListScrolled(listState)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .nvGlassSource(glassEnv)
                .background(NvColors.Background),
        ) {
            when {
                read is TextRead.Loading -> Column(Modifier.padding(top = topInset).padding(NvSpacing.L)) {
                    // §4.2 加载骨架：6 行长条错落
                    repeat(6) { i ->
                        NvSkeletonBox(Modifier.fillMaxWidth(if (i % 3 == 2) 0.6f else 0.9f).height(20.dp), corner = 4.dp)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                read is TextRead.Failed -> Box(
                    Modifier.fillMaxSize().padding(top = topInset).padding(horizontal = 40.dp),
                ) {
                    NvErrorState(
                        title = stringResource(R.string.misc_text_read_failed),
                        message = (read as TextRead.Failed).message,
                        onRetry = { loadKey++ },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                doc?.binary == true -> Box(
                    Modifier.fillMaxSize().padding(top = topInset),
                    contentAlignment = Alignment.Center,
                ) {
                    NvEmptyState(
                        Icons.Outlined.HelpOutline, stringResource(R.string.misc_text_binary),
                        actionText = stringResource(R.string.misc_open_with),
                        onAction = { onOpenWith(input.toUriString(), input.title) },
                    )
                }
                doc?.empty == true -> Box(
                    Modifier.fillMaxSize().padding(top = topInset),
                    contentAlignment = Alignment.Center,
                ) {
                    NvEmptyState(Icons.Outlined.Description, stringResource(R.string.misc_text_empty))
                }
                doc != null -> TextViewCodeArea(doc!!, mono, wrap, topInset, listState)
            }

            // §八登记 NvTruncateBanner：>2MB 截断提示 + 完整打开（外发系统处理）。
            // 悬浮翻转后挂内容 Box 底部（原 Column 内排在 fillMaxSize 正文之后被挤出屏外，
            // 翻转顺带对齐其「底部横幅」设计语义）
            if (doc?.truncated == true) {
                NvTruncateBanner(
                    onOpenWith = { onOpenWith(input.toUriString(), input.title) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // §八登记 NvTextViewerTopBar：返回 + 文件名 + 编码 Chip + 等宽/换行开关（悬浮常驻）
        NvTopBar(
            title = input.title,
            onBack = onBack,
            scrolled = scrolled,   // 观感返工①
            actions = listOf(
                {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .clip(NvShape.Button)
                                .border(1.dp, NvColors.Outline, NvShape.Button)
                                .clickable { showEncoding = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (read is TextRead.Done) {
                                    doc?.option?.label ?: stringResource(R.string.misc_text_encoding)
                                } else {
                                    "…"   // 省略号为语言中性标点，非文案
                                },
                                style = NvTypography.LabelMedium,
                                color = NvColors.TextSecondary,
                            )
                        }
                    }
                },
                {
                    IconButton(onClick = { mono = !mono }, Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Code, stringResource(R.string.misc_text_mono),
                            Modifier.size(24.dp), tint = if (mono) NvColors.Primary else NvColors.TextPrimary,
                        )
                    }
                },
                {
                    IconButton(onClick = { wrap = !wrap }, Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.WrapText, stringResource(R.string.misc_text_wrap),
                            Modifier.size(24.dp), tint = if (wrap) NvColors.Primary else NvColors.TextPrimary,
                        )
                    }
                },
            ),
        )
    }

    // §八登记 NvEncodingSheet：编码单选弹层（选中 ✓，选择后立即以新编码重载）
    if (showEncoding) {
        NvBottomSheet(onDismiss = { showEncoding = false }) {
            Text(
                stringResource(R.string.misc_text_choose_encoding),
                Modifier.padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
                style = NvTypography.TitleMedium,
            )
            NvTextCharsetOption.entries.forEach { opt ->
                NvSheetListItem(
                    opt.label,
                    selected = doc?.option == opt,
                    onClick = {
                        showEncoding = false
                        val cur = doc
                        if (cur != null && cur.option != opt) {
                            manualCharset = opt
                            Toast.makeText(
                                context,
                                context.getString(R.string.misc_text_reloaded, opt.label),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}

/** §八登记 NvTextCodeArea：LazyColumn 按行正文区（等宽/无衬线；换行关 = 单行 + 横向滚动）。
 *  悬浮顶栏翻转：列表全高、首行经 contentPadding 避让顶栏——滚动时行钻入顶栏后透进玻璃（§4.5 配方） */
@Composable
private fun TextViewCodeArea(
    doc: TextDoc,
    mono: Boolean,
    wrap: Boolean,
    topInset: Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,   // 观感返工①：屏级上提
) {
    val hScroll = rememberScrollState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(if (!wrap) Modifier.horizontalScroll(hScroll) else Modifier),
        contentPadding = PaddingValues(
            start = NvSpacing.L,
            top = topInset + NvSpacing.S,
            end = NvSpacing.L,
            bottom = NvSpacing.S,
        ),
    ) {
        items(
            count = doc.lines.size,
            key = { it },
        ) { i ->
            Text(
                doc.lines[i],
                style = if (mono) {
                    NvTypography.BodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    NvTypography.BodyMedium
                },
                color = NvColors.TextPrimary,
                softWrap = wrap,
                maxLines = if (wrap) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun NvTruncateBanner(onOpenWith: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(NvColors.Surface)
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = NvColors.Secondary)
        Spacer(Modifier.width(NvSpacing.S))
        Text(
            stringResource(R.string.misc_text_truncated, NvTextLimits.TRUNCATE_KEEP_BYTES / 1024),
            Modifier.weight(1f),
            style = NvTypography.LabelMedium,
            color = NvColors.TextSecondary,
        )
        NvTextButton(stringResource(R.string.misc_text_open_full), onClick = onOpenWith)
    }
}

// ---------- 内部模型 ----------

private data class TextRaw(val bytes: ByteArray, val readCount: Int)

private sealed interface TextRead {
    data object Loading : TextRead
    data class Done(val raw: TextRaw) : TextRead
    data class Failed(val message: String) : TextRead
}

private data class TextDoc(
    val option: NvTextCharsetOption,
    val lines: List<String>,
    val truncated: Boolean,
    val binary: Boolean,
    val empty: Boolean,
)
