package com.example.autop.device.impl.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnInteractionWindowTest {
    @Test
    fun `nothing is covered before any own interaction`() {
        assertThat(OwnInteractionWindow().covers(time = 1_000, suppressMs = 800)).isFalse()
    }

    @Test
    fun `events within the window after an own interaction are covered`() {
        val window = OwnInteractionWindow().apply { note(1_000) }

        assertThat(window.covers(1_000, 800)).isTrue()
        assertThat(window.covers(1_800, 800)).isTrue()
    }

    @Test
    fun `events after the window or before the interaction are not covered`() {
        val window = OwnInteractionWindow().apply { note(1_000) }

        assertThat(window.covers(1_801, 800)).isFalse()
        assertThat(window.covers(999, 800)).isFalse()
    }

    @Test
    fun `a newer interaction extends the window`() {
        val window = OwnInteractionWindow().apply { note(1_000) }
        window.note(1_700)

        assertThat(window.covers(2_400, 800)).isTrue()
    }
}
