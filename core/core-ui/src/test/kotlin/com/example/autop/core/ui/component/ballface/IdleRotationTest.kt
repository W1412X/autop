package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 空闲常态池：数据驱动的加权抽取。基础脸始终在池中；睡眠、充电、疲倦等只在对应数据出现时有权重。
 */
class IdleRotationTest {
    private val none = BallPoolContext()

    @Test
    fun `idle pool never spins the satellite faster than thirty seconds`() {
        BallFaceDirector.poolOf(BallFaceScene.IDLE).forEach { expression ->
            val orbit = expression.satellite as BallSatelliteMotion.Orbit
            assertThat(orbit.periodSec).isAtLeast(30f)
        }
    }

    @Test
    fun `idle pool holds no status faces`() {
        assertThat(BallFaceDirector.poolOf(BallFaceScene.IDLE)).containsNoneOf(
            BallFaceExpressions.blocked,
            BallFaceExpressions.lost,
            BallFaceExpressions.paused,
            BallFaceExpressions.waiting,
            BallFaceExpressions.failure,
            BallFaceExpressions.success,
            BallFaceExpressions.recording,
        )
    }

    @Test
    fun `base faces are always in the pool, data faces only with their data`() {
        val seen =
            (0 until 2_000)
                .map {
                    BallFaceDirector.pickIdle(seed = it.toLong(), previous = null, context = none)
                }.toSet()
        assertThat(seen).contains(BallFaceExpressions.calm)
        assertThat(seen).doesNotContain(SLEEP_EXPRESSION)
        assertThat(seen).doesNotContain(TIRED_EXPRESSION)
    }

    @Test
    fun `night makes sleep the most likely face`() {
        val night = BallPoolContext(night = true)
        val draws =
            (0 until 4_000).map {
                BallFaceDirector.pickIdle(
                    seed = it.toLong(),
                    previous = null,
                    context = night,
                )
            }
        val sleepShare = draws.count { it == SLEEP_EXPRESSION }.toFloat() / draws.size
        assertThat(sleepShare).isGreaterThan(0.45f)
        assertThat(sleepShare).isLessThan(0.65f)
    }

    @Test
    fun `a pick never repeats the previous face`() {
        val night = BallPoolContext(night = true)
        (0 until 500).forEach { seed ->
            val previous = BallFaceDirector.pickIdle(seed.toLong(), null, night)
            assertThat(BallFaceDirector.pickIdle(seed.toLong() + 1, previous, night)).isNotEqualTo(previous)
        }
    }

    @Test
    fun `idle pool holds only neutral expressions without status meaning`() {
        val pool = BallFaceDirector.poolOf(BallFaceScene.IDLE)
        assertThat(pool).containsNoDuplicates()
        assertThat(pool).hasSize(20)
    }

    @Test
    fun `idle reroll seconds stay in range and repeat for the same seed`() {
        (1..50).forEach { index ->
            val seconds = BallFaceDirector.idleRerollSeconds(seed = 7L, index = index)
            assertThat(seconds).isAtLeast(BallFaceDirector.IDLE_REROLL_MIN_SEC)
            assertThat(seconds).isAtMost(BallFaceDirector.IDLE_REROLL_MAX_SEC)
            assertThat(BallFaceDirector.idleRerollSeconds(seed = 7L, index = index)).isEqualTo(seconds)
        }
    }
}
