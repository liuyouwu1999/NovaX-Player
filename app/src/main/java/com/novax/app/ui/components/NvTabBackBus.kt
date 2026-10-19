package com.novax.app.ui.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Tab 返回键协同（BUG：Tab 内下钻状态被 App 级「回上一个 Tab」劫持——
 * 文件夹里按返回跳到首页/文件根、合集里按返回跳到文件页文件夹）。
 *
 * 结构：底部 Tab 页各自拥有自己的 BackHandler 处理下钻返回（文件页浏览器/向导、
 * 库页合集），App 级 BackHandler 只在 Tab **处于根态** 时才做「回上一 Tab / 退出」。
 * 但 Compose BackHandler 的调度优先级 = 注册顺序，而注册顺序在 Activity
 * STOP→START（如播放器前台返回）后存在被框架重挂打乱的实测案例——因此 App 级
 * handler 开火时若 Tab 已上报「下钻中」，不跳 Tab，改经本总线把返回动作**路由回
 * Tab 内部**执行（各 Tab 页组合期收集）。双通道等价语义，杜绝跨 Tab 误跳。
 */
object NvTabBackBus {

    /** Tab 页请求总线：App 级 handler 发 route，对应 Tab 页收集并执行自己的下钻返回 */
    private val _inTabBack = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val inTabBack: SharedFlow<String> = _inTabBack

    /** 各 Tab 的「下钻中」快照（组合期经 SideEffect 回写；App 级 handler 同步读） */
    @Volatile var filesDrilled: Boolean = false
    @Volatile var libraryDrilled: Boolean = false

    const val ROUTE_FILES = "files"
    const val ROUTE_LIBRARY = "library"

    fun requestInTabBack(route: String) {
        _inTabBack.tryEmit(route)
    }

    /** 当前 Tab 是否处于下钻态（App 级 handler 判定用） */
    fun isDrilled(route: String?): Boolean = when (route) {
        ROUTE_FILES -> filesDrilled
        ROUTE_LIBRARY -> libraryDrilled
        else -> false
    }
}
