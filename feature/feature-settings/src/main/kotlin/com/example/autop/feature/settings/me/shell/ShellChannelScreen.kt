/**
 * 注入通道子页（N200，docs/tech/08 §4.5 `me/shell`）：无线调试配对与 shell 注入通道状态。
 * 无状态（docs/standards/coding-standards §8），状态与副作用在 [ShellChannelViewModel]；
 * 配对入口放设置而非工具测试面板（N200：配对是一次性配置，测试面板只做诊断）。
 * 视觉与首次启动的权限检查页一致：状态卡片 + 图标 + 动作按钮。
 */
package com.example.autop.feature.settings.me.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusCard
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.device.ShellState
import com.example.autop.feature.settings.R

/**
 * 注入通道子页。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onAction 用户操作回调。
 */
@Composable
public fun ShellChannelScreen(
    state: ShellChannelUiState,
    onBack: () -> Unit,
    onAction: (ShellChannelAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_shell_title), onBack = onBack)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(text = stringResource(R.string.me_shell_section_status))
            ShellStatusCard(state = state, onAction = onAction)
            PairingGuideSection()
            SectionTitle(text = stringResource(R.string.me_shell_section_pair))
            PairingCard(state = state, onAction = onAction)
        }
    }
}

/** 通道状态卡：状态图标 + 状态文案 + 手动连接。 */
@Composable
private fun ShellStatusCard(
    state: ShellChannelUiState,
    onAction: (ShellChannelAction) -> Unit,
) {
    val connected = state.shellState == ShellState.Connected
    StatusCard(
        icon =
            when (state.shellState) {
                ShellState.Disconnected -> AutopIcons.Error
                ShellState.Connecting -> AutopIcons.Refresh
                ShellState.Connected -> AutopIcons.CheckCircle
            },
        tint =
            when (state.shellState) {
                ShellState.Disconnected -> MaterialTheme.colorScheme.error
                ShellState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
                ShellState.Connected -> MaterialTheme.colorScheme.primary
            },
        title = stringResource(R.string.me_shell_status_title),
        subtitle = stringResource(statusResOf(state.shellState)),
        trailing =
            if (connected) {
                null
            } else {
                {
                    AutopTextButton(
                        text = stringResource(R.string.me_shell_connect),
                        onClick = { onAction(ShellChannelAction.Connect) },
                    )
                }
            },
    )
}

/** 配对卡：三步引导 + 自动发现提示 + 端口/配对码表单。 */
@Composable
private fun PairingCard(
    state: ShellChannelUiState,
    onAction: (ShellChannelAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    StatusCard(
        icon = AutopIcons.PhoneAndroid,
        tint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.me_shell_pair_title),
        subtitle = null,
    ) {
        StepText(textRes = R.string.me_shell_step_1)
        StepText(textRes = R.string.me_shell_step_2)
        StepText(textRes = R.string.me_shell_step_3)
        Text(
            text =
                stringResource(
                    if (state.discovering) R.string.me_shell_discovering else R.string.me_shell_discover_hint,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        OutlinedTextField(
            value = state.portText,
            onValueChange = { onAction(ShellChannelAction.UpdatePort(it)) },
            label = { Text(stringResource(R.string.me_shell_port)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        OutlinedTextField(
            value = state.codeText,
            onValueChange = { onAction(ShellChannelAction.UpdateCode(it)) },
            label = { Text(stringResource(R.string.me_shell_code)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
        )
        AutopButton(
            text = stringResource(R.string.me_shell_pair),
            onClick = { onAction(ShellChannelAction.Pair) },
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        val notice = state.notice
        if (notice != null) {
            Text(
                text = noticeTextOf(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/** 配对步骤文字。 */
@Composable
private fun StepText(
    @StringRes textRes: Int,
) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 通道状态文案（N200）。 */
@Composable
private fun statusResOf(state: ShellState): Int =
    when (state) {
        ShellState.Disconnected -> R.string.me_shell_status_off
        ShellState.Connecting -> R.string.me_shell_status_connecting
        ShellState.Connected -> R.string.me_shell_status_on
    }

/** 配对提示文案（N200）。 */
@Composable
private fun noticeTextOf(notice: ShellNotice): String =
    when (notice) {
        ShellNotice.InputMissing -> stringResource(R.string.me_shell_input_missing)
        is ShellNotice.PairFailed -> stringResource(R.string.me_shell_pair_failed, notice.message)
    }
