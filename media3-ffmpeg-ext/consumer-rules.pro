# E4-5（发版审查）：JNI 按导出符号静态绑定（ffmpeg_jni.cc 断言恰 9 个 Java_ 符号）。
# 此前类存活依赖 AGP 默认 proguard-common.txt 的 native <methods> keep（隐式契约），
# 工具链升级即可能无声断掉 AC3/DTS/TrueHD 软解——显式锁死。库极小，全量 keep 无体积顾虑。
-keep class com.novax.media3.ffmpeg.** { *; }
