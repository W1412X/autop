/**
 * 任务编辑页 ViewModel（docs/tech/08 §4.3）：草稿生成、表单装配、校验保存与试运行。
 */
package com.example.autop.feature.tasks.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.feature.tasks.NEW_TASK_ID
import com.example.autop.feature.tasks.TASK_ID_ARG
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.task.ConditionResult
import com.example.autop.task.FilterOp
import com.example.autop.task.RunService
import com.example.autop.task.Task
import com.example.autop.task.TaskDraftGenerator
import com.example.autop.task.TaskRepository
import com.example.autop.task.TriggerEvent
import com.example.autop.task.TriggerSimulator
import com.example.autop.task.TriggerSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑页 ViewModel。 */
@HiltViewModel
public class TaskEditViewModel
    @Inject
    @Suppress("LongParameterList") // ViewModel 的依赖注入面，端口逐个注入是职责所在
    constructor(
        savedStateHandle: SavedStateHandle,
        private val taskRepository: TaskRepository,
        private val draftGenerator: TaskDraftGenerator,
        private val simulator: TriggerSimulator,
        private val runService: RunService,
        private val sources: Set<@JvmSuppressWildcards TriggerSource>,
        private val ids: IdGenerator,
        private val clock: Clock,
    ) : ViewModel() {
        private val taskId: String? =
            savedStateHandle.get<String>(TASK_ID_ARG)?.takeIf { it != NEW_TASK_ID }

        private val _uiState = MutableStateFlow(TaskEditUiState(isNew = taskId == null))

        public val uiState: StateFlow<TaskEditUiState> = _uiState

        private var simEvents: List<TriggerEvent> = emptyList()

        init {
            _uiState.update {
                it.copy(sourceOptions = sources.map { s -> SourceOptionUi(s.id, s.fieldNames, s.requiresPermission) })
            }
            if (taskId != null) load(taskId)
        }

        /** 处理意图（保存成功/返回经 [navigator]）。 */
        @Suppress("CyclomaticComplexMethod") // 意图分发表：分支数等于动作种类，是接口面的自然结果
        public fun onAction(
            action: TaskEditAction,
            navigator: TasksNavigator,
        ) {
            when (action) {
                is TaskEditAction.SetDescription -> _uiState.update { it.copy(description = action.value) }
                TaskEditAction.GenerateDraft -> generate()
                is TaskEditAction.SetName -> _uiState.update { it.copy(name = action.value) }
                is TaskEditAction.SetGoal -> _uiState.update { it.copy(goal = action.value) }
                is TaskEditAction.SetNeedsScreen -> _uiState.update { it.copy(needsScreen = action.value) }
                is TaskEditAction.SetTriggerKind -> _uiState.update { it.copy(triggerKind = action.kind) }
                is TaskEditAction.SetScheduleMode ->
                    _uiState.update {
                        it.copy(scheduleMode = action.mode).withUpcomingOnceDate(clock.nowMillis())
                    }
                is TaskEditAction.SetDate -> _uiState.update { it.copy(date = action.date) }
                is TaskEditAction.SetTime ->
                    _uiState.update {
                        it.copy(
                            timeHour = action.hour,
                            timeMinute = action.minute,
                            timeSecond = action.second,
                        )
                    }
                is TaskEditAction.SetCron -> _uiState.update { it.copy(cronExpr = action.value) }
                is TaskEditAction.SetSource -> _uiState.update { it.copy(sourceId = action.sourceId) }
                is TaskEditAction.SetClause ->
                    _uiState.update {
                        it.copy(clauses = it.clauses.mapIndexed { i, c -> if (i == action.index) action.clause else c })
                    }
                TaskEditAction.AddClause ->
                    _uiState.update {
                        it.copy(clauses = it.clauses + ClauseUi(field = "", op = FilterOp.CONTAINS.name, value = ""))
                    }
                is TaskEditAction.RemoveClause ->
                    _uiState.update { it.copy(clauses = it.clauses.filterIndexed { i, _ -> i != action.index }) }
                is TaskEditAction.SetGroupAll -> _uiState.update { it.copy(groupAll = action.value) }
                is TaskEditAction.SetLlmCondition -> _uiState.update { it.copy(llmCondition = action.value) }
                is TaskEditAction.SetCooldown -> _uiState.update { it.copy(cooldownMinutes = action.minutes) }
                is TaskEditAction.SetDailyLimit -> _uiState.update { it.copy(dailyLimit = action.value) }
                is TaskEditAction.SetLockedBehavior -> _uiState.update { it.copy(lockedRemind = action.remind) }
                is TaskEditAction.SetPriority -> _uiState.update { it.copy(priority = action.value) }
                TaskEditAction.OpenSimulate -> openSimulate()
                is TaskEditAction.PickSimEvent -> pickSim(action.index)
                TaskEditAction.RunSimOnce -> runSimOnce()
                TaskEditAction.Save -> save(navigator)
                TaskEditAction.Back -> navigator.navigateBack()
            }
        }

        private fun load(id: String) {
            viewModelScope.launch {
                val task = taskRepository.get(TaskId(id))
                if (task == null) {
                    _uiState.update { it.copy(error = TaskEditError.NOT_FOUND.name) }
                    return@launch
                }
                _uiState.update { state -> state.from(task) }
            }
        }

        private fun generate() {
            val description = _uiState.value.description
            if (description.isBlank()) return
            _uiState.update { it.copy(generating = true) }
            viewModelScope.launch {
                when (val result = draftGenerator.draft(description)) {
                    is Outcome.Ok -> _uiState.update { it.copy(generating = false).from(result.value) }
                    is Outcome.Err ->
                        _uiState.update {
                            it.copy(generating = false, error = result.error.code)
                        }
                }
            }
        }

        private fun openSimulate() {
            val state = _uiState.value
            val firstOption = state.sourceOptions.firstOrNull()
            val sourceId = state.sourceId.ifBlank { firstOption?.id.orEmpty() }
            if (sourceId.isBlank()) return
            viewModelScope.launch {
                simEvents = simulator.recentEvents(sourceId, SIM_EVENT_LIMIT)
                _uiState.update {
                    it.copy(
                        sourceId = sourceId,
                        simEvents = simEvents.map(::describe),
                        simQueried = true,
                        simPicked = null,
                        simResult = null,
                    )
                }
            }
        }

        private fun pickSim(index: Int) {
            val event = simEvents.getOrNull(index) ?: return
            val task = buildTask()
            viewModelScope.launch {
                when (val result = evaluate(task, event)) {
                    is Outcome.Ok ->
                        _uiState.update {
                            it.copy(
                                simPicked = index,
                                simResult =
                                    SimResultUi(
                                        matched = result.value.match,
                                        details =
                                            listOfNotNull(result.value.reason) +
                                                result.value.extracted.map { e -> "${e.key}=${e.value}" },
                                    ),
                            )
                        }
                    is Outcome.Err -> _uiState.update { it.copy(error = result.error.code) }
                }
            }
        }

        private suspend fun evaluate(
            task: Task,
            event: TriggerEvent,
        ): Outcome<ConditionResult> = Outcome.Ok(simulator.evaluate(task, event))

        private fun runSimOnce() {
            val task = buildTask()
            viewModelScope.launch {
                when (val result = runService.runTaskNow(task)) {
                    is Outcome.Ok -> _uiState.update { it.copy(simStarted = true) }
                    is Outcome.Err -> _uiState.update { it.copy(error = result.error.code) }
                }
            }
        }

        private fun save(navigator: TasksNavigator) {
            val state = _uiState.value
            val problem = state.validate(clock.nowMillis())
            if (problem != null) {
                _uiState.update { it.copy(error = problem) }
                return
            }
            viewModelScope.launch {
                when (val result = taskRepository.save(buildTask())) {
                    is Outcome.Ok -> navigator.navigateBack()
                    is Outcome.Err -> _uiState.update { it.copy(error = result.error.code) }
                }
            }
        }

        /** 当前表单 → Task（装配细节在 [buildTask] 扩展）。 */
        private fun buildTask(): Task =
            _uiState.value.buildTask(
                existingId = taskId?.let { TaskId(it) },
                newId = ids.newId(),
                now = clock.nowMillis(),
            )

        private fun describe(event: TriggerEvent): String =
            event.fields.entries
                .joinToString(" ") { "${it.key}=${it.value}" }
                .ifBlank { event.key }

        private companion object {
            const val SIM_EVENT_LIMIT = 5
        }
    }
