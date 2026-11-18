package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FloatingZTest {
    private val style = BallZStyle(count = 2, intervalSec = 2f)

    @Test
    fun `a new z starts small and invisible, then grows as it rises`() {
        // t = 0：第一个 z 刚出生（透明），第二个 z 已经过了一个间隔，位于中段。
        val zs = floatingZsOf(0f, style)
        assertThat(zs).hasSize(1)
        assertThat(zs.single().size).isWithin(1e-4f).of(165f)
        assertThat(zs.single().alpha).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `two z are visible at once, smaller one listed first`() {
        val zs = floatingZsOf(3f, style)
        assertThat(zs).hasSize(2)
        assertThat(zs.map { it.size }).containsExactly(127.5f, 202.5f).inOrder()
    }

    @Test
    fun `three z spread over three intervals`() {
        // t = 3.5：三个 z 都已离开出生点，全部可见。
        val zs = floatingZsOf(3.5f, BallZStyle(count = 3, intervalSec = 1f))
        assertThat(zs).hasSize(3)
    }

    @Test
    fun `size stays between 90 and 240 and alpha between 0 and 1 over time`() {
        var t = 0f
        while (t < 20f) {
            floatingZsOf(t, style).forEach { z ->
                assertThat(z.size).isAtLeast(90f)
                assertThat(z.size).isAtMost(240f)
                assertThat(z.alpha).isAtLeast(0.01f)
                assertThat(z.alpha).isAtMost(1f)
            }
            t += 0.05f
        }
    }
}
