package com.example.autop.core.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeClockTest {
    @Test
    fun `starts at the given timestamp`() {
        assertThat(FakeClock(1000L).nowMillis()).isEqualTo(1000L)
        assertThat(FakeClock().nowMillis()).isEqualTo(0L)
    }

    @Test
    fun `advanceBy moves the clock forward`() {
        val clock = FakeClock(1000L)
        clock.advanceBy(500L)
        assertThat(clock.nowMillis()).isEqualTo(1500L)
    }
}
