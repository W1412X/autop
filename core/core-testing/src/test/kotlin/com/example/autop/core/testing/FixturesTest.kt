package com.example.autop.core.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FixturesTest {
    @Test
    fun `loads a fixture from the test resources fixtures directory`() {
        assertThat(Fixtures.load("sample.txt").trim()).isEqualTo("fixture sample text")
    }

    @Test
    fun `missing fixture is a failure`() {
        val e = assertThrows(IllegalStateException::class.java) { Fixtures.load("ghost.txt") }
        assertThat(e).hasMessageThat().contains("fixture not found")
    }
}
