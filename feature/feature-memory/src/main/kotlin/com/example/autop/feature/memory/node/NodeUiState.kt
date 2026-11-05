/**
 * 条目编辑页 UI 状态（docs/tech/08 §4.4）。
 */
package com.example.autop.feature.memory.node

/** 一行版本记录（时间、来源、变化类型、前后值供对比）。 */
public data class VersionRow(
    val id: Long,
    val version: Int,
    val changeType: String,
    val source: String,
    val createdAt: Long,
    val contentBefore: String?,
    val contentAfter: String?,
    val runId: String?,
)

/** 条目编辑页状态。 */
public data class NodeUiState(
    val loading: Boolean = true,
    val path: String = "/",
    val exists: Boolean = false,
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val byAgent: Boolean = false,
    val version: Int = 0,
    val updatedAt: Long = 0,
    val summaryMaxChars: Int = 60,
    val saving: Boolean = false,
    val error: String? = null,
    val versions: List<VersionRow> = emptyList(),
    val showVersions: Boolean = false,
    val diffVersion: VersionRow? = null,
    val showMore: Boolean = false,
    val confirmDelete: Boolean = false,
    val moveTarget: String? = null,
    /** 删除成功后置真，页面显示已删除提示。 */
    val deleted: Boolean = false,
)
