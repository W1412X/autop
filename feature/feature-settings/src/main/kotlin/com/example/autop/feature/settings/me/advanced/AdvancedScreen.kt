/**
 * 高级配置页（docs/tech/08 §4.5"高级配置"）：配置项树按域分组，
 * 每项显示默认值/当前值；点击编辑整段 JSON 片段，校验失败显示错误且不生效。
 */
package com.example.autop.feature.settings.me.advanced

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
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.feature.settings.R

/** 单行副文本最大宽度（JSON 摘要截断）。 */
private const val JSON_PREVIEW_CHARS = 120

/**
 * 高级配置页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onAction 用户操作回调。
 */
@Composable
public fun AdvancedScreen(
    state: AdvancedUiState,
    onBack: () -> Unit,
    onAction: (AdvancedAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_nav_advanced_config), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.groups.forEach { group ->
                item { SectionTitle(text = group.domain) }
                items(group.items) { item ->
                    ListRow(
                        title = item.path,
                        subtitle =
                            stringResource(
                                if (item.overridden) {
                                    R.string.advanced_value_overridden
                                } else {
                                    R.string.advanced_value_default
                                },
                                item.valueJson.asPreview(),
                            ),
                        modifier = Modifier.clickable { onAction(AdvancedAction.Open(item.path)) },
                    )
                }
            }
            if (state.groups.isEmpty()) {
                item { EmptyState(text = stringResource(R.string.advanced_empty)) }
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

/** JSON 片段编辑对话框（校验失败显示错误且不生效）。 */
@Composable
private fun EditDialog(
    item: AdvancedItem,
    text: String,
    error: String?,
    saving: Boolean,
    onAction: (AdvancedAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(AdvancedAction.CloseEditor) },
        title = { Text(text = item.path) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.advanced_default_hint, item.defaultJson.asPreview()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { onAction(AdvancedAction.SetEditText(it)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            AutopButton(
                text = stringResource(R.string.advanced_save),
                onClick = { onAction(AdvancedAction.Save) },
                enabled = !saving,
            )
        },
        dismissButton = {
            AutopTextButton(
                text = stringResource(R.string.advanced_clear_override),
                onClick = { onAction(AdvancedAction.ClearOverride) },
                destructive = true,
            )
        },
    )
}

/** JSON 文本的单行摘要。 */
private fun String.asPreview(): String = replace(WHITESPACE, " ").take(JSON_PREVIEW_CHARS)

private val WHITESPACE = Regex("\\s+")
