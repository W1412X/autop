/**
 * [com.example.autop.task.AuditLogReader] 的实现（WP11 审计日志页）：
 * `audit_log` 分页查询映射为 [com.example.autop.task.AuditEntry]。
 */
package com.example.autop.task.impl.store

import com.example.autop.task.AuditEntry
import com.example.autop.task.AuditLogReader
import com.example.autop.task.impl.db.AuditEntity
import com.example.autop.task.impl.db.TasksDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 审计日志读取端口实现。 */
public class AuditLogReaderImpl(
    private val db: TasksDb,
) : AuditLogReader {
    override fun page(
        offset: Int,
        limit: Int,
    ): Flow<List<AuditEntry>> =
        db.auditDao().page(offset = offset, limit = limit).map { rows -> rows.map { it.toEntry() } }

    private fun AuditEntity.toEntry(): AuditEntry =
        AuditEntry(
            id = id,
            runId = runId,
            toolId = toolId,
            targetSummary = targetSummary,
            decision = decision,
            ruleId = ruleId,
            ok = ok,
            createdAt = createdAt,
        )
}
