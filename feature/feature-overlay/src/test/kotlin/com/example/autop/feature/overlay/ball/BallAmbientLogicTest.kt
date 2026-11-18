package com.example.autop.feature.overlay.ball

import com.example.autop.core.config.UiBallAmbientSection
import com.example.autop.core.ui.component.ballface.BallAmbientMode
import com.example.autop.core.ui.component.ballface.BallReaction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BallAmbientLogicTest {
    private val cfg = UiBallAmbientSection()

    @Test
    fun `night window wraps past midnight`() {
        assertThat(isNightHour(23, cfg)).isTrue()
        assertThat(isNightHour(0, cfg)).isTrue()
        assertThat(isNightHour(5, cfg)).isTrue()
        assertThat(isNightHour(6, cfg)).isFalse()
        assertThat(isNightHour(14, cfg)).isFalse()
        assertThat(isNightHour(22, cfg)).isFalse()
    }

    @Test
    fun `ball sleeps in the night window and never in the daytime`() {
        assertThat(ambientOf(2, false, 80, cfg)).isEqualTo(BallAmbientMode.SLEEP)
        assertThat(ambientOf(14, false, 80, cfg)).isEqualTo(BallAmbientMode.NONE)
    }

    @Test
    fun `sleep beats charging, charging beats low battery`() {
        assertThat(ambientOf(2, true, 10, cfg)).isEqualTo(BallAmbientMode.SLEEP)
        assertThat(ambientOf(14, true, 10, cfg)).isEqualTo(BallAmbientMode.CHARGING)
        assertThat(ambientOf(14, false, 19, cfg)).isEqualTo(BallAmbientMode.TIRED)
        assertThat(ambientOf(14, false, 20, cfg)).isEqualTo(BallAmbientMode.NONE)
    }

    @Test
    fun `unknown battery level is never treated as low`() {
        assertThat(ambientOf(14, false, null, cfg)).isEqualTo(BallAmbientMode.NONE)
    }

    @Test
    fun `morning greeting is due once per day inside the morning window`() {
        assertThat(morningGreetDue(7, null, 100, cfg)).isTrue()
        assertThat(morningGreetDue(7, 100, 100, cfg)).isFalse()
        assertThat(morningGreetDue(7, 99, 100, cfg)).isTrue()
        assertThat(morningGreetDue(9, null, 100, cfg)).isFalse()
        assertThat(morningGreetDue(5, null, 100, cfg)).isFalse()
    }

    @Test
    fun `touch while sleeping only wakes the ball`() {
        val sequence = touchReactionsOf(BallReaction.TAP, BallAmbientMode.SLEEP, morningDue = true)
        assertThat(sequence).containsExactly(BallReaction.WAKE)
    }

    @Test
    fun `first touch in the morning yawns and then greets`() {
        val sequence = touchReactionsOf(BallReaction.TAP, BallAmbientMode.NONE, morningDue = true)
        assertThat(sequence).containsExactly(BallReaction.YAWN, BallReaction.GREET).inOrder()
    }

    @Test
    fun `ordinary touch plays its own reaction`() {
        val sequence = touchReactionsOf(BallReaction.LONG_PRESS, BallAmbientMode.CHARGING, morningDue = false)
        assertThat(sequence).containsExactly(BallReaction.LONG_PRESS)
    }

    @Test
    fun `drag has no timer and fling needs the configured speed`() {
        assertThat(reactionDurationMs(BallReaction.DRAG, cfg)).isNull()
        assertThat(isFling(1.2f, cfg)).isTrue()
        assertThat(isFling(0.5f, cfg)).isFalse()
    }
}
