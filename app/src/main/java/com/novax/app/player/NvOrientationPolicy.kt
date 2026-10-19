package com.novax.app.player

/**
 * 方向模式变化时的画面旋转策略(2026-10-14,spec §2.2):
 * 「画面旋转」是在当前方向内修正画面姿态的手段,不跨方向模式存活——模式真实变化即归零,
 * 防旋转虚拟画布布局跨窗口 resize 失步(真机小窗指纹 spec §0.1)。
 * 模式相等(回前台重申 applyOrientationMode 等)保持原值,防误清。
 * 泛型 T:主播放器传 OrientationMode 枚举(PlayerScreen.kt:197 private enum,不放大可见性),RMVB 页传 Int 模式(0/1/2)。
 */
object NvOrientationPolicy {
    fun <T> rotationAfterModeChange(oldMode: T?, newMode: T, currentRotation: Int): Int =
        if (oldMode != newMode) 0 else currentRotation

    /** 系统旋转锁定读数(true = 锁定,ACCELEROMETER_ROTATION=0)。只读,无需任何权限;
     *  缺省 1(未锁)与系统出厂缺省一致。永不写回——App 不改系统锁定(2026-10-17 用户红线)。 */
    fun isSystemRotationLocked(context: android.content.Context): Boolean =
        android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
            1,
        ) == 0

    /** 观察系统旋转锁定变化的注册 URI(两个播放器共用,ContentObserver 只读监听) */
    val rotationLockUri: android.net.Uri
        get() = android.provider.Settings.System.getUriFor(
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
        )

    /**
     * 系统旋转锁定状态 → 方向模式收敛(2026-10-17 用户需求:重力跟随听系统的,删自有设置):
     * - 锁定:自动·重力 → 收拢为当前实际朝向固定档(竖屏姿态→竖屏档、否则横屏档,画面不跳);
     *   已是固定档不动(锁定状态本就不跟重力)。
     * - 解锁:固定档 → 立即恢复自动·重力(用户拍板的对称语义);已自动不动。
     * 返回 null = 模式无需变化(调用方免重申、免清旋转、免 toast)。
     * 泛型 T:主播放器 OrientationMode 枚举(PlayerScreen.kt private enum,不放大可见性),
     * RMVB 页 Int 模式(0横/1竖/2自动)。
     */
    fun <T> modeAfterSystemRotationLockChange(
        systemLocked: Boolean,
        currentMode: T,
        autoMode: T,
        portraitMode: T,
        landscapeMode: T,
        actuallyPortrait: Boolean,
    ): T? = when {
        systemLocked && currentMode == autoMode ->
            if (actuallyPortrait) portraitMode else landscapeMode
        !systemLocked && currentMode != autoMode -> autoMode
        else -> null
    }
}
