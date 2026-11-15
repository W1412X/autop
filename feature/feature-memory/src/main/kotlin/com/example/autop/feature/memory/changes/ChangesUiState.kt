/**
 * 最近变更页 UI 状态（docs/tech/08 §4.4）：按 Run 分组。
 */
package com.example.autop.feature.memory.changes

import com.example.autop.core.ui.component.PagedListState
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemorySource

/** 一条变更记录行。 */
public data class ChangeRow(
    val versionId: Long,
    val path: String,
    val title: String,
    val changeType: ChangeType,
    val source: MemorySource,
    val createdAt: Long,
    val evidence: String?,
    /** 变更的推理原因（agent 或用户填写），可能为空。 */
    val reason: String? = null,
    /** 字数变化：改后长度减改前长度。 */
    val charDelta: Int = 0,
    /** 内容摘要：改后第一行有内容的文字（删除时取改前），可能为空。 */
    val snippet: String? = null,
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
