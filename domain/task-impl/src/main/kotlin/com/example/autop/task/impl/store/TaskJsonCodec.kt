/**
 * Task ↔ `tasks` 表 JSON 行的编解码（docs/tech/07 §3）：
 * 演进靠 schemaVersion + [TaskJsonMigrations]。
 */
package com.example.autop.task.impl.store

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Logger
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.example.autop.task.impl.db.TaskEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Task JSON 编解码。 */
public class TaskJsonCodec(
    private val json: Json = AutopJson.default,
    private val logger: Logger? = null,
) {
    /**
     * 行 → Task（迁移后解码，不可解返回 null）。
     * 旧回放脚本（如缺 `observeMode` 的旧采样）解码失败时按“无脚本”处理（WP2.8），
     * 不让整条任务失效。
     */
    public fun decode(entity: TaskEntity): Task? {
        val raw =
            TaskJsonMigrations
                .migrate(entity.json, TaskJsonMigrations.readVersion(json, entity.json), json)
                ?: return null
        runCatching { json.decodeFromString(Task.serializer(), raw) }.getOrNull()?.let { return it }
        logger?.w(TAG, "task decode failed; retrying without replayScript", "taskId" to entity.id)
        val withoutScript = Json.parseToJsonElement(raw).jsonObject.minus(REPLAY_SCRIPT_KEY)
        return runCatching {
            json.decodeFromString(Task.serializer(), JsonObject(withoutScript).toString())
        }.getOrNull()
    }

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

    private companion object {
        const val TAG = "TaskJsonCodec"
        const val REPLAY_SCRIPT_KEY = "replayScript"
    }
}
