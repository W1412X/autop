package com.example.autop.device.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class IntentTimeTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `datetime with offset keeps its own instant`() {
        val parsed = IntentTime.parse("2026-10-24T19:00+08:00", shanghai)
        assertThat(parsed).isEqualTo(ParsedTime(Instant.parse("2026-10-24T11:00:00Z").toEpochMilli(), dateOnly = false))
    }

    @Test
    fun `local datetime is read in the given zone`() {
        val parsed = IntentTime.parse("2026-10-24T19:00", shanghai)
        assertThat(parsed).isEqualTo(ParsedTime(Instant.parse("2026-10-24T11:00:00Z").toEpochMilli(), dateOnly = false))
    }

    @Test
    fun `date only is the start of that day in the given zone`() {
        val parsed = IntentTime.parse("2026-10-24", shanghai)
        assertThat(parsed).isEqualTo(ParsedTime(Instant.parse("2026-10-23T16:00:00Z").toEpochMilli(), dateOnly = true))
    }

    @Test
    fun `date only in utc starts at midnight utc`() {
        val parsed = IntentTime.parse("2026-10-24", ZoneOffset.UTC)
        assertThat(parsed).isEqualTo(ParsedTime(Instant.parse("2026-10-24T00:00:00Z").toEpochMilli(), dateOnly = true))
    }

    @Test
    fun `unparsable text returns null`() {
        assertThat(IntentTime.parse("明天", shanghai)).isNull()
        assertThat(IntentTime.parse("2026-13-01T10:00", shanghai)).isNull()
        assertThat(IntentTime.parse("", shanghai)).isNull()
    }
}
