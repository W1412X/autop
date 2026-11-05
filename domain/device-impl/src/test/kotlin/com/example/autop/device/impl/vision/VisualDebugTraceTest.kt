/**
 * 视觉链路调试数据（N208）：逐框阅读序编号/OCR 归属/状态三态与识别 I/O 说明文案，供调试图渲染核对。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal class VisualDebugTraceTest {
    private fun det(
        category: String,
        conf: Double,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = VisualDetection(category, conf, Rect(l, t, r, b), checked = null)

    @Test
    fun `boxes numbered in reading order top then left`() {
        val topRight = det("text", 0.9, 60, 10, 100, 40)
        val bottomLeft = det("button", 0.8, 10, 50, 50, 90)
        val boxes = boxesOf(listOf(bottomLeft, topRight), emptyList())
        assertThat(boxes.map { it.index }).containsExactly(1, 2).inOrder()
        assertThat(boxes.map { it.category }).containsExactly("text", "button").inOrder()
    }

    @Test
    fun `ocr text goes to smallest containing box`() {
        val outer = det("container", 0.9, 0, 0, 100, 100)
        val inner = det("button", 0.9, 20, 20, 80, 60)
        val line = VisualOcrLine("确定", 0.95, Rect(30, 30, 70, 50))
        val boxes = boxesOf(listOf(outer, inner), listOf(line))
        assertThat(boxes.first { it.category == "button" }.text).isEqualTo("确定")
        assertThat(boxes.first { it.category == "container" }.text).isNull()
    }

    @Test
    fun `caption shows recognition input and output`() {
        val caption = captionOf(VisualDebugBox(3, "switch", 0.92, Rect(0, 0, 10, 10), "开启", true))
        assertThat(caption).contains("#3 switch")
        assertThat(caption).contains("输入=框内裁剪")
        assertThat(caption).contains("「开启」")
        assertThat(caption).contains("状态=on")
    }

    @Test
    fun `state tri-state text`() {
        assertThat(stateOf(true)).isEqualTo("on")
        assertThat(stateOf(false)).isEqualTo("off")
        assertThat(stateOf(null)).isEqualTo("未知")
    }

    @Test
    fun `detection caption counts boxes and ocr lines`() {
        val caption = detectionCaptionOf(5, 3)
        assertThat(caption).contains("5 个检测框")
        assertThat(caption).contains("3 条 OCR 行")
    }
}
