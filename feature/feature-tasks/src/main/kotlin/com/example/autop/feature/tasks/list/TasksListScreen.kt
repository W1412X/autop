/**
 * 任务页（docs/tech/08 §4.3）：顶部「任务 / 流程」两个页签。
 * 任务页签把条件触发、定时、手动合并成一个列表，内部按触发方式分组；
 * 流程页签的内容由 app 通过 [flowsContent] 提供（feature 之间不互相依赖）。
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
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.OptionTone
import com.example.autop.core.ui.component.SectionTitle
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
    flowsContent: @Composable () -> Unit = {},
) {
    var menuTaskId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        ListHeader(
            page = state.page,
            onAdd = {
                if (state.page == TasksPage.FLOWS) {
                    onAction(TasksListAction.CreateFlow)
                } else {
                    showCreate = true
                }
            },
        )
        PageTabs(page = state.page, onAction = onAction)
        if (state.page == TasksPage.FLOWS) {
            flowsContent()
        } else {
            TaskList(state = state, onAction = onAction, onLongPress = { menuTaskId = it })
        }
    }
    if (showCreate) {
        CreateSheet(
            onSelect = { selected ->
                showCreate = false
                onAction(if (selected == CREATE_AI) TasksListAction.CreateTaskAi else TasksListAction.CreateTask)
            },
            onDismiss = { showCreate = false },
        )
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

/** 新建任务入口：AI 创建（对话式）/ 手动创建（表单）。 */
@Composable
private fun CreateSheet(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = null,
        options =
            listOf(
                OptionItem(
                    id = CREATE_AI,
                    label = stringResource(R.string.task_create_ai),
                    icon = OptionIcon.AI,
                ),
                OptionItem(
                    id = CREATE_MANUAL,
                    label = stringResource(R.string.task_create_manual),
                    icon = OptionIcon.EDIT,
                ),
            ),
        selectedId = null,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

private const val CREATE_AI = "ai"
private const val CREATE_MANUAL = "manual"

/** 顶部标题栏：右上「＋」——任务页签弹新建入口，流程页签直接进流程创建页。 */
@Composable
private fun ListHeader(
    page: TasksPage,
    onAdd: () -> Unit,
) {
    TopLevelHeader(
        title = stringResource(R.string.tasks_title),
        actions = {
            AutopIconButton(
                icon = AutopIcons.Add,
                contentDescription =
                    stringResource(
                        if (page == TasksPage.FLOWS) R.string.tasks_flow_new else R.string.tasks_new,
                    ),
                onClick = onAdd,
            )
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
                    OptionItem(
                        id = MENU_EDIT,
                        label = stringResource(R.string.tasks_action_edit),
                        icon = OptionIcon.EDIT,
                    ),
                    OptionItem(
                        id = MENU_DELETE,
                        label = stringResource(R.string.tasks_action_delete),
                        icon = OptionIcon.DELETE,
                        tone = OptionTone.DESTRUCTIVE,
                    ),
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
private fun PageTabs(
    page: TasksPage,
    onAction: (TasksListAction) -> Unit,
) {
    val pages = TasksPage.entries
    SecondaryTabRow(selectedTabIndex = pages.indexOf(page)) {
        pages.forEach { item ->
            Tab(
                selected = item == page,
                onClick = { onAction(TasksListAction.SetPage(item)) },
                text = { Text(stringResource(pageLabelRes(item))) },
            )
        }
    }
}

/** 任务页签：等待横幅 + 按触发方式分组的任务列表（条件 / 定时 / 手动在同一列表内区分）。 */
@Composable
private fun TaskList(
    state: TasksListUiState,
    onAction: (TasksListAction) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (state.waiting.isNotEmpty()) {
        WaitingBanner(waiting = state.waiting.first(), onAction = onAction)
    }
    if (state.rows.isEmpty()) {
        EmptyState(
            text = stringResource(R.string.tasks_empty),
            icon = AutopIcons.EventRepeat,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        TasksTab.entries.forEach { kind ->
            val group = state.rows.filter { it.triggerKind == kind }
            if (group.isNotEmpty()) {
                item(key = "group-${kind.name}") { SectionTitle(text = stringResource(groupLabelRes(kind))) }
                items(group, key = { it.id }) { row ->
                    TaskRow(
                        row = row,
                        onClick = { onAction(TasksListAction.OpenDetail(row.id)) },
                        onLongClick = { onLongPress(row.id) },
                        onToggle = { onAction(TasksListAction.ToggleEnabled(row.id, it)) },
                    )
                }
            }
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

private fun pageLabelRes(page: TasksPage): Int =
    when (page) {
        TasksPage.TASKS -> R.string.tasks_page_tasks
        TasksPage.FLOWS -> R.string.tasks_page_flows
    }

private fun groupLabelRes(tab: TasksTab): Int =
    when (tab) {
        TasksTab.CONDITION -> R.string.tasks_tab_condition
        TasksTab.SCHEDULE -> R.string.tasks_tab_schedule
        TasksTab.MANUAL -> R.string.tasks_tab_manual
    }
