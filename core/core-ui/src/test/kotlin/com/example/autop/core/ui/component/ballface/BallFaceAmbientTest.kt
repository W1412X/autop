package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BallFaceAmbientTest {
    @Test
    fun `sleep face floats a z and keeps the satellite slow`() {
        val sleep = ambientExpressionOf(BallAmbientMode.SLEEP)
        assertThat(sleep?.floatingZ).isTrue()
        assertThat(sleep?.satellite).isEqualTo(BallSatelliteMotion.Orbit(60f))
    }

    @Test
    fun `no ambient mode and no reaction leave the idle pool in charge`() {
        assertThat(ambientExpressionOf(BallAmbientMode.NONE)).isNull()
        assertThat(idleOverrideOf(BallFaceMood.IDLE, BallAmbientMode.NONE, null)).isNull()
    }

    @Test
    fun `status moods ignore reactions and ambient modes`() {
        assertThat(idleOverrideOf(BallFaceMood.WAITING, BallAmbientMode.SLEEP, BallReaction.TAP)).isNull()
        assertThat(idleOverrideOf(BallFaceMood.RUNNING, BallAmbientMode.CHARGING, null)).isNull()
    }

    @Test
    fun `reaction takes priority over ambient mode while idle`() {
        assertThat(idleOverrideOf(BallFaceMood.IDLE, BallAmbientMode.SLEEP, BallReaction.TAP))
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
    fun `floating z phase is only drawn in motion and only for the sleep face`() {
        val sleep = ambientExpressionOf(BallAmbientMode.SLEEP)!!
        assertThat(ballFaceFrameOf(sleep, 1.2f, motion = true).zPhase).isWithin(1e-4f).of(0.5f)
        assertThat(ballFaceFrameOf(sleep, 1.2f, motion = false).zPhase).isNull()
        val calm = BallFaceExpressions.calm
        assertThat(ballFaceFrameOf(calm, 1.2f, motion = true).zPhase).isNull()
    }
}
