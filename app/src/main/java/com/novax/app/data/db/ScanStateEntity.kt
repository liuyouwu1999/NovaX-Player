package com.novax.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 扫描状态机（C12 §1.2；取消/失败保留已入库批次，增量语义） */
enum class ScanStatus { IDLE, RUNNING, DONE, FAILED, CANCELLED }

/**
 * 每源扫描状态（C12 §1.2，MediaDb v3 新增表，一源一行，持久化供冷启动 UI 读）。
 *
 * 扫描进行中的 currentDir（"正在扫描 xxx/…"）不落表：由 MediaScanner 的内存
 * StateFlow 实时广播（C12 §二.5），表里只存可持久化的最终态。
 */
@Entity(tableName = "scan_state")
data class ScanStateEntity(
    @PrimaryKey val sourceId: Long,   // 一源一行
    val status: ScanStatus,
    val scannedDirs: Int,             // 已遍历目录数
    val foundItems: Int,              // 已入库视频文件数
    val lastScanAt: Long,             // 最近一次扫描完成/中断时间
    val error: String? = null,        // 失败原因（中文，可直接展示）
)
