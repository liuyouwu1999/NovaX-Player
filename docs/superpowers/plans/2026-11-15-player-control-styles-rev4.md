# 极简样式 nPlayer 1:1 复刻（rev4）增量实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec rev4c 重做极简样式为 nPlayer 真机截图 1:1 复刻（蓝块顶栏/左右侧圆钮列/倍速三件套/底栏七钮黑条），常驻层与 line 进度条保留。

**Architecture:** 增量重写 `ControlStyleMinimal.kt`（顶栏/新增侧钮列与底栏），契约扩 `onSetSpeed`，两侧（PlayerScreen/RmvbPlayerActivity）MINIMAL 分支接线替换；classic/streaming/常驻层零触碰。

**Tech Stack:** Kotlin/Compose、DataStore（既有键）、snapPlaybackSpeed 复用。

**Spec:** `docs/superpowers/specs/2026-11-15-player-control-styles-design.md` @ rev4c（ed5298d）——执行者先读 §3.2 rev4 块与 §5.1 rev4 契约扩展段。

## Global Constraints

- 仓库 E:\NovaX-Player main（HEAD=ed5298d，含已完成的 rev2 实现+终审修复 f3a12a5）；提交中文 `type(scope):`。
- 单测 GBK 姿势 `./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest --console=plain`（基线 1337 绿）；构建单独跑。
- **零触碰红线**：classic/streaming 两样式、MinimalPersistentLayer 常驻层（含 persistentLayerVisible）、NvChapterSlider（含 line 模式）、手势机、显隐超时、更多面板既有两组、设置页经典/长视频预览卡。
- 1:1 锚定值（spec §3.2，逐字）：蓝块 `#4578C7`（入 NvColors 为 `NvPlayerBlue`）44dp 圆角方块白图标；顶栏深色半透明 44dp；位置时间=formatTime(positionMs)；剩余时间=`-`+formatTime(durationMs−positionMs)，durationMs≤0 隐藏；侧圆钮 46dp 半透明灰（黑 55%）间距 12dp，左列中心=屏高 56%；右列三件套 ⊕/formatSpeedLabel(speed) 高亮非 1x/⊖，点击重置 1.0x；底栏七钮**等距**、上/下集灰置不隐藏；窄幅（<480dp）五钮（去锁+比例）；倍速步进 `nvSpeedStep = snapPlaybackSpeed(current + delta)`。
- i18n：新增用户可见文案五语言（含 values-zh-rTW）。

## Review Focus

1. **onSetSpeed 提交语义漏项**（漏 recordLastSpeed/清 speedShift/静音 → 倍速行为与面板不一致）→ R1 测试锚定提交四要素函数化。
2. **±0.1 浮点漂移**（0.1+0.2=0.30000004 连点错档）→ R3 测试连点 1.0→1.3→…→2.0 全档断言。
3. **无时长文件剩余时间**（直播/损坏文件 durationMs≤0 → 负数/异常格式）→ R2 纯函数 `remainingLabel(durationMs, positionMs)` 测试（≤0 返 null 隐藏）。
4. **窄幅七钮溢出回归**→ R4 `minimalBottomSlots(narrow)` 测试（七/五槽）。
5. **防双线回归**（rev2 验收过的互斥不能被重写破坏）→ R6 冒烟专验。
6. **侧钮列压内嵌字幕**（左缘中下部钮列 vs 画面内字幕——设计接受）→ R6 冒烟记录不修复。

---

### Task R1: 契约扩展 onSetSpeed + 两侧提交语义（TDD）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/NvControlContract.kt`（加字段）
- Modify: `app/src/main/java/com/novax/app/player/PlayerScreen.kt`（契约组装接 onSetSpeed）
- Modify: `app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt`（同上 RMVB 侧）
- Test: `app/src/test/java/com/novax/app/player/NvControlContractTest.kt`（扩用例）

**Interfaces:**
- Consumes: 仓内 `snapPlaybackSpeed`（PlaybackSpeed.kt）、SpeedSheet.onPick 提交链（PlayerScreen.kt 约 L2352-2356）、RMVB vlcPlayer.rate 链（约 L1362/L3014）。
- Produces: 契约新字段 `val onSetSpeed: (Float) -> Unit = {}`；两侧实现函数（主侧建议 `fun applyPickedSpeed(picked: Float)` 提取自 onPick 重复段供面板与三件套共用；RMVB 侧复用其既有 setSpeed 链）——R3 三件套经 `c.onSetSpeed(nvSpeedStep(...))` 提交。

- [ ] **Step 1: 扩契约测试（先红）**——NvControlContractTest 加用例：`onSetSpeed 默认 no-op 不崩`（`c.onSetSpeed(1.5f)` 无异常）+ 字段存在性。（提交语义四/五要素 JVM 不可测——锚定靠本步提取消重+独立复审+R6 冒烟运行时锚定，如实声明）
- [ ] **Step 2: 跑测试确认红**（`--tests "com.novax.app.player.NvControlContractTest"`）
- [ ] **Step 3: 实现**——契约加 `val onSetSpeed: (Float) -> Unit = {}`（KDoc 注明提交语义=SpeedSheet.onPick 同款）。**主侧**：从 PlayerScreen 的 SpeedSheet onPick 回调体（约 L2349-2357）提取 `applyPickedSpeed(picked: Float)`，**五动作原序**：`speed = picked`（本地 UI 态手动赋值——缺此三件套标签不刷新）+ 清 speedShiftActive/speedShiftLocked + engine.setPlaybackSpeed(pitched) + applySpeedMute + recordLastSpeed；`sheet = null` 留在 onPick 调用处（三件套不关面板）。**RMVB 侧**：L3014-3033（八档 chips）与 L3052-3071（细调滑杆）是**两份重复提交段**——提取共享提交函数（要素全列：uiSpeed 赋值 + persistPlaybackSpeed + 清 speedShift 双态 + logSpeedBoostEnd + 解旧 boost 静音 setVolume(100) + rampRate 冲刷（非裸 vlcPlayer.rate）+ ≥3x 应用静音），八档/滑杆/契约 onSetSpeed 三处共用；`uiShowSpeedSheet=false` 留调用处。
- [ ] **Step 4: 全量绿 + assembleDebug**
- [ ] **Step 5: Commit** `feat(player): 契约扩 onSetSpeed 直设倍速——两侧提交语义与 SpeedSheet.onPick 同款(主侧提取 applyPickedSpeed 消重)`

### Task R2: MinimalTopBar 重写（蓝块顶栏）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/ControlStyleMinimal.kt`（重写 MinimalTopBar）
- Modify: `app/src/main/java/com/novax/app/ui/theme/Theme.kt`（加 `val NvPlayerBlue = Color(0xFF4578C7)`，对齐现有 NvColors 组织方式）
- Test: `app/src/test/java/com/novax/app/player/MinimalTopBarLogicTest.kt`（Create——纯函数部分）

**Interfaces:**
- Consumes: 契约（positionMs/durationMs/title/onBack/onMore）；仓内 `formatTime`。
- Produces: `fun remainingLabel(durationMs: Long, positionMs: Long): String?`（≤0 或越界返 null=隐藏，否则 `"-"+formatTime(diff)`）；重写后 `MinimalTopBar(c: NvControlContract)` 签名不变（两侧调用点零改动）。

- [ ] **Step 1: 纯函数测试（先红）**：remainingLabel 正常（45min→"-45:00"）/直播 durationMs=0→null/position 越界→null/负 diff→null。
- [ ] **Step 2: 红** → **Step 3: 实现**——remainingLabel 入 ControlStyleMinimal.kt 顶层；MinimalTopBar 重写：`Row(height 44dp, 深色半透明背景 NvColors 体系内取 scrim 同款)`——左 `NvGlassChip 不适用`，直接 `Box(size 44dp, background NvPlayerBlue, RoundedCornerShape(10.dp), click onBack){ Icon(Close, 白) }` → `Text(formatTime(positionMs))` → `Text(title, weight(1f), 居中, ellipsis)` → `remainingLabel?.let { Text(it) } ?: Spacer` → 右蓝块同款 `Icon(MoreVert)` click onMore。
- [ ] **Step 4: 全量绿 + assembleDebug**
- [ ] **Step 5: Commit** `feat(player): 极简顶栏 nPlayer 1:1 重写——蓝块#4578C7(入 NvColors)/位置时间/标题/剩余时间(无时长隐藏)`

### Task R3: 左右侧钮列 + 倍速三件套（TDD 步进）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/ControlStyleMinimal.kt`（新增 MinimalSideRail/MinimalSpeedRail 组件 + nvSpeedStep）
- Test: `app/src/test/java/com/novax/app/player/NvSpeedStepTest.kt`（Create）

**Interfaces:**
- Consumes: 契约 onLock/onSubtitle/onMore/onSetSpeed/speed/lockMode；`snapPlaybackSpeed`；`formatSpeedLabel`。
- Produces: `fun nvSpeedStep(current: Float, delta: Float): Float = snapPlaybackSpeed(current + delta)`；`@Composable MinimalSideRail(c)`（左列：锁三态图标/CC 字幕/齿轮更多）；`@Composable MinimalSpeedRail(c)`（右列：⊕/speed 标签非 1x 高亮+点击重置/⊖）；组件内**垂直定位=父 Box align Center + offset(y = 屏高×6%)**（56% 中心实测口径）。

- [ ] **Step 1: 步进测试（先红）**：`nvSpeedStep(1.0f, +0.1f)==1.1f`；连点漂移 `listOf(1.0,1.1,…,2.0)` 十步全档精确（防 0.30000004）；上界 clamp `nvSpeedStep(5.0f, +0.1f)==5.0f`；下界 `nvSpeedStep(0.1f, -0.1f)==0.1f`；重置语义（组件层）`delta=-current`→1.0 不必测（重置走 onSetSpeed(1f)）。
- [ ] **Step 2: 红** → **Step 3: 实现**——nvSpeedStep 一行复用；两 Rail 组件：46dp 圆 `background(Color.Black.copy(alpha=0.55f), CircleShape)` 白 icon 24dp，Column 间距 12dp；锁图标三分支与 NvPlayerControlRow.LockSlot 同口径（Outlines/Filled 同款）。**垂直定位统一在调用侧**（R4 接线）：`align(CenterStart/CenterEnd) + offset(y = 屏高×6%)`（组件内不做定位；56% 中心=Center+6% 偏移等价）。**i18n（本步落）**：⊕/⊖/中间钮重置三个 contentDescription 新键（如 `player_speed_plus/minus/reset`）五语言；锁/CC 字幕/齿轮复用现有 `player_cd_lock`/`player_sheet_subtitle`/`player_sheet_more` 不新增。
- [ ] **Step 4: 全量绿 + assembleDebug**
- [ ] **Step 5: Commit** `feat(player): 极简左右侧钮列——左(锁三态/CC字幕/齿轮更多) 右(倍速三件套⊕±0.1⊖ 复用 snapPlaybackSpeed 防漂移/中间点击重置)`

### Task R4: MinimalBottomBar 七钮黑条 + 两侧分支替换（TDD 窄幅）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/ControlStyleMinimal.kt`（新增 MinimalBottomBar）
- Modify: `PlayerScreen.kt` / `RmvbPlayerActivity.kt`（MINIMAL 分支底栏接线替换 + 唤起态挂左右 Rail）
- Test: `app/src/test/java/com/novax/app/player/MinimalBottomSlotsTest.kt`（Create）

**Interfaces:**
- Consumes: 契约（onLock/onPrev/onNext/onSkipBy/onTogglePlay/onAspect/hasPrev/hasNext/isPlaying/skipSec/lockMode）；仓内 NvClusterButton/NvSkipStepButton/NvGlassChip（暂停钮 PauseSlot 同款 58dp/30dp）。
- Produces: `fun minimalBottomSlots(narrow: Boolean): List<MinimalSlot>`（`enum MinimalSlot { LOCK, PREV, REW, PAUSE, FWD, NEXT, ASPECT }`；窄幅去 LOCK+ASPECT）；`@Composable MinimalBottomBar(c)`（黑色半透明横条，slots 等距 Arrangement.SpaceEvenly，PREV/NEXT 灰置=enabled:false 不隐藏）。

- [ ] **Step 1: 槽位测试（先红）**：宽幅七槽含 LOCK/ASPECT；窄幅五槽无 LOCK/ASPECT（Review Focus 4）。
- [ ] **Step 2: 红** → **Step 3: 实现**——组件 + 两侧接线：MINIMAL 分支底栏由"仅 line 进度条"改为 `Column { MinimalBottomBar(contract); NvChapterSlider(LINE…不变) }`（**七钮黑条在上、line 进度条贴底在下**——spec §3.2 布局图钉死顺序，细线保贴底缘与常驻线原位顶替语义；主侧 slider 的 onDragStart/onSliderBandPx 参数原样保留）；唤起态 root Box 加左右 Rail（**调用侧** `align(CenterStart/CenterEnd) + offset(y = 屏高×6%)`，挂独立 AnimatedVisibility 复用 controlsShown 同 visible——全锁/半锁随控制层同隐藏，fade 同款）。
- [ ] **Step 4: 全量绿 + assembleDebug**
- [ ] **Step 5: Commit** `feat(player): 极简底栏七钮黑条(等距/上下集灰置/窄幅五钮)+左右侧钮列两侧接线——MINIMAL 分支 rev4 形态完成`

### Task R5: 设置预览卡极简缩略图更新

**Files:**
- Modify: `app/src/main/java/com/novax/app/ui/settings/NvStylePreviewCard.kt`（MINIMAL 分支重画）

**Interfaces:**
- Consumes: NvPlayerBlue（R2 产出）。
- Produces: 预览卡 MINIMAL 分支=顶条两端蓝块+左缘三点竖排+右缘三点竖排+底部一行七点+贴缘细线（spec §7 rev4 示意）。

- [ ] **Step 1: 重画 Canvas MINIMAL 分支**（120×68dp 画布内按比例缩放各锚定元素；蓝块用 NvPlayerBlue；**注意现有 drawTopStrip 是三档共用在 when 之前调用**——MINIMAL 分支须改为分支内自绘顶条（两端蓝块），不走共用顶条，防"返回点+标题线"残留）。蓝块圆角 10.dp 系计划层补值（spec 未钉死圆角，L67 授权合理取值）。
- [ ] **Step 2: 全量绿 + assembleDebug**
- [ ] **Step 3: Commit** `feat(settings): 极简预览卡 rev4 重画——蓝块顶条+双侧竖排点+七点底行`

### Task R6: 全量验证 + 真机冒烟（rev4 专项）

**Files:**
- 证据：`E:\视频播放器apk\_emutest\smoke_styles_realm\`（追加 rev4 节）

**Interfaces:**
- Consumes: R1-R5 全部；三星 SM-F9160 已连接（adb serial R5CN909G3PD）。

- [ ] **Step 1: 全量单测绿 + gitleaks**（git 模式零泄漏）
- [ ] **Step 2: 构建+装机**——`./gradlew :app:assembleRelease` 后取 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`（AGP9 打包任务已合并族无单 ABI 任务，全量构建取 arm64 产物）；`adb install -r`（同签名覆盖，serial R5CN909G3PD）。
- [ ] **Step 3: 真机冒烟清单**（每项截图）：
  1. minimal 唤起态全貌（横屏）：蓝块✕/位置时间/标题/剩余时间/蓝块⋯/左三圆钮/右三圆钮/七钮底条/贴底线——与用户归档截图（`_research/player_ui/nplayer/user_photo_2026-11-15.jpg`）并排对照
  2. 倍速三件套：连点 ⊕ 十下（1.0→2.0 全档无漂移）+ 中间钮点击重置 1x + ⊖ 递减 + 非 1x 高亮
  3. 剩余时间负号格式；无时长文件隐藏（用损坏/无时长测试文件或跳过并记录）
  4. 上下集灰置态（单文件队列）；锁圆钮三态图标
  5. 防双线回归（唤起仅一线）+ 常驻层四态回归（未锁/半锁/全锁/错误）
  6. 窄幅竖屏五钮底条（折叠屏竖屏或 wm size 模拟 360dp）
  7. RMVB 侧同形态（播 rmvb_smoke.rmvb）
  8. 中央区无遮挡（带字幕视频唤起态）+ 侧钮列与内嵌字幕叠压观察记录（设计接受项）
  9. **Review Focus #1 运行时锚定**：①三件套设速 1.5x → 重启 app 验证 recordLastSpeed 记忆保留（仍 1.5x）；②≥3x 档验证静音生效（applySpeedMute/persist 链）
  10. §3.4 功能对等表 minimal rev4 列逐项核对（18 入口，含窄幅锁/比例经更多的兜底路径）
- [ ] **Step 4: README 追加 rev4 节 + 问题修复回灌**
- [ ] **Step 5: Commit（如有修复）**

## Self-Review

- Spec 覆盖：§3.2 rev4 块→R2/R3/R4；§5.1 onSetSpeed→R1；§7 预览→R5；§9 rev4 专项→R6；§3.4 表→R6.10 逐项核对。无缺口。
- 类型一致：onSetSpeed(Float)（R1→R3）；remainingLabel（R2 内用）；nvSpeedStep（R3 定义/R3 测试/R6 冒烟）；minimalBottomSlots/MinimalSlot（R4）；NvPlayerBlue（R2 定义/R5 用）。
- Review Focus 归属（如实声明）：#1 提交语义——JVM 不可测，锚定=R1 提取消重（三处共用一份实现）+独立复审+R6.9 运行时锚定；#2→R3 测试；#3→R2 测试；#4→R4 测试；#5→R6.5；#6→R6.8。
