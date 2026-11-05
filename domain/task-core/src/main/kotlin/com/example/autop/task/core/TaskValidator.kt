/**
 * 任务校验（docs/tech/07 §2）：名称非空、cron 合法、source 已注册、
 * cooldown ≥ 配置下限、dailyLimit ≤ 配置上限。
 */
package com.example.autop.task.core

import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.Trigger

/** 校验结果：不合法项的字段名 → 错误码。 */
public data class TaskProblems(
    val byField: Map<String, String>,
) {
    public val isValid: Boolean get() = byField.isEmpty()
}

/** 任务校验（纯函数）。 */
public object TaskValidator {
    /**
     * 校验 [task]。
     *
     * @param knownSources 已注册的触发源 id。
     * @param minCooldownMs 冷却期下限（`task.limits.minCooldownMs`）。
     * @param maxDailyLimit 每日上限上限（`task.limits.maxDailyLimit`）。
     */
    public fun validate(
        task: Task,
        knownSources: Set<String>,
        minCooldownMs: Long,
        maxDailyLimit: Int,
    ): TaskProblems {
        val problems = mutableMapOf<String, String>()
        if (task.name.isBlank()) problems[FIELD_NAME] = TaskErrorCodes.INVALID
        when (val trigger = task.trigger) {
            is Trigger.Schedule -> validateSchedule(trigger, problems)
            is Trigger.Condition -> validateCondition(trigger, knownSources, minCooldownMs, maxDailyLimit, problems)
            Trigger.Manual -> Unit
        }
        return TaskProblems(problems)
    }

    @Suppress("SwallowedException") // CronFormatException 的字段名已落到 problems 的 cron 字段
    private fun validateSchedule(
        trigger: Trigger.Schedule,
        problems: MutableMap<String, String>,
    ) {
        val spec = trigger.spec
        if (spec is com.example.autop.task.ScheduleSpec.Cron) {
            try {
                Cron.parse(spec.expr)
            } catch (e: CronFormatException) {
                problems[FIELD_CRON] = TaskErrorCodes.CRON_INVALID
            }
        }
    }

    private fun validateCondition(
        trigger: Trigger.Condition,
        knownSources: Set<String>,
        minCooldownMs: Long,
        maxDailyLimit: Int,
        problems: MutableMap<String, String>,
    ) {
        if (trigger.sourceId !in knownSources) problems[FIELD_SOURCE] = TaskErrorCodes.SOURCE_UNKNOWN
        if (trigger.cooldownMs < minCooldownMs) problems[FIELD_COOLDOWN] = TaskErrorCodes.INVALID
        if (trigger.dailyLimit > maxDailyLimit) problems[FIELD_DAILY_LIMIT] = TaskErrorCodes.INVALID
        if (trigger.dailyLimit < 1) problems[FIELD_DAILY_LIMIT] = TaskErrorCodes.INVALID
    }

    public const val FIELD_NAME: String = "name"
    public const val FIELD_CRON: String = "cron"
    public const val FIELD_SOURCE: String = "sourceId"
    public const val FIELD_COOLDOWN: String = "cooldownMs"
    public const val FIELD_DAILY_LIMIT: String = "dailyLimit"
}
