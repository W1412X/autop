/**
 * [buildTask] / [TaskEditUiState.validate] / 表单回显单测（docs/tech/08 §4.3）：单次的
 * 日期 + 时分秒装配、每天/工作日 cron 的 5/6 段形态、回显往返与过期校验。
 */
package com.example.autop.feature.tasks.edit

import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TaskBuilderTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: Long =
        LocalDateTime
            .parse("2026-10-21T12:00:00")
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun stateOf(mode: EditScheduleMode): TaskEditUiState =
        TaskEditUiState(
            name = "任务",
            goal = "目标",
            triggerKind = EditTriggerKind.SCHEDULE,
            scheduleMode = mode,
            date = LocalDate.parse("2026-10-25"),
            timeHour = 8,
            timeMinute = 30,
            timeSecond = 15,
        )

    private fun buildOf(state: TaskEditUiState): Task = state.buildTask(existingId = null, newId = "t1", now = now)

    private fun cronExprOf(task: Task): String? {
        val spec = (task.trigger as? Trigger.Schedule)?.spec as? ScheduleSpec.Cron
        return spec?.expr
    }

    @Test
    fun onceAssemblesPickedDateTime() {
        val expected =
            LocalDateTime
                .parse("2026-10-25T08:30:15")
                .atZone(zone)
                .toInstant()
                .toEpochMilli()

        val trigger = checkNotNull(buildOf(stateOf(EditScheduleMode.ONCE)).trigger as? Trigger.Schedule)

        assertThat(trigger.spec).isEqualTo(ScheduleSpec.Once(atMillis = expected))
    }

    @Test
    fun dailyUsesSixFieldCronWithSecondsAndFiveWithout() {
        val withSecond = buildOf(stateOf(EditScheduleMode.DAILY))
        val wholeMinute = buildOf(stateOf(EditScheduleMode.DAILY).copy(timeSecond = 0))

        assertThat(cronExprOf(withSecond)).isEqualTo("15 30 8 * * *")
        assertThat(cronExprOf(wholeMinute)).isEqualTo("30 8 * * *")
    }

    @Test
    fun workdayKeepsWeekSegment() {
        val task = buildOf(stateOf(EditScheduleMode.WORKDAY).copy(timeSecond = 0))

        assertThat(cronExprOf(task)).isEqualTo("30 8 * * 1-5")
    }

    @Test
    fun formEchoesBackOnceAndDaily() {
        val echoedOnce = TaskEditUiState().from(buildOf(stateOf(EditScheduleMode.ONCE)))
        val echoedDaily = TaskEditUiState().from(buildOf(stateOf(EditScheduleMode.DAILY)))

        assertThat(echoedOnce.scheduleMode).isEqualTo(EditScheduleMode.ONCE)
        assertThat(echoedOnce.date).isEqualTo(LocalDate.parse("2026-10-25"))
        assertThat(echoedOnce.timeHour to echoedOnce.timeMinute).isEqualTo(8 to 30)
        assertThat(echoedOnce.timeSecond).isEqualTo(15)
        assertThat(echoedDaily.scheduleMode).isEqualTo(EditScheduleMode.DAILY)
        assertThat(echoedDaily.timeHour to echoedDaily.timeMinute to echoedDaily.timeSecond).isEqualTo(8 to 30 to 15)
    }

    @Test
    fun validateFlagsPastOnceOnly() {
        val past = stateOf(EditScheduleMode.ONCE).copy(date = LocalDate.parse("2026-10-20"))

        assertThat(past.validate(now)).isEqualTo(TaskEditError.TIME.name)
        assertThat(stateOf(EditScheduleMode.ONCE).validate(now)).isNull()
        assertThat(stateOf(EditScheduleMode.DAILY).copy(date = LocalDate.parse("2026-10-20")).validate(now)).isNull()
    }

    @Test
    fun switchingToOnceRollsPastDefaultToTomorrow() {
        val rolled =
            stateOf(EditScheduleMode.DAILY)
                .copy(date = LocalDate.parse("2026-10-21"), timeHour = 8)
                .copy(scheduleMode = EditScheduleMode.ONCE)
                .withUpcomingOnceDate(now)

        assertThat(rolled.date).isEqualTo(LocalDate.parse("2026-10-22"))
    }
}
