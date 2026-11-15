# 播放器控制层三样式可切换 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三套播放器控制层样式（经典紧凑化/极简/长视频风）可切换，主播放器与 RMVB 兜底页同口径，功能对等（18 入口×3 档全可达）。

**Architecture:** 契约（NvControlContract）+ 三样式纯视觉包 + 编排层分发；NvChapterSlider 加 line 视觉模式（一份手势内核换皮）；RMVB 顶栏四维参数化收拢为共享组件；「更多」面板扩容「播放控制」组做功能全集兜底。

**Tech Stack:** Kotlin / Jetpack Compose（Canvas 自绘）/ DataStore Preferences / JUnit4 纯函数测试。

**Spec:** `docs/superpowers/specs/2026-11-15-player-control-styles-design.md`（rev3）——执行者须先读 spec，本计划从 spec 出发不再复述产品理由。

## Global Constraints

- 仓库：E:\NovaX-Player（公开仓，main 分支；当前 HEAD=ebc6367）。提交信息中文，格式 `type(scope): 描述`（对齐 `git log` 现有风格）。
- **单测命令（GBK 覆盖姿势，与构建分开跑）**：`./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest --console=plain`；构建用默认 daemon：`./gradlew :app:assembleDebug --console=plain`。两者**绝不合并成一条命令**（历史事故）。
- 现有 1316 个单测必须全程绿；`app/src/test` 现有测试对控制层尺寸常量零断言（两轮评审亲扫确认），新增测试不会与旧断言打架。
- i18n：全部新增用户可见文案补齐现有五语言（`app/src/main/res/values{,-zh-rCN,-ja,-ru,-es}`——以实际 values 目录为准，缺一语言即任务不通过）。
- **共享不动清单（红线）**：手势机（detectPlayerGestures 与 RMVB 内联版）、显隐超时（4s/暂停常驻）、三级锁语义（PlayerLockPolicy）、七个面板视觉、PlaybackFeatures、HUD、既有 PlayerPrefs 键语义。任何任务不得改这些的行为。
- 尺寸定值（spec §3.1，逐字）：热区 56→48dp；Column bottom 10→6dp；ControlRow padding top/bottom 2/14→1/9dp；PauseSlot 72→58dp icon 34→30dp；侧键 60→48dp icon 28→24dp；窄幅 pause 62/56/50→50/45/40dp、btn 52/46/42→42/38/36dp、最小档 icon 固定 18dp；气泡锚点上移 8dp；键名 `novax_control_style`。
- 每任务完成即 commit（任务内最后一步）；commit 前跑该任务测试。

## Review Focus

执行者每任务自检这六类，评审员按此抽查：

1. **DataStore 脏值**（旧版本升级/手改备份）：`novax_control_style` 存了 `"Classic"`（大写）、`""`、乱码 → 必须回退 CLASSIC 且不崩。→ T1 测试钉死。
2. **唤起态双线**（minimal 核心观感）：常驻细线与唤起态 line 进度条同屏 → 必须由 `persistentLayerVisible` 纯函数裁决（唤起即隐常驻线）。→ T6 测试+T10 冒烟 2 钉死。
3. **band 手势退场联动**：热区 56→48 后 `onSliderBandPx`（=热区+24dp slop）必须自动收敛，父手势机在唤起态底缘仍正确退场——漏改会导致唤起态拖进度条触发全屏横滑 seek。→ T3 步骤含 band 传递验证（测试断言 line 模式同样回调 band）。
4. **RMVB 顶栏顺序漂移**：收拢后按键顺序若变（音轨/字幕互换位），老用户肌肉记忆被打断。→ T5 顺序列表纯函数测试钉死两侧现状顺序。
5. **更多面板幽灵键**：RMVB 侧「播放控制」组若渲染 PIP/清晰度/投屏（RMVB 无此功能），点击空转或崩。→ T8 键集过滤纯函数测试钉死。
6. **streaming 窄幅挤爆**：360dp 竖屏单行底栏 7 元素溢出。→ T7 窄幅规则（去倍速钮）纯函数测试钉死。

---

### Task 1: 样式枚举与存储解析（TDD 纯函数）

**Files:**
- Create: `app/src/main/java/com/novax/app/player/ControlStyle.kt`
- Modify: `app/src/main/java/com/novax/app/player/PlayerPrefs.kt`（加键常量）
- Test: `app/src/test/java/com/novax/app/player/ControlStyleTest.kt`

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces: `enum class NvControlStyle { CLASSIC, MINIMAL, STREAMING }`；`fun parseControlStyle(raw: String?): NvControlStyle`；`PlayerPrefs.kt` 中 `const val KEY_CONTROL_STYLE = "novax_control_style"`（后续任务用 `rememberPlayerPrefs()[KEY_CONTROL_STYLE]` 读取 + `parseControlStyle` 解析）。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/ControlStyleTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlStyleTest {
    @Test fun `三个合法值解析`() {
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("classic"))
        assertEquals(NvControlStyle.MINIMAL, parseControlStyle("minimal"))
        assertEquals(NvControlStyle.STREAMING, parseControlStyle("streaming"))
    }
    @Test fun `null 与空串回退 classic`() {
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle(null))
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle(""))
    }
    @Test fun `未知值回退 classic 且不崩`() {
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("Classic"))   // 大写脏值
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("nplayer"))  // 乱值
        assertEquals(NvControlStyle.CLASSIC, parseControlStyle("\u0000"))   // 脏字节
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest --tests "com.novax.app.player.ControlStyleTest" --console=plain`
Expected: 编译失败 `unresolved reference: NvControlStyle`

- [ ] **Step 3: 最小实现**

```kotlin
// app/src/main/java/com/novax/app/player/ControlStyle.kt
package com.novax.app.player

/** 控制层样式（spec 2026-11-15 §3）：CLASSIC=经典（默认） MINIMAL=极简 STREAMING=长视频风。 */
enum class NvControlStyle { CLASSIC, MINIMAL, STREAMING }

/** DataStore 原始值解析；未知/null/空一律回退 CLASSIC（fail-safe，spec §6）。 */
fun parseControlStyle(raw: String?): NvControlStyle = when (raw) {
    "minimal" -> NvControlStyle.MINIMAL
    "streaming" -> NvControlStyle.STREAMING
    else -> NvControlStyle.CLASSIC
}
```

`PlayerPrefs.kt` 在现有键常量区（如 `KEY_HALF_LOCK_ENABLED` 旁）加：

```kotlin
/** 控制层样式（classic/minimal/streaming，解析见 parseControlStyle；缺失回退 classic） */
const val KEY_CONTROL_STYLE = "novax_control_style"
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令，Expected: PASS 3 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novax/app/player/ControlStyle.kt app/src/main/java/com/novax/app/player/PlayerPrefs.kt app/src/test/java/com/novax/app/player/ControlStyleTest.kt
git commit -m "feat(player): 控制层样式枚举+DataStore 键+脏值回退解析(三样式功能地基)"
```

---

### Task 2: 经典档紧凑化（尺寸与窄幅档位，TDD）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/PlayerControls.kt`（NvChapterSlider 热区/气泡锚点、NvPlayerBottomBar Column padding、NvPlayerControlRow padding 与槽位尺寸、nvNarrowControlMetrics）
- Test: `app/src/test/java/com/novax/app/player/NvControlMetricsTest.kt`（Create）

**Interfaces:**
- Consumes: 无。
- Produces: `nvNarrowControlMetrics(maxWidth: Dp): Pair<Dp, Dp>` 新档位（pause 50/45/40、btn 42/38/36）；新函数 `nvRowIconFor(btn: Dp): Dp`（btn×0.48，最小档固定 18dp 下限）——T7 streaming 底栏与 T6 复用。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/NvControlMetricsTest.kt
package com.novax.app.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NvControlMetricsTest {
    // 窄幅新档位（spec §3.1 表）：pause 50/45/40，btn 42/38/36
    @Test fun `窄幅档位紧凑化`() {
        assertEquals(50.dp to 42.dp, nvNarrowControlMetrics(400.dp))
        assertEquals(45.dp to 38.dp, nvNarrowControlMetrics(350.dp))
        assertEquals(40.dp to 36.dp, nvNarrowControlMetrics(300.dp))
    }
    @Test fun `icon 比例与最小档 18dp 下限`() {
        assertEquals(20.dp, nvRowIconFor(42.dp))   // 42*0.48=20.16 取整口径见实现
        assertEquals(18.dp, nvRowIconFor(36.dp))   // 36*0.48=17.3 → 夹到 18 下限
    }
}
```

注意：`dp` 相等断言用 `assertEquals(Dp, Dp)` 可直接工作（Dp 是 value class，equals 按值比较）。

- [ ] **Step 2: 跑测试确认失败**（同 T1 命令模式，`--tests "com.novax.app.player.NvControlMetricsTest"`；Expected: 断言失败——现档位 62/56/50 与 52/46/42）

- [ ] **Step 3: 实现（PlayerControls.kt 逐处修改）**

1) `nvNarrowControlMetrics`（约 L509）改档位并新增伴生函数：

```kotlin
internal fun nvNarrowControlMetrics(maxWidth: Dp): Pair<Dp, Dp> {
    val pauseSize = when {
        maxWidth >= 380.dp -> 50.dp
        maxWidth >= 330.dp -> 45.dp
        else -> 40.dp
    }
    val clusterGap = 2.dp
    val sideBtnCap = ((maxWidth - pauseSize) / 2 - clusterGap * 2) / 3f
    val btn = minOf(if (maxWidth >= 380.dp) 42.dp else if (maxWidth >= 330.dp) 38.dp else 36.dp, sideBtnCap)
    return pauseSize to btn
}

/** 窄幅行图标：btn×0.48，最小档（36dp）视觉过小 → 固定 18dp 下限（spec §3.1 / 评审 P2-1）。 */
internal fun nvRowIconFor(btn: Dp): Dp = (btn * 0.48f).coerceAtLeast(18.dp)
```

测试断言 `42.dp*0.48=20.16dp` 与 `20.dp` 不等——实现按四舍五入：`(btn * 0.48f).coerceAtLeast(18.dp)` 对 42dp 得 20.16dp。**测试与实现统一为不取整**：测试改 `assertEquals((42.dp * 0.48f), nvRowIconFor(42.dp))` 与 `assertEquals(18.dp, nvRowIconFor(36.dp))`（写测试时以这两行为准）。

2) NvPlayerControlRow（约 L544）：`.padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 9.dp)`（原 2/14）；注释保留返工⑥历史说明并追加一行「2026-11-15 紧凑化：top 2→1 / bottom 14→9（spec §3.1，布局与比例零变化）」。

3) 宽幅七键（约 L661-667）：`LockSlot(48.dp, 24.dp)` … `PauseSlot(58.dp, 30.dp)` … `SpeedSlot(48.dp, 24.dp)`（侧键 60→48、icon 28→24；暂停 72→58、icon 34→30）。

4) 窄幅行（约 L672-673）：`val rowIcon = nvRowIconFor(btn)`（原 `btn * 0.48f`）。

5) NvPlayerBottomBar Column（约 L461）：`.padding(bottom = 6.dp)`（原 10）。

6) NvChapterSlider：热区 `.height(48.dp)`（原 56，约 L831；注释更新「2026-11-15 紧凑化 56→48（用户拍板，spec §3.1）」）；拖动气泡锚点在气泡绘制处（约 L952-964）y 计算上移 8dp（`- 8.dp`，并注释「48dp 热区下旋钮顶 23dp vs 气泡底 30dp 重叠 7dp，上移消重叠——评审 P1-2」）。时间贴轨 Text 与轨道视觉值（5/8dp）不动。

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

Run: `./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest --console=plain`
Expected: 1316+新增全绿（既有测试对尺寸零断言，评审已核）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novax/app/player/PlayerControls.kt app/src/test/java/com/novax/app/player/NvControlMetricsTest.kt
git commit -m "feat(player): 经典档紧凑化——底栏 154→122dp(热区48/按钮48-58/padding 收敛/窄幅新档位/最小档 icon 18dp 下限)，布局与间隔比例零变化"
```

---

### Task 3: NvChapterSlider 视觉模式（line 模式，TDD 纯函数+接线）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/PlayerControls.kt`（NvChapterSlider）
- Test: `app/src/test/java/com/novax/app/player/NvSliderLineMetricsTest.kt`（Create）

**Interfaces:**
- Consumes: 无。
- Produces: `enum class NvSliderVisual { CLASSIC, LINE }`；`internal fun nvSliderLineMetrics(mode: NvSliderVisual): NvSliderMetrics`（data class：`trackYAnchor: (IntSize)->Float` 不可测，故 Metrics 只含可纯断言量：`hPaddingDp: Float`、`showsTimeRow: Boolean`、`showsThumb: Boolean`）；NvChapterSlider 新参数 `visual: NvSliderVisual = NvSliderVisual.CLASSIC`、`thickenOnDrag: Boolean = false`。T6/T7 依赖这些签名。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/NvSliderLineMetricsTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NvSliderLineMetricsTest {
    @Test fun `classic 模式保留时间行旋钮与 16dp 内边距`() {
        val m = nvSliderLineMetrics(NvSliderVisual.CLASSIC)
        assertEquals(16f, m.hPaddingDp, 0.01f)
        assertEquals(true, m.showsTimeRow)
        assertEquals(true, m.showsThumb)
    }
    @Test fun `line 模式贴缘无时间行无旋钮`() {
        val m = nvSliderLineMetrics(NvSliderVisual.LINE)
        assertEquals(0f, m.hPaddingDp, 0.01f)
        assertEquals(false, m.showsTimeRow)   // 时间由常驻层/顶栏承载（spec §5.2 口径）
        assertEquals(false, m.showsThumb)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**（Expected: unresolved reference）

- [ ] **Step 3: 实现**

PlayerControls.kt NvChapterSlider 区域（约 L771-968）：

```kotlin
/** NvChapterSlider 视觉模式（spec §5.2）：CLASSIC=粗轨+章节刻度+金属旋钮；LINE=贴缘细线（minimal 常驻/唤起与 streaming 底线共用）。 */
enum class NvSliderVisual { CLASSIC, LINE }

data class NvSliderMetrics(val hPaddingDp: Float, val showsTimeRow: Boolean, val showsThumb: Boolean)

internal fun nvSliderLineMetrics(mode: NvSliderVisual): NvSliderMetrics = when (mode) {
    NvSliderVisual.CLASSIC -> NvSliderMetrics(16f, showsTimeRow = true, showsThumb = true)
    NvSliderVisual.LINE -> NvSliderMetrics(0f, showsTimeRow = false, showsThumb = false)
}
```

NvChapterSlider 签名加 `visual: NvSliderVisual = NvSliderVisual.CLASSIC, thickenOnDrag: Boolean = false`，内部：
- 行外 `padding(horizontal = 16.dp + trackInset)` 改 `padding(horizontal = metrics.hPaddingDp.dp + if (visual == NvSliderVisual.CLASSIC) trackInset else 0.dp)`
- 时间行 Text（约 L839-847）包 `if (metrics.showsTimeRow) { ... }`
- Canvas 轨道 y 锚（约 L902 `size.height - 14.dp.toPx()`）line 模式改贴缘 `size.height - 2.5.dp.toPx()`（3dp 细线中心）
- 旋钮绘制（约 L941-948）`if (metrics.showsThumb)` 包裹
- line 模式轨道宽：静息 3dp；`thickenOnDrag=true` 且拖动态 → 6dp（streaming 拖动加粗，spec §3.3）
- 轻点 tap-seek 反馈（tapPulse 约 L787-795）：line 模式无旋钮可放大 → 改为气泡短现（复用拖动气泡，`tapPulse` 驱动气泡 600ms 可见后淡出）
- **band 计量不动**：`onSliderBandPx(it.size.height + 24.dp.roundToPx())` 对两模式同样回调（热区 48dp 已由 T2 生效，band 自动=72px 口径）——此行不许改，评审按 Review Focus #3 抽查

- [ ] **Step 4: 跑测试通过 + 全量绿**（同 T2 Step 4 命令）

- [ ] **Step 5: 冒烟编译检查**：`./gradlew :app:compileDebugKotlin --console=plain`（默认 daemon）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/novax/app/player/PlayerControls.kt app/src/test/java/com/novax/app/player/NvSliderLineMetricsTest.kt
git commit -m "feat(player): NvChapterSlider 视觉模式参数 classic/line——一份手势内核换皮(line=贴缘细线/无时间行/无旋钮/tap 气泡反馈/streaming 拖动加粗)，band 计量两模式同口径"
```

---

### Task 4: 控制层契约 NvControlContract

**Files:**
- Create: `app/src/main/java/com/novax/app/player/NvControlContract.kt`
- Test: `app/src/test/java/com/novax/app/player/NvControlContractTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces: T6/T7 样式包与 T5 收拢顶栏的统一参数载体（精确签名如下，后续任务按此引用不得改名）。

- [ ] **Step 1: 写测试（契约完整性锚定——防后续任务悄悄改字段）**

```kotlin
// app/src/test/java/com/novax/app/player/NvControlContractTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NvControlContractTest {
    @Test fun `默认契约状态值安全`() {
        val c = NvControlContract()
        assertEquals(0L, c.positionMs); assertEquals(0L, c.bufferedMs); assertEquals(0L, c.durationMs)
        assertEquals(false, c.isPlaying); assertEquals(1f, c.speed, 0.001f)
        assertEquals(0, c.lockMode); assertEquals(15, c.skipSec)
        assertEquals(false, c.hasPrev); assertEquals(false, c.hasNext)
        assertEquals("", c.title)
    }
    @Test fun `样式无关的全量回调可空持有`() {
        val c = NvControlContract()
        c.onTogglePlay()          // 默认 no-op 不崩
        c.onMore()
        assertNotNull(c.onSeek)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**（Expected: unresolved reference: NvControlContract）

- [ ] **Step 3: 实现**

```kotlin
// app/src/main/java/com/novax/app/player/NvControlContract.kt
package com.novax.app.player

/**
 * 控制层统一契约（spec §5.1）：三样式包接收同一份状态+回调，各画各的。
 * 从 NvPlayerBottomBar 参数表扩展；字段即"控制层全部输入"，样式包不得自带业务状态。
 */
data class NvControlContract(
    // —— 状态 ——
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    /** 0=未锁 1=半锁 2=全锁（PlayerLockPolicy 口径） */
    val lockMode: Int = 0,
    val skipSec: Int = 15,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val title: String = "",
    /** Jellyfin 有质量档时为 true（streaming 顶栏/更多面板据此显隐清晰度入口） */
    val hasQuality: Boolean = false,
    // —— 回调（全部默认 no-op，样式包按需接线）——
    val onBack: () -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onSkipBy: (Long) -> Unit = {},
    val onPrev: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onSpeed: () -> Unit = {},
    val onLock: () -> Unit = {},
    val onSubtitle: () -> Unit = {},
    val onAudio: () -> Unit = {},
    val onAspect: () -> Unit = {},
    val onOrientation: () -> Unit = {},
    val onVideoMirror: () -> Unit = {},
    val onVideoRotate: () -> Unit = {},
    val onPip: () -> Unit = {},
    val onQuality: () -> Unit = {},
    val onInfo: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onMore: () -> Unit = {},
    /** 拖动松手 seek（line/classic 进度条共用） */
    val onSeek: (Long) -> Unit = {},
)
```

- [ ] **Step 4: 跑测试通过**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novax/app/player/NvControlContract.kt app/src/test/java/com/novax/app/player/NvControlContractTest.kt
git commit -m "feat(player): 控制层统一契约 NvControlContract——状态+18 回调单一载体(三样式包共用参数表)"
```

---

### Task 5: RMVB 顶栏收拢（四维参数化共享，TDD 顺序纯函数）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/PlayerControls.kt`（NvPlayerTopBar 参数化）
- Modify: `app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt`（删 ControlsTop 副本 L3024-3117，接共享顶栏；外层加顶栏渐变）
- Test: `app/src/test/java/com/novax/app/player/NvTopBarOrderTest.kt`（Create）

**Interfaces:**
- Consumes: T4 契约（本任务顶栏可暂不切换到契约——保持现有参数表+新增可空回调，降低回归面；样式包任务再统一）。
- Produces: `NvPlayerTopBar` 新参数 `order: List<NvTopAction> = NvTopBarOrderMain`、回调 `onPip/onQuality/onCast: (() -> Unit)? = null`（null 即不渲染该键——RMVB 传 null）；`enum class NvTopAction { SUBTITLE, AUDIO, ASPECT, ORIENTATION, MIRROR, ROTATE, PIP, QUALITY, INFO, CAST, MORE }`；`val NvTopBarOrderMain: List<NvTopAction>` 与 `val NvTopBarOrderRmvb: List<NvTopAction>`（现状顺序常量）。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/NvTopBarOrderTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NvTopBarOrderTest {
    @Test fun `主播放器现状顺序`() {
        assertEquals(
            listOf(NvTopAction.SUBTITLE, NvTopAction.AUDIO, NvTopAction.ASPECT, NvTopAction.ORIENTATION,
                NvTopAction.MIRROR, NvTopAction.ROTATE, NvTopAction.PIP, NvTopAction.QUALITY,
                NvTopAction.INFO, NvTopAction.CAST, NvTopAction.MORE),
            NvTopBarOrderMain)
    }
    @Test fun `RMVB 现状顺序(音轨在字幕前,无 PIP/QUALITY/CAST)` {
        assertEquals(
            listOf(NvTopAction.ASPECT, NvTopAction.ORIENTATION, NvTopAction.MIRROR, NvTopAction.ROTATE,
                NvTopAction.AUDIO, NvTopAction.SUBTITLE, NvTopAction.INFO, NvTopAction.MORE),
            NvTopBarOrderRmvb)
        assertEquals(false, NvTopBarOrderRmvb.contains(NvTopAction.PIP))
        assertEquals(false, NvTopBarOrderRmvb.contains(NvTopAction.QUALITY))
        assertEquals(false, NvTopBarOrderRmvb.contains(NvTopAction.CAST))
    }
}
```

（顺序常量须与两侧代码现状逐一对齐——执行本任务前先 `grep -n "IconButton" RmvbPlayerActivity.kt | sed -n '3070,3115p'` 亲核 RMVB 现状，与 spec §5.3 一致再写实现。）

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

1) PlayerControls.kt：`NvTopAction` 枚举+两个顺序常量+`NvPlayerTopBar` 加 `order: List<NvTopAction> = NvTopBarOrderMain`、`onPip: (() -> Unit)? = null`、`onQuality: (() -> Unit)? = null`、`onCast: (() -> Unit)? = null`、`aspectCurrent: String? = null` / `mirrorCurrent: String? = null` / `rotateCurrent: String? = null`（cd 带当前值口径，RMVB 传值）。动作区渲染改为 `order.forEach { action -> when (action) { ... } }`，`null` 回调的键跳过（`onPip == null && action == PIP -> return@forEach`）。容器补 displayCutout insets（RMVB 接入后自动获得——改善型微变③）。
2) RmvbPlayerActivity：整段删除 `ControlsTop()`（L3024-3117），调用点换 `NvPlayerTopBar(...现有参数..., order = NvTopBarOrderRmvb, onPip = null, onQuality = null, onCast = null, aspectCurrent = ..., mirrorCurrent = ..., rotateCurrent = ...)`；其宿主 Box 顶栏区**外层加与主播放器同款渐变**（从 PlayerScreen.kt L1787-1790 抄 Brush.verticalGradient 口径——渐变移外层=微变②；顶栏高度显式化=微变①）。

- [ ] **Step 4: 跑测试通过 + 全量绿**

- [ ] **Step 5: 编译**：`./gradlew :app:compileDebugKotlin --console=plain`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/novax/app/player/PlayerControls.kt app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt app/src/test/java/com/novax/app/player/NvTopBarOrderTest.kt
git commit -m "refactor(player): RMVB 顶栏收拢为共享 NvPlayerTopBar——四维参数化(键集可空/顺序列表保两侧现状/cd 带当前值/容器统一 56dp+cutout+外层渐变),删平行副本 ControlsTop"
```

---

### Task 6: minimal 包 + 两侧接线（常驻层可见性 TDD）

**Files:**
- Create: `app/src/main/java/com/novax/app/player/ControlStyleMinimal.kt`（MinimalTopBar + MinimalPersistentLayer）
- Modify: `app/src/main/java/com/novax/app/player/PlayerScreen.kt`（样式分发+常驻层槽位）
- Modify: `app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt`（同款接线）
- Test: `app/src/test/java/com/novax/app/player/MinimalGateTest.kt`（Create）
- Res: 五语言 `player_style_name_*` 等键（T9 统一命名，本任务先只加代码用到不了的键延后到 T8/T9——本任务无用户可见新文案，标题/总时长复用现有）

**Interfaces:**
- Consumes: T1 `NvControlStyle/parseControlStyle/KEY_CONTROL_STYLE`、T3 `NvSliderVisual.LINE`、T4 契约。
- Produces: `internal fun persistentLayerVisible(fullLocked: Boolean, hasError: Boolean, controlsShown: Boolean): Boolean`（T10 冒烟 6 依赖）；`@Composable internal fun MinimalTopBar(c: NvControlContract)`、`@Composable internal fun MinimalPersistentLayer(c: NvControlContract)`。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/MinimalGateTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalGateTest {
    // spec §3.2 门控矩阵 + 二轮 P1-2 唤起态顶替裁决
    @Test fun `未锁未唤起显示`() = assertEquals(true, persistentLayerVisible(false, false, false))
    @Test fun `半锁显示`() = assertEquals(true, persistentLayerVisible(false, false, false)) // 半锁不唤起控制层(单击拦截),controlsShown 恒 false
    @Test fun `全锁隐藏`() = assertEquals(false, persistentLayerVisible(true, false, false))
    @Test fun `错误态隐藏`() = assertEquals(false, persistentLayerVisible(false, true, false))
    @Test fun `唤起态隐藏由 line 进度条顶替`() = assertEquals(false, persistentLayerVisible(false, false, true))
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

```kotlin
// app/src/main/java/com/novax/app/player/ControlStyleMinimal.kt
package com.novax.app.player

/**
 * 极简样式（spec §3.2）：常驻层=贴底细线+右下角时间小字（纯展示零热区）；
 * 唤起态=细顶栏(返回+标题+总时长+更多键)。唤起时常驻线隐藏，由门控内 line 进度条原位顶替。
 */

/** 常驻层可见性（spec §3.2 门控矩阵；半锁不唤起控制层故 controlsShown 恒 false=显示）。 */
internal fun persistentLayerVisible(fullLocked: Boolean, hasError: Boolean, controlsShown: Boolean): Boolean =
    !fullLocked && !hasError && !controlsShown

@Composable
internal fun MinimalPersistentLayer(c: NvControlContract) {
    // 零热区：不挂任何 pointerInput；细线+时间小字只读契约状态
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val w = size.width
        val played = if (c.durationMs > 0) (c.positionMs.toFloat() / c.durationMs) else 0f
        drawLine(NvColors.Unspecified.copy(alpha = 0.45f), Offset(0f, size.height - 1.5f), Offset(w, size.height - 1.5f), 3.dp.toPx())
        drawLine(NvColors.Primary, Offset(0f, size.height - 1.5f), Offset(w * played, size.height - 1.5f), 3.dp.toPx())
    }
    Text(formatClockMs(c.positionMs), color = Color.White.copy(alpha = 0.75f),
        style = NvTypography.LabelSmall,
        modifier = Modifier.align(BottomEnd).padding(end = 8.dp, bottom = 6.dp))
}

@Composable
internal fun MinimalTopBar(c: NvControlContract) {
    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = c.onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(20.dp), tint = Color.White)
        }
        Text(c.title, color = Color.White, style = NvTypography.LabelMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(formatClockMs(c.durationMs), color = Color.White.copy(alpha = 0.8f), style = NvTypography.LabelSmall)
        IconButton(onClick = c.onMore, modifier = Modifier.size(32.dp)) {   // P0-1：极简档唯一功能入口
            Icon(Icons.Outlined.MoreVert, null, Modifier.size(20.dp), tint = Color.White)
        }
    }
}
```

（`formatClockMs`/`NvColors`/`NvTypography` 用仓内现有符号——执行者 grep 同名函数，若时间格式化函数名不同以仓内为准；上图为结构蓝本。）

4) PlayerScreen.kt 接线：
- 读样式：`val controlStyle by rememberPlayerPrefs().let { ... }` 处取 `parseControlStyle(prefs[KEY_CONTROL_STYLE])`（对齐现有 `rememberPlayerPrefs()` 消费模式）。
- 组装契约 `val contract = NvControlContract(positionMs = ..., bufferedMs = ..., durationMs = ..., isPlaying = ..., speed = ..., lockMode = if (locked) 2 else if (halfLocked) 1 else 0, skipSec = ..., hasPrev = prevEntry != null, hasNext = nextEntry != null, title = ..., onXxx = 现有各回调)`（从 L1777-1996 顶/底栏现有调用点平移参数）。
- 门控外常驻层槽位（root Box 内、AnimatedVisibility 之外）：`if (controlStyle == NvControlStyle.MINIMAL && persistentLayerVisible(locked, errorMessage != null, controlsShown)) { MinimalPersistentLayer(contract) }`（align Bottom）。
- 唤起态分发：顶栏 AnimatedVisibility 内容 `when (controlStyle) { MINIMAL -> MinimalTopBar(contract); else -> NvPlayerTopBar(...) }`；底栏 AnimatedVisibility 内容 MINIMAL 分支只放 `NvChapterSlider(visual = NvSliderVisual.LINE, ...)` 一行（无 ControlRow——spec §3.2 中央与底部无按钮行）。
- 经典/streaming 分支暂走原 NvPlayerTopBar/NvPlayerBottomBar（streaming T7 接）。
5) RmvbPlayerActivity 对称接线（同 4，字段读 ui* 状态，回调接现有 uiControls 域函数；更多面板入口 `onMore` 打开其 NvPlayerPopup 更多弹层）。

- [ ] **Step 4: 跑测试通过 + 全量绿**

- [ ] **Step 5: 编译 + 手动快验**：`./gradlew :app:assembleDebug --console=plain`（模拟器验证延后 T10 统一冒烟）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/novax/app/player/ControlStyleMinimal.kt app/src/main/java/com/novax/app/player/PlayerScreen.kt app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt app/src/test/java/com/novax/app/player/MinimalGateTest.kt
git commit -m "feat(player): 极简样式包——常驻贴底细线+时间小字(零热区/锁态错误态隐藏/唤起由 line 进度条原位顶替)+细顶栏含更多键(P0-1 裁决)，主/RMVB 两侧接线"
```

---

### Task 7: streaming 包 + 两侧接线（窄幅规则 TDD）

**Files:**
- Create: `app/src/main/java/com/novax/app/player/ControlStyleStreaming.kt`（StreamingTopBar + StreamingBottomBar）
- Modify: `PlayerScreen.kt`、`RmvbPlayerActivity.kt`（分发接 streaming 分支）
- Test: `app/src/test/java/com/novax/app/player/StreamingRowTest.kt`（Create）

**Interfaces:**
- Consumes: T2 `nvNarrowControlMetrics/nvRowIconFor`、T3 `NvSliderVisual.LINE`（thickenOnDrag=true）、T4 契约、T5 `NvTopAction`（顶栏簇沿用按键可空思想）。
- Produces: `internal fun streamingRowSlots(narrow: Boolean): List<StreamingSlot>`（`enum class StreamingSlot { PREV, REW, PAUSE, FWD, NEXT, TIME, SPEED }`）。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/StreamingRowTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingRowTest {
    @Test fun `宽幅七槽位含倍速`() = assertEquals(
        listOf(StreamingSlot.PREV, StreamingSlot.REW, StreamingSlot.PAUSE, StreamingSlot.FWD, StreamingSlot.NEXT, StreamingSlot.TIME, StreamingSlot.SPEED),
        streamingRowSlots(narrow = false))
    @Test fun `窄幅去倍速(Review Focus 6)`() {
        val s = streamingRowSlots(narrow = true)
        assertEquals(false, s.contains(StreamingSlot.SPEED))   // 倍速经顶栏更多+长按仍可达
        assertEquals(true, s.contains(StreamingSlot.TIME))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

```kotlin
// app/src/main/java/com/novax/app/player/ControlStyleStreaming.kt
package com.novax.app.player

/** 长视频风（优酷式，spec §3.3）：图标簇顶栏+单行底栏+贴底线（line 模式拖动加粗）。 */

enum class StreamingSlot { PREV, REW, PAUSE, FWD, NEXT, TIME, SPEED }

/** 窄幅（<480dp）去倍速钮——倍速经顶栏更多与长按手势仍可达（spec §3.3）。 */
internal fun streamingRowSlots(narrow: Boolean): List<StreamingSlot> =
    if (narrow) listOf(StreamingSlot.PREV, StreamingSlot.REW, StreamingSlot.PAUSE, StreamingSlot.FWD, StreamingSlot.NEXT, StreamingSlot.TIME)
    else StreamingSlot.entries.toList()

@Composable
internal fun StreamingTopBar(c: NvControlContract, hasCast: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = c.onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(26.dp), tint = Color.White) }
        Text(c.title, color = Color.White, style = NvTypography.TitleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (hasCast) IconButton(onClick = c.onCast, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Cast, null, Modifier.size(26.dp), tint = Color.White) }  // RMVB 宿主 hasCast=false 自动减项(spec §3.3)
        IconButton(onClick = c.onSubtitle, modifier = Modifier.size(48.dp)) { /* Subtitles 图标 26dp */ }
        IconButton(onClick = c.onAudio, modifier = Modifier.size(48.dp)) { /* Audiotrack 图标 26dp */ }
        IconButton(onClick = c.onMore, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, null, Modifier.size(26.dp), tint = Color.White) }
    }
}

@Composable
internal fun StreamingBottomBar(c: NvControlContract) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 9.dp)) {
        val slots = streamingRowSlots(maxWidth < 480.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            slots.forEach { slot -> when (slot) {
                StreamingSlot.PREV -> NvClusterButton(icon = Icons.Outlined.SkipPrevious, caption = null, enabled = c.hasPrev, contentDescription = null, size = 48.dp, iconSize = 24.dp, onClick = c.onPrev)
                StreamingSlot.REW -> NvSkipStepButton(rewind = true, sec = c.skipSec, size = 48.dp, iconSize = 24.dp) { c.onSkipBy(-c.skipSec * 1000L) }
                StreamingSlot.PAUSE -> NvGlassChip(modifier = Modifier.size(58.dp), shape = RoundedCornerShape(29.dp), fill = NvColors.GlassFillStrong, fillOverlay = true, onClick = c.onTogglePlay) { Icon(if (c.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(30.dp), tint = NvColors.TextPrimary) }
                StreamingSlot.FWD -> NvSkipStepButton(rewind = false, sec = c.skipSec, size = 48.dp, iconSize = 24.dp) { c.onSkipBy(c.skipSec * 1000L) }
                StreamingSlot.NEXT -> NvClusterButton(icon = Icons.Outlined.SkipNext, caption = null, enabled = c.hasNext, contentDescription = null, size = 48.dp, iconSize = 24.dp, onClick = c.onNext)
                StreamingSlot.TIME -> Text("${formatClockMs(c.positionMs)} / ${formatClockMs(c.durationMs)}", color = Color.White, style = NvTypography.LabelMedium)
                StreamingSlot.SPEED -> Box(Modifier.size(48.dp).clickable(onClick = c.onSpeed), contentAlignment = Alignment.Center) { /* 倍速文本/Speed 图标同经典 SpeedSlot 口径 */ }
            } }
        }
    }
}
```

（`NvClusterButton/NvSkipStepButton/NvGlassChip` 签名以仓内为准——上图结构蓝本，caption/contentDescription 按现有参数表填；字幕图标 `Icons.Outlined.Subtitles`。）

PlayerScreen/RmvbPlayerActivity 分发 `STREAMING ->` 分支：顶栏 `StreamingTopBar(contract)`、底栏 `Column { NvChapterSlider(visual = LINE, thickenOnDrag = true, ...); StreamingBottomBar(contract) }`。

- [ ] **Step 4: 跑测试通过 + 全量绿 + assembleDebug**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novax/app/player/ControlStyleStreaming.kt app/src/main/java/com/novax/app/player/PlayerScreen.kt app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt app/src/test/java/com/novax/app/player/StreamingRowTest.kt
git commit -m "feat(player): 长视频风样式包——图标簇顶栏(平铺不可滚动/无 cast 自动减项)+单行底栏(窄幅去倍速)+贴底线拖动加粗，主/RMVB 两侧接线"
```

---

### Task 8: 「更多」面板扩容两组 + 两侧（键集过滤 TDD）

**Files:**
- Modify: `app/src/main/java/com/novax/app/player/PlayerSheets.kt`（MoreActionsSheet 加「播放控制」组+「控制样式」组）
- Modify: `app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt`（NvPlayerPopup 更多弹层加同款两组）
- Test: `app/src/test/java/com/novax/app/player/QuickControlsFilterTest.kt`（Create）
- Res: 五语言新键（`player_quick_controls`（组名）、`player_style_group`（组名）、`player_style_classic/minimal/streaming`（三样式名）、各入口行复用现有 `player_sheet_*`/`player_cd_*` 文案键，仅锁行如缺则补 `player_quick_lock`）

**Interfaces:**
- Consumes: T1 `KEY_CONTROL_STYLE`（控制样式组写入）。
- Produces: `enum class QuickControlId { LOCK, PREV, NEXT, REW, FWD, SPEED, SUBTITLE, AUDIO, ASPECT, ORIENTATION, MIRROR, ROTATE, PIP, QUALITY, CAST, INFO }`；`internal fun quickControlsFor(isRmvb: Boolean, hasQuality: Boolean): List<QuickControlId>`。

- [ ] **Step 1: 写失败测试**

```kotlin
// app/src/test/java/com/novax/app/player/QuickControlsFilterTest.kt
package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QuickControlsFilterTest {
    @Test fun `主播放器全集 16 项`() = assertEquals(16, quickControlsFor(isRmvb = false, hasQuality = true).size)
    @Test fun `RMVB 剔除 PIP QUALITY CAST 三项(Review Focus 5)`() {
        val s = quickControlsFor(isRmvb = true, hasQuality = true)
        assertFalse(s.contains(QuickControlId.PIP)); assertFalse(s.contains(QuickControlId.QUALITY)); assertFalse(s.contains(QuickControlId.CAST))
        assertEquals(13, s.size)
    }
    @Test fun `无质量档时主播放器也隐藏 QUALITY`() =
        assertEquals(false, quickControlsFor(isRmvb = false, hasQuality = false).contains(QuickControlId.QUALITY))
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

```kotlin
enum class QuickControlId { LOCK, PREV, NEXT, REW, FWD, SPEED, SUBTITLE, AUDIO, ASPECT, ORIENTATION, MIRROR, ROTATE, PIP, QUALITY, CAST, INFO }

/** 「播放控制」组键集（spec §5.5）：RMVB 按 §5.3 键集剔除 PIP/清晰度/投屏；QUALITY 仅 Jellyfin 有质量档时。 */
internal fun quickControlsFor(isRmvb: Boolean, hasQuality: Boolean): List<QuickControlId> =
    QuickControlId.entries.filter { id ->
        val rmvbExcluded = isRmvb && (id == QuickControlId.PIP || id == QuickControlId.QUALITY || id == QuickControlId.CAST)
        val qualityHidden = id == QuickControlId.QUALITY && !hasQuality
        !rmvbExcluded && !qualityHidden
    }
```

PlayerSheets.kt MoreActionsSheet（约 L556-598）在 AB 循环组前加「播放控制」组：每项一行（复用面板现有行样式），点击调用与顶/底栏同源的回调（MoreActionsSheet 参数表加 `quickControls: List<QuickControlId>` 与对应回调 lambda——从 PlayerScreen 传入契约同源函数）；再加「控制样式」组：三行单选（选中态勾标），点击 `putPlayerPref(KEY_CONTROL_STYLE, "classic"/"minimal"/"streaming")` 即时生效。RmvbPlayerActivity 的更多 NvPlayerPopup（约 L2943-2987）加同款两组（`quickControlsFor(isRmvb = true, ...)`）。

- [ ] **Step 4: 跑测试通过 + 全量绿 + assembleDebug**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novax/app/player/PlayerSheets.kt app/src/main/java/com/novax/app/player/RmvbPlayerActivity.kt app/src/test/java/com/novax/app/player/QuickControlsFilterTest.kt app/src/main/res/values*/strings.xml
git commit -m "feat(player): 更多面板扩容——播放控制组(功能全集兜底,RMVB 剔 PIP/清晰度/投屏,质量档条件显隐)+控制样式组(播放中三选一直写 DataStore)，两侧面板同步"
```

---

### Task 9: 设置页样式行 + Canvas 预览卡（纵排三卡）

**Files:**
- Create: `app/src/main/java/com/novax/app/ui/settings/NvStylePreviewCard.kt`
- Modify: `app/src/main/java/com/novax/app/ui/settings/SettingsGroups.kt`（PlaybackGroupPage 加「控制层样式」卡：纵排三张 NvStylePreviewCard）
- Res: 五语言 `settings_control_style`（标题）、`player_style_classic/minimal/streaming`（T8 已加则复用）

**Interfaces:**
- Consumes: T1（读当前值/写入 KEY_CONTROL_STYLE）。
- Produces: `@Composable fun NvStylePreviewCard(style: NvControlStyle, selected: Boolean, onClick: () -> Unit)`（Canvas 迷你示意图）。

- [ ] **Step 1: 画预览卡（无 JVM 测试点——纯 Canvas 视觉，验证靠 T10 冒烟+终审；本步实现+编译）**

```kotlin
// app/src/main/java/com/novax/app/ui/settings/NvStylePreviewCard.kt
package com.novax.app.ui.settings

/** 控制层样式迷你预览卡（spec §7）：Canvas 示意（非截图），黑底圆角+NvColors 语言，纵排用。 */
@Composable
fun NvStylePreviewCard(style: NvControlStyle, selected: Boolean, onClick: () -> Unit) {
    NvCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), border = if (selected) BorderStroke(2.dp, NvColors.Primary) else null) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101014))) {
                // 通用：顶条（返回点+标题线）
                drawCircle(Color.White.copy(alpha = 0.8f), 2.5f, Offset(10f, 10f))
                drawRoundRect(Color.White.copy(alpha = 0.45f), cornerRadius = CornerRadius(2f), topLeft = Offset(18f, 8f), size = Size(34f, 4f))
                when (style) {
                    NvControlStyle.CLASSIC -> { /* 动作区格子串 4 个 + 底部两行：七点 + 粗线 */ }
                    NvControlStyle.MINIMAL -> { /* 底部贴缘细线 + 右下角时间点 */ }
                    NvControlStyle.STREAMING -> { /* 顶条右端四点 + 单行五点+时间线段 + 贴缘细线 */ }
                }
            }
            Text(stringResource(style.labelRes()), style = NvTypography.BodyLarge, modifier = Modifier.padding(start = 16.dp))
        }
    }
}
```

（`NvCard/BorderStroke/NvColors` 以仓内设置页现有卡组件为准；`style.labelRes()` 映射三语言键。三档示意绘制细节按 spec §7 逐条画全——执行者照 spec §7 三个要点清单实现，不留空 when 分支。）

- [ ] **Step 2: PlaybackGroupPage 接行**

在现有三张 NvSettingsCard 之后加第四张：标题 `settings_control_style`，内容 `Column { NvStylePreviewCard(CLASSIC, ...); NvStylePreviewCard(MINIMAL, ...); NvStylePreviewCard(STREAMING, ...) }`（纵排，spec §4/P2-3）；当前值读 `rememberPlayerPrefs()[KEY_CONTROL_STYLE]` 经 `parseControlStyle`，点击 `SettingsViewModel.set(KEY_CONTROL_STYLE, 字符串值)`。

- [ ] **Step 3: 编译 + 全量绿 + assembleDebug**

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/novax/app/ui/settings/NvStylePreviewCard.kt app/src/main/java/com/novax/app/ui/settings/SettingsGroups.kt app/src/main/res/values*/strings.xml
git commit -m "feat(settings): 控制层样式设置行——纵排三张 Canvas 迷你预览卡(风格统一/选中描边)，单选即写 DataStore"
```

---

### Task 10: 全量验证 + 模拟器冒烟（spec §9 清单执行）

**Files:**
- 无代码改动；证据目录 Create: `_emutest/smoke_styles/`（gitignored 路径规范与既往冒烟一致——**不入公开仓**，README+截图存私有仓 `E:\视频播放器apk\_emutest\smoke_styles\`）

**Interfaces:**
- Consumes: T1-T9 全部。
- Produces: 冒烟报告（README.md：7 项逐条 PASS/FAIL+截图引用）；发现问题的修复回灌对应任务（新增提交，不变更已完成任务语义）。

- [ ] **Step 1: 全量单测 + gitleaks**

Run: `./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest --console=plain`（全绿）
Run: `"C:\tools\gitleaks\gitleaks.exe" detect --source E:/NovaX-Player`（no leaks）

- [ ] **Step 2: 装模拟器跑 §9 冒烟 7 项**

模拟器启动：`ANDROID_AVD_HOME="D:/AndroidSdk/avds" "D:/AndroidSdk/emulator/emulator.exe" -avd novaxtest -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`（后台）；安装 release x86_64 包（`./gradlew :app:assembleRelease` 后 `adb install -r app/build/outputs/apk/release/app-x86_64-release.apk`；若签名冲突先 `adb uninstall com.novax.app`）。
逐项执行并截图（`adb exec-out screencap -p > ...`）：
1. 三套样式切换（设置页+更多面板两入口）×主播放器/RMVB 截图一致性（RMVB 用 rmvb 测试文件路由兜底页；注记 116 vs 122dp 现状差异）
2. 唤起态直接切换（形态当场变、无双线——Review Focus 2 现场核）
3. 经典紧凑化前后高度对比（本任务先装 T2 前后各截一张，或引用 git stash 构建对照——简化：装 ebc6367 构建截"前"、装 HEAD 构建截"后"）
4. 带内嵌字幕视频 minimal 唤起态字幕区无遮挡；经典档拖动气泡不压旋钮（中英两语言字号）
5. §3.4 功能对等总表逐格核对（18 入口×3 档）
6. minimal 常驻层四态（未锁显示/半锁显示/全锁隐藏/错误态隐藏——错误态可用断网 WebDAV 文件触发）
7. RMVB 顶栏收拢差异（顺序与现状一致、三处微变截图）

- [ ] **Step 3: 写冒烟 README**（私有仓 `E:\视频播放器apk\_emutest\smoke_styles\README.md`：7 项表格+证据文件名+偏差记录）

- [ ] **Step 4: 问题修复回灌**（如有；每修一处跑该任务测试+全量）

- [ ] **Step 5: 收尾 Commit**（如仅证据无代码改动则无 commit；有修复则逐条提交）

---

## Self-Review 记录

- **Spec 覆盖**：§3.1→T2；§3.2/常驻层→T6；§3.3→T7；§3.4 总表→T8（入口承载）+T10.5（核对）；§4 双入口→T8（更多面板）+T9（设置页）；§5.1 契约→T4；§5.2 样式包/分发/接线→T6/T7；§5.3 RMVB 收拢→T5；§5.5 面板扩容→T8；§6 存储→T1；§7 预览→T9；§9 测试→各任务+T10；§10 验收→T10。无缺口。
- **占位符**：T7 顶栏字幕图标/倍速槽、T9 三档 Canvas 绘制为结构蓝本+要点清单（UI 绘制细节指回 spec 相应节，非"TODO"式空洞）——按 skill 标准属可接受的实现指归；其余任务代码完整。
- **类型一致性**：`NvControlStyle/parseControlStyle/KEY_CONTROL_STYLE`（T1→T6/T8/T9）；`nvNarrowControlMetrics/nvRowIconFor`（T2→T7）；`NvSliderVisual/NvSliderMetrics/nvSliderLineMetrics`（T3→T6/T7）；`NvControlContract`（T4→T6/T7）；`NvTopAction/两个顺序常量`（T5）；`persistentLayerVisible`（T6→T10）；`streamingRowSlots/StreamingSlot`（T7）；`quickControlsFor/QuickControlId`（T8）——逐对核过。
- **Review Focus**：六条全部有归属（#1→T1、#2→T6+T10.2、#3→T3 band 口径、#4→T5、#5→T8、#6→T7）。
