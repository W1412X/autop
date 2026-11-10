/**
 * 触摸过滤纯函数单测（questions.md N139：自家窗口矩形排除与自身动作窗口）。
 */
package com.example.autop.device.impl.touch

import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [insideAny] 与 [insideSelfWindow] 的边界行为。 */
public class TouchFilterTest {
    private fun spec(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): OverlaySpec =
        OverlaySpec(
            x = x,
            y = y,
            width = w,
            height = h,
            touchable = true,
            focusable = false,
            gravity = OverlayGravity.TOP_LEFT,
        )

    @Test
    public fun `point inside window is excluded`() {
        assertTrue(insideAny(listOf(spec(100, 200, 52, 52)), 120, 220))
    }

    @Test
    public fun `point on left top edge is inside but right bottom edge is outside`() {
        val bounds = listOf(spec(100, 200, 52, 52))
        assertTrue(insideAny(bounds, 100, 200))
        assertFalse(insideAny(bounds, 152, 252))
    }

    @Test
    public fun `point outside all windows is a user touch`() {
        assertFalse(insideAny(listOf(spec(100, 200, 52, 52), spec(0, 0, 300, 400)), 900, 900))
    }

    @Test
    public fun `zero sized window never contains`() {
        assertFalse(insideAny(listOf(spec(100, 200, 0, 0)), 100, 200))
    }

    @Test
    public fun `self window is closed interval`() {
        val windows = listOf(100L..200L)
        assertTrue(insideSelfWindow(windows, 100))
        assertTrue(insideSelfWindow(windows, 150))
        assertTrue(insideSelfWindow(windows, 200))
        assertFalse(insideSelfWindow(windows, 201))
        assertFalse(insideSelfWindow(windows, 99))
    }
}
