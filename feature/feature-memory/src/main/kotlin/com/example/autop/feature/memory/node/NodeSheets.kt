/**
 * 条目编辑页弹层（docs/tech/08 §4.4）：版本历史（前后对比 mono + 撤销此变更）、
 * 更多菜单（移动 / 删除）、移动路径输入。
 */
package com.example.autop.feature.memory.node

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.OptionTone
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.feature.memory.R

/** 编辑页全部弹层（无状态显示，状态由 [NodeUiState] 驱动）。 */
@Composable
public fun NodeSheets(
    state: NodeUiState,
    onAction: (NodeAction) -> Unit,
) {
    if (state.showVersions && state.diffVersion == null) {
        OptionSheet(
            title = stringResource(R.string.memory_version_history),
            options =
                state.versions.map { version ->
                    OptionItem(
                        id = version.id.toString(),
                        label = formatTime(version.createdAt),
                        description = "${version.changeType} · ${version.source}",
                    )
                },
            selectedId = null,
            onSelect = { id -> onAction(NodeAction.ShowDiff(id.toLongOrNull() ?: 0L)) },
            onDismiss = { onAction(NodeAction.HideVersions) },
        )
    }
    if (state.diffVersion != null) {
        DiffDialog(
            version = state.diffVersion,
            onAction = onAction,
        )
    }
    if (state.showMore) {
        OptionSheet(
            title = stringResource(R.string.memory_more),
            options =
                listOf(
                    OptionItem(
                        id = "move",
                        label = stringResource(R.string.memory_move),
                        icon = OptionIcon.MOVE,
                    ),
                    OptionItem(
                        id = "delete",
                        label = stringResource(R.string.memory_delete_title),
                        icon = OptionIcon.DELETE,
                        tone = OptionTone.DESTRUCTIVE,
                    ),
                ),
            selectedId = null,
            onSelect = { id ->
                if (id == "move") {
                    onAction(NodeAction.ShowMove)
                } else {
                    onAction(NodeAction.Delete)
                }
            },
            onDismiss = { onAction(NodeAction.HideMore) },
        )
    }
    if (state.moveTarget != null) {
        MoveDialog(state = state, onAction = onAction)
    }
}

@Composable
private fun DiffDialog(
    version: VersionRow,
    onAction: (NodeAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(NodeAction.HideDiff) },
        title = {
            Text(
                text = stringResource(R.string.memory_version_history),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            DiffView(before = version.contentBefore.orEmpty(), after = version.contentAfter.orEmpty())
        },
        confirmButton = {
            AutopTextButton(
                text = stringResource(R.string.memory_revert_this),
                onClick = { onAction(NodeAction.Revert(version.id)) },
            )
        },
        dismissButton = {
            AutopTextButton(text = stringResource(R.string.memory_cancel), onClick = { onAction(NodeAction.HideDiff) })
        },
    )
}

/** 前后对比：mono，删行 error 底 α0.15、增行 primary 底 α0.15。 */
@Composable
internal fun DiffView(
    before: String,
    after: String,
) {
    val lines = diffLines(before, after)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = DIFF_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            val background =
                when (line.kind) {
                    DiffLine.Kind.ADDED ->
                        MaterialTheme.colorScheme.primary.copy(alpha = AutopAlpha.BLOCK_FILL)
                    DiffLine.Kind.REMOVED ->
                        MaterialTheme.colorScheme.error.copy(alpha = AutopAlpha.BLOCK_FILL)
                    DiffLine.Kind.SAME -> MaterialTheme.colorScheme.surface
                }
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(color = background)
                        .padding(horizontal = DIFF_H_PADDING, vertical = DIFF_V_PADDING),
            )
        }
    }
}

@Composable
private fun MoveDialog(
    state: NodeUiState,
    onAction: (NodeAction) -> Unit,
) {
    var target by remember(state.moveTarget) { mutableStateOf(state.moveTarget.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onAction(NodeAction.HideMove) },
        title = {
            Text(text = stringResource(R.string.memory_move), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            AutopInputBar(
                value = target,
                onValueChange = { target = it },
                spec = InputBarSpec(placeholder = stringResource(R.string.memory_move_placeholder)),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            AutopTextButton(
                text = stringResource(R.string.memory_confirm),
                onClick = { if (target.isNotBlank()) onAction(NodeAction.Move(target.trim())) },
            )
        },
        dismissButton = {
            AutopTextButton(text = stringResource(R.string.memory_cancel), onClick = { onAction(NodeAction.HideMove) })
        },
    )
}

private val DIFF_MAX_HEIGHT = 320.dp
private val DIFF_H_PADDING = 8.dp
private val DIFF_V_PADDING = 2.dp
