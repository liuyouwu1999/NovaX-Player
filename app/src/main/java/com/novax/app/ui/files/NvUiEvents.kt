package com.novax.app.ui.files

/**
 * 跨 Tab 一次性 UI 事件槽（2026-09-15 首页改版）：
 * 首页「添加源」→ 切文件 Tab 并自动打开添加源向导。不引入事件总线/共享 VM——
 * 单向布尔槽，消费方（FilesScreen 组合进入时）读取即复位，未消费也不泄漏（bool 开关）。
 */
object NvUiEvents {

    @Volatile
    private var pendingAddSourceWizard: Boolean = false

    fun requestAddSourceWizard() {
        pendingAddSourceWizard = true
    }

    /** 读取并复位（消费式）；非请求期恒 false，重复调用安全 */
    fun consumeAddSourceWizard(): Boolean = pendingAddSourceWizard.also { pendingAddSourceWizard = false }
}
