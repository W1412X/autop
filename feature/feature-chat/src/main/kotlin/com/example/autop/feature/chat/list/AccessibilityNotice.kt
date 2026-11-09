/**
 * 首页无障碍提示（docs/tech/08 §4.1 AccessibilityNotice）：已开启时一行状态文字，
 * 未开启 / 被系统关闭时 blocked 色系 [StatusBanner]；SDK ≥ 33（侧载应用需
 * "允许受限设置"）时"去开启"前先弹 [ConfirmDialog] 说明步骤。
 * shell 注入提示只留一句可点问题，详细说明走弹窗。
 */
package com.example.autop.feature.chat.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.InfoDialog
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.chat.R

/**
 * 无障碍提示块。
 *
 * @param connected 无障碍服务是否已连接。
 * @param restrictedSettings 该设备上侧载应用开无障碍需先"允许受限设置"（Capability.RESTRICTED_SETTINGS）。
 * @param shellHint 是否提示可选的 shell 注入通道（未配对时显示，不阻塞任何功能）。
 * @param onEnable "去开启"确认后的回调（跳系统无障碍设置）。
 * @param modifier 组合修饰。
 */
@Composable
public fun AccessibilityNotice(
    connected: Boolean,
    restrictedSettings: Boolean,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
    shellHint: Boolean = false,
) {
    if (connected) {
        ConnectedRow(shellHint = shellHint, modifier = modifier)
    } else {
        DisabledBanner(
            restrictedSettings = restrictedSettings,
            onEnable = onEnable,
            modifier = modifier,
        )
    }
}

@Composable
private fun ConnectedRow(
    shellHint: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(horizontal = LocalSpacing.current.screenEdge)
                .padding(vertical = LocalSpacing.current.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AutopIcons.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(IconSize.Xs.scaled()),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(LocalSpacing.current.xs))
            Text(
                text = stringResource(R.string.chat_a11y_running),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (shellHint) {
            ShellHint(modifier = Modifier.padding(top = LocalSpacing.current.xs))
        }
    }
}

/** shell 注入提示：一句话问题（点击弹出详细说明）。 */
@Composable
private fun ShellHint(modifier: Modifier = Modifier) {
    var detail by rememberSaveable { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.chat_a11y_shell_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable { detail = true },
    )
    if (detail) {
        InfoDialog(
            title = stringResource(R.string.chat_a11y_shell_hint),
            text = stringResource(R.string.chat_a11y_shell_hint_detail),
            onDismiss = { detail = false },
        )
    }
}

@Composable
private fun DisabledBanner(
    restrictedSettings: Boolean,
    onEnable: () -> Unit,
    modifier: Modifier,
) {
    var confirmRestricted by rememberSaveable { mutableStateOf(false) }
    StatusBanner(
        tone = StatusTone.BLOCKED,
        title = stringResource(R.string.chat_a11y_title),
        message = stringResource(R.string.chat_a11y_body),
        modifier = modifier.padding(horizontal = LocalSpacing.current.screenEdge),
        actions = {
            AutopTonalButton(
                text = stringResource(R.string.chat_a11y_enable),
                onClick = {
                    if (restrictedSettings) {
                        confirmRestricted = true
                    } else {
                        onEnable()
                    }
                },
            )
        },
    )
    if (confirmRestricted) {
        ConfirmDialog(
            title = stringResource(R.string.chat_a11y_restricted_title),
            text = stringResource(R.string.chat_a11y_restricted_body),
            onConfirm = {
                confirmRestricted = false
                onEnable()
            },
            onDismiss = { confirmRestricted = false },
        )
    }
}
