/**
 * `tasks.db` 实体（docs/tech/07 §3）：Task/Run/TriggerState/Audit 四表 v1 一次建全，
 * 避免后续启用触发功能时再加表迁移；当前只实现 Run 与 Audit 的存取。
 */
package com.example.autop.task.impl.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 任务：整个 [com.example.autop.task.Task] 以 JSON 存储，带 schemaVersion。 */
@Entity(tableName = "tasks")
public data class TaskEntity(
    @PrimaryKey val id: String,
    val json: String,
    val enabled: Boolean,
    val triggerType: String,
    val sourceId: String?,
    val updatedAt: Long,
)

/** 一次运行记录。 */
@Entity(
    tableName = "runs",
    indices = [Index("taskId"), Index("status"), Index("createdAt")],
)
public data class RunEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val threadId: String,
    val origin: String,
    val status: String,
    val triggerContextJson: String,
    val createdAt: Long,
    val startedAt: Long?,
    val endedAt: Long?,
    val summary: String?,
    val errorCode: String?,
    val pauseReason: String?,
    val waitingQuestion: String?,
    /** ask_user 选项的 JSON 数组；无选项为 null。 */
    val waitingOptionsJson: String? = null,
)

/** 任务触发状态（冷却、每日上限、去重窗口）；触发功能 WP 启用。 */
@Entity(tableName = "trigger_state")
public data class TriggerStateEntity(
    @PrimaryKey val taskId: String,
    val lastFiredAt: Long?,
    val dayKey: String?,
    val firedToday: Int,
    val recentKeysJson: String,
)

/** 工具审计日志：每次 WRITE_* 调用与策略决定一行。 */
@Entity(
    tableName = "audit_log",
    indices = [Index("runId"), Index("createdAt")],
)
public data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String?,
    val toolId: String,
    val targetSummary: String?,
    /** `allowed|blocked`。 */
    val decision: String,
    val ruleId: String?,
    val ok: Boolean?,
    val createdAt: Long,
)
