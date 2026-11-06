/**
 * 记忆浏览页 ViewModel（docs/tech/08 §4.4）：children 分页、搜索防抖（`ui.memory.searchDebounceMs`）、
 * 新建条目/目录、最近变更计数、[MemoryStore.observeTree] 订阅（agent 写入/删除/移动/撤销
 * 即时刷新，N184——本 VM 随导航存活，不订阅就要重新打开页面才能看到）。
 */
package com.example.autop.feature.memory.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.ui.component.PagedListState
import com.example.autop.feature.memory.MEMORY_PATH_ARG
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.isAutomated
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 浏览页 ViewModel。 */
@Suppress("TooManyFunctions") // 每个动作与加载路径各一个职责单一的私有处理函数，函数数即功能面
@OptIn(FlowPreview::class)
@HiltViewModel
public class BrowseViewModel
    @Inject
    constructor(
        private val store: MemoryStore,
        private val search: MemorySearch,
        private val configProvider: ConfigProvider,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val initialPath: String = savedState.get<String>(MEMORY_PATH_ARG) ?: "/"
        private val queryFlow = MutableStateFlow("")

        private val _uiState = MutableStateFlow(BrowseUiState(path = initialPath))
        public val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch { navigateTo(initialPath) }
            viewModelScope.launch {
                queryFlow
                    .debounce(configProvider.current()[UiConfig.MEMORY].searchDebounceMs)
                    .distinctUntilChanged()
                    .collect { runSearch(it) }
            }
            viewModelScope.launch {
                store.observeTree().debounce(REFRESH_DEBOUNCE_MS).collect { refresh() }
            }
        }

        /** 分派用户动作。 */
        public fun onAction(action: BrowseAction) {
            when (action) {
                is BrowseAction.OpenCrumb -> openCrumb(action.index)
                is BrowseAction.OpenRow -> openRow(action)
                is BrowseAction.QueryChanged -> {
                    _uiState.value = _uiState.value.copy(query = action.query, searching = action.query.isNotBlank())
                    queryFlow.value = action.query
                }
                BrowseAction.LoadMore -> loadMore()
                is BrowseAction.Create -> create(action)
                BrowseAction.Retry ->
                    openCrumb(
                        _uiState.value.crumbs.lastIndex
                            .coerceAtLeast(0),
                    )
            }
        }

        private fun openCrumb(index: Int) {
            val target = _uiState.value.crumbs.getOrNull(index) ?: return
            viewModelScope.launch { navigateTo(target) }
        }

        private fun openRow(action: BrowseAction.OpenRow) {
            if (action.isDirectory) {
                viewModelScope.launch { navigateTo(action.path) }
            }
            // 条目点击由 Screen 回调 onOpenNode 导航
        }

        private suspend fun navigateTo(path: String) {
            val parsed =
                MemoryPath.parse(path).getOrNull()
                    ?: MemoryPath.ROOT
            _uiState.value =
                _uiState.value.copy(
                    loading = true,
                    path = parsed.value,
                    rows = emptyList(),
                    error = null,
                    paged = PagedListState(loading = true),
                )
            loadPage(parsed, offset = 0)
            loadRecentCount(parsed)
        }

        private fun loadMore() {
            val current = _uiState.value
            val parsed = MemoryPath.parse(current.path).getOrNull() ?: MemoryPath.ROOT
            viewModelScope.launch { loadPage(parsed, offset = current.rows.size) }
        }

        /** 树变更信号后按已加载条数整体重载当前目录（不重置分页进度）＋刷新最近变更计数。 */
        private fun refresh() {
            val current = _uiState.value
            val parsed = MemoryPath.parse(current.path).getOrNull() ?: MemoryPath.ROOT
            viewModelScope.launch {
                loadPage(parsed, offset = 0, limit = current.rows.size.coerceAtLeast(PAGE_SIZE))
                loadRecentCount(parsed)
            }
        }

        @Suppress("ReturnCount") // 出口按“解析失败/查询失败”分支，是错误处理语义
        private suspend fun loadPage(
            path: MemoryPath,
            offset: Int,
            limit: Int = PAGE_SIZE,
        ) {
            if (offset == 0) {
                _uiState.value =
                    _uiState.value.copy(crumbs = crumbsOf(path), error = null)
            }
            _uiState.value = _uiState.value.copy(paged = PagedListState(loading = true))
            val children = store.children(path, offset = offset, limit = limit)
            val rows =
                children.map { child ->
                    BrowseRow(
                        path = child.path.value,
                        title = child.title.ifBlank { child.path.name },
                        summary = child.summary,
                        childCount = child.childCount,
                        isDirectory = child.content.isBlank(),
                        byAgent = child.source.isAutomated,
                    )
                }
            _uiState.value =
                _uiState.value.copy(
                    loading = false,
                    rows =
                        _uiState.value.rows
                            .takeIf { offset > 0 }
                            .orEmpty() + rows,
                    paged =
                        PagedListState(
                            loading = false,
                            endReached = rows.size < limit,
                        ),
                    error = null,
                )
        }

        private suspend fun loadRecentCount(path: MemoryPath) {
            if (path != MemoryPath.ROOT) {
                _uiState.value = _uiState.value.copy(recentChangesCount = null)
                return
            }
            val count =
                store.recentChanges(offset = 0, limit = RECENT_LIMIT, source = MemorySource.AGENT).size +
                    store.recentChanges(offset = 0, limit = RECENT_LIMIT, source = MemorySource.FLOW).size
            _uiState.value = _uiState.value.copy(recentChangesCount = count)
        }

        private fun create(action: BrowseAction.Create) {
            val name = action.name.trim()
            val parent = MemoryPath.parse(_uiState.value.path).getOrNull() ?: MemoryPath.ROOT
            val target =
                when (val parsed = MemoryPath.parse("${parent.value}/$name")) {
                    is Outcome.Ok -> parsed.value
                    is Outcome.Err -> {
                        _uiState.value = _uiState.value.copy(error = parsed.error.message)
                        return
                    }
                }
            viewModelScope.launch {
                val result =
                    store.write(
                        MemoryWrite(
                            path = target,
                            mode = WriteMode.CREATE,
                            title = action.title ?: name,
                            content = "",
                            source = MemorySource.USER,
                        ),
                    )
                when (result) {
                    is Outcome.Ok -> navigateTo(parent.value)
                    is Outcome.Err -> _uiState.value = _uiState.value.copy(error = result.error.message)
                }
            }
        }

        private suspend fun runSearch(query: String) {
            if (query.isBlank()) {
                _uiState.value = _uiState.value.copy(searchResults = emptyList(), searching = false)
                return
            }
            val hits = search.search(query = query, prefix = null, k = SEARCH_LIMIT)
            _uiState.value =
                _uiState.value.copy(
                    searching = false,
                    searchResults =
                        hits.map {
                            SearchRow(path = it.path.value, title = it.title, snippet = it.snippet)
                        },
                )
        }

        private fun crumbsOf(path: MemoryPath): List<String> {
            val crumbs = mutableListOf("/")
            var current = ""
            path.segments().forEach { seg ->
                current += "/$seg"
                crumbs += current
            }
            return crumbs
        }

        private companion object {
            const val PAGE_SIZE: Int = 30
            const val SEARCH_LIMIT: Int = 20
            const val RECENT_LIMIT: Int = 20

            /** 树变更信号去抖：一次 Run 可能连续写多条记忆，攒一攒再刷。 */
            const val REFRESH_DEBOUNCE_MS: Long = 300
        }
    }
