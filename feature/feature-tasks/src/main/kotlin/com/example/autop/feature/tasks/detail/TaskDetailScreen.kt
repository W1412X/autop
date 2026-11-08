/**
 * 任务详情页（docs/tech/08 §4.3）：摘要 + 立即运行 / 编辑 + 运行记录。
 */
package com.example.autop.feature.tasks.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.tasks.R
import com.example.autop.feature.tasks.runStatusTextRes
import com.example.autop.feature.tasks.runStatusTone
import com.example.autop.feature.tasks.sourceLabel
import com.example.autop.feature.tasks.sourceLabelRes

/** 任务详情页。 */
@Composable
public fun TaskDetailScreen(
    state: TaskDetailUiState,
    onAction: (TaskDetailAction) -> Unit,
) {
    if (state.notFound) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            SubPageTopBar(
                title = stringResource(R.string.task_detail_title),
                onBack = { onAction(TaskDetailAction.Back) },
            )
            EmptyState(
                text = stringResource(R.string.task_detail_not_found),
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(
            title = stringResource(R.string.task_detail_title),
            onBack = { onAction(TaskDetailAction.Back) },
            actions = {
                AutopTonalButton(
                    text = stringResource(R.string.task_detail_edit),
                    onClick = { onAction(TaskDetailAction.OpenEdit) },
                )
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SummarySection(state = state) }
            item {
                AutopButton(
                    text = stringResource(R.string.task_detail_run_now),
                    onClick = { onAction(TaskDetailAction.RunNow) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(LocalSpacing.current.screenEdge),
                )
            }
            item { FeedbackBanner(feedback = state.feedback) }
            item { SectionTitle(text = stringResource(R.string.task_detail_runs)) }
            if (state.runs.isEmpty()) {
                item {
                    ListRow(title = stringResource(R.string.task_detail_no_runs), subtitle = "")
                }
            } else {
                items(state.runs, key = { it.runId }) { run ->
                    RunRow(run = run, onClick = { onAction(TaskDetailAction.OpenRun(run.runId)) })
                }
            }
        }
    }
}

@Composable
private fun SummarySection(state: TaskDetailUiState) {
    val spacing = LocalSpacing.current
    SectionTitle(text = stringResource(R.string.task_detail_config))
    ListRow(
        title = state.name,
        subtitle = state.goal,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.task_detail_trigger),
        subtitle = triggerSubtitle(state),
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.task_edit_needs_screen),
        subtitle = stringResource(if (state.needsScreen) R.string.task_edit_yes else R.string.task_edit_no),
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.task_edit_locked_behavior),
        subtitle =
            stringResource(
                if (state.lockedRemind) R.string.task_edit_locked_remind else R.string.task_edit_locked_skip,
            ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.task_edit_priority),
        subtitle = state.priority.toString(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
}

/** 触发描述：定时模式 + 时刻；条件任务给事件源中文名；其余原样，手动回落到“手动触发”。 */
@Composable
private fun triggerSubtitle(state: TaskDetailUiState): String =
    when {
        state.triggerLabelRes != 0 -> "${stringResource(state.triggerLabelRes)} ${state.triggerText}"
        sourceLabelRes(state.triggerText) != 0 -> sourceLabel(state.triggerText)
        state.triggerText.isNotBlank() -> state.triggerText
        else -> stringResource(R.string.tasks_trigger_manual)
    }

@Composable
private fun FeedbackBanner(feedback: TaskDetailFeedback?) {
    if (feedback == null) return
    val message =
        if (feedback.started) {
            stringResource(R.string.task_detail_running)
        } else {
            stringResource(R.string.task_detail_run_failed, feedback.errorCode.orEmpty())
        }
    StatusBanner(
        tone = if (feedback.started) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
        message = message,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge),
    )
}

@Composable
private fun RunRow(
    run: RunRowUi,
    onClick: () -> Unit,
) {
    ListRow(
        title = run.summary.ifBlank { stringResource(runStatusTextRes(run.statusName)) },
        subtitle = "${stringResource(runStatusTextRes(run.statusName))} · ${run.originName} · ${run.timeText}",
        trailing = {
            StatusChip(
                text = stringResource(runStatusTextRes(run.statusName)),
                tone = runStatusTone(run.statusName),
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
