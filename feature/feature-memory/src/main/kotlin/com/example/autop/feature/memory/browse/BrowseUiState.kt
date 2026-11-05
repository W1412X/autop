/**
 * 记忆浏览页 UI 状态（docs/tech/08 §4.4）。
 */
package com.example.autop.feature.memory.browse

import com.example.autop.core.ui.component.PagedListState

/** 一个列表行（目录或条目）。 */
public data class BrowseRow(
    val path: String,
    val title: String,
    val summary: String,
    val childCount: Int,
    val isDirectory: Boolean,
    val byAgent: Boolean,
)

/** 搜索结果行。 */
public data class SearchRow(
    val path: String,
    val title: String,
    val snippet: String,
)

/** 浏览页状态。 */
public data class BrowseUiState(
    val loading: Boolean = true,
    val path: String = "/",
    /** 面包屑各段（含根）。 */
    val crumbs: List<String> = emptyList(),
    val rows: List<BrowseRow> = emptyList(),
    val paged: PagedListState = PagedListState(),
    val query: String = "",
    val searchResults: List<SearchRow> = emptyList(),
    val searching: Boolean = false,
    /** 根目录时展示的“最近变更”入口计数（agent 来源条数）。 */
    val recentChangesCount: Int? = null,
    val error: String? = null,
)
