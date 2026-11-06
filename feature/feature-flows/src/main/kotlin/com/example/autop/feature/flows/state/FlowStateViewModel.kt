/**
 * 流程状态管理（高级）：查看、修改、删除某个流程及其绑定任务的跨运行状态键值。
 */
package com.example.autop.feature.flows.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.feature.flows.FLOW_ID_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.FlowStateStore
import com.example.autop.task.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** 一个作用域下的状态。 */
public data class StateScopeUi(
    val scope: String,
    /** 任务作用域显示任务名；流程作用域为 null。 */
    val taskName: String?,
    val entries: List<Pair<String, String>>,
)

/** 正在编辑的键值。 */
public data class StateEdit(
    val scope: String,
    val key: String,
    val value: String,
    val isNew: Boolean,
    val error: Boolean = false,
)

/** 状态页状态。 */
public data class FlowStateUiState(
    val scopes: List<StateScopeUi> = emptyList(),
    val edit: StateEdit? = null,
    val pendingClear: String? = null,
)

/** 状态页意图。 */
public sealed interface FlowStateAction {
    public data object Back : FlowStateAction

    public data class Edit(
        val scope: String,
        val key: String,
    ) : FlowStateAction

    public data class Add(
        val scope: String,
    ) : FlowStateAction

    public data class Change(
        val edit: StateEdit,
    ) : FlowStateAction

    public data object Save : FlowStateAction

    public data object Dismiss : FlowStateAction

    public data class Delete(
        val scope: String,
        val key: String,
    ) : FlowStateAction

    public data class RequestClear(
        val scope: String,
    ) : FlowStateAction

    public data object ConfirmClear : FlowStateAction

    public data object DismissClear : FlowStateAction
}

/** 状态页 ViewModel。 */
@HiltViewModel
public class FlowStateViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val states: FlowStateStore,
        private val tasks: TaskRepository,
    ) : ViewModel() {
        private val flowId: String = savedStateHandle.get<String>(FLOW_ID_ARG).orEmpty()
        private val _uiState = MutableStateFlow(FlowStateUiState())
        public val uiState: StateFlow<FlowStateUiState> = _uiState

        init {
            refresh()
        }

        private fun refresh() {
            viewModelScope.launch {
                val bound = tasks.observeTasks().first().filter { it.flow?.flowId?.value == flowId }
                val scopes =
                    listOf(
                        StateScopeUi(FlowStateStore.flowScope(flowId), null, load(FlowStateStore.flowScope(flowId))),
                    ) +
                        bound.map { t ->
                            val scope = FlowStateStore.taskScope(t.id.value)
                            StateScopeUi(scope, t.name, load(scope))
                        }
                _uiState.update { it.copy(scopes = scopes) }
            }
        }

        private suspend fun load(scope: String): List<Pair<String, String>> =
            states
                .list(scope)
                .entries
                .map { (k, v) ->
                    k to ((v as? JsonPrimitive)?.content ?: v.toString())
                }.sortedBy { it.first }

        public fun onAction(
            action: FlowStateAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                FlowStateAction.Back -> navigator.navigateBack()
                is FlowStateAction.Edit -> {
                    val value =
                        _uiState.value.scopes.firstOrNull { it.scope == action.scope }?.entries?.firstOrNull {
                            it.first ==
                                action.key
                        }
                    _uiState.update {
                        it.copy(
                            edit = StateEdit(action.scope, action.key, value?.second.orEmpty(), isNew = false),
                        )
                    }
                }
                is FlowStateAction.Add ->
                    _uiState.update {
                        it.copy(
                            edit = StateEdit(action.scope, "", "", isNew = true),
                        )
                    }
                is FlowStateAction.Change -> _uiState.update { it.copy(edit = action.edit.copy(error = false)) }
                FlowStateAction.Save -> save()
                FlowStateAction.Dismiss -> _uiState.update { it.copy(edit = null) }
                is FlowStateAction.Delete ->
                    viewModelScope.launch {
                        states.delete(action.scope, action.key)
                        refresh()
                    }
                is FlowStateAction.RequestClear -> _uiState.update { it.copy(pendingClear = action.scope) }
                FlowStateAction.DismissClear -> _uiState.update { it.copy(pendingClear = null) }
                FlowStateAction.ConfirmClear -> {
                    val scope = _uiState.value.pendingClear ?: return
                    _uiState.update { it.copy(pendingClear = null) }
                    viewModelScope.launch {
                        states.clearScope(scope)
                        refresh()
                    }
                }
            }
        }

        private fun save() {
            val edit = _uiState.value.edit ?: return
            if (edit.key.isBlank()) {
                _uiState.update { it.copy(edit = edit.copy(error = true)) }
                return
            }
            val value = runCatching { Json.parseToJsonElement(edit.value) }.getOrDefault(JsonPrimitive(edit.value))
            viewModelScope.launch {
                states.set(edit.scope, edit.key.trim(), value, null)
                _uiState.update { it.copy(edit = null) }
                refresh()
            }
        }
    }
