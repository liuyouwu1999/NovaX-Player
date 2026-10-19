package com.novax.app.data.cast

import com.novax.app.R

/**
 * i18n Task 8：cast 层用户可见文案经 text 缝解析——测试注入 zh 原句假映射，
 * 断言维持中文原句（生产由 CastSessionHolder 注入 appContext::getString；
 * 四语文案本身由 LocalizationKeyParityTest 锁定）。
 */
val FakeCastText: (Int) -> String = { res ->
    when (res) {
        R.string.data_cast_err_wifi_needed -> "投屏需要连接 Wi-Fi"
        R.string.data_cast_err_bad_tv_address -> "电视地址无效，无法投屏"
        R.string.data_cast_err_connect_timeout -> "投屏连接超时"
        R.string.data_cast_err_tv_cannot_fetch -> "电视无法取流，请检查网络"
        R.string.data_cast_err_cast_failed -> "投屏失败"
        R.string.data_cast_err_disconnected -> "投屏连接已中断"
        R.string.data_cast_ended_with_reason -> "投屏已结束：%1\$s"
        R.string.data_cast_ended_wifi_lost -> "Wi-Fi 已断开，投屏结束"
        R.string.data_cast_ended_metered -> "已切换到计量网络，投屏结束"
        R.string.data_cast_warn_unsupported_format -> "格式可能不受支持，黑屏请本机播放"
        R.string.data_cast_warn_dolby_vision -> "杜比视界可能黑屏，建议本机播放"
        R.string.data_cast_err_source_unreadable -> "片源无法读取（NAS 连接失败），已取消投屏"
        R.string.data_cast_err_source_stalled -> "片源读取停滞（NAS 无响应？），已取消投屏"
        R.string.data_cast_err_source_missing -> "媒体源不存在（可能已删除）"
        R.string.data_cast_err_service_not_ready -> "投屏服务未就绪，请重试"
        R.string.data_cast_err_generic -> "投屏操作失败"
        R.string.data_cast_err_tv_no_response -> "电视无响应，请检查网络"
        R.string.data_cast_err_action_unsupported -> "电视不支持该操作"
        R.string.data_cast_err_tv_rejected_args -> "电视拒绝该操作（参数无效）"
        R.string.data_cast_err_tv_out_of_sync -> "电视状态不同步，请重试"
        R.string.data_cast_err_tv_rejected_value -> "电视拒绝该操作（参数值无效）"
        R.string.data_cast_err_tv_action_failed -> "电视执行该操作失败"
        R.string.data_cast_err_tv_rejected_out_of_range -> "电视拒绝该操作（参数值越界）"
        R.string.data_cast_err_tv_out_of_memory -> "电视内存不足"
        R.string.data_cast_err_tv_needs_intervention -> "电视执行该操作失败（需人工干预）"
        R.string.data_cast_err_session_expired -> "电视投屏会话已失效，请重新投屏"
        R.string.data_cast_err_tv_not_ready_701 -> "电视还未就绪（701），已重试仍失败"
        R.string.data_cast_err_tv_failed_with_code -> "电视执行该操作失败（错误码 %1\$d）"
        R.string.data_cast_err_tv_timeout -> "电视响应超时，请检查网络"
        else -> res.toString()
    }
}
