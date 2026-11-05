/**
 * 运行预算页（docs/tech/08 §4.5"运行预算"）：`agent.budget` 四项上限的编辑入口，
 * 每项可设数值或勾选"不限"（0）；恢复默认 = 清除用户覆盖（默认不限）。
 * 时长按分钟编辑（存储毫秒），其余按原值编辑。
 */
package com.example.autop.feature.settings.me.budget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.feature.settings.R

/**
 * 运行预算页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onAction 用户操作回调。
 */
@Composable
public fun BudgetScreen(
    state: BudgetUiState,
    onBack: () -> Unit,
    onAction: (BudgetAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_nav_budget), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SectionTitle(text = stringResource(R.string.budget_section_limits)) }
            items(state.items) { item ->
                OptionRow(
                    title = titleOf(item.field),
                    subtitle = null,
                    value = valueText(item),
                    onClick = { onAction(BudgetAction.OpenEditor(item.field)) },
                )
            }
        }
    }
    state.editing?.let { edit ->
        EditDialog(edit = edit, onAction = onAction)
    }
}

/** 编辑弹层：数值输入 + "不限预算"开关（保存为 0）；恢复默认 = 清除覆盖。 */
@Composable
private fun EditDialog(
    edit: BudgetEditState,
    onAction: (BudgetAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(BudgetAction.CloseEditor) },
        title = { Text(text = titleOf(edit.field)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.budget_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    title = stringResource(R.string.budget_unlimited_switch),
                    subtitle = null,
                    checked = edit.unlimited,
                    onCheckedChange = { onAction(BudgetAction.SetUnlimited(it)) },
                )
                OutlinedTextField(
                    value = edit.text,
                    onValueChange = { onAction(BudgetAction.SetEditText(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !edit.unlimited,
                    isError = edit.invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            text =
                                if (edit.invalid) {
                                    stringResource(R.string.budget_invalid_number)
                                } else {
                                    unitOf(edit.field)
                                },
                        )
                    },
                )
            }
        },
        confirmButton = {
            AutopButton(
                text = stringResource(R.string.budget_save),
                onClick = { onAction(BudgetAction.Save) },
            )
        },
        dismissButton = {
            AutopTextButton(
                text = stringResource(R.string.budget_restore_default),
                onClick = { onAction(BudgetAction.RestoreDefault) },
                destructive = true,
            )
        },
    )
}

/** 维度标题。 */
@Composable
private fun titleOf(field: BudgetField): String =
    when (field) {
        BudgetField.MAX_STEPS -> stringResource(R.string.budget_max_steps)
        BudgetField.MAX_TOTAL_TOKENS -> stringResource(R.string.budget_max_tokens)
        BudgetField.MAX_DURATION_MS -> stringResource(R.string.budget_max_duration)
        BudgetField.MAX_SCREENSHOTS -> stringResource(R.string.budget_max_screenshots)
    }

/** 行内当前值：0（或负）= 不限；时长按分钟展示（存储毫秒）。 */
@Composable
private fun valueText(item: BudgetItem): String {
    if (item.value <= 0) return stringResource(R.string.budget_unlimited)
    return when (item.field) {
        BudgetField.MAX_STEPS -> stringResource(R.string.budget_value_steps, item.value)
        BudgetField.MAX_TOTAL_TOKENS -> stringResource(R.string.budget_value_tokens, item.value)
        BudgetField.MAX_DURATION_MS -> stringResource(R.string.budget_value_minutes, item.value / MINUTES_MS)
        BudgetField.MAX_SCREENSHOTS -> stringResource(R.string.budget_value_screenshots, item.value)
    }
}

/** 编辑框单位提示。 */
@Composable
private fun unitOf(field: BudgetField): String =
    when (field) {
        BudgetField.MAX_STEPS -> stringResource(R.string.budget_unit_steps)
        BudgetField.MAX_TOTAL_TOKENS -> stringResource(R.string.budget_unit_tokens)
        BudgetField.MAX_DURATION_MS -> stringResource(R.string.budget_unit_minutes)
        BudgetField.MAX_SCREENSHOTS -> stringResource(R.string.budget_unit_screenshots)
    }

/** 每分钟毫秒数（时长编辑单位换算）。 */
private const val MINUTES_MS: Long = 60_000L
