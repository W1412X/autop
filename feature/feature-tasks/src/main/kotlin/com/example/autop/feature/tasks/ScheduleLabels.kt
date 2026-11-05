/**
 * 定时触发的展示文案与 cron 形态判定（docs/tech/08 §4.3）：单次给日期时间，每天/工作日
 * 给「模式 + 时刻」，自定义 cron 原样给表达式。模式文案给资源 id，由屏幕层解析。
 * 形态判定与编辑表单回显（TaskEditUiState）同源，避免两处各判一套。
 */
package com.example.autop.feature.tasks

import androidx.annotation.StringRes
import com.example.autop.task.ScheduleSpec
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日期展示（yyyy-MM-dd）。 */
internal fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

/** 时刻展示（HH:mm:ss）。 */
internal fun formatClock(
    hour: Int,
    minute: Int,
    second: Int,
): String = CLOCK_FORMAT.format(LocalTime.of(hour, minute, second))

/** 日期时间展示（yyyy-MM-dd HH:mm:ss）。 */
internal fun formatDateTime(millis: Long): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** 每天 cron（单值时分[秒] + 日月周全通配）。 */
internal fun isDailyCron(expr: String): Boolean = plainCronTailOf(expr) == CRON_TAIL_DAILY

/** 工作日 cron（同每天，周段 1-5）。 */
internal fun isWorkdayCron(expr: String): Boolean = plainCronTailOf(expr) == CRON_TAIL_WORKDAY

/** cron 的时/分/秒（5 段形态秒取 0）。 */
internal fun cronTimeOf(expr: String): Triple<Int, Int, Int> {
    val tail = cronTailOf(expr)
    val minute = tail.getOrNull(CRON_MINUTE_INDEX)?.toIntOrNull() ?: 0
    val hour = tail.getOrNull(CRON_HOUR_INDEX)?.toIntOrNull() ?: DEFAULT_HOUR
    return Triple(hour, minute, cronSecondOf(expr))
}

/** 定时规格 → 模式文案（单次/每天/工作日；自定义 cron 无模式给 0）。 */
@StringRes
internal fun scheduleLabelRes(spec: ScheduleSpec): Int =
    when {
        spec is ScheduleSpec.Once -> R.string.tasks_schedule_once
        spec is ScheduleSpec.Cron -> cronLabelRes(spec.expr)
        else -> 0
    }

/** 日常 cron 给模式文案，自定义 cron 无模式。 */
@StringRes
private fun cronLabelRes(expr: String): Int =
    when {
        isDailyCron(expr) -> R.string.tasks_schedule_daily
        isWorkdayCron(expr) -> R.string.tasks_schedule_workday
        else -> 0
    }

/** 定时规格 → 时刻文案（单次含日期；自定义 cron 原样表达式）。 */
internal fun scheduleTimeText(spec: ScheduleSpec): String =
    when (spec) {
        is ScheduleSpec.Once -> formatDateTime(spec.atMillis)
        is ScheduleSpec.Cron -> cronTimeText(spec.expr)
    }

/** 日常 cron 给时刻文本，自定义 cron 原样表达式。 */
private fun cronTimeText(expr: String): String {
    val (hour, minute, second) = cronTimeOf(expr)
    return if (isDailyCron(expr) || isWorkdayCron(expr)) formatClock(hour, minute, second) else expr
}

/** 单值时分（秒）的 cron 尾段（日 月 周）；段数不对或时分非单值给 null。 */
private fun plainCronTailOf(expr: String): List<String>? {
    val tail = cronTailOf(expr)
    if (tail.size != CRON_FIELDS) return null
    if (tail[CRON_MINUTE_INDEX].toIntOrNull() == null) return null
    if (tail[CRON_HOUR_INDEX].toIntOrNull() == null) return null
    return tail.drop(CRON_DAY_INDEX)
}

/** cron 的 [分 时 日 月 周] 五段（6 段丢秒）。 */
private fun cronTailOf(expr: String): List<String> {
    val parts = expr.trim().split(Regex("\\s+"))
    return if (parts.size == CRON_FIELDS_WITH_SECONDS) parts.drop(1) else parts
}

/** cron 的秒段值（5 段形态秒固定 0）。 */
private fun cronSecondOf(expr: String): Int {
    val parts = expr.trim().split(Regex("\\s+"))
    return if (parts.size == CRON_FIELDS_WITH_SECONDS) {
        parts[CRON_SECOND_INDEX].toIntOrNull() ?: 0
    } else {
        0
    }
}

/** 每天的 cron 尾段（日 月 周）。 */
private val CRON_TAIL_DAILY: List<String> = listOf("*", "*", "*")

/** 工作日的 cron 尾段。 */
private val CRON_TAIL_WORKDAY: List<String> = listOf("*", "*", "1-5")

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private const val DEFAULT_HOUR = 8

/** cron 段数（分 时 日 月 周）。 */
private const val CRON_FIELDS = 5

/** 带秒的 cron 段数（秒 分 时 日 月 周）。 */
private const val CRON_FIELDS_WITH_SECONDS = 6

/** 带秒形态下“秒”的下标。 */
private const val CRON_SECOND_INDEX = 0

/** “分”的下标（分 时 日 月 周）。 */
private const val CRON_MINUTE_INDEX = 0

/** “时”的下标。 */
private const val CRON_HOUR_INDEX = 1

/** “日”的下标。 */
private const val CRON_DAY_INDEX = 2
