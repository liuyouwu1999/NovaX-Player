package com.novax.app.data.update

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 更新功能持久化键（spec §2.4/§2.5）。
 * 存于 `Context.novaxUiPrefs`（DataStore 属性定义于 MainActivity.kt，internal）；
 * Task 9 的设置页/检查编排为读写端。
 */
internal val KEY_UPDATE_AUTO_CHECK = booleanPreferencesKey("novax_update_auto_check")
internal val KEY_UPDATE_LAST_CHECK_MILLIS = longPreferencesKey("novax_update_last_check_millis")
internal val KEY_UPDATE_DOWNLOAD_ID = longPreferencesKey("novax_update_download_id")
internal val KEY_UPDATE_PENDING_VERSION = stringPreferencesKey("novax_update_pending_version")

/**
 * 2026-10-26「忽略此版本」：用户在弹窗点「忽略此版本」后记录的 release tag——自动检查
 * 发现的 latest 与其相同则静默不弹（手动检查照常展示，弹窗仍可安装/再忽略）；出现更新的
 * tag 自然失配恢复提醒。单值口径（只忽略当前 latest）。
 */
internal val KEY_UPDATE_SKIPPED_TAG = stringPreferencesKey("novax_update_skipped_tag")
