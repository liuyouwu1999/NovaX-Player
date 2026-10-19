package com.novax.app.data.smb

/**
 * SMB/WebDAV 状态码错误文案映射（纯函数，无 Android 依赖，单测锁定）。
 *
 * 背景（2026-10-19 用户反馈）：扫描横幅原样透出 smbj 包装后的原始异常
 * 「operation homes: STATUS_OBJECT_PATH_NOT_FOUND (0xc000003a): Create failed for \\…」，
 * 用户既读不懂也无法行动。本对象把状态码常量名 / 0x 码匹配为可读原因，
 * 供扫描器（scan_state.error 落库前翻译）与向导/浏览器（mapConnectError）共用；
 * 原始异常文本保持不变（cause 链与日志仍可取证）。
 */
object SmbStatusText {

    /**
     * 「路径不存在」类失败：STATUS_OBJECT_PATH_NOT_FOUND（上级目录缺失，0xC000003A）、
     * STATUS_OBJECT_NAME_NOT_FOUND（末段缺失，0xC0000034）；WebDAV 的同义语义是 404。
     * 与权限/连接类错误的本质区别：服务器明确说「这个路径没有」——目录多半已被
     * 移动/改名（迅雷类自管共享的常态），重试无意义，上层可做降级处理。
     */
    fun missingPath(message: String): Boolean {
        val m = message.lowercase()
        return "status_object_path_not_found" in m ||
            "0xc000003a" in m ||
            "status_object_name_not_found" in m ||
            "0xc0000034" in m ||
            "http 404" in m
    }

    // missingPathReason() 随 i18n Task 8 删除：可读原因改由资源 data_scan_error_path_missing
    // (_with_dir) 承载（唯一调用方 MediaScanner.friendlyScanError 经 text 缝解析）。

    /**
     * 从原始异常文本提取服务器 UNC 路径（smbj 文案形如
     * 「Create failed for \\host\share\a\b」），供文案指出具体是哪个目录；无则 null。
     */
    fun uncPathOf(message: String): String? =
        Regex("""\\\\\S+""").find(message)?.value?.trimEnd('，', ',', '。', ')', '）', '」')
}
