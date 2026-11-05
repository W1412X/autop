package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 原子类吸收（vp9 §3.1）：icon→iconRegion、text→删除、其他→优先级保留高者、非原子不吸收。 */
internal class AtomicAbsorberTest {
    private fun det(
        category: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualDetection(category, 0.9, Rect(l, t, r, b))

    @Test
    fun `icon child of atomic box is absorbed into icon region`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 200, 100)
        val icon = det(VisualCategory.ICON, 10, 10, 60, 60)
        val result = AtomicAbsorber.absorb(listOf(button, icon))
        assertThat(result).hasSize(1)
        assertThat(result.single().detection.category).isEqualTo(VisualCategory.BUTTON)
        assertThat(result.single().iconRegion).isEqualTo(Rect(10, 10, 60, 60))
    }

    @Test
    fun `text child of atomic box is dropped without icon region`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 200, 100)
        val text = det(VisualCategory.TEXT, 20, 30, 180, 70)
        val result = AtomicAbsorber.absorb(listOf(button, text))
        assertThat(result).hasSize(1)
        assertThat(result.single().detection.category).isEqualTo(VisualCategory.BUTTON)
        assertThat(result.single().iconRegion).isNull()
    }

    @Test
    fun `higher priority child survives and absorbs host`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 300, 120)
        val input = det(VisualCategory.INPUT, 20, 20, 280, 100)
        val result = AtomicAbsorber.absorb(listOf(button, input))
        assertThat(result).hasSize(1)
        assertThat(result.single().detection.category).isEqualTo(VisualCategory.INPUT)
    }

    @Test
    fun `equal priority keeps host and drops child`() {
        val outer = det(VisualCategory.BUTTON, 0, 0, 300, 120)
        val inner = det(VisualCategory.BUTTON, 20, 20, 280, 100)
        val result = AtomicAbsorber.absorb(listOf(outer, inner))
        assertThat(result).hasSize(1)
        assertThat(result.single().detection.bounds).isEqualTo(Rect(0, 0, 300, 120))
    }

    @Test
    fun `icon child of non atomic box is kept`() {
        val item = det(VisualCategory.ITEM, 0, 0, 300, 120)
        val icon = det(VisualCategory.ICON, 20, 20, 80, 80)
        val result = AtomicAbsorber.absorb(listOf(item, icon))
        assertThat(result).hasSize(2)
        assertThat(result[1].iconRegion).isNull()
    }

    @Test
    fun `largest icon region wins when multiple icons absorbed`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 300, 120)
        val small = det(VisualCategory.ICON, 10, 10, 50, 50)
        val big = det(VisualCategory.ICON, 60, 10, 160, 110)
        val result = AtomicAbsorber.absorb(listOf(button, small, big))
        assertThat(result).hasSize(1)
        assertThat(result.single().iconRegion).isEqualTo(Rect(60, 10, 160, 110))
    }

    @Test
    fun `nested icon region transfers to final host`() {
        val button = det(VisualCategory.BUTTON, 0, 0, 300, 120)
        val outerIcon = det(VisualCategory.ICON, 10, 10, 200, 110)
        val innerIcon = det(VisualCategory.ICON, 20, 20, 60, 60)
        val result = AtomicAbsorber.absorb(listOf(button, outerIcon, innerIcon))
        assertThat(result).hasSize(1)
        val regions = result.single().iconRegion
        assertThat(regions).isEqualTo(Rect(10, 10, 200, 110))
    }

    @Test
    fun `priority orders input above text`() {
        assertThat(AtomicAbsorber.priorityOf(VisualCategory.INPUT))
            .isGreaterThan(AtomicAbsorber.priorityOf(VisualCategory.SWITCH))
        assertThat(AtomicAbsorber.priorityOf(VisualCategory.SWITCH))
            .isGreaterThan(AtomicAbsorber.priorityOf(VisualCategory.SLIDER))
        assertThat(AtomicAbsorber.priorityOf(VisualCategory.BUTTON))
            .isGreaterThan(AtomicAbsorber.priorityOf(VisualCategory.ICON))
        assertThat(AtomicAbsorber.priorityOf(VisualCategory.ICON))
            .isGreaterThan(AtomicAbsorber.priorityOf(VisualCategory.TEXT))
    }
}
