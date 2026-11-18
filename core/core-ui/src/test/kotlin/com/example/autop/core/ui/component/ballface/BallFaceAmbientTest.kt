package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BallFaceAmbientTest {
    @Test
    fun `sleep face floats a z and keeps the satellite slow`() {
        assertThat(SLEEP_EXPRESSION.floatingZ).isTrue()
        assertThat(SLEEP_EXPRESSION.satellite).isEqualTo(BallSatelliteMotion.Orbit(60f))
    }

    @Test
    fun `no reaction means no override and the pool takes over`() {
        assertThat(reactionOverrideOf(BallFaceMood.IDLE, null)).isNull()
    }

    @Test
    fun `status moods ignore reactions`() {
        assertThat(reactionOverrideOf(BallFaceMood.WAITING, BallReaction.TAP)).isNull()
        assertThat(reactionOverrideOf(BallFaceMood.RUNNING, BallReaction.FLING)).isNull()
    }

    @Test
    fun `reaction overrides the idle pool`() {
        assertThat(reactionOverrideOf(BallFaceMood.IDLE, BallReaction.TAP))
            .isEqualTo(reactionExpressionOf(BallReaction.TAP))
    }

    @Test
    fun `no reaction spins the satellite faster than thirty seconds or floats a z`() {
        BallReaction.entries.forEach { reaction ->
            val expression = reactionExpressionOf(reaction)
            val orbit = expression.satellite as BallSatelliteMotion.Orbit
            assertThat(orbit.periodSec).isAtLeast(30f)
            assertThat(expression.floatingZ).isFalse()
        }
    }

    @Test
    fun `satellite time stops during the pause and resumes without a jump`() {
        assertThat(pausedTimeOf(2.9f)).isWithin(1e-4f).of(pausedTimeOf(3.9f))
        assertThat(pausedTimeOf(4.5f) - pausedTimeOf(4.0f)).isWithin(1e-4f).of(0.5f)
        assertThat(pausedTimeOf(1.0f)).isWithin(1e-4f).of(1.0f)
    }

    @Test
    fun `floating z time is only drawn in motion and only for the sleep face`() {
        assertThat(ballFaceFrameOf(SLEEP_EXPRESSION, 1.2f, motion = true).zTime).isEqualTo(1.2f)
        assertThat(ballFaceFrameOf(SLEEP_EXPRESSION, 1.2f, motion = false).zTime).isNull()
        assertThat(ballFaceFrameOf(BallFaceExpressions.calm, 1.2f, motion = true).zTime).isNull()
    }
}
