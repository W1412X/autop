/**
 * 最近变更页（docs/tech/08 §4.4）：按 Run 分组。分组标题显示时间与条数；每条变更显示标题，
 * 下一行是按类型着色的半透明标签、来源与路径，右侧是圆形撤销按钮。证据原文不在列表中展示。
 */
package com.example.autop.feature.memory.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
                    GroupSection(
                        group = group,
                        onAction = onAction,
                        onOpenNode = onOpenNode,
                    )
                }
        }
    }
}

@Composable
private fun GroupSection(
    group: ChangeGroup,
    onAction: (ChangesAction) -> Unit,
    onOpenNode: (String) -> Unit,
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
        ChangeItem(row = row, onAction = onAction, onOpenNode = onOpenNode)
    }
}

@Composable
private fun ChangeItem(
    row: ChangeRow,
    onAction: (ChangesAction) -> Unit,
    onOpenNode: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpenNode(row.path) }
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
                    text = "${stringResource(sourceLabel(row.source))} · ${row.path}",
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

private const val PILL_CORNER_PERCENT: Int = 50
