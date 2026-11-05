/**
 * Run 详情页（docs/tech/08 §4.3）：只读状态/摘要/触发上下文 + 过程时间线 + “在此基础上继续”。
 */
package com.example.autop.feature.tasks.run

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
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

/** Run 详情页。 */
@Composable
public fun RunDetailScreen(
    state: RunDetailUiState,
    onAction: (RunDetailAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SubPageTopBar(
            title = stringResource(R.string.run_detail_title),
            onBack = { onAction(RunDetailAction.Back) },
        )
        if (state.notFound) {
            EmptyState(
                text = stringResource(R.string.run_detail_not_found),
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { StatusSection(state = state) }
            if (state.canResume) {
                item {
                    AutopButton(
                        text = stringResource(R.string.run_detail_resume),
                        onClick = { onAction(RunDetailAction.ResumeInterrupted) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(LocalSpacing.current.screenEdge),
                    )
                }
            }
            item {
                AutopButton(
                    text = stringResource(R.string.run_detail_continue),
                    onClick = { onAction(RunDetailAction.Continue) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(LocalSpacing.current.screenEdge),
                )
            }
            item { FeedbackBanner(feedback = state.feedback) }
            item { TriggerContextSection(state = state) }
            item { TimelineSection(state = state) }
        }
    }
}

@Composable
private fun TimelineSection(state: RunDetailUiState) {
    SectionTitle(text = stringResource(R.string.run_detail_timeline))
    if (state.timeline.isEmpty()) {
        ListRow(title = stringResource(R.string.run_detail_no_summary), subtitle = "")
    } else {
        state.timeline.forEach { step ->
            ListRow(
                title = stringResource(timelineKindRes(step.kind)),
                subtitle = step.detail,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalSpacing.current.screenEdge),
            )
        }
    }
}

@Composable
private fun StatusSection(state: RunDetailUiState) {
    val spacing = LocalSpacing.current
    StatusChip(
        text = stringResource(runStatusTextRes(state.statusName)),
        tone = runStatusTone(state.statusName),
        modifier = Modifier.padding(spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.run_detail_summary),
        subtitle = state.summary.ifBlank { stringResource(R.string.run_detail_no_summary) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.run_detail_origin),
        subtitle = state.originName,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    ListRow(
        title = stringResource(R.string.run_detail_started_at),
        subtitle = state.startedText,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
    )
    if (state.endedText.isNotBlank()) {
        ListRow(
            title = stringResource(R.string.run_detail_finished_at),
            subtitle = state.endedText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
        )
    }
}

@Composable
private fun TriggerContextSection(state: RunDetailUiState) {
    if (state.triggerContext.isEmpty()) return
    SectionTitle(text = stringResource(R.string.run_detail_trigger_ctx))
    state.triggerContext.forEach { line ->
        ListRow(
            title = line,
            subtitle = "",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalSpacing.current.screenEdge),
        )
    }
}

@Composable
private fun FeedbackBanner(feedback: RunDetailFeedback?) {
    if (feedback == null) return
    val message =
        when {
            feedback.kind == RunDetailFeedbackKind.RESUME && feedback.continued ->
                stringResource(R.string.run_detail_resumed)
            feedback.kind == RunDetailFeedbackKind.RESUME ->
                stringResource(R.string.run_detail_resume_failed, feedback.errorCode.orEmpty())
            feedback.continued -> stringResource(R.string.run_detail_forked)
            else -> stringResource(R.string.run_detail_fork_failed, feedback.errorCode.orEmpty())
        }
    StatusBanner(
        tone = if (feedback.continued) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
        message = message,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge),
    )
}

private fun timelineKindRes(kind: TimelineKind): Int =
    when (kind) {
        TimelineKind.USER -> R.string.run_timeline_user
        TimelineKind.ASSISTANT -> R.string.run_timeline_assistant
        TimelineKind.TOOL -> R.string.run_timeline_tool
        TimelineKind.STARTED -> R.string.run_timeline_started
        TimelineKind.FINISHED -> R.string.run_timeline_finished
        TimelineKind.PAUSED -> R.string.run_timeline_paused
        TimelineKind.RESUMED -> R.string.run_timeline_resumed
        TimelineKind.NOTE -> R.string.run_timeline_note
        TimelineKind.PLAN -> R.string.run_timeline_plan
        TimelineKind.OTHER -> R.string.run_timeline_other
    }
