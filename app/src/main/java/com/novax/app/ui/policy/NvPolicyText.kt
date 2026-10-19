package com.novax.app.ui.policy

import androidx.annotation.StringRes
import com.novax.app.R

/**
 * 隐私政策与用户协议文本结构（App 内唯一展示源）。
 *
 * 四语国际化起，正文全部改为字符串资源（四个语言目录下的 strings_policy.xml，键
 * policy_p{N}_t / policy_p{N}_p{M} 与 policy_ua{N}_*）；本文件只持有资源 ID，
 * 展示端用 stringResource/context.getString 解析。简体中文（values-zh-rCN）为
 * 合规基准文本，仓库根 PRIVACY.md / USER_AGREEMENT.md 以
 * values-zh-rCN/strings_policy.xml 为源人工同步，供下载页/仓库直接阅读——
 * 两处改动必须同一次提交。
 *
 * 合规口径（2026-10-22）：本应用不接入任何统计/广告/上报 SDK，全部数据仅存设备本地；
 * 文本以四部门《App违法违规收集使用个人信息行为认定方法》与主流商店审核要求为基线撰写。
 */
object NvPolicyText {

    /** 政策版本：收集范围/权限用途等重大变更时 +1，首启弹窗按此重新征求同意 */
    const val VERSION = 2
    const val LAST_UPDATED = "2026-10-22"

    data class Section(@StringRes val title: Int, val paragraphs: List<Int>)

    val privacy: List<Section> = listOf(
        Section(R.string.policy_p1_t, listOf(R.string.policy_p1_p1, R.string.policy_p1_p2)),
        Section(R.string.policy_p2_t, listOf(R.string.policy_p2_p1, R.string.policy_p2_p2)),
        Section(
            R.string.policy_p3_t,
            listOf(
                R.string.policy_p3_p1,
                R.string.policy_p3_p2,
                R.string.policy_p3_p3,
                R.string.policy_p3_p4,
                R.string.policy_p3_p5,
                R.string.policy_p3_p6,
            ),
        ),
        Section(R.string.policy_p4_t, listOf(R.string.policy_p4_p1)),
        Section(
            R.string.policy_p5_t,
            listOf(
                R.string.policy_p5_p1,
                R.string.policy_p5_p2,
                R.string.policy_p5_p3,
                R.string.policy_p5_p4,
                R.string.policy_p5_p5,
                R.string.policy_p5_p6,
                R.string.policy_p5_p7,
                R.string.policy_p5_p8,
                R.string.policy_p5_p9,
                R.string.policy_p5_p10,
                R.string.policy_p5_p11,
                R.string.policy_p5_p12,
                R.string.policy_p5_p13,
                R.string.policy_p5_p14,
            ),
        ),
        Section(R.string.policy_p6_t, listOf(R.string.policy_p6_p1)),
        Section(R.string.policy_p7_t, listOf(R.string.policy_p7_p1)),
        Section(R.string.policy_p8_t, listOf(R.string.policy_p8_p1)),
        Section(R.string.policy_p9_t, listOf(R.string.policy_p9_p1)),
    )

    val agreement: List<Section> = listOf(
        Section(R.string.policy_ua1_t, listOf(R.string.policy_ua1_p1)),
        Section(R.string.policy_ua2_t, listOf(R.string.policy_ua2_p1)),
        Section(R.string.policy_ua3_t, listOf(R.string.policy_ua3_p1)),
        Section(R.string.policy_ua4_t, listOf(R.string.policy_ua4_p1)),
        Section(R.string.policy_ua5_t, listOf(R.string.policy_ua5_p1)),
        Section(R.string.policy_ua6_t, listOf(R.string.policy_ua6_p1)),
        Section(R.string.policy_ua7_t, listOf(R.string.policy_ua7_p1)),
        Section(R.string.policy_ua8_t, listOf(R.string.policy_ua8_p1)),
    )
}
