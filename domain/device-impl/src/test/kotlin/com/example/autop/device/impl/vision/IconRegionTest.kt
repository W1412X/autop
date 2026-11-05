package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 图标裁图区域（vp9 §5.2）：iconRegion 优先、无文字整框、文字列投影、过窄不推理。 */
internal class IconRegionTest {
    @Test
    fun `icon region wins over whole box`() {
        val out = IconRegion.cropRegion(Rect(0, 0, 100, 50), Rect(10, 10, 40, 40), listOf(Rect(0, 0, 100, 50)), 0.6)
        assertThat(out).isEqualTo(Rect(10, 10, 40, 40))
    }

    @Test
    fun `box without text uses whole box`() {
        val out = IconRegion.cropRegion(Rect(0, 0, 100, 50), null, emptyList(), 0.6)
        assertThat(out).isEqualTo(Rect(0, 0, 100, 50))
    }

    @Test
    fun `text columns are excluded keeping widest free band`() {
        val text = Rect(0, 0, 40, 50)
        val out = IconRegion.cropRegion(Rect(0, 0, 100, 50), null, listOf(text), 0.6)
        assertThat(out).isEqualTo(Rect(40, 0, 100, 50))
    }

    @Test
    fun `narrow free band below min icon side is skipped`() {
        val text = Rect(0, 0, 90, 50)
        val out = IconRegion.cropRegion(Rect(0, 0, 100, 50), null, listOf(text), 0.6)
        assertThat(out).isNull()
    }

    @Test
    fun `two text lines leave middle gap as free band`() {
        val lines = listOf(Rect(0, 0, 30, 20), Rect(70, 30, 100, 50))
        val out = IconRegion.cropRegion(Rect(0, 0, 100, 50), null, lines, 0.6)
        assertThat(out).isEqualTo(Rect(30, 0, 70, 50))
    }
}
