/**
 * 执行记录页：运行摘要、运行中控制（暂停 / 继续 / 停止 / 单步 / 失败后的处理）、逐节点明细。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.flows.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopOutlinedButton
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.JsonTreeView
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.durationText
import com.example.autop.feature.flows.nodeStatusLabel
import com.example.autop.feature.flows.nodeStatusTone
import com.example.autop.feature.flows.runStatusLabel
import com.example.autop.feature.flows.runStatusTone
import com.example.autop.flow.FlowNodeRecord
import com.example.autop.flow.FlowRunStatus
import com.example.autop.flow.PauseResolution

/** 执行记录页。 */
@Composable
public fun FlowRecordScreen(
    state: FlowRecordUiState,
    onAction: (FlowRecordAction) -> Unit,
) {
    val run = state.run
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(title = run?.flowName ?: stringResource(R.string.flow_record_title), onBack = {
            onAction(FlowRecordAction.Back)
        })
        if (state.loaded && run == null) {
            StatusBanner(
                tone = StatusTone.BLOCKED,
                message = stringResource(R.string.flow_record_missing),
                modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
            )
            return@Column
        }
        if (run == null) return@Column
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SummaryBlock(state = state, onAction = onAction) }
            item { SectionTitle(text = stringResource(R.string.flow_record_nodes)) }
            items(state.nodes, key = { it.rowId }) { node ->
                NodeBlock(node = node, expanded = node.rowId in state.expanded, onToggle = {
                    onAction(FlowRecordAction.Toggle(node.rowId))
                })
            }
        }
    }
}

@Composable
private fun SummaryBlock(
    state: FlowRecordUiState,
    onAction: (FlowRecordAction) -> Unit,
) {
    val run = state.run ?: return
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StatusChip(text = runStatusLabel(run.status.name), tone = runStatusTone(run.status.name))
            Text(
                text = durationText(run.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = spacing.md),
            )
        }
        run.summary?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.md))
        }
        run.errorCode?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
        Controls(status = run.status, state = state, onAction = onAction)
    }
    InfoRow(stringResource(R.string.flow_record_llm), null, "${run.llmCalls} / ${run.llmTokens}")
    InfoRow(stringResource(R.string.flow_record_api), null, "${run.apiCalls}")
    InfoRow(stringResource(R.string.flow_record_handoffs), null, "${run.handoffs}")
    run.outputs?.takeIf { it.isNotEmpty() }?.let {
        SectionTitle(text = stringResource(R.string.flow_record_outputs))
        JsonTreeView(text = it.toString(), modifier = Modifier.padding(horizontal = spacing.screenEdge))
    }
}

@Composable
private fun Controls(
    status: FlowRunStatus,
    state: FlowRecordUiState,
    onAction: (FlowRecordAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    if (status != FlowRunStatus.RUNNING && status != FlowRunStatus.PAUSED) return
    val paused = status == FlowRunStatus.PAUSED || state.progress?.paused == true
    Column(modifier = Modifier.padding(top = spacing.md)) {
        Row {
            if (paused) {
                AutopButton(
                    text = stringResource(R.string.flow_ctl_resume),
                    onClick = { onAction(FlowRecordAction.Resume) },
                    icon = AutopIcons.Play,
                    modifier = Modifier.weight(1f),
                )
            } else {
                AutopTonalButton(
                    text = stringResource(R.string.flow_ctl_pause),
                    onClick = { onAction(FlowRecordAction.Pause) },
                    icon = AutopIcons.Pause,
                    modifier = Modifier.weight(1f),
                )
            }
            AutopOutlinedButton(
                text = stringResource(R.string.flow_ctl_stop),
                onClick = { onAction(FlowRecordAction.Stop) },
                icon = AutopIcons.Stop,
                modifier = Modifier.weight(1f).padding(start = spacing.md),
            )
        }
        if (paused) {
            Row(modifier = Modifier.padding(top = spacing.md)) {
                AutopTonalButton(
                    text = stringResource(R.string.flow_ctl_step),
                    onClick = { onAction(FlowRecordAction.Step) },
                    modifier = Modifier.weight(1f),
                )
                AutopTonalButton(
                    text = stringResource(R.string.flow_ctl_continue),
                    onClick = { onAction(FlowRecordAction.Continue) },
                    modifier = Modifier.weight(1f).padding(start = spacing.md),
                )
            }
            Row(modifier = Modifier.padding(top = spacing.md)) {
                AutopTonalButton(
                    text = stringResource(R.string.flow_ctl_retry_node),
                    onClick = { onAction(FlowRecordAction.Resolve(PauseResolution.RETRY_NODE)) },
                    modifier = Modifier.weight(1f),
                )
                AutopTonalButton(
                    text = stringResource(R.string.flow_ctl_skip_node),
                    onClick = { onAction(FlowRecordAction.Resolve(PauseResolution.SKIP_NODE)) },
                    modifier = Modifier.weight(1f).padding(start = spacing.md),
                )
                AutopTonalButton(
                    text = stringResource(R.string.flow_ctl_end_run),
                    onClick = { onAction(FlowRecordAction.Resolve(PauseResolution.END_RUN)) },
                    modifier = Modifier.weight(1f).padding(start = spacing.md),
                )
            }
        }
        state.progress?.currentNodeName?.let {
            Text(
                text = stringResource(R.string.flow_ctl_current, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.md),
            )
        }
    }
}

@Composable
private fun NodeBlock(
    node: FlowNodeRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val isLog = node.nodeType == "log"
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onToggle,
                ).padding(horizontal = spacing.screenEdge, vertical = spacing.md),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = node.nodeName ?: if (isLog) node.message.orEmpty() else node.nodeId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (!isLog) StatusChip(text = nodeStatusLabel(node.status), tone = nodeStatusTone(node.status))
        }
        Text(
            text =
                "${node.nodeType} · ${node.path} · ${durationText(node.durationMs)}" +
                    if (node.attempts > 1) " · ×${node.attempts}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (node.errorCode != null) {
            Text(
                text = listOfNotNull(node.errorCode, node.message).joinToString("：").take(ERROR_LINE_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (expanded && !isLog) {
            node.input?.let {
                Text(stringResource(R.string.flow_record_input), style = MaterialTheme.typography.labelSmall)
                JsonTreeView(text = it.toString())
            }
            node.output?.let {
                Text(stringResource(R.string.flow_record_output), style = MaterialTheme.typography.labelSmall)
                JsonTreeView(text = it.toString())
            }
        }
    }
}

private const val ERROR_LINE_CHARS = 240
