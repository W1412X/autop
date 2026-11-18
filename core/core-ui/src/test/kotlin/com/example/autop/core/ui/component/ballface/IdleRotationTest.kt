/**
 * 空闲表情轮换（方案预览 ball-face-motion）：池子只含中性表情，间隔在 5–10 秒之间且可复现。
 */
package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdleRotationTest {
    @Test
    fun `idle pool holds only neutral expressions without status meaning`() {
        val pool = BallFaceDirector.poolOf(BallFaceScene.IDLE)
        assertThat(pool).hasSize(13)
        assertThat(pool).containsNoDuplicates()
        assertThat(pool).containsNoneOf(
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
    fun `idle intervals stay within five to ten seconds`() {
        for (index in 1..200) {
            val seconds = BallFaceDirector.idleRerollSeconds(seed = 42L, index = index)
            assertThat(seconds).isAtLeast(BallFaceDirector.IDLE_REROLL_MIN_SEC)
            assertThat(seconds).isAtMost(BallFaceDirector.IDLE_REROLL_MAX_SEC)
        }
    }

    @Test
    fun `idle intervals are reproducible for the same seed`() {
        val first = (1..10).map { BallFaceDirector.idleRerollSeconds(7L, it) }
        val second = (1..10).map { BallFaceDirector.idleRerollSeconds(7L, it) }
        assertThat(first).isEqualTo(second)
    }
}
