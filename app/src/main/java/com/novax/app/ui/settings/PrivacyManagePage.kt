@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novax.app.ui.settings

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.data.NvPaths
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.PrivacyRuleEntity
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.db.ThumbState
import com.novax.app.data.privacy.NvPrivacyCipher
import com.novax.app.data.privacy.NvPrivacyCrypto
import com.novax.app.data.privacy.NvPrivacyIndex
import com.novax.app.data.privacy.NvPrivacyManagePolicy
import com.novax.app.data.privacy.NvPrivacyPrefs
import com.novax.app.data.privacy.NvSecureWipe
import com.novax.app.data.scanner.NvScannerHolder
import com.novax.app.ui.components.NvConfirmDialog
import com.novax.app.ui.components.NvEntryRow
import com.novax.app.ui.components.NvSettingsCard
import com.novax.app.ui.components.NvSettingsRow
import com.novax.app.ui.components.NvShape
import com.novax.app.ui.components.NvSpacing
import com.novax.app.ui.components.NvSwitchRow
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.privacy.NvPinChangeSheet
import com.novax.app.ui.privacy.NvSettingsFlagSecureEffect
import com.novax.app.ui.privacy.rememberNvForgotResetFlow
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「隐私保护」管理页（隐私计划 Task 16，spec §4.5）：修改 PIN（验旧）/ 指纹开关（不支持置灰）/
 * 规则管理列表（源 + 解密路径/展示名 + 两开关状态 + ACTIVE/ORPHANED/BROKEN 徽标；
 * ORPHANED/BROKEN 行「重挂到…/解除」，每次变更操作逐次验 PIN——闸在宿主 PrivacyGroupPage）/
 * 忘码重置（设备凭据 → resetAll）/ 说明文案四条。
 *
 * 行解密口径：与 NvPrivacyIndex.replaceAll 同一 fail-closed 语义（decrypt null → 路径未知），
 * 但直接从实体行内解密——若经 access.index.views() 读快照，本页 observeAll 监听与 Access
 * 常驻监是两个协程，emission 顺序无保证，会短暂呈现「新 state + 旧解密」的错位组合。
 */

/** 管理页变更操作（执行前逐次验 PIN；闸与执行都在宿主 PrivacyGroupPage） */
sealed interface ManageOp {
    data class Repoint(val ruleId: Long) : ManageOp
    data class Remove(val ruleId: Long) : ManageOp
}

/** 行渲染快照：实体 + 映射结果 + 行内解密出的路径/展示名 */
private data class RuleRowView(
    val entity: PrivacyRuleEntity,
    val row: NvPrivacyManagePolicy.RuleRow,
    val dirPath: String,
    val display: String,
)

@Composable
internal fun NvPrivacyManagePage(
    sources: List<SourceEntity>,
    onBack: () -> Unit,
    onRequestOp: (ManageOp) -> Unit,
) {
    val ctx = LocalContext.current
    val cipher = remember(ctx) { NvPrivacyCrypto.keystoreCipher() }
    val store = remember(ctx) { NvPrivacyPrefs(ctx, cipher) }
    val scope = rememberCoroutineScope()

    var showChange by remember { mutableStateOf(false) }
    var removeCandidate by remember { mutableStateOf<Long?>(null) }
    var askReset by remember { mutableStateOf(false) }
    val startForgot = rememberNvForgotResetFlow(onResetDone = onBack)

    // 管理页整页展示解密路径 → 挂载期防截屏（离组还原）
    NvSettingsFlagSecureEffect()
    // 系统返回键：先关本页回隐私组（后注册者优先于 SettingsScreen 的组级 BackHandler）
    BackHandler { onBack() }

    val rows by produceState<List<RuleRowView>>(emptyList(), ctx) {
        val dao = MediaDb.get(ctx).privacyRuleDao()
        dao.observeAll().collect { entities ->
            value = entities.map { e ->
                val dir = runCatching { cipher.decrypt(e.dirCipher) }.getOrNull().orEmpty()
                val disp = if (e.displayCipher.isEmpty()) "" else {
                    runCatching { cipher.decrypt(e.displayCipher) }.getOrNull().orEmpty()
                }
                RuleRowView(
                    entity = e,
                    row = NvPrivacyManagePolicy.rowOf(
                        NvPrivacyIndex.RuleView(
                            id = e.id, sourceId = e.sourceId, dirPath = dir, display = disp,
                            lockEnabled = e.lockEnabled, incognitoEnabled = e.incognitoEnabled,
                            wholeSource = true,
                        ),
                        e.state,
                    ),
                    dirPath = dir,
                    display = disp,
                )
            }
        }
    }

    // 指纹可用性（BIOMETRIC_WEAK 即可；28 以下框架对话框形态不齐，只收 28+，与 T12 同口径）
    var canAuth by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        canAuth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
            BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }
    val biometricOn by store.biometricEnabled.collectAsState(initial = false)

    SettingsGroupPage(title = stringResource(R.string.privacy_manage_title), onBack = onBack) {
        item {
            NvSettingsCard(null) {
                NvEntryRow(
                    stringResource(R.string.privacy_manage_change_pin),
                    subtitle = stringResource(R.string.privacy_manage_biometric_desc),
                    onClick = { showChange = true },
                )
                NvSwitchRow(
                    stringResource(R.string.privacy_manage_biometric),
                    checked = biometricOn,
                    enabled = canAuth,
                    subtitle = if (canAuth) stringResource(R.string.privacy_manage_biometric_desc)
                    else stringResource(R.string.privacy_manage_biometric_unavailable),
                    onChecked = { on ->
                        scope.launch(Dispatchers.IO) { runCatching { store.setBiometricEnabled(on) } }
                    },
                    showDivider = false,
                )
            }
        }
        item {
            NvSettingsCard(stringResource(R.string.privacy_rules_section)) {
                if (rows.isEmpty()) {
                    NvSettingsRow(
                        title = stringResource(R.string.privacy_rules_empty),
                        onClick = null,
                        showDivider = false,
                    )
                }
                rows.forEachIndexed { index, r ->
                    val src = sources.firstOrNull { it.id == r.entity.sourceId }
                    val sourceLabel = src?.nickname ?: stringResource(R.string.privacy_rule_source_gone)
                    val repointAllowed = r.row.canRepoint && src?.type != SourceType.JELLYFIN
                    NvSettingsRow(
                        title = ruleTitleOf(r),
                        subtitle = stringResource(
                            R.string.privacy_rule_flags,
                            stateLabel(r.row.lockEnabled),
                            stateLabel(r.row.incognitoEnabled),
                        ),
                        onClick = null,
                        showDivider = repointAllowed || r.row.canRemove || index < rows.lastIndex,
                    ) {
                        Text(
                            "$sourceLabel · ${protocolLabel(src)}",
                            style = NvTypography.LabelMedium,
                            color = NvColors.TextSecondary,
                            modifier = Modifier.padding(end = NvSpacing.S),
                        )
                        RuleBadge(r.row.badge)
                    }
                    if (repointAllowed) {
                        NvSettingsRow(
                            title = stringResource(R.string.privacy_action_repoint),
                            onClick = { onRequestOp(ManageOp.Repoint(r.entity.id)) },
                            showDivider = true,
                        )
                    }
                    if (r.row.canRemove) {
                        NvSettingsRow(
                            title = stringResource(R.string.privacy_action_remove),
                            danger = true,
                            onClick = { removeCandidate = r.entity.id },
                            showDivider = index < rows.lastIndex,
                        )
                    }
                }
            }
        }
        item {
            NvSettingsCard(stringResource(R.string.privacy_notes_title)) {
                NoteRow(R.string.privacy_note_recover_title, R.string.privacy_note_recover_body, true)
                NoteRow(R.string.privacy_note_clear_title, R.string.privacy_note_clear_body, true)
                NoteRow(R.string.privacy_note_incognito_title, R.string.privacy_note_incognito_body, true)
                NoteRow(R.string.privacy_note_jf_title, R.string.privacy_note_jf_body, false)
            }
        }
        item {
            NvSettingsCard(null) {
                NvEntryRow(
                    stringResource(R.string.privacy_manage_reset),
                    danger = true,
                    showDivider = false,
                    onClick = { askReset = true },
                )
            }
        }
    }

    if (showChange) {
        NvPinChangeSheet(onChanged = { showChange = false }, onDismiss = { showChange = false })
    }
    if (removeCandidate != null) {
        NvConfirmDialog(
            title = stringResource(R.string.privacy_remove_confirm_title),
            message = stringResource(R.string.privacy_remove_confirm_body),
            confirmText = stringResource(R.string.privacy_action_remove),
            danger = true,
            onConfirm = {
                val op = ManageOp.Remove(removeCandidate!!)
                removeCandidate = null
                onRequestOp(op)
            },
            onDismiss = { removeCandidate = null },
        )
    }
    if (askReset) {
        NvConfirmDialog(
            title = stringResource(R.string.privacy_manage_reset_confirm_title),
            message = stringResource(R.string.privacy_manage_reset_confirm_body),
            confirmText = stringResource(R.string.settings_reset_action),
            danger = true,
            onConfirm = {
                askReset = false
                startForgot()
            },
            onDismiss = { askReset = false },
        )
    }
}

/** 行标题按映射结果取值：展示名优先 → 解密路径 → 整源/未知占位 */
@Composable
private fun ruleTitleOf(r: RuleRowView): String = when (r.row.titleSource) {
    NvPrivacyManagePolicy.TitleSource.DISPLAY -> r.display
    NvPrivacyManagePolicy.TitleSource.DIR_PATH -> r.dirPath
    NvPrivacyManagePolicy.TitleSource.WHOLE_SOURCE ->
        if (r.row.badge == NvPrivacyManagePolicy.Badge.ACTIVE) stringResource(R.string.privacy_rule_whole_source)
        else stringResource(R.string.privacy_rule_path_unknown)
}

@Composable
private fun stateLabel(on: Boolean): String =
    stringResource(if (on) R.string.privacy_state_on else R.string.privacy_state_off)

/** 源类型短标签（协议专名白名单，不资源化——与 subLangLabel 同惯例） */
private fun protocolLabel(src: SourceEntity?): String = when (src?.type) {
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.SMB -> "SMB"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.SFTP -> "SFTP"
    SourceType.LOCAL -> "LOCAL"
    null -> ""
}

@Composable
private fun RuleBadge(badge: NvPrivacyManagePolicy.Badge) {
    val text = stringResource(
        when (badge) {
            NvPrivacyManagePolicy.Badge.ACTIVE -> R.string.privacy_badge_active
            NvPrivacyManagePolicy.Badge.ORPHANED -> R.string.privacy_badge_orphaned
            NvPrivacyManagePolicy.Badge.BROKEN -> R.string.privacy_badge_broken
        },
    )
    val tint = if (badge == NvPrivacyManagePolicy.Badge.ACTIVE) NvColors.TextSecondary else NvColors.Error
    Text(
        text,
        Modifier
            .background(NvColors.Skeleton, NvShape.Badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = NvTypography.LabelSmall,
        color = tint,
    )
}

@Composable
private fun NoteRow(titleRes: Int, bodyRes: Int, showDivider: Boolean) {
    NvSettingsRow(
        title = stringResource(titleRes),
        subtitle = stringResource(bodyRes),
        onClick = null,
        showDivider = showDivider,
    )
}

// ---------------- 操作执行（宿主 PIN 验过才调用；调用方持主线程 scope，Toast 在 Main 侧弹） ----------------

/**
 * 「重挂到…」（已验 PIN）：暂存 ruleId 进 NvPrivacyPrefs → 宿主跳文件页，T13「挂载到此处」
 * 完成回传后自行清除（completeRepointAt）。返回 false = 暂存失败（加密/存储不可用）。
 */
internal suspend fun executeRepoint(context: Context, ruleId: Long): Boolean =
    runCatching { NvPrivacyPrefs(context, NvPrivacyCrypto.keystoreCipher()).setPendingRepointRuleId(ruleId) }
        .onFailure { Log.w("NvPrivacyManage", "NvPrivacy repoint persist failed: ${it.javaClass.simpleName}") }
        .isSuccess

/**
 * 「解除」（已验 PIN）：deleteById 包 NvSecureWipe.wiping 真抹除管道（SQLite 删行≠抹除，
 * WAL/空闲页留明文）；顺手清指向本规则的 pendingRepoint（防文件页幽灵「挂载到此处」）；
 * 解除后范围恢复（占位缩略图重置 + 整源重扫，对齐 T13 移除锁口径）。返回 false = 删行失败。
 */
internal suspend fun executeRemove(context: Context, ruleId: Long): Boolean = runCatching {
    val db = MediaDb.get(context)
    val dao = db.privacyRuleDao()
    val cipher = NvPrivacyCrypto.keystoreCipher()
    val rule = dao.all().firstOrNull { it.id == ruleId } ?: return@runCatching
    NvSecureWipe.wiping(db) { dao.deleteById(ruleId) }
    Log.w("NvPrivacyManage", "NvPrivacy rule removed from settings: rule=$ruleId")
    val store = NvPrivacyPrefs(context, cipher)
    if (runCatching { store.pendingRepointRuleId() }.getOrNull() == ruleId) {
        runCatching { store.setPendingRepointRuleId(null) }
    }
    restoreScopeAfterRemove(context, rule, cipher)
}.onFailure {
    Log.w("NvPrivacyManage", "NvPrivacy rule remove failed: ${it.javaClass.simpleName}")
}.isSuccess

/** 解除后的范围恢复：DONE+null 占位缩略图重置（路径可解时）+ 整源重扫（BROKEN 路径未知也覆盖） */
private suspend fun restoreScopeAfterRemove(context: Context, rule: PrivacyRuleEntity, cipher: NvPrivacyCipher) {
    runCatching { cipher.decrypt(rule.dirCipher) }.getOrNull()?.let { dir ->
        resetBlockedThumbsInScope(context, rule.sourceId, dir)
    }
    runCatching {
        val scanner = NvScannerHolder.get(context)
        scanner.cancelAndJoin(rule.sourceId)
        MediaDb.get(context).sourceDao().getById(rule.sourceId)?.let { scanner.startScan(it) }
    }.onFailure {
        Log.w("NvPrivacyManage", "NvPrivacy scope rescan failed: ${it.javaClass.simpleName}")
    }
}

/** 必做 B（T10/T13 口径）：DONE+null 双条件占位行重置为 NONE，解除后重新走正常抽帧认领 */
private suspend fun resetBlockedThumbsInScope(context: Context, sourceId: Long, dirPath: String) {
    runCatching {
        val dir = NvPaths.normalize(dirPath)
        val dao = MediaDb.get(context).mediaItemDao()
        val paths = dao.allPaths(sourceId).filter { NvPrivacyIndex.isUnderDir(it, dir) }
        paths.chunked(500).flatMap { runCatching { dao.findByPaths(sourceId, it) }.getOrDefault(emptyList()) }
            .forEach { row ->
                if (row.thumbState == ThumbState.DONE.name && row.thumbnailPath == null) {
                    dao.resetThumb(row.id)
                }
            }
    }.onFailure {
        Log.w("NvPrivacyManage", "NvPrivacy thumb reset skipped: ${it.javaClass.simpleName}")
    }
}
