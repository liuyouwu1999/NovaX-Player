package com.novax.app.player

import androidx.media3.common.MediaMetadata
import java.net.URLDecoder

/**
 * 通知栏媒体卡片元数据(spec 2026-10-10 §4.1):title 恒在(空白回落路径末段)、
 * 副文字(artist 行)与封面字节可选。封面只走 artworkData 字节内嵌、不设 artworkUri——
 * SystemUI 对 URI 键优先加载而 app 私有 file:// 它读不到(静默失败),且两通道可能显示
 * 不一致的图;字节内嵌使 compat 位图与通知 largeIcon 同源(spec §4.4)。
 *
 * 纯数据 + 纯函数(JVM 单测);[toMediaMetadata] 是唯一触碰 media3 的转接薄层(真机验收覆盖)。
 */
data class NvNotificationMetadata(
    val title: String,
    val subtitle: String?,
    val artworkData: ByteArray?,
) {
    /** media3 渲染;PICTURE_TYPE_FRONT_COVER 为媒体封面惯例值(不参与系统映射,仅随数据存储) */
    fun toMediaMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder().setTitle(title)
        subtitle?.let { builder.setArtist(it) }
        artworkData?.let { builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return builder.build()
    }

    // ByteArray 成员:数据类默认引用比较会破坏相等语义,按内容实现
    override fun equals(other: Any?): Boolean =
        other is NvNotificationMetadata && title == other.title && subtitle == other.subtitle &&
            (artworkData?.contentEquals(other.artworkData) ?: (other.artworkData == null))

    override fun hashCode(): Int =
        (title.hashCode() * 31 + (subtitle?.hashCode() ?: 0)) * 31 + (artworkData?.contentHashCode() ?: 0)

    companion object {
        /**
         * 副文字(spec §4.1):队列播放「第X集 · 共Y集」;单文件/异常 index 回落源昵称;
         * 全空 null(系统模板隐藏 artist 行)。queueIndex 0-based(EngineSpec 语义,-1 守卫纯防御)。
         */
        fun subtitle(queueIndex: Int, queueSize: Int, sourceNickname: String?): String? = when {
            // 任务书原文为「共$queueSize集」:CJK 字符「集」是合法标识符字符,Kotlin 模板
            // 简单名会解析成变量 queueSize集 → Unresolved reference,故加花括号钉住边界
            queueSize > 1 && queueIndex >= 0 -> "第${queueIndex + 1}集 · 共${queueSize}集"
            else -> sourceNickname?.takeIf { it.isNotBlank() }
        }

        /**
         * 标题兜底:空白回落 URI/路径末段。novax:// 路径经 Uri.encode(NvDataSource.kt:231),
         * 末段是 %XX 形态,必须 URLDecoder 解码(裸 substringAfterLast 会显示乱码);解码失败原样。
         */
        fun displayTitle(title: String?, uriOrPath: String?): String {
            if (!title.isNullOrBlank()) return title
            val last = uriOrPath?.trim()?.trimEnd('/')?.substringAfterLast('/').orEmpty()
            val decoded = if (last.isEmpty()) last
            else runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
            return decoded.ifBlank { "视频" }
        }

        /** 组装入口:title 兜底 + 副标题空白折叠(调用方无需自行判空) */
        fun build(title: String?, uriOrPath: String?, subtitle: String?, artworkData: ByteArray?): NvNotificationMetadata =
            NvNotificationMetadata(
                title = displayTitle(title, uriOrPath),
                subtitle = subtitle?.takeIf { it.isNotBlank() },
                artworkData = artworkData,
            )
    }
}
