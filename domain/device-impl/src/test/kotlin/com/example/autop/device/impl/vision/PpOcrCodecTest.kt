package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** PP-OCR 前后处理（vp9 §4.1）：rec 裁剪/切窗/预处理、CTC 解码/字典校验。 */
internal class PpOcrCodecTest {
    @Test
    fun `ctc decode drops blanks and repeats`() {
        // 时间步类别索引：blank, a, a, b, blank → "ab"。
        val charset = listOf("a", "b", "c")
        val logits = FloatArray(5 * 4)
        val path = intArrayOf(0, 1, 1, 2, 0)
        path.forEachIndexed { t, cls -> logits[t * 4 + cls] = 1.0f }
        assertThat(PpOcrCodec.ctcDecode(logits, 5, 4, charset)).isEqualTo("ab")
    }

    @Test
    fun `rec input has fixed shape`() {
        val image = VisionImage(200, 40, IntArray(200 * 40) { 0xFF222222.toInt() })
        val input =
            PpOcrCodec.recInput(
                image,
                com.example.autop.device
                    .Rect(0, 0, 200, 40),
            )
        assertThat(input).hasLength(3 * PpOcrCodec.REC_HEIGHT * PpOcrCodec.REC_WIDTH)
    }

    @Test
    fun `rec input scales channels to unit range and pads with zeros`() {
        val white = VisionImage(200, 100, IntArray(200 * 100) { 0xFFFFFFFF.toInt() })
        val input = PpOcrCodec.recInput(white, Rect(0, 0, 200, 100))
        val plane = PpOcrCodec.REC_HEIGHT * PpOcrCodec.REC_WIDTH
        // 内容区：纯白 → x/255＝1（导出模型内置归一化，输入 0..1）；右下补零区即模型输入 0。
        assertThat(input[0]).isWithin(1e-4f).of(1.0f)
        assertThat(input[plane]).isWithin(1e-4f).of(1.0f)
        assertThat(input[2 * plane]).isWithin(1e-4f).of(1.0f)
        assertThat(input[plane - 1]).isWithin(1e-4f).of(0.0f)
    }

    @Test
    fun `rec input keeps official geometry for wide rows`() {
        // 官方口径：高恒 REC_HEIGHT、宽截断到 REC_WIDTH（训练预处理一致，勿再越分布）。
        val wide = VisionImage(1000, 50, IntArray(1000 * 50) { 0xFFFFFFFF.toInt() })
        val input = PpOcrCodec.recInput(wide, Rect(0, 0, 1000, 50))
        val plane = PpOcrCodec.REC_HEIGHT * PpOcrCodec.REC_WIDTH
        assertThat(PpOcrCodec.REC_HEIGHT).isEqualTo(48)
        assertThat(input[plane - 1]).isWithin(1e-4f).of(1.0f)
    }

    @Test
    fun `rec crop expands bounds to protect strokes`() {
        // 框外贴边的笔画（白列 x=45..49）被外扩采样进来；不外扩时最左列是框内黑。
        val pixels = IntArray(100 * 100) { 0xFF000000.toInt() }
        for (y in 0 until 100) {
            for (x in 45..49) pixels[y * 100 + x] = 0xFFFFFFFF.toInt()
        }
        val image = VisionImage(100, 100, pixels)
        val crop = PpOcrCodec.recCrop(image, Rect(50, 50, 70, 70))
        val input = PpOcrCodec.recInput(image, crop)
        assertThat(input[0]).isGreaterThan(0f)
    }

    @Test
    fun `rec windows single for normal aspect`() {
        val crop = Rect(0, 0, 300, 50)
        assertThat(PpOcrCodec.recWindows(crop)).containsExactly(crop)
    }

    @Test
    fun `rec windows cover long box with overlap and legal aspect`() {
        val crop = Rect(0, 0, 1000, 50)
        val windows = PpOcrCodec.recWindows(crop)
        assertThat(windows.size).isGreaterThan(1)
        assertThat(windows.first().l).isEqualTo(crop.l)
        assertThat(windows.last().r).isEqualTo(crop.r)
        for (w in windows) {
            assertThat(w.width.toDouble() / w.height).isAtMost(PpOcrCodec.REC_MAX_ASPECT)
            assertThat(w.l).isAtLeast(crop.l)
            assertThat(w.r).isAtMost(crop.r)
        }
        for (i in 1 until windows.size) {
            assertThat(windows[i].l).isLessThan(windows[i - 1].r)
        }
    }

    @Test
    fun `charset match requires one blank class beyond charset`() {
        val charset = List(18384) { "字" }
        assertThat(PpOcrCodec.charsetMatches(18385, charset)).isTrue()
        assertThat(PpOcrCodec.charsetMatches(18386, charset)).isFalse()
        assertThat(PpOcrCodec.charsetMatches(18384, charset)).isFalse()
    }
}
