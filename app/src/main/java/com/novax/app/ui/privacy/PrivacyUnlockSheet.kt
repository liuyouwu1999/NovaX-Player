package com.novax.app.ui.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed
import com.novax.app.R
import com.novax.app.data.db.MediaDb
import com.novax.app.data.privacy.CooldownMath
import com.novax.app.data.privacy.NvGateWiring
import com.novax.app.data.privacy.NvMonotonicClock
import com.novax.app.data.privacy.NvPrivacyAccess
import com.novax.app.data.privacy.NvPrivacyCrypto
import com.novax.app.data.privacy.NvPrivacyPrefs
import com.novax.app.data.privacy.PinResult
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解锁面板（隐私计划 Task 12）：已锁内容的唯一放行入口。
 * - 打开即自动弹 BiometricPrompt（用户已启用指纹 且 设备当前可用，NvGateWiring 判定）；
 *   失败/取消落回自绘 PIN 键盘（4×3+删除，不经 TextField/输入法）。
 * - 冷却倒计时禁盘（PinResult.Cooldown / Wrong 的 cooldownSec 驱动；打开时先探存量冷却）。
 * - pinCipherBroken（Keystore 密钥丢失，T6）→ 直接引导忘码流，不进错误计数循环。
 * - 「忘记密码？」：未设锁屏先警告「无法防止他人清除」→ 设备凭据（SDK≥30 BiometricPrompt
 *   DEVICE_CREDENTIAL 组合；26~29 KeyguardManager.createConfirmDeviceCredentialIntent）→
 *   NvPinManager.resetAll（工厂构造，真抹除管道）。
 * - 本面板不直接改 NvPrivacySession：解锁/重置结果一律回调调用方（PlayerActivity 登记
 *   session.unlock + 世代守卫补扫），单一接线点。
 *
 * FLAG_SECURE：挂载即对宿主窗口加防截屏标志；默认**不清除**（播放器窗口常驻防截屏，
 * 解锁后也不能允许截屏）——仅 [clearFlagSecureOnDispose]=true 的宿主（如 Task 13 浏览态）
 * 在面板离组时还原。
 */
@Composable
fun NvPrivacyUnlockSheet(
    ruleIds: List<Long>,
    onUnlocked: (List<Long>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    clearFlagSecureOnDispose: Boolean = false,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val store = remember(context) { NvPrivacyPrefs(context, NvPrivacyCrypto.keystoreCipher()) }
    val manager = remember(context) { NvPrivacyAccess.newPinManager(context, store) }
    val clock = remember(context) { NvMonotonicClock.androidSystem(context) }
    val scope = rememberCoroutineScope()

    var entry by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }
    var biometricFailed by remember { mutableStateOf(false) }
    var cooldownSec by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pinBroken by remember { mutableStateOf(false) }
    var showNoKeyguardWarn by remember { mutableStateOf(false) }

    /** 解锁全部拦截规则（PIN/指纹共用出口）：会话登记与补扫归调用方 */
    fun unlockAll() {
        onUnlocked(ruleIds)
    }

    /** 忘码重置统一入口：resetAll（NvPinStore 逐键清 + 真抹除 privacy_rules）→ 回调 */
    fun performReset() {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            runCatching { manager.resetAll(MediaDb.get(context)) }
            withContext(Dispatchers.Main) {
                busy = false
                Toast.makeText(context, R.string.privacy_forgot_done_toast, Toast.LENGTH_SHORT).show()
                onReset()
            }
        }
    }

    // SDK 26~29 设备凭据：系统锁屏验证页经 startActivityForResult（结果 OK = 验证通过）
    val credentialIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) performReset()
    }

    /** 「忘记密码？」分流（NvGateWiring.credentialPath 纯逻辑）：未设锁屏先警告。
     * createConfirmDeviceCredentialIntent 的弃用警告在此压制——它正是 SDK 26~29 分支的指定 API */
    @Suppress("DEPRECATION")
    fun startForgotFlow() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        when (NvGateWiring.credentialPath(Build.VERSION.SDK_INT, km?.isDeviceSecure == true)) {
            NvGateWiring.CredentialPath.WARN_NO_KEYGUARD -> showNoKeyguardWarn = true
            NvGateWiring.CredentialPath.BIOMETRIC_PROMPT_SDK30 -> promptDeviceCredentialSdk30(
                activity,
                context.getString(R.string.privacy_forgot_credential_prompt),
                onSucceeded = { performReset() },
                onError = { /* 用户取消：留在面板，可再试 */ },
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

    fun submitPin(typed: String) {
        if (busy || cooldownSec > 0) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { manager.verify(typed) }.getOrDefault(PinResult.Broken)
            withContext(Dispatchers.Main) {
                busy = false
                when (val effect = NvGateWiring.unlockEffect(result)) {
                    is NvGateWiring.UnlockEffect.Passed -> unlockAll()
                    is NvGateWiring.UnlockEffect.Stay -> {
                        wrongPin = true
                        biometricFailed = false
                        entry = ""
                    }
                    is NvGateWiring.UnlockEffect.Cooldown -> {
                        wrongPin = true
                        entry = ""
                        cooldownSec = effect.remainingSec
                    }
                    is NvGateWiring.UnlockEffect.BrokenGuide -> {
                        pinBroken = true
                        entry = ""
                    }
                }
            }
        }
    }

    // FLAG_SECURE：挂载即防截屏（已锁内容 + PIN 输入都不得进截图/最近任务缩略图）
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (clearFlagSecureOnDispose) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // 打开时探测：PIN 密文损坏 → 直接引导忘码流；存量冷却 → 立即倒计时禁盘
    LaunchedEffect(Unit) {
        pinBroken = runCatching { manager.pinCipherBroken() }.getOrDefault(true)
        val (until, bootAtLock) = runCatching { store.lockUntil() }.getOrDefault(0L to 0)
        cooldownSec = CooldownMath.remainingSec(until, bootAtLock, clock.elapsedRealtime(), clock.bootCount())
    }

    // 冷却倒计时：每秒 -1，归零自动解禁（重组由 cooldownSec 状态驱动）
    LaunchedEffect(cooldownSec) {
        if (cooldownSec > 0) {
            delay(1000)
            cooldownSec -= 1
        }
    }

    // 已启用指纹且设备可用 → 打开即自动弹（withResumed 等 Resumed 再 authenticate，
    // 防冷启动组合期早于 onResume 的窗口竞态）
    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        val enabled = runCatching { store.biometricEnabled.first() }.getOrDefault(false)
        val canAuth = runCatching {
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
        if (NvGateWiring.autoBiometricPrompt(enabled, canAuth)) {
            val lc = activity.lifecycle
            lc.withResumed {
                promptFingerprint(
                    activity,
                    negativeText = context.getString(R.string.privacy_unlock_biometric_negative),
                    onSucceeded = { unlockAll() },
                    onError = { biometricFailed = true },
                )
            }
        }
    }

    NvPinSheetPanel(
        title = stringResource(R.string.privacy_locked_title),
        onClose = onDismiss,
        closeEnabled = !busy,
        status = {
            NvPinHintText(
                text = when {
                    cooldownSec > 0 -> stringResource(R.string.privacy_unlock_cooldown, cooldownSec)
                    wrongPin -> stringResource(R.string.privacy_unlock_wrong_pin)
                    biometricFailed -> stringResource(R.string.privacy_unlock_biometric_failed)
                    pinBroken -> stringResource(R.string.privacy_unlock_broken_guide)
                    else -> stringResource(R.string.privacy_unlock_enter_pin)
                },
                isError = cooldownSec > 0 || wrongPin || biometricFailed || pinBroken,
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
                    enabled = !busy && cooldownSec <= 0,
                    onDigit = { d ->
                        if (!busy && cooldownSec <= 0 && entry.length < 4) {
                            wrongPin = false
                            biometricFailed = false
                            entry += d.toString()
                            if (entry.length == 4) submitPin(entry)
                        }
                    },
                    onDelete = { if (!busy && entry.isNotEmpty()) entry = entry.dropLast(1) },
                )
            }
        },
        footer = {
            if (pinBroken) {
                // 密文损坏：直接引导忘码流（不进错误计数循环）
                Button(onClick = { startForgotFlow() }, enabled = !busy) {
                    Text(stringResource(R.string.privacy_unlock_forgot))
                }
            } else {
                TextButton(onClick = { startForgotFlow() }, enabled = !busy) {
                    Text(
                        stringResource(R.string.privacy_unlock_forgot),
                        color = NvColors.TextSecondary,
                        style = NvTypography.BodyMedium,
                    )
                }
            }
        },
    )

    // 未设锁屏的忘码警告（spec：无法防止他人清除应用数据，用户知情后仍可继续）
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
}

/** 指纹自动弹（BIOMETRIC_WEAK）：成功回调放行；error（取消/锁定）落键盘，单次识别失败系统继续 */
private fun promptFingerprint(
    activity: FragmentActivity,
    negativeText: String,
    onSucceeded: () -> Unit,
    onError: () -> Unit,
) {
    val prompt = buildPrompt(activity, onSucceeded, onError)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.privacy_unlock_biometric_prompt_title))
        .setSubtitle(activity.getString(R.string.privacy_unlock_biometric_prompt_subtitle))
        .setNegativeButtonText(negativeText)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    runCatching { prompt.authenticate(info) }.onFailure { onError() }
}

/** SDK≥30 忘码凭据：BIOMETRIC_WEAK or DEVICE_CREDENTIAL 组合（含凭据时不得设 negativeButtonText）。
 *  internal：Task 16 设置页验证/管理面板的忘码流复用同一 SDK 分支（NvGateWiring.credentialPath） */
internal fun promptDeviceCredentialSdk30(
    activity: FragmentActivity?,
    subtitle: String,
    onSucceeded: () -> Unit,
    onError: () -> Unit,
) {
    if (activity == null) {
        onError()
        return
    }
    val prompt = buildPrompt(activity, onSucceeded, onError)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.privacy_unlock_biometric_prompt_title))
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    runCatching { prompt.authenticate(info) }.onFailure { onError() }
}

private fun buildPrompt(
    activity: FragmentActivity,
    onSucceeded: () -> Unit,
    onError: () -> Unit,
): BiometricPrompt = BiometricPrompt(
    activity,
    ContextCompat.getMainExecutor(activity),
    object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSucceeded()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError()
        }
        // onAuthenticationFailed（单次不匹配）不处理：系统对话框自行提示并继续识别
    },
)

/** LocalContext → 宿主 FragmentActivity（compose LocalContext 恒为 Activity 本体，包装只为兜底） */
internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
