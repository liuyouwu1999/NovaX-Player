package com.novax.app.player

import com.novax.app.R

/**
 * Jellyfin 清晰度档位（spec 2026-10-07 §6）：ORIGINAL 为原画静态流直连；
 * P20M–P2M 为服务端实时转码的 HLS 码率上限；AUTO 为"跟随带宽"（起播原画、
 * rebuffer 降档、稳定升档），阶梯之外的语义由 NvQualityAutoPolicy 解析。
 * 纯逻辑，无 Android 依赖，JVM 可测。
 * Task 7 i18n：档位名改 labelRes 资源承载（原 label:String 中文硬编码移除），
 * 消费端组合层 stringResource(labelRes) / 非组合层 context.getString(labelRes)。
 */
enum class NvQualityTier(
    /** 档位显示名资源（清晰度面板行；「原画」「自动」随应用语言，20M 等码率档各语言同文） */
    val labelRes: Int,
    /** 传给 PlaybackInfo MaxStreamingBitrate 的码率上限（bps） */
    val maxBitrateBps: Int,
) {
    ORIGINAL(R.string.player_quality_tier_original, Int.MAX_VALUE),
    P20M(R.string.player_quality_tier_20m, 20_000_000),
    P10M(R.string.player_quality_tier_10m, 10_000_000),
    P4M(R.string.player_quality_tier_4m, 4_000_000),
    P2M(R.string.player_quality_tier_2m, 2_000_000),
    AUTO(R.string.player_quality_tier_auto, Int.MAX_VALUE),
    ;

    /** 降一档（码率更低）；已是下界或 AUTO 返回 null */
    fun lower(): NvQualityTier? = when (this) {
        ORIGINAL -> P20M
        P20M -> P10M
        P10M -> P4M
        P4M -> P2M
        P2M -> null
        AUTO -> null
    }

    /** 升一档（码率更高）；已是上界或 AUTO 返回 null */
    fun higher(): NvQualityTier? = when (this) {
        ORIGINAL -> null
        P20M -> ORIGINAL
        P10M -> P20M
        P4M -> P10M
        P2M -> P4M
        AUTO -> null
    }

    companion object {
        /** 持久化名解析；null/未知值回默认档 AUTO（与 KEY_PLAYBACK_QUALITY 默认一致） */
        fun fromName(name: String?): NvQualityTier =
            entries.firstOrNull { it.name == name } ?: AUTO

        /**
         * 清晰度面板的可选档位（2026-10-08 用户需求，视频 App 同款「无此清晰度不显示」）：
         * 档位是转码码率上限——上限 ≥ 片源码率时服务器不转码、直接给原画（2026-10-08 实证，
         * Jellyfin 对此形态不给 TranscodingUrl），这些档位只是原画的重复项，应从面板隐藏。
         * [sourceBitrateBps] 为片源视频码率（未知 0 → 档位无法判定，全部显示，行为同旧版）。
         * ORIGINAL/AUTO 恒显示；转码档只保留上限严格低于源码率的（相等也不转，一并隐藏）。
         */
        fun availableTiers(sourceBitrateBps: Long): List<NvQualityTier> {
            val transcoding = if (sourceBitrateBps > 0) {
                entries.filter { it != ORIGINAL && it != AUTO && it.maxBitrateBps < sourceBitrateBps }
            } else {
                entries.filter { it != ORIGINAL && it != AUTO }
            }
            return listOf(ORIGINAL) + transcoding + AUTO
        }
    }
}
