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
