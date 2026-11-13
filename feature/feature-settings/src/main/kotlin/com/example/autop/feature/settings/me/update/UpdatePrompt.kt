/**
 * 新版本提示弹窗（全局）。由 app 根组合挂载 [UpdatePromptHost]，任何页面都可能弹出；
 * 显示条件与行为见 [UpdatePromptViewModel]。
 */
package com.example.autop.feature.settings.me.update

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.device.UpdateInfo
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.me.rememberAppVersion

/** 更新说明最多显示的行数（超出部分省略，弹窗高度有界）。 */
private const val NOTES_MAX_LINES: Int = 8

/** 全局新版本提示宿主：有待提示的新版本时显示弹窗，否则不占位。 */
@Composable
public fun UpdatePromptHost(viewModel: UpdatePromptViewModel = hiltViewModel()) {
    val info by viewModel.prompt.collectAsStateWithLifecycle()
    info?.let { pending ->
        UpdatePromptDialog(
            info = pending,
            currentVersion = rememberAppVersion(),
            onDownload = { viewModel.onDownloadNow(pending) },
            onLater = { viewModel.onLater(pending) },
        )
    }
}

@Composable
internal fun UpdatePromptDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(
                text = stringResource(R.string.me_update_prompt_title, info.versionName),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.me_update_prompt_body, currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val notes = info.notes.trim()
                if (notes.isNotEmpty()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = NOTES_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            AutopButton(text = stringResource(R.string.me_update_prompt_download), onClick = onDownload)
        },
        dismissButton = {
            AutopTextButton(text = stringResource(R.string.me_update_prompt_later), onClick = onLater)
        },
    )
}
