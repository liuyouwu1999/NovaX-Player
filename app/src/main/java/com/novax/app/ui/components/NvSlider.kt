package com.novax.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors

/** 统一滑杆内核(2026-10-10 S2 视觉重做,spec §2.2):M3 Slider 的手势/步进吸附/无障碍语义
 *  全保留,thumb/track 槽位自绘——金属旋钮(drawNvKnob)+ 凹槽渐变轨(drawNvGrooveTrack/
 *  drawNvActiveTrack)+ 步进刻度点。视觉高 28dp、触控目标抬 48dp(minimumInteractiveComponentSize,
 *  子内容按原约束居中测量不变形)。steps 刻度沿用本组件参数自绘,不依赖 SliderState.steps;
 *  进度分数读 SliderState.value(material3 1.3.2 公开 API,BOM 2025.09.00 aar 实证)。
 *  槽位重载带 @ExperimentalMaterial3Api(material3 1.3.2 aar 实证),显式 OptIn(仓库惯例,
 *  Sheets.kt:81 同)。
 *  回退口径:若槽位签名编译不符,thumb/track 内改读本组件 value 参数(6 个调用点均为
 *  onValueChange 回写模式,值同步已验证)。
 *  6 个调用点(设置页/SpeedSheet 细调/RMVB 细调/投屏×2/PDF)自动继承新视觉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NvSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // DrawScope 非 Composable:token 提升局部
    val knobHighlight = NvColors.KnobHighlight
    val knobBase = NvColors.KnobBase
    val knobEdge = NvColors.KnobEdge
    val knobRim = NvColors.KnobRim
    val track = NvColors.Track
    val primary = NvColors.Primary
    val primaryPressed = NvColors.PrimaryPressed
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier.minimumInteractiveComponentSize().height(28.dp),
        interactionSource = interactionSource,
        thumb = {
            Canvas(Modifier.size(20.dp)) {
                drawNvKnob(
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f,
                    highlight = knobHighlight,
                    base = knobBase,
                    edge = knobEdge,
                    rim = knobRim,
                )
            }
        },
        track = { sliderState ->
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                // 2026-10-10 终审 M-1:零宽区间(如 PdfViewer 单页传 0f..0f)防 NaN
                val span = valueRange.endInclusive - valueRange.start
                val fraction = if (span > 0f) {
                    ((sliderState.value - valueRange.start) / span).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val th = 5.dp.toPx()
                val yCenter = size.height / 2f
                val topLeft = Offset(0f, yCenter - th / 2f)
                drawNvGrooveTrack(track, topLeft, Size(size.width, th))
                drawNvActiveTrack(primary, primaryPressed, topLeft, Size(size.width * fraction, th))
                // 步进刻度点(内点,2dp;thumb 会盖住当前位附近刻度,M3 同观感)
                if (steps > 0) {
                    val dotR = 1.dp.toPx()
                    val dotColor = track.copy(alpha = (track.alpha * 1.8f).coerceAtMost(1f))
                    for (i in 1..steps) {
                        drawCircle(dotColor, dotR, Offset(size.width * i / (steps + 1f), yCenter))
                    }
                }
            }
        },
    )
}
