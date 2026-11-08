package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** PP-OCR 前后处理（vp9 §4.1）：det 尺寸/连通域框/box_thresh、v5 预处理（BGR＋ImageNet/rec）、CTC/字典校验。 */
internal class PpOcrCodecTest {
    @Test
    fun `det size keeps aspect and aligns to 32`() {
        val (w, h) = PpOcrCodec.detSize(1080, 2400)
        assertThat(w % 32).isEqualTo(0)
        assertThat(h % 32).isEqualTo(0)
        assertThat(h).isEqualTo(960)
    }

    @Test
    fun `det boxes find bright blob and scale back`() {
        val mapW = 64
        val mapH = 64
        val prob = FloatArray(mapW * mapH)
        for (y in 8..15) {
            for (x in 10..29) {
                prob[y * mapW + x] = 1.0f
            }
        }
        val boxes = PpOcrCodec.detBoxes(PpOcrCodec.DetMap(prob, mapW, mapH, 2.0))
        assertThat(boxes).hasSize(1)
        val bounds = boxes.single().bounds
        assertThat(bounds.l).isEqualTo((10 - 2) * 2)
        assertThat(bounds.t).isEqualTo((8 - 2) * 2)
        assertThat(bounds.r).isEqualTo((29 + 1 + 2) * 2)
        assertThat(bounds.b).isEqualTo((15 + 1 + 2) * 2)
    }

    @Test
    fun `det boxes ignore tiny noise`() {
        val prob = FloatArray(64 * 64)
        prob[5 * 64 + 5] = 1.0f
        prob[5 * 64 + 6] = 1.0f
        assertThat(PpOcrCodec.detBoxes(PpOcrCodec.DetMap(prob, 64, 64, 1.0))).isEmpty()
    }

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
    fun `det input keeps blue channel first and red last`() {
        val red = VisionImage(1, 1, intArrayOf(0xFFFF0000.toInt()))
        val chw = PpOcrCodec.detInput(red, 1, 1)
        // 纯红：R 在最后通道、归一化后最大；B 在最前通道、值最小。
        assertThat(chw[2]).isGreaterThan(chw[0])
    }

    @Test
    fun `det input normalizes with imagenet stats`() {
        val black = VisionImage(1, 1, intArrayOf(0xFF000000.toInt()))
        val chw = PpOcrCodec.detInput(black, 1, 1)
        assertThat(chw[0]).isWithin(1e-4f).of(-0.485f / 0.229f)
        assertThat(chw[1]).isWithin(1e-4f).of(-0.456f / 0.224f)
        assertThat(chw[2]).isWithin(1e-4f).of(-0.406f / 0.225f)
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
    fun `rec input expands bounds to protect strokes`() {
        // 框外贴边的笔画（白列 x=45..49）被外扩采样进来；不外扩时最左列是框内黑。
        val pixels = IntArray(100 * 100) { 0xFF000000.toInt() }
        for (y in 0 until 100) {
            for (x in 45..49) pixels[y * 100 + x] = 0xFFFFFFFF.toInt()
        }
        val image = VisionImage(100, 100, pixels)
        val input = PpOcrCodec.recInput(image, Rect(50, 50, 70, 70))
        assertThat(input[0]).isGreaterThan(0f)
    }

    @Test
    fun `det boxes drop blob below box thresh`() {
        val mapW = 64
        val mapH = 64
        val low = FloatArray(mapW * mapH)
        for (y in 8..15) {
            for (x in 10..29) {
                low[y * mapW + x] = 0.55f
            }
        }
        assertThat(PpOcrCodec.detBoxes(PpOcrCodec.DetMap(low, mapW, mapH, 1.0))).isEmpty()
        val high = FloatArray(mapW * mapH)
        for (y in 8..15) {
            for (x in 10..29) {
                high[y * mapW + x] = 0.7f
            }
        }
        assertThat(PpOcrCodec.detBoxes(PpOcrCodec.DetMap(high, mapW, mapH, 1.0))).hasSize(1)
    }

    @Test
    fun `charset match requires one blank class beyond charset`() {
        val charset = List(18384) { "字" }
        assertThat(PpOcrCodec.charsetMatches(18385, charset)).isTrue()
        assertThat(PpOcrCodec.charsetMatches(18386, charset)).isFalse()
        assertThat(PpOcrCodec.charsetMatches(18384, charset)).isFalse()
    }
}
