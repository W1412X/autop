/**
 * 最近变更页（docs/tech/08 §4.4）：按 Run 分组（SectionHeader + 全部撤销），
 * 每条 ListRow（路径、变化类型、evidence 摘要），trailing“撤销”文字按钮。
 */
package com.example.autop.feature.memory.changes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ErrorState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.PagedList
import com.example.autop.core.ui.component.SectionHeader
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.feature.memory.R
import com.example.autop.feature.memory.node.formatTime

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
                stringResource(R.string.memory_run_title, runId.take(RUN_ID_CHARS))
            },
        subtitle = formatTime(group.createdAt),
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
        ListRow(
            title = row.title,
            subtitle = subtitleParts(row.path, row.changeType, row.evidence),
            modifier = Modifier.clickable { onOpenNode(row.path) },
            trailing = {
                AutopTextButton(
                    text = stringResource(R.string.memory_revert),
                    onClick = { onAction(ChangesAction.Revert(row.versionId)) },
                )
            },
        )
    }
}

private const val RUN_ID_CHARS: Int = 8
