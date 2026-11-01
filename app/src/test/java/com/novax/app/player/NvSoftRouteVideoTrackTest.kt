package com.novax.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-01 v1.0.0.13 软路由「视频轨缺失」token + 兜底页切集路由回主播放器（方案二）纯函数单测。
 *
 * 背景：用户 NAS 老编码 AVI（MPEG-4 ASP 无硬解路径）在主播放器「只出声没画面、零提示」，
 * 根因之一是既有视频轨缺失检测的 duration>0 护栏被「无视频轨→时长算不出」精准绕过（方案一，
 * 检测本体在 PlayerScreen 心跳里、依赖 Tracks 运行时，不在 JVM 单测域）；方案二为兜底页
 * 切集时把硬解可承载的目标交回主播放器（shouldRouteBackToMain）。
 */
class NvSoftRouteVideoTrackTest {

    // ---------------- 方案一：video_track token ----------------

    @Test
    fun `video_track token 识别为视频类`() {
        assertTrue(isSoftRouteVideoReason(SOFT_ROUTE_REASON_VIDEO))
        assertFalse(isSoftRouteVideoReason(SOFT_ROUTE_REASON_DV))
        assertFalse(isSoftRouteVideoReason(SOFT_ROUTE_REASON_AUDIO))
    }

    /** 迁移只清音轨类；新 video_track 记忆绝不能被迁移误清（回归锁） */
    @Test
    fun `迁移保留 video_track 记忆`() {
        val entries = mapOf(
            "novax_softroute_http://nas/old.avi" to SOFT_ROUTE_REASON_VIDEO,
            "novax_softroute_http://nas/old.mkv" to SOFT_ROUTE_REASON_AUDIO,
            "novax_softroute_http://nas/dv.mkv" to SOFT_ROUTE_REASON_DV,
        )
        assertEquals(
            setOf(
                "novax_soft_unsupported_audio_mimes",
                "novax_softroute_http://nas/old.mkv",
            ),
            planSoftRouteMigration(entries),
        )
    }

    // ---------------- 方案二：shouldRouteBackToMain ----------------

    @Test
    fun `普通硬解文件交回主播放器`() {
        assertTrue(RmvbPlayerActivity.shouldRouteBackToMain("http://nas/a.mp4", null))
        assertTrue(RmvbPlayerActivity.shouldRouteBackToMain("http://nas/b.mkv", null))
    }

    @Test
    fun `兜底扩展名留在兜底内核`() {
        for (u in listOf("rm", "rmvb", "wmv", "asf", "wma", "ape", "aiff", "aif", "avi")) {
            assertFalse("ext=$u", RmvbPlayerActivity.shouldRouteBackToMain("http://nas/x.$u", null))
        }
    }

    /** 有视频轨缺失记忆的目标不再回主播放器（记忆语义：这台设备主播放器带不动） */
    @Test
    fun `video_track 记忆阻止回路由`() {
        assertFalse(
            RmvbPlayerActivity.shouldRouteBackToMain(
                "http://nas/old.mkv",
                SOFT_ROUTE_REASON_VIDEO,
            ),
        )
        // DV 记忆同样阻止（既有语义不变）
        assertFalse(
            RmvbPlayerActivity.shouldRouteBackToMain(
                "http://nas/dv.mkv",
                SOFT_ROUTE_REASON_DV,
            ),
        )
    }

    /** 音轨类记忆读侧已退役：不得阻止回路由（与 PlaybackService.softRouteOf 同口径） */
    @Test
    fun `音轨类记忆不阻止回路由`() {
        assertTrue(
            RmvbPlayerActivity.shouldRouteBackToMain(
                "http://nas/a.mp4",
                SOFT_ROUTE_REASON_AUDIO,
            ),
        )
        assertTrue(
            RmvbPlayerActivity.shouldRouteBackToMain(
                "http://nas/a.mp4",
                "音轨 DTS",
            ),
        )
    }

    /** 脏尾巴与 query 容错：与 isFallbackTarget 同式（2026-10-26 口径不回归） */
    @Test
    fun `脏尾巴与 query 不影响判定`() {
        assertTrue(RmvbPlayerActivity.shouldRouteBackToMain("file:///sdcard/x.mp4 ", null))
        // v1.0.0.14 起 avi 属兜底扩展名：query 脏尾巴不影响「留在兜底内核」判定
        assertFalse(RmvbPlayerActivity.shouldRouteBackToMain("novax://1/dir/a.avi?token=x", null))
        assertFalse(RmvbPlayerActivity.shouldRouteBackToMain("novax://1/dir/b.rmvb?token=x", null))
    }
}
