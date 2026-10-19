package com.novax.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novax.app.ui.theme.NvColors
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass

/** 边缘透镜分档（lens 轮，apple_ref_v2 准绳5「元素越小透镜越强」+ 准绳1「窄带大位移」）：
 *  Bar=大面积栏（顶/底栏等横条面，弱档）；Chip=小元素（胶囊钮/筛选 chip，强档——带宽更窄、
 *  位移/强度/色散更高，参照 crop_numeral_optics 数字笔画的戏剧化透镜）。
 *  定档按**组件类型**在 nvGlassSurface 内部完成（NvGlassChip→Chip，其余默认 Bar），调用方零新参数。
 *  终态取值 = 变体 A（BASE/A/B/C 四联实拍评审胜出，decisions §12.5.2）。
 *  None=返工⑤（2026-10-15 用户第五项反馈）新增：承载文字的弹层面板档——位移/色散全零（文字面板
 *  可读性第一，透镜边带会搅动面板边缘下滚动文字行），blur/填充/光效不受影响。 */
enum class NvGlassLensTier(val band: Dp, val strength: Float, val displacement: Dp, val ca: Float) {
    Bar(band = 8.dp, strength = 0.9f, displacement = 18.dp, ca = 0.25f),     // 终态（变体A 评审胜出）
    Chip(band = 8.dp, strength = 1.0f, displacement = 22.dp, ca = 0.3f),    // 终态（变体A 评审胜出）
    None(band = 0.dp, strength = 0f, displacement = 0.dp, ca = 0f),         // 返工⑤：文字面板关透镜
}

/** 效果+填充收口（2026-10-15 观感返工②后：FULL/BLUR 档纯 blur+tint 无描边；rework3 液态玻璃补
 *  顶缘 GlassSheen 镜面高光——左上光源亮边 3dp 带内衰减，非全域提亮非描边；
 *  lens 轮：补右下 GlassInnerShadow 暗色内影（apple_ref_v2 准绳4 不对称光效——左上亮边+右下暗影），
 *  并按 [NvGlassLensTier] 分档边缘透镜参数。
 *  rework8（2026-10-15 直线残留对账）：**底缘 3dp 暗带全面停画**（矩形/圆角面同判）——用户真机
 *  「玻璃面下方直线残留」对账实证：暗带+hairline 底段在面背衬素底时读作悬浮横线（文件页顶栏下
 *  y268-275 全宽、库页筛选簇下 y382-385，decisions §15.2）；右缘 2dp 半强竖带保留（无横线感知）。
 *  矩形面底边 hairline 描边归零（控制器裁定），圆角面 hairline 轮廓保留（返工⑤/⑥用户认可边界感）。
 *  SCRIM 档填充自动升为 GlassFillStrong（spec §7「仅 SCRIM 档」语义的唯一消费点），
 *  并保留提亮+描边（弹层语义；老设备无 blur，需要描边托边界）；
 *  env=null（未接线域）退化纯调用方 fill 背景。
 *  注意（decisions.md §4.1）：haze 效果节点与其取样源（nvGlassSource）须为兄弟节点，
 *  本修饰符只负责效果侧，不涉及布局结构。
 *  Task 10 实测增补（修复轮 1 定稿；T12 措辞精化）：haze 效果层按节点矩形画——不加收口时
 *  圆形 chip（播放器暂停钮）圆形界外会露方形效果残影（亮内容下像素级复现）。GlassStyle.shape()
 *  **单用不圆化 blur**（javap 确认 API 存在，效果层仍矩形渲染），其职责是遮罩样式 tint 层
 *  （见 nvHazeOrFill 注）；与链首 `.clip(shape)` 前置两件套后整体生效（clip 裁效果层圆界 +
 *  shape() 圆界 tint）。`.clip(shape)` 必须**挂在链首**：Compose 语义 clip 只裁链中其后的
 *  绘制，放 nvHazeOrFill 之后则效果层不受裁（修复轮 1 评审 Important-1）。
 *  已知副作用（decisions.md §4.6）：RectangleShape 下 1dp 发丝描边的外半 0.5dp 被同一裁剪裁掉
 *  （描边以边界为中心画线），实拍观感无退化；RectangleShape 的 clip 本身恒等。 */
@Composable
fun Modifier.nvGlassSurface(
    fill: Color,
    shape: Shape,
    tier: NvGlassLensTier = NvGlassLensTier.Bar,
    // 返工⑤（2026-10-15 用户第三项反馈）：常规档维持 12dp（HIG clear 档）；承载文字的弹层面板
    //（NvPlayerPopup）传 28dp 重磨砂档——可读性优先，玻璃质感靠填充+光效维持
    blurRadius: Dp = 12.dp,
    // 磨砂强填充盖印（返工⑤）：true 时 fill 以原 alpha 盖印在效果层**之上**（drawWithContent），
    // 专供「不透视画面」的磨砂档（播放器中央播放钮 GlassFillStrong / NvPlayerPopup 重磨砂面板）。
    // 依据：haze 2.0.1 FULL 档 GlassStyle.tint() 实测不渲染（emulator-5554 亮/暗双帧取色实证——
    // 效果节点像素=纯 blur 内容色，白帧 255/暗帧=视频原色，tint 数学值均未出现；GlassStyleScope
    // 字节码确认 tint(Color) API 在但运行时未成像，decisions.md §13 追记），磨砂可读性红线不能
    // 押在 shader tint 上，改 Compose 侧盖印。sheen/innerShadow/hairline 仍在盖印之上/描边最上。
    fillOverlay: Boolean = false,
): Modifier {
    val env = LocalNvGlassEnv.current
    val roundedShape = shape as? RoundedCornerShape
    // rework8（2026-10-15 直线残留对账，decisions §15.2）：矩形玻璃面（nvTopBarBackground 唯一
    // FULL 路径消费）底边线消除——emulator-5554 实拍对账：文件页顶栏下方横线=y268-275 全宽暗带
    // （min lum 224 vs 背景 245）、库页筛选簇下直线=y382-385（同构成），均为玻璃面底缘
    // GlassInnerShadow 3dp 暗带+hairline 底段（横幅顶缘 y448 与线相距 63px，排除横幅假说）。
    // 矩形面：底边描边归零（渐变端色→Transparent）+不画底/右暗影；圆角面：hairline 轮廓保留
    // （用户返工⑤/⑥认可边界感），底缘暗带一并摘除（直线感主犯是 3dp 暗带非 1dp hairline；
    // 右缘半强竖带保留——竖向边缘无横线感知，准绳4 右下暗影的竖向分量不动）
    val rectFace = roundedShape == null
    // @Composable getter 须在组合期取值（Global Constraint），先提升再进 drawBehind/border lambda
    val highlight = NvColors.GlassHighlight
    val border = NvColors.GlassBorder
    // 返工⑤：FULL/BLUR 档极细描边端色（GlassBorderHairline；亮色黑基/暗色白基，弱于 GlassBorder）
    val hairline = NvColors.GlassBorderHairline
    // rework3 液态玻璃：顶缘镜面高光端色（GlassSheen）——提亮/暗影须在组合期取值
    val sheen = NvColors.GlassSheen
    val innerShadow = NvColors.GlassInnerShadow
    return this
        // clip 必须在最前（裁效果层 blur；见上注）
        .clip(shape)
        .then(
            when (env?.capability) {
                // 观感返工②：FULL/BLUR 档纯 blur+tint（描边/提亮装饰删除——Apple 参照无描边无提亮）
                // rework3：补回**克制的**顶缘镜面高光（GlassSheen 顶缘 3dp 带内衰减 + 左缘 2dp 弱化）
                // lens 轮：补右下暗色内影（GlassInnerShadow 底缘 3dp 向上衰减 + 右缘 2dp 半强）——
                // 与亮边同构的「暗边」，合成不对称光效（左上主亮/右下主暗，crop_numeral_optics 基准）
                // 返工⑤：恢复 1dp hairline 极细描边（GlassBorderHairline 垂直渐变，顶强底弱×0.4——
                // 与 SCRIM 档同构语言；亮色模式白描边不可辨改黑基，浅背景玻璃边界感回归，亮暗同步挂）
                NvGlassCapability.FULL, NvGlassCapability.BLUR -> Modifier
                    .nvHazeOrFill(fill, roundedShape, tier, blurRadius)
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(sheen, Color.Transparent),
                                startY = 0f,
                                endY = 3.dp.toPx(),
                            ),
                        )
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(sheen.copy(alpha = sheen.alpha * 0.5f), Color.Transparent),
                                startX = 0f,
                                endX = 2.dp.toPx(),
                            ),
                        )
                        // rework8：底缘 3dp 暗带全面停画（直线残留对账结论——两种被投诉横线的共同
                        // 主犯；矩形面/圆角面同判，见上方 rectFace 注）。右缘 2dp 半强竖带保留：
                        // 竖向边缘无横线感知，准绳4「右下暗影」的竖向分量不动。
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, innerShadow.copy(alpha = innerShadow.alpha * 0.5f)),
                                startX = size.width - 2.dp.toPx(),
                                endX = size.width,
                            ),
                        )
                    }
                    // 返工⑤：磨砂盖印先画 fill 再 drawContent——fill 落在效果层+光效之上、
                    // 描边与节点子内容（播放图标/面板文字行）之下（盖印绝不能盖内容）
                    .then(
                        if (fillOverlay) {
                            Modifier.drawWithContent {
                                drawRect(fill)
                                drawContent()
                            }
                        } else {
                            Modifier
                        },
                    )
                    .border(
                        1.dp,
                        // rework8：矩形面底边描边归零（渐变端色→Transparent，顶边/侧边渐弱保留）——
                        // 「RectangleShape 玻璃面不画底边描边」控制器裁定；圆角面轮廓渐变不变（用户认可）
                        Brush.verticalGradient(
                            if (rectFace) listOf(hairline, Color.Transparent)
                            else listOf(hairline, hairline.copy(alpha = hairline.alpha * 0.4f)),
                        ),
                        shape,
                    )
                // SCRIM 档/未接线：背景填充 + 描边提亮保留（SCRIM 填充升 GlassFillStrong）
                else -> Modifier.background(
                    if (env?.capability == NvGlassCapability.SCRIM) NvColors.GlassFillStrong else fill,
                    shape,
                )
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(highlight, Color.Transparent),
                                startY = 0f,
                                endY = size.height * 0.35f,
                            ),
                        )
                    }
                    .border(
                        1.dp,
                        // rework8：矩形面底边描边归零（与 FULL/BLUR 档同判，SCRIM 顶栏老设备同线源）
                        Brush.verticalGradient(
                            if (rectFace) listOf(border, Color.Transparent)
                            else listOf(border, border.copy(alpha = border.alpha * 0.4f)),
                        ),
                        shape,
                    )
            },
        )
}

/** Haze 接线收口（取名避开 haze 库自身 1.x 修饰符 hazeEffect 同名混淆）。@Composable：读 env 的 CompositionLocal。
 *  roundedShape 仅 FULL 档消费：GlassStyleScope.shape(RoundedCornerShape) 遮罩样式 tint 层
 *  （修复轮 1 实测：链首 clip 后 blur 已圆界，但 tint 仍按节点矩形画、5% 残带——shape() 槽
 *  负责遮罩 tint；null=维持库默认）。BLUR 档 HazeBlurStyle 无 shape 槽（31-32 档限制）。 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun Modifier.nvHazeOrFill(
    fill: Color,
    roundedShape: RoundedCornerShape? = null,
    tier: NvGlassLensTier = NvGlassLensTier.Bar,
    blurRadius: Dp = 12.dp,
): Modifier {
    val env = LocalNvGlassEnv.current ?: return this.background(fill)
    val state = env.hazeState as? HazeState ?: return this.background(fill)
    return when (env.capability) {
        NvGlassCapability.FULL ->
            this.hazeGlass(HazeInput.Sources(state), style = glassStyleFull(fill, roundedShape, tier, blurRadius))
        else ->
            this.hazeBlur(HazeInput.Sources(state), style = glassStyleBlur(fill, blurRadius))
    }
}

// 2.x builder 形态（包名按 Task 0 javap 实测钉定：GlassStyle 在 dev.chrisbanes.haze.glass、
// HazeBlurStyle/HazeColorEffect 在 dev.chrisbanes.haze.blur——decisions.md §4.2/§4.3）
// 观感返工②（2026-10-15）：样式解析序 = GlassStyle.regular → local → explicit（haze 源码
// resolveGlassStyleValues 实证），explicit 只覆写列出的成员。这里把 regular 预置的材质光照/
// 镜面/折射/色彩grade归零，只留 tint+blur（optics() 作用域函数省参会把 blurRadius 重置为
// Fixed 14dp，故用 GlassDefaults.optics.copy 仅覆写列出的位）。
// 精修轮 rework2（2026-10-15，HIG《Adopting Liquid Glass》clear 档）：blur 从 regular 预置的
// Responsive 20-25dp 降到 **Fixed 12dp**——HIG「材料让位内容」+ clearGlass 变体（更透更少模糊，
// 媒体库类应用适用档）；BLUR 档 hazeBlur 同步降档同值。
// rework3 液态玻璃（2026-10-15 用户原话「苹果玻璃不是单纯的玻璃，有液态玻璃效果」）：补液态三要素。
// 折射用 **RefractionProfile.Edge(width)** 边缘透镜——Haze 2.0.1 AGSL shader 实证（GlassShaders
// 字节码内嵌源码）：`edgeRefractionWidth >= 0` 分支下 `opticalDistance >= edgeRefractionWidth 即
// return vec2(0.0)`，位移严格局域在边带内、中心位移恒零（中心内容完全不动，不泛浑）；默认的
// RefractionProfile.Surface 才是全域位移（rework2 试拍 3dp「无增益且泛浑」的根因即 Surface 全域）。
// lens 轮（2026-10-15，apple_ref_v2 参照库重调）：旧 Edge(20dp)/0.6/12dp 环带占 72dp 栏高 28%，
// 「糊边不像透镜」——按准绳1 收窄带宽、加大带内位移（分档见 NvGlassLensTier，变体试参记录见
// decisions §12）；色散随位移联动 0.2-0.3 域。
@OptIn(ExperimentalHazeApi::class)
private fun glassStyleFull(
    fill: Color,
    roundedShape: RoundedCornerShape?,
    tier: NvGlassLensTier,
    blurRadius: Dp = 12.dp,
) = GlassStyle {
    tint(fill)
    if (roundedShape != null) shape(roundedShape)
    // blur 常规 Fixed 12dp（HIG clear 档起步值，实拍回归见 §11）；弹层面板档 28dp（返工⑤重磨砂）；
    // 边缘透镜 Edge 带宽/位移按分档（lens 轮：窄带 8-12dp + 带内大位移 14-22dp——apple_ref_v2 准绳1
    // 「环带 ≈ 元素高 5-8%」，20dp 旧带占 72dp 栏高 28% 是「糊边不像透镜」根因；Edge 下中心位移恒零
    // 红线不破坏；None 档 strength/CA=0 全关——文字面板可读性第一，返工⑤）
    optics(
        GlassDefaults.optics.copy(
            blurRadius = OpticalSizeValue.Fixed(blurRadius),
            refractionStrength = tier.strength,
            refractionDisplacement = tier.displacement,
            refractionProfile = dev.chrisbanes.haze.glass.RefractionProfile.Edge(tier.band),
        ),
    )
    specularIntensity(0f)          // 镜面高光（库内置材质面光源——亮边由 nvGlassSurface GlassSheen 自绘，见上）
    ambientResponse(0f)            // 环境光响应+Fresnel
    edgeShadow(Color.Transparent)  // directional 暗边（regular 暗 0.32/亮 0.14 alpha）
    whitePoint(0f)                 // 色彩 grade：regular 亮 0.55/暗 -0.22（「白板感」来源之一）
    chromaMultiplier(1f)           // 色度增益：regular 1.5/1.1 → 1（透内容原色）
    contrast(0f)
    contentNormalBlend(0f)
    chromaticAberrationStrength(tier.ca)  // 色散随带内位移联动（0.2-0.3 域，边缘细看有分离不花；None=0）
    chromaticAberrationMode(dev.chrisbanes.haze.glass.ChromaticAberrationMode.Simple)
}
private fun glassStyleBlur(fill: Color, blurRadius: Dp = 12.dp) =
    HazeBlurStyle {
        colorEffects(listOf(HazeColorEffect.tint(fill)))
        blurRadius(blurRadius)  // 常规 12dp（rework2 与 FULL 档同档）；弹层面板档 28dp（返工⑤）
        noiseFactor(0f)   // 默认 0.15 噪声点阵（HazeBlurDefaults.noiseFactor）归零
    }

// 分支 B（1.6.10 退路，按 decisions.md 执行）：nvHazeOrFill 的两分支统一替换为
//   this.hazeEffect(state) { style = dev.chrisbanes.haze.materials.HazeStyle(backgroundColor = Color.Transparent, tints = listOf(dev.chrisbanes.haze.HazeTint(fill))) }
// （1.x HazeStyle.tints 类型为 List<HazeTint>，需 HazeTint(fill) 包装）
