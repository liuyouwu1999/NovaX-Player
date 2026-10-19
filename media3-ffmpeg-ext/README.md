# media3-ffmpeg-ext

FFmpeg 音频软解扩展模块（media3 1.8.0 `DecoderAudioRenderer` 移植 + JNI shim）。
只做四类格式的软解兜底：AC3 / E-AC3 / TrueHD / DTS，其余格式一律走 MediaCodec 硬解。

## 入库内容与「干净检出可构建」

仓库内 `src/main/jni/ffmpeg/` 是指向 `native/ffmpeg`（本地 FFmpeg 构建树）的 **junction**，
该路径整体被 .gitignore 忽略，仅四类内容以 git blob 形式入库（junction 本身不入库，
新克隆没有它）：

- `android-libs/<abi>/lib{avcodec,avutil,swresample}.a` —— 三 ABI 静态库（strip 后，二进制）；
- `libavcodec/*.h`（500 个）、`libavutil/*.h`（120 个，含 configure 生成的 `avconfig.h`）、
  `libswresample/*.h`（5 个）—— `ffmpeg_jni.cc` 编译期 `#include` 的全部公开头。

CMakeLists 的 include 路径与 IMPORTED .a 路径全部落在 `src/main/jni/ffmpeg/` 内，
因此上述入库内容自洽：干净检出后（无 junction、无 native 构建树）
`:media3-ffmpeg-ext:assembleDebug` 可直接编译。

注意：头文件来自 n4.4.4 构建树，`avconfig.h` 为三 ABI 共用的一份
（`AV_HAVE_BIGENDIAN 0` / `AV_HAVE_FAST_UNALIGNED 0`，Android 全 ABI 小端、取保守值，
与实际三 ABI 构建时的现实一致）。头文件在库里存 LF（autocrlf=input），编译无影响。

## 重建链（换 FFmpeg 版本 / 换机器时）

1. **clone FFmpeg**：`git clone --branch n4.4.4 --depth 1 https://git.ffmpeg.org/ffmpeg.git
   NovaX/media3-ffmpeg-ext/native/ffmpeg`（换版本则换 tag；LGPL 配置，不引入 GPL 组件）。
2. **建 junction**（管理员或开发者模式 CMD，在 `src/main/jni` 目录下执行）：
   `mklink /J ffmpeg ..\..\..\native\ffmpeg`
   —— 即让 `jni/ffmpeg` 指向 `media3-ffmpeg-ext/native/ffmpeg`。junction 只在开发机存在，永不入库。
3. **构建**（Git Bash）：
   `bash NovaX/media3-ffmpeg-ext/src/main/jni/build_ffmpeg.sh <FFMPEG_MODULE_PATH> <NDK_PATH> <HOST_PLATFORM> ac3 eac3 dca truehd`
   脚本为上游原样 vendor，逐 ABI configure→make→install-libs→make clean，
   产物落在（junction 视角）`src/main/jni/ffmpeg/android-libs/<abi>/`。
4. **strip .a**：`llvm-strip --strip-debug` 各 `android-libs/<abi>/lib*.a`（体积入库关键）。
5. **入库（junction 下普通 `git add` 不工作，走 hash-object 管道）**：

   ```bash
   cd <仓库根>
   for f in <临时组装目录>/{libavcodec,libavutil,libswresample}/*.h \
            <路径>/android-libs/*/*.a; do
     sha=$(git hash-object -w "$f")
     git update-index --add --cacheinfo "100644,$sha,<仓库内目标路径>"
   done
   ```

   头文件先在 junction 外的临时目录组装（只拷 `.h`，不拷 `.c/.asm/ffbuild`），
   避免 `hash-object` 触碰 junction 内路径。文件已 tracked 后 .gitignore 不再生效；
   忽略例外规则见根 `.gitignore`（`!…/android-libs/` 与 `!…/lib{avcodec,avutil,swresample}/`）。

## 契约：解码器集变更必须同步四处

| 同步点 | 位置 |
|---|---|
| FFmpeg 构建参数 | `build_ffmpeg.sh` 调用的 `--enable-decoder` 列表（见上） |
| 契约常量 | `NvFfmpegAudio.BUILT_DECODER_NAMES`（app 侧，`["ac3","eac3","dca","truehd"]`） |
| mime 白名单 | `FfmpegLibrary.getCodecName`（只映射实际编入的解码器，其余 mime 返回 null 走硬解） |
| 二进制+头重入库 | `.a` 与三目录 `.h` 经 hash-object 管道重新入库（同版本编译产物与新头必须成对更新） |

## Gradle daemon 编码约束

单测跑法：`./gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=GBK" :app:testDebugUnitTest`
（默认 daemon 已是 UTF-8；单测若遇 @argfile ClassNotFoundException 按根 `gradle.properties`
注释用上述 GBK 覆盖运行）。构建用默认 daemon，勿另起 `--no-daemon`。

## 许可

FFmpeg n4.4.4 以 LGPL-2.1+ 配置构建（无 GPL 组件），许可条目见 app 内「关于/许可证」页
（LGPL-2.1；FFmpeg 源码获取方式见 `native/ffmpeg` 上游仓库）。
