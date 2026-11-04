# NovaX 发版流程文档（三期 §3.4 固化首版）

> 本文档固化 2026-11-03 三期安全整改 §3.4「发版 R8 冒烟清单」（出处 spec 在私有仓归档，公开仓不含内部文档）。
> **§3.4 验收 = 下方清单逐项打勾。** 后续发版沿用本清单；增补条目时在本文件追加并注明来源 spec/任务。

发版主干流程（既有惯例，不在本清单重复展开）：
版本号/版本码定档（nvVersionBase/nvReleaseIndex）→ 双变体构建 → `aapt dump badging` 核验 versionCode/Name → 归档 `_release/`（gitignored）+ SHA256SUMS → 安全终审 + gitleaks → push + tag → `gh release`（走代理）。
发布前红线速查：严禁 `git push --tags`；拷贝文件后 `git update-index --chmod=+x gradlew`；终审必须在 push 之前做。

### SHA256SUMS 双文件约定（2026-11-04 起，v1.0.0.19 补丁实证）

每个 Release **必须同时上传两份校验和文件**（内容相同、命名不同）：

1. `SHA256SUMS-<版本>.txt`（带版本号）——本仓归档惯例，人类可辨；
2. `SHA256SUMS.txt`（固定名）——**旧版本 app 的应用内更新器依赖它**：v1.0.0.18 及更早的更新器
   精确匹配固定名（v1.0.0.19 起更新器双口径兼容，见 UpdateRepository.isSumsAsset）。
   缺固定名 → 旧 app「无法获取校验文件，不提供应用内安装」（v1.0.0.19 发版当日 vivo 实证）。

生成：`sha256sum NovaX-<版本>-*.apk > SHA256SUMS-<版本>.txt && cp SHA256SUMS-<版本>.txt SHA256SUMS.txt`，
`gh release create/upload` 时两份都带上。

### Release body 双段约定（2026-11-04 定稿，**自 v1.0.0.21 起执行**；v1.0.0.20 有意不带围栏）

Release 说明正文 = **markdown 列表说明 + 末尾 ```sha256 围栏**（不使用 ```changelog 围栏）：

- markdown 说明段：GitHub 网页正常渲染；应用内弹窗走 extractor 回退路径（v1.0.0.20 起
  stripMarkdown 跳围栏内容，哈希行不透出弹窗；v1.0.0.19 及更早弹窗会一次性看到哈希行）。
- ```sha256 围栏：内容逐行 `<64hex> *<apk文件名>`，**文件名必须与 APK 资产名逐字一致**
  （更新器按文件名键查 sums；v1.0.0.20 起更新器在 sums 资产取不到时回退解析此围栏——
  文件下载域名被墙而 api.github.com 可达的场景，校验和随 release JSON 零额外请求下发）。
- 两份 SHA256SUMS 资产照常上传（旧版本 app 只走资产通道）。
- **时点说明**：v1.0.0.20 发布时曾带围栏，当日用户实测 ≤1.0.0.19 旧弹窗透出 3 行哈希观感
  不可接受 → 020 body 围栏已撤（服务端编辑，016→020 应用内安装走资产通道不受影响）；
  围栏自 021 起进入 body——届时仅 ≤019 未更新用户见一次性哈希行，随更新消失。

---

## Release R8 真机冒烟清单

以下各项在 **release 变体**（minify+shrinkResources 开启的真机包）上逐项验证，预期表现逐条对照。
历版发版实际一直在做这些验证，本节将其固化为成文清单（2026-11-03 首版，出处三期 §3.4）。

| # | 冒烟项 | 预期表现 |
|---|---|---|
| 1 | ExoPlayer 硬解起播（本地源 + NAS 远程源各一） | 主播放器正常起播，画面声音同步；MP4/MKV 等硬解格式无黑屏、无冻帧 |
| 2 | VLC 兜底页起播（AVI / RMVB 路由） | 命中 FALLBACK_EXTENSIONS 的文件进入兜底播放器正常渲染；RMVB 经代理拉流播放连续、可拖动 |
| 3 | FFmpeg 音轨（老编码 AVI 出声） | 老编码 AVI 在主播放器起播后有声音（AC3/DTS/TrueHD 等 FFmpeg 软解音轨），视频轨正常 |
| 4 | DLNA 投屏发现与推送 | 设备列表能发现局域网 DLNA 渲染器；选中后推送片源，渲染器正常起播，停止/断开不残留播放 |
| 5 | SMB / SFTP / WebDAV 三协议源起播 | 三类 NAS 源各自完成目录浏览并起播至少一个视频；SFTP 首连出现主机指纹确认提示 |
| 6 | Room 迁移（旧版本覆盖装数据保留） | 用旧版本升级覆盖安装后首启，媒体库/源配置/播放记录均在，无崩溃、无空库 |
| 7 | release 包日志剥除核验 | `adb logcat \| grep "com.novax.app"` 应用侧无 Log.v/Log.d 输出（I/W/E 不在本项核验范围，出现不算失败）——R8 `-assumenosideeffects` 剥除生效（二期挂账 §10-1 销账项） |

### 历版实证备注（承接 AGENTS.md 既有记录）

- #7 在 v1.0.0.15 / v1.0.0.16 发版冒烟中已实证剥除有效（proguard 规则 `-assumenosideeffects android.util.Log.v/d`）。
- #2/#3 的路由与软解行为由 v1.0.0.13 / v1.0.0.14 发版回归覆盖（AVI 整体路由兜底页、VLC opensles 音频输出）。
- #5 的 SFTP 信任链（首连指纹/编辑保留/删源重置）由一期整改模拟器端到端冒烟覆盖（`_emutest/smoke_p1sec/`）。
