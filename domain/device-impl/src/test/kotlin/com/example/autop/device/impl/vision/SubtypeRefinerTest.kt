package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 单类检测结果的子类型细化（设计稿 §6.1）：几何/文字覆盖/实心度判据。 */
internal class SubtypeRefinerTest {
    private fun det(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualDetection(VisualCategory.ICON_CLICKABLE, 0.9, Rect(l, t, r, b))

    private fun ocr(
        text: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualOcrLine(text, 0.95, Rect(l, t, r, b))

    @Test
    fun `compact pill without text refines to switch`() {
        val pill = det(900, 500, 1020, 560)
        val result = SubtypeRefiner.refine(listOf(pill), emptyList(), 1080, 2400) { 0.0 }
        assertThat(result.single().category).isEqualTo(VisualCategory.SWITCH)
    }

    @Test
    fun `text leaf with dense ocr refines to text label`() {
        val block = det(100, 100, 500, 160)
        val line = ocr("设置", 120, 110, 480, 150)
        val result = SubtypeRefiner.refine(listOf(block), listOf(line), 1080, 2400) { 0.0 }
        assertThat(result.single().category).isEqualTo(VisualCategory.TEXT_LABEL)
    }

    @Test
    fun `wide thin box with sparse text refines to input field`() {
        val field = det(50, 300, 1030, 380)
        val line = ocr("搜索", 60, 310, 200, 350)
        val result = SubtypeRefiner.refine(listOf(field), listOf(line), 1080, 2400) { 0.1 }
        assertThat(result.single().category).isEqualTo(VisualCategory.INPUT_FIELD)
    }

    @Test
    fun `large row with sub block refines to list item`() {
        val row = det(0, 600, 1080, 900)
        val icon = det(40, 640, 140, 740)
        val line = ocr("WLAN", 200, 700, 600, 760)
        val result = SubtypeRefiner.refine(listOf(row, icon), listOf(line), 1080, 2400) { 0.0 }
        assertThat(result.first().category).isEqualTo(VisualCategory.LIST_ITEM)
    }

    @Test
    fun `medium box with moderate text refines to button`() {
        val button = det(300, 2000, 780, 2120)
        val line = ocr("恢复默认设置", 350, 2030, 650, 2090)
        val result = SubtypeRefiner.refine(listOf(button), listOf(line), 1080, 2400) { 0.8 }
        assertThat(result.single().category).isEqualTo(VisualCategory.BUTTON)
    }

    @Test
    fun `group of text leaves refines to text label`() {
        val group = det(0, 1000, 600, 1100)
        val left = det(10, 1010, 300, 1080)
        val right = det(310, 1010, 560, 1080)
        val lineL = ocr("上午", 20, 1020, 290, 1070)
        val lineR = ocr("9:41", 320, 1020, 550, 1070)
        val result = SubtypeRefiner.refine(listOf(group, left, right), listOf(lineL, lineR), 1080, 2400) { 0.0 }
        assertThat(result.first().category).isEqualTo(VisualCategory.TEXT_LABEL)
    }

    @Test
    fun `bare box without text refines to icon clickable`() {
        val icon = det(80, 80, 160, 160)
        val result = SubtypeRefiner.refine(listOf(icon), emptyList(), 1080, 2400) { 0.3 }
        assertThat(result.single().category).isEqualTo(VisualCategory.ICON_CLICKABLE)
    }
}
