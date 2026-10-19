package com.novax.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/**
 * 真机双指捏合注入（2026-09-20 RMVB 缩放验证专用）：
 * adb `input swipe` 并发注入无法合成多点事件（两条流各带 pointer id 0 互相顶掉），
 * sendevent 需要 root；UiAutomation.injectInputEvent 是唯一免 root 的完整多点注入通道。
 *
 * 运行前提：RMVB 播放页已在前台（由 adb 侧导航），然后：
 *   adb shell am instrument -w -e class com.novax.app.PinchZoomInjectTest \
 *       com.novax.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 手势：两指从 (920,600)/(1720,600) 水平外扩到 (420,600)/(2220,600)——横屏播放画面
 * 中央区域的捏合放大（不进亮度/音量竖条、不触发倍速长按）。
 */
class PinchZoomInjectTest {

    @Test
    fun pinchOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        Thread.sleep(2_000)   // 等前台稳定（播放页由 adb 侧先打开）

        val steps = 14
        val start0 = 920f to 600f
        val start1 = 1720f to 600f
        val end0 = 420f to 600f
        val end1 = 2220f to 600f
        val down = SystemClock.uptimeMillis()
        var seq = 0

        fun pointer(id: Int) = MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        fun coord(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }

        fun inject(action: Int, props: Array<MotionEvent.PointerProperties>, coords: Array<MotionEvent.PointerCoords>) {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(
                down, t, action, props.size, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            val ok = automation.injectInputEvent(ev, true)
            check(ok) { "injectInputEvent failed at seq=$seq action=$action" }
            seq++
            Thread.sleep(70)
        }

        // DOWN：第一指落下
        inject(
            MotionEvent.ACTION_DOWN,
            arrayOf(pointer(0)),
            arrayOf(coord(start0.first, start0.second)),
        )
        // POINTER_DOWN：第二指落下（index 1）
        inject(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            arrayOf(pointer(0), pointer(1)),
            arrayOf(coord(start0.first, start0.second), coord(start1.first, start1.second)),
        )
        // MOVE：两指外扩
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            val x0 = start0.first + (end0.first - start0.first) * f
            val x1 = start1.first + (end1.first - start1.first) * f
            inject(
                MotionEvent.ACTION_MOVE,
                arrayOf(pointer(0), pointer(1)),
                arrayOf(coord(x0, 600f), coord(x1, 600f)),
            )
        }
        // POINTER_UP：第二指抬起（index 1），再 UP 第一指
        inject(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            arrayOf(pointer(0), pointer(1)),
            arrayOf(coord(end0.first, 600f), coord(end1.first, 600f)),
        )
        inject(
            MotionEvent.ACTION_UP,
            arrayOf(pointer(0)),
            arrayOf(coord(end0.first, 600f)),
        )
        // 手势结束后留窗给 UI 反馈（不调 waitForIdleSync：视频持续渲染时它可能永不空闲）
        Thread.sleep(1_000)
    }
}
