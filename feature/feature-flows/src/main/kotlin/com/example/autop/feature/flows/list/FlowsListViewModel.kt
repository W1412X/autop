/**
 * 流程列表 ViewModel：流程库 + 草稿；长按菜单的复制、导出、删除。
 */
package com.example.autop.feature.flows.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.DraftInfo
import com.example.autop.flow.FlowAuthor
import com.example.autop.flow.FlowBundleService
import com.example.autop.flow.FlowListItem
import com.example.autop.flow.FlowStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一行流程。 */
public data class FlowRowUi(
    val id: String,
    val name: String,
    val description: String,
    val nodeCount: Int,
    val needsScreen: Boolean,
    val totalRuns: Long,
    /** 成功率 0–100；没有运行记录为 null。 */
    val successPercent: Int?,
    val lastRunStatus: String?,
)

/** 列表页状态。 */
public data class FlowsListUiState(
    val rows: List<FlowRowUi> = emptyList(),
    val drafts: List<DraftInfo> = emptyList(),
    val pendingDeleteId: String? = null,
)

/** 列表页意图。 */
public sealed interface FlowsListAction {
    public data class Open(
        val flowId: String,
    ) : FlowsListAction

    public data class Edit(
        val flowId: String,
    ) : FlowsListAction

    public data class Duplicate(
        val flowId: String,
    ) : FlowsListAction

    public data class Export(
        val flowId: String,
    ) : FlowsListAction

    public data class RequestDelete(
        val flowId: String,
    ) : FlowsListAction

    public data object ConfirmDelete : FlowsListAction

    public data object DismissDelete : FlowsListAction

    public data class OpenDraft(
        val draftId: String,
    ) : FlowsListAction

    public data class DeleteDraft(
        val draftId: String,
    ) : FlowsListAction

    public data object OpenEndpoints : FlowsListAction
}

/** 列表页 ViewModel。 */
@HiltViewModel
public class FlowsListViewModel
    @Inject
    constructor(
        private val store: FlowStore,
        private val author: FlowAuthor,
        private val bundles: FlowBundleService,
        private val ids: IdGenerator,
    ) : ViewModel() {
        private val pendingDelete = MutableStateFlow<String?>(null)
        private val drafts = MutableStateFlow<List<DraftInfo>>(emptyList())

        public val uiState: StateFlow<FlowsListUiState> =
            combine(store.observeAll(), drafts, pendingDelete) { items, draftList, deleting ->
                FlowsListUiState(items.map(::rowOf), draftList, deleting)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FlowsListUiState())

        init {
            refreshDrafts()
        }

        /** 回到页面时刷新草稿列表（草稿不是流，创作结束后需要重读）。 */
        public fun refreshDrafts() {
            viewModelScope.launch { drafts.value = author.drafts() }
        }

        public fun onAction(
            action: FlowsListAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                is FlowsListAction.Open -> navigator.openFlow(action.flowId)
                is FlowsListAction.Edit -> navigator.openEditor(action.flowId)
                is FlowsListAction.Duplicate -> duplicate(action.flowId)
                is FlowsListAction.Export -> export(action.flowId, navigator)
                is FlowsListAction.RequestDelete -> pendingDelete.value = action.flowId
                FlowsListAction.DismissDelete -> pendingDelete.value = null
                FlowsListAction.ConfirmDelete -> confirmDelete()
                is FlowsListAction.OpenDraft -> navigator.openAuthor(action.draftId, null)
                is FlowsListAction.DeleteDraft ->
                    viewModelScope.launch {
                        author.deleteDraft(action.draftId)
                        drafts.value = author.drafts()
                    }
                FlowsListAction.OpenEndpoints -> navigator.openEndpoints()
            }
        }

        private fun duplicate(flowId: String) {
            viewModelScope.launch {
                val source = store.get(FlowId(flowId))?.definition ?: return@launch
                store.save(source.copy(id = "flow_" + ids.newId(), name = source.name + COPY_SUFFIX))
            }
        }

        private fun export(
            flowId: String,
            navigator: FlowsNavigator,
        ) {
            viewModelScope.launch {
                (bundles.export(listOf(flowId)) as? Outcome.Ok)?.let { navigator.shareText(it.value) }
            }
        }

        private fun confirmDelete() {
            val id = pendingDelete.value ?: return
            pendingDelete.value = null
            viewModelScope.launch { store.delete(FlowId(id)) }
        }

        private fun rowOf(item: FlowListItem): FlowRowUi =
            FlowRowUi(
                id = item.id.value,
                name = item.name,
                description = item.description,
                nodeCount = item.nodeCount,
                needsScreen = item.needsScreen,
                totalRuns = item.totalRuns,
                successPercent =
                    if (item.totalRuns ==
                        0L
                    ) {
                        null
                    } else {
                        (item.succeededRuns * PERCENT / item.totalRuns).toInt()
                    },
                lastRunStatus = item.lastRunStatus,
            )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val PERCENT = 100
            const val COPY_SUFFIX = " (2)"
        }
    }
