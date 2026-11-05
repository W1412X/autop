/**
 * 最近变更页 UI 状态（docs/tech/08 §4.4）：按 Run 分组。
 */
package com.example.autop.feature.memory.changes

import com.example.autop.core.ui.component.PagedListState

/** 一条变更记录行。 */
public data class ChangeRow(
    val versionId: Long,
    val path: String,
    val title: String,
    val changeType: String,
    val source: String,
    val createdAt: Long,
    val evidence: String?,
)

/** 一个 Run 的变更分组（[runId] 为 null 时是用户手动变更）。 */
public data class ChangeGroup(
    val runId: String?,
    val createdAt: Long,
    val rows: List<ChangeRow>,
)

/** 最近变更页状态。 */
public data class ChangesUiState(
    val loading: Boolean = true,
    val groups: List<ChangeGroup> = emptyList(),
    val paged: PagedListState = PagedListState(),
    val error: String? = null,
)
