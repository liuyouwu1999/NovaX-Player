package com.novax.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novax.app.data.db.ThumbState
import com.novax.app.ui.glass.nvGlassSurface
import com.novax.app.ui.theme.NvColors

/**
 * C12 §4.3 真实媒体海报卡（DESIGN-SYSTEM §三 已登记，包装 NvPosterCard）。
 *
 * thumbState 状态机（写入点唯一：claimThumb CAS / updateThumb 终态 / resetThumb，C12 §三.5）：
 * - DONE 且 thumbnailPath 非空 → 真帧 200ms Crossfade 淡入（NvPosterCard posterFile 分支）；
 * - RUNNING → NvSkeletonBox shimmer（海报比例，S8）；
 * - NONE → 同骨架色块，进入可视组合即经 [onNeedThumb] 回调触发抽帧
 *   （CAS 认领与并发去重在数据层 NvThumbHolder.requestThumb 内，重复回调安全）；
 * - FAILED → NvPosterFallback 首字母渐变（S9，绝不留白；长按「重新抽帧」可复位重抽）。
 *
 * 抽帧回流零手动刷新：requestThumb 回写经 Room InvalidationTracker → 库页 Flow 自动重发射。
 */
@Composable
fun NvMediaPosterCard(
    title: String,
    thumbnailPath: String?,
    thumbState: ThumbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    onNeedThumb: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    // 可见项触发（C12 §3.8）：NONE 且无路径时请求抽帧；key 带状态避免重组风暴，
    // RUNNING 去重由数据层 claimThumb CAS（NONE→RUNNING 仅成功一次）+ gate inFlight 双保险。
    // 2026-09-18：FAILED 也回调——数据层（requestThumb）只放行 libVLC 兜底扩展名
    //（RM/RMVB/WMV，每会话一次），其余 FAILED 在数据层原样拒绝，不会形成重试风暴
    LaunchedEffect(title, thumbnailPath, thumbState) {
        if (thumbnailPath == null && (thumbState == ThumbState.NONE || thumbState == ThumbState.FAILED)) {
            // 2026-09-21 审计 P3：原 Log.w 每卡一条（release 全保留、快速滚动刷屏）→ Log.d
            android.util.Log.d("NvThumb", "card needThumb fire: state=$thumbState")
            onNeedThumb?.invoke()
        }
    }
    when {
        thumbnailPath != null && thumbState == ThumbState.DONE ->
            NvPosterCard(
                title = title,
                subtitle = subtitle,
                posterFile = java.io.File(thumbnailPath),
                progress = progress,
                badge = badge,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
            )
        thumbState == ThumbState.RUNNING || thumbState == ThumbState.NONE ->
            // S8：CAS 后即 RUNNING，shimmer 不闪断；标题文字同位保留，网格高度稳定
            Column(modifier.widthIn(min = 110.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(NvShape.Poster),
                ) {
                    NvSkeletonBox(Modifier.fillMaxSize(), corner = 12.dp)
                }
                Spacer(Modifier.height(NvSpacing.S))
                Text(
                    title,
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = NvTypography.LabelSmall,
                        color = NvColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        else ->
            // FAILED（S9）→ posterUrl=null 落 NvPosterFallback 首字母渐变
            NvPosterCard(
                title = title,
                subtitle = subtitle,
                posterUrl = null,
                progress = progress,
                badge = badge,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
            )
    }
}

/**
 * 扫描进度横幅（2026-09-15 重做，评审 P3）：两行卡片——
 * 第 1 行「正在扫描 {源名} · 已扫 N 目录 · 发现 M 个」（计数前置，永不被路径挤掉）；
 * 第 2 行当前目录（LabelSmall 弱化，单行省略）；底部 2dp 不定向进度条（BFS 无总数，诚实表达进行中）。
 * × = 取消扫描（48dp 热区保留）。库页/首页共用。
 */
@Composable
fun NvScanStatusRow(
    title: String,
    modifier: Modifier = Modifier,
    path: String? = null,
    note: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NvSpacing.L, vertical = NvSpacing.S)
            // 2026-10-19 用户反馈：扫描行改玻璃面（与顶/底栏同管线），不再实心板
            .nvGlassSurface(NvColors.GlassFill, NvShape.Poster)
            .padding(start = NvSpacing.M, end = NvSpacing.XS, top = NvSpacing.S, bottom = NvSpacing.XS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 16dp 小进度圈（NvLoader 固定 24dp 不适配行内）
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = NvColors.Primary,
            )
            Spacer(Modifier.width(NvSpacing.S))
            Text(
                buildString {
                    append(title)
                    note?.let { append(" · ").append(it) }
                },
                modifier = Modifier.weight(1f),
                style = NvTypography.LabelMedium,
                color = NvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "停止扫描",
                        modifier = Modifier.size(18.dp),
                        tint = NvColors.TextSecondary,
                    )
                }
            }
        }
        if (!path.isNullOrBlank()) {
            Text(
                path,
                modifier = Modifier.padding(start = 16.dp + NvSpacing.S),
                style = NvTypography.LabelSmall,
                color = NvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NvSpacing.XS)
                .height(2.dp),
            color = NvColors.Primary,
            trackColor = NvColors.Track,
        )
    }
}
