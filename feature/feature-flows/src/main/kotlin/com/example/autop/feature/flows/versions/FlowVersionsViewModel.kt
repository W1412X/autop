/**
 * 流程版本历史 ViewModel：列出历史版本、查看定义、回滚到指定版本。
 *
 * 回滚后列表刷新走 FlowStore.observeAll 变更信号（保存/回滚都会写流程定义），
 * 不在回滚回调里手动拼列表状态。
 */
package com.example.autop.feature.flows.versions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.feature.flows.FLOW_ID_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowVersionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一条版本记录的展示数据。 */
public data class FlowVersionUi(
    val version: Int,
    val createdAt: Long,
    val reason: String?,
    /** 定义与当前流程一致时为 true。 */
    val isCurrent: Boolean,
    val definition: FlowDefinition,
)

/** 版本历史页状态。 */
public data class FlowVersionsUiState(
    val loaded: Boolean = false,
    val versions: List<FlowVersionUi> = emptyList(),
    /** 当前正在预览的版本（非 null = 弹出预览对话框）。 */
    val preview: FlowVersionUi? = null,
    /** 待确认回滚的版本（非 null = 弹出确认对话框）。 */
    val pendingRestore: FlowVersionUi? = null,
    /** 回滚成功提示的目标版本号。 */
    val restoredVersion: Int? = null,
    val error: String? = null,
)

/** 版本历史页意图。 */
public sealed interface FlowVersionsAction {
    public data object Back : FlowVersionsAction

    public data class View(
        val version: Int,
    ) : FlowVersionsAction

    public data object DismissPreview : FlowVersionsAction

    public data class RequestRestore(
        val version: Int,
    ) : FlowVersionsAction

    public data object ConfirmRestore : FlowVersionsAction

    public data object DismissRestore : FlowVersionsAction

    public data object DismissRestored : FlowVersionsAction
}

/** 版本历史页 ViewModel。 */
@OptIn(FlowPreview::class) // debounce 为 FlowPreview API，用于变更信号去抖
@HiltViewModel
public class FlowVersionsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val store: FlowStore,
        private val versionStore: FlowVersionStore,
    ) : ViewModel() {
        private val flowId: String = savedStateHandle.get<String>(FLOW_ID_ARG).orEmpty()
        private val _uiState = MutableStateFlow(FlowVersionsUiState())
        public val uiState: StateFlow<FlowVersionsUiState> = _uiState

        init {
            viewModelScope.launch { reload() }
            viewModelScope.launch {
                // 订阅流程变更信号（development-practice-specification）：保存/回滚都写流程定义
                // 并追加版本记录，updatedAt 变化触发整体重载，不手动拼列表状态。
                store
                    .observeAll()
                    .map { list -> list.firstOrNull { it.id.value == flowId }?.updatedAt }
                    .distinctUntilChanged()
                    .debounce(REFRESH_DEBOUNCE_MS)
                    .collect { reload() }
            }
        }

        /** 重新加载版本列表（页面回前台时也调用）。 */
        public fun refresh() {
            viewModelScope.launch { reload() }
        }

        private suspend fun reload() {
            val current = store.get(FlowId(flowId))?.definition
            val ui =
                versionStore.list(flowId).map { v ->
                    FlowVersionUi(
                        version = v.version,
                        createdAt = v.createdAt,
                        reason = v.reason,
                        isCurrent = v.definition == current,
                        definition = v.definition,
                    )
                }
            _uiState.update { it.copy(loaded = true, versions = ui) }
        }

        public fun onAction(
            action: FlowVersionsAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                FlowVersionsAction.Back -> navigator.navigateBack()
                is FlowVersionsAction.View ->
                    _uiState.update {
                        it.copy(preview = it.versions.firstOrNull { v -> v.version == action.version })
                    }
                FlowVersionsAction.DismissPreview -> _uiState.update { it.copy(preview = null) }
                is FlowVersionsAction.RequestRestore ->
                    _uiState.update {
                        it.copy(pendingRestore = it.versions.firstOrNull { v -> v.version == action.version })
                    }
                FlowVersionsAction.ConfirmRestore -> confirmRestore()
                FlowVersionsAction.DismissRestore -> _uiState.update { it.copy(pendingRestore = null) }
                FlowVersionsAction.DismissRestored -> _uiState.update { it.copy(restoredVersion = null) }
            }
        }

        private fun confirmRestore() {
            val target = _uiState.value.pendingRestore ?: return
            _uiState.update { it.copy(pendingRestore = null) }
            viewModelScope.launch {
                // 回滚后列表刷新走变更信号（restore 内部写流程定义会触发 observeAll），
                // 此处只记录成功提示，不手动重载列表。
                when (val result = versionStore.restore(flowId, target.version)) {
                    is Outcome.Ok -> _uiState.update { it.copy(restoredVersion = target.version, error = null) }
                    is Outcome.Err -> _uiState.update { it.copy(error = result.error.message) }
                }
            }
        }

        private companion object {
            /** 变更信号去抖（毫秒）。 */
            const val REFRESH_DEBOUNCE_MS: Long = 300
        }
    }
