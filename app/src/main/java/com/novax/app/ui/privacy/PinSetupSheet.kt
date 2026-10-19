package com.novax.app.ui.privacy

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.novax.app.data.privacy.NvGateWiring
import com.novax.app.data.privacy.NvPrivacyAccess
import com.novax.app.data.privacy.NvPrivacyCrypto
import com.novax.app.data.privacy.NvPrivacyPrefs
import com.novax.app.ui.components.NvTypography
import com.novax.app.ui.theme.NvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PIN 设置面板（隐私计划 Task 12）：两遍输入 → NvPinManager.setup 落库（密文）→
 * 设备支持生物识别则追问「启用指纹解锁？」（写 nvp_biometric 偏好，不现场弹指纹验证）。
 * 键盘为自绘四格（4×3+删除，纯 Compose 按钮）——PIN 永不经过 TextField/系统输入法。
 * 骨架用 NvPinSheetPanel：竖屏单列 / 矮宽横屏（折叠展开）信息列与键盘并排自适应。
 *
 * 消费方（Task 13 长按菜单 / Task 16 设置页）：无 PIN 时先挂本面板，完成回调后续动作。
 * 构造纪律：一律经 [NvPrivacyAccess.newPinManager] 工厂（T8 裁定——绕过工厂则忘码重置
 * 退化为普通删行，明文留 WAL）。
 *
 * @param onDismiss 用户中途放弃（未设成 PIN）
 * @param onCompleted PIN 已设置（可能已同时启用指纹）
 */
@Composable
fun NvPinSetupSheet(
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { NvPrivacyPrefs(context, NvPrivacyCrypto.keystoreCipher()) }
    val manager = remember(context) { NvPrivacyAccess.newPinManager(context, store) }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(SetupPhase.ENTER_FIRST) }
    var firstPin by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<SetupError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var biometricAvailable by remember { mutableStateOf(false) }

    // 设备生物识别可用性（BIOMETRIC_WEAK 即可解锁；canAuthenticate 经 androidx 兼容层，
    // 轻量 binder 调用，组装期查一次；28 以下框架指纹对话框形态不齐，只收 28+）
    LaunchedEffect(Unit) {
        biometricAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    fun resetEntry(p: SetupPhase, err: SetupError?) {
        firstPin = ""
        entry = ""
        phase = p
        error = err
    }

    fun submit(typed: String) {
        when (phase) {
            SetupPhase.ENTER_FIRST -> {
                firstPin = typed
                entry = ""
                error = null
                phase = SetupPhase.ENTER_SECOND
            }
            SetupPhase.ENTER_SECOND -> {
                if (NvGateWiring.setupPass(firstPin, typed) == NvGateWiring.SetupPass.MISMATCH) {
                    // 两遍不一致：第一遍一并作废从头再来（防「第二遍顺着错」）
                    resetEntry(SetupPhase.ENTER_FIRST, SetupError.MISMATCH)
                    return
                }
                busy = true
                scope.launch(Dispatchers.IO) {
                    // setup 内部先验 ^\d{4}$ 再加密落库；fail-closed（加密不可用）返回 false 不写半态
                    val ok = runCatching { manager.setup(typed) }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        busy = false
                        if (ok) {
                            entry = ""
                            error = null
                            if (biometricAvailable) {
                                phase = SetupPhase.ASK_BIOMETRIC
                            } else {
                                onCompleted()
                            }
                        } else {
                            resetEntry(SetupPhase.ENTER_FIRST, SetupError.SAVE_FAILED)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    NvPinSheetPanel(
        title = stringResource(R.string.privacy_setup_title),
        onClose = onDismiss,
        closeEnabled = !busy,
        status = {
            if (phase == SetupPhase.ASK_BIOMETRIC) {
                Text(
                    stringResource(R.string.privacy_setup_biometric_ask),
                    style = NvTypography.TitleMedium,
                    color = NvColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
            } else {
                NvPinHintText(
                    text = when {
                        error == SetupError.MISMATCH -> stringResource(R.string.privacy_setup_mismatch)
                        error == SetupError.SAVE_FAILED -> stringResource(R.string.privacy_setup_failed)
                        phase == SetupPhase.ENTER_SECOND -> stringResource(R.string.privacy_setup_reenter)
                        else -> stringResource(R.string.privacy_setup_enter)
                    },
                    isError = error != null,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                NvPinDots(filled = entry.length)
            }
        },
        keypad = when (phase) {
            SetupPhase.ASK_BIOMETRIC -> null
            else -> {
                {
                    NvPinKeypad(
                        enabled = !busy,
                        onDigit = { d ->
                            if (!busy && entry.length < 4) {
                                entry += d.toString()
                                if (entry.length == 4) submit(entry)
                            }
                        },
                        onDelete = { if (!busy && entry.isNotEmpty()) entry = entry.dropLast(1) },
                    )
                }
            }
        },
        footer = if (phase == SetupPhase.ASK_BIOMETRIC) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onCompleted) {
                        Text(stringResource(R.string.privacy_setup_skip), color = NvColors.TextSecondary)
                    }
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            store.setBiometricEnabled(true)
                            withContext(Dispatchers.Main) { onCompleted() }
                        }
                    }) { Text(stringResource(R.string.privacy_setup_enable)) }
                }
            }
        } else {
            null
        },
    )
}

private enum class SetupPhase { ENTER_FIRST, ENTER_SECOND, ASK_BIOMETRIC }

private enum class SetupError { MISMATCH, SAVE_FAILED }
