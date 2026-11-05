/**
 * 最近变更页 ViewModel（docs/tech/08 §4.4）：按 Run 分组的最近变更、单条撤销与整 Run 撤销；
 * 订阅 [MemoryStore.observeTree] 即时刷新（N184：agent 的记忆写入不刷新就得重新打开页面）。
 */
package com.example.autop.feature.memory.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.ui.component.PagedListState
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 最近变更页动作。 */
public sealed interface ChangesAction {
    /** 撤销单条变更。 */
    public data class Revert(
        val versionId: Long,
    ) : ChangesAction

    /** 撤销整个 Run。 */
    public data class RevertRun(
        val runId: String,
    ) : ChangesAction

    /** 分页加载。 */
    public data object LoadMore : ChangesAction

    /** 重试。 */
    public data object Retry : ChangesAction
}

/** 最近变更页 ViewModel。 */
@OptIn(FlowPreview::class) // debounce 为 FlowPreview API（同 BrowseViewModel）
@HiltViewModel
public class ChangesViewModel
    @Inject
    constructor(
        private val store: MemoryStore,
    ) : ViewModel() {
        private val loaded = mutableListOf<MemoryVersion>()

        private val _uiState = MutableStateFlow(ChangesUiState())
        public val uiState: StateFlow<ChangesUiState> = _uiState.asStateFlow()

        init {
            loadMore()
            viewModelScope.launch {
                store.observeTree().debounce(REFRESH_DEBOUNCE_MS).collect { refresh() }
            }
        }

        /** 分派用户动作。 */
        public fun onAction(action: ChangesAction) {
            when (action) {
                is ChangesAction.Revert -> revert(action.versionId)
                is ChangesAction.RevertRun -> revertRun(action.runId)
                ChangesAction.LoadMore -> loadMore()
                ChangesAction.Retry -> loadMore()
            }
        }

        private fun loadMore() {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            viewModelScope.launch {
                val page = store.recentChanges(offset = loaded.size, limit = PAGE_SIZE, source = null)
                loaded += page
                _uiState.value =
                    _uiState.value.copy(
                        loading = false,
                        groups = group(loaded),
                        paged = PagedListState(loading = false, endReached = page.size < PAGE_SIZE),
                    )
            }
        }

        private fun revert(versionId: Long) {
            viewModelScope.launch {
                val result = store.revert(versionId)
                if (result is Outcome.Err) {
                    _uiState.value = _uiState.value.copy(error = result.error.message)
                }
                refresh()
            }
        }

        private fun revertRun(runId: String) {
            viewModelScope.launch {
                val result = store.revertRun(RunId(runId))
                if (result is Outcome.Err) {
                    _uiState.value = _uiState.value.copy(error = result.error.message)
                }
                refresh()
            }
        }

        /** 按已加载条数整体重载（不重置分页进度）。 */
        private suspend fun refresh() {
            val keep = loaded.size.coerceAtLeast(PAGE_SIZE)
            val page = store.recentChanges(offset = 0, limit = keep, source = null)
            loaded.clear()
            loaded += page
            _uiState.value =
                _uiState.value.copy(
                    groups = group(loaded),
                    paged = PagedListState(loading = false, endReached = page.size < keep),
                )
        }

        private fun group(versions: List<MemoryVersion>): List<ChangeGroup> =
            versions
                .groupBy { it.runId?.value }
                .map { (runId, rows) ->
                    ChangeGroup(
                        runId = runId,
                        createdAt = rows.maxOf { it.createdAt },
                        rows =
                            rows.map {
                                ChangeRow(
                                    versionId = it.id,
                                    path = it.path.value,
                                    title = it.titleAfter ?: it.path.name,
                                    changeType = it.changeType.name,
                                    source = it.source.name,
                                    createdAt = it.createdAt,
                                    evidence = it.evidence?.excerpt,
                                )
                            },
                    )
                }.sortedByDescending { it.createdAt }

        private companion object {
            const val PAGE_SIZE: Int = 30

            /** 树变更信号去抖：一次 Run 可能连续写多条记忆，攒一攒再刷。 */
            const val REFRESH_DEBOUNCE_MS: Long = 300
        }
    }
