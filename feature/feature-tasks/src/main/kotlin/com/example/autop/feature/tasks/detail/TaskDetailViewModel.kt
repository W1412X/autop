/**
 * 任务详情页 ViewModel（docs/tech/08 §4.3）：任务摘要 + 运行记录 + 立即运行。
 */
package com.example.autop.feature.tasks.detail

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.feature.tasks.RUN_LIMIT
import com.example.autop.feature.tasks.TASK_ID_ARG
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.feature.tasks.flowsEnabled
import com.example.autop.feature.tasks.formatTime
import com.example.autop.feature.tasks.scheduleLabelRes
import com.example.autop.feature.tasks.scheduleTimeText
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
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

/** 详情页 ViewModel。 */
@HiltViewModel
public class TaskDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val taskRepository: TaskRepository,
        private val runService: RunService,
        private val configProvider: ConfigProvider,
    ) : ViewModel() {
        private val taskId: String = savedStateHandle.get<String>(TASK_ID_ARG).orEmpty()

        private val feedback = MutableStateFlow<TaskDetailFeedback?>(null)

        /** 流程 Beta 开关（ADR-0030）：随配置变化更新，关闭时流程任务显示「失效」且不能立即运行。 */
        private val flowsEnabled = configProvider.observe().map { it.flowsEnabled() }

        public val uiState: StateFlow<TaskDetailUiState> =
            combine(
                taskRepository.observeTasks(),
                taskRepository.observeRuns(taskOrNull(), RUN_LIMIT),
                feedback,
                flowsEnabled,
            ) { tasks, runs, current, flowsOn ->
                val task = tasks.firstOrNull { it.id.value == taskId }
                TaskDetailUiState(
                    taskId = taskId,
                    notFound = task == null,
                    name = task?.name.orEmpty(),
                    goal = task?.goal.orEmpty(),
                    triggerText = task?.let(::triggerText).orEmpty(),
                    triggerLabelRes = task?.let(::triggerLabelRes) ?: 0,
                    needsScreen = task?.needsScreen ?: true,
                    lockedRemind = task?.lockedBehavior == com.example.autop.task.LockedBehavior.REMIND,
                    priority = task?.priority ?: 0,
                    enabled = task?.enabled ?: true,
                    flowDisabled = task?.let { isFlowDisabled(it, flowsOn) } ?: false,
                    runs = runs.map(::rowOf),
                    feedback = current,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                TaskDetailUiState(taskId = taskId),
            )

        /** 处理意图（导航动作经 [navigator] 回调）。 */
        public fun onAction(
            action: TaskDetailAction,
            navigator: TasksNavigator,
        ) {
            when (action) {
                TaskDetailAction.RunNow -> runNow()
                TaskDetailAction.OpenEdit -> navigator.openEdit(taskId)
                is TaskDetailAction.OpenRun -> navigator.openRun(action.runId)
                TaskDetailAction.Back -> navigator.navigateBack()
            }
        }

        private fun runNow() {
            viewModelScope.launch {
                val task = taskRepository.get(TaskId(taskId))
                if (task == null) {
                    feedback.value = TaskDetailFeedback(started = false, errorCode = NOT_FOUND)
                    return@launch
                }
                // 流程功能关闭时流程任务失效：不调用 runService，也不产生运行记录（ADR-0030）。
                if (isFlowDisabled(task, configProvider.current().flowsEnabled())) return@launch
                feedback.value = startNow(task)
            }
        }

        /** 流程任务且流程功能关闭：失效（ADR-0030）。 */
        private fun isFlowDisabled(
            task: Task,
            flowsOn: Boolean,
        ): Boolean = task.executor == TaskExecutor.FLOW && !flowsOn

        private suspend fun startNow(task: Task): TaskDetailFeedback =
            when (val result = runService.runTaskNow(task)) {
                is Outcome.Ok -> TaskDetailFeedback(started = true)
                is Outcome.Err -> TaskDetailFeedback(started = false, errorCode = result.error.code)
            }

        private fun taskOrNull(): TaskId? = taskId.takeIf { it.isNotBlank() }?.let { TaskId(it) }

        private fun triggerText(task: Task): String =
            when (val trigger = task.trigger) {
                Trigger.Manual -> ""
                is Trigger.Schedule -> scheduleTimeText(trigger.spec)
                is Trigger.Condition -> trigger.sourceId
            }

        /** 定时模式文案（单次/每天/工作日）；非定时或自定义 cron 给 0。 */
        @StringRes
        private fun triggerLabelRes(task: Task): Int {
            val spec = (task.trigger as? Trigger.Schedule)?.spec ?: return 0
            return scheduleLabelRes(spec)
        }

        private fun rowOf(run: RunRecord): RunRowUi =
            RunRowUi(
                runId = run.id.value,
                statusName = run.status.name,
                originName = run.origin.name,
                summary = run.summary.orEmpty(),
                timeText = formatTime(run.startedAt ?: run.createdAt),
            )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val NOT_FOUND = "task.not_found"
        }
    }
