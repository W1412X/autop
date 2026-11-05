/**
 * 任务列表页（docs/tech/08 §4.3）：分组标签 + 等待提醒横幅 + 任务行（启用开关）。
 */
package com.example.autop.feature.tasks.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.TopLevelHeader
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.tasks.R
import com.example.autop.feature.tasks.runStatusTextRes
import com.example.autop.feature.tasks.sourceLabel

/** 任务列表页。 */
@Composable
public fun TasksListScreen(
    state: TasksListUiState,
    onAction: (TasksListAction) -> Unit,
) {
    var menuTaskId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        TopLevelHeader(
            title = stringResource(R.string.tasks_title),
            actions = {
                AutopIconButton(
                    icon = AutopIcons.Add,
                    contentDescription = stringResource(R.string.tasks_new),
                    onClick = { onAction(TasksListAction.CreateTask) },
                )
            },
        )
        TaskTabs(tab = state.tab, onAction = onAction)
        if (state.waiting.isNotEmpty()) {
            WaitingBanner(waiting = state.waiting.first(), onAction = onAction)
        }
        if (state.rows.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.tasks_empty),
                icon = AutopIcons.EventRepeat,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.id }) { row ->
                    TaskRow(
                        row = row,
                        onClick = { onAction(TasksListAction.OpenDetail(row.id)) },
                        onLongClick = { menuTaskId = row.id },
                        onToggle = { onAction(TasksListAction.ToggleEnabled(row.id, it)) },
                    )
                }
            }
        }
    }
    TaskDialogs(
        menuTaskId = menuTaskId,
        pendingDeleteId = state.pendingDeleteId,
        onAction = onAction,
        onDismissMenu = { menuTaskId = null },
        onSelectMenu = { selected, id ->
            menuTaskId = null
            when (selected) {
                MENU_EDIT -> onAction(TasksListAction.OpenEdit(id))
                MENU_DELETE -> onAction(TasksListAction.RequestDelete(id))
            }
        },
    )
}

@Composable
private fun TaskDialogs(
    menuTaskId: String?,
    pendingDeleteId: String?,
    onAction: (TasksListAction) -> Unit,
    onDismissMenu: () -> Unit,
    onSelectMenu: (String, String) -> Unit,
) {
    menuTaskId?.let { id ->
        OptionSheet(
            title = null,
            options =
                listOf(
                    OptionItem(id = MENU_EDIT, label = stringResource(R.string.tasks_action_edit)),
                    OptionItem(id = MENU_DELETE, label = stringResource(R.string.tasks_action_delete)),
                ),
            selectedId = null,
            onSelect = { selected -> onSelectMenu(selected, id) },
            onDismiss = onDismissMenu,
        )
    }
    if (pendingDeleteId != null) {
        ConfirmDialog(
            title = stringResource(R.string.tasks_delete_title),
            text = stringResource(R.string.tasks_delete_text),
            onConfirm = { onAction(TasksListAction.ConfirmDelete) },
            onDismiss = { onAction(TasksListAction.DismissDelete) },
            destructive = true,
        )
    }
}

private const val MENU_EDIT = "edit"
private const val MENU_DELETE = "delete"

@Composable
private fun TaskTabs(
    tab: TasksTab,
    onAction: (TasksListAction) -> Unit,
) {
    val tabs = TasksTab.entries
    SecondaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
        tabs.forEach { item ->
            Tab(
                selected = item == tab,
                onClick = { onAction(TasksListAction.SetTab(item)) },
                text = { Text(stringResource(tabLabelRes(item))) },
            )
        }
    }
}

@Composable
private fun WaitingBanner(
    waiting: WaitingRunUi,
    onAction: (TasksListAction) -> Unit,
) {
    StatusBanner(
        tone = StatusTone.WAITING,
        title = stringResource(R.string.tasks_waiting_title),
        message = waiting.reason,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge),
        actions = {
            AutopTonalButton(
                text = stringResource(R.string.tasks_action_execute),
                onClick = { onAction(TasksListAction.ExecuteWaiting(waiting.runId)) },
            )
            AutopTonalButton(
                text = stringResource(R.string.tasks_action_snooze),
                onClick = { onAction(TasksListAction.SnoozeWaiting(waiting.runId)) },
            )
            AutopTonalButton(
                text = stringResource(R.string.tasks_action_skip),
                onClick = { onAction(TasksListAction.SkipWaiting(waiting.runId)) },
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    row: TaskRowUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    ListRow(
        title = row.name,
        subtitle = subtitleOf(row),
        leading = AutopIcons.EventRepeat,
        trailing = {
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
            )
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun subtitleOf(row: TaskRowUi): String {
    val trigger =
        when (row.triggerKind) {
            TasksTab.MANUAL -> stringResource(R.string.tasks_trigger_manual)
            TasksTab.SCHEDULE -> stringResource(R.string.tasks_trigger_schedule, scheduleText(row))
            TasksTab.CONDITION ->
                stringResource(
                    R.string.tasks_trigger_condition,
                    sourceLabel(row.triggerDetail),
                    row.cooldownMinutes,
                )
        }
    val last =
        row.lastRunStatus
            ?.let { stringResource(R.string.tasks_last_run, stringResource(runStatusTextRes(it))) }
            ?: stringResource(R.string.tasks_last_run_none)
    return "$trigger · $last"
}

/** 定时描述：模式 + 时刻；自定义 cron 原样表达式。 */
@Composable
private fun scheduleText(row: TaskRowUi): String =
    if (row.scheduleLabelRes == 0) {
        row.triggerDetail
    } else {
        "${stringResource(row.scheduleLabelRes)} ${row.triggerDetail}"
    }

private fun tabLabelRes(tab: TasksTab): Int =
    when (tab) {
        TasksTab.CONDITION -> R.string.tasks_tab_condition
        TasksTab.SCHEDULE -> R.string.tasks_tab_schedule
        TasksTab.MANUAL -> R.string.tasks_tab_manual
    }
