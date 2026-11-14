/**
 * Run 详情页 ViewModel（docs/tech/08 §4.3）：只读 Run 记录 + 过程时间线 + 分叉继续；
 * 时间线订阅 thread 事件即时刷新（N184：运行中 append 不改 Run 记录，只等记录变化会漏步骤）。
 */
package com.example.autop.feature.tasks.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.thread.ui.TimelineAction
import com.example.autop.core.thread.ui.TimelineEntry
import com.example.autop.core.thread.ui.mergeTimeline
import com.example.autop.feature.tasks.RUN_ID_ARG
import com.example.autop.feature.tasks.RUN_LIMIT
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.feature.tasks.formatTime
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import com.example.autop.task.TaskErrorCodes
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
import kotlinx.coroutines.flow.update
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
        private val configProvider: ConfigProvider,
    ) : ViewModel() {
        private val runId: String = savedStateHandle.get<String>(RUN_ID_ARG).orEmpty()

        private val record = MutableStateFlow<RunRecord?>(null)
        private val timeline = MutableStateFlow<List<TimelineEntry>>(emptyList())
        private val loaded = MutableStateFlow(false)
        private val feedback = MutableStateFlow<RunDetailFeedback?>(null)
        private val overlay = MutableStateFlow(RunDetailOverlay())

        public val uiState: StateFlow<RunDetailUiState> =
            combine(record, timeline, loaded, feedback, overlay, ::stateOf)
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
                        val ticks =
                            current?.let { threadStore.observeFrom(it.threadId, afterSeq = 0).map {} } ?: emptyFlow()
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
                is RunDetailAction.Timeline -> onTimeline(action.action, navigator)
                is RunDetailAction.Fork -> forkAt(action.seq)
                is RunDetailAction.ShowRaw -> showRaw(action.seq)
                RunDetailAction.DismissEntryMenu -> overlay.update { it.copy(entryMenu = null) }
                RunDetailAction.DismissRaw -> overlay.update { it.copy(rawText = null) }
            }
        }

        private fun stateOf(
            run: RunRecord?,
            steps: List<TimelineEntry>,
            isLoaded: Boolean,
            current: RunDetailFeedback?,
            ov: RunDetailOverlay,
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
                entryMenu = ov.entryMenu,
                rawText = ov.rawText,
                feedback = current,
                canResume = run?.status == RunStatus.INTERRUPTED,
            )

        /** 过程条目动作：快捷入口与分叉标记经导航回调；长按打开菜单；暂停/停止直接作用于 Run。 */
        private fun onTimeline(
            action: TimelineAction,
            navigator: TasksNavigator,
        ) {
            when (action) {
                is TimelineAction.OpenLink -> navigator.openStepLink(action.link)
                is TimelineAction.OpenSourceThread -> navigator.openThread(action.threadId)
                is TimelineAction.ShowEntryMenu -> {
                    val forkable = timeline.value.firstOrNull { it.seq == action.seq }?.forkable == true
                    overlay.update { it.copy(entryMenu = RunEntryMenu(action.seq, forkable)) }
                }
                TimelineAction.Resume -> record.value?.let { runService.resume(it.id, null) }
                TimelineAction.Stop -> record.value?.let { runService.cancel(it.id) }
                // 复制需要剪贴板，由页面处理。
                TimelineAction.CopyReply -> Unit
            }
        }

        private fun forkAt(seq: Long) {
            val run = record.value ?: return
            overlay.update { it.copy(entryMenu = null) }
            viewModelScope.launch { feedback.value = forkFrom(run.threadId, seq) }
        }

        private fun showRaw(seq: Long) {
            val run = record.value ?: return
            overlay.update { it.copy(entryMenu = null) }
            viewModelScope.launch {
                val text =
                    runCatching {
                        threadStore
                            .events(run.threadId, fromSeq = seq, toSeq = seq)
                            .filter { it.seq == seq }
                            .joinToString("\n\n") { "seq=${it.seq}\n${it.payload}" }
                    }.onFailure { logger.w(TAG, "raw_load_failed", "runId" to runId, error = it) }
                        .getOrDefault("")
                overlay.update { it.copy(rawText = text.ifBlank { null }) }
            }
        }

        private fun continueFrom() {
            val run = record.value ?: return
            viewModelScope.launch {
                val atSeq =
                    runCatching { threadStore.forkablePoints(run.threadId) }
                        .onFailure { logger.w(TAG, "forkable_points_failed", "runId" to runId, error = it) }
                        .getOrDefault(emptyList())
                        .lastOrNull()
                feedback.value =
                    if (atSeq == null) {
                        RunDetailFeedback(continued = false, errorCode = TaskErrorCodes.NO_FORK_POINT)
                    } else {
                        forkFrom(run.threadId, atSeq)
                    }
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

        private suspend fun loadTimeline(run: RunRecord): List<TimelineEntry> =
            runCatching {
                // 一次运行对应一个线程（ADR-0031），线程事件全部属于这次运行；与对话页使用同一套归并。
                mergeTimeline(
                    history = threadStore.events(run.threadId, fromSeq = FIRST_SEQ),
                    live = emptyList(),
                    stepGroupThreshold = configProvider.current()[UiConfig.CHAT].stepGroupThreshold,
                )
            }.onFailure { logger.w(TAG, "timeline_load_failed", "runId" to runId, error = it) }
                .getOrDefault(emptyList())

        /** 过程页弹层状态：长按菜单与原始数据文本。 */
        private data class RunDetailOverlay(
            val entryMenu: RunEntryMenu? = null,
            val rawText: String? = null,
        )

        private companion object {
            const val TAG = "RunDetailViewModel"
            const val STOP_TIMEOUT_MS = 5_000L
            const val FIRST_SEQ = 0L
        }
    }
