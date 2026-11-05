/**
 * 调试页的工具试执行区（docs/tech/08 §4.6：OptionSheet 选工具 → 参数 JSON 输入 → 执行 → 显示 ToolOutput）。
 */
package com.example.autop.feature.settings.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopOutlinedButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/** 参数 JSON 输入的行数上限。 */
private const val ARGS_MAX_LINES: Int = 4

/** 工具试执行区（无状态）。 */
@Composable
internal fun DebugToolSection(
    state: DebugUiState,
    onAction: (DebugAction) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val spacing = LocalSpacing.current
    SectionTitle(text = stringResource(R.string.debug_tools_title))
    if (state.toolIds.isEmpty()) {
        EmptyHint(textRes = R.string.debug_no_tools)
        return
    }
    OptionRow(
        title = stringResource(R.string.debug_tool_picker),
        subtitle = null,
        value = state.selectedToolId ?: stringResource(R.string.debug_error_tool),
        onClick = { pickerOpen = true },
    )
    Column(modifier = Modifier.padding(horizontal = spacing.lg)) {
        Text(
            text = stringResource(R.string.debug_tool_args),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutopInputBar(
            value = state.argsText,
            onValueChange = { onAction(DebugAction.SetArgs(it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.debug_tool_args_hint), maxLines = ARGS_MAX_LINES),
            modifier = Modifier.fillMaxWidth(),
        )
        AutopOutlinedButton(
            text = stringResource(R.string.debug_tool_execute),
            onClick = { onAction(DebugAction.ExecuteTool) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ToolOutcomeView(outcome = state.toolOutcome)
    if (pickerOpen) {
        OptionSheet(
            title = stringResource(R.string.debug_tool_picker),
            options = state.toolIds.map { OptionItem(id = it, label = it) },
            selectedId = state.selectedToolId,
            onSelect = { id ->
                onAction(DebugAction.SelectTool(id))
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** ToolOutput 展示：状态、错误、控制指令与正文（mono）。 */
@Composable
private fun ToolOutcomeView(outcome: DebugToolOutcome?) {
    if (outcome == null) return
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(horizontal = spacing.lg)) {
        Text(
            text = stringResource(R.string.debug_tool_output),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "ok=${outcome.ok}  ${outcome.toolId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (outcome.errorCode != null) {
            Text(
                text =
                    stringResource(
                        R.string.debug_tool_error,
                        outcome.errorCode.orEmpty(),
                        outcome.errorMessage.orEmpty(),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val control = outcome.control
        if (control != null) {
            Text(
                text = control,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = outcome.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
