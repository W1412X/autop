/**
 * 触发状态存取（docs/tech/07 §2/§3）：`trigger_state` 表与 [TriggerState] 的映射。
 */
package com.example.autop.task.impl.store

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.TaskId
import com.example.autop.task.core.TriggerState
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.db.TriggerStateEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 触发状态存储。 */
public class TriggerStateStore(
    private val db: TasksDb,
    private val json: Json = AutopJson.default,
) {
    private val keysSerializer = ListSerializer(String.serializer())

    public suspend fun get(taskId: TaskId): TriggerState =
        db.triggerStateDao().byTask(taskId.value)?.toState() ?: TriggerState()

    public suspend fun save(
        taskId: TaskId,
        state: TriggerState,
    ) {
        db.triggerStateDao().upsert(state.toEntity(taskId))
    }

    private fun TriggerStateEntity.toState(): TriggerState =
        TriggerState(
            lastFiredAt = lastFiredAt,
            dayKey = dayKey,
            firedToday = firedToday,
            recentKeys = json.decodeFromString(keysSerializer, recentKeysJson),
        )

    private fun TriggerState.toEntity(taskId: TaskId): TriggerStateEntity =
        TriggerStateEntity(
            taskId = taskId.value,
            lastFiredAt = lastFiredAt,
            dayKey = dayKey,
            firedToday = firedToday,
            recentKeysJson = json.encodeToString(keysSerializer, recentKeys),
        )
}
