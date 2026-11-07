/**
 * 流程详情页：说明、运行/调试/编辑入口、累计统计、最易失败的节点、参数、运行记录。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.flows.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopOutlinedButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.flows.ParamFields
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.durationText
import com.example.autop.feature.flows.runStatusLabel
import com.example.autop.feature.flows.textOf
import com.example.autop.flow.FlowRunStatus

/** 流程详情页。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun FlowDetailScreen(
    state: FlowDetailUiState,
    onAction: (FlowDetailAction) -> Unit,
) {
    val flow = state.flow
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(
            title = flow?.name ?: stringResource(R.string.flow_detail_title),
            onBack = { onAction(FlowDetailAction.Back) },
        )
        if (state.loaded && flow == null) {
            StatusBanner(
                tone = StatusTone.BLOCKED,
                message = stringResource(R.string.flow_not_found),
                modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
            )
            return@Column
        }
        if (flow == null) return@Column
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { HeaderBlock(state = state, onAction = onAction) }
            item { StatsBlock(state = state) }
            if (state.failing.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.flow_detail_failing)) }
                items(state.failing, key = { "f-" + it.nodeId }) { f ->
                    InfoRow(
                        title = f.name,
                        subtitle = stringResource(R.string.flow_detail_failing_sub, f.executions),
                        value = "${f.failurePercent}%",
                    )
                }
            }
            if (flow.params.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.flow_detail_params)) }
                items(flow.params, key = { "p-" + it.name }) { p ->
                    InfoRow(
                        title = p.name,
                        subtitle = p.description.ifBlank { null },
                        value =
                            if (p.required) {
                                stringResource(R.string.flow_param_required)
                            } else {
                                p.default?.let { textOf(it) }.orEmpty()
                            },
                    )
                }
            }
            item {
                NavRow(title = stringResource(R.string.flow_detail_state), subtitle = null, onClick = {
                    onAction(FlowDetailAction.OpenState)
                })
            }
            item {
                NavRow(title = stringResource(R.string.flow_detail_versions), subtitle = null, onClick = {
                    onAction(FlowDetailAction.OpenVersions)
                })
            }
            item { SectionTitle(text = stringResource(R.string.flow_detail_runs)) }
            items(state.runs, key = { it.runId }) { run ->
                ListRow(
                    title = runStatusLabel(run.status.name),
                    subtitle = runSubtitle(run.durationMs, run.origin, run.errorCode),
                    leading = if (run.status == FlowRunStatus.SUCCEEDED) AutopIcons.CheckCircle else AutopIcons.Play,
                    modifier =
                        Modifier.combinedClickable(
                            onClick = { onAction(FlowDetailAction.OpenRun(run.runId)) },
                            onLongClick = { onAction(FlowDetailAction.RequestDeleteRun(run.runId)) },
                        ),
                )
            }
            if (state.runs.isNotEmpty()) {
                item {
                    AutopTextButton(
                        text = stringResource(R.string.flow_detail_clear),
                        onClick = { onAction(FlowDetailAction.RequestClear) },
                        destructive = true,
                        modifier = Modifier.padding(LocalSpacing.current.screenEdge),
                    )
                }
            }
        }
    }
    DetailDialogs(state = state, onAction = onAction)
}

@Composable
private fun runSubtitle(
    durationMs: Long?,
    origin: String,
    errorCode: String?,
): String {
    val parts = mutableListOf(durationText(durationMs), originLabel(origin))
    errorCode?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
private fun originLabel(origin: String): String =
    stringResource(
        when (origin) {
            "agent" -> R.string.flow_origin_agent
            "schedule" -> R.string.flow_origin_schedule
            "condition" -> R.string.flow_origin_condition
            else -> R.string.flow_origin_manual
        },
    )

@Composable
private fun HeaderBlock(
    state: FlowDetailUiState,
    onAction: (FlowDetailAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    val flow = state.flow ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge)) {
        if (flow.description.isNotBlank()) {
            Text(
                text = flow.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.lg)) {
            AutopButton(
                text = stringResource(R.string.flow_action_run),
                onClick = { onAction(FlowDetailAction.Run(debug = false)) },
                icon = AutopIcons.Play,
                modifier = Modifier.weight(1f),
            )
            AutopOutlinedButton(
                text = stringResource(R.string.flow_action_debug),
                onClick = { onAction(FlowDetailAction.Run(debug = true)) },
                modifier = Modifier.weight(1f).padding(start = spacing.md),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.md)) {
            AutopTonalButton(
                text = stringResource(R.string.flow_action_edit),
                onClick = { onAction(FlowDetailAction.Edit) },
                icon = AutopIcons.Edit,
                modifier = Modifier.weight(1f),
            )
            AutopTonalButton(
                text = stringResource(R.string.flow_action_ai_edit),
                onClick = { onAction(FlowDetailAction.AiEdit) },
                modifier = Modifier.weight(1f).padding(start = spacing.md),
            )
            AutopTonalButton(
                text = stringResource(R.string.flow_action_export),
                onClick = { onAction(FlowDetailAction.Export) },
                modifier = Modifier.weight(1f).padding(start = spacing.md),
            )
        }
        state.error?.let { code ->
            StatusBanner(
                tone = StatusTone.BLOCKED,
                message = errorText(code),
                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
            )
        }
    }
}

@Composable
private fun errorText(code: String): String =
    when (code) {
        FlowDetailViewModel.MISSING_PARAM -> stringResource(R.string.flow_error_missing_param)
        else -> stringResource(R.string.flow_error_generic, code)
    }

@Composable
private fun StatsBlock(state: FlowDetailUiState) {
    val s = state.stats ?: return
    SectionTitle(text = stringResource(R.string.flow_detail_stats))
    InfoRow(stringResource(R.string.flow_stat_runs), null, "${s.totalRuns}")
    InfoRow(stringResource(R.string.flow_stat_success), null, "${(s.successRate * PERCENT).toInt()}%")
    InfoRow(stringResource(R.string.flow_stat_avg), null, durationText(s.avgDurationMs))
    InfoRow(stringResource(R.string.flow_stat_max), null, durationText(s.maxDurationMs))
    InfoRow(stringResource(R.string.flow_stat_tokens), null, "${s.llmTokens}")
    InfoRow(stringResource(R.string.flow_stat_handoffs), null, "${s.handoffs}")
}

private const val PERCENT = 100

@Composable
private fun DetailDialogs(
    state: FlowDetailUiState,
    onAction: (FlowDetailAction) -> Unit,
) {
    val form = state.runForm
    val flow = state.flow
    if (form != null && flow != null) {
        AlertDialog(
            onDismissRequest = { onAction(FlowDetailAction.DismissRun) },
            title = { Text(stringResource(if (form.debug) R.string.flow_action_debug else R.string.flow_action_run)) },
            text = {
                Column {
                    if (flow.params.isEmpty()) {
                        Text(stringResource(R.string.flow_run_no_params))
                    } else {
                        ParamFields(
                            flow.params,
                            form.values,
                        ) { name, value -> onAction(FlowDetailAction.SetParam(name, value)) }
                    }
                }
            },
            confirmButton = {
                AutopTextButton(
                    text = stringResource(R.string.flow_run_start),
                    onClick = { onAction(FlowDetailAction.ConfirmRun) },
                )
            },
            dismissButton = {
                AutopTextButton(
                    text = stringResource(R.string.flow_cancel),
                    onClick = { onAction(FlowDetailAction.DismissRun) },
                )
            },
        )
    }
    if (state.pendingDeleteRunId != null) {
        ConfirmDialog(
            title = stringResource(R.string.flow_run_delete_title),
            text = stringResource(R.string.flow_run_delete_text),
            onConfirm = { onAction(FlowDetailAction.ConfirmDeleteRun) },
            onDismiss = { onAction(FlowDetailAction.DismissDeleteRun) },
            destructive = true,
        )
    }
    if (state.confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.flow_run_clear_title),
            text = stringResource(R.string.flow_run_clear_text),
            onConfirm = { onAction(FlowDetailAction.ConfirmClear) },
            onDismiss = { onAction(FlowDetailAction.DismissClear) },
            destructive = true,
        )
    }
}
