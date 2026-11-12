/**
 * `intent` 工具的时间解析（intent-tool §6.1）：纯函数，时区由调用方传入。
 */
package com.example.autop.device.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** 解析结果：[ms] 为 Unix 毫秒；[dateOnly] 表示输入只写了日期（全天）。 */
public data class ParsedTime(
    val ms: Long,
    val dateOnly: Boolean,
)

/** 时间文本解析：带偏移的日期时间、本地日期时间、仅日期，依次尝试。 */
public object IntentTime {
    /** 无法解析返回 null。 */
    public fun parse(
        text: String,
        zone: ZoneId,
    ): ParsedTime? =
        withOffset(text)
            ?: withLocalTime(text, zone)
            ?: dateOnly(text, zone)

    private fun withOffset(text: String): ParsedTime? =
        attempt { OffsetDateTime.parse(text).toInstant().toEpochMilli() }?.let { ParsedTime(it, dateOnly = false) }

    private fun withLocalTime(
        text: String,
        zone: ZoneId,
    ): ParsedTime? =
        attempt {
            LocalDateTime
                .parse(text)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }?.let { ParsedTime(it, dateOnly = false) }

    private fun dateOnly(
        text: String,
        zone: ZoneId,
    ): ParsedTime? =
        attempt {
            LocalDate
                .parse(text)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }?.let { ParsedTime(it, dateOnly = true) }

    /** 解析失败是预期中的输入错误（调用方给出提示即可），只吞 [DateTimeException]，不需要保留异常。 */
    @Suppress("SwallowedException")
    private inline fun attempt(block: () -> Long): Long? =
        try {
            block()
        } catch (unparsable: DateTimeException) {
            null
            null
        }
}
