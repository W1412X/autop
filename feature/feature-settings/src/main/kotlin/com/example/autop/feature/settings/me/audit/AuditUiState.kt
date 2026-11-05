/**
 * 操作记录页状态（docs/tech/08 §4.5"操作记录"）。
 */
package com.example.autop.feature.settings.me.audit

import com.example.autop.task.AuditEntry

/** 操作记录页状态（分页追加，[endReached] 后不再加载）。 */
public data class AuditUiState(
    val entries: List<AuditEntry> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
)
