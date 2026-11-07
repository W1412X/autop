/**
 * Run 详情页 ViewModel（docs/tech/08 §4.3）：只读 Run 记录 + 过程时间线 + 分叉继续；
 * 时间线订阅 thread 事件即时刷新（N184：运行中 append 不改 Run 记录，只等记录变化会漏步骤）。
 */
package com.example.autop.feature.tasks.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.ThreadId
import com.example.autop.feature.tasks.RUN_ID_ARG
import com.example.autop.feature.tasks.RUN_LIMIT
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.feature.tasks.formatTime
import com.example.autop.llm.Block
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import com.example.autop.task.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Run 详情页 ViewModel。 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest 为实验 API
@HiltViewModel
public class RunDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val taskRepository: TaskRepository,
        private val runService: RunService,
        private val threadStore: ThreadStore,
        private val logger: Logger,
    ) : ViewModel() {
        private val runId: String = savedStateHandle.get<String>(RUN_ID_ARG).orEmpty()

        private val record = MutableStateFlow<RunRecord?>(null)
        private val timeline = MutableStateFlow<List<TimelineUi>>(emptyList())
        private val loaded = MutableStateFlow(false)
        private val feedback = MutableStateFlow<RunDetailFeedback?>(null)

        public val uiState: StateFlow<RunDetailUiState> =
            combine(record, timeline, loaded, feedback, ::stateOf)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    RunDetailUiState(runId = runId),
                )

        init {
            viewModelScope.launch {
                taskRepository
                    .observeRuns(taskId = null, limit = RUN_LIMIT)
                    .map { runs -> runs.firstOrNull { it.id.value == runId } }
                    .distinctUntilChanged()
                    .flatMapLatest { current ->
                        // thread 事件作时间线刷新信号（N184）：记录变化或任一事件落盘都重读时间线。
                        val ticks = current?.let { threadStore.observe(it.threadId).map {} } ?: emptyFlow()
                        ticks.onStart { emit(Unit) }.map { current }
                    }.collectLatest { current ->
                        record.value = current
                        timeline.value = current?.let { loadTimeline(it) }.orEmpty()
                        loaded.value = true
                    }
            }
        }

        /** 处理意图（导航动作经 [navigator] 回调）。 */
        public fun onAction(
            action: RunDetailAction,
            navigator: TasksNavigator,
        ) {
            when (action) {
                RunDetailAction.Continue -> continueFrom()
                RunDetailAction.ResumeInterrupted -> resumeInterrupted()
                RunDetailAction.Back -> navigator.navigateBack()
            }
        }

        private fun stateOf(
            run: RunRecord?,
            steps: List<TimelineUi>,
            isLoaded: Boolean,
            current: RunDetailFeedback?,
        ): RunDetailUiState =
            RunDetailUiState(
                runId = runId,
                notFound = isLoaded && run == null,
                statusName = run?.status?.name.orEmpty(),
                originName = run?.origin?.name.orEmpty(),
                summary = run?.summary.orEmpty(),
                triggerContext = run?.triggerContext?.map { "${it.key}=${it.value}" }.orEmpty(),
                startedText = run?.let { formatTime(it.startedAt ?: it.createdAt) }.orEmpty(),
                endedText = run?.endedAt?.let(::formatTime).orEmpty(),
                timeline = steps,
                feedback = current,
                canResume = run?.status == RunStatus.INTERRUPTED,
            )

        private fun continueFrom() {
            val run = record.value ?: return
            viewModelScope.launch {
                val atSeq =
                    runCatching { threadStore.forkablePoints(run.threadId) }
                        .onFailure { logger.w(TAG, "forkable_points_failed", "runId" to runId, error = it) }
                        .getOrDefault(emptyList())
                        .lastOrNull()
                        ?: FIRST_SEQ
                feedback.value = forkFrom(run.threadId, atSeq)
            }
        }

        /** 中断恢复（docs/tech/04 §3.11）：同线程新起 Run 接着做。 */
        private fun resumeInterrupted() {
            val run = record.value ?: return
            viewModelScope.launch {
                feedback.value =
                    when (val result = runService.resumeInterrupted(run.id)) {
                        is Outcome.Ok ->
                            RunDetailFeedback(
                                kind = RunDetailFeedbackKind.RESUME,
                                continued = true,
                            )
                        is Outcome.Err ->
                            RunDetailFeedback(
                                kind = RunDetailFeedbackKind.RESUME,
                                continued = false,
                                errorCode = result.error.code,
                            )
                    }
            }
        }

        private suspend fun forkFrom(
            threadId: ThreadId,
            atSeq: Long,
        ): RunDetailFeedback =
            when (val result = runService.forkAndRun(threadId, atSeq, text = null)) {
                is Outcome.Ok -> RunDetailFeedback(continued = true)
                is Outcome.Err -> RunDetailFeedback(continued = false, errorCode = result.error.code)
            }

        private suspend fun loadTimeline(run: RunRecord): List<TimelineUi> =
            runCatching {
                threadStore
                    .events(run.threadId, fromSeq = FIRST_SEQ)
                    .filter { it.runId == run.id }
                    .takeLast(TIMELINE_LIMIT)
                    .map { timelineOf(it.payload) }
            }.onFailure { logger.w(TAG, "timeline_load_failed", "runId" to runId, error = it) }
                .getOrDefault(emptyList())

        private fun timelineOf(payload: EventPayload): TimelineUi =
            when (payload) {
                is UserMessage -> TimelineUi(TimelineKind.USER, payload.blocks.textOf())
                is AssistantMessage -> TimelineUi(TimelineKind.ASSISTANT, payload.blocks.textOf())
                is ToolResultEvent -> TimelineUi(TimelineKind.TOOL, payload.toolId)
                is RunStarted -> TimelineUi(TimelineKind.STARTED, "")
                is RunFinished -> TimelineUi(TimelineKind.FINISHED, payload.summary.orEmpty())
                is RunPaused -> TimelineUi(TimelineKind.PAUSED, payload.detail.orEmpty())
                is RunResumed -> TimelineUi(TimelineKind.RESUMED, payload.userNote.orEmpty())
                is RunNoteAdded -> TimelineUi(TimelineKind.NOTE, payload.text)
                is PlanUpdated ->
                    TimelineUi(
                        TimelineKind.PLAN,
                        (listOfNotNull(payload.explanation) + payload.items.map { it.step }).joinToString("\n"),
                    )
                else -> TimelineUi(TimelineKind.OTHER, "")
            }

        private fun List<Block>.textOf(): String =
            filterIsInstance<Block.Text>()
                .joinToString(" ") { it.text }
                .take(TEXT_LIMIT)

        private companion object {
            const val TAG = "RunDetailViewModel"
            const val STOP_TIMEOUT_MS = 5_000L
            const val TIMELINE_LIMIT = 50
            const val TEXT_LIMIT = 120
            const val FIRST_SEQ = 0L
        }
    }
