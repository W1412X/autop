/**
 * 任务定义与触发器（docs/tech/07 §1.1）。
 */
package com.example.autop.task

import com.example.autop.agent.ReplayScript
import com.example.autop.core.common.TaskId
import com.example.autop.policy.PolicyOverrides
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 一个任务定义。 */
@Serializable
public data class Task(
    val id: TaskId,
    val name: String,
    /** 自然语言目标。 */
    val goal: String,
    val trigger: Trigger,
    val needsScreen: Boolean = true,
    /** null = 全部启用工具。 */
    val allowedTools: Set<String>? = null,
    val policyOverrides: PolicyOverrides = PolicyOverrides(),
    /** Task 级配置覆盖层。 */
    val configOverrides: JsonObject = JsonObject(emptyMap()),
    /** 越大越先。 */
    val priority: Int = 0,
    val lockedBehavior: LockedBehavior = LockedBehavior.REMIND,
    val enabled: Boolean = true,
    /** finish.outputs 的 JSON Schema 子集（R1/C2）；后台任务结束时按它校验，不通过拒绝结束。 */
    val outputSchema: JsonObject? = null,
    /** 回放脚本（docs/tech/04 §3.12，R1/A6）：最近一次成功 Run 录制的动作序列；null = 无。 */
    val replayScript: ReplayScript? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = 1,
)

/** 锁屏时的行为。 */
@Serializable
public enum class LockedBehavior {
    /** 提醒用户解锁后执行。 */
    REMIND,

    /** 直接跳过本次。 */
    SKIP,
}

/** 触发方式。 */
@Serializable
public sealed interface Trigger {
    /** 手动触发。 */
    @Serializable
    @SerialName("manual")
    public data object Manual : Trigger

    /** 定时触发。 */
    @Serializable
    @SerialName("schedule")
    public data class Schedule(
        val spec: ScheduleSpec,
    ) : Trigger

    /** 条件触发。 */
    @Serializable
    @SerialName("condition")
    public data class Condition(
        /** 如 "trigger.notification" 等。 */
        val sourceId: String,
        /** 本地规则过滤。 */
        val filter: FilterGroup,
        /** 可选 LLM 判定条件。 */
        val llmCondition: String? = null,
        val cooldownMs: Long,
        val dailyLimit: Int,
        /** 用事件的哪个字段去重，如 "key"。 */
        val dedupeField: String? = null,
    ) : Trigger
}

/** 定时规格。 */
@Serializable
public sealed interface ScheduleSpec {
    /** 一次性，到点执行。 */
    @Serializable
    @SerialName("once")
    public data class Once(
        val atMillis: Long,
    ) : ScheduleSpec

    /** 5 段 cron（分 时 日 月 周）。 */
    @Serializable
    @SerialName("cron")
    public data class Cron(
        val expr: String,
        val zoneId: String,
    ) : ScheduleSpec
}

/** 过滤条件组。 */
@Serializable
public data class FilterGroup(
    val mode: GroupMode,
    val clauses: List<FilterClause>,
    val groups: List<FilterGroup> = emptyList(),
)

/** 组合方式。 */
@Serializable
public enum class GroupMode {
    ALL,
    ANY,
}

/** 单条过滤条件。 */
@Serializable
public data class FilterClause(
    val field: String,
    val op: FilterOp,
    val value: String,
    val ignoreCase: Boolean = true,
)

/** 过滤操作。 */
@Serializable
public enum class FilterOp {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    REGEX,

    /** value 用 | 分隔。 */
    IN,
    EXISTS,
}
