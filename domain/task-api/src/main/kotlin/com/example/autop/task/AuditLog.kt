/**
 * 工具审计读取端口（docs/tech/07 §1.3、08 §4.7）："设置 → 高级 → 审计日志"页
 * 分页查看 `audit_log`（写入侧是 agent 的 `ToolAuditSink`，落库在 task-impl）。
 */
package com.example.autop.task

import kotlinx.coroutines.flow.Flow

/** 一条审计记录。 */
public data class AuditEntry(
    val id: Long,
    val runId: String?,
    val toolId: String,
    val targetSummary: String?,
    /** `allowed` 或 `blocked`。 */
    val decision: String,
    val ruleId: String?,
    val ok: Boolean?,
    val createdAt: Long,
)

/** 审计日志分页读取（按时间倒序）。 */
public interface AuditLogReader {
    public fun page(
        offset: Int,
        limit: Int,
    ): Flow<List<AuditEntry>>
}
