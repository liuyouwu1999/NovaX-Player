package com.novax.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** NAS 源类型（Room 自动按 enum name 存为 TEXT） */
enum class SourceType { SMB, WEBDAV, SFTP, LOCAL, JELLYFIN }

/** 源最近一次连通性检查结果 */
enum class SourceStatus { UNKNOWN, OK, ERROR }

/**
 * NAS / 本地媒体源配置（Room 实体）。
 *
 * 字段语义按 [type] 解释：
 * - SMB：host = 主机名/IP，port = 445，domain = Windows 域（可空），shareOrPath = 共享名（如 "media"）
 * - WEBDAV：host = 主机名/IP（可带 "https://" 前缀强制 TLS），port = 5005/443…，shareOrPath = 服务根路径（如 "/dav"）
 * - SFTP：host = 主机名/IP，port = 22（2026-09-29 sshj 接入），shareOrPath = 根目录（如 "/home/nas/videos"，空 = 登录家目录）
 * - LOCAL：shareOrPath = 本地根目录绝对路径，host/port/domain/username/password 留空
 * - JELLYFIN：host = 裸 IP/域名（N3 新语义，加密由向导 HTTPS 开关决定、开=存 "https://host" 前缀；
 *   历史存量可带 "http(s)://IP:8096" 整段前缀，编辑回显自动剥离拆分），port = 独立端口字段（默认 8096，0 = 客户端按协议默认），shareOrPath = 登录后缓存的 Jellyfin userId（可空）
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: SourceType,
    val nickname: String,
    val host: String = "",
    val port: Int = 0,
    val domain: String = "",
    val username: String = "",
    // 存 NvSecrets 密文（"nvenc1:iv:ct"，Keystore AES-GCM；Keystore 不可用时降级明文原样落库）
    val password: String = "",
    val shareOrPath: String = "",
    val lastCheckedAt: Long = 0L,
    val status: SourceStatus = SourceStatus.UNKNOWN,
    val scrapeEnabled: Boolean = false,   // C12 §1.5：二期 TMDB 匹配是否对该源生效；一期恒 false
    // 扫描根目录集（向导第三步目录多选结果；JSON 字符串数组 TEXT，编解码见
    // data/scanner/ScanRoots）。null / 空 = 扫根目录（共享留空 SMB 先枚举共享，其余扫 "/"）；
    // 非空 = MediaScanner 逐根 BFS（上限/去重规则沿用）。
    val scanRoots: String? = null,
    // 排除目录集（文件页多选目录「从媒体库排除」；JSON 字符串数组 TEXT，编解码同 ScanRoots）。
    // null / 空 = 无排除；非空 = MediaScanner BFS 剪枝（目录及其子树整体不扫）+ 对账清除存量行。
    val excludedDirs: String? = null,
)
