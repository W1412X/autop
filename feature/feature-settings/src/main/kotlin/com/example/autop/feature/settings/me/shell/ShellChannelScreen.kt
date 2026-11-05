/**
 * 注入通道子页（N200，docs/tech/08 §4.5 `me/shell`）：无线调试配对与 shell 注入通道状态。
 * 无状态（docs/standards/coding-standards §8），状态与副作用在 [ShellChannelViewModel]；
 * 配对入口放设置而非工具测试面板（N200：配对是一次性配置，测试面板只做诊断）。
 */
package com.example.autop.feature.settings.me.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.SectionTitle
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
            StatusRow(state = state, onAction = onAction)
            SectionTitle(text = stringResource(R.string.me_shell_section_pair))
            PairForm(state = state, onAction = onAction)
        }
    }
}

/** 通道状态行：状态文案 + 手动连接。 */
@Composable
private fun StatusRow(
    state: ShellChannelUiState,
    onAction: (ShellChannelAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge),
    ) {
        Text(
            text = stringResource(statusResOf(state.shellState)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        AutopTextButton(
            text = stringResource(R.string.me_shell_connect),
            onClick = { onAction(ShellChannelAction.Connect) },
            modifier = Modifier.padding(start = LocalSpacing.current.xs),
        )
    }
}

/** 配对表单：配对端口 + 6 位配对码 + 配对按钮 + 引导/提示。 */
@Composable
private fun PairForm(
    state: ShellChannelUiState,
    onAction: (ShellChannelAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge),
    ) {
        OutlinedTextField(
            value = state.portText,
            onValueChange = { onAction(ShellChannelAction.UpdatePort(it)) },
            label = { Text(stringResource(R.string.me_shell_port)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.codeText,
            onValueChange = { onAction(ShellChannelAction.UpdateCode(it)) },
            label = { Text(stringResource(R.string.me_shell_code)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        AutopButton(
            text = stringResource(R.string.me_shell_pair),
            onClick = { onAction(ShellChannelAction.Pair) },
            modifier = Modifier.padding(top = LocalSpacing.current.xs),
        )
        val notice = state.notice
        if (notice != null) {
            Text(
                text = noticeTextOf(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.me_shell_guide),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
