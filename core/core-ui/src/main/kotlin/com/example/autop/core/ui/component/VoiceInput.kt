/**
 * 语音输入的共用辅助（系统语音识别）：权限申请、文本拼接，以及语音错误的提示。
 * 错误码与 SpeechRecognizer 的错误表一一对应（speech.err.N），界面按原因给出具体文案，并显示错误码。
 */
package com.example.autop.core.ui.component

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.StatusTone

/**
 * 返回「开始语音输入」的函数：已授权直接调用 [onGranted]；未授权先申请录音权限，
 * 授权后调用 [onGranted]，拒绝时调用 [onDenied]。
 */
@Composable
public fun rememberVoiceStarter(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val grantedNow by rememberUpdatedState(onGranted)
    val deniedNow by rememberUpdatedState(onDenied)
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) grantedNow() else deniedNow()
        }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            grantedNow()
        } else {
            request.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** 语音结果接在开始时已有文字的后面（中间补一个空格）。 */
public fun joinVoiceText(
    base: String,
    spoken: String,
): String =
    when {
        base.isBlank() -> spoken
        base.endsWith(" ") || base.endsWith("\n") -> base + spoken
        else -> "$base $spoken"
    }

/** 语音输入出错的提示横幅。[code] 为 speech.* 错误码。 */
@Composable
public fun VoiceErrorBanner(code: String) {
    val (res, arg) = voiceErrorText(code)
    StatusBanner(
        tone = StatusTone.BLOCKED,
        message = stringResource(res, arg),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 错误码 → 文案资源与参数（错误码数字）。未列出的错误码给出通用文案并显示错误码。 */
internal fun voiceErrorText(code: String): Pair<Int, String> =
    (VOICE_ERROR_RES[code] ?: R.string.core_ui_voice_unknown) to code.removePrefix(ERR_PREFIX)

private const val ERR_PREFIX = "speech.err."

private val VOICE_ERROR_RES: Map<String, Int> =
    mapOf(
        "speech.no_service" to R.string.core_ui_voice_no_service,
        "speech.permission" to R.string.core_ui_voice_permission,
        "${ERR_PREFIX}1" to R.string.core_ui_voice_network,
        "${ERR_PREFIX}2" to R.string.core_ui_voice_network,
        "${ERR_PREFIX}3" to R.string.core_ui_voice_audio,
        "${ERR_PREFIX}4" to R.string.core_ui_voice_server,
        "${ERR_PREFIX}5" to R.string.core_ui_voice_client,
        "${ERR_PREFIX}6" to R.string.core_ui_voice_no_speech,
        "${ERR_PREFIX}7" to R.string.core_ui_voice_no_speech,
        "${ERR_PREFIX}8" to R.string.core_ui_voice_busy,
        "${ERR_PREFIX}10" to R.string.core_ui_voice_too_many,
        "${ERR_PREFIX}11" to R.string.core_ui_voice_disconnected,
        "${ERR_PREFIX}12" to R.string.core_ui_voice_language,
        "${ERR_PREFIX}13" to R.string.core_ui_voice_language,
        "${ERR_PREFIX}14" to R.string.core_ui_voice_support_check,
        "${ERR_PREFIX}15" to R.string.core_ui_voice_download,
    )
