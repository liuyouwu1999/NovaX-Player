# NovaX R8 keep 规则（2026-09-15 C7）
# 反射/JNI/序列化面逐类保留；其余交给 R8 收缩。

# --- smbj / jcifs-ng / BouncyCastle / SLF4J（SMB 协议栈，大量反射与 SPI） ---
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
-keepclassmembers class * implements org.slf4j.spi.SLF4JServiceProvider { *; }

# --- Apache Mina SSHD / sshj 预留（未接 SFTP，无依赖则空转） ---
-dontwarn org.apache.sshd.**
-dontwarn com.trilead.**

# --- pinyin4j（反射读资源表） ---
-keep class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

# --- WebDAV/网络栈（OkHttp/JAXB 反射面；sardine-android 已于 2026-09-28 删除） ---
-dontwarn org.apache.http.**
-dontwarn javax.xml.**
-dontwarn okio.**

# --- kotlinx.coroutines / media3 仅保留被反射引用的（media3 内部基本无 app 侧反射需求） ---
-dontwarn kotlinx.coroutines.**

# --- 保留崩溃日志可读的行号（本地崩溃报告用） ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- xpp3（smbj 传递依赖）与 android 平台 XmlPullParser 冲突：合规修复 ---
-dontwarn org.xmlpull.**
-keep class org.xmlpull.** { *; }

# --- javax.el（smbj 事件总线 mbassy 的可选 EL 分支，Android 上永远走不到） ---
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.**
-keep class net.engio.mbassy.** { *; }

# --- 2026-09-16 审计 P2：release 剥除调试日志（播放心跳每 500ms 一条 NvHbDiag 等） ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# --- 2026-09-18 libVLC 兜底内核：JNI 按名回调 native ↔ Java，R8 重命名会直接崩 ---
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# --- C13 投屏：jUPnP 反射绑定设备/服务描述 XML，重命名直接哑火 ---
-keep class org.jupnp.** { *; }
-dontwarn org.jupnp.**
-dontwarn org.eclipse.jetty.**
# 2026-10-18 发布终审：Jetty 由 -dontwarn 升级为显式 keep——mapping.txt 实证 R8 已把
# org.eclipse.jetty.** 重命名（如 AbstractConnectionPool$EntryHolder -> c0）。jUPnP 对 Jetty
# 是直接引用链（可达类本就保留），基础投屏冒烟不炸；但 Jetty 内部存在 Class.forName 式
# 服务加载（HttpClient 的协议/内容工厂注册等），混淆名下走不到匹配分支属延迟引爆面。
# Jetty 经 jUPnP 引用后基本整包可达，keep 的体积代价可忽略
-keep class org.eclipse.jetty.** { *; }

# --- 2026-10-03 SFTP（sshj→eddsa）：EdDSAEngine 引用 Android 不存在的 JRE 类，R8 直接拒编 ---
# （纯构建期问题：这些类在 Android 上本来就没有，dontwarn 不改变任何运行时行为）
-dontwarn sun.security.x509.**
