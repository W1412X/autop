/**
 * [PagedList] 的尾部状态。
 */
package com.example.autop.core.ui.component

/** 分页状态：加载中 / 已到底 / 尾部错误（“重试”触发 [PagedList.onLoadMore]）。 */
public data class PagedListState(
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)
