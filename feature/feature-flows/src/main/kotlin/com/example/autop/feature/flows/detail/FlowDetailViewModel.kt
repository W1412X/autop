/**
 * 流程详情 ViewModel：定义、累计统计、最易失败的节点、运行记录；运行与调试入口。
 */
@file:Suppress("CyclomaticComplexMethod") // 意图分发表

package com.example.autop.feature.flows.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.feature.flows.FLOW_ID_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.feature.flows.missingRequired
import com.example.autop.feature.flows.paramsJson
import com.example.autop.flow.FlowBundleService
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowNodeStats
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowRunStore
import com.example.autop.flow.FlowRunSummary
import com.example.autop.flow.FlowStats
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowTree
import com.example.autop.task.RunService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一个节点的失败统计行。 */
public data class NodeFailureUi(
    val nodeId: String,
    val name: String,
    val executions: Long,
    val failurePercent: Int,
)

/** 详情页状态。 */
public data class FlowDetailUiState(
    val loaded: Boolean = false,
    val flow: FlowDefinition? = null,
    val stats: FlowStats? = null,
    val failing: List<NodeFailureUi> = emptyList(),
    val runs: List<FlowRunSummary> = emptyList(),
    /** 运行前的参数表单（非 null = 弹出）。 */
    val runForm: RunFormUi? = null,
    val error: String? = null,
    val pendingDeleteRunId: String? = null,
    val confirmClear: Boolean = false,
)

/** 运行前参数表单。 */
public data class RunFormUi(
    val values: Map<String, String>,
    val debug: Boolean,
)

/** 详情页意图。 */
public sealed interface FlowDetailAction {
    public data object Back : FlowDetailAction

    public data class Run(
        val debug: Boolean,
    ) : FlowDetailAction

    public data class SetParam(
        val name: String,
        val value: String,
    ) : FlowDetailAction

    public data object ConfirmRun : FlowDetailAction

    public data object DismissRun : FlowDetailAction

    public data object Edit : FlowDetailAction

    public data object AiEdit : FlowDetailAction

    public data object OpenState : FlowDetailAction

    public data object OpenVersions : FlowDetailAction

    public data object Export : FlowDetailAction

    public data class OpenRun(
        val runId: String,
    ) : FlowDetailAction

    public data class RequestDeleteRun(
        val runId: String,
    ) : FlowDetailAction

    public data object ConfirmDeleteRun : FlowDetailAction

    public data object DismissDeleteRun : FlowDetailAction

    public data object RequestClear : FlowDetailAction

    public data object ConfirmClear : FlowDetailAction

    public data object DismissClear : FlowDetailAction
}

/** 详情页 ViewModel。 */
@HiltViewModel
public class FlowDetailViewModel
    @Inject
    @Suppress("LongParameterList") // ViewModel 的依赖注入面
    constructor(
        savedStateHandle: SavedStateHandle,
        private val store: FlowStore,
        private val runs: FlowRunStore,
        private val runService: RunService,
        private val bundles: FlowBundleService,
    ) : ViewModel() {
        private val flowId: String = savedStateHandle.get<String>(FLOW_ID_ARG).orEmpty()
        private val _uiState = MutableStateFlow(FlowDetailUiState())
        public val uiState: StateFlow<FlowDetailUiState> = _uiState

        init {
            viewModelScope.launch {
                val flow = store.get(FlowId(flowId))?.definition
                _uiState.update { it.copy(loaded = true, flow = flow, error = if (flow == null) NOT_FOUND else null) }
                runs.observeRuns(flowId).collect { list ->
                    val stats = runs.stats(flowId)
                    val failing = failingNodes(flow, runs.nodeStats(flowId))
                    _uiState.update { it.copy(runs = list, stats = stats, failing = failing) }
                }
            }
        }

        public fun onAction(
            action: FlowDetailAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                FlowDetailAction.Back -> navigator.navigateBack()
                is FlowDetailAction.Run -> beginRun(action.debug)
                is FlowDetailAction.SetParam ->
                    _uiState.update { s ->
                        s.copy(
                            runForm =
                                s.runForm?.let { f ->
                                    f.copy(
                                        values =
                                            f.values + (action.name to action.value),
                                    )
                                },
                        )
                    }
                FlowDetailAction.ConfirmRun -> confirmRun(navigator)
                FlowDetailAction.DismissRun -> _uiState.update { it.copy(runForm = null) }
                FlowDetailAction.Edit -> navigator.openEditor(flowId)
                FlowDetailAction.AiEdit -> navigator.openAuthor(null, flowId)
                FlowDetailAction.OpenState -> navigator.openState(flowId)
                FlowDetailAction.OpenVersions -> navigator.openVersions(flowId)
                FlowDetailAction.Export ->
                    viewModelScope.launch {
                        (
                            bundles.export(
                                listOf(flowId),
                            ) as? Outcome.Ok
                        )?.let { navigator.shareText(it.value) }
                    }
                is FlowDetailAction.OpenRun -> navigator.openRunRecord(action.runId)
                is FlowDetailAction.RequestDeleteRun -> _uiState.update { it.copy(pendingDeleteRunId = action.runId) }
                FlowDetailAction.DismissDeleteRun -> _uiState.update { it.copy(pendingDeleteRunId = null) }
                FlowDetailAction.ConfirmDeleteRun -> deleteRun()
                FlowDetailAction.RequestClear -> _uiState.update { it.copy(confirmClear = true) }
                FlowDetailAction.DismissClear -> _uiState.update { it.copy(confirmClear = false) }
                FlowDetailAction.ConfirmClear -> {
                    _uiState.update { it.copy(confirmClear = false) }
                    viewModelScope.launch { runs.deleteRunsOf(flowId) }
                }
            }
        }

        private fun beginRun(debug: Boolean) {
            val flow = _uiState.value.flow ?: return
            if (flow.params.isEmpty()) {
                _uiState.update { it.copy(runForm = RunFormUi(emptyMap(), debug)) }
                return
            }
            val defaults =
                flow.params
                    .mapNotNull { p ->
                        p.default?.let { d ->
                            p.name to
                                com.example.autop.feature.flows
                                    .textOf(d)
                        }
                    }.toMap()
            _uiState.update { it.copy(runForm = RunFormUi(defaults, debug)) }
        }

        private fun confirmRun(navigator: FlowsNavigator) {
            val state = _uiState.value
            val flow = state.flow ?: return
            val form = state.runForm ?: return
            if (missingRequired(flow.params, form.values)) {
                _uiState.update { it.copy(error = MISSING_PARAM) }
                return
            }
            _uiState.update { it.copy(runForm = null, error = null) }
            viewModelScope.launch {
                val options = FlowRunOptions(debug = form.debug)
                when (val result = runService.runFlow(FlowId(flowId), paramsJson(flow.params, form.values), options)) {
                    is Outcome.Ok -> navigator.openRunRecord(result.value.id.value)
                    is Outcome.Err -> _uiState.update { it.copy(error = result.error.code) }
                }
            }
        }

        private fun deleteRun() {
            val id = _uiState.value.pendingDeleteRunId ?: return
            _uiState.update { it.copy(pendingDeleteRunId = null) }
            viewModelScope.launch { runs.deleteRun(id) }
        }

        private fun failingNodes(
            flow: FlowDefinition?,
            stats: List<FlowNodeStats>,
        ): List<NodeFailureUi> {
            val names = flow?.let { FlowTree.allNodes(it).associate { n -> n.id to (n.name ?: n.id) } }.orEmpty()
            return stats.filter { it.failures > 0 }.take(FAILING_LIMIT).map {
                NodeFailureUi(
                    it.nodeId,
                    names[it.nodeId] ?: it.nodeId,
                    it.executions,
                    (it.failureRate * PERCENT).toInt(),
                )
            }
        }

        public companion object {
            public const val NOT_FOUND: String = "not_found"
            public const val MISSING_PARAM: String = "missing_param"
            private const val FAILING_LIMIT = 5
            private const val PERCENT = 100
        }
    }
