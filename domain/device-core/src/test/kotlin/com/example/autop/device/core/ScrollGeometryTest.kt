package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollGeometryTest {
    private val params =
        ScrollGeometryParams(
            stepRatio = 0.5,
            edgeInsetRatio = 0.1,
            pixelsPerMs = 1.5,
            minDurationMs = 250,
            maxDurationMs = 900,
        )

    @Test
    fun `finger up moves from lower to upper half inside the container`() {
        val container = Rect(0, 400, 1000, 1400)

        val step = ScrollGeometry.step(container, Point(500, 900), ScrollDirection.UP, params)

        assertThat(step.from).isEqualTo(Point(500, 1150))
        assertThat(step.to).isEqualTo(Point(500, 650))
    }

    @Test
    fun `both end points stay inside the inset container even for a small container`() {
        val container = Rect(0, 1000, 1000, 1200)

        val step = ScrollGeometry.step(container, Point(500, 1100), ScrollDirection.DOWN, params)

        listOf(step.from, step.to).forEach {
            assertThat(it.y).isAtLeast(container.t + 20)
            assertThat(it.y).isAtMost(container.b - 1 - 20)
        }
    }

    @Test
    fun `step distance never exceeds the usable span`() {
        val big = params.copy(stepRatio = 1.0)

        val step = ScrollGeometry.step(Rect(0, 0, 1000, 1000), Point(500, 500), ScrollDirection.UP, big)

        assertThat(step.from.y - step.to.y).isAtMost(800)
    }

    @Test
    fun `horizontal scroll keeps the anchor row and moves along x`() {
        val container = Rect(0, 500, 1000, 700)

        val step = ScrollGeometry.step(container, Point(500, 600), ScrollDirection.LEFT, params)

        assertThat(step.from.y).isEqualTo(600)
        assertThat(step.to.y).isEqualTo(600)
        assertThat(step.from.x).isGreaterThan(step.to.x)
    }

    @Test
    fun `cross axis anchor is clamped into the container`() {
        val step = ScrollGeometry.step(Rect(100, 0, 900, 1000), Point(5, 500), ScrollDirection.UP, params)

        assertThat(step.from.x).isAtLeast(100)
    }

    @Test
    fun `duration follows distance and is clamped`() {
        val slow = params.copy(pixelsPerMs = 0.01)

        val step = ScrollGeometry.step(Rect(0, 0, 1000, 1000), Point(500, 500), ScrollDirection.UP, slow)

        assertThat(step.durationMs).isEqualTo(900)
    }

    @Test
    fun `intersection falls back to the first rect when disjoint`() {
        val a = Rect(0, 0, 100, 100)

        assertThat(ScrollGeometry.intersectOrSelf(a, Rect(200, 200, 300, 300))).isEqualTo(a)
        assertThat(ScrollGeometry.intersectOrSelf(a, Rect(50, 50, 300, 300))).isEqualTo(Rect(50, 50, 100, 100))
    }
}
