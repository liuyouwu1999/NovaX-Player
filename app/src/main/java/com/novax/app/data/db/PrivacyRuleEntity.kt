package com.novax.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 隐私锁规则（隐私功能 Task 2，MediaDb v7 新增表）。
 *
 * 落盘零明文：目录路径只存 [dirCipher]（密文）+ [keyHash]（HMAC 确定性哈希）两列，
 * 运行时由 data/privacy/NvPrivacyIndex 全量解密进内存做前缀匹配，不依赖 SQL 明文查询。
 * GCM 随机 IV → 密文不确定，查重/唯一约束必须走 [keyHash]。
 *
 * - keyHash：HMAC-SHA256(sourceId + 分隔 + dirPath)；唯一约束/查重/改名跟随匹配键
 * - dirPath 语义：文件型源=源内目录路径（""=整源）；JELLYFIN=「jf:<文件夹 item id>」
 * - state：ACTIVE / ORPHANED（目标丢失待重挂）/ BROKEN（密文不可解）；
 *   ORPHANED/BROKEN 由消费方视为「覆盖全源」参与判定（fail-closed）
 * - 无外键（全库惯例）；删源级联走「DELETE FROM privacy_rules WHERE sourceId=?」手工清理
 */
@Entity(
    tableName = "privacy_rules",
    indices = [Index(value = ["sourceId", "keyHash"], unique = true)],
)
data class PrivacyRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: Long,
    val keyHash: String,
    val dirCipher: String,
    val displayCipher: String = "",
    val lockEnabled: Boolean = false,
    val incognitoEnabled: Boolean = false,
    val fingerprint: String? = null,
    val state: String = "ACTIVE",
    val createdAt: Long,
)
