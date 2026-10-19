package com.novax.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// rework3（2026-10-15 用户反馈①）：主 Tab 列表页顶栏下滑收起/上滑出现——M3 enterAlways 语义
// + YouTube 实际行为（筛选行/分段控件钉住不收，收的只是 NvTopBar 整条）。rework3 追平：库页
// 用户真机裁定推翻「筛选行钉住」初判——筛选栏并入同一收起容器（顶簇整体挂 nvBarCollapse，
// 同一 graphicsLayer 同步滑出/滑回），其余五屏仍只收顶栏。
// 与既有滚动渐显（TopBar.kt rememberNvListScrolled → NvTopBar(scrolled) 背景层 alpha）正交：
// 收起中/收起后两者叠加不冲突——收起是整条 graphicsLayer 位移+淡出，优先级天然更高。
// 内容 topInset（rememberNvTopBarInset）不改：内容本就从栏下滚过，收起不动布局零跳动。
private const val NV_BAR_ITEM_STRIDE = 4096

// 累计位置合成步长：须显著大于任一列表单项的最大像素高（海报卡 ~240dp·3.5x ≈ 840px），
// index 进位（offset 归零）后 acc 仍单调不减，方向判定不失真。
const val NV_BAR_COLLAPSE_THRESHOLD_PX = 80

/** rework3：收起方向的下行累计阈值（原始 px，沿 rememberNvListScrolled 的 px 口径先例；decisions §10 遗留 dp 化 follow-up 一并覆盖时再统一）。 */
private class NvBarCollapseHolder {
    val collapsed: MutableState<Boolean> = mutableStateOf(false)
    var lastAcc by mutableIntStateOf(0)
    var anchor by mutableIntStateOf(0)

    /** 每个滚动帧调用（snapshotFlow 逐帧收集，独立于调用方重组时机）。
     *  方向判定：任一上行→立即出现（YouTube enterAlways 手感）；下行累计越过阈值→收起；
     *  列表回顶部→恒显示。只在状态翻转时写 collapsed（State），acc/anchor 为普通 var 不参与订阅。 */
    fun update(acc: Int, atIndex: Int, atOffset: Int) {
        if (acc == lastAcc) return
        when {
            atIndex == 0 && atOffset == 0 -> {
                collapsed.value = false
                anchor = acc
            }
            acc < lastAcc -> {          // 上行：立即出现，锚点重置到当前位置
                collapsed.value = false
                anchor = acc
            }
            acc - anchor >= NV_BAR_COLLAPSE_THRESHOLD_PX -> collapsed.value = true  // 下行累计越过阈值
        }
        lastAcc = acc
    }
}

/** rework3 enterAlways 收起信号（主 Tab 列表页专用；二级推入页/DetailScreen/player 不接）。
 *  驱动：snapshotFlow 每滚动帧发射累计位置→holder.update（**不可用 derivedStateOf 订阅**：
 *  其派生值若恒等则读者永不失效，纯滚动帧不触发调用方重组时 update 不会被调——实拍复现
 *  「列表中段上滑顶栏不回弹」后改 snapshotFlow，见 decisions §12）。 */
@Composable
fun rememberNvBarCollapseState(listState: LazyListState): State<Boolean> {
    val holder = remember(listState) { NvBarCollapseHolder() }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            holder.update(index * NV_BAR_ITEM_STRIDE + offset, index, offset)
        }
    }
    return holder.collapsed
}

/** rework3：LazyGridState 版（库页网格/剧集组网格视图；offset 语义同为首可见项像素偏移）。
 *  与 List 版共用 holder 逻辑；两态并存时消费方按当前视图择一（LibraryHeader 先例 headerScrolled）。 */
@Composable
fun rememberNvBarCollapseState(gridState: androidx.compose.foundation.lazy.grid.LazyGridState): State<Boolean> {
    val holder = remember(gridState) { NvBarCollapseHolder() }
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            holder.update(index * NV_BAR_ITEM_STRIDE + offset, index, offset)
        }
    }
    return holder.collapsed
}

/** rework3：整条栏滑出+淡出（收起）动画修饰符。graphicsLayer 位移不改布局（零跳动），
 *  且变换后的命中测试随层走——完全收起后动作钮不可误触。tween 200ms（DurationNormal）。 */
@Composable
fun Modifier.nvBarCollapse(collapsed: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(NvMotion.DurationNormal, easing = NvMotion.CurveDecelerate),
        label = "nvBarCollapse",
    )
    return graphicsLayer {
        translationY = -progress * size.height
        alpha = 1f - progress
    }
}
