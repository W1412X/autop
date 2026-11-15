/**
 * 最近变更页（docs/tech/08 §4.4）：按 Run 分组。列表行只显示标题、类型与路径；点行打开详情，
 * 详情里分段显示时间、类型、来源、路径、字数变化、原因、证据与内容摘要，并可打开条目或撤销。
 */
package com.example.autop.feature.memory.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ErrorState
import com.example.autop.core.ui.component.PagedList
import com.example.autop.core.ui.component.SectionHeader
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.tone
import com.example.autop.feature.memory.R
import com.example.autop.feature.memory.node.formatTime
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemorySource

/** 最近变更页。 */
@Composable
public fun ChangesScreen(
    state: ChangesUiState,
    onAction: (ChangesAction) -> Unit,
    onBack: () -> Unit,
    onOpenNode: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.memory_recent_changes), onBack = onBack)
        when {
            state.error != null ->
                ErrorState(text = state.error.orEmpty(), onRetry = { onAction(ChangesAction.Retry) })
            state.loading && state.groups.isEmpty() ->
                EmptyState(text = stringResource(R.string.memory_loading))
            state.groups.isEmpty() ->
                EmptyState(text = stringResource(R.string.memory_changes_empty))
            else ->
                PagedList(
                    items = state.groups,
                    state = state.paged,
                    onLoadMore = { onAction(ChangesAction.LoadMore) },
                    modifier = Modifier.padding(horizontal = LocalSpacing.current.screenEdge),
                ) { group ->
                    GroupSection(group = group, onAction = onAction)
                }
        }
    }
    state.selected?.let { row ->
        ChangeDetailDialog(row = row, onAction = onAction, onOpenNode = onOpenNode)
    }
}

@Composable
private fun GroupSection(
    group: ChangeGroup,
    onAction: (ChangesAction) -> Unit,
) {
    val runId = group.runId
    SectionHeader(
        title =
            if (runId == null) {
                stringResource(R.string.memory_changes_manual_group)
            } else {
                stringResource(R.string.memory_run_group)
            },
        subtitle = "${formatTime(
            group.createdAt,
        )} · ${stringResource(R.string.memory_recent_changes_hint, group.rows.size)}",
        action =
            if (runId != null) {
                {
                    AutopTextButton(
                        text = stringResource(R.string.memory_revert_all),
                        onClick = { onAction(ChangesAction.RevertRun(runId)) },
                    )
                }
            } else {
                null
            },
    )
    group.rows.forEach { row ->
        ChangeItem(row = row, onAction = onAction)
    }
}

/** 列表行：标题、类型标签与路径；右侧圆形撤销按钮。点行打开详情。 */
@Composable
private fun ChangeItem(
    row: ChangeRow,
    onAction: (ChangesAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onAction(ChangesAction.Select(row.versionId)) }
                .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypePill(text = stringResource(changeTypeLabel(row.changeType)), tone = changeTone(row.changeType))
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    text = row.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.width(spacing.sm))
        AutopIconButton(
            icon = AutopIcons.Undo,
            contentDescription = stringResource(R.string.memory_revert),
            onClick = { onAction(ChangesAction.Revert(row.versionId)) },
            compact = true,
        )
    }
}

/** 变更详情：分段显示各项信息，底部可打开条目或撤销。 */
@Composable
private fun ChangeDetailDialog(
    row: ChangeRow,
    onAction: (ChangesAction) -> Unit,
    onOpenNode: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = { onAction(ChangesAction.ClearSelection) },
        title = { Text(text = row.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                DetailField(stringResource(R.string.memory_detail_time), formatTime(row.createdAt))
                DetailField(
                    stringResource(R.string.memory_detail_type),
                    stringResource(changeTypeLabel(row.changeType)),
                )
                DetailField(stringResource(R.string.memory_detail_source), stringResource(sourceLabel(row.source)))
                DetailField(stringResource(R.string.memory_detail_path), row.path)
                DetailField(stringResource(R.string.memory_detail_chars), signedDelta(row.charDelta))
                DetailField(stringResource(R.string.memory_detail_reason), row.reason)
                DetailField(stringResource(R.string.memory_detail_evidence), row.evidence)
                DetailField(stringResource(R.string.memory_detail_snippet), row.snippet)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onOpenNode(row.path)
                onAction(ChangesAction.ClearSelection)
            }) {
                Text(stringResource(R.string.memory_open_item))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onAction(ChangesAction.Revert(row.versionId))
                onAction(ChangesAction.ClearSelection)
            }) {
                Text(stringResource(R.string.memory_revert))
            }
        },
    )
}

/** 详情中的一项：小号灰色标签 + 值；值为空时不显示。 */
@Composable
private fun DetailField(
    label: String,
    value: String?,
) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 半透明底的类型标签（颜色取自语义色）。 */
@Composable
private fun TypePill(
    text: String,
    tone: StatusTone,
) {
    val semantic = LocalSemanticColors.current.tone(tone)
    val spacing = LocalSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = semantic.foreground,
        maxLines = 1,
        modifier =
            Modifier
                .background(
                    color = semantic.foreground.copy(alpha = AutopAlpha.BANNER_COMPACT_FILL),
                    shape = RoundedCornerShape(percent = PILL_CORNER_PERCENT),
                ).padding(horizontal = spacing.sm, vertical = spacing.xs),
    )
}

private fun changeTone(type: ChangeType): StatusTone =
    when (type) {
        ChangeType.CREATE -> StatusTone.SUCCEEDED
        ChangeType.UPDATE, ChangeType.APPEND, ChangeType.SUPPLEMENT -> StatusTone.RUNNING
        ChangeType.DELETE -> StatusTone.BLOCKED
        ChangeType.MOVE, ChangeType.CONFIRM -> StatusTone.WAITING
        ChangeType.REVERT -> StatusTone.NEUTRAL
    }

private fun changeTypeLabel(type: ChangeType): Int =
    when (type) {
        ChangeType.CREATE -> R.string.memory_change_create
        ChangeType.UPDATE -> R.string.memory_change_update
        ChangeType.APPEND -> R.string.memory_change_append
        ChangeType.SUPPLEMENT -> R.string.memory_change_supplement
        ChangeType.DELETE -> R.string.memory_change_delete
        ChangeType.MOVE -> R.string.memory_change_move
        ChangeType.REVERT -> R.string.memory_change_revert
        ChangeType.CONFIRM -> R.string.memory_change_confirm
    }

private fun sourceLabel(source: MemorySource): Int =
    when (source) {
        MemorySource.USER -> R.string.memory_source_user
        MemorySource.AGENT, MemorySource.FLOW -> R.string.memory_source_agent
        MemorySource.IMPORT -> R.string.memory_source_import
    }

/** 字数变化带符号显示，如 +12、-5、0。 */
private fun signedDelta(delta: Int): String = if (delta > 0) "+$delta" else delta.toString()

private const val PILL_CORNER_PERCENT: Int = 50
