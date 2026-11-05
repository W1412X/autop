/**
 * 工具审计（docs/tech/06 §5、07 §3）：ToolRouter 对每次 WRITE_* 调用与策略决定
 * 调用 [record]，落 `audit_log` 行。
 */
package com.example.autop.task.impl.store

import com.example.autop.agent.ToolAuditSink
import com.example.autop.core.common.Clock
import com.example.autop.core.common.RunId
import com.example.autop.policy.PolicyDecision
import com.example.autop.task.impl.db.AuditEntity
import com.example.autop.task.impl.db.TasksDb

/** 审计端口实现。 */
public class AuditStore(
    private val db: TasksDb,
    private val clock: Clock,
) : ToolAuditSink {
    override suspend fun record(
        runId: RunId,
        toolId: String,
        targetSummary: String?,
        decision: PolicyDecision,
        ok: Boolean?,
    ) {
        db
            .auditDao()
            .insert(
                AuditEntity(
                    runId = runId.value,
                    toolId = toolId,
                    targetSummary = targetSummary,
                    decision =
                        when (decision) {
                            is PolicyDecision.Allow -> DECISION_ALLOWED
                            is PolicyDecision.Block -> DECISION_BLOCKED
                        },
                    ruleId = (decision as? PolicyDecision.Block)?.ruleId,
                    ok = ok,
                    createdAt = clock.nowMillis(),
                ),
            )
    }

    private companion object {
        const val DECISION_ALLOWED = "allowed"
        const val DECISION_BLOCKED = "blocked"
    }
}
