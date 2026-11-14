/**
 * Run 详情页（docs/tech/08 §4.3）：只读状态/摘要/触发上下文 + 过程时间线 + “在此基础上继续”。
 */
package com.example.autop.feature.tasks.run

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.example.autop.core.thread.ui.TimelineAction
import com.example.autop.core.thread.ui.TimelineRow
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
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
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
        state.entryMenu?.let { menu -> EntryMenuSheet(menu = menu, onAction = onAction) }
        state.rawText?.let { text -> RawDataDialog(text = text, onAction = onAction) }
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
            item { TimelineSection(state = state, onAction = onAction) }
        }
    }
}

@Composable
private fun TimelineSection(
    state: RunDetailUiState,
    onAction: (RunDetailAction) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SectionTitle(text = stringResource(R.string.run_detail_timeline))
    if (state.timeline.isEmpty()) {
        ListRow(title = stringResource(R.string.run_detail_no_summary), subtitle = "")
    } else {
        state.timeline.forEach { entry ->
            TimelineRow(
                entry = entry,
                onAction = { action ->
                    if (action == TimelineAction.CopyReply) {
                        clipboard.setText(AnnotatedString(state.summary))
                    } else {
                        onAction(RunDetailAction.Timeline(action))
                    }
                },
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

/** 长按过程条目的菜单：从这里分叉（仅可分叉点）与查看原始数据。 */
@Composable
private fun EntryMenuSheet(
    menu: RunEntryMenu,
    onAction: (RunDetailAction) -> Unit,
) {
    val options =
        buildList {
            if (menu.forkable) add(OptionItem(id = FORK_ID, label = stringResource(R.string.run_entry_menu_fork)))
            add(OptionItem(id = RAW_ID, label = stringResource(R.string.run_entry_menu_raw)))
        }
    OptionSheet(
        title = stringResource(R.string.run_entry_menu_title),
        options = options,
        selectedId = null,
        onSelect = { id ->
            when (id) {
                FORK_ID -> onAction(RunDetailAction.Fork(menu.seq))
                RAW_ID -> onAction(RunDetailAction.ShowRaw(menu.seq))
            }
        },
        onDismiss = { onAction(RunDetailAction.DismissEntryMenu) },
    )
}

/** 原始数据弹窗：事件的 seq 与载荷，可滚动。 */
@Composable
private fun RawDataDialog(
    text: String,
    onAction: (RunDetailAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(RunDetailAction.DismissRaw) },
        title = { Text(text = stringResource(R.string.run_raw_title)) },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAction(RunDetailAction.DismissRaw) }) {
                Text(text = stringResource(R.string.run_raw_close))
            }
        },
    )
}

private const val FORK_ID: String = "fork"
private const val RAW_ID: String = "raw"
