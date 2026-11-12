package com.example.autop.device.core

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class IntentSpecParserTest {
    private val limits = IntentLimits(timerMaxSeconds = 86400, calendarDefaultMinutes = 60, textMaxChars = 20)
    private val maps = setOf("amap", "baidu", "tencent", "google")
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun parse(json: String): IntentParse =
        IntentSpecParser.parse(Json.parseToJsonElement(json).jsonObject, limits, maps, zone)

    private fun reasonOf(json: String): IntentInvalidReason =
        (parse(json) as IntentParse.Invalid).error.reason

    private fun specOf(json: String): IntentSpec = (parse(json) as IntentParse.Ok).spec

    @Test
    fun `missing kind is reported with the kind list`() {
        val result = parse("{}") as IntentParse.Invalid
        assertThat(result.error.reason).isEqualTo(IntentInvalidReason.MISSING_KIND)
        assertThat(result.error.detail).contains("sms_draft")
    }

    @Test
    fun `unknown kind keeps the rejected value`() {
        val result = parse("""{"kind":"install"}""") as IntentParse.Invalid
        assertThat(result.error.reason).isEqualTo(IntentInvalidReason.UNKNOWN_KIND)
        assertThat(result.error.value).isEqualTo("install")
    }

    @Test
    fun `alarm defaults minute to zero and sorts unique weekdays`() {
        val spec = specOf("""{"kind":"alarm","hour":"7","days":[3,1,3]}""") as IntentSpec.Alarm
        assertThat(spec.hour).isEqualTo(7)
        assertThat(spec.minute).isEqualTo(0)
        assertThat(spec.days).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `alarm needs hour`() {
        assertThat(reasonOf("""{"kind":"alarm"}""")).isEqualTo(IntentInvalidReason.ALARM_NEEDS_HOUR)
    }

    @Test
    fun `alarm rejects out of range time`() {
        assertThat(reasonOf("""{"kind":"alarm","hour":24}""")).isEqualTo(IntentInvalidReason.BAD_TIME)
        assertThat(reasonOf("""{"kind":"alarm","hour":6,"minute":60}""")).isEqualTo(IntentInvalidReason.BAD_TIME)
    }

    @Test
    fun `alarm rejects weekday outside one to seven`() {
        assertThat(reasonOf("""{"kind":"alarm","hour":6,"days":[0]}""")).isEqualTo(IntentInvalidReason.BAD_DAYS)
        assertThat(reasonOf("""{"kind":"alarm","hour":6,"days":[8]}""")).isEqualTo(IntentInvalidReason.BAD_DAYS)
        assertThat(reasonOf("""{"kind":"alarm","hour":6,"days":2}""")).isEqualTo(IntentInvalidReason.BAD_DAYS)
    }

    @Test
    fun `timer needs seconds within the configured maximum`() {
        assertThat(reasonOf("""{"kind":"timer"}""")).isEqualTo(IntentInvalidReason.TIMER_NEEDS_SECONDS)
        assertThat(reasonOf("""{"kind":"timer","seconds":0}""")).isEqualTo(IntentInvalidReason.TIMER_RANGE)
        assertThat(reasonOf("""{"kind":"timer","seconds":86401}""")).isEqualTo(IntentInvalidReason.TIMER_RANGE)
        assertThat((specOf("""{"kind":"timer","seconds":"90"}""") as IntentSpec.Timer).seconds).isEqualTo(90)
    }

    @Test
    fun `calendar with datetime defaults end to the configured duration`() {
        val spec =
            specOf("""{"kind":"calendar","title":"评审","begin":"2026-10-24T19:00"}""") as IntentSpec.Calendar
        val begin = Instant.parse("2026-10-24T11:00:00Z").toEpochMilli()
        assertThat(spec.beginMs).isEqualTo(begin)
        assertThat(spec.endMs).isEqualTo(begin + 60 * 60_000L)
        assertThat(spec.allDay).isFalse()
    }

    @Test
    fun `calendar date only is all day and defaults to one day`() {
        val spec = specOf("""{"kind":"calendar","title":"出差","begin":"2026-10-24"}""") as IntentSpec.Calendar
        assertThat(spec.allDay).isTrue()
        assertThat(spec.endMs - spec.beginMs).isEqualTo(24 * 60 * 60_000L)
    }

    @Test
    fun `calendar explicit all_day false keeps the given time`() {
        val spec =
            specOf("""{"kind":"calendar","title":"x","begin":"2026-10-24","all_day":"false"}""") as IntentSpec.Calendar
        assertThat(spec.allDay).isFalse()
    }

    @Test
    fun `calendar reports missing and invalid times`() {
        assertThat(reasonOf("""{"kind":"calendar","begin":"2026-10-24"}""")).isEqualTo(IntentInvalidReason.CALENDAR_NEEDS_TITLE)
        assertThat(reasonOf("""{"kind":"calendar","title":"x"}""")).isEqualTo(IntentInvalidReason.CALENDAR_NEEDS_BEGIN)
        assertThat(reasonOf("""{"kind":"calendar","title":"x","begin":"明天"}""")).isEqualTo(IntentInvalidReason.BAD_BEGIN)
        assertThat(reasonOf("""{"kind":"calendar","title":"x","begin":"2026-10-24","end":"later"}"""))
            .isEqualTo(IntentInvalidReason.BAD_END)
        assertThat(reasonOf("""{"kind":"calendar","title":"x","begin":"2026-10-24T19:00","end":"2026-10-24T18:00"}"""))
            .isEqualTo(IntentInvalidReason.END_BEFORE_BEGIN)
    }

    @Test
    fun `calendar rejects over long description`() {
        val long = "a".repeat(21)
        assertThat(reasonOf("""{"kind":"calendar","title":"x","begin":"2026-10-24","description":"$long"}"""))
            .isEqualTo(IntentInvalidReason.TEXT_TOO_LONG)
    }

    @Test
    fun `dial strips spaces and rejects service codes`() {
        assertThat((specOf("""{"kind":"dial","number":"+86 138 0013 8000"}""") as IntentSpec.Dial).number)
            .isEqualTo("+8613800138000")
        assertThat(reasonOf("""{"kind":"dial"}""")).isEqualTo(IntentInvalidReason.DIAL_NEEDS_NUMBER)
        assertThat(reasonOf("""{"kind":"dial","number":"*100#"}""")).isEqualTo(IntentInvalidReason.BAD_NUMBER)
        assertThat(reasonOf("""{"kind":"dial","number":"abc"}""")).isEqualTo(IntentInvalidReason.BAD_NUMBER)
    }

    @Test
    fun `sms draft needs text and a valid optional number`() {
        assertThat(reasonOf("""{"kind":"sms_draft","number":"10086"}""")).isEqualTo(IntentInvalidReason.SMS_NEEDS_TEXT)
        assertThat(reasonOf("""{"kind":"sms_draft","text":"hi","number":"#06#"}""")).isEqualTo(IntentInvalidReason.BAD_NUMBER)
        assertThat((specOf("""{"kind":"sms_draft","text":"hi"}""") as IntentSpec.SmsDraft).number).isNull()
        assertThat(reasonOf("""{"kind":"sms_draft","text":"${"b".repeat(21)}"}"""))
            .isEqualTo(IntentInvalidReason.TEXT_TOO_LONG)
    }

    @Test
    fun `share needs text`() {
        assertThat(reasonOf("""{"kind":"share"}""")).isEqualTo(IntentInvalidReason.SHARE_NEEDS_TEXT)
        assertThat((specOf("""{"kind":"share","text":"hi","app":"微信"}""") as IntentSpec.Share).app).isEqualTo("微信")
    }

    @Test
    fun `navigate defaults to driving and accepts a known map`() {
        val spec = specOf("""{"kind":"navigate","destination":"北京站","map":"baidu"}""") as IntentSpec.Navigate
        assertThat(spec.mode).isEqualTo(NavMode.DRIVE)
        assertThat(spec.map).isEqualTo("baidu")
        assertThat(spec.lat).isNull()
    }

    @Test
    fun `navigate needs a destination or a coordinate pair`() {
        assertThat(reasonOf("""{"kind":"navigate"}""")).isEqualTo(IntentInvalidReason.NAV_NEEDS_TARGET)
        assertThat(reasonOf("""{"kind":"navigate","lat":39.9}""")).isEqualTo(IntentInvalidReason.COORD_PAIR)
        assertThat(reasonOf("""{"kind":"navigate","destination":"x","lng":116.4}""")).isEqualTo(IntentInvalidReason.COORD_PAIR)
    }

    @Test
    fun `navigate checks coordinate range`() {
        assertThat(reasonOf("""{"kind":"navigate","lat":91,"lng":10}""")).isEqualTo(IntentInvalidReason.COORD_RANGE)
        assertThat(reasonOf("""{"kind":"navigate","lat":10,"lng":181}""")).isEqualTo(IntentInvalidReason.COORD_RANGE)
    }

    @Test
    fun `navigate rejects unknown mode and map`() {
        assertThat(reasonOf("""{"kind":"navigate","destination":"x","mode":"fly"}""")).isEqualTo(IntentInvalidReason.BAD_MODE)
        assertThat(reasonOf("""{"kind":"navigate","destination":"x","map":"yandex"}""")).isEqualTo(IntentInvalidReason.BAD_MAP)
    }

    @Test
    fun `settings needs a listed page and an app for app notifications`() {
        assertThat(reasonOf("""{"kind":"settings"}""")).isEqualTo(IntentInvalidReason.SETTINGS_NEEDS_PAGE)
        assertThat(reasonOf("""{"kind":"settings","page":"accessibility"}""")).isEqualTo(IntentInvalidReason.BAD_PAGE)
        assertThat(reasonOf("""{"kind":"settings","page":"app_notifications"}""")).isEqualTo(IntentInvalidReason.SETTINGS_NEEDS_APP)
        assertThat((specOf("""{"kind":"settings","page":"wifi"}""") as IntentSpec.Settings).page).isEqualTo(SettingsPage.WIFI)
    }

    @Test
    fun `json null is treated as missing`() {
        val obj: JsonObject = Json.parseToJsonElement("""{"kind":"timer","seconds":null}""").jsonObject
        val result = IntentSpecParser.parse(obj, limits, maps, zone) as IntentParse.Invalid
        assertThat(result.error.reason).isEqualTo(IntentInvalidReason.TIMER_NEEDS_SECONDS)
    }
}
