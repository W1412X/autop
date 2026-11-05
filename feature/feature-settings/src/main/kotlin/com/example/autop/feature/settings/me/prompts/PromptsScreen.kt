/**
 * 提示词页（docs/tech/08 §4.5"提示词"）：模板列表（id/版本/来源），
 * 点击编辑用户覆盖正文；清除覆盖恢复内置正文。
 */
package com.example.autop.feature.settings.me.prompts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.feature.settings.R

/**
 * 提示词页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onAction 用户操作回调。
 */
@Composable
public fun PromptsScreen(
    state: PromptsUiState,
    onBack: () -> Unit,
    onAction: (PromptsAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_nav_prompts), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items) { item ->
                ListRow(
                    title = item.id,
                    subtitle =
                        subtitleParts(
                            stringResource(R.string.prompts_version, item.version),
                            stringResource(sourceLabelRes(item.source)),
                        ),
                    modifier = Modifier.clickable { onAction(PromptsAction.Open(item.id)) },
                )
            }
            if (state.items.isEmpty()) {
                item { EmptyState(text = stringResource(R.string.prompts_empty)) }
            }
        }
    }
    val editing = state.editing
    if (editing != null) {
        EditDialog(
            item = editing,
            text = state.editText,
            error = state.editError,
            saving = state.saving,
            onAction = onAction,
        )
    }
}

@androidx.annotation.StringRes
private fun sourceLabelRes(source: PromptSource): Int =
    when (source) {
        PromptSource.BUILTIN -> R.string.prompts_source_builtin
        PromptSource.USER -> R.string.prompts_source_user
    }

/** 正文编辑对话框（未声明变量显示错误且不生效）。 */
@Composable
private fun EditDialog(
    item: PromptItem,
    text: String,
    error: String?,
    saving: Boolean,
    onAction: (PromptsAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(PromptsAction.CloseEditor) },
        title = { Text(text = item.id) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text =
                        if (item.vars.isEmpty()) {
                            stringResource(R.string.prompts_vars_none)
                        } else {
                            stringResource(
                                R.string.prompts_vars_hint,
                                item.vars.sorted().joinToString(" ") { "{{$it}}" },
                            )
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { onAction(PromptsAction.SetEditText(it)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                if (error != null) {
                    Text(
                        text = stringResource(R.string.prompts_save_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            AutopButton(
                text = stringResource(R.string.prompts_save),
                onClick = { onAction(PromptsAction.Save) },
                enabled = !saving,
            )
        },
        dismissButton = {
            AutopTextButton(
                text = stringResource(R.string.prompts_clear_override),
                onClick = { onAction(PromptsAction.ClearOverride) },
                destructive = true,
            )
        },
    )
}
