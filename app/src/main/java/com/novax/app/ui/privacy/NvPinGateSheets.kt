package com.novax.app.ui.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novax.app.R
import com.novax.app.data.db.MediaDb
import com.novax.app.data.privacy.CooldownMath
import com.novax.app.data.privacy.NvGateWiring
import com.novax.app.data.privacy.NvMonotonicClock
import com.novax.app.data.privacy.NvPrivacyAccess
import com.novax.app.data.privacy.NvPrivacyCrypto
import com.novax.app.data.privacy.NvPrivacyPrefs
import com.novax.app.data.privacy.NvPinManager
import com.novax.app.data.privacy.PinResult
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 PIN 闸面板（隐私计划 Task 16）：T12 两面板是「解锁规则」语义，本文件是「纯验证」
 * 语义——验证通过只回调调用方，绝不碰 NvPrivacySession。
 * - [NvPinVerifySheet]：进入隐私功能区/单次变更操作的逐次验 PIN 入口；
 * - [NvPinChangeSheet]：修改 PIN（验旧 → 新 PIN 两遍）；
 * - [rememberNvForgotResetFlow]：忘码重置共享流（设备凭据 → resetAll，SDK 分支与 T12 同表——
 *   NvGateWiring.credentialPath），验证/改 PIN/管理页三处复用。
 * 构造纪律：一律经 [NvPrivacyAccess.newPinManager] 工厂（绕过即忘码重置退化为普通删行）。
 *
 * FLAG_SECURE：挂载即对宿主窗口加防截屏，离组**清除**——设置窗口非长驻防截屏面，
 * 与 T12 播放器面板（默认不清除）语义相反，离组必须还原窗口原状。
 */

/**
 * 挂载即防截屏、离组还原（设置窗口语义，见文件头注释）；Task 16 管理页（展示解密路径）复用。
 * FLAG_SECURE 是窗口级非引用计数标志，而设置域面板嵌套（管理页 → 验证/改 PIN 面板）——
 * 内层面板离组直接 clear 会连带拆掉外层（管理页正展示解密路径）的防截屏，故按持有者计数，
 * 归零才清除。**仅限 MainActivity 单窗口的设置面板使用**（跨 Activity 共用计数会串窗口）；
 * 播放器窗口的防截屏归 T12 面板自身逻辑，与本计数互不相干。
 */
private var secureFlagHolders = 0

@Composable
internal fun NvSettingsFlagSecureEffect() {
    val activity = LocalContext.current.findFragmentActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        secureFlagHolders++
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secureFlagHolders--
            if (secureFlagHolders <= 0) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** 存量冷却探测 + 每秒倒计时（打开时先探存量，Wrong 触发档由面板写入同一状态） */
@Composable
private fun rememberCooldownState(store: NvPrivacyPrefs, clock: NvMonotonicClock): MutableState<Int> {
    val cooldown = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val (until, bootAtLock) = runCatching { store.lockUntil() }.getOrDefault(0L to 0)
        cooldown.value = CooldownMath.remainingSec(until, bootAtLock, clock.elapsedRealtime(), clock.bootCount())
    }
    LaunchedEffect(cooldown.value) {
        if (cooldown.value > 0) {
            delay(1000)
            cooldown.value -= 1
        }
    }
    return cooldown
}

/**
 * 纯验证面板：验过回调 [onVerified]，不碰会话。冷却禁盘 / 密文损坏引导忘码流与 T12 同口径。
 * 用于：进入隐私功能区（pendingOp=null）与每次变更操作的逐次验 PIN。
 */
@Composable
fun NvPinVerifySheet(
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { NvPrivacyPrefs(context, NvPrivacyCrypto.keystoreCipher()) }
    val manager = remember(context) { NvPrivacyAccess.newPinManager(context, store) }
    val clock = remember(context) { NvMonotonicClock.androidSystem(context) }
    val scope = rememberCoroutineScope()
    val cooldown = rememberCooldownState(store, clock)
    val startForgot = rememberNvForgotResetFlow(onResetDone = onVerified)

    var entry by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pinBroken by remember { mutableStateOf(false) }

    NvSettingsFlagSecureEffect()
    // 返回键 = 关面板（不落回设置组级返回——后注册者优先）
    BackHandler(enabled = !busy) { onDismiss() }

    // 打开时探测：PIN 密文损坏 → 直接引导忘码流（fail-closed，不进错误计数循环）
    LaunchedEffect(Unit) {
        pinBroken = runCatching { manager.pinCipherBroken() }.getOrDefault(true)
    }

    fun submitPin(typed: String) {
        if (busy || cooldown.value > 0) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { manager.verify(typed) }.getOrDefault(PinResult.Broken)
            withContext(Dispatchers.Main) {
                busy = false
                when (val effect = NvGateWiring.unlockEffect(result)) {
                    is NvGateWiring.UnlockEffect.Passed -> onVerified()
                    is NvGateWiring.UnlockEffect.Stay -> {
                        wrongPin = true
                        entry = ""
                    }
                    is NvGateWiring.UnlockEffect.Cooldown -> {
                        wrongPin = true
                        entry = ""
                        cooldown.value = effect.remainingSec
                    }
                    is NvGateWiring.UnlockEffect.BrokenGuide -> {
                        pinBroken = true
                        entry = ""
                    }
                }
            }
        }
    }

    NvPinSheetPanel(
        title = stringResource(R.string.privacy_verify_title),
        onClose = onDismiss,
        closeEnabled = !busy,
        status = {
            NvPinHintText(
                text = when {
                    cooldown.value > 0 -> stringResource(R.string.privacy_unlock_cooldown, cooldown.value)
                    wrongPin -> stringResource(R.string.privacy_unlock_wrong_pin)
                    pinBroken -> stringResource(R.string.privacy_unlock_broken_guide)
                    else -> stringResource(R.string.privacy_verify_enter)
                },
                isError = cooldown.value > 0 || wrongPin || pinBroken,
                textAlign = TextAlign.Center,
            )
            if (!pinBroken) {
                Spacer(Modifier.height(16.dp))
                NvPinDots(filled = entry.length)
            }
        },
        keypad = if (pinBroken) {
            null
        } else {
            {
                NvPinKeypad(
                    enabled = !busy && cooldown.value <= 0,
                    onDigit = { d ->
                        if (!busy && cooldown.value <= 0 && entry.length < 4) {
                            wrongPin = false
                            entry += d.toString()
                            if (entry.length == 4) submitPin(entry)
                        }
                    },
                    onDelete = { if (!busy && entry.isNotEmpty()) entry = entry.dropLast(1) },
                )
            }
        },
        footer = if (pinBroken) {
            {
                Button(onClick = { startForgot() }, enabled = !busy) {
                    Text(stringResource(R.string.privacy_unlock_forgot))
                }
            }
        } else {
            {
                TextButton(onClick = { startForgot() }, enabled = !busy) {
                    Text(
                        stringResource(R.string.privacy_unlock_forgot),
                        color = NvColors.TextSecondary,
                        style = NvTypography.BodyMedium,
                    )
                }
            }
        },
    )
}

/**
 * 修改 PIN 面板（验旧 → 新两遍）。旧 PIN 验证失败同样进错误计数与冷却（与解锁面板同口径，
 * 不能因为换了入口就放宽穷举成本）。
 */
@Composable
fun NvPinChangeSheet(
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { NvPrivacyPrefs(context, NvPrivacyCrypto.keystoreCipher()) }
    val manager = remember(context) { NvPrivacyAccess.newPinManager(context, store) }
    val clock = remember(context) { NvMonotonicClock.androidSystem(context) }
    val scope = rememberCoroutineScope()
    val cooldown = rememberCooldownState(store, clock)
    val startForgot = rememberNvForgotResetFlow(onResetDone = onDismiss)

    var phase by remember { mutableStateOf(ChangePhase.OLD) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<ChangeError?>(null) }
    var pinBroken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    NvSettingsFlagSecureEffect()
    BackHandler(enabled = !busy) { onDismiss() }

    LaunchedEffect(Unit) {
        pinBroken = runCatching { manager.pinCipherBroken() }.getOrDefault(true)
    }

    fun resetEntry(p: ChangePhase, err: ChangeError?) {
        entry = ""
        newPin = ""
        phase = p
        error = err
    }

    fun submit(typed: String) {
        if (busy || cooldown.value > 0) return
        when (phase) {
            ChangePhase.OLD -> {
                busy = true
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { manager.verify(typed) }.getOrDefault(PinResult.Broken)
                    withContext(Dispatchers.Main) {
                        busy = false
                        when (result) {
                            is PinResult.Ok -> {
                                oldPin = typed
                                entry = ""
                                error = null
                                phase = ChangePhase.NEW_FIRST
                            }
                            is PinResult.Wrong -> {
                                error = ChangeError.WRONG_OLD
                                entry = ""
                                if (result.cooldownSec > 0) cooldown.value = result.cooldownSec
                            }
                            is PinResult.Cooldown -> {
                                entry = ""
                                cooldown.value = result.remainingSec
                            }
                            is PinResult.Broken -> {
                                pinBroken = true
                                entry = ""
                            }
                        }
                    }
                }
            }
            ChangePhase.NEW_FIRST -> {
                newPin = typed
                entry = ""
                error = null
                phase = ChangePhase.NEW_SECOND
            }
            ChangePhase.NEW_SECOND -> {
                if (NvGateWiring.setupPass(newPin, typed) == NvGateWiring.SetupPass.MISMATCH) {
                    // 两遍不一致：新 PIN 从头再来（旧 PIN 已验过不必重验）
                    resetEntry(ChangePhase.NEW_FIRST, ChangeError.MISMATCH)
                    return
                }
                busy = true
                scope.launch(Dispatchers.IO) {
                    val ok = runCatching { manager.change(oldPin, typed) }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        busy = false
                        if (ok) {
                            Toast.makeText(context, R.string.privacy_change_done_toast, Toast.LENGTH_SHORT).show()
                            onChanged()
                        } else {
                            resetEntry(ChangePhase.NEW_FIRST, ChangeError.SAVE_FAILED)
                        }
                    }
                }
            }
        }
    }

    NvPinSheetPanel(
        title = stringResource(R.string.privacy_change_title),
        onClose = onDismiss,
        closeEnabled = !busy,
        status = {
            if (pinBroken) {
                NvPinHintText(
                    text = stringResource(R.string.privacy_unlock_broken_guide),
                    isError = true,
                    textAlign = TextAlign.Center,
                )
            } else {
                NvPinHintText(
                    text = when {
                        cooldown.value > 0 -> stringResource(R.string.privacy_unlock_cooldown, cooldown.value)
                        error == ChangeError.WRONG_OLD -> stringResource(R.string.privacy_unlock_wrong_pin)
                        error == ChangeError.MISMATCH -> stringResource(R.string.privacy_setup_mismatch)
                        error == ChangeError.SAVE_FAILED -> stringResource(R.string.privacy_setup_failed)
                        phase == ChangePhase.NEW_FIRST -> stringResource(R.string.privacy_setup_enter)
                        phase == ChangePhase.NEW_SECOND -> stringResource(R.string.privacy_setup_reenter)
                        else -> stringResource(R.string.privacy_change_enter_old)
                    },
                    isError = cooldown.value > 0 || error != null,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                NvPinDots(filled = entry.length)
            }
        },
        keypad = if (pinBroken) {
            null
        } else {
            {
                NvPinKeypad(
                    enabled = !busy && cooldown.value <= 0,
                    onDigit = { d ->
                        if (!busy && cooldown.value <= 0 && entry.length < 4) {
                            error = null
                            entry += d.toString()
                            if (entry.length == 4) submit(entry)
                        }
                    },
                    onDelete = { if (!busy && entry.isNotEmpty()) entry = entry.dropLast(1) },
                )
            }
        },
        footer = if (pinBroken) {
            {
                Button(onClick = { startForgot() }, enabled = !busy) {
                    Text(stringResource(R.string.privacy_unlock_forgot))
                }
            }
        } else {
            null
        },
    )
}

private enum class ChangePhase { OLD, NEW_FIRST, NEW_SECOND }
private enum class ChangeError { WRONG_OLD, MISMATCH, SAVE_FAILED }

/**
 * 忘码重置共享流（验证/改 PIN/管理页三处复用）：NvGateWiring.credentialPath 分流——
 * 未设锁屏先警告；SDK≥30 BiometricPrompt 凭据组合；26~29 KeyguardManager Intent。
 * 通过后 [NvPinManager.resetAll]（工厂构造，真抹除管道）→ toast → [onResetDone]。
 *
 * @return 启动忘码流的回调（在按钮 onClick 里调用）
 */
@Composable
internal fun rememberNvForgotResetFlow(onResetDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val manager = remember(context) { NvPrivacyAccess.newPinManager(context, NvPrivacyPrefs(context, NvPrivacyCrypto.keystoreCipher())) }
    val scope = rememberCoroutineScope()
    var showNoKeyguardWarn by remember { mutableStateOf(false) }

    fun performReset() {
        scope.launch(Dispatchers.IO) {
            runCatching { manager.resetAll(MediaDb.get(context)) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.privacy_forgot_done_toast, Toast.LENGTH_SHORT).show()
                onResetDone()
            }
        }
    }

    // SDK 26~29 设备凭据：系统锁屏验证页经 startActivityForResult（结果 OK = 验证通过）
    val credentialIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) performReset()
    }

    /** createConfirmDeviceCredentialIntent 的弃用警告在此压制——它正是 SDK 26~29 分支的指定 API */
    @Suppress("DEPRECATION")
    fun startForgotFlow() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        when (NvGateWiring.credentialPath(Build.VERSION.SDK_INT, km?.isDeviceSecure == true)) {
            NvGateWiring.CredentialPath.WARN_NO_KEYGUARD -> showNoKeyguardWarn = true
            NvGateWiring.CredentialPath.BIOMETRIC_PROMPT_SDK30 -> promptDeviceCredentialSdk30(
                activity,
                context.getString(R.string.privacy_forgot_credential_prompt),
                onSucceeded = { performReset() },
                onError = { /* 用户取消：留在原面板，可再试 */ },
            )
            NvGateWiring.CredentialPath.KEYGUARD_INTENT_SDK26_29 -> {
                val intent = km?.createConfirmDeviceCredentialIntent(
                    context.getString(R.string.privacy_forgot_credential_prompt),
                    context.getString(R.string.privacy_forgot_credential_prompt),
                )
                if (intent != null) credentialIntentLauncher.launch(intent) else showNoKeyguardWarn = true
            }
        }
    }

    // 未设锁屏的忘码警告（与 T12 同文案：无法防止他人清除应用数据，用户知情后仍可继续）
    if (showNoKeyguardWarn) {
        AlertDialog(
            onDismissRequest = { showNoKeyguardWarn = false },
            containerColor = NvColors.GlassFillStrong,
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.privacy_forgot_warn_title), style = NvTypography.TitleMedium) },
            text = { Text(stringResource(R.string.privacy_forgot_warn_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showNoKeyguardWarn = false
                    performReset()
                }) { Text(stringResource(R.string.privacy_common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showNoKeyguardWarn = false }) {
                    Text(stringResource(android.R.string.cancel), color = NvColors.TextSecondary)
                }
            },
        )
    }

    return { startForgotFlow() }
}
