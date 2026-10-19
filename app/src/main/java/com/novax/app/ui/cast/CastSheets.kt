@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Replay30
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.novax.app.data.cast.CastDevice
import com.novax.app.data.cast.CastSessionHolder
import com.novax.app.data.cast.CastUiState
import com.novax.app.ui.components.NvBottomSheet
import com.novax.app.ui.components.NvEmptyState
import com.novax.app.ui.components.NvPrimaryButton
import com.novax.app.ui.components.NvSecondaryButton
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSlider
import com.novax.app.ui.theme.NvColors
import com.novax.app.ui.components.NvSheetListItem
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.files.NvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * C13 投屏 UI（spec C13 §5）：设备选择弹层 + 遥控面板。
 * 视觉全走 Nv token（浅深双主题自动成立）；会话状态来自 CastSessionHolder 单例。
 */

/** 设备选择弹层（调用方按需组合显示；onCast 在用户选中设备后回调） */
@Composable
fun NvCastDeviceSheet(
    onDismiss: () -> Unit,
    onCast: (CastDevice) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // init 幂等且同步——必须发生在组合期，不能用 LaunchedEffect（晚于首帧，
    // 首次组合拿到 null discovery 后无重组触发，设备列表永远空白）
    // S2-F1（2026-10-09 审计 P1）：init 只建会话对象零网络副作用；发现栈改由
    // ensureDiscovery 在面板真正打开（用户明确投屏意图）时按需拉起，不再随
    // CastOverlay 的组合期 init 常驻整个进程
    remember {
        CastSessionHolder.init(context)
        CastSessionHolder.ensureDiscovery()
    }
    // 面板离组（关闭/投出/取消计量确认）即回收发现栈；活跃会话由 Holder 内守卫绝不停
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { CastSessionHolder.stopDiscoveryIfIdle() }
    }

    val discovery = CastSessionHolder.discovery()
    val devices = if (discovery != null) {
        discovery.devices.collectAsState().value
    } else {
        emptyList()
    }
    var manualUrl by remember { mutableStateOf("") }
    // 返工⑮（2026-10-16 用户真机「点投屏设备后卡一下无反馈，会连点」）：点击瞬间行内转
    // loading（转圈+「正在连接 XXX…」）、列表整体禁点——可视反馈先于一切异步（计量确认/
    // 片源预处理/连接握手最长可 ~50s）；被点行在连接结束（面板自动弹/失败 toast）随面板
    // 关闭一并消失，无需手动复位
    var connectingUdn by remember { mutableStateOf<String?>(null) }

    NvBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = NvSpacing.L)) {
            Text("投到电视", style = NvTypography.TitleMedium, color = NvColors.TextPrimary)
            Spacer(Modifier.height(NvSpacing.S))

            if (devices.isEmpty()) {
                NvEmptyState(
                    icon = Icons.Outlined.Cast,
                    title = "没有发现电视",
                    description = "确认电视与手机连同一 Wi-Fi，电视端开启 DLNA/多屏互动",
                )
                // E3-7（发版审查）：发现无终态上报，恒转圈与「没有发现电视」自相矛盾——
                // 空态 10 秒后转圈淡出，止于引导文案（不改发现层）
                var scanTimedOut by remember { mutableStateOf(false) }
                LaunchedEffect(devices) {
                    scanTimedOut = false
                    if (devices.isEmpty()) {
                        delay(10_000)
                        scanTimedOut = true
                    }
                }
                if (!scanTimedOut) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(NvSpacing.S))
                        Text("正在扫描…", style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                    }
                }
            } else {
                devices.forEach { device ->
                    if (connectingUdn == device.udn) {
                        // 返工⑮：被点行转 loading——转圈+「正在连接 XXX…」，同帧可视
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(horizontal = NvSpacing.L),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(NvSpacing.S))
                            Text(
                                "正在连接 ${device.name}…",
                                style = NvTypography.BodyMedium,
                                color = NvColors.TextSecondary,
                            )
                        }
                    } else {
                        // 2026-09-28 审计 C8：DLNA 固有信任问题——设备表收任意 LAN 广播，恶意设备
                        // 可伪装「客厅电视」骗流。长按显示控制地址 host 供用户辨识（应用侧唯一可行的缓解）
                        NvSheetListItem(
                            text = device.name,
                            selected = false,
                            enabled = connectingUdn == null,
                            onLongClick = {
                                val host = runCatching { android.net.Uri.parse(device.controlUri).host }.getOrNull() ?: "?"
                                android.widget.Toast.makeText(context, "控制地址：$host", android.widget.Toast.LENGTH_LONG).show()
                            },
                        ) {
                            // 同步置位（本帧即转 loading），连接动作异步发起
                            connectingUdn = device.udn
                            scope.launch { onCast(device) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(NvSpacing.S))
            // 手动添加：空态兜底 + 模拟器测试通道（spec C13 §5/§8）
            // N1（2026-10-09 批次二）：原生 OutlinedTextField → 统一 NvTextField
            NvTextField(
                label = "手动添加",
                value = manualUrl,
                onValueChange = { manualUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "描述 XML 地址 http://…/desc.xml",
            )
            NvPrimaryButton(
                text = "添加并投屏",
                enabled = manualUrl.isNotBlank() && connectingUdn == null,
                loading = connectingUdn != null,
                onClick = {
                    val url = manualUrl.trim()
                    if (url.isNotEmpty()) {
                        scope.launch {
                            val added = discovery?.manualAdd(url)
                            if (added == null) {
                                android.widget.Toast.makeText(
                                    context, "添加失败：地址无效或非渲染器", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                connectingUdn = added.udn
                                onCast(added)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(NvSpacing.M))
        }
    }
}

/**
 * 遥控面板：投屏中的播放/暂停/拖进度/±30s/音量/拉回本机（spec C13 §5）。
 *
 * 返工⑮（2026-10-16 用户真机三项）：宿主从 ModalBottomSheet 改为**原地覆盖层**——
 * 真机实证 heavyGlass 档在 ModalBottomSheet 里就是实心白板（用户截图）：M3 弹层开独立
 * 窗口，haze 玻璃模糊取的是同窗口源录制的回放，跨窗口取不到主界面内容 → 重磨砂静默
 * 退化为 GlassFillStrong 实底（decisions §4.1 同类边界、§25 裁定）。本面板现在由
 * CastOverlay（MainActivity 内容同窗口的根 Box 内）原地渲染：半透明 scrim（点击关面板，
 * 隐藏时整体不组合=不拦截触摸）+ 底部锚定玻璃面板（popupGlassBackground 同款重磨砂：
 * GlassFillStrong + blur Fixed 28dp + 透镜关档，BottomSheet 圆角保留）。
 * 返回键关面板（BackHandler，ModalBottomSheet 同语义）；顶部拖把手 + L 级留白
 * （§2.11 面板间距，真机「设备名贴顶」修复）。
 */
@Composable
fun NvCastRemotePanel(
    onDismiss: () -> Unit,
    onPullBack: () -> Unit,
    onNext: (() -> Unit)? = null,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val session = CastSessionHolder.session()
    val state = session?.state?.collectAsState()?.value ?: CastUiState.Idle

    // 拖动本地回显：拖动期间不改服务端，松手才 seek
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // 返回键先关面板（ModalBottomSheet 同语义）
    androidx.activity.compose.BackHandler(onBack = onDismiss)

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // 半透明 scrim：点击空白关面板
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        )
        // 底部锚定玻璃面板：锚点与 chip 同一（bottomPadding + M，浮在底栏胶囊上方，
        // 全程在内容层内不与 Scaffold bottomBar 争 z 序）；面板本体吃掉点击不漏 scrim
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottomPadding + NvSpacing.M)
                .then(com.novax.app.ui.components.popupGlassBackground(NvShape.BottomSheet))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            // 拖把手 + L 级顶部留白（§2.11；真机「设备名几乎贴面板上缘」修复：
            // 面板上缘 → 把手 8dp + 4dp 高 + 16dp → 设备名）
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = NvSpacing.S, bottom = NvSpacing.L)
                    .size(32.dp, 4.dp)
                    .clip(NvShape.DividerHandle)
                    .background(NvColors.Divider),
            )
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .verticalScroll(scroll)
                    .padding(horizontal = NvSpacing.L)
                    .padding(bottom = NvSpacing.XXL),
            ) {
                val active = activeOf(state)
                if (active == null) {
                    Text(
                        when (state) {
                            is CastUiState.Error -> state.message
                            is CastUiState.Connecting -> "正在连接 ${state.device.name}…"
                            else -> "投屏已结束"
                        },
                        style = NvTypography.BodyMedium,
                        color = NvColors.TextSecondary,
                    )
                    Spacer(Modifier.height(NvSpacing.M))
                } else {
                    val paused = state is CastUiState.Paused
                    Text(active.device.name, style = NvTypography.TitleMedium, color = NvColors.TextPrimary)
                    Text(active.media.title, style = NvTypography.LabelMedium, color = NvColors.TextSecondary)
                    Spacer(Modifier.height(NvSpacing.M))

                    val total = active.durationMs.coerceAtLeast(1L)
                    val fraction = if (dragging) dragFraction else (active.positionMs.toFloat() / total).coerceIn(0f, 1f)
                    NvSlider(
                        value = fraction,
                        onValueChange = { dragging = true; dragFraction = it },
                        onValueChangeFinished = {
                            if (dragging) {
                                session?.seekTo((dragFraction * total).toLong())
                                dragging = false
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatMs(active.positionMs), style = NvTypography.LabelSmall, color = NvColors.TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(formatMs(active.durationMs), style = NvTypography.LabelSmall, color = NvColors.TextSecondary)
                    }
                    // N1 二轮（2026-10-09 用户截图点名：进度条/按钮挤在一起）：进度条区与走带
                    // 控制之间拉开一段落间距（XL），四段纵向节奏=进度/走带/音量/动作
                    Spacer(Modifier.height(NvSpacing.XL))

                    // N1（2026-10-09 批次二）：三键固定 48dp 触控 + Center 均布，
                    // 消除 Spacer(weight) 在两键/三键间的漂移（onClick 回调零变更）
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { session?.seekTo((active.positionMs - 30_000).coerceAtLeast(0)) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Replay30, "快退30秒", tint = NvColors.TextPrimary)
                        }
                        Spacer(Modifier.width(NvSpacing.XL))
                        IconButton(
                            onClick = { if (paused) session?.resume() else session?.pause() },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                if (paused) "播放" else "暂停",
                                tint = NvColors.Primary,
                            )
                        }
                        if (onNext != null) {
                            Spacer(Modifier.width(NvSpacing.XL))
                            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.SkipNext, "下集", tint = NvColors.TextPrimary)
                            }
                        }
                    }

                    // N1 二轮（2026-10-09 用户截图点名）：走带控制与音量区之间补段落间距，
                    // 消除滑条 thumb 直接顶到按钮的挤压。
                    // T9 批次二评审 Minor：无音量设备（volumeUri=null）时此 XL 与下方动作区间距
                    // 相邻叠成 48dp 空洞——归并进音量块，仅音量区存在时才补此段
                    if (active.device.volumeUri != null) {
                        Spacer(Modifier.height(NvSpacing.XL))

                        // 音量：设备声明 RCS 才显示（spec C13 §7：不猜）
                        var volume by remember(active.device.udn) { mutableFloatStateOf(0.5f) }
                        // 返工⑬补完整性（GetVolume）：面板打开拉一次电视端实际音量作滑杆初值
                        //（此前恒 50% 硬编码）；失败静默回退 50% 初值，不打扰用户。
                        // 物理音量键不联动投屏（系统键控本机媒体音量）——见 rework 报告定界
                        LaunchedEffect(active.device.udn) {
                            session?.currentVolume()?.let { volume = it / 100f }
                        }
                        Text("音量", style = NvTypography.LabelSmall, color = NvColors.TextSecondary)
                        NvSlider(
                            value = volume,
                            onValueChange = { volume = it },
                            onValueChangeFinished = { session?.setVolume((volume * 100).toInt()) },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // N1 二轮（2026-10-09 用户截图点名）：音量区与底部动作区之间补段落间距，
                    // 按钮行不与滑条贴叠（无音量设备时此为走带/动作间唯一间距，与上方 XL 归并）
                    Spacer(Modifier.height(NvSpacing.XL))

                    // 返工⑮（2026-10-16 用户真机「两按钮不一致：拉回有描边、断开裸红字」）：
                    // 统一 48dp 描边胶囊（NvShape.Button 药丸）——断开投屏=红字红描边 danger 档，
                    // 拉回本机=常规描边档；同 Row weight(1f) 等宽
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NvSpacing.S),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NvSecondaryButton(
                            text = "断开投屏",
                            onClick = {
                                session?.stop()
                                onDismiss()
                            },
                            danger = true,
                            modifier = Modifier.weight(1f),
                        )
                        NvSecondaryButton(
                            text = "拉回本机播放",
                            onClick = onPullBack,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(NvSpacing.M))
                }
            }
        }
    }
}

/** 面板渲染用的公共视图（Playing/Paused 字段同构） */
private data class ActiveCast(
    val device: CastDevice,
    val media: com.novax.app.data.cast.CastMedia,
    val positionMs: Long,
    val durationMs: Long,
)

private fun activeOf(state: CastUiState): ActiveCast? = when (state) {
    is CastUiState.Playing -> ActiveCast(state.device, state.media, state.positionMs, state.durationMs)
    is CastUiState.Paused -> ActiveCast(state.device, state.media, state.positionMs, state.durationMs)
    else -> null
}

private fun formatMs(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

/** 主界面悬浮「投屏中」条（C13 §5 已投屏态）：点按打开遥控面板，可拉回本机 */
@Composable
fun CastOverlay(bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    // 同上：init 必须在组合期完成，否则 session 为 null 且永不重组
    // （S2-F1 后 init 零网络副作用，此调用恒安全；会话只能经 cast() 产生，cast 必经
    // 设备面板路径已 ensureDiscovery，chip 的状态暴露方式不变）
    remember { CastSessionHolder.init(context) }
    val session = CastSessionHolder.session()
    val state = session?.state?.collectAsState()?.value ?: CastUiState.Idle
    val active = activeOf(state)
    // 返工⑮：连接中（含 Holder 预处理期）也視同可视活跃——chip 显「正在连接」，
    // 设备面板提前关掉时用户不再裸等
    val connecting = state as? CastUiState.Connecting

    // S2-F1：会话从活跃转为结束（断开/拉回本机/电视端断连）后，发现栈已无存在必要，
    // 顺手回收（面板 onDispose 可能先于状态翻转，此处置补漏）。活跃期此 effect 只在
    // active↔null 边沿重启，守卫判定在 Holder 内（投屏中绝不停；Connecting 归 Holder
    // isActiveSession 活跃集，此处调用是幂等 no-op）
    androidx.compose.runtime.LaunchedEffect(active == null) {
        if (active == null) CastSessionHolder.stopDiscoveryIfIdle()
    }
    if (active == null && connecting == null) return

    var showPanel by remember { mutableStateOf(false) }

    // 返工⑭（2026-10-16 用户真机反馈「投屏成功还要手动点 chip 才能开面板」）：投屏确立
    //（Connecting→Playing，两条路径——媒体库 grid chip 与播放页投出——最终都汇到本
    // CastOverlay 的 chip/面板）自动弹出一次遥控面板。票据由 CastSession 在 Playing 确立时
    // 签发、此处 CAS 取走即清：a) 手动关闭/逐秒轮询重组都不复活（每轮询的 Playing 实例
    // 不再签发）；b) 拉回本机/断开投屏走 stop()→Idle 不签发；c) 下一轮 cast() 成功才再签发。
    // 返工⑮改挂 [active] 键（原 LaunchedEffect(Unit) 依赖「chip 首个 active 实例组合」时序，
    // Connecting 也组合 chip 后该前提改变）：active 每次轮询更新换实例 → effect 随实例重启，
    // CAS 保证每个会话至多取票一次，Playing 确立后首个实例帧即弹。
    LaunchedEffect(active) {
        if (session?.takePanelAutoOpen() == true) showPanel = true
    }

    // Box 无背景不拦触摸：只有 chip 本身可点，其余区域穿透到下层内容
    // N1（2026-10-09 批次二）：散点 dp 等价归拢 token（12dp→M、8dp→S；14dp/6dp/16dp
    // 无等价 token 或属图标尺寸先例，保留字面值——值全部不变）
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().padding(bottom = bottomPadding + NvSpacing.M),
    ) {
        // Liquid Glass（spec §10）：悬浮 chip 归主域 NvGlassChip 承接——描边/提亮由原语承担，
        // shadowElevation 移除；align(BottomCenter) 必须留在传给 chip 的 modifier 上
        com.novax.app.ui.glass.NvGlassChip(
            shape = NvShape.Pill,
            onClick = { showPanel = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = NvSpacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Cast, null, Modifier.size(16.dp), tint = NvColors.Primary)
                Spacer(Modifier.width(NvSpacing.S))
                Text(
                    active?.let { "正在投屏 · " + it.device.name }
                        ?: "正在连接 · " + connecting!!.device.name,
                    style = NvTypography.LabelMedium,
                    color = NvColors.TextPrimary,
                )
            }
        }
    }

    if (showPanel) {
        NvCastRemotePanel(
            bottomPadding = bottomPadding,
            onDismiss = { showPanel = false },
            onPullBack = {
                // 拉回本机：以电视端当前进度重启本机播放，随后结束投屏会话
                val a = active
                if (a != null) {
                    context.startActivity(
                        android.content.Intent(context, com.novax.app.player.PlayerActivity::class.java)
                            .putExtra(com.novax.app.player.PlayerActivity.EXTRA_VIDEO_URL, a.media.playUrl)
                            .putExtra(com.novax.app.player.PlayerActivity.EXTRA_TITLE, a.media.title)
                            .putExtra(com.novax.app.player.PlayerActivity.EXTRA_POSITION_MS, a.positionMs),
                    )
                    session?.stop()
                    showPanel = false
                }
            },
        )
    }
}
