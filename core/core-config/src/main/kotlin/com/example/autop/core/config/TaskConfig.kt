/**
 * `task` 领域的配置段与键（docs/tech/09 §1.6）。
 * 段默认值与 `core-config-impl/assets/config/task.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `task`（顶层散键）：任务调度杂项。 */
@Serializable
public data class TaskSection(
    @SerialName("idleBeforeStartMs") val idleBeforeStartMs: Long = 10000,
    @SerialName("returnToOriginApp") val returnToOriginApp: Boolean = true,
) : ConfigSection

/** `task.conditions`：条件触发。 */
@Serializable
public data class TaskConditionsSection(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("evaluator") val evaluator: String = "evaluator.llm",
    @SerialName("onEvaluatorError") val onEvaluatorError: String = "skip",
    @SerialName("maxPerHour") val maxPerHour: Int = 20,
) : ConfigSection

/** `task.dedupe`：重复触发去重窗口（条）。 */
@Serializable
public data class TaskDedupeSection(
    @SerialName("window") val window: Int = 50,
) : ConfigSection

/** `task.queue`：触发源队列权重。 */
@Serializable
public data class TaskQueueSection(
    @SerialName("originWeights") val originWeights: Map<String, Int> = DEFAULT_ORIGIN_WEIGHTS,
) : ConfigSection {
    public companion object {
        public val DEFAULT_ORIGIN_WEIGHTS: Map<String, Int> =
            mapOf("CHAT" to 100, "TASK_MANUAL" to 80, "RESUME" to 60, "FORK" to 60, "SCHEDULE" to 40, "CONDITION" to 20)
    }
}

/** `task.limits`：冷却与每日限额。 */
@Serializable
public data class TaskLimitsSection(
    @SerialName("minCooldownMs") val minCooldownMs: Long = 60000,
    @SerialName("maxDailyLimit") val maxDailyLimit: Int = 100,
) : ConfigSection

/** `task.reminder`：锁屏提醒。 */
@Serializable
public data class TaskReminderSection(
    @SerialName("previewChars") val previewChars: Int = 60,
    @SerialName("snoozeMinutes") val snoozeMinutes: Int = 10,
    @SerialName("snoozeOptions") val snoozeOptions: List<Int> = DEFAULT_SNOOZE_OPTIONS,
    @SerialName("expireMinutes") val expireMinutes: ExpireMinutes = ExpireMinutes(),
) : ConfigSection {
    /** `task.reminder.expireMinutes`：提醒过期时间（分钟）。 */
    @Serializable
    public data class ExpireMinutes(
        @SerialName("schedule") val schedule: Int = 60,
        @SerialName("condition") val condition: Int = 15,
    )

    public companion object {
        public val DEFAULT_SNOOZE_OPTIONS: List<Int> = listOf(5, 10, 30)
    }
}

/** `task.notify`：完成通知。 */
@Serializable
public data class TaskNotifySection(
    @SerialName("summaryChars") val summaryChars: Int = 120,
) : ConfigSection

/** `task.draft`：草稿生成修复重试。 */
@Serializable
public data class TaskDraftSection(
    @SerialName("retries") val retries: Int = 2,
) : ConfigSection

/** `task.audit`：审计日志保留。 */
@Serializable
public data class TaskAuditSection(
    @SerialName("retentionDays") val retentionDays: Int = 30,
) : ConfigSection

/** `task.host`：AgentHost 前台服务。 */
@Serializable
public data class TaskHostSection(
    @SerialName("persistent") val persistent: Boolean = false,
) : ConfigSection

/** `task.replay`：任务回放（docs/tech/04 §2.7/§3.12，R1/A6）。 */
@Serializable
@Suppress("LongParameterList") // 配置段字段是回放参数面的自然结果
public data class TaskReplaySection(
    @SerialName("enabled") val enabled: Boolean = true,
    /** 录制与回放的最大步数，超出不录制。 */
    @SerialName("maxSteps") val maxSteps: Int = 20,
    /** 每步前置状态等待窗口。 */
    @SerialName("preStateWaitMs") val preStateWaitMs: Long = 3000,
    /** 前置状态轮询间隔。 */
    @SerialName("pollMs") val pollMs: Long = 500,
    /** 前置状态相似度阈值（docs/tech/04 §2.7）。 */
    @SerialName("threshold") val threshold: Double = 0.85,
    /** 键集 Jaccard 权重（docs/tech/04 §2.7）。 */
    @SerialName("keysWeight") val keysWeight: Double = 0.85,
    /** 包名/Activity 相同权重（docs/tech/04 §2.7）。 */
    @SerialName("appWeight") val appWeight: Double = 0.15,
) : ConfigSection

/** `task` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object TaskConfig {
    public val TASK: ConfigKey<TaskSection> =
        ConfigKey("task", TaskSection.serializer()) { TaskSection() }
    public val CONDITIONS: ConfigKey<TaskConditionsSection> =
        ConfigKey("task.conditions", TaskConditionsSection.serializer()) { TaskConditionsSection() }
    public val DEDUPE: ConfigKey<TaskDedupeSection> =
        ConfigKey("task.dedupe", TaskDedupeSection.serializer()) { TaskDedupeSection() }
    public val QUEUE: ConfigKey<TaskQueueSection> =
        ConfigKey("task.queue", TaskQueueSection.serializer()) { TaskQueueSection() }
    public val LIMITS: ConfigKey<TaskLimitsSection> =
        ConfigKey("task.limits", TaskLimitsSection.serializer()) { TaskLimitsSection() }
    public val REMINDER: ConfigKey<TaskReminderSection> =
        ConfigKey("task.reminder", TaskReminderSection.serializer()) { TaskReminderSection() }
    public val NOTIFY: ConfigKey<TaskNotifySection> =
        ConfigKey("task.notify", TaskNotifySection.serializer()) { TaskNotifySection() }
    public val DRAFT: ConfigKey<TaskDraftSection> =
        ConfigKey("task.draft", TaskDraftSection.serializer()) { TaskDraftSection() }
    public val AUDIT: ConfigKey<TaskAuditSection> =
        ConfigKey("task.audit", TaskAuditSection.serializer()) { TaskAuditSection() }
    public val HOST: ConfigKey<TaskHostSection> =
        ConfigKey("task.host", TaskHostSection.serializer()) { TaskHostSection() }
    public val REPLAY: ConfigKey<TaskReplaySection> =
        ConfigKey("task.replay", TaskReplaySection.serializer()) { TaskReplaySection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(TASK, CONDITIONS, DEDUPE, QUEUE, LIMITS, REMINDER, NOTIFY, DRAFT, AUDIT, HOST, REPLAY)
}
