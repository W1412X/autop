/**
 * 接口登记页：列表 + 新增/编辑对话框（地址、密钥、请求头、测试）。
 */
@file:Suppress("LongMethod") // Compose 对话框：布局声明

package com.example.autop.feature.flows.endpoints

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.durationText

/** 接口登记页。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun EndpointsScreen(
    state: EndpointsUiState,
    onAction: (EndpointsAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(
            title = stringResource(R.string.endpoints_title),
            onBack = { onAction(EndpointsAction.Back) },
            actions = {
                AutopIconButton(
                    icon = AutopIcons.Add,
                    contentDescription = stringResource(R.string.endpoints_add),
                    onClick = { onAction(EndpointsAction.Add) },
                )
            },
        )
        Text(
            text = stringResource(R.string.endpoints_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LocalSpacing.current.screenEdge),
        )
        if (state.endpoints.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.endpoints_empty),
                icon = AutopIcons.Link,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.endpoints, key = { it.name }) { e ->
                    ListRow(
                        title = e.name,
                        subtitle =
                            e.baseUrl + if (e.enabled) "" else " · " + stringResource(R.string.endpoints_disabled),
                        leading = AutopIcons.Link,
                        modifier =
                            Modifier.combinedClickable(
                                onClick = { onAction(EndpointsAction.Edit(e.name)) },
                                onLongClick = { onAction(EndpointsAction.RequestDelete(e.name)) },
                            ),
                    )
                }
            }
        }
    }
    state.form?.let { EndpointDialog(form = it, onAction = onAction) }
    state.pendingDelete?.let {
        ConfirmDialog(
            title = stringResource(R.string.endpoints_delete_title),
            text = stringResource(R.string.endpoints_delete_text, it),
            onConfirm = { onAction(EndpointsAction.ConfirmDelete) },
            onDismiss = { onAction(EndpointsAction.DismissDelete) },
            destructive = true,
        )
    }
}

@Composable
private fun EndpointDialog(
    form: EndpointForm,
    onAction: (EndpointsAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(EndpointsAction.Dismiss) },
        title = {
            Text(
                stringResource(
                    if (form.originalName ==
                        null
                    ) {
                        R.string.endpoints_add
                    } else {
                        R.string.endpoints_edit
                    },
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                field(R.string.endpoints_name, form.name) { onAction(EndpointsAction.Change(form.copy(name = it))) }
                field(
                    R.string.endpoints_base_url,
                    form.baseUrl,
                ) { onAction(EndpointsAction.Change(form.copy(baseUrl = it))) }
                AutopInputBar(
                    value = form.secret,
                    onValueChange = { onAction(EndpointsAction.Change(form.copy(secret = it))) },
                    spec =
                        InputBarSpec(
                            placeholder =
                                stringResource(
                                    if (form.hasSecret) R.string.endpoints_secret_keep else R.string.endpoints_secret,
                                ),
                            password = true,
                        ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.sm),
                )
                field(R.string.endpoints_secret_header, form.secretHeader) {
                    onAction(EndpointsAction.Change(form.copy(secretHeader = it)))
                }
                field(R.string.endpoints_secret_prefix, form.secretPrefix) {
                    onAction(EndpointsAction.Change(form.copy(secretPrefix = it)))
                }
                AutopInputBar(
                    value = form.headers,
                    onValueChange = { onAction(EndpointsAction.Change(form.copy(headers = it))) },
                    spec = InputBarSpec(placeholder = stringResource(R.string.endpoints_headers), maxLines = 4),
                    modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.sm),
                )
                SwitchRow(
                    title = stringResource(R.string.endpoints_enabled),
                    subtitle = null,
                    checked = form.enabled,
                    onCheckedChange = { onAction(EndpointsAction.Change(form.copy(enabled = it))) },
                )
                field(
                    R.string.endpoints_test_path,
                    form.testPath,
                ) { onAction(EndpointsAction.Change(form.copy(testPath = it))) }
                AutopTonalButton(
                    text = stringResource(if (form.testing) R.string.endpoints_testing else R.string.endpoints_test),
                    onClick = { onAction(EndpointsAction.Test) },
                    enabled = !form.testing,
                    modifier = Modifier.fillMaxWidth(),
                )
                form.testResult?.let { r ->
                    StatusBanner(
                        tone = if (r.ok) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
                        message =
                            stringResource(
                                if (r.ok) R.string.endpoints_test_ok else R.string.endpoints_test_fail,
                                r.status?.toString() ?: r.message.orEmpty(),
                                durationText(r.durationMs),
                            ),
                        modifier = Modifier.fillMaxWidth().padding(top = LocalSpacing.current.md),
                    )
                }
                form.error?.let {
                    StatusBanner(
                        tone = StatusTone.BLOCKED,
                        message = stringResource(R.string.flow_error_generic, it),
                        modifier = Modifier.fillMaxWidth().padding(top = LocalSpacing.current.md),
                    )
                }
            }
        },
        confirmButton = {
            AutopTextButton(text = stringResource(R.string.flow_save), onClick = { onAction(EndpointsAction.Save) })
        },
        dismissButton = {
            AutopTextButton(
                text = stringResource(R.string.flow_cancel),
                onClick = { onAction(EndpointsAction.Dismiss) },
            )
        },
    )
}

@Composable
private fun field(
    placeholder: Int,
    value: String,
    onChange: (String) -> Unit,
) {
    AutopInputBar(
        value = value,
        onValueChange = onChange,
        spec = InputBarSpec(placeholder = stringResource(placeholder)),
        modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.sm),
    )
}
