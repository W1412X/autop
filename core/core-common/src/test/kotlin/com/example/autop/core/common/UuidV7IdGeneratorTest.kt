package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class UuidV7IdGeneratorTest {
    private class MutableClock(
        var millis: Long,
    ) : Clock {
        override fun nowMillis(): Long = millis
    }

    @Test
    fun `generated id has uuid format`() {
        val id = UuidV7IdGenerator(MutableClock(1_700_000_000_000), Random(1)).newId()
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }

    @Test
    fun `ids are time ordered`() {
        val clock = MutableClock(1_000_000)
        val generator = UuidV7IdGenerator(clock, Random(2))
        val first = generator.newId()
        clock.millis += 5_000
        val second = generator.newId()
        clock.millis += 5_000
        val third = generator.newId()
        assertThat(first < second).isTrue()
        assertThat(second < third).isTrue()
    }

    @Test
    fun `ids at same millis are unique`() {
        val clock = MutableClock(42)
        val generator = UuidV7IdGenerator(clock, Random(3))
        val ids = List(1_000) { generator.newId() }
        assertThat(ids.toSet()).hasSize(1_000)
    }

    @Test
    fun `timestamp part decodes clock millis`() {
        val clock = MutableClock(1_700_000_000_123)
        val id = UuidV7IdGenerator(clock, Random(4)).newId()
        val tsHex = id.substring(0, 13).replace("-", "")
        assertThat(tsHex.toLong(16)).isEqualTo(1_700_000_000_123L)
    }
}
