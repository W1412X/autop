/**
 * 流程状态管理页。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.flows.state

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.flows.R

/** 状态管理页。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun FlowStateScreen(
    state: FlowStateUiState,
    onAction: (FlowStateAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(title = stringResource(R.string.state_title), onBack = { onAction(FlowStateAction.Back) })
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.scopes.forEach { scope ->
                item(key = "t-" + scope.scope) {
                    SectionTitle(
                        text =
                            scope.taskName?.let { stringResource(R.string.state_scope_task, it) }
                                ?: stringResource(R.string.state_scope_flow),
                    )
                }
                if (scope.entries.isEmpty()) {
                    item(key = "e-" + scope.scope) { InfoRow(stringResource(R.string.state_empty), null, "") }
                }
                scope.entries.forEach { (key, value) ->
                    item(key = scope.scope + "/" + key) {
                        ListRow(
                            title = key,
                            subtitle = value,
                            modifier =
                                Modifier.combinedClickable(
                                    onClick = { onAction(FlowStateAction.Edit(scope.scope, key)) },
                                    onLongClick = { onAction(FlowStateAction.Delete(scope.scope, key)) },
                                ),
                        )
                    }
                }
                item(key = "a-" + scope.scope) {
                    Column(modifier = Modifier.padding(horizontal = LocalSpacing.current.screenEdge)) {
                        AutopTextButton(
                            text = stringResource(R.string.state_add),
                            onClick = { onAction(FlowStateAction.Add(scope.scope)) },
                        )
                        if (scope.entries.isNotEmpty()) {
                            AutopTextButton(
                                text = stringResource(R.string.state_clear),
                                onClick = { onAction(FlowStateAction.RequestClear(scope.scope)) },
                                destructive = true,
                            )
                        }
                    }
                }
            }
        }
    }
    state.edit?.let { edit ->
        AlertDialog(
            onDismissRequest = { onAction(FlowStateAction.Dismiss) },
            title = { Text(stringResource(if (edit.isNew) R.string.state_add else R.string.state_edit)) },
            text = {
                Column {
                    AutopInputBar(
                        value = edit.key,
                        onValueChange = { onAction(FlowStateAction.Change(edit.copy(key = it))) },
                        spec = InputBarSpec(placeholder = stringResource(R.string.state_key)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.sm),
                    )
                    AutopInputBar(
                        value = edit.value,
                        onValueChange = { onAction(FlowStateAction.Change(edit.copy(value = it))) },
                        spec = InputBarSpec(placeholder = stringResource(R.string.state_value), maxLines = 4),
                        modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.sm),
                    )
                    if (edit.error) Text(stringResource(R.string.state_key_required))
                }
            },
            confirmButton = {
                AutopTextButton(text = stringResource(R.string.flow_save), onClick = { onAction(FlowStateAction.Save) })
            },
            dismissButton = {
                AutopTextButton(
                    text = stringResource(R.string.flow_cancel),
                    onClick = { onAction(FlowStateAction.Dismiss) },
                )
            },
        )
    }
    if (state.pendingClear != null) {
        ConfirmDialog(
            title = stringResource(R.string.state_clear),
            text = stringResource(R.string.state_clear_text),
            onConfirm = { onAction(FlowStateAction.ConfirmClear) },
            onDismiss = { onAction(FlowStateAction.DismissClear) },
            destructive = true,
        )
    }
}
