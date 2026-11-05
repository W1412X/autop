/**
 * Task ↔ `tasks` 表 JSON 行的编解码（docs/tech/07 §3）：
 * 演进靠 schemaVersion + [TaskJsonMigrations]。
 */
package com.example.autop.task.impl.store

import com.example.autop.core.common.AutopJson
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.example.autop.task.impl.db.TaskEntity
import kotlinx.serialization.json.Json

/** Task JSON 编解码。 */
public class TaskJsonCodec(
    private val json: Json = AutopJson.default,
) {
    /** 行 → Task（迁移后解码，不可解返回 null）。 */
    public fun decode(entity: TaskEntity): Task? =
        TaskJsonMigrations
            .migrate(entity.json, TaskJsonMigrations.readVersion(json, entity.json), json)
            ?.let { raw -> json.decodeFromString(Task.serializer(), raw) }

    /** Task → 行。 */
    public fun toEntity(task: Task): TaskEntity =
        TaskEntity(
            id = task.id.value,
            json = json.encodeToString(Task.serializer(), task),
            enabled = task.enabled,
            triggerType = task.triggerType(),
            sourceId = (task.trigger as? Trigger.Condition)?.sourceId,
            updatedAt = task.updatedAt,
        )

    private fun Task.triggerType(): String =
        when (trigger) {
            is Trigger.Manual -> "manual"
            is Trigger.Schedule -> "schedule"
            is Trigger.Condition -> "condition"
        }
}
