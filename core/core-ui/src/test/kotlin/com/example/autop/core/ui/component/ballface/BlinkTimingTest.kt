package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class BlinkTimingTest {
    @Test
    fun `blink gap stays between two and six seconds`() {
        val random = Random(42)
        repeat(1_000) {
            val gap = BallFaceDirector.blinkGapSec(random)
            assertThat(gap).isAtLeast(BallFaceDirector.BLINK_MIN_GAP_SEC)
            assertThat(gap).isAtMost(BallFaceDirector.BLINK_MAX_GAP_SEC)
        }
    }

    @Test
    fun `blink gap sequence repeats for the same random seed`() {
        val first = List(5) { BallFaceDirector.blinkGapSec(Random(7)) }
        val second = List(5) { BallFaceDirector.blinkGapSec(Random(7)) }
        assertThat(first).isEqualTo(second)
    }
}
