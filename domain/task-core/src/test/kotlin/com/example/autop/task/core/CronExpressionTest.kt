package com.example.autop.task.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** [Cron] 解析与 [CronExpression.next] 单测（docs/tech/07 §2）。 */
internal class CronExpressionTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        LocalDateTime
            .parse(text)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun nextOf(
        expr: String,
        from: String,
    ): String {
        val millis = checkNotNull(Cron.parse(expr).next(at(from), zone))
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).toString()
    }

    @Test
    fun everyDayAtEight() {
        assertThat(nextOf("0 8 * * *", "2026-10-13T09:00")).isEqualTo("2026-10-14T08:00")
    }

    @Test
    fun everyMinuteOnHour() {
        assertThat(nextOf("0 * * * *", "2026-10-13T08:30")).isEqualTo("2026-10-13T09:00")
    }

    @Test
    fun stepMinutes() {
        assertThat(nextOf("*/15 * * * *", "2026-10-13T08:07")).isEqualTo("2026-10-13T08:15")
    }

    @Test
    fun crossesMonth() {
        assertThat(nextOf("0 0 1 * *", "2026-10-13T08:00")).isEqualTo("2026-11-01T00:00")
    }

    @Test
    fun leapDay() {
        assertThat(nextOf("0 0 29 2 *", "2026-03-01T00:00")).isEqualTo("2028-02-29T00:00")
    }

    @Test
    fun neverMatchesReturnsNull() {
        assertThat(Cron.parse("0 0 30 2 *").next(at("2026-03-01T00:00"), zone)).isNull()
    }

    @Test
    fun weekdayOnly() {
        // 2026-10-13 是周二；下一个周五 08:00。
        assertThat(nextOf("0 8 * * 5", "2026-10-13T08:00")).isEqualTo("2026-10-16T08:00")
    }

    @Test
    fun sundayAcceptsZero() {
        assertThat(nextOf("0 8 * * 0", "2026-10-13T08:00")).isEqualTo("2026-10-18T08:00")
    }

    @Test
    fun dayAndWeekAreOred() {
        // 日=15 或 周=1（周一）：10-13 周二之后先到 10-19 周一（日 15 在 10-15 更早）。
        assertThat(nextOf("0 8 15 * 1", "2026-10-13T08:00")).isEqualTo("2026-10-15T08:00")
        assertThat(nextOf("0 8 15 * 1", "2026-10-15T09:00")).isEqualTo("2026-10-19T08:00")
    }

    @Test
    fun listAndRangeFields() {
        assertThat(nextOf("0 8,20 * * *", "2026-10-13T09:00")).isEqualTo("2026-10-13T20:00")
        assertThat(nextOf("0 8 * * 1-5", "2026-10-17T09:00")).isEqualTo("2026-10-19T08:00")
    }

    @Test
    fun dstSpringForwardSkipsMissingHour() {
        // 纽约 2026-03-08 02:00 不存在（跳到 03:00）。
        val tz = ZoneId.of("America/New_York")
        val from =
            LocalDateTime
                .parse("2026-03-08T01:30")
                .atZone(tz)
                .toInstant()
                .toEpochMilli()
        val millis = checkNotNull(Cron.parse("30 2 * * *").next(from, tz))
        val hit = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), tz)
        // 不存在的 02:30 会落到 03:30（时钟跳变后的墙钟映射）。
        assertThat(hit.toString()).isEqualTo("2026-03-08T03:30")
    }

    @Test
    fun sixFieldsAddSeconds() {
        assertThat(nextOf("30 0 8 * * *", "2026-10-13T08:00:00")).isEqualTo("2026-10-13T08:00:30")
        assertThat(nextOf("*/20 * * * * *", "2026-10-13T08:00:05")).isEqualTo("2026-10-13T08:00:20")
    }

    @Test
    fun secondGranularitySkipsPastInstant() {
        assertThat(nextOf("30 30 8 * * *", "2026-10-13T08:30:31")).isEqualTo("2026-10-14T08:30:30")
    }

    @Test
    fun fiveFieldsFireOnWholeMinute() {
        assertThat(nextOf("0 8 * * *", "2026-10-13T08:00:30")).isEqualTo("2026-10-14T08:00")
    }

    @Test(expected = CronFormatException::class)
    fun rejectsBadSecond() {
        Cron.parse("61 0 8 * * *")
    }

    @Test(expected = CronFormatException::class)
    fun rejectsSevenFields() {
        Cron.parse("* * * * * * *")
    }

    @Test(expected = CronFormatException::class)
    fun rejectsBadField() {
        Cron.parse("61 * * * *")
    }

    @Test(expected = CronFormatException::class)
    fun rejectsWrongFieldCount() {
        Cron.parse("* * *")
    }
}
