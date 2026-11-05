package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** YOLO 前后处理：letterbox 还原映射、解码截断、NMS�?*/
internal class YoloDecoderTest {
    private fun image(
        w: Int,
        h: Int,
        color: Int = 0xFF808080.toInt(),
    ) = VisionImage(w, h, IntArray(w * h) { color })

    private fun identityInput(): YoloInput = YoloInput(FloatArray(0), 1.0, 0, 0)

    @Test
    fun `letterbox scales long side and pads short side`() {
        val input = YoloDecoder.letterbox(image(100, 50), 640)
        assertThat(input.chw).hasLength(3 * 640 * 640)
        assertThat(input.scale).isWithin(1e-6).of(6.4)
        assertThat(input.padX).isEqualTo(0)
        assertThat(input.padY).isEqualTo(160)
    }

    @Test
    fun `decode maps xywh back to original coordinates`() {
        val raw = floatArrayOf(50f, 25f, 20f, 10f, 0.9f)
        val dets = YoloDecoder.decode(raw, 1, identityInput(), 0.35, image(100, 100))
        assertThat(dets).hasSize(1)
        assertThat(dets.single().bounds).isEqualTo(Rect(40, 20, 60, 30))
        assertThat(dets.single().confidence).isWithin(1e-6).of(0.9)
    }

    @Test
    fun `decode drops below threshold and keeps best of overlaps`() {
        // 通道主序 [cx,cy,w,h,score]×3 锚：�?0/1 同位（NMS 合并留高分）＋锚 2 低分（截断）�?
        val raw =
            floatArrayOf(
                50f,
                51f,
                20f,
                50f,
                50f,
                20f,
                10f,
                10f,
                10f,
                10f,
                10f,
                10f,
                0.9f,
                0.8f,
                0.1f,
            )
        val dets = YoloDecoder.decode(raw, 3, identityInput(), 0.35, image(100, 100))
        assertThat(dets).hasSize(1)
        assertThat(dets.single().confidence).isWithin(1e-6).of(0.9)
    }

    @Test
    fun `decode takes best class score for multi class output`() {
        // 2 类输出（4+2 通道）：�?1 得分更高�?
        val raw = floatArrayOf(50f, 25f, 20f, 10f, 0.2f, 0.7f)
        val dets = YoloDecoder.decode(raw, 1, identityInput(), 0.35, image(100, 100))
        assertThat(dets.single().confidence).isWithin(1e-6).of(0.7)
    }

    @Test
    fun `decode maps argmax class through metadata names`() {
        // v2 多类：类别名表来自 ONNX metadata，argmax 类别即输出类别（vp8 §8.2）。
        val raw = floatArrayOf(50f, 25f, 20f, 10f, 0.2f, 0.7f)
        val dets =
            YoloDecoder.decode(
                raw,
                1,
                identityInput(),
                0.35,
                image(100, 100),
                listOf("text", "button"),
            )
        assertThat(dets.single().category).isEqualTo("button")
    }

    @Test
    fun `nms keeps highest score of overlapping boxes`() {
        val a = VisualDetection(VisualCategory.ICON_CLICKABLE, 0.9, Rect(0, 0, 50, 50))
        val b = VisualDetection(VisualCategory.ICON_CLICKABLE, 0.8, Rect(5, 5, 55, 55))
        val c = VisualDetection(VisualCategory.ICON_CLICKABLE, 0.7, Rect(200, 200, 250, 250))
        val kept = YoloDecoder.nms(listOf(a, b, c))
        assertThat(kept).containsExactly(a, c)
    }

    @Test
    fun `nms only suppresses overlapping boxes of the same class`() {
        // 类别感知 NMS：重叠的不同类框都保留（vp8 §8.2）。
        val icon = VisualDetection("icon", 0.9, Rect(0, 0, 50, 50))
        val iconLow = VisualDetection("icon", 0.8, Rect(5, 5, 55, 55))
        val button = VisualDetection("button", 0.7, Rect(5, 5, 55, 55))
        val kept = YoloDecoder.nms(listOf(icon, iconLow, button))
        assertThat(kept).containsExactly(icon, button)
    }
}
