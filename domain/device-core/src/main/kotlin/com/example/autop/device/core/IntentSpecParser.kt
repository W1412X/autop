/**
 * `intent` 参数校验（intent-tool §1.2）：纯函数，不读系统时区也不读配置。
 * 模型常把数字写成字符串，这里统一按文本读取后再转换。
 */
package com.example.autop.device.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/** 校验 `intent` 工具参数。 */
public object IntentSpecParser {
    /**
     * 校验 [args]。[knownMapIds] 来自导航规则表（`map` 的合法取值），[zone] 由调用方传入，
     * 用于解析不带偏移的时间与“仅日期”。
     */
    public fun parse(
        args: JsonObject,
        limits: IntentLimits,
        knownMapIds: Set<String>,
        zone: ZoneId,
    ): IntentParse = Parser(limits, knownMapIds, zone).parse(args)
}

private class Parser(
    private val limits: IntentLimits,
    private val knownMapIds: Set<String>,
    private val zone: ZoneId,
) {
    fun parse(args: JsonObject): IntentParse {
        val kind = args.text("kind") ?: return fail(IntentInvalidReason.MISSING_KIND, detail = KIND_IDS)
        return when (kind) {
            "alarm" -> alarm(args)
            "timer" -> timer(args)
            "calendar" -> calendar(args)
            "dial" -> dial(args)
            "sms_draft" -> smsDraft(args)
            "share" -> share(args)
            "navigate" -> navigate(args)
            "settings" -> settings(args)
            else -> fail(IntentInvalidReason.UNKNOWN_KIND, value = kind, detail = KIND_IDS)
        }
    }

    private fun alarm(args: JsonObject): IntentParse {
        val hourRaw = args.text("hour") ?: return fail(IntentInvalidReason.ALARM_NEEDS_HOUR)
        val minuteRaw = args.text("minute")
        val hour = hourRaw.toIntOrNull()
        val minute = if (minuteRaw == null) 0 else minuteRaw.toIntOrNull()
        val hourOk = hour?.let { it in HOUR_RANGE } == true
        val minuteOk = minute?.let { it in MINUTE_RANGE } == true
        if (!hourOk || !minuteOk) {
            return fail(IntentInvalidReason.BAD_TIME, value = "$hourRaw:${minuteRaw.orEmpty()}")
        }
        val days = daysOf(args) ?: return fail(IntentInvalidReason.BAD_DAYS)
        return ok(IntentSpec.Alarm(hour, minute, args.text("message"), days))
    }

    private fun timer(args: JsonObject): IntentParse {
        val raw = args.text("seconds") ?: return fail(IntentInvalidReason.TIMER_NEEDS_SECONDS)
        val seconds = raw.toIntOrNull()
        if (seconds == null || seconds !in 1..limits.timerMaxSeconds) {
            return fail(IntentInvalidReason.TIMER_RANGE, value = raw, detail = limits.timerMaxSeconds.toString())
        }
        return ok(IntentSpec.Timer(seconds, args.text("message")))
    }

    private fun calendar(args: JsonObject): IntentParse {
        val title = args.text("title") ?: return fail(IntentInvalidReason.CALENDAR_NEEDS_TITLE)
        val beginRaw = args.text("begin") ?: return fail(IntentInvalidReason.CALENDAR_NEEDS_BEGIN)
        val begin = IntentTime.parse(beginRaw, zone) ?: return fail(IntentInvalidReason.BAD_BEGIN, value = beginRaw)
        val allDay = args.bool("all_day") ?: begin.dateOnly
        val endRaw = args.text("end")
        val endMs: Long =
            if (endRaw == null) {
                defaultEndMs(begin, allDay)
            } else {
                val end = IntentTime.parse(endRaw, zone) ?: return fail(IntentInvalidReason.BAD_END, value = endRaw)
                end.ms
            }
        if (endMs < begin.ms) return fail(IntentInvalidReason.END_BEFORE_BEGIN)
        val description = args.text("description")
        if (description != null) checkLength(description)?.let { return it }
        return ok(IntentSpec.Calendar(title, begin.ms, endMs, allDay, args.text("location"), description))
    }

    /** 缺省结束时间：全天加一天；否则加 `calendarDefaultMinutes`。 */
    private fun defaultEndMs(
        begin: ParsedTime,
        allDay: Boolean,
    ): Long =
        if (allDay) {
            Instant
                .ofEpochMilli(begin.ms)
                .atZone(zone)
                .plusDays(1)
                .toInstant()
                .toEpochMilli()
        } else {
            begin.ms + limits.calendarDefaultMinutes * MILLIS_PER_MINUTE
        }

    private fun dial(args: JsonObject): IntentParse {
        val raw = args.text("number") ?: return fail(IntentInvalidReason.DIAL_NEEDS_NUMBER)
        val number = phoneOf(raw) ?: return fail(IntentInvalidReason.BAD_NUMBER, value = raw)
        return ok(IntentSpec.Dial(number))
    }

    private fun smsDraft(args: JsonObject): IntentParse {
        val text = args.text("text") ?: return fail(IntentInvalidReason.SMS_NEEDS_TEXT)
        checkLength(text)?.let { return it }
        val numberRaw = args.text("number")
        val number =
            if (numberRaw == null) {
                null
            } else {
                phoneOf(numberRaw) ?: return fail(IntentInvalidReason.BAD_NUMBER, value = numberRaw)
            }
        return ok(IntentSpec.SmsDraft(number, text))
    }

    private fun share(args: JsonObject): IntentParse {
        val text = args.text("text") ?: return fail(IntentInvalidReason.SHARE_NEEDS_TEXT)
        checkLength(text)?.let { return it }
        return ok(IntentSpec.Share(text, args.text("app")))
    }

    private fun navigate(args: JsonObject): IntentParse {
        val destination = args.text("destination")
        val latRaw = args.text("lat")
        val lngRaw = args.text("lng")
        val modeRaw = args.text("mode")
        val map = args.text("map")
        val lat = latRaw?.toDoubleOrNull()
        val lng = lngRaw?.toDoubleOrNull()
        navigateProblem(destination, latRaw, lngRaw, modeRaw, map)?.let { return IntentParse.Invalid(it) }
        val mode = modeRaw?.let { modeOf(it) } ?: NavMode.DRIVE
        return ok(IntentSpec.Navigate(destination, lat, lng, mode, map))
    }

    /** 导航参数的第一条校验失败（按固定顺序）；全部通过返回 null。 */
    private fun navigateProblem(
        destination: String?,
        latRaw: String?,
        lngRaw: String?,
        modeRaw: String?,
        map: String?,
    ): IntentInvalid? =
        when {
            (latRaw == null) != (lngRaw == null) -> IntentInvalid(IntentInvalidReason.COORD_PAIR)
            destination == null && latRaw == null -> IntentInvalid(IntentInvalidReason.NAV_NEEDS_TARGET)
            latRaw != null && lngRaw != null && !coordsInRange(latRaw.toDoubleOrNull(), lngRaw.toDoubleOrNull()) ->
                IntentInvalid(IntentInvalidReason.COORD_RANGE, value = "$latRaw,$lngRaw")
            modeRaw != null && modeOf(modeRaw) == null ->
                IntentInvalid(IntentInvalidReason.BAD_MODE, value = modeRaw, detail = MODE_IDS)
            map != null && map !in knownMapIds ->
                IntentInvalid(
                    IntentInvalidReason.BAD_MAP,
                    value = map,
                    detail = knownMapIds.sorted().joinToString(", "),
                )
            else -> null
        }

    private fun coordsInRange(
        lat: Double?,
        lng: Double?,
    ): Boolean = lat != null && lng != null && lat in LAT_RANGE && lng in LNG_RANGE

    private fun settings(args: JsonObject): IntentParse {
        val pageRaw = args.text("page") ?: return fail(IntentInvalidReason.SETTINGS_NEEDS_PAGE, detail = PAGE_IDS)
        val page =
            SettingsPage.entries.firstOrNull { it.id == pageRaw }
                ?: return fail(IntentInvalidReason.BAD_PAGE, value = pageRaw, detail = PAGE_IDS)
        val app = args.text("app")
        if (page == SettingsPage.APP_NOTIFICATIONS && app == null) return fail(IntentInvalidReason.SETTINGS_NEEDS_APP)
        return ok(IntentSpec.Settings(page, app))
    }

    private fun checkLength(text: String): IntentParse? =
        if (text.length > limits.textMaxChars) {
            fail(IntentInvalidReason.TEXT_TOO_LONG, detail = limits.textMaxChars.toString())
        } else {
            null
        }

    private fun daysOf(args: JsonObject): List<Int>? {
        val element = args[DAYS_KEY] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        if (element !is JsonArray) return null
        val values =
            element.map { item ->
                (item as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()
                    ?: return null
            }
        if (values.any { it !in WEEKDAY_RANGE }) return null
        return values.distinct().sorted()
    }

    private fun phoneOf(raw: String): String? {
        if (raw.any { it == '*' || it == '#' }) return null
        return if (PHONE_PATTERN.matches(raw)) raw.replace(" ", "") else null
    }

    private fun modeOf(raw: String): NavMode? = NavMode.entries.firstOrNull { it.id == raw }

    private fun ok(spec: IntentSpec): IntentParse = IntentParse.Ok(spec)

    private fun fail(
        reason: IntentInvalidReason,
        value: String? = null,
        detail: String? = null,
    ): IntentParse = IntentParse.Invalid(IntentInvalid(reason, value, detail))

    private companion object {
        const val DAYS_KEY = "days"
        const val MILLIS_PER_MINUTE = 60_000L
        val HOUR_RANGE = 0..23
        val MINUTE_RANGE = 0..59
        val WEEKDAY_RANGE = 1..7
        val LAT_RANGE = -90.0..90.0
        val LNG_RANGE = -180.0..180.0

        /** 电话号码：可选 +，首位数字，之后允许空格、括号、连字符；服务码（* #）由调用方另行拒绝。 */
        val PHONE_PATTERN = Regex("^\\+?[0-9][0-9 ()\\-]{1,30}$")
        const val KIND_IDS = "alarm, timer, calendar, dial, sms_draft, share, navigate, settings"
        val MODE_IDS = NavMode.entries.joinToString(", ") { it.id }
        val PAGE_IDS = SettingsPage.entries.joinToString(", ") { it.id }
    }
}

/** 文本参数：空白或缺失视为未提供；JSON null 也视为未提供。 */
private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** 布尔参数：true/false、1/0（大小写不敏感）；其它取值视为未提供。 */
private fun JsonObject.bool(name: String): Boolean? =
    text(name)?.let { value ->
        when {
            value.equals("true", ignoreCase = true) || value == "1" -> true
            value.equals("false", ignoreCase = true) || value == "0" -> false
            else -> null
        }
    }
