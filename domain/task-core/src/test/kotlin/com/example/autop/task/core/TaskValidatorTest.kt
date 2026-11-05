package com.example.autop.task.core

import com.example.autop.core.common.TaskId
import com.example.autop.task.FilterGroup
import com.example.autop.task.GroupMode
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.Trigger
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [TaskValidator] 单测（docs/tech/07 §2）。 */
internal class TaskValidatorTest {
    private val conditionTrigger =
        Trigger.Condition(
            sourceId = "trigger.notification",
            filter = FilterGroup(GroupMode.ALL, emptyList()),
            cooldownMs = 60000,
            dailyLimit = 10,
        )

    private fun task(
        name: String = "每日截图",
        trigger: Trigger = conditionTrigger,
    ) = Task(
        id = TaskId("t1"),
        name = name,
        goal = "截图",
        trigger = trigger,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun validate(task: Task): TaskProblems =
        TaskValidator.validate(
            task = task,
            knownSources = setOf("trigger.notification"),
            minCooldownMs = 60000,
            maxDailyLimit = 100,
        )

    @Test
    fun acceptsValidConditionTask() {
        assertThat(validate(task()).isValid).isTrue()
    }

    @Test
    fun rejectsBlankName() {
        assertThat(validate(task(name = " ")).byField)
            .containsEntry(TaskValidator.FIELD_NAME, TaskErrorCodes.INVALID)
    }

    @Test
    fun rejectsUnknownSource() {
        val t = task(trigger = conditionTrigger.copy(sourceId = "trigger.unknown"))
        assertThat(validate(t).byField).containsEntry(TaskValidator.FIELD_SOURCE, TaskErrorCodes.SOURCE_UNKNOWN)
    }

    @Test
    fun rejectsShortCooldown() {
        val t = task(trigger = conditionTrigger.copy(cooldownMs = 1))
        assertThat(validate(t).byField).containsEntry(TaskValidator.FIELD_COOLDOWN, TaskErrorCodes.INVALID)
    }

    @Test
    fun rejectsTooHighDailyLimit() {
        val t = task(trigger = conditionTrigger.copy(dailyLimit = 1000))
        assertThat(validate(t).byField).containsEntry(TaskValidator.FIELD_DAILY_LIMIT, TaskErrorCodes.INVALID)
    }

    @Test
    fun rejectsZeroDailyLimit() {
        val t = task(trigger = conditionTrigger.copy(dailyLimit = 0))
        assertThat(validate(t).byField).containsEntry(TaskValidator.FIELD_DAILY_LIMIT, TaskErrorCodes.INVALID)
    }

    @Test
    fun acceptsValidCron() {
        val t = task(trigger = Trigger.Schedule(ScheduleSpec.Cron("0 8 * * *", "Asia/Shanghai")))
        assertThat(validate(t).isValid).isTrue()
    }

    @Test
    fun rejectsBadCron() {
        val t = task(trigger = Trigger.Schedule(ScheduleSpec.Cron("99 * * * *", "Asia/Shanghai")))
        assertThat(validate(t).byField).containsEntry(TaskValidator.FIELD_CRON, TaskErrorCodes.CRON_INVALID)
    }

    @Test
    fun manualNeedsOnlyName() {
        assertThat(validate(task(trigger = Trigger.Manual)).isValid).isTrue()
    }
}
