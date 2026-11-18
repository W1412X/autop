package com.example.autop.feature.overlay.ball

import com.example.autop.core.config.UiBallAmbientSection
import com.example.autop.core.ui.component.ballface.BallPoolContext
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
    }

    @Test
    fun `pool context reads the time window`() {
        val night = poolContextOf(2, false, 80, false, false, cfg)
        assertThat(night).isEqualTo(BallPoolContext(night = true))
        val morning = poolContextOf(7, false, 80, false, false, cfg)
        assertThat(morning).isEqualTo(BallPoolContext(morning = true))
    }

    @Test
    fun `charging is not low battery, and low battery needs a known level`() {
        assertThat(poolContextOf(14, true, 10, false, false, cfg).lowBattery).isFalse()
        assertThat(poolContextOf(14, false, 19, false, false, cfg).lowBattery).isTrue()
        assertThat(poolContextOf(14, false, 20, false, false, cfg).lowBattery).isFalse()
        assertThat(poolContextOf(14, false, null, false, false, cfg).lowBattery).isFalse()
    }

    @Test
    fun `offline and recent touch are passed through`() {
        val ctx = poolContextOf(14, false, 80, true, true, cfg)
        assertThat(ctx.offline).isTrue()
        assertThat(ctx.recentTouch).isTrue()
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
    fun `touch at night only wakes the ball`() {
        assertThat(touchReactionsOf(BallReaction.TAP, night = true, morningDue = true))
            .containsExactly(BallReaction.WAKE)
    }

    @Test
    fun `first touch in the morning yawns and then greets`() {
        assertThat(touchReactionsOf(BallReaction.TAP, night = false, morningDue = true))
            .containsExactly(BallReaction.YAWN, BallReaction.GREET)
            .inOrder()
    }

    @Test
    fun `ordinary touch plays its own reaction`() {
        assertThat(touchReactionsOf(BallReaction.LONG_PRESS, night = false, morningDue = false))
            .containsExactly(BallReaction.LONG_PRESS)
    }

    @Test
    fun `drag has no timer and fling needs the configured speed`() {
        assertThat(reactionDurationMs(BallReaction.DRAG, cfg)).isNull()
        assertThat(isFling(1.2f, cfg)).isTrue()
        assertThat(isFling(0.5f, cfg)).isFalse()
    }
}
