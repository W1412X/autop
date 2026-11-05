/**
 * 5/6 段 cron 解析与下次触发计算（docs/tech/07 §2）：[秒] 分 时 日 月 周，
 * 6 段带秒、5 段秒取 0（兼容旧任务）。支持 `*`、`,`、`-`、`/`，周 0–7 且 0/7 为周日；
 * 周与日同时指定按“或”处理（与标准 cron 一致）。
 */
package com.example.autop.task.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 一个已解析的 cron 表达式。 */
public class CronExpression internal constructor(
    private val fields: CronFields,
) {
    /**
     * 计算 [afterMillis] 之后的下一次触发（不含正好等于的时刻）。
     * 找不到（例如 2 月 30 日）返回 null。
     */
    public fun next(
        afterMillis: Long,
        zone: ZoneId,
    ): Long? {
        var cursor = startCursor(afterMillis, zone)
        var searched = 0
        while (searched < MAX_SEARCH_DAYS && cursor.year <= MAX_YEAR) {
            if (matchesDate(cursor)) {
                val time = nextTimeOnDay(cursor)
                if (time != null) return time.atZone(zone).toInstant().toEpochMilli()
            }
            cursor = cursor.toLocalDate().plusDays(1).atStartOfDay()
            searched++
        }
        return null
    }

    private fun startCursor(
        afterMillis: Long,
        zone: ZoneId,
    ): LocalDateTime =
        Instant
            .ofEpochMilli(afterMillis)
            .atZone(zone)
            .toLocalDateTime()
            .plusSeconds(1)
            .withNano(0)

    /** 当天 [cursor] 起（含）第一个匹配的时:分:秒；找不到返回 null。 */
    private fun nextTimeOnDay(cursor: LocalDateTime): LocalDateTime? {
        for (hour in fields.hours.sorted()) {
            if (hour < cursor.hour) continue
            for (minute in fields.minutes.sorted()) {
                val second = nextSecondAt(hour, minute, cursor) ?: continue
                return cursor.withHour(hour).withMinute(minute).withSecond(second)
            }
        }
        return null
    }

    /** 该时分下不早于 [cursor] 的第一个秒；时分已过或无匹配返回 null。 */
    private fun nextSecondAt(
        hour: Int,
        minute: Int,
        cursor: LocalDateTime,
    ): Int? {
        if (hour == cursor.hour && minute < cursor.minute) return null
        return fields.seconds
            .sorted()
            .firstOrNull { second -> hour != cursor.hour || minute != cursor.minute || second >= cursor.second }
    }

    /** 日期（月、日/周规则）是否匹配。 */
    private fun matchesDate(dateTime: LocalDateTime): Boolean {
        if (dateTime.monthValue !in fields.months) return false
        val domMatch = dateTime.dayOfMonth in fields.daysOfMonth
        val dowMatch = toCronWeek(dateTime.dayOfWeek) in fields.daysOfWeek
        return when {
            fields.dayOfMonthAny && fields.dayOfWeekAny -> true
            fields.dayOfMonthAny -> dowMatch
            fields.dayOfWeekAny -> domMatch
            // 周与日同时指定按“或”处理（标准 cron 语义）。
            else -> domMatch || dowMatch
        }
    }
}

/** 已解析的段值。 */
internal data class CronFields(
    val seconds: Set<Int>,
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    val daysOfWeek: Set<Int>,
    val dayOfMonthAny: Boolean,
    val dayOfWeekAny: Boolean,
)

/** cron 解析失败（携带字段名）。 */
public class CronFormatException(
    public val field: String,
) : IllegalArgumentException("invalid cron field: $field")

/** [CronExpression] 的解析入口。 */
public object Cron {
    private const val FIELD_COUNT = 5
    private const val FIELD_COUNT_WITH_SECONDS = 6
    private const val IDX_MINUTE = 0
    private const val IDX_HOUR = 1
    private const val IDX_DAY = 2
    private const val IDX_MONTH = 3
    private const val IDX_WEEK = 4
    private const val SECOND_MIN = 0
    private const val SECOND_MAX = 59
    private const val MINUTE_MIN = 0
    private const val MINUTE_MAX = 59
    private const val HOUR_MAX = 23
    private const val DAY_MIN = 1
    private const val DAY_MAX = 31
    private const val MONTH_MAX = 12
    private const val WEEK_MAX = 7
    private const val SUNDAY = SUNDAY_CRON

    /** 5 段形态的秒：整分触发。 */
    private val SECONDS_WHOLE_MINUTE: Set<Int> = setOf(0)

    /** 解析 5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周）cron；非法抛 [CronFormatException]。 */
    public fun parse(expr: String): CronExpression {
        val fields = expr.trim().split(Regex("\\s+"))
        val hasSeconds = fields.size == FIELD_COUNT_WITH_SECONDS
        if (fields.size != FIELD_COUNT && !hasSeconds) throw CronFormatException("count")
        val shift = if (hasSeconds) 1 else 0
        val seconds =
            if (hasSeconds) {
                parseField(fields[0], SECOND_MIN, SECOND_MAX, "second").values
            } else {
                SECONDS_WHOLE_MINUTE
            }
        val minutes = parseField(fields[IDX_MINUTE + shift], MINUTE_MIN, MINUTE_MAX, "minute").values
        val hours = parseField(fields[IDX_HOUR + shift], MINUTE_MIN, HOUR_MAX, "hour").values
        val dom = parseField(fields[IDX_DAY + shift], DAY_MIN, DAY_MAX, "dayOfMonth")
        val months = parseField(fields[IDX_MONTH + shift], DAY_MIN, MONTH_MAX, "month").values
        val dow = parseField(fields[IDX_WEEK + shift], DAY_MIN, WEEK_MAX, "week")
        return CronExpression(
            CronFields(
                seconds = seconds,
                minutes = minutes,
                hours = hours,
                daysOfMonth = dom.values,
                months = months,
                daysOfWeek = dow.values,
                dayOfMonthAny = dom.any,
                dayOfWeekAny = dow.any,
            ),
        )
    }

    private data class ParsedField(
        val values: Set<Int>,
        val any: Boolean,
    )

    /** 解析单段：`*`、`*`/n（步长）、`a-b`、`a-b/n`、`a`、`a/n`。 */
    private fun parseField(
        field: String,
        min: Int,
        max: Int,
        name: String,
    ): ParsedField {
        val values = sortedSetOf<Int>()
        var any = false
        for (part in field.split(",")) {
            val segments = part.split("/", limit = 2)
            val step = parseStep(segments.getOrNull(1), name)
            val range = parseRange(segments[0], step != null, min, max, name)
            if (range.lo == min && range.hi == max && step == null) any = true
            val (normLo, normHi) = normalizeWeekRange(range.lo, range.hi, name)
            if (normLo < min || normHi > max || normLo > normHi) throw CronFormatException(name)
            var v = normLo
            while (v <= normHi) {
                values += v
                v += step ?: 1
            }
        }
        return ParsedField(values, any)
    }

    private fun parseStep(
        stepText: String?,
        name: String,
    ): Int? =
        stepText?.let {
            it.toIntOrNull()?.takeIf { s -> s > 0 } ?: throw CronFormatException(name)
        }

    private data class Range(
        val lo: Int,
        val hi: Int,
    )

    private fun parseRange(
        rangeText: String,
        hasStep: Boolean,
        min: Int,
        max: Int,
        name: String,
    ): Range =
        when {
            rangeText == "*" -> Range(min, max)
            "-" in rangeText -> {
                val parts = rangeText.split("-", limit = 2)
                Range(
                    parts[0].toIntOrNull() ?: throw CronFormatException(name),
                    parts[1].toIntOrNull() ?: throw CronFormatException(name),
                )
            }
            else -> {
                val v = rangeText.toIntOrNull() ?: throw CronFormatException(name)
                // `a/n` 视为 a–max/n；`a` 单值。
                Range(v, if (hasStep) max else v)
            }
        }

    /** 周字段 0 归一到 7（周日）。 */
    private fun normalizeWeekRange(
        lo: Int,
        hi: Int,
        name: String,
    ): Pair<Int, Int> =
        if (name == "week") {
            (if (lo == 0) SUNDAY else lo) to (if (hi == 0) SUNDAY else hi)
        } else {
            lo to hi
        }
}

/** 周字段取值：周一=1 … 周日=7。 */
private fun toCronWeek(dayOfWeek: DayOfWeek): Int = if (dayOfWeek == DayOfWeek.SUNDAY) SUNDAY_CRON else dayOfWeek.value

private const val MAX_SEARCH_DAYS: Int = 2000
private const val MAX_YEAR: Int = 2999
private const val SUNDAY_CRON: Int = 7
