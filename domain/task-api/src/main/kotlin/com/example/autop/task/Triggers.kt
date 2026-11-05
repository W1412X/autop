/**
 * 触发源与条件判定扩展点（docs/tech/07 §1.4）。
 */
package com.example.autop.task

import com.example.autop.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** 一个触发事件（字段值均不可信文本）。 */
@Serializable
public data class TriggerEvent(
    val sourceId: String,
    /** 去重键（如通知 key）。 */
    val key: String,
    val occurredAt: Long,
    val fields: Map<String, String>,
)

/** 触发源扩展点（内置源在 task-impl 注册）。 */
public interface TriggerSource {
    /** 稳定 id（`trigger.notification` 等）。 */
    public val id: String

    /** 供任务编辑器展示可过滤的字段名。 */
    public val fieldNames: List<String>

    /** 冷流，由 TriggerHub 在需要时收集。 */
    public fun events(): Flow<TriggerEvent>

    /** 例如 "notification_listener"；未满足时任务编辑器提示。 */
    public val requiresPermission: String?
}

/**
 * LLM 条件判定（只用于 llmCondition；规则过滤由 task-core 完成）。
 */
public interface ConditionEvaluator {
    public val id: String

    public suspend fun evaluate(
        condition: String,
        event: TriggerEvent,
    ): Outcome<ConditionResult>
}

/** 条件判定结果。 */
@Serializable
public data class ConditionResult(
    val match: Boolean,
    val reason: String? = null,
    val extracted: Map<String, String> = emptyMap(),
)
