/**
 * 执行记录 ViewModel：运行摘要 + 节点明细（实时更新）；运行中的暂停/继续/停止与调试控制。
 */
package com.example.autop.feature.flows.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.RunId
import com.example.autop.feature.flows.FLOW_RUN_ID_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.FlowNodeRecord
import com.example.autop.flow.FlowProgress
import com.example.autop.flow.FlowRunStatus
import com.example.autop.flow.FlowRunStore
import com.example.autop.flow.FlowRunSummary
import com.example.autop.flow.PauseResolution
import com.example.autop.task.RunService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 记录页状态。 */
public data class FlowRecordUiState(
    val loaded: Boolean = false,
    val run: FlowRunSummary? = null,
    val nodes: List<FlowNodeRecord> = emptyList(),
    val progress: FlowProgress? = null,
    val expanded: Set<Long> = emptySet(),
)

/** 记录页意图。 */
public sealed interface FlowRecordAction {
    public data object Back : FlowRecordAction

    public data class Toggle(
        val rowId: Long,
    ) : FlowRecordAction

    public data object Pause : FlowRecordAction

    public data object Resume : FlowRecordAction

    public data object Stop : FlowRecordAction

    public data object Step : FlowRecordAction

    public data object Continue : FlowRecordAction

    public data class Resolve(
        val resolution: PauseResolution,
    ) : FlowRecordAction

    public data object OpenFlow : FlowRecordAction
}

/** 记录页 ViewModel。 */
@HiltViewModel
public class FlowRecordViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val runs: FlowRunStore,
        private val runService: RunService,
    ) : ViewModel() {
        private val runId: String = savedStateHandle.get<String>(FLOW_RUN_ID_ARG).orEmpty()
        private val _uiState = MutableStateFlow(FlowRecordUiState())
        public val uiState: StateFlow<FlowRecordUiState> = _uiState
        private var progressJob: Job? = null

        init {
            viewModelScope.launch {
                runs.observeRun(runId).collect { summary ->
                    _uiState.update { it.copy(loaded = true, run = summary) }
                    if (summary?.status == FlowRunStatus.RUNNING ||
                        summary?.status == FlowRunStatus.PAUSED
                    ) {
                        attachProgress()
                    }
                }
            }
            viewModelScope.launch {
                runs.observeNodes(runId).collect { list -> _uiState.update { it.copy(nodes = list) } }
            }
        }

        private fun attachProgress() {
            if (progressJob?.isActive == true) return
            val flow = runService.flowProgress(RunId(runId)) ?: return
            progressJob = viewModelScope.launch { flow.collect { p -> _uiState.update { it.copy(progress = p) } } }
        }

        public fun onAction(
            action: FlowRecordAction,
            navigator: FlowsNavigator,
        ) {
            val id = RunId(runId)
            when (action) {
                FlowRecordAction.Back -> navigator.navigateBack()
                is FlowRecordAction.Toggle ->
                    _uiState.update { s ->
                        s.copy(
                            expanded =
                                if (action.rowId in
                                    s.expanded
                                ) {
                                    s.expanded - action.rowId
                                } else {
                                    s.expanded + action.rowId
                                },
                        )
                    }
                FlowRecordAction.Pause -> runService.pause(id)
                FlowRecordAction.Resume -> runService.resume(id, null)
                FlowRecordAction.Stop -> runService.cancel(id)
                FlowRecordAction.Step -> runService.flowDebug(id)?.step()
                FlowRecordAction.Continue -> runService.flowDebug(id)?.resumeRun()
                is FlowRecordAction.Resolve -> runService.flowDebug(id)?.resolvePause(action.resolution)
                FlowRecordAction.OpenFlow -> _uiState.value.run?.let { navigator.openFlow(it.flowId) }
            }
        }
    }
