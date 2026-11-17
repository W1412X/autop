package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceDwellTest {
    private var now = 10_000L
    private val dwell = FaceDwell(nowMs = { now })

    private val starting = heldFaceOf(BallFaceMood.RUNNING_START, BallFaceScene.STARTING, 7L)
    private val sampling = heldFaceOf(BallFaceMood.RUNNING, BallFaceScene.SAMPLING, 7L)
    private val blocked = heldFaceOf(BallFaceMood.BLOCKED, BallFaceScene.SAMPLING, 0L)
    private val idle = heldFaceOf(BallFaceMood.IDLE, BallFaceScene.SAMPLING, 0L)

    @Test
    fun `first face shows immediately`() {
        assertThat(dwell.waitMs(idle, HOLD)).isEqualTo(0L)
    }

    @Test
    fun `a new face waits until the hold has passed`() {
        dwell.onShown(starting)
        now += 1_000L
        assertThat(dwell.waitMs(sampling, HOLD)).isEqualTo(1_500L)
    }

    @Test
    fun `no wait once the hold has passed`() {
        dwell.onShown(starting)
        now += HOLD
        assertThat(dwell.waitMs(sampling, HOLD)).isEqualTo(0L)
    }

    @Test
    fun `a zero hold never waits`() {
        dwell.onShown(starting)
        assertThat(dwell.waitMs(sampling, 0L)).isEqualTo(0L)
    }

    @Test
    fun `urgent face shows immediately even inside the hold`() {
        dwell.onShown(starting)
        now += 500L
        assertThat(dwell.waitMs(blocked, HOLD)).isEqualTo(0L)
    }

    @Test
    fun `leaving an urgent face still holds for the minimum time`() {
        dwell.onShown(blocked)
        now += 500L
        assertThat(dwell.waitMs(idle, HOLD)).isEqualTo(2_000L)
    }

    @Test
    fun `same face does not wait and does not restart the hold`() {
        dwell.onShown(sampling)
        now += 1_000L
        assertThat(dwell.waitMs(sampling, HOLD)).isEqualTo(0L)
        dwell.onShown(sampling)
        now += 1_000L
        assertThat(dwell.waitMs(idle, HOLD)).isEqualTo(500L)
    }

    @Test
    fun `a newly shown face restarts the hold from that moment`() {
        dwell.onShown(starting)
        now += HOLD
        dwell.onShown(sampling)
        now += 1_000L
        assertThat(dwell.waitMs(idle, HOLD)).isEqualTo(1_500L)
    }

    @Test
    fun `fixed faces ignore scene and seed`() {
        assertThat(heldFaceOf(BallFaceMood.IDLE, BallFaceScene.SAMPLING, 1L))
            .isEqualTo(heldFaceOf(BallFaceMood.IDLE, BallFaceScene.GESTURE, 9L))
    }

    private companion object {
        const val HOLD: Long = 2_500L
    }
}
