/**
 * 任务列表 ViewModel（docs/tech/08 §4.3）：任务流 + 等待确认的 Run。
 */
package com.example.autop.feature.tasks.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.TaskId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.feature.tasks.NEW_TASK_ID
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.feature.tasks.flowsEnabled
import com.example.autop.feature.tasks.scheduleLabelRes
import com.example.autop.feature.tasks.scheduleTimeText
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.TaskExecutor
import com.example.autop.task.TaskRepository
import com.example.autop.task.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 列表页 ViewModel。 */
@HiltViewModel
public class TasksListViewModel
    @Inject
    constructor(
        private val taskRepository: TaskRepository,
        private val runService: RunService,
        private val configProvider: ConfigProvider,
    ) : ViewModel() {
        private val page = MutableStateFlow(TasksPage.TASKS)
        private val pendingDeleteId = MutableStateFlow<String?>(null)

        /** 流程 Beta 开关（ADR-0030）：由配置流驱动，关闭时流程页签与新建流程入口不可用。 */
        private val flowsEnabled: StateFlow<Boolean> =
            configProvider
                .observe()
                .map { it.flowsEnabled() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, configProvider.current().flowsEnabled())

        public val uiState: StateFlow<TasksListUiState> =
            combine(
                taskRepository.observeTasks(),
                taskRepository.observeRuns(taskId = null, limit = RUN_LIMIT),
                page,
                pendingDeleteId,
                flowsEnabled,
            ) { tasks, runs, currentPage, deletingId, flowsOn ->
                TasksListUiState(
                    flowsEnabled = flowsOn,
                    // 流程关闭时不停留在「流程」页签。
                    page = if (flowsOn) currentPage else TasksPage.TASKS,
                    rows = tasks.map { rowOf(it, runs, flowsOn) }.sortedBy { it.triggerKind.ordinal },
                    waiting = runs.filter { it.status == RunStatus.WAITING_USER }.map(::waitingOf),
                    pendingDeleteId = deletingId,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TasksListUiState())

        /** 处理意图（导航动作经 [navigator] 回调）。 */
        public fun onAction(
            action: TasksListAction,
            navigator: TasksNavigator,
        ) {
            when (action) {
                is TasksListAction.SetPage ->
                    if (action.page == TasksPage.TASKS || flowsEnabled.value) page.value = action.page
                TasksListAction.CreateFlow -> if (flowsEnabled.value) navigator.openFlowCreate()
                is TasksListAction.OpenDetail -> navigator.openDetail(action.taskId)
                is TasksListAction.OpenEdit -> navigator.openEdit(action.taskId)
                TasksListAction.CreateTask -> navigator.openEdit(NEW_TASK_ID)
                TasksListAction.CreateTaskAi -> navigator.openTaskAuthor()
                is TasksListAction.ToggleEnabled ->
                    launch {
                        taskRepository.setEnabled(
                            TaskId(action.taskId),
                            action.enabled,
                        )
                    }
                is TasksListAction.RequestDelete -> pendingDeleteId.value = action.taskId
                TasksListAction.ConfirmDelete -> confirmDelete()
                TasksListAction.DismissDelete -> pendingDeleteId.value = null
                is TasksListAction.ExecuteWaiting ->
                    launch {
                        runService.requeueWaiting(
                            com.example.autop.core.common
                                .RunId(action.runId),
                        )
                    }
                is TasksListAction.SnoozeWaiting ->
                    launch {
                        runService.snoozeWaiting(
                            com.example.autop.core.common
                                .RunId(action.runId),
                            SNOOZE_MINUTES,
                        )
                    }
                is TasksListAction.SkipWaiting ->
                    launch {
                        runService.skipWaiting(
                            com.example.autop.core.common
                                .RunId(action.runId),
                        )
                    }
            }
        }

        private fun confirmDelete() {
            val id = pendingDeleteId.value ?: return
            pendingDeleteId.value = null
            launch { taskRepository.delete(TaskId(id)) }
        }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch { block() }
        }

        private fun rowOf(
            task: Task,
            runs: List<RunRecord>,
            flowsOn: Boolean,
        ): TaskRowUi {
            val (kind, detail) = triggerOf(task)
            val spec = (task.trigger as? Trigger.Schedule)?.spec
            return TaskRowUi(
                id = task.id.value,
                name = task.name,
                triggerKind = kind,
                triggerDetail = detail,
                scheduleLabelRes = spec?.let { scheduleLabelRes(it) } ?: 0,
                cooldownMinutes = (task.trigger as? Trigger.Condition)?.cooldownMs?.div(MINUTE_MS)?.toInt() ?: 0,
                enabled = task.enabled,
                lastRunStatus = runs.firstOrNull { it.taskId == task.id }?.status?.name,
                flowDisabled = task.executor == TaskExecutor.FLOW && !flowsOn,
            )
        }

        private fun triggerOf(task: Task): Pair<TasksTab, String> =
            when (val trigger = task.trigger) {
                Trigger.Manual -> TasksTab.MANUAL to ""
                is Trigger.Schedule -> TasksTab.SCHEDULE to scheduleTimeText(trigger.spec)
                is Trigger.Condition -> TasksTab.CONDITION to trigger.sourceId
            }

        private fun waitingOf(run: RunRecord): WaitingRunUi =
            WaitingRunUi(
                runId = run.id.value,
                taskName = run.summary.orEmpty().ifEmpty { run.taskId?.value.orEmpty() },
                reason =
                    run.triggerContext.values
                        .firstOrNull()
                        .orEmpty(),
            )

        private companion object {
            const val RUN_LIMIT = 50
            const val SNOOZE_MINUTES = 10
            const val MINUTE_MS = 60_000L
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
