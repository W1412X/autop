package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BallFaceDirectorTest {
    @Test
    fun `pool sizes follow the design`() {
        assertThat(BallFaceDirector.poolOf(BallFaceScene.STARTING)).hasSize(3)
        assertThat(BallFaceDirector.poolOf(BallFaceScene.SAMPLING)).hasSize(6)
        assertThat(BallFaceDirector.poolOf(BallFaceScene.GESTURE)).hasSize(3)
        assertThat(BallFaceDirector.poolOf(BallFaceScene.TEXT)).hasSize(3)
        assertThat(BallFaceDirector.poolOf(BallFaceScene.VISION)).hasSize(3)
        assertThat(BallFaceDirector.poolOf(BallFaceScene.APP)).hasSize(3)
        assertThat(BallFaceDirector.poolOf(BallFaceScene.COMPACTING)).hasSize(2)
    }

    @Test
    fun `pick is deterministic for the same seed`() {
        val a = BallFaceDirector.pick(BallFaceScene.SAMPLING, seed = 42L)
        val b = BallFaceDirector.pick(BallFaceScene.SAMPLING, seed = 42L)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `pick always stays inside the scene pool`() {
        for (scene in BallFaceScene.entries) {
            val pool = BallFaceDirector.poolOf(scene)
            for (seed in 0L until 200L) {
                assertThat(pool).contains(BallFaceDirector.pick(scene, seed))
            }
        }
    }

    @Test
    fun `consecutive picks never repeat the previous face`() {
        for (scene in BallFaceScene.entries) {
            var previous: BallFaceExpression? = null
            for (seed in 0L until 300L) {
                val next = BallFaceDirector.pick(scene, seed, previous)
                if (previous != null && BallFaceDirector.poolOf(scene).size > 1) {
                    assertThat(next).isNotEqualTo(previous)
                }
                previous = next
            }
        }
    }

    @Test
    fun `pick covers every face in a pool over many seeds`() {
        val pool = BallFaceDirector.poolOf(BallFaceScene.SAMPLING)
        val seen = (0L until 500L).map { BallFaceDirector.pick(BallFaceScene.SAMPLING, it) }.toSet()
        assertThat(seen).containsExactlyElementsIn(pool)
    }

    @Test
    fun `event seed is stable per run and step`() {
        assertThat(BallFaceDirector.seedOfEvent("run-1", 3)).isEqualTo(BallFaceDirector.seedOfEvent("run-1", 3))
        assertThat(BallFaceDirector.seedOfEvent("run-1", 3)).isNotEqualTo(BallFaceDirector.seedOfEvent("run-1", 4))
        assertThat(BallFaceDirector.seedOfEvent("run-1", 3)).isNotEqualTo(BallFaceDirector.seedOfEvent("run-2", 3))
    }

    @Test
    fun `mixed pool holds every performance face once`() {
        val mixed = BallFaceDirector.poolOf(BallFaceScene.MIXED)
        val performance =
            BallFaceScene.entries.filter { it != BallFaceScene.MIXED && it != BallFaceScene.IDLE }.flatMap {
                BallFaceDirector.poolOf(
                    it,
                )
            }
        assertThat(mixed).containsExactlyElementsIn(performance)
        assertThat(mixed).hasSize(23)
    }

    @Test
    fun `reroll changes the seed within one event`() {
        val base = BallFaceDirector.seedOfEvent("run-1", 3)
        assertThat(BallFaceDirector.seedOf(base, 1)).isNotEqualTo(BallFaceDirector.seedOf(base, 2))
    }
}
