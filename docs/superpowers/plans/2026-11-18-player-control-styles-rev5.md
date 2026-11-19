# 播放器控制层三样式 rev5 增量计划 — 极简样式二次修正（顶栏即进度条）

- 日期：2026-11-18
- 依据：spec `docs/superpowers/specs/2026-11-15-player-control-styles-design.md` **rev5**（用户第三次拍板：顶栏=进度条/左一旋转/常驻层删除/⋯精简菜单；spec 审核两轮，PASS+收尾 5 条已落实）
- 基线：公开仓本地 main `83ef321`（rev4 R1-R6 + 根修），全量 1353 绿
- 执行模式：SDD（每任务全新实现子代理 + 独立复审子代理，fix loop ≤5 轮，终审）

## 背景一页（实现子代理必读）

rev4 的极简样式被用户指出三处理解错误（附 nPlayer 四张真机截图）：①进度条被放在底部——nPlayer 的**顶栏本身就是进度条**（蓝填充=已播比例，整栏可拖/点 seek），底部只有按钮黑条、没有任何进度条；②左侧第一圆钮放了锁——nPlayer 是**画面旋转**（0/90/180/270 循环，复用既有 `rotateVideo`）；③保留了常驻层（贴底细线+时间小字）——nPlayer 隐藏后**画面完全干净**。rev5 全部推翻重做 MINIMAL 分支；classic/streaming 分支与共享组件**零变化**。

## 全局红线（每个任务的实现与复审都核对）

1. **classic/streaming 零变化**：`NvChapterSlider`、`NvPlayerTopBar`、`ControlStyleStreaming.kt`、classic 分发分支不得有任何行为/视觉改动。允许的接触仅两类：①删除两侧 MINIMAL 分支对 line slider 的调用（T3/T4 点位）；②顶栏宿主渐变 Box 按分支条件化（T3-7）——classic/streaming 分支渲染树保持等价。
2. **手势机/显隐超时零改动**，显式豁免仅两处：①隐藏计时 quickMenuOpen 门控（spec §5.1，唯一豁免）；②MINIMAL 分支 `controlsBandHeightPx` 置零（spec §5.2-③，防热切后旧 band 残留死区）。半锁重武装语义（2026-11-09 审计 A1）不得触碰。
3. **quickMenuOpen 复位口径**（spec 复审 P1-1）：任何控制层隐藏出口（锁定收层/全锁/错误态）与样式热切换时编排层一律复位 false；菜单渲染条件=`quickMenuOpen && 控制层可见`。
4. 测试跑法：GBK daemon 覆盖姿势单独跑 `./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest --console=plain`；构建单独跑，禁止合并一条命令。
5. 每任务一次提交（任务级 commit，风格对齐仓内），提交前自查泄漏；**不 push**。
6. 不新增契约回调之外的状态源；样式包不得自带影响编排层行为的业务状态（quickMenuOpen 归编排层）。

---

## T1 契约扩展 + 顶栏填充纯函数（JVM 锚）

**文件**：`app/src/main/java/com/novax/app/player/NvControlContract.kt`、`ControlStyleMinimal.kt`、测试（`ControlStyleTest.kt` 或新建 `MinimalSeekTopBarLogicTest.kt`）

- 契约新增三字段（带默认值，不破坏既有调用；spec §5.1 rev5 扩展段）：
  - `quickMenuOpen: Boolean = false`（⋯菜单打开态，编排层持有）
  - `onToggleQuickMenu: () -> Unit = {}`（⋯块点击切换）
  - `hasCast: Boolean = true`（⋯菜单投屏行门控：主侧=playUrl 非 null，RMVB 恒 false）
- `ControlStyleMinimal.kt` 新增纯函数与键集：
  - `minimalFillWidthPx(leadWidthPx: Float, progress: Float, barWidthPx: Float): Float` = `max(leadWidthPx, progress * barWidthPx).coerceIn(0f, barWidthPx)`（spec §3.2 最小填充宽=✕块 48dp+时间文字宽，nPlayer 截图 00:02 实证 max 规则）
  - `quickMenuRows(isRmvb: Boolean, hasCast: Boolean): List<QuickMenuRow>`；`enum class QuickMenuRow { LOCK, PREV, NEXT, PIP, CAST, EXIT }`——RMVB 剔除 PIP/CAST（spec §5.3 键集思想，`quickControlsFor` 同型）；主侧 hasCast=false 剔除 CAST（运行时门控）。两机制独立（复审已核无冲突）
  - `minimalDrawsFill(durationMs: Long): Boolean = durationMs > 0`（plan 审核 P2-2：把「无时长不画填充」提为可断言纯函数，对齐 spec §9 JVM 测试项）
- JVM 测试锚（TDD：先红后绿）：
  - 填充宽：progress=0 时=leadWidth（覆盖✕+时间）；progress=1 时=barWidth；中间值 max 语义；负 progress coerce 到 leadWidth
  - 无时长断言：`durationMs<=0` 时调用侧不画填充（用纯函数口径：progress 按 0 传且 leadWidth 亦不生效——**实现口径：durationMs≤0 时组件完全不画填充**，该行为由组件层守卫+本纯函数 doc 注明，plan 冒烟锚点核）
  - `quickMenuRows`：RMVB 矩阵（无 PIP/CAST）、主侧 hasCast=false（无 CAST）、主侧 hasCast=true（全 6 行）
  - `minimalDrawsFill`：durationMs>0 true / ≤0 false
  - 契约默认值：三新字段默认 false/false/true
- **验收**：编译绿 + 新测试绿 + 全量绿。

## T2 minimal 包组件改造（ControlStyleMinimal.kt 一站式）

**文件**：`ControlStyleMinimal.kt`、`res/values*/strings_player.xml`（新键五语言）

1. **MinimalSeekTopBar（新组件，替代旧 MinimalTopBar 的调用位；旧组件本任务暂留不删）**：
   - 通栏高 **48dp**，自绘（不复用 NvChapterSlider）；两侧宿主 Scrim 渐变对 MINIMAL **不挂载**（spec §3.2——渐变叠加会毁未播段半透明观感；实现=宿主侧 MINIMAL 分支去掉渐变 Box，T3/T4 落地，本任务组件 doc 注明）
   - 背景：已播段 `NvPlayerBlue` 从 x=0 起铺，宽=`minimalFillWidthPx(lead, progress, barW)`；**lead=当前显示文本实测宽+48dp 块宽，随文本变化即接受跳动**（拖动跨小时格式跳变时 lead 变化→填充前缘位移=nPlayer 同源口径，不做平滑；复审锚：拖动全程无前缘回跳毛刺、窄屏不溢出，plan 审核 P2-3）；未播段 `Color.Black.copy(alpha≈0.45f)`；`minimalDrawsFill(durationMs)==false` 时不画任何填充；**无缓冲段显示、无章节刻度、无拖动气泡**（spec §3.2 三条禁令，自绘实现天然不带，复审仍核）
   - 拖动松手提交后回显：对齐经典 slider 现状（引擎 seek 异步，允许瞬时回跳旧值再追上；若实现者做「保持提交值至外部追上」优化须两侧一致，plan 审核 P2-4）
   - 元素排布（Row 叠在 Canvas 上）：蓝✕块（`NvPlayerBlue` **通栏高 48dp 见方**圆角方块，白 `Icons.Outlined.Close`，`onBack`）→ 当前时间（`formatTime(positionMs)`；拖动中显示拖动目标位置）→ 标题（居中 weight(1f) 省略号）→ 剩余时间（复用 `remainingLabel`）→ 蓝⋯块（同尺寸，**`Icons.Filled.MoreHoriz`** 横三点，`onToggleQuickMenu`；打开态可加高亮底色区分）
   - 交互（`pointerInput` 挂在整栏）：
     - 横向拖动 scrub：本地 `dragPositionMs` state，拖动中填充与时间标签实时跟随（position 显示=拖动目标值），松手 `onSeek(dragPositionMs)` 提交并清除本地态；拖动中不调用引擎 seek
     - 单点 tap：按位置比例换算→`onSeek`（tap 位置优先于 toggle 语义；✕/⋯块区域点击不被 slider 抢——两蓝块自身 `clickable` 在上层消费）
     - durationMs≤0：拖动/tap-seek 全部 no-op
     - 手势消费限于本栏（不外溢）；拖动开始时若父层有 seek 预览浮层需收起的钩子，参考两侧 line slider 现有 `onDragStart` 口径（主侧传 `showSeekPreview=false` 等价物——T3 接线时统一）
2. **MinimalQuickMenu（新组件）**：
   - 右上下拉卡：`align(TopEnd)` + 顶部 offset=顶栏高（48dp）；深色半透明圆角卡（配色对齐现有 MoreActionsSheet 容器视觉，宽 ~220dp）；每行高 ≥48dp 热区，纯文字行（nPlayer ⋯菜单无图标，1:1）
   - 行集=`quickMenuRows(isRmvb, hasCast)`：LOCK→`c.onLock`（cd/文案=现键 `player_cd_lock` 系）；PREV/NEXT→`onPrev/onNext`（`hasPrev/hasNext=false` 灰置不禁用隐藏）；PIP→`onPip`；CAST→`onCast`；EXIT→`onBack`
   - i18n 新键 **`player_quick_exit`**「退出播放器」补齐现有全部语言目录（其余行文案复用现键：锁定/上/下集/画中画/投屏均有 cd 键可作行标签，T8 行标签先例）
   - **外点消费（plan 审核 P1-1 姿势钉死）**：菜单可见时菜单外全屏透明拦截层——`pointerInput` 内 `detectTapGestures(onTap = { onToggleQuickMenu() })` **外加 drag 全消费**（`detectDragGestures(onDrag = { _, _ -> })` 占位或 down/move/up 统一 consume）。裸 `clickable` 只消费 tap：横向拖动超过 touch slop 后 move 不被消费，祖先手势状态机会照常判横滑→亮度/音量/seek 穿透执行。验收锚：菜单开着横滑=零手势响应、tap 外点=仅关菜单
   - 渲染条件由调用侧保证（`quickMenuOpen && controlsShown`）；**防自动隐藏归编排层**（T3/T4）；**行点击后关菜单语义（plan 审核 P2-5 拍板）**：除 LOCK 行外（PREV/NEXT/PIP/CAST/EXIT）点击后统一回调 onToggleQuickMenu 复位（切集/投屏/退出后菜单不留、隐藏计时不再挂起；LOCK 行走锁定收层出口的复位链，不自行关）
3. **MinimalSideRail 首钮锁→旋转**：第一钮=`onVideoRotate`（icon `Icons.Outlined.Rotate90DegreesCcw`——PlayerScreen.kt:89 同款已在依赖；cd 用现键 **`player_cd_rotate`** 或带度数口径 **`player_cd_rotate_current`**（strings_player.xml :93/:241，经典顶栏现用同款，勿造新键））→ CC 字幕（不动）→ 齿轮=更多面板（不动）。锁三态图标逻辑从本组件移除（底栏锁钮保留）
4. **MinimalBottomBar**：七钮/窄幅五钮/灰置/尺寸**零功能变化**；①**背景本任务不动代码**（仍由宿主 Column 承载）——背景收回组件自带与宿主 Column 拆除**必须同任务原子完成**（T3/T4），否则中间提交出现组件 Scrim+宿主 Scrim 双层叠加（rev4 根修 83ef321 的接缝教训）；本任务仅 kdoc 注明 rev5 目标口径（背景随 T3/T4 收回组件自带）；②kdoc 更新（窄幅锁兜底=⋯菜单恒有锁行；线不在底栏）
5. 删除文件头 kdoc 中 rev2/rev4 常驻层描述，更新为 rev5 口径；`MinimalPersistentLayer`/`persistentLayerVisible`/旧 `MinimalTopBar` **本任务暂留**（T5 删）
- **验收**：编译绿（两侧调用点未动，旧组件仍被引用）+ 全量绿 + 新纯函数测试绿。**任务间运行时混合态属预期**（plan 审核 P2-1：T2 提交后两侧 minimal 形态=左列旋转钮+旧顶栏+底栏 line slider+常驻层仍在；锁功能不丢——底栏锁钮在；**禁止中途对照 nPlayer 验收**，两侧 rev5 完整形态以 T4 完成为准）。

## T3 主播放器编排接线（PlayerScreen.kt）

**点位**（当前 HEAD 83ef321 行号）：契约组装 :1829、MINIMAL 顶栏分发 :1889、MINIMAL 底栏分支 :1981-2000（Column+line slider）、隐藏计时 :1281、controlsVisible=false 出口 :1815/:1823/:2123/:2131、常驻层槽位 :2147-2149、侧列挂载 :2167/:2175

1. 契约组装补三字段：`quickMenuOpen`（新 `remember { mutableStateOf(false) }`）、`onToggleQuickMenu = { quickMenuOpen = !quickMenuOpen }`（`controlsTick++` 为可选保险非门控机制——门控唯一姿势见第 4 条，plan 审核 P2-6）、`hasCast = playUrl != null`
2. MINIMAL 顶栏分发 :1889 `MinimalTopBar` → `MinimalSeekTopBar`（拖动开始收 seek 预览：若经典/line slider 有 `onDragStart={showSeekPreview=false}` 同款需求，在此接线）
3. MINIMAL 底栏分支 :1981-2000：拆宿主 Column+`NvChapterSlider(LINE)`，改为直接 `MinimalBottomBar(controlContract)`；**band 置零**——该分支内 `SideEffect { controlsBandHeightPx = 0f }` 或等价机制（样式切换进 MINIMAL 时生效一次即可，防旧 band 残留死区；spec §5.2-③）
4. 隐藏计时 :1281：**`quickMenuOpen` 加入 LaunchedEffect key 集 + 条件体加 `&& !quickMenuOpen`**（姿势唯一，不得只改条件体——若仅条件体，effect 在 `delay(CONTROL_AUTO_HIDE_MS)` 中途时菜单打开不会重启 effect，delay 到点仍会隐藏；进 key 后菜单打开→effect 重启→条件 false 空转，菜单关闭→重启→重新计时 4s，两态语义都对）。RMVB 侧（T4）同口径：`controlsHideJob` 在 `uiQuickMenuOpen=true` 时 cancel 且不重新 launch，关闭时重新 schedule（半锁重武装注释语义不动，红线 2）
5. **复位口径**（红线 3，矩阵四路全覆盖——plan 审核 P1-2 补错误态落点）：①锁定收层出口 :1815/:1823/:2123/:2131 同句 `quickMenuOpen.value = false`（:1284 经门控推演在菜单开着时不可达——effect 条件含 `&& !quickMenuOpen`，可不复位或防御性同句，plan 审核 P2-7）；②**错误态**：`LaunchedEffect(errorMessage) { if (errorMessage != null) quickMenuOpen.value = false }`（错误态是渲染门控而非 controlsVisible 出口，errorMessage 赋值 :972/:980/:1065 不动 controlsVisible——不复位则错误恢复后菜单重现且隐藏计时永久挂起）；③热切：`LaunchedEffect(controlStyle) { quickMenuOpen.value = false }`；④全锁随 ① 锁定路径覆盖。复审核四路矩阵+错误恢复后 4s 隐藏恢复
6. QuickMenu 挂载：控制层 Box 内 `if (controlStyle == MINIMAL && quickMenuOpen && controlsShown等价条件) MinimalQuickMenu(...)`（isRmvb=false）
7. 常驻层槽位 :2147-2149 删除（含注释）；宿主渐变：MINIMAL 顶栏宿主的 ScrimStrong→透明渐变 Box（主侧 ≈:1882 附近、RMVB ≈:2714 附近，实现时以现码为准）按分支不挂载——**仅 MINIMAL 分支跳过渐变，classic/streaming 分支渐变原样**（与底栏背景收回同任务原子完成）
8. 侧列 :2167 `MinimalSideRail` 调用零改动（组件内部已换旋转钮）；:2175 SpeedRail 零改动
- **验收**：编译绿 + 全量绿 + grep 确认 MINIMAL 分支无 `NvChapterSlider` 调用、无 `MinimalPersistentLayer` 引用。

## T4 RMVB 兜底页对称接线（RmvbPlayerActivity.kt）

**点位**（plan 审核 P2-8 补全，均以现码为准）：契约组装 :2631、顶栏分发 :2722、MINIMAL 底栏分支 :2770-2785（Column+line slider）、常驻层 :2807-2811、侧列 :2828/:2836、`uiControlsVisible=false` 四出口（:2656/:2671 契约 onLock、:3254/:3270 ControlsBottom onLock）、uiControlStyle 赋值 :3533、隐藏 job 体 :3544-3553（`controlsHideJob`，半锁重武装注释块 :3538 起——**这段注释与语义不得动**）

- 与 T3 逐项对称：契约三字段（`hasCast = false` 恒）、顶栏换 `MinimalSeekTopBar`、底栏拆 Column+slider+band 置零、QuickMenu 挂载（isRmvb=true）、常驻层槽位删、宿主渐变 MINIMAL 不挂载（与底栏背景收回同任务原子完成）
- **隐藏 job 门控完整姿势（plan 审核 P1-3 钉死，防「菜单刚打开即被收走」竞态）**：`onToggleQuickMenu` 打开路径（open=true）**显式 `controlsHideJob?.cancel()`**（救已在 delay 中途的 job——仅「launch 前门控/首行早退」救不了 3.9s 时点开菜单 0.1s 后被收走的场景）；关闭路径（open=false）**调 `keepControls()` 重新 schedule**（否则菜单关闭后无人 schedule，自动隐藏失效到下一次交互）；keepControls 的 launch 处另加 `if (!uiQuickMenuOpen)` 门控（双保险）。复位出口=四出口同句复位 + **错误态 `LaunchedEffect(uiError)` 同型补钉** + uiControlStyle 变化复位
- RMVB 手势 seek 预览（`uiSeekPreviewMs` :324/:2274/:3201-3204）与顶栏拖动互不相干，顶栏无需接线（plan 审核 P2-9 事实更正：RMVB **有**手势 seek 预览，但 hide 判定已自带该门）
- **验收**：编译绿 + 全量绿 + grep 对称性核对（两侧 MINIMAL 分支结构逐项对应）。

## T5 死代码清理 + 测试改写

**文件**：`ControlStyleMinimal.kt`、`MinimalGateTest.kt`、`MinimalTopBarLogicTest.kt`

- 删旧 `MinimalTopBar`、`MinimalPersistentLayer`、`persistentLayerVisible`（grep 全仓零引用后删）
- `MinimalGateTest`：纯测 `persistentLayerVisible`，随函数删除**整文件删除**（「MINIMAL 恒不挂载常驻层」是编排层 composable 行为无 JVM 可测对象，plan 审核 P2-10-③ 钉死——不再留文档性断言文件）
- `MinimalTopBarLogicTest`：`remainingLabel` 用例保留；补/确认 T1 填充纯函数锚在此文件或独立文件
- 全仓 grep 残留：`MinimalPersistentLayer|persistentLayerVisible|MinimalTopBar(` 零命中（`MinimalSeekTopBar` 除外）
- **验收**：编译绿 + 全量绿（预期 ≈1353±锚增删净额）。

## T6 设置页预览卡（NvStylePreviewCard.kt）

**点位**：`drawMinimal` :164

- MINIMAL 迷你图更新（spec §7）：顶栏=左段实蓝（蓝填充）+右段暗条、两端蓝块；左缘三点竖排+右缘三点竖排；底部一行七点黑条；**无贴缘细线**（rev4 版细线删除）。**`drawBottomEdgeLine` 函数必须保留**（drawStreaming :209 共用），仅删 MINIMAL 分支的调用（plan 审核 P2-10-④）
- classic/streaming 两张迷你图零改动
- **验收**：编译绿 + 全量绿；预览卡视觉随装机冒烟截图核对（主会话执行）。

---

## 冒烟与真机验收（主会话执行，非子代理）

1. 模拟器冒烟：三样式切换回归（classic/streaming 形态零变化对照）、minimal 唤起态全貌、§9 冒烟清单（spec rev5）——顶栏填充比例/拖 scrub/点跳/低进度覆盖✕+时间/无时长无填充、⋯菜单（行集/灰置/外点仅关菜单/**打开 >4s 不消失**/复位链[锁定收层→解锁→菜单不残留+4s 隐藏恢复]/错误态[菜单开着→造错误→重试恢复→菜单不残留+计时恢复]/行点击[非 LOCK 行点击后菜单关]/拖动全程无前缘毛刺）、**菜单开着横滑=零手势响应**（P1-1 锚）、左一旋转、隐藏后全干净、底部无线
2. RMVB 侧对称核对（键集减 PIP/CAST）
3. 验收真机装机（release arm64 覆盖装）→ 用户上手验收（nPlayer 对照）
4. gitleaks `detect --source .` 零泄漏；全量单测最终绿

## 挂账（不阻塞）

- nPlayer ⋯ 菜单「播放列表」：NovaX 无播放中队列 UI，将来可立项（spec §11）
- RMVB 兜底页仍是第二实现（spec §11 既有挂账不变）
