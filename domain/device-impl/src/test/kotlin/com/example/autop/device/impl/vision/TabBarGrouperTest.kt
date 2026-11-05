package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 标签栏几何识别（vp8 §8.2，ADR-0008 决策 7）：边缘带内同行 ≥3 个尺寸相近的 item/button/icon。 */
internal class TabBarGrouperTest {
    private fun cand(
        category: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = TabCandidate(category, Rect(l, t, r, b))

    private val bottomRow =
        listOf(
            cand(VisualCategory.ITEM, 0, 940, 100, 1000),
            cand(VisualCategory.BUTTON, 110, 940, 210, 1000),
            cand(VisualCategory.ICON, 220, 940, 320, 1000),
        )

    @Test
    fun `bottom row of three similar candidates is grouped in x order`() {
        val groups = TabBarGrouper.group(bottomRow, 1000)
        assertThat(groups).containsExactly(listOf(0, 1, 2))
    }

    @Test
    fun `top row also qualifies`() {
        val top =
            listOf(
                cand(VisualCategory.ICON, 0, 10, 100, 70),
                cand(VisualCategory.ICON, 110, 10, 210, 70),
                cand(VisualCategory.ICON, 220, 10, 320, 70),
            )
        assertThat(TabBarGrouper.group(top, 1000)).containsExactly(listOf(0, 1, 2))
    }

    @Test
    fun `mid screen row is not a tab bar`() {
        val mid =
            listOf(
                cand(VisualCategory.ITEM, 0, 400, 100, 460),
                cand(VisualCategory.ITEM, 110, 400, 210, 460),
                cand(VisualCategory.ITEM, 220, 400, 320, 460),
            )
        assertThat(TabBarGrouper.group(mid, 1000)).isEmpty()
    }

    @Test
    fun `width spread beyond tolerance is not grouped`() {
        val uneven =
            listOf(
                cand(VisualCategory.ITEM, 0, 940, 100, 1000),
                cand(VisualCategory.ITEM, 110, 940, 210, 1000),
                cand(VisualCategory.ITEM, 220, 940, 420, 1000),
            )
        assertThat(TabBarGrouper.group(uneven, 1000)).isEmpty()
    }

    @Test
    fun `fewer than three candidates is not grouped`() {
        val two = bottomRow.take(2)
        assertThat(TabBarGrouper.group(two, 1000)).isEmpty()
    }

    @Test
    fun `non candidate categories are ignored`() {
        val texts =
            listOf(
                cand(VisualCategory.TEXT, 0, 940, 100, 1000),
                cand(VisualCategory.TEXT, 110, 940, 210, 1000),
                cand(VisualCategory.TEXT, 220, 940, 320, 1000),
            )
        assertThat(TabBarGrouper.group(texts, 1000)).isEmpty()
    }
}
